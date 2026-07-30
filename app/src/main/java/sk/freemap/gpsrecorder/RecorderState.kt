package sk.freemap.gpsrecorder

/**
 * Whatever the UI needs to render, published by [RecordingService]. Service and Activity live in
 * the same process, so a plain object is enough — no binding, no IPC.
 */
object RecorderState {
    @Volatile
    var recording: Boolean = false

    /**
     * Whether a running recording is consuming fixes. The session is still open and the foreground
     * service is still up while this is true, which is why [recording] stays true alongside it —
     * `paused` is the finer state, not a different one.
     */
    @Volatile
    var paused: Boolean = false

    @Volatile
    var pointCount: Long = 0

    @Volatile
    var lastSeq: Long = 0

    /**
     * The config the running recording is actually using, after clamping. Only meaningful while
     * [recording]: with nothing running, what the next start will pick up is the stored one, and
     * `GET /status` reads that instead of this.
     */
    @Volatile
    var config: RecordingConfig = RecordingConfig()

    /**
     * The port carried by the last `freemap-gps-recorder://` link, echoed back by `GET /status`. It lets
     * the page that opened the link confirm that the app answering on that port is the one it just
     * launched, rather than some other listener that happens to be bound there.
     */
    @Volatile
    var portEcho: Int? = null
}
