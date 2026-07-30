package sk.freemap.gpsrecorder

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
import android.os.Handler
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

/**
 * Foreground service that streams fixes on the terms [RecordingConfig] sets and appends each one to
 * [PointStore].
 *
 * Fixes are delivered onto a dedicated [HandlerThread] so the SQLite writes never touch the main
 * thread, and a partial wake lock is held while a recording runs so writes keep landing with the
 * screen off.
 *
 * One state, not two: the service is either tracking or it is gone. Stopping and starting again is
 * what a break in a recording is, and it costs nothing a pause would have saved — the exemption
 * `Setup.canRecord` now demands is what makes the restart reliable from the background.
 */
class RecordingService : Service() {

    private var store: PointStore? = null
    private var client: FusedLocationProviderClient? = null
    private var worker: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gnss: GnssMonitor? = null

    // Written here on the main thread, read by the location callback on the point-writer thread —
    // which is why they are volatile: a stop the callback did not see for a while would go on
    // appending fixes, and a start's new segment number has to be visible to the first fix after it.
    @Volatile
    private var tracking = false

    @Volatile
    private var config = RecordingConfig()

    @Volatile
    private var segment = 0L

    private var lastNotifiedAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // removeLocationUpdates is asynchronous, so a fix can still land after a stop.
            if (!tracking) return
            val store = store ?: return
            var appended = false
            for (loc in result.locations) {
                val accuracy = loc.accuracy.toDouble().takeIf { loc.hasAccuracy() }
                // The accuracy floor is applied here rather than being left to whoever reads the
                // track: a fix filtered out on this side is one that never reaches the database.
                if (!config.accepts(accuracy)) continue
                val point = store.append(loc, segment, gnss?.satellites(), gnss?.geoidSeparation())
                RecorderState.lastSeq = point.seq
                RecorderState.pointCount++
                RecorderBus.publishFix(point)
                appended = true
            }
            if (appended) maybeRefreshNotification()
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
        //
        // The battery exemption that `Setup.canRecord` also demands is not checked here: it gates
        // *starting* a recording from the background, and a recording that is already under way would
        // only be lost by ending it over a setting the user changed while it ran.
        if (!Setup.canKeepRecording(this)) {
            Log.w(TAG, "cannot record: location or notification permission missing, stopping")
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            // A start against a recording that is already running: adopt whatever config was stored.
            ACTION_RECONFIGURE -> {
                // No session to act on: whoever sent this lost a race with a recording that was
                // already ending, and the service must not be left sitting here for nothing.
                if (!tracking) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTracking()
            }

            // A null intent means the system restarted us after a process kill — carry on recording.
            else -> startTracking()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    /**
     * Opens a session, or brings a running one into line with the stored config. Everything in that
     * config can be changed by re-subscribing, so the effective config is always what was asked for
     * and a `POST /start` never has to be refused for disagreeing with the recording in progress.
     */
    private fun startTracking() {
        val wanted = RecordingConfig.load(this)

        if (tracking) {
            if (wanted != config) {
                config = wanted
                RecorderState.config = wanted
                Log.i(TAG, "config changed to $wanted")
                subscribe()
            }
            refreshNotification()
            return
        }

        tracking = true
        config = wanted
        RecorderState.config = wanted

        val store = PointStore.get(this).also { this.store = it }
        RecorderState.pointCount = store.count()
        RecorderState.lastSeq = store.maxSeq()
        RecorderState.recording = true

        startInForeground()

        worker = HandlerThread("point-writer").also { it.start() }

        segment = store.nextSegment()
        subscribe()
        Log.i(TAG, "tracking started in segment $segment, ${RecorderState.pointCount} points stored")
        RecorderApi.publishStatus()
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        RecorderState.recording = false

        unsubscribe()
        gnss = null
        wakeLock = null

        worker?.quitSafely()
        worker = null

        // The store outlives the recording — the HTTP API serves the track either way.
        store = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "tracking stopped at ${RecorderState.pointCount} points")
        // A tail that stays open across a stop is exactly the client this is for: it would otherwise
        // just see the fixes dry up, which looks identical to standing still.
        RecorderApi.publishStatus()
    }

    /**
     * Subscribes to fixes for the current [config], or re-subscribes for a changed one: Play
     * services replaces the request belonging to a callback rather than adding a second one, so this
     * is how a config is applied live without the session noticing.
     */
    private fun subscribe() {
        val worker = worker ?: return

        wakeLock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG:recording")
            .apply { setReferenceCounted(false) }
        wakeLock?.let { if (!it.isHeld) it.acquire() }

        val request = LocationRequest.Builder(config.priority.platform, config.intervalMs)
            .setMinUpdateIntervalMillis(config.intervalMs)
            .setMinUpdateDistanceMeters(config.minDistanceM.toFloat())
            .setWaitForAccurateLocation(false)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()

        val client = LocationServices.getFusedLocationProviderClient(this).also { this.client = it }
        try {
            client.requestLocationUpdates(request, callback, worker.looper)
            // Satellite counts come from the raw receiver, which the fixes above are what turn on.
            gnss = (gnss ?: GnssMonitor(this)).also { it.start(Handler(worker.looper)) }
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing, stopping", e)
            stopTracking()
            stopSelf()
        }
    }

    private fun unsubscribe() {
        client?.removeLocationUpdates(callback)
        client = null

        gnss?.stop()

        wakeLock?.let { if (it.isHeld) it.release() }
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
        if (!tracking) return
        lastNotifiedAt = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    /** The recording made visible: what it is, how many points it has, and how to end it. */
    private fun buildNotification(): Notification {
        ensureChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, RecorderState.pointCount))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_track),
                    getString(R.string.stop),
                    PendingIntent.getService(
                        this,
                        REQUEST_STOP,
                        Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
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
        private const val TAG = "RecordingService"
        private const val WAKE_LOCK_TAG = "freemap-gps-recorder"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        private const val NOTIFICATION_REFRESH_MS = 3_000L

        /** Distinct from the content intent's, or the immutable PendingIntents would collide. */
        private const val REQUEST_STOP = 1

        const val ACTION_STOP = "sk.freemap.gpsrecorder.STOP"
        private const val ACTION_RECONFIGURE = "sk.freemap.gpsrecorder.RECONFIGURE"

        /** A cold start, and the one call here that needs the foreground-service start to be allowed. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        /**
         * The rest go through plain [Context.startService], which is only ever called against a
         * service that is already running and already in the foreground — so there is no
         * foreground-service start to be refused, and no exemption needed for any of them.
         */
        fun reconfigure(context: Context) = send(context, ACTION_RECONFIGURE)

        fun stop(context: Context) = send(context, ACTION_STOP)

        /**
         * Both of these say "make the running service do this", so losing the race against a
         * recording that was already ending is not a failure: there is nothing left to reconfigure
         * or stop. Android would otherwise answer a background [Context.startService] with no
         * service running by throwing, and the caller has nothing useful to do with that.
         */
        private fun send(context: Context, action: String) {
            try {
                context.startService(
                    Intent(context, RecordingService::class.java).setAction(action)
                )
            } catch (e: Exception) {
                Log.w(TAG, "no running service to deliver $action to", e)
            }
        }
    }
}
