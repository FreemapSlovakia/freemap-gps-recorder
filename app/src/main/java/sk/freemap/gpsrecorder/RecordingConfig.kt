package sk.freemap.gpsrecorder

import android.content.Context
import org.json.JSONObject
import com.google.android.gms.location.Priority as GmsPriority

/**
 * What gets recorded — the cadence, the displacement gate, the accuracy floor, and how hard the
 * platform is asked to work for a fix.
 *
 * These belong on this side of the wire rather than in whatever page is watching: filtering in the
 * browser still burns the battery and still fills the database, and every other client then sees a
 * different track from the one that was actually recorded.
 *
 * The values are persisted, so a `POST /start` with no body records with whatever was last asked
 * for — including a recording started from the app's own button after the settings were chosen on
 * the web.
 */
data class RecordingConfig(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val minDistanceM: Double = 0.0,
    val maxAccuracyM: Double? = null,
    val priority: Priority = Priority.HIGH,
) {

    /**
     * How hard to work for a fix. [id] is what crosses the wire and what is stored, written out
     * literally because R8 renames the enum's own `name` — the same reason [Vendor.id] exists.
     */
    enum class Priority(val id: String, val platform: Int) {
        HIGH("high", GmsPriority.PRIORITY_HIGH_ACCURACY),
        BALANCED("balanced", GmsPriority.PRIORITY_BALANCED_POWER_ACCURACY),
        LOW("low", GmsPriority.PRIORITY_LOW_POWER),
        ;

        companion object {
            fun of(id: String?): Priority? = values().firstOrNull { it.id == id }
        }
    }

    /**
     * Whether a fix this accurate is worth storing. A fix that reports no accuracy at all is kept:
     * the floor is a statement about bad fixes, not about silent ones.
     */
    fun accepts(accuracyM: Double?): Boolean {
        val floor = maxAccuracyM ?: return true
        return accuracyM == null || accuracyM <= floor
    }

    /**
     * The same config with every value inside the range the platform will actually honour. This is
     * what `GET /status` reports and what the recording uses, so a client can compare what it asked
     * for against what it got instead of guessing at limits it cannot see.
     */
    fun clamped() = RecordingConfig(
        intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        minDistanceM = minDistanceM.finiteOrZero().coerceIn(0.0, MAX_DISTANCE_M),
        // Nothing useful can be meant by a non-positive or nonsensical floor, and reading it
        // literally would throw every fix away; it is the same as asking for no floor at all.
        maxAccuracyM = maxAccuracyM
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(MAX_ACCURACY_M),
        priority = priority,
    )

    private fun Double.finiteOrZero() = if (isFinite()) this else 0.0

    companion object {
        /** Tracking-grade cadence: a fix a second, which is what a recorded track wants. */
        const val DEFAULT_INTERVAL_MS = 1_000L

        private const val MIN_INTERVAL_MS = 200L
        private const val MAX_INTERVAL_MS = 3_600_000L
        private const val MAX_DISTANCE_M = 100_000.0
        private const val MAX_ACCURACY_M = 100_000.0

        private const val PREFS = "recording"
        private const val KEY_INTERVAL = "intervalMs"
        private const val KEY_DISTANCE = "minDistanceM"
        private const val KEY_MAX_ACCURACY = "maxAccuracyM"
        private const val KEY_PRIORITY = "priority"

        /**
         * Reads a `POST /start` body over [base] — normally the stored config, so a body naming one
         * key changes one thing. Every key is optional and unknown keys are ignored, which is what
         * keeps an older page and a newer recorder talking in both directions.
         *
         * A malformed body or a non-numeric value throws [org.json.JSONException]: recording with
         * settings nobody asked for is worse than answering `400`. An unrecognised `priority`
         * *value* is the one exception — it leaves the previous priority in place, and the caller
         * sees that in the effective config it gets back.
         */
        fun parse(body: String?, base: RecordingConfig): RecordingConfig {
            if (body == null) return base.clamped()
            val json = JSONObject(body)
            return RecordingConfig(
                intervalMs =
                    if (json.has(KEY_INTERVAL)) json.getLong(KEY_INTERVAL) else base.intervalMs,
                minDistanceM =
                    if (json.has(KEY_DISTANCE)) json.getDouble(KEY_DISTANCE) else base.minDistanceM,
                maxAccuracyM = when {
                    !json.has(KEY_MAX_ACCURACY) -> base.maxAccuracyM
                    // An explicit null is how a client turns the filter back off.
                    json.isNull(KEY_MAX_ACCURACY) -> null
                    else -> json.getDouble(KEY_MAX_ACCURACY)
                },
                priority =
                    if (json.has(KEY_PRIORITY)) {
                        Priority.of(json.optString(KEY_PRIORITY)) ?: base.priority
                    } else {
                        base.priority
                    },
            ).clamped()
        }

        fun load(context: Context): RecordingConfig {
            val prefs = prefs(context)
            return RecordingConfig(
                intervalMs = prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS),
                minDistanceM = prefs.getFloat(KEY_DISTANCE, 0f).toDouble(),
                // 0 is how "no floor" is stored, since preferences have no nullable float.
                maxAccuracyM = prefs.getFloat(KEY_MAX_ACCURACY, 0f).toDouble().takeIf { it > 0.0 },
                priority = Priority.of(prefs.getString(KEY_PRIORITY, null)) ?: Priority.HIGH,
            ).clamped()
        }

        /**
         * Written through with `commit`, not `apply`: the service reads this back to decide what to
         * record, and a config that survives the answer to `POST /start` but not a process kill
         * seconds later would leave the client believing in settings the recording never used.
         */
        fun save(context: Context, config: RecordingConfig) {
            prefs(context).edit()
                .putLong(KEY_INTERVAL, config.intervalMs)
                .putFloat(KEY_DISTANCE, config.minDistanceM.toFloat())
                .putFloat(KEY_MAX_ACCURACY, config.maxAccuracyM?.toFloat() ?: 0f)
                .putString(KEY_PRIORITY, config.priority.id)
                .commit()
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
