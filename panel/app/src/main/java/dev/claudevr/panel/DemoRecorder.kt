package dev.claudevr.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Records the captured view to an MP4 for demo videos. Android 14 allows only
 * one virtual display per MediaProjection, so instead of a second capture this
 * draws each frame the ImageReader already receives into a MediaRecorder
 * surface. Claude's spoken replies are saved alongside as speech-<ms>.wav
 * (offset from the start) so they can be mixed in afterwards.
 */
class DemoRecorder(context: Context, val dir: File, width: Int, height: Int) {

    // Hardware encoders are happiest with dimensions that are multiples of 16.
    private val videoWidth = (width + 15) / 16 * 16
    private val videoHeight = (height + 15) / 16 * 16
    private val dst = Rect(0, 0, videoWidth, videoHeight)
    private val startedAt = SystemClock.elapsedRealtime()

    @Suppress("DEPRECATION")
    private val recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setVideoSize(videoWidth, videoHeight)
        setVideoFrameRate(30)
        setVideoEncodingBitRate(12_000_000)
        setOutputFile(File(dir, "video.mp4"))
        prepare()
        start()
    }
    private val surface = recorder.surface

    /** Draws one captured frame into the video. Needs an ImageReader with GPU-sampled usage. */
    fun addFrame(img: Image) {
        val buffer = img.hardwareBuffer ?: return
        try {
            val bmp = Bitmap.wrapHardwareBuffer(buffer, null) ?: return
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawBitmap(bmp, null, dst, null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame dropped", e)
        } finally {
            buffer.close()
        }
    }

    /** Keeps a copy of a spoken reply, named by its offset from the start of the recording. */
    fun addSpeech(wav: File) {
        val offset = SystemClock.elapsedRealtime() - startedAt
        wav.copyTo(File(dir, "speech-%07d.wav".format(offset)), overwrite = true)
    }

    fun stop() {
        try {
            recorder.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "recorder stop failed (too short?)", e)
        }
        recorder.release()
    }

    companion object {
        private const val TAG = "ClaudePanel"
    }
}
