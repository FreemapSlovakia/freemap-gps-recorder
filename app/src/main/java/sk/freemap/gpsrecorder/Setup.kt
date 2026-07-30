package sk.freemap.gpsrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/**
 * The state of everything that has to be in place before a recording survives.
 *
 * Deliberately Activity-free, so the setup screen and `GET /status` read exactly the same answers —
 * the web side has to be able to say "recorder needs setup" for the same reasons the app does.
 */
object Setup {

    fun fine(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun background(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun notifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryExempt(context: Context) =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** Nothing on the platform can report this one, so it comes down to the user's word. */
    fun oemAcknowledged(context: Context) = prefs(context).getBoolean(KEY_OEM, false)

    fun acknowledgeOem(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_OEM, done).apply()
    }

    /**
     * What a recording needs in order to happen at all. Without location there is nothing to record,
     * and without the notification permission the recording would run with no visible sign of it —
     * which is precisely what a foreground service is supposed to prevent.
     */
    fun canKeepRecording(context: Context) = fine(context) && notifications(context)

    /**
     * The hard gate on starting: everything [canKeepRecording] needs, plus the battery exemption.
     *
     * The exemption is here for a different reason from the other two. `RecorderApi` answers
     * `POST /start` from the app process while the browser is in front, and Android 12+ refuses a
     * backgrounded app's `startForegroundService` unless it is exempt — so without this term the
     * website could be told it may record and then be refused, which is worse than being told to
     * finish the setup first.
     *
     * It is deliberately *not* part of [canKeepRecording]: a recording already in progress does not
     * stop needing to be recorded because the user has since turned the exemption off, and tearing a
     * live track down over it would lose the track.
     */
    fun canRecord(context: Context) = canKeepRecording(context) && batteryExempt(context)

    /**
     * Everything resolved, including the items that only make recording *reliable*. Recording is
     * allowed without them; it is just liable to stop on its own.
     */
    fun complete(context: Context) =
        canRecord(context) &&
            background(context) &&
            (Vendor.current == null || oemAcknowledged(context))

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "setup"
    private const val KEY_OEM = "oemAcknowledged"
}
