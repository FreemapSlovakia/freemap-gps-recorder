package sk.freemap.gpsrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Recording state, a start/stop button, a live point count — and, until everything a recording needs
 * is in place, a setup checklist under them.
 *
 * The checklist is re-read on every resume rather than only after a prompt, because half of these
 * items can only be resolved by walking off into the system settings and coming back.
 *
 * Also the landing point for `freemap-gps-recorder://` links, which let a page start a recording without
 * the user ever looking at this screen.
 */
class MainActivity : Activity() {

    private lateinit var stateView: TextView
    private lateinit var countView: TextView
    private lateinit var toggle: Button
    private lateinit var warning: TextView
    private lateinit var setupHeading: TextView
    private lateinit var setupList: LinearLayout

    private val rows = mutableListOf<Row>()

    /** Kept in step by [renderSetup], so the 2 Hz ticker does not re-check permissions forever. */
    private var canRecord = false

    /** Set by a `start` link: get out of the way as soon as the recording is actually running. */
    private var returnAfterStart = false

    /** Whether this task exists only because a link opened it — decides finish vs. move-to-back. */
    private var openedByLink = false

    /** An update is offered once per visit at most, however many resumes that visit contains. */
    private var updateOffered = false

    /** A dialog raised after the activity has gone away is a crash, so the callback checks this. */
    private var resumed = false

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
        warning = findViewById(R.id.warning)
        setupHeading = findViewById(R.id.setup_heading)
        setupList = findViewById(R.id.setup)

        toggle.setOnClickListener {
            // Pressing the button is a decision to be here, so drop any pending link hand-back —
            // e.g. from a link whose setup the user abandoned earlier and only finished now.
            returnAfterStart = false
            if (RecorderState.recording) RecordingService.stop(this) else startRecording()
        }

        buildSetupRows()

        // Cold start with nothing recording: show what is already on disk.
        if (!RecorderState.recording) {
            Thread { RecorderState.pointCount = PointStore.get(this).count() }.start()
        }

