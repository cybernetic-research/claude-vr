package dev.claudevr.panel

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Mirrors what the user sees (via MediaProjection) and hands out the newest
 * frame as a JPEG on request. Android 14+ requires this to be a foreground
 * service of type mediaProjection, started before getMediaProjection().
 */
class CaptureService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var demo: DemoRecorder? = null
    private var frameWidth = 0
    private var frameHeight = 0

    val recording get() = demo != null

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
            fail("Couldn't start screen capture service: $e")
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val proj = if (code == Activity.RESULT_OK && data != null) {
            try {
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            } catch (e: Exception) {
                Log.w(TAG, "getMediaProjection failed", e)
                null
            }
        } else null
        if (proj == null) {
            fail("Screen capture wasn't granted.")
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = release()
        }, handler)

        startVirtualDisplay(proj)
        return START_NOT_STICKY
    }

    private fun startVirtualDisplay(proj: MediaProjection) {
        val dm = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(dm)
        val width = if (dm.widthPixels > 0) dm.widthPixels else 1250
        val height = if (dm.heightPixels > 0) dm.heightPixels else 1000
        val dpi = if (dm.densityDpi > 0) dm.densityDpi else 200

        frameWidth = width
        frameHeight = height
        // CPU-readable for snapshots, GPU-sampled so DemoRecorder can draw frames without copying.
        val r = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        // Keep draining so the producer never stalls; snapshot() reads whatever is newest.
        r.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            demo?.addFrame(img)
            synchronized(this) {
                latest?.close()
                latest = img
            }
        }, handler)
        reader = r

        virtualDisplay = try {
            proj.createVirtualDisplay(
                "claude-panel-capture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } catch (e: Exception) {
            Log.w(TAG, "createVirtualDisplay failed", e)
            null
        }
        if (virtualDisplay == null) {
            fail("Screen capture couldn't start.")
            return
        }
        instance = this
        notifyState(true, null)
    }

    /** Newest frame as JPEG (long edge capped for the API), delivered on the main thread; null if none yet. */
    fun snapshot(onResult: (ByteArray?) -> Unit) {
        handler.post {
            val bmp = synchronized(this) { latest?.let { toBitmap(it) } }
            val jpeg = bmp?.let { encode(it) }
            main.post { onResult(jpeg) }
        }
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

    private fun encode(src: Bitmap): ByteArray {
        val longEdge = maxOf(src.width, src.height)
        val bmp = if (longEdge > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    /** Starts recording the view to demos/demo-<time>/ and plays a sync beep. Main-thread callback. */
    fun startRecording(onResult: (File?) -> Unit) {
        handler.post {
            val dir = File(
                getExternalFilesDir("demos"),
                "demo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            ).apply { mkdirs() }
            demo = try {
                DemoRecorder(this, dir, frameWidth, frameHeight)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't start demo recording", e)
                null
            }
            // A 1 kHz beep marks t=0 so a separately recorded mic track can be lined up.
            if (demo != null) {
                demo?.markBeep()
                syncBeep()
            }
            val started = demo?.dir
            notifyState(true, null) // refresh the Rec button, e.g. when started over adb
            main.post { onResult(started) }
        }
    }

    fun stopRecording(onResult: (File?) -> Unit = {}) {
        handler.post {
            val dir = demo?.dir
            demo?.stop()
            demo = null
            notifyState(true, null)
            main.post { onResult(dir) }
        }
    }

    private fun syncBeep() {
        val rate = 48_000
        val n = rate * 3 / 10 // 300 ms
        val ramp = rate / 100 // 10 ms fade in/out, no clicks
        val pcm = ShortArray(n) { i ->
            val env = minOf(1.0, minOf(i, n - i).toDouble() / ramp)
            (sin(2 * PI * 1000 * i / rate) * env * 30_000).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, n)
        track.play()
        handler.postDelayed({ track.release() }, 1_000)
    }

    /** Saves a copy of a spoken reply into the current recording, if any. */
    fun addSpeech(wav: File) {
        handler.post { demo?.addSpeech(wav) }
    }

    fun stopCapture() {
        handler.post {
            if (projection != null) projection?.stop() // triggers onStop -> release()
            else release()
        }
    }

    private fun release() {
        instance = null
        demo?.stop()
        demo = null
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
        notifyState(false, null)
    }

    private fun fail(msg: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyState(false, msg)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopCapture()
        super.onTaskRemoved(rootIntent)
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
            .setContentTitle("Claude Panel")
            .setContentText("Claude can see your view when you send a message")
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun notifyState(running: Boolean, error: String?) {
        main.post { listener?.invoke(running, error) }
    }

    companion object {
        private const val TAG = "ClaudePanel"
        private const val CHANNEL = "capture"
        private const val MAX_EDGE = 1568
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile var instance: CaptureService? = null

        /** (running, error) - called on the main thread whenever capture starts, stops or fails. */
        var listener: ((Boolean, String?) -> Unit)? = null
    }
}
