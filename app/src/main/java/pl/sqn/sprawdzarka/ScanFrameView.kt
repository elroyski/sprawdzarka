package pl.sqn.sprawdzarka

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat

/** Celownik nad podglądem kamery: narożniki ramki i przesuwająca się linia skanowania. */
class ScanFrameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.sqn_blue)

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerPath = Path()

    private var lineProgress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { lineProgress = it.animatedValue as Float; invalidate() }
    }

    /** Kolor narożników – na moment zmienia się po udanym odczycie. */
    var highlighted = false
        set(value) {
            field = value
            cornerPaint.color = if (value) accent else 0xFFFFFFFF.toInt()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Ramka w kształcie kodu kreskowego: szeroka, niższa niż podgląd.
        val frameW = w * 0.8f
        val frameH = minOf(h * 0.6f, frameW * 0.6f)
        val l = (w - frameW) / 2
        val t = (h - frameH) / 2
        val r = l + frameW
        val b = t + frameH
        val len = 32 * density

        cornerPath.reset()
        cornerPath.moveTo(l, t + len); cornerPath.lineTo(l, t); cornerPath.lineTo(l + len, t)
        cornerPath.moveTo(r - len, t); cornerPath.lineTo(r, t); cornerPath.lineTo(r, t + len)
        cornerPath.moveTo(r, b - len); cornerPath.lineTo(r, b); cornerPath.lineTo(r - len, b)
        cornerPath.moveTo(l + len, b); cornerPath.lineTo(l, b); cornerPath.lineTo(l, b - len)

        frame[0] = l; frame[1] = t; frame[2] = r; frame[3] = b
        linePaint.shader = LinearGradient(
            l, 0f, r, 0f,
            intArrayOf(0x00000000, accent, 0x00000000), null, Shader.TileMode.CLAMP
        )
    }

    private val frame = FloatArray(4)

    override fun onDraw(canvas: Canvas) {
        val (l, t, r, b) = frame
        val inset = 12 * density
        val y = t + inset + (b - t - 2 * inset) * lineProgress
        canvas.drawRect(l + inset, y - 1.5f * density, r - inset, y + 1.5f * density, linePaint)
        canvas.drawPath(cornerPath, cornerPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
