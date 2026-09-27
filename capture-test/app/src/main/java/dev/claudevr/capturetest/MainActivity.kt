package dev.claudevr.capturetest

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test harness: can a normal sideloaded 2D app on Horizon OS use MediaProjection
 * to see what the user sees? Every step is logged on screen and to logcat
 * (tag "ClaudeVRCapture") so the result is unambiguous.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var preview: ImageView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        CaptureService.listener = { msg, bmp -> runOnUiThread { onServiceEvent(msg, bmp) } }

        log("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        log("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        log("Build: ${Build.DISPLAY}")
        log("Tap 'Request capture' to start.")

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onDestroy() {
        CaptureService.listener = null
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "MediaProjection test"
            textSize = 22f
        })

        fun button(label: String, onClick: () -> Unit) =
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })

        button("1. Request capture") { requestCapture() }
        button("2. Grab frame now") { grab(0) }
        button("3. Grab frame in 15s (switch to another app)") { grab(15_000) }
        button("Stop capture") { stopCapture() }

        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = (300 * resources.displayMetrics.density).toInt()
        }
        column.addView(preview)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        column.addView(logView)

        return ScrollView(this).apply { addView(column) }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            log("RESULT: MediaProjectionManager service is missing on this OS.")
            return
        }
        val intent = mpm.createScreenCaptureIntent()
        log("Consent intent component: ${intent.component ?: "(implicit)"}")
        try {
            startActivityForResult(intent, REQ_CAPTURE)
            log("Consent prompt requested; waiting for user...")
        } catch (e: ActivityNotFoundException) {
            log("RESULT: no consent activity on this OS -> MediaProjection unavailable. ($e)")
        } catch (e: SecurityException) {
            log("RESULT: consent activity blocked -> $e")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            log("Consent denied or cancelled (resultCode=$resultCode). If no prompt ever appeared, the OS rejected it silently.")
            return
        }
        log("Consent granted. Starting capture service...")
        val svc = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        try {
            startForegroundService(svc)
        } catch (e: Exception) {
            log("Failed to start capture service: $e")
        }
    }

    private fun grab(delayMs: Long) {
        val svc = CaptureService.instance
        if (svc == null) {
            log("Capture isn't running. Tap 'Request capture' first.")
            return
        }
        if (delayMs > 0) log("Grabbing in ${delayMs / 1000}s. Switch to another app / launch a VR game now.")
        svc.grab(delayMs)
    }

    private fun stopCapture() {
        CaptureService.instance?.stopCapture() ?: log("Capture isn't running.")
    }

    private fun onServiceEvent(msg: String, bmp: Bitmap?) {
        append(msg) // service already wrote it to logcat
        if (bmp != null) preview.setImageBitmap(bmp)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        append(msg)
    }

    private fun append(msg: String) {
        logView.append("${timeFmt.format(Date())}  $msg\n")
    }

    companion object {
        const val TAG = "ClaudeVRCapture"
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
    }
}
