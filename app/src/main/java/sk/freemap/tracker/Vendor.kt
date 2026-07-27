package sk.freemap.tracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Manufacturers that kill background apps beyond anything the platform itself does, and the settings
 * screens where a user can opt out.
 *
 * Every component named here is undocumented and moves between ROM versions, so they are tried in
 * turn and a miss is never fatal — [guidance] is the real answer, and the intent is only a shortcut
 * to the screen it describes.
 */
enum class Vendor(val label: Int, val guidance: Int, private val screens: List<String>) {

    // No component: MIUI puts both switches that matter — Battery saver and Autostart — on the
    // app's own info page, which the documented app-details intent already opens.
    XIAOMI(R.string.vendor_xiaomi, R.string.vendor_xiaomi_help, emptyList()),

    HUAWEI(
        R.string.vendor_huawei, R.string.vendor_huawei_help,
        listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
    ),

    SAMSUNG(
        R.string.vendor_samsung, R.string.vendor_samsung_help,
        listOf(
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
    ),

    OPPO(
        R.string.vendor_oppo, R.string.vendor_oppo_help,
        listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
    ),

    VIVO(
        R.string.vendor_vivo, R.string.vendor_vivo_help,
        listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.iqoo.secure/com.iqoo.secure.safeguard.PurviewTabActivity",
        ),
    ),

    ONEPLUS(
        R.string.vendor_oneplus, R.string.vendor_oneplus_help,
        listOf(
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
    ),

    ;

    /**
     * Opens the vendor's own settings screen, for the vendors whose switch is on a global list
     * rather than on the app's own page. Returns false when this ROM has none of them — the
     * components are guesses about somebody else's private app, so anything from a renamed class to
     * a missing export permission lands here, and the caller falls back to app details.
     */
    fun openSettings(context: Context): Boolean {
        for (screen in screens) {
            val component = ComponentName.unflattenFromString(screen) ?: continue
            try {
                context.startActivity(Intent().setComponent(component))
                return true
            } catch (e: Exception) {
                Log.i(TAG, "no $screen on this ROM: $e")
            }
        }
        return false
    }

    companion object {
        private const val TAG = "Vendor"

        /** This device's vendor, or null on a ROM that plays by the platform's rules. */
        val current: Vendor? by lazy { of(Build.MANUFACTURER, Build.BRAND) }

        private fun of(manufacturer: String?, brand: String?): Vendor? {
            // Sub-brands report themselves inconsistently across ROMs — Redmi and Poco are usually
            // MANUFACTURER=Xiaomi but not always, and Honor split off Huawei mid-life.
            val id = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
            return when {
                id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> XIAOMI
                id.contains("huawei") || id.contains("honor") -> HUAWEI
                id.contains("samsung") -> SAMSUNG
                id.contains("oppo") || id.contains("realme") -> OPPO
                id.contains("vivo") -> VIVO
                id.contains("oneplus") -> ONEPLUS
                else -> null
            }
        }
    }
}