        openedByLink = savedInstanceState == null && intent?.data != null
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a link arriving while the app is already up is delivered here, not to onCreate.
        setIntent(intent)
        // Reaching here at all means the task predates this intent, so it is not the throwaway a
        // link creates and [dismiss] must not take it away. Worth saying explicitly because
        // `setIntent` leaves the link on the activity: a later recreation reads it back, and without
        // this the flag would outlive the one launch it describes.
        openedByLink = false
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        renderSetup()
        handler.post(ticker)
        // A link that arrived before the app was able to record is fulfilled the moment it can be —
        // which is usually right here, on the way back from a permission prompt or settings screen.
        if (returnAfterStart && !RecorderState.recording && canRecord) startRecording()
        // Not on the way through: a link hand-back is about to send this screen to the back, and an
        // update prompt has no business arriving as it goes.
        if (!returnAfterStart && !updateOffered) checkForUpdates(manual = false)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // --- menu ---------------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_update -> {
            if (RecorderState.recording) toast(R.string.update_recording) else checkForUpdates(manual = true)
            true
        }

        R.id.menu_help -> {
            startActivity(Intent(this, HelpActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // --- updates ------------------------------------------------------------------------------

    /**
     * A [manual] check reports back either way, since somebody is waiting for an answer; the
     * automatic one is silent unless there is something to offer. Both are gated in [UpdateCheck] —
     * neither runs during a recording.
     */
    private fun checkForUpdates(manual: Boolean) {
        UpdateCheck.request(this, manual) { result ->
            if (isFinishing || isDestroyed) return@request
            val update = result.update
            when {
                update != null -> if (resumed) showUpdate(update)
                !manual -> Unit
                result.failed -> toast(R.string.update_failed)
                else -> toast(getString(R.string.update_current, AppVersion.name(this)))
            }
        }
    }

    private fun showUpdate(update: UpdateCheck.Update) {
        updateOffered = true
        val installed = AppVersion.name(this)
        val builder = AlertDialog.Builder(this)
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.update_download) { _, _ -> openUrl(update.apkUrl) }

        if (update.obsolete) {
            // No skipping a version the server has stopped supporting — declining it is still fine,
            // it just gets offered again tomorrow.
            builder.setTitle(getString(R.string.update_obsolete_title, update.versionName))
                .setMessage(getString(R.string.update_obsolete_msg, installed, update.notes))
        } else {
            builder.setTitle(getString(R.string.update_title, update.versionName))
                .setMessage(getString(R.string.update_msg, installed, update.notes))
                .setNeutralButton(R.string.update_skip) { _, _ ->
                    UpdateCheck.skip(this, update.versionCode)
                }
        }
        builder.show()
    }

    /** Hands the APK to the browser, which is where the user installs it from — never in-app. */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(TAG, "could not open $url", e)
            toast(R.string.no_browser)
        }
    }

    private fun render() {
        val recording = RecorderState.recording
        stateView.setText(if (recording) R.string.state_recording else R.string.state_idle)
        countView.text = getString(R.string.points_fmt, RecorderState.pointCount)
        toggle.setText(if (recording) R.string.stop else R.string.start)
        // Stopping must always be possible, even if a permission was revoked mid-recording.
        toggle.isEnabled = recording || canRecord
    }

    // --- setup checklist ---------------------------------------------------------------------

    /** One checklist item and the views showing it. Order of declaration is order of asking. */
    private enum class Item(val title: Int, val detail: Int, val action: Int, val required: Boolean) {

        FINE(R.string.item_fine, R.string.item_fine_detail, R.string.act_grant, true),

        BACKGROUND(
            R.string.item_background, R.string.item_background_detail, R.string.act_allow, false,
        ),

        NOTIFICATIONS(
            R.string.item_notifications, R.string.item_notifications_detail, R.string.act_grant, true,
        ),

        // Required, and not only for the reason the label gives: without the exemption Android 12+
        // refuses the foreground-service start that `POST /start` makes from the background, so a
        // recording asked for by the website could not begin at all.
        BATTERY(R.string.item_battery, R.string.item_battery_detail, R.string.act_allow, true),

        OEM(R.string.item_oem, R.string.item_oem_detail, R.string.act_how, false),

        ;

        fun applies() = this != OEM || Vendor.current != null

        fun satisfied(context: Context) = when (this) {
            FINE -> Setup.fine(context)
            BACKGROUND -> Setup.background(context)
            NOTIFICATIONS -> Setup.notifications(context)
            BATTERY -> Setup.batteryExempt(context)
            OEM -> Setup.oemAcknowledged(context)
        }

        /** Background location is the one item Android refuses to hand out on its own. */
        fun blocked(context: Context) = this == BACKGROUND && !Setup.fine(context)
    }

    private class Row(
        val item: Item,
        val mark: TextView,
        val title: TextView,
        val detail: TextView,
        val action: Button,
    )

    private fun buildSetupRows() {
        for (item in Item.values()) {
            if (!item.applies()) continue
            val view = layoutInflater.inflate(R.layout.setup_item, setupList, false)
            val row = Row(
                item,
                view.findViewById(R.id.mark),
                view.findViewById(R.id.title),
                view.findViewById(R.id.detail),
                view.findViewById(R.id.action),
            )
            row.action.setText(item.action)
            row.action.setOnClickListener { resolve(item) }
            setupList.addView(view)
            rows += row
        }
    }

    /**
     * Reads the live state of every item straight from the platform. Called on every resume, since
     * most of these are granted in Settings, outside this app, with no callback back to here.
     */
    private fun renderSetup() {
        canRecord = Setup.canRecord(this)
        val complete = Setup.complete(this)

        for (row in rows) {
            val satisfied = row.item.satisfied(this)
            val blocked = !satisfied && row.item.blocked(this)

            row.mark.setText(
                when {
                    satisfied -> R.string.mark_ok
                    row.item.required -> R.string.mark_missing
                    else -> R.string.mark_optional
                }
            )
            row.mark.setTextColor(
                getColor(
                    when {
                        satisfied -> R.color.ok
                        row.item.required -> R.color.missing
                        else -> R.color.warn
                    }
                )
            )
            // Only the OEM strings carry a placeholder; the rest ignore the extra argument.
            row.title.text = getString(row.item.title, vendorName)
            row.detail.text =
                if (blocked) getString(R.string.item_background_blocked)
                else getString(row.item.detail, vendorName)

            // INVISIBLE rather than GONE, so resolving an item does not make the list jump about.
            row.action.visibility = if (satisfied) View.INVISIBLE else View.VISIBLE
            row.action.isEnabled = !blocked
        }

        // This is the one moment the app learns that a permission, the battery exemption or the OEM
        // acknowledgement changed — they are granted in Settings, with nothing to call back — so it is
        // also the cheapest place to tell a connected `/stream` client. Firing at every resume costs
        // nothing: an unchanged status is not published.
        RecorderApi.publishStatus()

        val checklist = if (complete) View.GONE else View.VISIBLE
        setupHeading.visibility = checklist
        setupList.visibility = checklist
        // The checklist says what is missing; the banner says why it matters. It is only worth
        // saying once recording is possible at all — before that, the missing items speak for
        // themselves.
        warning.visibility = if (!complete && canRecord) View.VISIBLE else View.GONE
    }

    private val vendorName: String by lazy {
        Vendor.current?.let { getString(it.label) }.orEmpty()
    }

    // --- resolving items -----------------------------------------------------------------------

    private fun resolve(item: Item) {
        when (item) {
            // Prominent disclosure: say what is collected, and that it happens in the background,
            // before the system dialog appears.
            Item.FINE -> confirm(R.string.rationale_title, R.string.rationale_msg, R.string.rationale_continue) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                    REQ_FINE,
                )
            }

            Item.BACKGROUND -> resolveBackground()

            Item.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }

            Item.BATTERY -> confirm(R.string.battery_title, R.string.battery_msg, R.string.act_allow) {
                requestExemption()
            }

            Item.OEM -> showVendorHelp()
        }
    }

    /**
     * On Android 11+ the system shows no dialog for background location at all — the request is
     * dropped on the floor — so the only way through is the app's own settings screen.
     */
    private fun resolveBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            return
        }
        confirm(R.string.background_title, R.string.background_msg, R.string.open_settings) {
            openAppSettings()
        }
    }

    private fun requestExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            // Deliberately not resolveActivity() first — package-visibility filtering can hide the
            // handler from us on Android 11+, so the exception is the reliable signal.
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "no battery optimisation screen on this device", e)
            openAppSettings()
        }
    }

    /**
     * The one item nothing can verify: no API reports a vendor's autostart state, so the guidance is
     * shown and the user says when it is done.
     */
    private fun showVendorHelp() {
        val vendor = Vendor.current ?: return
        AlertDialog.Builder(this)
            .setTitle(vendor.label)
            .setMessage(vendor.guidance)
            .setPositiveButton(R.string.oem_done) { _, _ ->
                Setup.acknowledgeOem(this, true)
                renderSetup()
            }
            .setNeutralButton(R.string.oem_open) { _, _ ->
                // Several of these vendors keep the switch on the app's own info page, and it is
                // where the rest end up looking anyway when their global list has moved.
                if (!vendor.openSettings(this)) openAppSettings()
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun confirm(title: Int, message: Int, positive: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "no app settings screen on this device", e)
            toast(R.string.no_settings_screen)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val permission = when (requestCode) {
            REQ_FINE -> Manifest.permission.ACCESS_FINE_LOCATION
            REQ_BACKGROUND -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
            REQ_NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
            else -> return
        }

        // A denial the system will no longer let us ask about is final, and asking again does
        // nothing visible at all — so hand the user to the screen that can still change it.
        val denied = checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        if (denied && !shouldShowRequestPermissionRationale(permission)) {
            toast(R.string.denied_permanently)
            openAppSettings()
        }
        renderSetup()
    }

    // --- links -------------------------------------------------------------------------------

    /**
     * `freemap-gps-recorder://start` starts recording and hands focus straight back to the browser;
     * any other authority just opens the app. An optional `?port=` is echoed by `GET /status`.
     *
     * A link makes the app foreground, so the foreground-service start itself is always permitted
     * here. It still goes through the same [Setup.canRecord] gate as `POST /start`, so the one visit
     * that resolves the checklist is what makes every later start from the web work too.
     */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        if (uri.scheme != LINK_SCHEME) return

        uri.getQueryParameter("port")?.toIntOrNull()?.let {
            RecorderState.portEcho = it
            if (it != RecorderApi.PORT) Log.w(TAG, "link asked for port $it, serving ${RecorderApi.PORT}")
        }

        if (uri.host != LINK_START) return
        returnAfterStart = true
        when {
            // Already recording: nothing to do but get out of the way.
            RecorderState.recording -> dismiss()
            Setup.canRecord(this) -> startRecording()
            // Setup is unfinished, so the link cannot be honoured yet. Open the first thing standing
            // in the way rather than leaving the user to work out which row to press.
            else -> Item.values().first { it.required && !it.satisfied(this) }.let { resolve(it) }
        }
    }

    private fun startRecording() {
        RecordingService.start(this)
        if (returnAfterStart) dismiss()
    }

    /**
     * Hands focus back to whoever opened the link. Finishing is right when the link created this
     * task; when the app was already open behind the browser, moving the task back returns focus
     * without tearing down the screen the user left behind.
     *
     * It has to be [finishAndRemoveTask], not `finish()`. Under `singleTask` this activity is its
     * task's only member, so finishing the root leaves the task itself in Recents — a white entry
     * that resumes to the homescreen and cannot be switched back to. A link-created task exists
     * only to hand focus back, so there is nothing in it to keep.
     */
    private fun dismiss() {
        returnAfterStart = false
        if (openedByLink && isTaskRoot) finishAndRemoveTask() else moveTaskToBack(true)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_FINE = 1
        private const val REQ_BACKGROUND = 2
        private const val REQ_NOTIFICATIONS = 3
        private const val REFRESH_MS = 500L

        /** Declared in the manifest's BROWSABLE intent filter. */
        private const val LINK_SCHEME = "freemap-gps-recorder"
        private const val LINK_START = "start"
    }
}
