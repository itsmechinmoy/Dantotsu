package ani.dantotsu.media.novel.novelreader

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

class NovelReaderAutoScroll {

    var speedSeconds: Float = 3f
    var isRunning: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var scrollRunnable: Runnable? = null
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

        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                wv.scrollBy(0, pxPerTick)
                handler.postDelayed(this, tickMs)
            }
        }
        scrollRunnable = runnable
        handler.postDelayed(runnable, tickMs)
    }

    fun stop() {
        isRunning = false
        scrollRunnable?.let { handler.removeCallbacks(it) }
        scrollRunnable = null
    }

    fun toggle(): Boolean {
        return if (isRunning) { stop(); false } else { start(); true }
    }

    fun destroy() {
        stop()
        webView = null
    }
}