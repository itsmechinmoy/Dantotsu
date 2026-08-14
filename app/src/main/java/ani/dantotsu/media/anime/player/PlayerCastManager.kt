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

import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import ani.dantotsu.media.anime.cast.CastProxyServer
import ani.dantotsu.media.anime.cast.CastProxyServerService
import com.google.android.gms.cast.framework.media.RemoteMediaClient

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
    var currentVideo: Video? = null
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
                progressHandler.postDelayed(this, 100)
            } else {
                isTrackingProgress = false
            }
        }
    }

    private val remoteProgressListener = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        currentPositionMs = progressMs.coerceAtLeast(0L)
        currentDurationMs = durationMs.coerceAtLeast(0L)
        castScreenView?.updateProgress(currentPositionMs, currentDurationMs)
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            activeDeviceName = session.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)
            session.remoteMediaClient?.addProgressListener(remoteProgressListener, 100)
            onSessionStartedListener?.invoke(activeDeviceName)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val resumePos = currentPositionMs
            activeDeviceName = null
            session.remoteMediaClient?.removeProgressListener(remoteProgressListener)
            stopProgressTracking()
            CastProxyServerService.stop(activity)
            onSessionEndedListener?.invoke(resumePos)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            activeDeviceName = session.castDevice?.friendlyName
            castScreenView?.updateDeviceName(activeDeviceName)
            session.remoteMediaClient?.addProgressListener(remoteProgressListener, 100)
            onSessionStartedListener?.invoke(activeDeviceName)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            CastProxyServerService.stop(activity)
        }
        override fun onSessionStarting(session: CastSession) {
            CastProxyServerService.start(activity)
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            session.remoteMediaClient?.removeProgressListener(remoteProgressListener)
        }
    }

    private var pendingCastButtonSetup: (() -> Unit)? = null

    init {
        initCastApi()
    }

    private fun initCastApi() {
        try {
            MediaRouter.getInstance(activity).routerParams = MediaRouterParams.Builder()
                .setDialogType(MediaRouterParams.DIALOG_TYPE_DYNAMIC_GROUP)
                .setOutputSwitcherEnabled(true)
                .build()

            CastContext.getSharedInstance(activity, Executors.newSingleThreadExecutor())
                .addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        castContext = task.result
                        try {
                            castContext?.sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                            castPlayer = castContext?.let { CastPlayer(it) }
                            castPlayer?.setSessionAvailabilityListener(this)
                            setupCastPlayerListener()
                            activity.runOnUiThread {
                                pendingCastButtonSetup?.invoke()
                                pendingCastButtonSetup = null
                            }
                        } catch (e: Exception) {
                            isCastApiAvailable = false
                            activity.runOnUiThread {
                                pendingCastButtonSetup?.invoke()
                                pendingCastButtonSetup = null
                            }
                        }
                    } else {
                        isCastApiAvailable = false
                        activity.runOnUiThread {
                            pendingCastButtonSetup?.invoke()
                            pendingCastButtonSetup = null
                        }
                    }
                }
        } catch (e: Exception) {
            isCastApiAvailable = false
            activity.runOnUiThread {
                pendingCastButtonSetup?.invoke()
                pendingCastButtonSetup = null
            }
        }
    }

    private fun setupCastPlayerListener() {
        castPlayer?.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                onCastStateChanged(playWhenReady)
                castScreenView?.updatePlaybackState(playWhenReady, isBuffering)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onCastStateChanged(isPlaying)
                castScreenView?.updatePlaybackState(isPlaying, isBuffering)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    castScreenView?.updatePlaybackState(true, isBuffering)
                }
                castScreenView?.updatePlaybackState(castPlayer?.isPlaying == true, isBuffering)
            }
        })
    }

    fun startProgressTracking() {
        if (!isTrackingProgress) {
            isTrackingProgress = true
            progressHandler.removeCallbacks(progressRunnable)
            progressHandler.post(progressRunnable)
        }
    }

    fun stopProgressTracking() {
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
        castButton.setAlwaysVisible(true)

        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            if (castContext == null && isCastApiAvailable) {
                pendingCastButtonSetup = {
                    setupCastButton(castButton, media, video, subtitle, hasExtSubtitles, episodeTitle)
                }
            } else if (castContext != null) {
                try {
                    CastButtonFactory.setUpMediaRouteButton(activity, castButton)
                    castButton.dialogFactory = CustomCastThemeFactory()
                    castButton.setAlwaysVisible(true)
                } catch (e: Exception) {
                    isCastApiAvailable = false
                    castButton.setCastCallback {
                        castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
                    }
                }
            } else {
                castButton.setCastCallback {
                    castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
                }
            }
        } else {
            castButton.setCastCallback {
                castExternal(media, video, subtitle, hasExtSubtitles, episodeTitle)
            }
        }
    }

    fun updateCurrentMedia(mediaItem: MediaItem?, exoPlayer: Player?, video: Video? = null) {
        this.currentMediaItem = mediaItem
        this.exoPlayer = exoPlayer
        this.currentVideo = video
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

            val proxiedItem = getProxiedMediaItem(item, currentVideo)

            castPlayer?.setMediaItem(proxiedItem, handoverPosition)
            castPlayer?.prepare()
            castPlayer?.play()

            startProgressTracking()
            onSessionStartedListener?.invoke(activeDeviceName)
        }
    }

    private fun getProxiedMediaItem(item: MediaItem, video: Video?): MediaItem {
        val originalUri = item.localConfiguration?.uri ?: return item
        val uriStr = originalUri.toString()
        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video?.file?.headers?.let { headers.putAll(it) }

        val proxyServer = CastProxyServerService.currentProxyServer
            ?: CastProxyServer(contentResolver = activity.contentResolver, ipAddress = CastProxyServer.getLocalIpAddress())

        val proxiedUri = if (uriStr.startsWith("content://")) {
            proxyServer.getLocalUrl(uriStr).toUri()
        } else if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
            proxyServer.getProxyUrl(uriStr, headers).toUri()
        } else {
            originalUri
        }

        val proxiedSubs = item.localConfiguration?.subtitleConfigurations?.map { sub ->
            val subUriStr = sub.uri.toString()
            val proxiedSubUri = if (subUriStr.startsWith("http://") || subUriStr.startsWith("https://")) {
                proxyServer.getProxyUrl(subUriStr, headers).toUri()
            } else if (subUriStr.startsWith("content://") || subUriStr.startsWith("file://")) {
                proxyServer.getLocalUrl(subUriStr).toUri()
            } else {
                sub.uri
            }
            sub.buildUpon().setUri(proxiedSubUri).build()
        } ?: emptyList()

        return item.buildUpon()
            .setUri(proxiedUri)
            .setSubtitleConfigurations(proxiedSubs)
            .build()
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
