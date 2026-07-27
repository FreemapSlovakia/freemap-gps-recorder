package sk.freemap.tracker

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Deliberately bare: recording state, a start/stop button, and a live point count.
 * Permissions are asked for inline, right when Start is pressed — the real first-run flow
 * comes later.
 *
 * Also the landing point for `freemap-recorder://` links, which let a page start a recording
 * without the user ever looking at this screen.
 */
class MainActivity : Activity() {

    private lateinit var stateView: TextView
    private lateinit var countView: TextView
    private lateinit var toggle: Button

    /** Set by a `start` link: get out of the way as soon as the recording is actually running. */
    private var returnAfterStart = false

    /** Whether this task exists only because a link opened it — decides finish vs. move-to-back. */
    private var openedByLink = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateView = findViewById(R.id.state)
        countView = findViewById(R.id.count)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            // Pressing the button is a decision to be here, so drop any pending link hand-back —
            // e.g. from a link whose permission chain the user abandoned earlier.
            returnAfterStart = false
            if (TrackerState.recording) TrackingService.stop(this) else requestPermissionsThenStart()
        }

        // Cold start with nothing recording: show what is already on disk.
        if (!TrackerState.recording) {
            Thread { TrackerState.pointCount = PointStore.get(this).count() }.start()
        }

        openedByLink = savedInstanceState == null && intent?.data != null
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a link arriving while the app is already up is delivered here, not to onCreate.
        setIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val recording = TrackerState.recording
        stateView.setText(if (recording) R.string.state_recording else R.string.state_idle)
        countView.text = getString(R.string.points_fmt, TrackerState.pointCount)
        toggle.setText(if (recording) R.string.stop else R.string.start)
    }

    // --- links -------------------------------------------------------------------------------

    /**
     * `freemap-recorder://start` starts recording and hands focus straight back to the browser;
     * any other authority just opens the app. An optional `?port=` is echoed by `GET /status`.
     *
     * A link makes the app foreground, so the foreground-service start is always permitted here —
     * unlike `POST /start`, which needs the battery-optimisation exemption.
     */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        if (uri.scheme != LINK_SCHEME) return

        uri.getQueryParameter("port")?.toIntOrNull()?.let {
            TrackerState.portEcho = it
            if (it != TrackerApi.PORT) Log.w(TAG, "link asked for port $it, serving ${TrackerApi.PORT}")
        }

        if (uri.host != LINK_START) return
        returnAfterStart = true
        // Already recording: nothing to do but get out of the way.
        if (TrackerState.recording) dismiss() else requestPermissionsThenStart()
    }

    /**
     * Hands focus back to whoever opened the link. Finishing is right when the link created this
     * task; when the app was already open behind the browser, moving the task back returns focus
     * without tearing down the screen the user left behind.
     */
    private fun dismiss() {
        returnAfterStart = false
        if (openedByLink && isTaskRoot) finish() else moveTaskToBack(true)
    }

    // --- permissions -------------------------------------------------------------------------

    private fun requestPermissionsThenStart() {
        val wanted = buildList {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), REQ_FOREGROUND)
            return
        }
        requestBackgroundThenStart()
    }

    /**
     * Background location has to be asked for on its own, after foreground location is already
     * held — the system silently drops a combined request.
     */
    private fun requestBackgroundThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            toast(R.string.need_background)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            return
        }
        requestExemptionThenStart()
    }

    /**
     * Not needed to record, but Android 12+ refuses to let a backgrounded app start a foreground
     * service unless it is exempt from battery optimisation — which is what `POST /start` on the
     * local API does. Declining is fine; only remote start is lost.
     */
    private fun requestExemptionThenStart() {
        val power = getSystemService(PowerManager::class.java)
        // A link start is already in the foreground and so needs no exemption; interrupting it with
        // a system dialog would defeat the point of handing focus straight back to the browser.
        if (!returnAfterStart && !power.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            try {
                // Deliberately not resolveActivity() first — package-visibility filtering can hide
                // the handler from us on Android 11+, so the exception is the reliable signal.
                toast(R.string.need_exemption)
                startActivityForResult(intent, REQ_EXEMPTION)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no battery optimisation settings screen on this device", e)
            }
        }
        startRecording()
    }

    private fun startRecording() {
        TrackingService.start(this)
        if (returnAfterStart) dismiss()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // The exemption dialog always reports RESULT_CANCELED, so there is nothing to inspect —
        // and either answer leads to the same place.
        if (requestCode == REQ_EXEMPTION) startRecording()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQ_FOREGROUND -> {
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    toast(R.string.need_location)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !granted(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    toast(R.string.notifications_denied)
                }
                requestBackgroundThenStart()
            }

            REQ_BACKGROUND -> {
                // Recording still works while the service is in the foreground state, so start
                // either way and just say what was given up.
                if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    toast(R.string.background_denied)
                }
                requestExemptionThenStart()
            }
        }
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_FOREGROUND = 1
        private const val REQ_BACKGROUND = 2
        private const val REQ_EXEMPTION = 3
        private const val REFRESH_MS = 500L

        /** Declared in the manifest's BROWSABLE intent filter. */
        private const val LINK_SCHEME = "freemap-recorder"
        private const val LINK_START = "start"
    }
}
