package sk.freemap.gpsrecorder

import java.util.concurrent.CopyOnWriteArrayList

/** Anything a `/stream` client should hear about as it happens. */
sealed interface RecorderEvent {

    /** A fix, as recorded. Carries a `seq`, so it is the only kind that can be replayed or de-duped. */
    class Fix(val point: Point) : RecorderEvent

    /** A snapshot of `GET /status`, already serialised — see [RecorderApi.publishStatus]. */
    class Status(val json: String) : RecorderEvent
}

/**
 * Fan-out from whatever caused an event to whoever is tailing the recording — today, the `/stream`
 * connections.
 *
 * [publishFix] runs on the point-writer thread, so listeners must return immediately and must never
 * block: an SSE connection that stops draining has to drop itself, not stall recording.
 */
object RecorderBus {

    private val listeners = CopyOnWriteArrayList<(RecorderEvent) -> Unit>()

    /** The last status published, so that only *changes* go out — see [publishStatus]. */
    private var lastStatus: String? = null

    fun subscribe(listener: (RecorderEvent) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (RecorderEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun publishFix(point: Point) = publish(RecorderEvent.Fix(point))

    /**
     * Publishes [json] unless it is what went out last, so callers may fire this at anything that
     * *might* have changed the status — a resume, a permission prompt coming back — without having to
     * work out whether it did. That is also what keeps the promise the frame makes: a status event
     * means the status is different, not merely that something happened.
     *
     * Synchronized because the callers are on different threads (the service's main thread, the HTTP
     * threads) and the comparison and the send have to be one step, or two changes racing could go out
     * in the wrong order and leave clients holding the older one.
     */
    @Synchronized
    fun publishStatus(json: String) {
        if (json == lastStatus) return
        lastStatus = json
        publish(RecorderEvent.Status(json))
    }

    private fun publish(event: RecorderEvent) {
        for (listener in listeners) listener(event)
    }
}
