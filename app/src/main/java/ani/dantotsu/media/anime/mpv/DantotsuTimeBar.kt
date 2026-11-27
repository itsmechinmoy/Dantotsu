package ani.dantotsu.media.anime.mpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import kotlin.math.roundToLong

/**
 * Lightweight seekbar replacing Media3's DefaultTimeBar, with chapter/AniSkip ad markers,
 * buffered indicator, touch scrubbing, and zero Media3 dependency.
 */
class DantotsuTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface OnScrubListener {
        fun onScrubStart(timeBar: DantotsuTimeBar, position: Long)
        fun onScrubMove(timeBar: DantotsuTimeBar, position: Long)
        fun onScrubStop(timeBar: DantotsuTimeBar, position: Long, canceled: Boolean)
    }

    private var duration: Long = 0L
    private var position: Long = 0L
    private var bufferedPosition: Long = 0L
    private var scrubPosition: Long = 0L
    private var scrubbing = false

    private var adGroupTimesMs: LongArray? = null
    private var playedAdGroups: BooleanArray? = null

    private val listeners = mutableListOf<OnScrubListener>()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeCap = Paint.Cap.ROUND
    }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = runCatching { context.getThemeColor(androidx.appcompat.R.attr.colorPrimary) }.getOrDefault(0xFFBB86FC.toInt())
        strokeCap = Paint.Cap.ROUND
    }
    private val adMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
    }
    private val playedAdMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
    }
    private val scrubberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = runCatching { context.getThemeColor(androidx.appcompat.R.attr.colorPrimary) }.getOrDefault(0xFFBB86FC.toInt())
    }

    private val density = context.resources.displayMetrics.density
    private val barHeightPx = 3f * density
    private val thumbRadiusNormal = 6f * density
    private val thumbRadiusScrub = 9f * density
    private val markerWidthPx = 3f * density

    fun addListener(listener: OnScrubListener) {
        if (!listeners.contains(listener)) listeners += listener
    }

    fun setForceDisabled(forceDisabled: Boolean) {
        isEnabled = !forceDisabled
    }

    fun removeListener(listener: OnScrubListener) {
        listeners -= listener
    }

    fun setDuration(durationMs: Long) {
        duration = durationMs.coerceAtLeast(0L)
        invalidate()
    }

    fun setPosition(positionMs: Long) {
        if (!scrubbing) {
            position = positionMs.coerceAtLeast(0L)
            invalidate()
        }
    }

    fun setBufferedPosition(bufferedPositionMs: Long) {
        bufferedPosition = bufferedPositionMs.coerceAtLeast(0L)
        invalidate()
    }

    fun setAdGroupTimesMs(extraAdGroupTimesMs: LongArray?, extraPlayedAdGroups: BooleanArray?, extraAdGroupCount: Int) {
        if (extraAdGroupCount <= 0 || extraAdGroupTimesMs == null) {
            adGroupTimesMs = null
            playedAdGroups = null
        } else {
            adGroupTimesMs = extraAdGroupTimesMs.copyOf(extraAdGroupCount)
            playedAdGroups = extraPlayedAdGroups?.copyOf(extraAdGroupCount) ?: BooleanArray(extraAdGroupCount)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = paddingLeft + thumbRadiusScrub
        val padRight = paddingRight + thumbRadiusScrub
        val barWidth = (width - padLeft - padRight).coerceAtLeast(1f)
        val centerY = height / 2f
        val top = centerY - barHeightPx / 2f
        val bottom = centerY + barHeightPx / 2f

        canvas.drawRoundRect(padLeft, top, padLeft + barWidth, bottom, barHeightPx, barHeightPx, trackPaint)

        if (duration > 0L) {
            val bufFrac = (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            canvas.drawRoundRect(padLeft, top, padLeft + barWidth * bufFrac, bottom, barHeightPx, barHeightPx, bufferedPaint)

            val activePos = if (scrubbing) scrubPosition else position
            val playFrac = (activePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val playEnd = padLeft + barWidth * playFrac
            canvas.drawRoundRect(padLeft, top, playEnd, bottom, barHeightPx, barHeightPx, playedPaint)

            adGroupTimesMs?.forEachIndexed { idx, timeMs ->
                val frac = (timeMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val x = padLeft + barWidth * frac
                val paint = if (playedAdGroups?.getOrNull(idx) == true) playedAdMarkerPaint else adMarkerPaint
                canvas.drawRect(x - markerWidthPx / 2f, top - density, x + markerWidthPx / 2f, bottom + density, paint)
            }

            val radius = if (scrubbing) thumbRadiusScrub else thumbRadiusNormal
            canvas.drawCircle(playEnd, centerY, radius, scrubberPaint)
        } else {
            canvas.drawCircle(padLeft, centerY, thumbRadiusNormal, scrubberPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0L) return false
        val padLeft = paddingLeft + thumbRadiusScrub
        val padRight = paddingRight + thumbRadiusScrub
        val barWidth = (width - padLeft - padRight).coerceAtLeast(1f)
        val frac = ((event.x - padLeft) / barWidth).coerceIn(0f, 1f)
        val targetPos = (frac * duration.toFloat()).roundToLong()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                scrubPosition = targetPos
                listeners.forEach { it.onScrubStart(this, targetPos) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) {
                    scrubPosition = targetPos
                    listeners.forEach { it.onScrubMove(this, targetPos) }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    val canceled = event.action == MotionEvent.ACTION_CANCEL
                    scrubbing = false
                    if (!canceled) position = targetPos
                    listeners.forEach { it.onScrubStop(this, targetPos, canceled) }
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
