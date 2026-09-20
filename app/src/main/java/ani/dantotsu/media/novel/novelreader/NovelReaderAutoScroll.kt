package ani.dantotsu.media.novel.novelreader

import android.view.Choreographer
import android.view.View

class NovelReaderAutoScroll {

    var speed: Float = 3f
    var isRunning: Boolean = false
        private set

    private var targetView: View? = null
    private var scrollConsumer: ((Int) -> Unit)? = null
    private var lastFrameTimeNanos: Long = 0L
    private var accumulatedScroll = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (lastFrameTimeNanos != 0L) {
                val dt = ((frameTimeNanos - lastFrameTimeNanos).coerceAtMost(50_000_000L)) / 1_000_000_000f
                // Standardize speed relative to 60fps base:
                accumulatedScroll += speed * 60f * dt
                val pixelsToScroll = accumulatedScroll.toInt()

                if (pixelsToScroll != 0) {
                    accumulatedScroll -= pixelsToScroll
                    scrollConsumer?.invoke(pixelsToScroll) ?: targetView?.scrollBy(0, pixelsToScroll)
                }
            }
            lastFrameTimeNanos = frameTimeNanos

            if (isRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun attach(view: View?, customConsumer: ((Int) -> Unit)? = null) {
        targetView = view
        scrollConsumer = customConsumer
    }

    fun start() {
        if (isRunning) stop()
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
        targetView = null
        scrollConsumer = null
    }
}
