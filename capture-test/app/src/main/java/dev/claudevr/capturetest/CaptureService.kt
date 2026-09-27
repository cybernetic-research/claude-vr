package dev.claudevr.capturetest

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds the MediaProjection. Android 14+ requires this to be a foreground
 * service of type mediaProjection, started before getMediaProjection().
 */
class CaptureService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var framesSeen = 0L
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            post("RESULT: startForeground(mediaProjection) rejected -> $e")
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            post("Service started without a valid consent token.")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            post("RESULT: getMediaProjection threw -> $e")
            null
        }
        if (proj == null) {
            post("RESULT: getMediaProjection returned null -> capture not permitted.")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopRequested) post("Projection stopped by the system.")
                release()
            }
        }, handler)

        startVirtualDisplay(proj)
        return START_NOT_STICKY
    }

    private fun startVirtualDisplay(proj: MediaProjection) {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(dm)
        width = if (dm.widthPixels > 0) dm.widthPixels else 1920
        height = if (dm.heightPixels > 0) dm.heightPixels else 1080
        val dpi = if (dm.densityDpi > 0) dm.densityDpi else 320
        post("Default display reports ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi; capturing ${width}x$height")

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        // Keep draining so the producer never stalls; grab() reads whatever is newest.
        r.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            framesSeen++
            synchronized(this) {
                latest?.close()
                latest = img
            }
        }, handler)
        reader = r

        virtualDisplay = try {
            proj.createVirtualDisplay(
                "claude-vr-capture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } catch (e: Exception) {
            post("RESULT: createVirtualDisplay threw -> $e")
            null
        }
        if (virtualDisplay != null) {
            instance = this
            post("Capture running. Try 'Grab frame now'.")
            handler.postDelayed({ post("Frames received in first 3s: $framesSeen") }, 3_000)
        }
    }

    private var latest: Image? = null

    fun grab(delayMs: Long) {
        handler.postDelayed({ grabNow(if (delayMs > 0) "delayed" else "now") }, delayMs)
    }

    private fun grabNow(label: String) {
        val bmp = synchronized(this) { latest?.let { toBitmap(it) } }
        if (bmp == null) {
            post("No frame yet ($framesSeen frames total). The display may not be feeding the virtual display.")
            return
        }
        val stats = analyze(bmp)
        val file = save(bmp, label)
        post("Frame ${bmp.width}x${bmp.height}: $stats. Saved ${file?.absolutePath ?: "(save failed)"}", bmp)
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
    }

    /** Samples a grid of pixels; an all-black frame means capture is blocked or blanked. */
    private fun analyze(bmp: Bitmap): String {
        val steps = 64
        var lit = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (yi in 0 until steps) for (xi in 0 until steps) {
            val c = bmp.getPixel(xi * (bmp.width - 1) / (steps - 1), yi * (bmp.height - 1) / (steps - 1))
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (r + g + b > 48) lit++
            sumR += r; sumG += g; sumB += b
        }
        val n = steps * steps
        val pct = lit * 100 / n
        val verdict = if (pct < 1) "ALL BLACK (blocked/blanked?)" else "has content"
        return "$pct% non-black, avg rgb(${sumR / n},${sumG / n},${sumB / n}) -> $verdict"
    }

    private fun save(bmp: Bitmap, label: String): File? = try {
        val dir = getExternalFilesDir("frames") ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(dir, "frame_${stamp}_$label.png").also { f ->
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "save failed", e)
        null
    }

    fun stopCapture() {
        handler.post {
            stopRequested = true
            projection?.stop() // triggers onStop -> release()
            if (projection == null) release()
        }
    }

    private fun release() {
        instance = null
        virtualDisplay?.release()
        virtualDisplay = null
        synchronized(this) {
            latest?.close()
            latest = null
        }
        reader?.close()
        reader = null
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        post("Capture stopped.")
    }

    override fun onDestroy() {
        instance = null
        thread.quitSafely()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Claude VR capture test")
            .setContentText("Screen capture is active")
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    companion object {
        private const val TAG = MainActivity.TAG
        private const val CHANNEL = "capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile var instance: CaptureService? = null
        @Volatile var listener: ((String, Bitmap?) -> Unit)? = null

        fun post(msg: String, bmp: Bitmap? = null) {
            Log.i(TAG, msg)
            listener?.invoke(msg, bmp)
        }
    }
}
