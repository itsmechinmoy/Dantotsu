package ani.dantotsu.media.anime

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.Player.PlaybackSuppressionReason

@Suppress("DEPRECATION")
class AudioFocusListener(
    val context: Context,
    val player: Player
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var isPausedByFocusLoss = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            val activity = context as? Activity
            val lifecycleOwner = context as? LifecycleOwner
            val canPlay = when {
                activity == null -> true
                activity.isFinishing || activity.isDestroyed -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode -> true
                lifecycleOwner != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> true
                lifecycleOwner == null -> true
                else -> false
            }
            if (canPlay && isPausedByFocusLoss) {
                player.apply {
                    if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                        play()
                    }
                }
            }
            isPausedByFocusLoss = false
            abandonRequest()
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    fun abandonRequest() {
        isPausedByFocusLoss = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
                build()
            }
        }
        onPlaybackSuppressionReasonChanged(player.playbackSuppressionReason)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            isPausedByFocusLoss = false
        }
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: @PlaybackSuppressionReason Int) {
        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
            if (player.playWhenReady) {
                isPausedByFocusLoss = true
                player.playWhenReady = false
                requestFocus()
            }
        }
    }
}
