package ani.dantotsu.media.manga.mangareader

import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.settings.CurrentReaderSettings
import java.util.Timer
import java.util.TimerTask

class MangaReaderAutoScroll {

    var speedSeconds: Float = 3f
    var isRunning: Boolean = false
        private set

    private var timer: Timer? = null
    private var recyclerView: RecyclerView? = null
    private var direction: CurrentReaderSettings.Directions = CurrentReaderSettings.Directions.TOP_TO_BOTTOM

    fun attach(rv: RecyclerView, dir: CurrentReaderSettings.Directions) {
        recyclerView = rv
        direction = dir
    }

    fun start() {
        if (isRunning) stop()
        val rv = recyclerView ?: return
        isRunning = true
        val tickMs = 50L
        
        val displayMetrics = rv.context.resources.displayMetrics
        val baseSize = if (direction == CurrentReaderSettings.Directions.TOP_TO_BOTTOM || direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP) {
            displayMetrics.heightPixels
        } else {
            displayMetrics.widthPixels
        }

        val pxPerTick = (baseSize / speedSeconds.coerceAtLeast(0.5f) * tickMs / 1000f).toInt().coerceAtLeast(1)

        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                rv.post {
                    when (direction) {
                        CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> rv.scrollBy(0, pxPerTick)
                        CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> rv.scrollBy(0, -pxPerTick)
                        CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> rv.scrollBy(pxPerTick, 0)
                        CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> rv.scrollBy(-pxPerTick, 0)
                    }
                }
            }
        }, tickMs, tickMs)
    }

    fun stop() {
        isRunning = false
        timer?.cancel()
        timer?.purge()
        timer = null
    }

    fun toggle(): Boolean {
        return if (isRunning) { stop(); false } else { start(); true }
    }

    fun destroy() {
        stop()
        recyclerView = null
    }
}
