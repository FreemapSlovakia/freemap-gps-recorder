package sk.freemap.tracker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Deliberately bare: recording state, a start/stop button, and a live point count.
 * Permissions are asked for inline, right when Start is pressed — the real first-run flow
 * comes later.
 */
class MainActivity : Activity() {

    private lateinit var stateView: TextView
    private lateinit var countView: TextView
    private lateinit var toggle: Button

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
            if (TrackerState.recording) TrackingService.stop(this) else requestPermissionsThenStart()
        }

        // Cold start with nothing recording: show what is already on disk.
        if (!TrackerState.recording) {
            Thread { TrackerState.pointCount = PointStore(this).use { it.count() } }.start()
        }
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
        TrackingService.start(this)
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
                TrackingService.start(this)
            }
        }
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_FOREGROUND = 1
        private const val REQ_BACKGROUND = 2
        private const val REFRESH_MS = 500L
    }
}
