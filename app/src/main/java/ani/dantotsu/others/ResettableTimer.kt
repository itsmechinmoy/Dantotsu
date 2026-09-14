package ani.dantotsu.others

import android.os.Handler
import android.os.Looper

class ResettableTimer(private val handler: Handler = Handler(Looper.getMainLooper())) {
    private var pendingRunnable: Runnable? = null

    fun reset(action: Runnable, delay: Long) {
        cancel()
        val runnable = Runnable {
            action.run()
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    fun cancel() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
    }
}