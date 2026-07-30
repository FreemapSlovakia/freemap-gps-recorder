package sk.freemap.gpsrecorder

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Body of a `/stream` response: says what the recorder is currently doing, replays whatever a
 * reconnecting client missed, then blocks handing over each new event as it happens.
 *
 * NanoHTTPD pumps this stream on the connection's own thread, so blocking in [read] is exactly
 * right. The recording thread, however, must never block, so [RecorderBus] delivery only offers to a
 * bounded queue. A client that falls far enough behind to fill it gets the stream closed rather
 * than a silent hole in its tail: `EventSource` reconnects with `Last-Event-ID` and the replay path
 * below fills the gap.
 */
class SseStream(store: PointStore, since: Long?, status: String) : InputStream() {

    private val backlog = ArrayDeque<Point>()
    private val live = ArrayBlockingQueue<RecorderEvent>(QUEUE_CAPACITY)

    /** Highest `seq` already written out — the de-dup between the replay and the live tail. */
    private var sentThrough = since ?: 0L

    /**
     * The connection opens with the current status, before any point, so that a client which never
     * calls `/status` still learns the state it is joining — and, because the status object names the
     * point columns, how to decode the rows that follow.
     */
    private var buffer = (PROLOGUE + statusFrame(status))
    private var offset = 0

    @Volatile
    private var overflowed = false

    @Volatile
    private var closed = false

    private val listener: (RecorderEvent) -> Unit = {
        if (!overflowed && !live.offer(it)) overflowed = true
    }

    init {
        // Subscribe before reading the backlog, never after: an event that happens while the replay
        // query runs then lands in the queue instead of falling between the two, and the seq filter in
        // fill() discards the overlap.
        RecorderBus.subscribe(listener)
        if (since != null) {
            try {
                store.forEachSince(since) { backlog.add(it) }
            } catch (e: Throwable) {
                RecorderBus.unsubscribe(listener)
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
        RecorderBus.unsubscribe(listener)
    }

    /** Loads the next frame into [buffer], blocking until there is one. False means end of stream. */
    private fun fill(): Boolean {
        while (!closed) {
            // Once the queue has overflowed the listener stops feeding it, so draining what is
            // left is the whole remaining stream — then hang up and let the client resume.
            if (overflowed && backlog.isEmpty() && live.isEmpty()) return false

            val replayed = backlog.poll()
            if (replayed != null) {
                if (!emit(replayed)) continue
                return true
            }

            val next = live.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
            if (next == null) {
                // A comment frame: keeps the connection from being timed out as idle, and turns a
                // client that vanished without a FIN into a write error instead of a silent hang.
                buffer = HEARTBEAT
                offset = 0
                return true
            }
            when (next) {
                is RecorderEvent.Fix -> if (!emit(next.point)) continue
                is RecorderEvent.Status -> {
                    buffer = statusFrame(next.json)
                    offset = 0
                }
            }
            return true
        }
        return false
    }

    /** False when this point has already gone out — the replay and the live tail overlap by design. */
    private fun emit(point: Point): Boolean {
        if (point.seq <= sentThrough) return false
        sentThrough = point.seq
        val sb = StringBuilder(160)
        // The `id:` field is the point's `seq`, which is what comes back as `Last-Event-ID`.
        sb.append("id: ").append(point.seq).append('\n').append("data: ")
        point.appendJson(sb)
        sb.append("\n\n")
        buffer = sb.toString().toByteArray(StandardCharsets.UTF_8)
        offset = 0
        return true
    }

    companion object {
        /** Roughly four minutes of 1 Hz recording before a stalled client is cut loose. */
        private const val QUEUE_CAPACITY = 256
        private const val HEARTBEAT_MS = 15_000L

        private val PROLOGUE = "retry: 3000\n\n".toByteArray(StandardCharsets.UTF_8)
        private val HEARTBEAT = ": ping\n\n".toByteArray(StandardCharsets.UTF_8)

        /**
         * Deliberately carries no `id:`. `Last-Event-ID` is a point cursor, and a status frame that
         * set it would have a reconnecting client resume from something that is not a `seq` — losing
         * points, or replaying them.
         */
        private fun statusFrame(json: String): ByteArray =
            "event: status\ndata: $json\n\n".toByteArray(StandardCharsets.UTF_8)
    }
}
