package ani.dantotsu.media.anime.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.R
import ani.dantotsu.databinding.LayoutCastScreenBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.anime.Episode
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.slider.Slider
import java.util.Locale

@UnstableApi
class CastScreenView(
    private val activity: AppCompatActivity,
    private val binding: LayoutCastScreenBinding,
    private val castManager: PlayerCastManager,
    private val onPlayPauseClick: () -> Unit,
    private val onPreviousClick: () -> Unit,
    private val onNextClick: () -> Unit,
    private val onSeekTo: (positionMs: Long) -> Unit,
    private val onPlaylistClick: () -> Unit,
    private val onSpeedClick: () -> Unit,
    private val onSubtitlesClick: () -> Unit,
    private val onAudioClick: () -> Unit,
    private val onQualityClick: () -> Unit,
    private val onCustomSkipClick: () -> Unit,
    private val onSkipIntroClick: () -> Unit
) {

    private var isUserSeeking = false
    private var currentDurationMs = 0L

    init {
        setupListeners()
    }

    private fun setupListeners() {
        binding.exoCastBack.setOnClickListener {
            activity.onBackPressedDispatcher.onBackPressed()
        }

        binding.exoCastDisconnect.setOnClickListener {
            castManager.disconnect()
        }

        binding.exoCastPlayPause.setOnClickListener {
            onPlayPauseClick()
        }

        binding.exoCastPrev.setOnClickListener {
            onPreviousClick()
        }

        binding.exoCastNext.setOnClickListener {
            onNextClick()
        }

        binding.exoCastRewind.setOnClickListener {
            val target = (castManager.currentPositionMs - 10_000L).coerceAtLeast(0L)
            onSeekTo(target)
        }

        binding.exoCastForward.setOnClickListener {
            val target = if (currentDurationMs > 0) {
                (castManager.currentPositionMs + 10_000L).coerceAtMost(currentDurationMs)
            } else {
                castManager.currentPositionMs + 10_000L
            }
            onSeekTo(target)
        }

        binding.exoCastSeeker.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val targetMs = (slider.value / 100f * currentDurationMs).toLong()
                onSeekTo(targetMs)
                isUserSeeking = false
            }
        })

        binding.exoCastSeeker.addOnChangeListener { _, value, fromUser ->
            if (fromUser && currentDurationMs > 0) {
                val previewMs = (value / 100f * currentDurationMs).toLong()
                binding.exoCastTimeCurrent.text = formatTime(previewMs)
            }
        }

        binding.exoCastVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                castManager.setRemoteVolume(value / 100f)
            }
        }

        binding.exoCastPlaylist.setOnClickListener { onPlaylistClick() }
        binding.exoCastSpeed.setOnClickListener { onSpeedClick() }
        binding.exoCastSubs.setOnClickListener { onSubtitlesClick() }
        binding.exoCastAudio.setOnClickListener { onAudioClick() }
        binding.exoCastQuality.setOnClickListener { onQualityClick() }
        binding.exoCastCustomSkip.setOnClickListener { onCustomSkipClick() }
        binding.exoCastSkipIntro.setOnClickListener { onSkipIntroClick() }
    }

    fun updateMediaInfo(media: Media, episode: Episode?) {
        binding.exoCastTitle.text = media.userPreferredName
        val epTitle = episode?.let { ep ->
            val cleaned = MediaNameAdapter.removeEpisodeNumberCompletely(ep.title ?: "")
            "Episode ${ep.number}${if (cleaned.isNotBlank() && cleaned != "null") ": $cleaned" else ""}"
        } ?: "Episode 1"
        binding.exoCastSubtitle.text = epTitle

        val coverUrl = media.cover ?: media.banner
        if (!activity.isDestroyed && !coverUrl.isNullOrBlank()) {
            Glide.with(activity)
                .load(coverUrl)
                .transform(CenterCrop(), RoundedCorners(28))
                .placeholder(R.drawable.ic_dantotsu_round)
                .error(R.drawable.ic_dantotsu_round)
                .into(binding.exoCastCover)
        }
    }

    fun updateDeviceName(deviceName: String?) {
        binding.exoCastDeviceName.text = deviceName ?: "Cast Device"
    }

    fun updatePlaybackState(isPlaying: Boolean, isBuffering: Boolean) {
        if (isBuffering) {
            binding.exoCastBuffering.visibility = View.VISIBLE
            binding.exoCastPlayPause.visibility = View.INVISIBLE
        } else {
            binding.exoCastBuffering.visibility = View.GONE
            binding.exoCastPlayPause.visibility = View.VISIBLE
            binding.exoCastPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
            )
        }
    }

    fun updateProgress(currentPositionMs: Long, durationMs: Long) {
        this.currentDurationMs = durationMs
        if (!isUserSeeking && durationMs > 0) {
            val progressPercent = (currentPositionMs.toFloat() / durationMs.toFloat() * 100f).coerceIn(0f, 100f)
            binding.exoCastSeeker.value = progressPercent
            binding.exoCastTimeCurrent.text = formatTime(currentPositionMs)
            binding.exoCastTimeDuration.text = formatTime(durationMs)
        }
    }

    fun updateSkipIntroButton(visible: Boolean, text: String? = null) {
        binding.exoCastSkipIntro.visibility = if (visible) View.VISIBLE else View.GONE
        if (text != null) {
            binding.exoCastSkipIntro.text = text
        }
    }

    fun updateVolume(volumeFraction: Float) {
        val volumePercent = (volumeFraction * 100f).coerceIn(0f, 100f)
        binding.exoCastVolume.value = volumePercent
    }

    fun show(animate: Boolean = true) {
        if (binding.castScreenRoot.visibility == View.VISIBLE) return
        if (animate) {
            binding.castScreenRoot.alpha = 0f
            binding.castScreenRoot.visibility = View.VISIBLE
            binding.castScreenRoot.animate()
                .alpha(1f)
                .setDuration(250)
                .setListener(null)
                .start()
        } else {
            binding.castScreenRoot.alpha = 1f
            binding.castScreenRoot.visibility = View.VISIBLE
        }
    }

    fun hide(animate: Boolean = true) {
        if (binding.castScreenRoot.visibility != View.VISIBLE) return
        if (animate) {
            binding.castScreenRoot.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        binding.castScreenRoot.visibility = View.GONE
                    }
                })
                .start()
        } else {
            binding.castScreenRoot.visibility = View.GONE
        }
    }

    val isVisible: Boolean
        get() = binding.castScreenRoot.visibility == View.VISIBLE

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            val remMinutes = minutes % 60
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, remMinutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }
}
