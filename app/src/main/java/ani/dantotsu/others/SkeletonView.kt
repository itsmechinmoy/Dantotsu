package ani.dantotsu.others

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import ani.dantotsu.getThemeColor
import kotlin.math.ceil

/**
 * Loading placeholder: a row of cover tiles with a sweeping highlight (android:tag="covers").
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val surface = context.getThemeColor(com.google.android.material.R.attr.colorSurface)
    private val ink = context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)

    private val blockColor = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 28), surface)
    private val shineColor = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 8), surface)

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shift = Matrix()
    private var shader: LinearGradient? = null
    private var shown = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun wantedDp() = COVER_ROW_DP

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((wantedDp() * dp).toInt(), heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shader = LinearGradient(
            0f, 0f, w * BAND, w * BAND * 0.35f,
            intArrayOf(blockColor, shineColor, blockColor), null, Shader.TileMode.CLAMP
        )
        blockPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        // clock-driven so it still moves with animations disabled
        val phase = (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        shader?.let {
            shift.setTranslate((-BAND + phase * (1f + 2 * BAND)) * width, 0f)
            it.setLocalMatrix(shift)
        }
        drawCovers(canvas)
        if (shown) postInvalidateOnAnimation()
    }

    private fun drawCovers(canvas: Canvas) {
        val tileWidth = COVER_W_DP * dp
        val tileHeight = COVER_H_DP * dp
        val gap = 16 * dp
        val bar = 12 * dp
        val top = ((height - (COVER_H_DP + 36) * dp) / 2).coerceAtLeast(0f)
        blockPaint.alpha = 255
        for (i in 0 until ceil(width / (tileWidth + gap)).toInt()) {
            val left = 24 * dp + i * (tileWidth + gap)
            canvas.drawRoundRect(left, top, left + tileWidth, top + tileHeight, 16 * dp, 16 * dp, blockPaint)
            pill(canvas, left, top + tileHeight + 12 * dp, left + tileWidth * 0.8f, bar)
        }
    }

    private fun pill(canvas: Canvas, left: Float, top: Float, right: Float, height: Float) {
        canvas.drawRoundRect(left, top, right, top + height, height / 2, height / 2, blockPaint)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) postInvalidateOnAnimation()
    }

    private companion object {
        const val SWEEP_MS = 1400L
        const val BAND = 0.6f
        const val COVER_W_DP = 102
        const val COVER_H_DP = 154
        const val COVER_ROW_DP = 228
    }
}
