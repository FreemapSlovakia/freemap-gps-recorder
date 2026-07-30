package sk.freemap.gpsrecorder

import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import android.content.Context

/**
 * What the fused client drops on the floor: the satellite count, and the geoid separation needed to
 * turn an ellipsoidal altitude into an altitude above mean sea level.
 *
 * Both come from the platform's own GNSS, because a [Location] handed over by Play services'
 * `FusedLocationProviderClient` carries neither — verified on an API 36 emulator, where the platform
 * providers report `mslAlt` and `hasMslAltitude()` on the fused client's copy is nevertheless false.
 *
 * Nothing here turns anything on. The GNSS status callback is a report on a receiver somebody else is
 * already driving, and locations are taken from [LocationManager.PASSIVE_PROVIDER], which by
 * definition only delivers what another request has already paid for — so a recording at
 * `priority: low`, with no GNSS running, simply sees nothing and reports nulls.
 *
 * Both readings go stale on purpose, for the same reason: a value that cannot speak for the fix in
 * hand has to be absent rather than approximated from whatever GNSS last happened to mention.
 */
class GnssMonitor(context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)

    @Volatile
    private var usedInFix = 0

    @Volatile
    private var satellitesAt = 0L

    @Volatile
    private var separationM = 0.0

    @Volatile
    private var separationAt = 0L

    private var registered = false

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            usedInFix = used
            satellitesAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Records the separation rather than the MSL altitude itself. The altitude belongs to the fix that
     * carried it and would be wrong by the difference between the two fixes' altitudes; the separation
     * is a property of the geoid under this bit of the world, changes by metres over kilometres, and
     * so transfers cleanly onto the fix being stored.
     */
    private val passiveListener = LocationListener { loc ->
        if (loc.provider != LocationManager.GPS_PROVIDER) return@LocationListener
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@LocationListener
        if (!loc.hasMslAltitude() || !loc.hasAltitude()) return@LocationListener
        separationM = loc.altitude - loc.mslAltitudeMeters
        separationAt = SystemClock.elapsedRealtime()
    }

    /**
     * Starts listening, with callbacks on [handler]'s thread — the point-writer thread, so a reading is
     * never updated halfway through the fix that is reading it.
     */
    fun start(handler: Handler) {
        if (registered || manager == null) return
        registered = try {
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.registerGnssStatusCallback(Executor { handler.post(it) }, statusCallback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerGnssStatusCallback(statusCallback, handler)
            }
            manager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                0L,
                0f,
                passiveListener,
                handler.looper,
            )
            status
        } catch (e: SecurityException) {
            // Same permission as the fixes themselves, so this should not happen — but a missing
            // satellite count must never be what stops a recording.
            Log.w(TAG, "no permission for GNSS status", e)
            false
        }
        if (!registered) Log.i(TAG, "GNSS status unavailable; sat will be null")
    }

    fun stop() {
        if (!registered) return
        registered = false
        satellitesAt = 0L
        separationAt = 0L
        manager?.unregisterGnssStatusCallback(statusCallback)
        manager?.removeUpdates(passiveListener)
    }

    /** Null when the receiver has said nothing recently enough to speak for the fix in hand. */
    fun satellites(): Int? = usedInFix.takeIf { fresh(satellitesAt, SATELLITES_STALE_MS) }

    /**
     * Metres between the ellipsoid and mean sea level here, or null when no GNSS fix carrying both has
     * been seen recently. Its window is far longer than the satellite count's because it describes the
     * ground rather than the receiver: it stays true across a tunnel, and across the minutes a
     * duty-cycled receiver spends switched off.
     */
    fun geoidSeparation(): Double? = separationM.takeIf { fresh(separationAt, SEPARATION_STALE_MS) }

    private fun fresh(at: Long, window: Long) =
        at != 0L && SystemClock.elapsedRealtime() - at <= window

    companion object {
        private const val TAG = "GnssMonitor"

        /**
         * A running receiver reports about once a second. Long enough to survive the duty cycling that
         * a slow `intervalMs` brings, short enough that a count cannot outlive the fix it belongs to by
         * anything that matters.
         */
        private const val SATELLITES_STALE_MS = 10_000L

        /** Ten minutes of geoid separation is a few hundred metres of travel — well under a metre of it. */
        private const val SEPARATION_STALE_MS = 600_000L
    }
}
