package sk.freemap.tracker

import android.content.Context
import android.os.Build

/**
 * This build's version, read back from the installed package rather than from `BuildConfig`, so it
 * is what Android actually installed. Both halves come from `tracker.versionCode`/`versionName` in
 * `gradle.properties`, which is the only place either is written down.
 */
object AppVersion {

    @Volatile
    private var code = 0L

    @Volatile
    private var name = ""

    /** What the update check compares against the server's manifest. */
    fun code(context: Context): Long {
        load(context)
        return code
    }

    fun name(context: Context): String {
        load(context)
        return name
    }

    private fun load(context: Context) {
        if (code != 0L) return
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        name = info.versionName.orEmpty()
    }
}
