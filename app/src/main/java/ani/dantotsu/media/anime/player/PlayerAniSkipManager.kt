package ani.dantotsu.media.anime.player

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.AniSkip.getType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString

@UnstableApi
class PlayerAniSkipManager(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val model: MediaDetailsViewModel,
    private val exoSkipOpEd: ImageButton,
    private val exoSkip: View,
    private val skipTimeButton: View,
    private val skipTimeText: TextView,
    private val timeStampText: TextView,
    private val getPlayer: () -> Player?
) {

    private val handler = Handler(Looper.getMainLooper())
    private var isUpdating = false
    private var currentTimeStamp: AniSkip.Stamp? = null
    private val skippedTimeStamps: MutableList<AniSkip.Stamp> = mutableListOf()
    private var countDownTimer: CountDownTimer? = null
    private var disappeared = false
    private var functionStarted = false
    var isTimeStampsLoaded = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTimeStamp()
            if (isUpdating) {
                handler.postDelayed(this, 500)
            }
        }
    }

    fun init() {
        setupTimeStampsObserver()
        setupSkipOpEdButton()
        if (PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            startTracking()
        }
    }

    private fun setupTimeStampsObserver() {
        model.timeStamps.observe(activity) { stamps ->
            isTimeStampsLoaded = true
            if (stamps != null) {
                val adGroups = stamps.flatMap {
                    listOf(
                        it.interval.startTime.toLong() * 1000,
                        it.interval.endTime.toLong() * 1000
                    )
                }.toLongArray()
                val playedAdGroups = stamps.flatMap {
                    listOf(false, false)
                }.toBooleanArray()
                playerView.setExtraAdGroupMarkers(adGroups, playedAdGroups)
                exoSkipOpEd.visibility = View.VISIBLE
            } else {
                exoSkipOpEd.visibility = View.GONE
            }
        }
    }

    private fun setupSkipOpEdButton() {
        exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        exoSkipOpEd.setOnClickListener {
            val enabled = PrefManager.getVal<Boolean>(PrefName.AutoSkipOPED)
            if (enabled) {
                snackString(activity.getString(R.string.disabled_auto_skip), activity)
                PrefManager.setVal(PrefName.AutoSkipOPED, false)
            } else {
                snackString(activity.getString(R.string.auto_skip), activity)
                PrefManager.setVal(PrefName.AutoSkipOPED, true)
            }
            exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        }
    }

    fun startTracking() {
        isUpdating = true
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    fun stopTracking() {
        isUpdating = false
        handler.removeCallbacks(updateRunnable)
        cancelTimer()
    }

    fun resetForNewEpisode() {
        disappeared = false
        functionStarted = false
        isTimeStampsLoaded = false
        skippedTimeStamps.clear()
        cancelTimer()
    }

    fun skipCurrentInterval() {
        val new = currentTimeStamp ?: return
        val player = getPlayer() ?: return
        player.seekTo((new.interval.endTime * 1000).toLong())
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    private fun updateTimeStamp() {
        val player = getPlayer() ?: return
        if (player.playbackState == Player.STATE_IDLE) return

        val playerCurrentTime = player.currentPosition / 1000
        currentTimeStamp = model.timeStamps.value?.find { timestamp ->
            timestamp.interval.startTime < playerCurrentTime &&
                    playerCurrentTime < (timestamp.interval.endTime - 1)
        }

        val new = currentTimeStamp
        timeStampText.text = if (new != null) {
            fun disappearSkip() {
                functionStarted = true
                skipTimeButton.visibility = View.VISIBLE
                exoSkip.visibility = View.GONE
                skipTimeText.text = new.skipType.getType()
                skipTimeButton.setOnClickListener {
                    player.seekTo((new.interval.endTime * 1000).toLong())
                }

                cancelTimer()
                countDownTimer = object : CountDownTimer(5000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        if (currentTimeStamp == null) {
                            skipTimeButton.visibility = View.GONE
                            exoSkip.isVisible = PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                            disappeared = false
                            functionStarted = false
                            cancelTimer()
                        }
                    }

                    override fun onFinish() {
                        skipTimeButton.visibility = View.GONE
                        exoSkip.isVisible = PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                        disappeared = true
                        functionStarted = false
                        cancelTimer()
                    }
                }.start()
            }

            if (PrefManager.getVal(PrefName.ShowTimeStampButton)) {
                if (!functionStarted && !disappeared && PrefManager.getVal(PrefName.AutoHideTimeStamps)) {
                    disappearSkip()
                } else if (!PrefManager.getVal<Boolean>(PrefName.AutoHideTimeStamps)) {
                    skipTimeButton.visibility = View.VISIBLE
                    exoSkip.visibility = View.GONE
                    skipTimeText.text = new.skipType.getType()
                    skipTimeButton.setOnClickListener {
                        player.seekTo((new.interval.endTime * 1000).toLong())
                    }
                }
            }

            if (PrefManager.getVal(PrefName.AutoSkipOPED) &&
                (new.skipType == "op" || new.skipType == "ed") &&
                !skippedTimeStamps.contains(new)
            ) {
                player.seekTo((new.interval.endTime * 1000).toLong())
                skippedTimeStamps.add(new)
            }

            if (PrefManager.getVal(PrefName.AutoSkipRecap) &&
                new.skipType == "recap" &&
                !skippedTimeStamps.contains(new)
            ) {
                player.seekTo((new.interval.endTime * 1000).toLong())
                skippedTimeStamps.add(new)
            }

            new.skipType.getType()
        } else {
            disappeared = false
            functionStarted = false
            skipTimeButton.visibility = View.GONE
            exoSkip.isVisible = PrefManager.getVal<Int>(PrefName.SkipTime) > 0
            ""
        }
    }
}
