package ani.dantotsu.media.anime.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import ani.dantotsu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Custom Dantotsu player container wrapping [MpvVideoView] inside Dantotsu's existing
 * `exo_player_view` + `exo_player_control_view` overlay with zero Media3 dependency.
 */
class DantotsuPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    fun interface ControllerVisibilityListener {
        fun onVisibilityChanged(visibility: Int)
    }

    val mpvView: MpvVideoView
    val contentFrame: FrameLayout
    val shutterView: View?
    val bufferingView: View?
    val controllerView: View
    val subtitleView: View?
    val timeBar: DantotsuTimeBar?
    val positionView: TextView?
    val durationView: TextView?
    val playPauseButton: ImageButton?

    val videoSurfaceView: View get() = mpvView

    var useController: Boolean = true
        set(value) {
            field = value
            if (!value) hideController()
        }

    var controllerShowTimeoutMs: Int = 5000

    var resizeMode: Int = 0
        set(value) {
            field = value
            val mode = when (value) {
                3 -> VideoScaleMode.STRETCH
                4 -> VideoScaleMode.ZOOM
                else -> VideoScaleMode.FIT
            }
            mpvView.setVideoScaleMode(mode)
        }

    val isControllerFullyVisible: Boolean
        get() = controllerView.isVisible

    private var visibilityListener: ControllerVisibilityListener? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideController() }
    private var uiScope: CoroutineScope? = null
    private var isScrubbing = false
    private var isCompact = false

    init {
        setBackgroundColor(0xFF000000.toInt())
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.mpv_player_view, this, true)

        contentFrame = findViewById(R.id.exo_content_frame)
        shutterView = findViewById(R.id.exo_shutter)
        bufferingView = findViewById(R.id.exo_buffering)
        subtitleView = findViewById(R.id.exo_subtitles)

        mpvView = MpvVideoView(context)
        contentFrame.addView(
            mpvView,
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        val placeholder = findViewById<View>(R.id.exo_controller_placeholder)
        controllerView = inflater.inflate(R.layout.mpv_player_control_view, this, false)
        val parentGroup = placeholder?.parent as? ViewGroup
        if (parentGroup != null) {
            val idx = parentGroup.indexOfChild(placeholder)
            controllerView.layoutParams = placeholder.layoutParams
            parentGroup.removeViewAt(idx)
            parentGroup.addView(controllerView, idx)
        } else {
            addView(controllerView)
        }

        timeBar = controllerView.findViewById(R.id.exo_progress)
        positionView = controllerView.findViewById(R.id.exo_position)
        durationView = controllerView.findViewById(R.id.exo_duration)
        playPauseButton = controllerView.findViewById(R.id.exo_play)

        timeBar?.addListener(object : DantotsuTimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: DantotsuTimeBar, position: Long) {
                isScrubbing = true
                cancelAutoHide()
                positionView?.text = formatTime(position)
            }

            override fun onScrubMove(timeBar: DantotsuTimeBar, position: Long) {
                positionView?.text = formatTime(position)
            }

            override fun onScrubStop(timeBar: DantotsuTimeBar, position: Long, canceled: Boolean) {
                isScrubbing = false
                if (!canceled) mpvView.seekTo(position)
                scheduleAutoHide()
            }
        })

        playPauseButton?.setOnClickListener {
            mpvView.togglePlayPause()
            scheduleAutoHide()
        }

        bindStateFlows()
    }

    private fun bindStateFlows() {
        uiScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope

        scope.launch {
            mpvView.currentPosition.collectLatest { pos ->
                if (!isScrubbing) {
                    timeBar?.setPosition(pos)
                    positionView?.text = formatTime(pos)
                }
            }
        }
        scope.launch {
            mpvView.duration.collectLatest { dur ->
                timeBar?.setDuration(dur)
                durationView?.text = formatTime(dur)
            }
        }
        scope.launch {
            mpvView.bufferedPosition.collectLatest { buf ->
                timeBar?.setBufferedPosition(buf)
            }
        }
        scope.launch {
            mpvView.playbackState.collectLatest { state ->
                bufferingView?.isVisible = state == PlaybackState.BUFFERING
                shutterView?.isVisible = state == PlaybackState.IDLE
                updatePlayPauseIcon(state == PlaybackState.PLAYING)
                if (state == PlaybackState.PLAYING && isControllerFullyVisible) {
                    scheduleAutoHide()
                } else if (state == PlaybackState.PAUSED) {
                    cancelAutoHide()
                }
            }
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        playPauseButton?.setImageResource(
            if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
        )
    }

    fun setControllerVisibilityListener(listener: ControllerVisibilityListener?) {
        this.visibilityListener = listener
    }

    fun showController() {
        if (!useController) return
        if (!controllerView.isVisible) {
            controllerView.isVisible = true
            visibilityListener?.onVisibilityChanged(View.VISIBLE)
        }
        scheduleAutoHide()
    }

    fun hideController() {
        cancelAutoHide()
        if (controllerView.isVisible) {
            controllerView.isVisible = false
            visibilityListener?.onVisibilityChanged(View.GONE)
        }
    }

    fun toggleController() {
        if (isControllerFullyVisible) hideController() else showController()
    }

    fun setExtraAdGroupMarkers(extraAdGroupTimesMs: LongArray?, extraPlayedAdGroups: BooleanArray?) {
        timeBar?.setAdGroupTimesMs(extraAdGroupTimesMs, extraPlayedAdGroups, extraAdGroupTimesMs?.size ?: 0)
    }

    fun setCompactMode(compact: Boolean) {
        isCompact = compact
        controllerView.findViewById<View>(R.id.exo_prev_ep)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_next_ep)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_lock)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_rotate)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_screenshot)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_pip)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_audio)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_ep_sel)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_video_info)?.isVisible = !compact
        controllerView.findViewById<View>(R.id.exo_timeline_cont)?.let { timeline ->
            val lp = timeline.layoutParams as? MarginLayoutParams ?: return@let
            val density = resources.displayMetrics.density
            lp.bottomMargin = ((if (compact) 42 else 36) * density).toInt()
            timeline.layoutParams = lp
        }
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        if (controllerShowTimeoutMs > 0 && mpvView.isPlaying.value) {
            hideHandler.postDelayed(hideRunnable, controllerShowTimeoutMs.toLong())
        }
    }

    private fun cancelAutoHide() {
        hideHandler.removeCallbacks(hideRunnable)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isCompact && event.action == MotionEvent.ACTION_UP) {
            toggleController()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        return mpvView.handleKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAutoHide()
        uiScope?.cancel()
        uiScope = null
    }

    companion object {
        fun formatTime(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
