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
import org.json.JSONException
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
        // An unread request body stays in the connection's buffer, where it would be taken for the
        // beginning of the next request on a keep-alive connection, so every POST body is consumed
        // whether or not the endpoint has any use for one. Only the start endpoint reads it.
        val body = if (method == Method.POST) body(session) else null
        return when (session.uri) {
            "/track" -> when (method) {
                Method.GET -> track(session)
                Method.DELETE -> clearTrack()
                else -> notAllowed()
            }
            "/stream" -> if (method == Method.GET) stream(session) else notAllowed()
            "/status" -> if (method == Method.GET) json(Response.Status.OK, statusJson()) else notAllowed()
            "/start" -> if (method == Method.POST) startRecording(body) else notAllowed()
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
            // The status goes out ahead of the points, so a client that never calls `/status` still
            // knows what it has joined and how to read the rows.
            SseStream(store, since, statusJson()),
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
        // The generation bump is the only signal that a client's points are gone, and it used to be
        // discoverable by polling alone — a live tail saw `seq` simply carry on. Now it arrives.
        publishStatus()
        return json(Response.Status.OK, statusJson())
    }

    /**
     * Starts recording, on the terms an optional JSON [body] asks for — see [RecordingConfig]. The
     * config is stored before the service is told anything, so it is what the recording picks up and
     * what a start with no body reuses next time.
     *
     * A start against a recording that is already running is still idempotent: the config is applied
     * live and the effective one comes back in the response, which is how a client learns what it
     * actually got without a second request.
     */
    private fun startRecording(body: String?): Response {
        // Same gate as the Start button: no location means nothing to record, no notification
        // permission means a recording nobody can see is running, and without the battery exemption
        // the foreground-service start below is refused outright — this endpoint is answered from the
        // background, with the browser in front. `canRecord` in the body says so.
        if (!Setup.canRecord(app)) {
            return json(Response.Status.FORBIDDEN, statusJson("setup incomplete"))
        }

        val config = try {
            RecordingConfig.parse(body, RecordingConfig.load(app))
        } catch (e: JSONException) {
            Log.w(TAG, "could not read the config in POST /start", e)
            return json(Response.Status.BAD_REQUEST, statusJson("bad config"))
        }
        // Stored before the service is told anything: this is where the recording reads it from, and
        // where it stays for the next start that sends no body — including one from the app's own
        // button. It is also what `/status` reports while nothing is recording.
        RecordingConfig.save(app, config)

        if (RecorderState.recording) {
            RecordingService.reconfigure(app)
            // The service owns the config of a running recording, so wait for it to have adopted
            // this one. Then the response says what is actually running rather than what was asked
            // for.
            await { RecorderState.config == config }
            return json(Response.Status.OK, statusJson())
        }

        try {
            RecordingService.start(app)
        } catch (e: Exception) {
            // A safety net rather than the expected path: the one thing Android 12+ refuses a
            // background foreground-service start for is the missing battery exemption, and
            // `canRecord` above has already turned that away with a 403.
            Log.w(TAG, "could not start recording", e)
            return json(Response.Status.CONFLICT, statusJson(e.javaClass.simpleName))
        }
        await { RecorderState.recording }
        return json(Response.Status.OK, statusJson())
    }

    private fun stopRecording(): Response {
        if (RecorderState.recording) {
            RecordingService.stop(app)
            await { !RecorderState.recording }
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
     *
     * `config` is the *effective* recording config, after clamping. Its presence is also the feature
     * detection for `POST /start` taking a body at all: a client that gets none back knows the
     * recorder it is talking to ignored what it sent.
     *
     * Every key here is written out literally, both because R8 renames everything it can and because
     * `checkApiDocs` reads this function to decide what API.md has to document.
     */
    private fun statusJson(error: String? = null): String {
        val vendor = Vendor.current
        // A running recording's config is whatever it is running with, which is the service's to
        // report; with nothing running it is what the next start will pick up, which is on disk.
        val config = if (RecorderState.recording) RecorderState.config else RecordingConfig.load(app)
        val sb = StringBuilder(420)
        sb.append("{\"recording\":").append(RecorderState.recording)
        sb.append(",\"lastSeq\":").append(store.maxSeq())
        sb.append(",\"count\":").append(store.count())
        sb.append(",\"generation\":").append(store.generation())
        // The same list `/track` returns with every page. It is here because `/stream` sends bare
        // rows, and a client that attaches to the stream without reading a page first — a reconnecting
        // EventSource, or one whose cursor is already current — would otherwise have to fall back on a
        // hardcoded column list. Appending to the list can never break such a client; naming it here
        // means they need not guess in the first place.
        sb.append(",\"fields\":").append(Point.FIELDS_JSON)
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
        sb.append(",\"config\":{\"intervalMs\":").append(config.intervalMs)
        sb.append(",\"minDistanceM\":").append(config.minDistanceM)
        sb.append(",\"maxAccuracyM\":").append(config.maxAccuracyM)
        sb.append(",\"priority\":")
        quoted(sb, config.priority.id)
        sb.append(",\"source\":")
        quoted(sb, config.source.id)
        sb.append('}')
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

    /**
     * The raw request body, or null when there is none. NanoHTTPD hands a body it cannot recognise as
     * a form over as a pseudo-file named `postData`, which is exactly what a JSON body is.
     *
     * A body that cannot be read is treated as no body at all rather than as an error: the failures
     * here are truncated requests and dropped connections, and there is nobody left to answer.
     */
    private fun body(session: IHTTPSession): String? {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"]?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the request body of ${session.uri}", e)
            null
        }
    }

    /**
     * Service commands are asynchronous; wait briefly so the response reports the settled state and
     * not a hopeful guess. Times out rather than hanging — a status object that turns out to be one
     * transition behind is better than a request that never answers.
     */
    private fun await(settled: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (!settled() && SystemClock.elapsedRealtime() < deadline) {
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

        /**
         * Pushes the current status to every open `/stream`, so a connected client does not have to
         * poll `/status` to notice a start, a stop or a cleared track.
         *
         * Safe to call at anything that might have changed it: [RecorderBus.publishStatus] drops a
         * snapshot identical to the last one, so a status event always means the status is genuinely
         * different. Does nothing before the server is up, which is only the case before
         * [RecorderApp.onCreate] has run.
         */
        fun publishStatus() {
            val api = instance ?: return
            RecorderBus.publishStatus(api.statusJson())
        }

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
