package sk.freemap.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.IOException

/**
 * Loopback-only HTTP API over the recording: catch-up, live tail, and start/stop control.
 *
 * It runs in the app process, which the foreground service keeps alive, so it stays reachable with
 * the screen off for as long as a recording is in progress.
 */
class TrackerApi private constructor(context: Context) : NanoHTTPD(HOST, PORT) {

    private val app = context.applicationContext
    private val store = PointStore.get(app)

    /**
     * NanoHTTPD gzips any textual mime type whenever the client offers it, which would sit a buffering
     * compressor in front of the SSE tail and defeat the whole point. The API only ever talks to
     * loopback, so compression buys nothing anywhere.
     */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            Log.e(TAG, "request failed: ${session.method} ${session.uri}", e)
            json(Response.Status.INTERNAL_ERROR, """{"error":"internal"}""")
        }
        return cors(response, preflight = session.method == Method.OPTIONS)
    }

    private fun route(session: IHTTPSession): Response {
        val method = session.method
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, null, null)
        }
        return when (session.uri) {
            "/track" -> if (method == Method.GET) track(session) else notAllowed()
            "/stream" -> if (method == Method.GET) stream(session) else notAllowed()
            "/status" -> if (method == Method.GET) json(Response.Status.OK, statusJson()) else notAllowed()
            "/start" -> if (method == Method.POST) startRecording() else notAllowed()
            "/stop" -> if (method == Method.POST) stopRecording() else notAllowed()
            else -> json(Response.Status.NOT_FOUND, """{"error":"no such endpoint"}""")
        }
    }

    // --- endpoints ---------------------------------------------------------------------------

    /** Cold-open catch-up: the whole track, or everything after `?since=`. */
    private fun track(session: IHTTPSession): Response {
        val since = longParam(session, "since") ?: 0L
        val sb = StringBuilder(4096)
        sb.append("{\"fields\":").append(Point.FIELDS_JSON).append(",\"points\":[")
        var written = 0
        store.forEachSince(since) {
            if (written++ > 0) sb.append(',')
            it.appendJson(sb)
        }
        sb.append("]}")
        return json(Response.Status.OK, sb.toString())
    }

    /**
     * Live tail. `Last-Event-ID` (or `?since=`, for clients that can't set headers) replays the
     * gap first; without either, the stream starts from the next fix recorded.
     */
    private fun stream(session: IHTTPSession): Response {
        val since = longParam(session, "since") ?: session.headers[HEADER_LAST_EVENT_ID]?.toLongOrNull()
        val response = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream",
            SseStream(store, since),
        )
        response.addHeader("Cache-Control", "no-cache, no-transform")
        response.addHeader("X-Accel-Buffering", "no")
        return response
    }

    private fun startRecording(): Response {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return json(Response.Status.FORBIDDEN, statusJson("location permission not granted"))
        }
        if (!TrackerState.recording) {
            try {
                TrackingService.start(app)
            } catch (e: Exception) {
                // Android 12+ refuses a background foreground-service start unless the app is
                // exempt from battery optimisation — which is what batteryExempt reports.
                Log.w(TAG, "could not start recording", e)
                return json(Response.Status.CONFLICT, statusJson(e.javaClass.simpleName))
            }
            awaitRecording(true)
        }
        return json(Response.Status.OK, statusJson())
    }

    private fun stopRecording(): Response {
        if (TrackerState.recording) {
            TrackingService.stop(app)
            awaitRecording(false)
        }
        return json(Response.Status.OK, statusJson())
    }

    // --- responses ---------------------------------------------------------------------------

    private fun statusJson(error: String? = null): String {
        val sb = StringBuilder(256)
        sb.append("{\"recording\":").append(TrackerState.recording)
        sb.append(",\"lastSeq\":").append(store.maxSeq())
        sb.append(",\"count\":").append(store.count())
        sb.append(",\"port\":").append(PORT)
        sb.append(",\"permissions\":{\"fine\":").append(granted(Manifest.permission.ACCESS_FINE_LOCATION))
        sb.append(",\"background\":").append(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        )
        sb.append(",\"notifications\":").append(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS)
        )
        sb.append("},\"batteryExempt\":").append(batteryExempt())
        if (error != null) {
            sb.append(",\"error\":\"").append(error.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun notAllowed(): Response =
        json(Response.Status.METHOD_NOT_ALLOWED, """{"error":"method not allowed"}""")

    private fun cors(response: Response, preflight: Boolean): Response {
        response.addHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        response.addHeader("Vary", "Origin")
        if (preflight) {
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID, Cache-Control")
            response.addHeader("Access-Control-Max-Age", "86400")
            // Chrome's Private Network Access model gates public-origin -> loopback requests on
            // this; the newer Local Network Access model ignores it, so sending it costs nothing.
            response.addHeader("Access-Control-Allow-Private-Network", "true")
        }
        return response
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun longParam(session: IHTTPSession, name: String): Long? =
        session.parameters[name]?.firstOrNull()?.toLongOrNull()

    private fun granted(permission: String) =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(app.packageName)

    /** Service start/stop is asynchronous; wait briefly so the response reports the settled state. */
    private fun awaitRecording(target: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (TrackerState.recording != target && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(STATE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        private const val TAG = "TrackerApi"

        /** The single origin allowed to talk to the recorder. */
        const val ALLOWED_ORIGIN = "https://freemap.sk"

        const val PORT = 8378

        /** Loopback only — never 0.0.0.0. The API must not be reachable from the LAN. */
        private const val HOST = "127.0.0.1"

        private const val HEADER_LAST_EVENT_ID = "last-event-id"
        private const val STATE_TIMEOUT_MS = 3_000L
        private const val STATE_POLL_MS = 25L

        @Volatile
        private var instance: TrackerApi? = null

        @Synchronized
        fun ensureRunning(context: Context) {
            if (instance != null) return
            val api = TrackerApi(context)
            try {
                api.start(SOCKET_READ_TIMEOUT, false)
                instance = api
                Log.i(TAG, "listening on $HOST:$PORT")
            } catch (e: IOException) {
                Log.e(TAG, "could not bind $HOST:$PORT", e)
            }
        }
    }
}
