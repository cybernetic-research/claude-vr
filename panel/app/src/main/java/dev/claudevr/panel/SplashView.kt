package dev.claudevr.panel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * Launch splash: the headset from the app icon, with Claude's orange
 * spark spinning into place in front of it. Geometry is in the icon's
 * 108-unit space and scaled to the view.
 */
class SplashView(context: Context) : View(context) {

    private val bg = context.getColor(R.color.brand_bg)
    private val headsetColor = context.getColor(R.color.headset)
    private val strapColor = context.getColor(R.color.strap)
    private val sparkColor = context.getColor(R.color.spark)

    private val visor = Path().apply {
        moveTo(37f, 39f); lineTo(71f, 39f)
        arcTo(RectF(59f, 39f, 83f, 63f), 270f, 90f); lineTo(83f, 61f)
        arcTo(RectF(59f, 49f, 83f, 73f), 0f, 90f); lineTo(61f, 73f)
        lineTo(54f, 66f); lineTo(47f, 73f); lineTo(37f, 73f)
        arcTo(RectF(25f, 49f, 49f, 73f), 90f, 90f); lineTo(25f, 51f)
        arcTo(RectF(25f, 39f, 49f, 63f), 180f, 90f); close()
    }
    private val strap = RectF(17.5f, 49.5f, 90.5f, 58.5f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private var sparkT = 0f // 0..1 spin-in
    private var textT = 0f // 0..1 fade-in

    private val sparkAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        startDelay = 150
        interpolator = OvershootInterpolator(1.6f)
        addUpdateListener { sparkT = it.animatedValue as Float; invalidate() }
    }
    private val textAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 500
        startDelay = 700
        addUpdateListener { textT = it.animatedValue as Float; invalidate() }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // BlurMaskFilter needs a software layer
        isClickable = true // swallow taps while visible
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sparkAnim.start()
        textAnim.start()
    }

    override fun onDetachedFromWindow() {
        sparkAnim.cancel()
        textAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        val unit = min(width, height) * 0.62f / 108f
        val cx = width / 2f
        val cy = height * 0.44f

        canvas.save()
        canvas.translate(cx - 54f * unit, cy - 54f * unit)
        canvas.scale(unit, unit)

        fill.color = strapColor
        canvas.drawRoundRect(strap, 4.5f, 4.5f, fill)
        fill.color = headsetColor
        canvas.drawPath(visor, fill)

        // Spark spins and grows into place over the visor.
        canvas.save()
        canvas.translate(54f, 54f)
        canvas.rotate(-135f * (1f - sparkT))
        val r = 10f * sparkT.coerceAtLeast(0f)
        glow.color = sparkColor
        glow.strokeWidth = 7f
        glow.alpha = (110 * sparkT.coerceIn(0f, 1f)).toInt()
        drawSpark(canvas, r, glow)
        stroke.color = sparkColor
        stroke.strokeWidth = 4f
        drawSpark(canvas, r, stroke)
        canvas.restore()
        canvas.restore()

        val alpha = (255 * textT).toInt()
        title.color = headsetColor
        title.alpha = alpha
        title.textSize = 40f * resources.displayMetrics.density
        canvas.drawText("Claude Panel", cx, cy + 46f * unit + title.textSize, title)
        subtitle.color = 0xFF9A968C.toInt()
        subtitle.alpha = alpha
        subtitle.textSize = 16f * resources.displayMetrics.density
        canvas.drawText(
            "Claude, floating in your headset",
            cx, cy + 46f * unit + title.textSize + subtitle.textSize * 2f, subtitle
        )
    }

    private fun drawSpark(canvas: Canvas, r: Float, paint: Paint) {
        if (r <= 0f) return
        val d = r * 0.707f
        canvas.drawLine(0f, -r, 0f, r, paint)
        canvas.drawLine(-r, 0f, r, 0f, paint)
        canvas.drawLine(-d, -d, d, d, paint)
        canvas.drawLine(d, -d, -d, d, paint)
    }
}
