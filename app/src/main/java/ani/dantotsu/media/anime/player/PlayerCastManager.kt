package ani.dantotsu.media.anime.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.CustomCastButton
import ani.dantotsu.media.anime.CustomCastThemeFactory
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.util.concurrent.Executors

@UnstableApi
class PlayerCastManager(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val onCastStateChanged: (isPlaying: Boolean) -> Unit
) : SessionAvailabilityListener {

    var castPlayer: CastPlayer? = null
        private set
    var castContext: CastContext? = null
        private set
    var isCastApiAvailable = true
        private set

    var currentMediaItem: MediaItem? = null
        private set
    private var exoPlayer: Player? = null

    var castScreenView: CastScreenView? = null

    var activeDeviceName: String? = null
        private set
    var currentPositionMs: Long = 0L
        private set
    var currentDurationMs: Long = 0L
        private set
    var isBuffering: Boolean = false
        private set

    var onSessionStartedListener: ((deviceName: String?) -> Unit)? = null
    var onSessionEndedListener: ((resumePositionMs: Long) -> Unit)? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isTrackingProgress = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isCasting() || castPlayer?.playbackState == Player.STATE_BUFFERING) {
                castPlayer?.let { cp ->
                    currentPositionMs = cp.currentPosition.coerceAtLeast(0L)
                    currentDurationMs = cp.duration.coerceAtLeast(0L)
                    isBuffering = cp.playbackState == Player.STATE_BUFFERING
                    castScreenView?.updateProgress(currentPositionMs, currentDurationMs)
                    castScreenView?.updatePlaybackState(cp.isPlaying, isBuffering)
                }
                progressHandler.postDelayed(this, 500)
            } else {
                isTrackingProgress = false
            }
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            activeDeviceName = session.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)
            onSessionStartedListener?.invoke(activeDeviceName)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val resumePos = currentPositionMs
            activeDeviceName = null
            stopProgressTracking()
            onSessionEndedListener?.invoke(resumePos)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            activeDeviceName = session.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)
            onSessionStartedListener?.invoke(activeDeviceName)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    init {
        initCastApi()
    }

    private fun initCastApi() {
        try {
            CastContext.getSharedInstance(activity, Executors.newSingleThreadExecutor())
                .addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        castContext = task.result
                        try {
                            castContext?.sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                            castPlayer = castContext?.let { CastPlayer(it) }
                            castPlayer?.setSessionAvailabilityListener(this)
                            setupCastPlayerListener()
                        } catch (e: Exception) {
                            isCastApiAvailable = false
                        }
                    } else {
                        isCastApiAvailable = false
                    }
                }
        } catch (e: Exception) {
            isCastApiAvailable = false
        }
    }

    private fun setupCastPlayerListener() {
        castPlayer?.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                super.onPlayWhenReadyChanged(playWhenReady, reason)
                onCastStateChanged(playWhenReady)
                castScreenView?.updatePlaybackState(playWhenReady, isBuffering)
                if (playWhenReady) startProgressTracking()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                onCastStateChanged(isPlaying)
                castScreenView?.updatePlaybackState(isPlaying, isBuffering)
                if (isPlaying) startProgressTracking()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                isBuffering = playbackState == Player.STATE_BUFFERING
                castScreenView?.updatePlaybackState(castPlayer?.isPlaying == true, isBuffering)
                if (playbackState == Player.STATE_READY) {
                    startProgressTracking()
                }
            }
        })
    }

    private fun startProgressTracking() {
        if (!isTrackingProgress) {
            isTrackingProgress = true
            progressHandler.removeCallbacks(progressRunnable)
            progressHandler.post(progressRunnable)
        }
    }

    private fun stopProgressTracking() {
        isTrackingProgress = false
        progressHandler.removeCallbacks(progressRunnable)
    }

    fun setupCastButton(
        castButton: CustomCastButton,
        media: Media?,
        video: Video?,
        subtitle: Subtitle?,
        hasExtSubtitles: Boolean,
        episodeTitle: String?
    ) {
        if (!PrefManager.getVal<Boolean>(PrefName.Cast)) {
            castButton.visibility = View.GONE
            return
        }

        castButton.visibility = View.VISIBLE
        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            try {
                CastButtonFactory.setUpMediaRouteButton(activity, castButton)
                castButton.dialogFactory = CustomCastThemeFactory()
            } catch (e: Exception) {
                isCastApiAvailable = false
            }
        } else {
            castButton.setCastCallback {
                castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
            }
        }
    }

    fun updateCurrentMedia(mediaItem: MediaItem?, exoPlayer: Player?) {
        this.currentMediaItem = mediaItem
        this.exoPlayer = exoPlayer
    }

    fun isCasting(): Boolean = castPlayer?.isCastSessionAvailable == true && castPlayer?.currentMediaItem != null

    fun pause() {
        castPlayer?.pause()
    }

    fun play() {
        castPlayer?.play()
    }

    fun togglePlayPause() {
        castPlayer?.let { cp ->
            if (cp.isPlaying) {
                cp.pause()
            } else {
                cp.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        castPlayer?.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        castPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun setRemoteVolume(volumeFraction: Float) {
        try {
            castContext?.sessionManager?.currentCastSession?.volume = volumeFraction.toDouble().coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            Logger.log("Failed to set remote cast volume: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            castContext?.sessionManager?.endCurrentSession(true)
        } catch (e: Exception) {
            Logger.log("Failed to end cast session: ${e.message}")
        }
    }

    fun castExternal(
        media: Media?,
        video: Video?,
        subtitle: Subtitle?,
        hasExtSubtitles: Boolean,
        episodeTitle: String?
    ) {
        val videoURL = video?.file?.url ?: return
        val subtitleUrl = if (!hasExtSubtitles || subtitle == null) video.file.url else subtitle.file.url
        val shareVideo = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoURL.toUri(), "video/*")
            setPackage("com.instantbits.cast.webvideo")
            if (subtitle != null) putExtra("subtitle", subtitleUrl)
            putExtra(
                "title",
                (media?.userPreferredName ?: "") + " : " + (episodeTitle ?: "")
            )
            putExtra("poster", media?.cover)
            val headers = Bundle().apply {
                defaultHeaders.forEach { putString(it.key, it.value) }
                video.file.headers?.forEach { putString(it.key, it.value) }
            }
            putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
            putExtra("secure_uri", true)
        }

        try {
            activity.startActivity(shareVideo)
        } catch (ex: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "market://details?id=com.instantbits.cast.webvideo".toUri())
                )
            } catch (e: Exception) {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.instantbits.cast.webvideo".toUri())
                )
            }
        }
    }

    override fun onCastSessionAvailable() {
        val item = currentMediaItem
        if (isCastApiAvailable && !activity.isDestroyed && item != null) {
            val handoverPosition = exoPlayer?.currentPosition ?: 0L
            exoPlayer?.pause()

            activeDeviceName = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)

            castPlayer?.setMediaItem(item, handoverPosition)
            castPlayer?.prepare()
            castPlayer?.play()

            startProgressTracking()
            onSessionStartedListener?.invoke(activeDeviceName)
        }
    }

    override fun onCastSessionUnavailable() {
        val resumePosition = currentPositionMs
        stopProgressTracking()
        onSessionEndedListener?.invoke(resumePosition)
    }

    fun release() {
        stopProgressTracking()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
        } catch (_: Exception) {}
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        castScreenView = null
    }
}
