package ani.dantotsu.media.novel.novelreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovelReaderOverlayManager(private val root: FrameLayout) {

    private val statusBar = LinearLayout(root.context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 8, 24, 8)
        elevation = 800f
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = 160 }
    }

    private val timeView = TextView(root.context).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val batteryView = TextView(root.context).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }

    private val progressView = TextView(root.context).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
        setPadding(16, 6, 16, 6)
        elevation = 800f
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { bottomMargin = 160; marginEnd = 16 }
    }

    var showStatusBar: Boolean = false
        set(value) {
            field = value
            statusBar.visibility = if (value) View.VISIBLE else View.GONE
            if (value) startClock() else stopClock()
        }

    var showReadingProgress: Boolean = false
        set(value) {
            field = value
            progressView.visibility = if (value) View.VISIBLE else View.GONE
        }

    var progressFraction: Float = 0f
        set(value) {
            field = value
            progressView.text = "${(value * 100).toInt()}%"
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockRunnable: Runnable? = null
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun startClock() {
        stopClock()
        val runnable = object : Runnable {
            override fun run() {
                timeView.text = timeFmt.format(Date())
                clockHandler.postDelayed(this, 30_000L)
            }
        }
        clockRunnable = runnable
        clockHandler.post(runnable)
    }

    private fun stopClock() {
        clockRunnable?.let { clockHandler.removeCallbacks(it) }
        clockRunnable = null
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryView.text = "${(level * 100 / scale)}%"
            }
        }
    }

    fun attach() {
        statusBar.addView(timeView)
        statusBar.addView(batteryView)
        root.addView(statusBar)
        root.addView(progressView)

        root.context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        timeView.text = timeFmt.format(Date())
    }

    fun destroy() {
        stopClock()
        try { root.context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        root.removeView(statusBar)
        root.removeView(progressView)
    }
}