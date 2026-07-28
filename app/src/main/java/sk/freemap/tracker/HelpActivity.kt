package sk.freemap.tracker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * How the APK gets on and off this phone: what Android asks the first time a browser download is
 * installed, and the manifest URL for people who would rather let Obtainium do it.
 *
 * It is a screen rather than a dialog because the manifest URL has to be readable and copyable.
 */
class HelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val url = BuildConfig.UPDATE_MANIFEST_URL
        findViewById<TextView>(R.id.manifest_url).text = url
        findViewById<Button>(R.id.copy_url).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.help_manifest), url))
            // Android 13+ shows its own copy confirmation, so saying it again would be noise.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.help_copied, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.version).text =
            getString(R.string.help_version, AppVersion.name(this), AppVersion.code(this))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
