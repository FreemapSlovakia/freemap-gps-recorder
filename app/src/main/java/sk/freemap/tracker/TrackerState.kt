package sk.freemap.tracker

/**
 * Whatever the UI needs to render, published by [TrackingService]. Service and Activity live in
 * the same process, so a plain object is enough — no binding, no IPC.
 */
object TrackerState {
    @Volatile
    var recording: Boolean = false

    @Volatile
    var pointCount: Long = 0

    @Volatile
    var lastSeq: Long = 0
}
