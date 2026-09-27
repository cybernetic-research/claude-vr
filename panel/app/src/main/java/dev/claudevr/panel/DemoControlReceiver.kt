package dev.claudevr.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets a computer start and stop demo recording over adb, so an external mic
 * can be started first and stay in sync:
 *   adb shell am broadcast -a dev.claudevr.panel.START_REC -n dev.claudevr.panel/.DemoControlReceiver
 * Guarded by the DUMP permission in the manifest, which only the adb shell holds.
 */
class DemoControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = CaptureService.instance
        if (svc == null) {
            Log.w(TAG, "REC ignored: screen capture isn't running")
            return
        }
        when (intent.action) {
            ACTION_START -> if (!svc.recording) svc.startRecording { Log.i(TAG, "REC started ${it?.name}") }
            ACTION_STOP -> svc.stopRecording { Log.i(TAG, "REC stopped ${it?.name}") }
        }
    }

    companion object {
        private const val TAG = "ClaudePanel"
        const val ACTION_START = "dev.claudevr.panel.START_REC"
        const val ACTION_STOP = "dev.claudevr.panel.STOP_REC"
    }
}
