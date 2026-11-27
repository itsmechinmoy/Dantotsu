package ani.dantotsu.media.anime.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import ani.dantotsu.R
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.CustomCastButton
import ani.dantotsu.media.anime.CustomCastThemeFactory
import ani.dantotsu.media.anime.cast.CastProxyServer
import ani.dantotsu.media.anime.cast.CastProxyServerService
import ani.dantotsu.media.anime.mpv.DantotsuPlayerView
import ani.dantotsu.media.anime.mpv.ExternalSubtitle
import ani.dantotsu.media.anime.mpv.MpvVideoView
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import java.util.concurrent.Executors

class PlayerCastManager(
    private val activity: AppCompatActivity,
    private val playerView: DantotsuPlayerView,
    private val onCastStateChanged: (isPlaying: Boolean) -> Unit
) {

    var castContext: CastContext? = null
        private set
    var isCastApiAvailable = true
        private set

    var currentVideo: Video? = null
        private set
    private var mpvView: MpvVideoView? = null
    private var currentTitle: String? = null
    private var currentSubtitleTitle: String? = null
    private var currentPosterUrl: String? = null
    private var currentExternalSubtitles: List<ExternalSubtitle> = emptyList()

    var castScreenView: CastScreenView? = null
    private var customCastButton: CustomCastButton? = null

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

    private val remoteMediaClient: RemoteMediaClient?
        get() = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private val progressRunnable = object : Runnable {
        override fun run() {
            val client = remoteMediaClient
            if (isCasting() && client != null) {
                currentPositionMs = client.approximateStreamPosition.coerceAtLeast(0L)
                currentDurationMs = client.streamDuration.coerceAtLeast(0L)
                isBuffering = client.playerState == MediaStatus.PLAYER_STATE_BUFFERING
                castScreenView?.updateProgress(currentPositionMs, currentDurationMs)
                castScreenView?.updatePlaybackState(client.isPlaying, isBuffering)
                progressHandler.postDelayed(this, 250)
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

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = remoteMediaClient ?: return
            isBuffering = client.playerState == MediaStatus.PLAYER_STATE_BUFFERING
            val playing = client.isPlaying
            onCastStateChanged(playing)
            castScreenView?.updatePlaybackState(playing, isBuffering)
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachCastSession(session)
            loadMediaOntoCastSession(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val resumePos = currentPositionMs
            activeDeviceName = null
            session.remoteMediaClient?.unregisterCallback(remoteCallback)
            session.remoteMediaClient?.removeProgressListener(remoteProgressListener)
            stopProgressTracking()
            CastProxyServerService.stop(activity)
            onSessionEndedListener?.invoke(resumePos)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachCastSession(session)
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
            session.remoteMediaClient?.unregisterCallback(remoteCallback)
            session.remoteMediaClient?.removeProgressListener(remoteProgressListener)
            stopProgressTracking()
        }
    }

    init {
        initCastApi()
    }

    private fun attachCastSession(session: CastSession) {
        activeDeviceName = session.castDevice?.friendlyName
        castScreenView?.updateDeviceName(activeDeviceName)
        session.remoteMediaClient?.registerCallback(remoteCallback)
        session.remoteMediaClient?.addProgressListener(remoteProgressListener, 250)
        startProgressTracking()
        onSessionStartedListener?.invoke(activeDeviceName)
    }

    private fun initCastApi() {
        try {
            try {
                MediaRouter.getInstance(activity).routerParams = MediaRouterParams.Builder()
                    .setDialogType(MediaRouterParams.DIALOG_TYPE_DYNAMIC_GROUP)
                    .setOutputSwitcherEnabled(true)
                    .build()
            } catch (_: Throwable) {}

            CastContext.getSharedInstance(activity, Executors.newSingleThreadExecutor())
                .addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        castContext = task.result
                        try {
                            castContext?.sessionManager?.addSessionManagerListener(
                                sessionManagerListener,
                                CastSession::class.java
                            )
                            if (PrefManager.getVal(PrefName.UseInternalCast)) {
                                activity.runOnUiThread {
                                    customCastButton?.let { btn ->
                                        try {
                                            CastButtonFactory.setUpMediaRouteButton(activity, btn)
                                            btn.dialogFactory = CustomCastThemeFactory()
                                            btn.visibility = View.VISIBLE
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            isCastApiAvailable = false
                        }
                    } else {
                        isCastApiAvailable = false
                    }
                }
        } catch (_: Exception) {
            isCastApiAvailable = false
        }
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
        this.customCastButton = castButton
        this.currentTitle = media?.userPreferredName
        this.currentSubtitleTitle = episodeTitle
        this.currentPosterUrl = media?.cover
        if (!PrefManager.getVal<Boolean>(PrefName.Cast)) {
            castButton.visibility = View.GONE
            return
        }

        castButton.visibility = View.VISIBLE

        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            try {
                CastButtonFactory.setUpMediaRouteButton(activity, castButton)
                castButton.dialogFactory = CustomCastThemeFactory()
            } catch (_: Exception) {
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
    }

    fun updateCurrentMedia(
        mpvView: MpvVideoView?,
        video: Video? = null,
        title: String? = null,
        episodeTitle: String? = null,
        posterUrl: String? = null,
        externalSubtitles: List<ExternalSubtitle> = emptyList()
    ) {
        this.mpvView = mpvView
        this.currentVideo = video
        if (title != null) this.currentTitle = title
        if (episodeTitle != null) this.currentSubtitleTitle = episodeTitle
        if (posterUrl != null) this.currentPosterUrl = posterUrl
        this.currentExternalSubtitles = externalSubtitles

        castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let { session ->
            loadMediaOntoCastSession(session)
        }
    }

    private fun loadMediaOntoCastSession(session: CastSession) {
        val video = currentVideo ?: return
        if (!isCastApiAvailable || activity.isDestroyed) return

        val handoverPosition = mpvView?.currentPosition?.value ?: 0L
        mpvView?.pause()

        val mediaInfo = buildProxiedCastMediaInfo(video)
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(handoverPosition.coerceAtLeast(0L))
            .build()

        session.remoteMediaClient?.load(request)
    }

    private fun buildProxiedCastMediaInfo(video: Video): MediaInfo {
        val uriStr = video.file.url
        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video.file.headers.let { headers.putAll(it) }

        val proxyServer = CastProxyServerService.currentProxyServer
            ?: CastProxyServer(contentResolver = activity.contentResolver, ipAddress = CastProxyServer.getLocalIpAddress())

        val proxiedUri = when {
            uriStr.startsWith("content://") -> proxyServer.getLocalUrl(uriStr)
            uriStr.startsWith("http://") || uriStr.startsWith("https://") -> proxyServer.getProxyUrl(uriStr, headers)
            else -> uriStr
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
            currentTitle?.let { putString(MediaMetadata.KEY_TITLE, it) }
            currentSubtitleTitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            currentPosterUrl?.takeIf { it.isNotBlank() }?.let {
                addImage(WebImage(it.toUri()))
            }
        }

        val tracks = currentExternalSubtitles.mapIndexed { idx, sub ->
            val proxiedSubUrl = when {
                sub.url.startsWith("http://") || sub.url.startsWith("https://") ->
                    proxyServer.getProxyUrl(sub.url, headers + sub.headers)
                sub.url.startsWith("content://") || sub.url.startsWith("file://") ->
                    proxyServer.getLocalUrl(sub.url)
                else -> sub.url
            }
            MediaTrack.Builder((idx + 1).toLong(), MediaTrack.TYPE_TEXT)
                .setName(sub.label ?: "Subtitle ${idx + 1}")
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(proxiedSubUrl)
                .setLanguage(sub.language ?: "und")
                .build()
        }

        val contentType = when {
            uriStr.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            uriStr.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            else -> "video/mp4"
        }

        return MediaInfo.Builder(proxiedUri)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .apply {
                if (tracks.isNotEmpty()) setMediaTracks(tracks)
            }
            .build()
    }

    fun isCasting(): Boolean =
        castContext?.sessionManager?.currentCastSession?.isConnected == true &&
            remoteMediaClient?.hasMediaSession() == true

    fun pause() {
        remoteMediaClient?.pause()
    }

    fun play() {
        remoteMediaClient?.play()
    }

    fun togglePlayPause() {
        remoteMediaClient?.let { client ->
            if (client.isPlaying) client.pause() else client.play()
        }
    }

    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        remoteMediaClient?.seek(
            com.google.android.gms.cast.MediaSeekOptions.Builder()
                .setPosition(positionMs)
                .build()
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        remoteMediaClient?.setPlaybackRate(speed.toDouble().coerceIn(0.5, 2.0))
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
        val videoURL = video?.file?.url ?: run {
            toast(activity.getString(R.string.video_not_ready))
            return
        }
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
                video.file.headers.forEach { putString(it.key, it.value) }
            }
            putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
            putExtra("secure_uri", true)
        }

        try {
            activity.startActivity(shareVideo)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "market://details?id=com.instantbits.cast.webvideo".toUri())
                )
            } catch (_: Exception) {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.instantbits.cast.webvideo".toUri())
                )
            }
        }
    }

    fun release() {
        stopProgressTracking()
        try {
            castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.let {
                it.unregisterCallback(remoteCallback)
                it.removeProgressListener(remoteProgressListener)
            }
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionManagerListener,
                CastSession::class.java
            )
        } catch (_: Exception) {}
        castScreenView = null
    }
}
