package ani.dantotsu.media.novel.novelreader

import android.webkit.WebView
import java.util.Timer
import java.util.TimerTask

class NovelReaderAutoScroll {

    var speedSeconds: Float = 3f
    var isRunning: Boolean = false
        private set

    private var timer: Timer? = null
    private var webView: WebView? = null

    fun attach(wv: WebView) {
        webView = wv
    }

    fun start() {
        if (isRunning) stop()
        val wv = webView ?: return
        isRunning = true
        val tickMs = 50L
        val pxPerTick = (wv.context.resources.displayMetrics.heightPixels /
                speedSeconds.coerceAtLeast(0.5f) * tickMs / 1000f).toInt().coerceAtLeast(1)

        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                wv.post { wv.scrollBy(0, pxPerTick) }
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
        webView = null
    }
}