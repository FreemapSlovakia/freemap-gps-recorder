package sk.freemap.tracker

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out from the recording thread to whoever is tailing the track — today, the `/stream`
 * connections.
 *
 * [publish] runs on the point-writer thread, so listeners must return immediately and must never
 * block: an SSE connection that stops draining has to drop itself, not stall recording.
 */
object PointBus {

    private val listeners = CopyOnWriteArrayList<(Point) -> Unit>()

    fun subscribe(listener: (Point) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (Point) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(point: Point) {
        for (listener in listeners) listener(point)
    }
}
