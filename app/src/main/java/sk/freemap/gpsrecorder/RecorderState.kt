package sk.freemap.gpsrecorder

/**
 * Whatever the UI needs to render, published by [RecordingService]. Service and Activity live in
 * the same process, so a plain object is enough — no binding, no IPC.
 */
object RecorderState {
    @Volatile
    var recording: Boolean = false

    @Volatile
    var pointCount: Long = 0

    @Volatile
    var lastSeq: Long = 0

    /**
     * The port carried by the last `freemap-gps-recorder://` link, echoed back by `GET /status`. It lets
     * the page that opened the link confirm that the app answering on that port is the one it just
     * launched, rather than some other listener that happens to be bound there.
     */
    @Volatile
    var portEcho: Int? = null
}
