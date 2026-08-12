package ani.dantotsu.media.anime.player

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.math.MathUtils.clamp
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.brightnessConverter
import ani.dantotsu.circularReveal
import ani.dantotsu.dp
import ani.dantotsu.getCurrentBrightnessValue
import ani.dantotsu.hideSystemBars
import ani.dantotsu.others.ResettableTimer
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toPx
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@UnstableApi
class PlayerGestureManager(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val exoBrightnessCont: View,
    private val exoVolumeCont: View,
    private val exoBrightness: Slider,
    private val exoVolume: Slider,
    private val getPlayer: () -> ExoPlayer?,
    private val isPlayerInitialized: () -> Boolean
) {

    private val handler = Handler(Looper.getMainLooper())
    private var notchHeight = 0
    var isLocked = false
    private var isSeeking = false
    private var isFastForwarding = false
    private var seekTimesF = 0
    private var seekTimesR = 0
    private val seekTimerF = ResettableTimer()
    private val seekTimerR = ResettableTimer()

    private var brightnessTimer = Timer()
    private var volumeTimer = Timer()

    private var fastForwardStartX = 0f
    private var fastForwardInitialSpeed = 1f
    private var fastForwardOriginalSpeed = 1f
    private var lastFastForwardSpeed = 1f

    private val minLongPressSpeed = 0.25f
    private val maxLongPressSpeed = 4f
    private val dragSpeedSensitivity = 4f
    private val minSpeedUpdateDelta = 0.01f
    private val horizontalDeadZoneRatio = 0.03f

    fun updateNotchHeight(height: Int) {
        this.notchHeight = height
        checkNotch()
    }

    fun checkNotch() {
        if (notchHeight != 0) {
            val orientation = activity.resources.configuration.orientation
            playerView
                .findViewById<View>(R.id.exo_controller_margin)
                ?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        marginStart = notchHeight
                        marginEnd = notchHeight
                        topMargin = 0
                    } else {
                        topMargin = notchHeight
                        marginStart = 0
                        marginEnd = 0
                    }
                }
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering)?.translationY =
                (if (orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else (notchHeight + 8.toPx)).dp
            exoBrightnessCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd = if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
            exoVolumeCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
        }
    }

    fun handleController() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) {
            return
        }
        val overshoot = AnimationUtils.loadInterpolator(activity, R.anim.over_shoot)
        val controllerDuration = (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()

        if (playerView.isControllerFullyVisible) {
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_controller), "alpha", 1f, 0f)
                .setDuration(controllerDuration).start()
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_bottom_cont), "translationY", 0f, 128f).apply {
                interpolator = overshoot
                duration = controllerDuration
                start()
            }
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_timeline_cont), "translationY", 0f, 128f).apply {
                interpolator = overshoot
                duration = controllerDuration
                start()
            }
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_top_cont), "translationY", 0f, -128f).apply {
                interpolator = overshoot
                duration = controllerDuration
                start()
            }
            playerView.postDelayed({ playerView.hideController() }, controllerDuration)
        } else {
            checkNotch()
            playerView.showController()
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_controller), "alpha", 0f, 1f)
                .setDuration(controllerDuration).start()
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_bottom_cont), "translationY", 128f, 0f).apply {
                interpolator = overshoot
                duration = controllerDuration
                start()
            }
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_timeline_cont), "translationY", 128f, 0f).apply {
                interpolator = overshoot
                duration = controllerDuration
                start()
            }
            ObjectAnimator.ofFloat(playerView.findViewById(R.id.exo_top_cont), "translationY", -128f, 0f).apply {
                interpolator = overshoot
                duration = controllerDuration
                start()
            }
        }
    }

    fun seek(forward: Boolean, event: MotionEvent? = null) {
        val player = getPlayer() ?: return
        val seekTime = PrefManager.getVal<Int>(PrefName.SeekTime)
        val forwardText = playerView.findViewById<TextView>(R.id.exo_fast_forward_anim)
        val rewindText = playerView.findViewById<TextView>(R.id.exo_fast_rewind_anim)
        val fastForwardCard = playerView.findViewById<View>(R.id.exo_fast_forward)
        val fastRewindCard = playerView.findViewById<View>(R.id.exo_fast_rewind)

        val (card, text) = if (forward) {
            val t = "+${seekTime * ++seekTimesF}"
            forwardText.text = t
            handler.post { player.seekTo(player.currentPosition + seekTime * 1000) }
            fastForwardCard to forwardText
        } else {
            val t = "-${seekTime * ++seekTimesR}"
            rewindText.text = t
            handler.post { player.seekTo(player.currentPosition - seekTime * 1000) }
            fastRewindCard to rewindText
        }

        val showCardAnim = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f).setDuration(300)
        val showTextAnim = ObjectAnimator.ofFloat(text, "alpha", 0f, 1f).setDuration(150)

        fun startAnim() {
            showTextAnim.start()
            (text.compoundDrawables[1] as? Animatable)?.apply {
                if (!isRunning) start()
            }
            if (!isSeeking && event != null) {
                playerView.hideController()
                card.circularReveal(event.x.toInt(), event.y.toInt(), !forward, 800)
                showCardAnim.start()
            }
        }

        fun stopAnim() {
            handler.post {
                showCardAnim.cancel()
                showTextAnim.cancel()
                ObjectAnimator.ofFloat(card, "alpha", card.alpha, 0f).setDuration(150).start()
                ObjectAnimator.ofFloat(text, "alpha", 1f, 0f).setDuration(150).start()
            }
        }

        startAnim()
        isSeeking = true

        if (forward) {
            seekTimerR.reset(
                object : TimerTask() {
                    override fun run() {
                        isSeeking = false
                        stopAnim()
                        seekTimesF = 0
                    }
                },
                850
            )
        } else {
            seekTimerF.reset(
                object : TimerTask() {
                    override fun run() {
                        isSeeking = false
                        stopAnim()
                        seekTimesR = 0
                    }
                },
                850
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initGestures() {
        val audioManager = activity.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val gestureSpeed = (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()

        val brightnessRunnable = Runnable {
            if (exoBrightnessCont.alpha == 1f) {
                activity.lifecycleScope.launch {
                    ObjectAnimator.ofFloat(exoBrightnessCont, "alpha", 1f, 0f)
                        .setDuration(gestureSpeed)
                        .start()
                    delay(gestureSpeed)
                    exoBrightnessCont.visibility = View.GONE
                    checkNotch()
                }
            }
        }

        val volumeRunnable = Runnable {
            if (exoVolumeCont.alpha == 1f) {
                activity.lifecycleScope.launch {
                    ObjectAnimator.ofFloat(exoVolumeCont, "alpha", 1f, 0f)
                        .setDuration(gestureSpeed)
                        .start()
                    delay(gestureSpeed)
                    exoVolumeCont.visibility = View.GONE
                    checkNotch()
                }
            }
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.GONE) {
                    activity.hideSystemBars()
                    brightnessRunnable.run()
                    volumeRunnable.run()
                }
            }
        )

        playerView.findViewById<View>(R.id.exo_full_area)?.setOnClickListener {
            handleController()
        }

        if (!PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            playerView.findViewById<View>(R.id.exo_fast_forward_button_cont)?.visibility = View.VISIBLE
            playerView.findViewById<View>(R.id.exo_fast_rewind_button_cont)?.visibility = View.VISIBLE
            playerView.findViewById<View>(R.id.exo_fast_forward_button)?.setOnClickListener {
                if (isPlayerInitialized()) seek(true)
            }
            playerView.findViewById<View>(R.id.exo_fast_rewind_button)?.setOnClickListener {
                if (isPlayerInitialized()) seek(false)
            }
        }

        if (PrefManager.getVal<Boolean>(PrefName.Gestures) || PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            fun doubleTap(forward: Boolean, event: MotionEvent) {
                if (!isLocked && isPlayerInitialized() && PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
                    seek(forward, event)
                }
            }

            fun brightnessHide() {
                brightnessTimer.cancel()
                brightnessTimer.purge()
                brightnessTimer = Timer()
                brightnessTimer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            handler.post(brightnessRunnable)
                        }
                    },
                    3000
                )
            }

            exoBrightness.value = (getCurrentBrightnessValue(activity) * 10f)
            exoBrightness.addOnChangeListener { _, value, _ ->
                val lp = activity.window.attributes
                lp.screenBrightness = brightnessConverter((value.takeIf { !it.isNaN() } ?: 0f) / 10, false)
                activity.window.attributes = lp
                brightnessHide()
            }

            val volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC)
            exoVolume.value = audioManager.getStreamVolume(STREAM_MUSIC).toFloat() / volumeMax * 10

            fun volumeHide() {
                volumeTimer.cancel()
                volumeTimer.purge()
                volumeTimer = Timer()
                volumeTimer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            handler.post(volumeRunnable)
                        }
                    },
                    3000
                )
            }

            exoVolume.addOnChangeListener { _, value, _ ->
                val volume = ((value.takeIf { !it.isNaN() } ?: 0f) / 10 * volumeMax).roundToInt()
                audioManager.setStreamVolume(STREAM_MUSIC, volume, 0)
                volumeHide()
            }

            val fastForward = playerView.findViewById<TextView>(R.id.exo_fast_forward_text)

            fun updateFastForwardText(speed: Float) {
                fastForward.text = String.format(Locale.US, "%.2fx", speed)
            }

            fun fastForward(event: MotionEvent) {
                val player = getPlayer() ?: return
                isFastForwarding = true
                fastForwardStartX = event.rawX
                fastForwardOriginalSpeed = player.playbackParameters.speed
                fastForwardInitialSpeed = clamp(fastForwardOriginalSpeed * 2f, minLongPressSpeed, maxLongPressSpeed)
                player.setPlaybackSpeed(fastForwardInitialSpeed)
                lastFastForwardSpeed = fastForwardInitialSpeed
                fastForward.visibility = View.VISIBLE
                updateFastForwardText(player.playbackParameters.speed)
            }

            fun updateFastForwardSpeed(event: MotionEvent) {
                val player = getPlayer() ?: return
                if (!isFastForwarding) return
                val width = playerView.width.toFloat().takeIf { it > 0f } ?: return
                val deltaX = event.rawX - fastForwardStartX
                if (abs(deltaX) < width * horizontalDeadZoneRatio) return
                val deltaRatio = deltaX / width
                val targetSpeed = clamp(
                    fastForwardInitialSpeed + (deltaRatio * dragSpeedSensitivity),
                    minLongPressSpeed,
                    maxLongPressSpeed
                )
                if (abs(targetSpeed - lastFastForwardSpeed) < minSpeedUpdateDelta) return
                player.setPlaybackSpeed(targetSpeed)
                lastFastForwardSpeed = targetSpeed
                updateFastForwardText(player.playbackParameters.speed)
            }

            fun stopFastForward() {
                val player = getPlayer()
                if (isFastForwarding && player != null) {
                    isFastForwarding = false
                    player.setPlaybackSpeed(fastForwardOriginalSpeed)
                    fastForward.visibility = View.GONE
                }
            }

            val fastRewindDetector = GestureDetector(
                activity,
                object : GesturesListener() {
                    override fun onLongClick(event: MotionEvent) {
                        if (PrefManager.getVal(PrefName.FastForward)) fastForward(event)
                    }

                    override fun onDoubleClick(event: MotionEvent) {
                        doubleTap(false, event)
                    }

                    override fun onScrollYClick(y: Float) {
                        if (!isLocked && PrefManager.getVal(PrefName.Gestures)) {
                            exoBrightness.value = clamp(exoBrightness.value + y / 100, 0f, 10f)
                            if (exoBrightnessCont.visibility != View.VISIBLE) {
                                exoBrightnessCont.visibility = View.VISIBLE
                            }
                            exoBrightnessCont.alpha = 1f
                        }
                    }

                    override fun onSingleClick(event: MotionEvent) =
                        if (isSeeking) doubleTap(false, event) else handleController()
                }
            )

            val rewindArea = playerView.findViewById<View>(R.id.exo_rewind_area)
            rewindArea?.isClickable = true
            rewindArea?.setOnTouchListener { v, event ->
                fastRewindDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
                }
                v.performClick()
                true
            }

            val fastForwardDetector = GestureDetector(
                activity,
                object : GesturesListener() {
                    override fun onLongClick(event: MotionEvent) {
                        if (PrefManager.getVal(PrefName.FastForward)) fastForward(event)
                    }

                    override fun onDoubleClick(event: MotionEvent) {
                        doubleTap(true, event)
                    }

                    override fun onScrollYClick(y: Float) {
                        if (!isLocked && PrefManager.getVal(PrefName.Gestures)) {
                            exoVolume.value = clamp(exoVolume.value + y / 100, 0f, 10f)
                            if (exoVolumeCont.visibility != View.VISIBLE) {
                                exoVolumeCont.visibility = View.VISIBLE
                            }
                            exoVolumeCont.alpha = 1f
                        }
                    }

                    override fun onSingleClick(event: MotionEvent) =
                        if (isSeeking) doubleTap(true, event) else handleController()
                }
            )

            val forwardArea = playerView.findViewById<View>(R.id.exo_forward_area)
            forwardArea?.isClickable = true
            forwardArea?.setOnTouchListener { v, event ->
                fastForwardDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
                }
                v.performClick()
                true
            }
        }
    }
}
