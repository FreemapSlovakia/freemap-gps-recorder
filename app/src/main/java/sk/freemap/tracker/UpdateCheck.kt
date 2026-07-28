package sk.freemap.tracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks for a newer APK on the Freemap server and, when there is one, hands it to the caller to
 * offer. Nothing is downloaded and nothing is installed here: the user is sent to the browser and
 * installs it the way they installed the first one.
 *
 * Everything about this is allowed to fail. A recorder that cannot check for updates is a recorder,
 * so every failure path is a log line and no more — and the check never runs during a recording,
 * where even a dialog would be an interruption of the one thing the app is for.
 */
object UpdateCheck {

    /** A published version newer than this one. */
    class Update(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        /** This build is below the manifest's `minSupportedVersionCode` — the server has dropped it. */
        val obsolete: Boolean,
    )

    /**
     * What came of a check. A null [update] with [failed] false means there is nothing to offer —
     * already current, or turned down before. The distinction only matters to a check the user
     * asked for: the automatic one says nothing either way.
     */
    class Result(val update: Update?, val failed: Boolean)

    /**
     * Checks in the background and reports back on the main thread. A [manual] check skips the
     * once-a-day and unmetered-connection gates, since the user asked for it; nothing skips the
     * no-recording rule.
     */
    fun request(context: Context, manual: Boolean, onResult: (Result) -> Unit) {
        val app = context.applicationContext
        // One at a time: resumes come in pairs often enough (a permission prompt, a settings trip)
        // that two overlapping checks are easy to provoke.
        if (!inFlight.compareAndSet(false, true)) return
        Thread {
            var update: Update? = null
            var failed = false
            try {
                update = check(app, manual)
            } catch (e: Exception) {
                failed = true
                Log.i(TAG, "update check failed: $e")
            } finally {
                inFlight.set(false)
            }
            val result = Result(update, failed)
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.apply {
            name = "update-check"
            isDaemon = true
        }.start()
    }

    /** Remembers a version the user declined, so the offer is not repeated every day. */
    fun skip(context: Context, versionCode: Long) {
        prefs(context).edit().putLong(KEY_SKIPPED, versionCode).apply()
    }

    private fun check(app: Context, manual: Boolean): Update? {
        if (TrackerState.recording) return null
        if (!manual && !due(app)) return null

        // Counted as spent whether or not it succeeds, so a server that is down cannot turn "once a
        // day" into "on every resume".
        prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        val manifest = JSONObject(fetch(BuildConfig.UPDATE_MANIFEST_URL))
        val versionCode = manifest.getLong("versionCode")
        val apkUrl = manifest.getString("apkUrl")
        // The manifest comes off the network, and this URL decides where the user is sent to fetch
        // something they will then install, so it does not get to choose its own scheme.
        if (!apkUrl.startsWith("https://")) throw IOException("apkUrl is not https: $apkUrl")

        val installed = AppVersion.code(app)
        val obsolete = installed < manifest.optLong("minSupportedVersionCode", 0L)
        if (versionCode <= installed) {
            Log.i(TAG, "up to date at $installed")
            return null
        }
        // A version the user has already turned down stays turned down — unless the server says this
        // build is no longer supported, or they went looking for the update themselves.
        if (!manual && !obsolete && versionCode <= prefs(app).getLong(KEY_SKIPPED, 0L)) {
            Log.i(TAG, "$versionCode available, previously skipped")
            return null
        }

        Log.i(TAG, "update available: $versionCode (installed $installed, obsolete=$obsolete)")
        return Update(
            versionCode = versionCode,
            versionName = manifest.optString("versionName", versionCode.toString()),
            apkUrl = apkUrl,
            notes = manifest.optString("notes"),
            obsolete = obsolete,
        )
    }

    private fun due(app: Context): Boolean {
        val last = prefs(app).getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        // A clock that jumped backwards, or a last-check stamp from a restored backup, must not park
        // the check somewhere in the future forever.
        val elapsed = if (now < last) Long.MAX_VALUE else now - last
        if (elapsed < CHECK_INTERVAL_MS) return false

        val capabilities = app.getSystemService(ConnectivityManager::class.java)
            ?.let { it.getNetworkCapabilities(it.activeNetwork) }
        // Offline: not an attempt at all, so it does not spend the daily allowance either.
        if (capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            return false
        }
        // Unmetered by preference. A phone that only ever sees mobile data would otherwise never
        // hear about an update at all, so metered connections are allowed eventually — the manifest
        // is a few hundred bytes, and the APK is only fetched if the user asks for it.
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return elapsed >= METERED_INTERVAL_MS
        }
        return true
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status from $url")

            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            connection.inputStream.use { input ->
                while (out.size() <= MAX_BODY_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            // Whatever is answering at that URL, it is not the manifest if it is this big.
            if (out.size() > MAX_BODY_BYTES) throw IOException("body over $MAX_BODY_BYTES bytes")
            return out.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "UpdateCheck"
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "lastCheckAt"
    private const val KEY_SKIPPED = "skippedVersionCode"

    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val METERED_INTERVAL_MS = 30 * 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 8_000
    private const val MAX_BODY_BYTES = 64 * 1024

    private val inFlight = AtomicBoolean(false)
}
