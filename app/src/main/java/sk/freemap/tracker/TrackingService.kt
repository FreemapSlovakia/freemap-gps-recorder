package sk.freemap.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service that streams high-accuracy fixes and appends each one to [PointStore].
 *
 * Fixes are delivered onto a dedicated [HandlerThread] so the SQLite writes never touch the main
 * thread, and a partial wake lock is held for the duration so writes keep landing with the screen
 * off.
 */
class TrackingService : Service() {

    private var store: PointStore? = null
    private var client: FusedLocationProviderClient? = null
    private var worker: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var tracking = false
    private var lastNotifiedAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // removeLocationUpdates is asynchronous, so a fix can still land after we stopped.
            if (!tracking) return
            val store = store ?: return
            for (loc in result.locations) {
                val point = store.append(loc)
                TrackerState.lastSeq = point.seq
                TrackerState.pointCount++
                PointBus.publish(point)
            }
            maybeRefreshNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        // Recording is impossible without location, and invisible without the notification. The
        // restart path makes this more than a formality: a null intent means the system brought us
        // back after a process kill, and revoking a permission is itself what kills the process — so
        // this is exactly where a revoke lands. Play services does not fail the subscription
        // synchronously, so without this check the service would sit in the notification claiming to
        // record while appending nothing.
        if (!Setup.canRecord(this)) {
            Log.w(TAG, "cannot record: location or notification permission missing, stopping")
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }

        // A null intent means the system restarted us after a process kill — resume recording.
        startTracking()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun startTracking() {
        if (tracking) {
            refreshNotification()
            return
        }
        tracking = true

        val store = PointStore.get(this).also { this.store = it }
        TrackerState.pointCount = store.count()
        TrackerState.lastSeq = store.maxSeq()
        TrackerState.recording = true

        startInForeground()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG:recording")
            .apply {
                setReferenceCounted(false)
                acquire()
            }

        val worker = HandlerThread("point-writer").also {
            it.start()
            this.worker = it
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()

        val client = LocationServices.getFusedLocationProviderClient(this).also { this.client = it }
        try {
            client.requestLocationUpdates(request, callback, worker.looper)
            Log.i(TAG, "tracking started, ${TrackerState.pointCount} points already stored")
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing, stopping", e)
            stopTracking()
            stopSelf()
        }
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        TrackerState.recording = false

        client?.removeLocationUpdates(callback)
        client = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        worker?.quitSafely()
        worker = null

        // The store outlives the recording — the HTTP API serves the track either way.
        store = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "tracking stopped at ${TrackerState.pointCount} points")
    }

    private fun startInForeground() {
        val notification = buildNotification()
        lastNotifiedAt = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Keeps the notification's point count roughly live without hammering the notification manager. */
    private fun maybeRefreshNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAt < NOTIFICATION_REFRESH_MS) return
        lastNotifiedAt = now
        refreshNotification()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, TrackerState.pointCount))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_track),
                    getString(R.string.stop),
                    stop,
                ).build()
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val WAKE_LOCK_TAG = "freemap-tracker"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** Tracking-grade cadence: ask for a fix every second, accept them no faster than that. */
        private const val INTERVAL_MS = 1_000L
        private const val MIN_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_REFRESH_MS = 3_000L

        const val ACTION_STOP = "sk.freemap.tracker.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
