package sk.freemap.gpsrecorder

import android.app.Application

/**
 * Brings the HTTP API up with the process, rather than with the recording: `POST /start` has to be
 * answerable while nothing is being recorded yet. Once a recording is running, the foreground
 * service is what keeps this process — and therefore the server — alive with the screen off.
 */
class RecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RecorderApi.ensureRunning(this)
    }
}
