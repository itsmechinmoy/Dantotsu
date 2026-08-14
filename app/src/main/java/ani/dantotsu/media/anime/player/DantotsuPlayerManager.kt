package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.AudioFocusListener
import ani.dantotsu.media.anime.VideoCache
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssSupport
import okhttp3.OkHttpClient
import java.util.Calendar

@UnstableApi
class DantotsuPlayerManager(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val subtitleManager: PlayerSubtitleManager,
    private val client: OkHttpClient,
    private val onPlayerErrorCallback: (error: PlaybackException) -> Unit
) {

    var exoPlayer: ExoPlayer? = null
        private set
    var trackSelector: DefaultTrackSelector? = null
        private set
    var mediaSession: MediaSession? = null
        private set
    var audioFocusListener: AudioFocusListener? = null
        private set

    var mediaSource: MediaSource? = null
        private set
    var currentMediaItem: MediaItem? = null
        private set
    var isInitialized = false
        private set

    private val DEFAULT_MIN_BUFFER_MS = 30_000
    private val DEFAULT_MAX_BUFFER_MS = 120_000
    private val BUFFER_FOR_PLAYBACK_MS = 2_500
    private val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
    private val BACK_BUFFER_DURATION_MS = 60_000

    fun initTrackSelector() {
        trackSelector = DefaultTrackSelector(activity)
    }

    fun buildMediaSource(
        video: Video,
        subConfigs: List<MediaItem.SubtitleConfiguration>,
        mimeType: String?,
        downloadedMediaItem: MediaItem?,
        mediaMetadata: MediaMetadata? = null
    ): Pair<MediaSource, MediaItem> {
        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video.file.headers?.let {
            headers.putAll(it)
        }

        val httpClient = client.newBuilder().apply {
            connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        }.build()
        val httpDataSourceFactory = OkHttpDataSource.Factory(httpClient).apply {
            setDefaultRequestProperties(headers)
            if (headers.containsKey("User-Agent")) {
                setUserAgent(headers["User-Agent"])
            }
        }

        val upstream = DefaultDataSource.Factory(activity, httpDataSourceFactory)
        val cacheFactory: DataSource.Factory = CacheDataSource.Factory()
            .setCache(VideoCache.getInstance(activity))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val extractorsFactory = subtitleManager.createExtractorsFactory()
        val assParserFactory = subtitleManager.createSubtitleParserFactory()
        val assMediaSourceFactory = DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
            .setSubtitleParserFactory(assParserFactory)

        val mediaItem = downloadedMediaItem?.buildUpon()?.apply {
            if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
        }?.build() ?: MediaItem.Builder()
            .setUri(video.file.url.toUri())
            .apply {
                if (mimeType != null) setMimeType(mimeType)
                if (subConfigs.isNotEmpty()) setSubtitleConfigurations(subConfigs)
                if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
            }
            .build()
        this.currentMediaItem = mediaItem

        val isContentUri = video.file.url.startsWith("content://")
        val primarySource = if (isContentUri) {
            val localDataSourceFactory = DefaultDataSource.Factory(activity)
            DefaultMediaSourceFactory(localDataSourceFactory, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .createMediaSource(mediaItem)
        } else {
            assMediaSourceFactory.createMediaSource(mediaItem)
        }

        this.mediaSource = primarySource
        return Pair(primarySource, mediaItem)
    }

    fun buildExoplayer(
        playbackPosition: Long,
        playbackParameters: PlaybackParameters,
        listener: Player.Listener,
        forceDefaultRenderers: Boolean = false
    ): ExoPlayer {
        releaseExoPlayer()

        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(BACK_BUFFER_DURATION_MS, false)
            .setBufferDurationsMs(
                DEFAULT_MIN_BUFFER_MS,
                DEFAULT_MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val useExtensionDecoder = PrefManager.getVal<Boolean>(PrefName.UseAdditionalCodec) && !forceDefaultRenderers
        val decoder = if (useExtensionDecoder) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        val nextRenderersFactory = NextRenderersFactory(activity)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(decoder)

        subtitleManager.initAssHandler()
        val handler = subtitleManager.assHandler!!
        Logger.log("Libass: Calling nextRenderersFactory.withAssSupport()")
        val renderersFactory = if (forceDefaultRenderers) {
            DefaultRenderersFactory(activity)
                .setEnableDecoderFallback(true)
                .withAssSupport(handler)
        } else {
            nextRenderersFactory.withAssSupport(handler)
        }

        val assMediaSourceFactory = DefaultMediaSourceFactory(activity)
            .setSubtitleParserFactory(subtitleManager.createSubtitleParserFactory())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val newTrackSelector = DefaultTrackSelector(activity)
        this.trackSelector = newTrackSelector

        val player = ExoPlayer.Builder(activity, renderersFactory)
            .setMediaSourceFactory(assMediaSourceFactory)
            .setTrackSelector(newTrackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        this.exoPlayer = player
        playerView.player = player

        audioFocusListener = AudioFocusListener(activity, player)
        player.addListener(audioFocusListener!!)

        Logger.log("Libass: Calling handler.init(exoPlayer)")
        handler.init(player)

        player.playWhenReady = true
        player.playbackParameters = playbackParameters
        mediaSource?.let { player.setMediaSource(it) }
        player.prepare()
        if (playbackPosition > 0L) {
            player.seekTo(playbackPosition)
        }

        try {
            val rightNow = Calendar.getInstance()
            mediaSession = MediaSession.Builder(activity, player)
                .setId(rightNow.timeInMillis.toString())
                .build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        player.addListener(listener)
        player.addAnalyticsListener(EventLogger())
        isInitialized = true
        return player
    }

    fun releaseExoPlayer() {
        audioFocusListener?.abandonRequest()
        audioFocusListener = null
        isInitialized = false
        playerView.player = null
        exoPlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
            p.release()
        }
        exoPlayer = null
        trackSelector = null
        mediaSession?.release()
        mediaSession = null
    }

    fun release() {
        releaseExoPlayer()
        VideoCache.release()
        subtitleManager.release()
    }
}
