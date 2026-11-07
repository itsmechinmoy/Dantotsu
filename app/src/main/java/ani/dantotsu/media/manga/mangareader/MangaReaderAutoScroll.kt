package ani.dantotsu.media.manga.mangareader

import android.view.Choreographer
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.settings.CurrentReaderSettings

class MangaReaderAutoScroll {

    var speed: Float = 3f
    var isRunning: Boolean = false
        private set

    private var recyclerView: RecyclerView? = null
    private var direction: CurrentReaderSettings.Directions = CurrentReaderSettings.Directions.TOP_TO_BOTTOM

    private var lastFrameTimeNanos: Long = 0L
    private var accumulatedScroll = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val rv = recyclerView
            if (!isRunning || rv == null) return

            if (lastFrameTimeNanos != 0L) {
                val dt = (frameTimeNanos - lastFrameTimeNanos).coerceAtMost(50_000_000L) / 1_000_000_000f
                // Standardize speed relative to 60fps base:
                // At 60Hz:  dt ~ 0.0166s -> 60 * dt ~ 1.0 -> speed pixels/frame
                // At 90Hz:  dt ~ 0.0111s -> 60 * dt ~ 0.67 -> smoother micro-steps
                // At 120Hz: dt ~ 0.0083s -> 60 * dt ~ 0.5 -> 2x smoother micro-steps
                accumulatedScroll += speed * 60f * dt
                val pixelsToScroll = accumulatedScroll.toInt()

                if (pixelsToScroll != 0) {
                    accumulatedScroll -= pixelsToScroll

                    when (direction) {
                        CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> rv.scrollBy(0, pixelsToScroll)
                        CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> rv.scrollBy(0, -pixelsToScroll)
                        CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> rv.scrollBy(pixelsToScroll, 0)
                        CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> rv.scrollBy(-pixelsToScroll, 0)
                    }
                }
            }
            lastFrameTimeNanos = frameTimeNanos

            if (isRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun attach(rv: RecyclerView, dir: CurrentReaderSettings.Directions) {
        recyclerView = rv
        direction = dir
    }

    fun start() {
        if (isRunning) stop()
        if (recyclerView == null) return
        isRunning = true
        accumulatedScroll = 0f
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        isRunning = false
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun toggle(): Boolean {
        return if (isRunning) {
            stop()
            false
        } else {
            start()
            true
        }
    }

    fun destroy() {
        stop()
        recyclerView = null
    }
}
