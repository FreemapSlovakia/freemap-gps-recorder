package sk.freemap.gpsrecorder

import android.content.Context
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
class RecorderApi private constructor(context: Context) : NanoHTTPD(HOST, PORT) {

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
        return cors(response, session.headers[HEADER_ORIGIN], session.method == Method.OPTIONS)
    }

    private fun route(session: IHTTPSession): Response {
        val method = session.method
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, null, null)
        }
        return when (session.uri) {
            "/track" -> when (method) {
                Method.GET -> track(session)
                Method.DELETE -> clearTrack()
                else -> notAllowed()
            }
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

    /**
     * Throws the whole track away. Refused while recording rather than racing the writer: the
     * recording thread is appending as this runs, and a client tailing `/stream` would go on being
     * handed points belonging to a track the caller has been told is gone. Stop first, then clear.
     */
    private fun clearTrack(): Response {
        if (RecorderState.recording) {
            return json(Response.Status.CONFLICT, statusJson("recording"))
        }
        store.clear()
        RecorderState.pointCount = 0
        RecorderState.lastSeq = 0
        return json(Response.Status.OK, statusJson())
    }

    private fun startRecording(): Response {
        // Same gate as the Start button: no location means nothing to record, and no notification
        // permission means a recording nobody can see is running. `canRecord` in the body says so.
        if (!Setup.canRecord(app)) {
            return json(Response.Status.FORBIDDEN, statusJson("setup incomplete"))
        }
        if (!RecorderState.recording) {
            try {
                RecordingService.start(app)
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
        if (RecorderState.recording) {
            RecordingService.stop(app)
            awaitRecording(false)
        }
        return json(Response.Status.OK, statusJson())
    }

    // --- responses ---------------------------------------------------------------------------

    /**
     * Everything the setup screen shows, so a page can say "the recorder needs setup" instead of
     * watching `POST /start` fail for reasons it cannot name. `canRecord` is the hard gate;
     * `setupComplete` additionally covers the items that only make a long recording survive.
     *
     * `version` is here so a page can tell which recorder it is talking to, and say "too old for
     * this" rather than calling an endpoint that will not answer. `generation` is how it notices
     * that the track it holds was thrown away — see [PointStore.generation].
     */
    private fun statusJson(error: String? = null): String {
        val vendor = Vendor.current
        val sb = StringBuilder(320)
        sb.append("{\"recording\":").append(RecorderState.recording)
        sb.append(",\"lastSeq\":").append(store.maxSeq())
        sb.append(",\"count\":").append(store.count())
        sb.append(",\"generation\":").append(store.generation())
        sb.append(",\"version\":{\"code\":").append(AppVersion.code(app))
        sb.append(",\"name\":")
        quoted(sb, AppVersion.name(app))
        sb.append('}')
        sb.append(",\"port\":").append(PORT)
        sb.append(",\"portEcho\":").append(RecorderState.portEcho)
        sb.append(",\"permissions\":{\"fine\":").append(Setup.fine(app))
        sb.append(",\"background\":").append(Setup.background(app))
        sb.append(",\"notifications\":").append(Setup.notifications(app))
        sb.append("},\"batteryExempt\":").append(Setup.batteryExempt(app))
        sb.append(",\"oem\":{\"vendor\":")
        if (vendor == null) sb.append("null") else quoted(sb, vendor.id)
        sb.append(",\"needed\":").append(vendor != null)
        sb.append(",\"acknowledged\":").append(Setup.oemAcknowledged(app))
        sb.append("},\"canRecord\":").append(Setup.canRecord(app))
        sb.append(",\"setupComplete\":").append(Setup.complete(app))
        if (error != null) {
            sb.append(",\"error\":")
            quoted(sb, error)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun quoted(sb: StringBuilder, value: String) {
        sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
    }

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun notAllowed(): Response =
        json(Response.Status.METHOD_NOT_ALLOWED, """{"error":"method not allowed"}""")

    /**
     * Echoes the caller's own origin when it is one we allow. `Access-Control-Allow-Origin` takes a
     * single origin, never a list, so an allowlist has to be matched and reflected rather than
     * simply printed — and `Vary: Origin` goes with it, unconditionally, because the answer now
     * depends on the request.
     *
     * An unknown or absent origin gets no header at all: browsers refuse the response, while
     * anything without an origin (curl, the address bar) is unaffected either way.
     */
    private fun cors(response: Response, origin: String?, preflight: Boolean): Response {
        response.addHeader("Vary", "Origin")
        if (origin != null && originAllowed(origin)) {
            response.addHeader("Access-Control-Allow-Origin", origin)
        }
        if (preflight) {
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
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

    /** Service start/stop is asynchronous; wait briefly so the response reports the settled state. */
    private fun awaitRecording(target: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (RecorderState.recording != target && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(STATE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        private const val TAG = "RecorderApi"

        /**
         * The origins allowed to talk to the recorder. Matched exactly, and an origin is scheme +
         * host + port, so `https://freemap.sk` does not cover `https://www.freemap.sk` — every host
         * the site is served from has to be listed here in full.
         */
        val ALLOWED_ORIGINS = setOf(
            "https://freemap.sk",
            "https://www.freemap.sk",
            "https://www.freemap.eu",
        )

        /**
         * The development host, additionally allowed on any port and over plain http, because a dev
         * server picks whatever port is free and pinning one here would only mean editing this list.
         * It is a freemap.sk subdomain, so pointing it anywhere still means controlling the site's
         * own DNS.
         */
        private const val DEV_ORIGIN_HOST = "local.freemap.sk"

        fun originAllowed(origin: String): Boolean {
            if (origin in ALLOWED_ORIGINS) return true
            val authority = when {
                origin.startsWith("https://") -> origin.substring(8)
                origin.startsWith("http://") -> origin.substring(7)
                else -> return false
            }
            if (authority == DEV_ORIGIN_HOST) return true
            // removePrefix leaves the whole string when it does not match, so a lookalike host like
            // `evil-local.freemap.sk` or `local.freemap.sk.example.com` gets no further than here.
            val port = authority.removePrefix("$DEV_ORIGIN_HOST:")
            return port != authority && port.isNotEmpty() && port.all { it.isDigit() }
        }

        const val PORT = 8378

        /** Loopback only — never 0.0.0.0. The API must not be reachable from the LAN. */
        private const val HOST = "127.0.0.1"

        private const val HEADER_LAST_EVENT_ID = "last-event-id"
        private const val HEADER_ORIGIN = "origin"
        private const val STATE_TIMEOUT_MS = 3_000L
        private const val STATE_POLL_MS = 25L

        @Volatile
        private var instance: RecorderApi? = null

        @Synchronized
        fun ensureRunning(context: Context) {
            if (instance != null) return
            val api = RecorderApi(context)
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
