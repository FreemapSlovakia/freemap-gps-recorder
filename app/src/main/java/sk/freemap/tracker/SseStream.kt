package sk.freemap.tracker

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Body of a `/stream` response: replays whatever a reconnecting client missed, then blocks handing
 * over each new fix as it is recorded.
 *
 * NanoHTTPD pumps this stream on the connection's own thread, so blocking in [read] is exactly
 * right. The recording thread, however, must never block, so [PointBus] delivery only offers to a
 * bounded queue. A client that falls far enough behind to fill it gets the stream closed rather
 * than a silent hole in its tail: `EventSource` reconnects with `Last-Event-ID` and the replay path
 * below fills the gap.
 */
class SseStream(store: PointStore, since: Long?) : InputStream() {

    private val backlog = ArrayDeque<Point>()
    private val live = ArrayBlockingQueue<Point>(QUEUE_CAPACITY)

    /** Highest `seq` already written out — the de-dup between the replay and the live tail. */
    private var sentThrough = since ?: 0L

    private var buffer = PROLOGUE
    private var offset = 0

    @Volatile
    private var overflowed = false

    @Volatile
    private var closed = false

    private val listener: (Point) -> Unit = {
        if (!overflowed && !live.offer(it)) overflowed = true
    }

    init {
        // Subscribe before reading the backlog, never after: a fix recorded while the replay query
        // runs then lands in the queue instead of falling between the two, and the seq filter in
        // fill() discards the overlap.
        PointBus.subscribe(listener)
        if (since != null) {
            try {
                store.forEachSince(since) { backlog.add(it) }
            } catch (e: Throwable) {
                PointBus.unsubscribe(listener)
                throw e
            }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (offset >= buffer.size && !fill()) return -1
        val n = minOf(len, buffer.size - offset)
        System.arraycopy(buffer, offset, b, off, n)
        offset += n
        return n
    }

    override fun available(): Int = buffer.size - offset

    override fun close() {
        closed = true
        PointBus.unsubscribe(listener)
    }

    /** Loads the next frame into [buffer], blocking until there is one. False means end of stream. */
    private fun fill(): Boolean {
        while (!closed) {
            // Once the queue has overflowed the listener stops feeding it, so draining what is
            // left is the whole remaining stream — then hang up and let the client resume.
            if (overflowed && backlog.isEmpty() && live.isEmpty()) return false
            val next = backlog.poll() ?: live.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
            if (next == null) {
                // A comment frame: keeps the connection from being timed out as idle, and turns a
                // client that vanished without a FIN into a write error instead of a silent hang.
                buffer = HEARTBEAT
                offset = 0
                return true
            }
            if (next.seq <= sentThrough) continue
            sentThrough = next.seq
            buffer = encode(next)
            offset = 0
            return true
        }
        return false
    }

    /** The `id:` field is the point's `seq`, which is what comes back as `Last-Event-ID`. */
    private fun encode(point: Point): ByteArray {
        val sb = StringBuilder(112)
        sb.append("id: ").append(point.seq).append('\n').append("data: ")
        point.appendJson(sb)
        sb.append("\n\n")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        /** Roughly four minutes of 1 Hz recording before a stalled client is cut loose. */
        private const val QUEUE_CAPACITY = 256
        private const val HEARTBEAT_MS = 15_000L

        private val PROLOGUE = "retry: 3000\n\n".toByteArray(StandardCharsets.UTF_8)
        private val HEARTBEAT = ": ping\n\n".toByteArray(StandardCharsets.UTF_8)
    }
}
