package sk.freemap.tracker

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
     * The hard gate on Start. Without location there is nothing to record, and without the
     * notification permission the recording would run with no visible sign of it — which is
     * precisely what a foreground service is supposed to prevent.
     */
    fun canRecord(context: Context) = fine(context) && notifications(context)

    /**
     * Everything resolved, including the items that only make recording *reliable*. Recording is
     * allowed without them; it is just liable to stop on its own.
     */
    fun complete(context: Context) =
        canRecord(context) &&
            background(context) &&
            batteryExempt(context) &&
            (Vendor.current == null || oemAcknowledged(context))

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "setup"
    private const val KEY_OEM = "oemAcknowledged"
}
