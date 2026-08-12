package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.AnimePlayerService
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
        downloadedMediaItem: MediaItem? = null
    ): Pair<MediaSource, MediaItem> {
        val headers = mutableMapOf<String, String>()
        defaultHeaders.forEach { headers[it.key] = it.value }
        video.file.headers?.forEach { headers[it.key] = it.value }

        val httpSource: HttpDataSource.Factory = if (video.file.url.startsWith("http")) {
            val hf = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setUserAgent(defaultHeaders["User-Agent"])
            if (video.file.headers?.containsKey("User-Agent") == true) {
                hf.setUserAgent(video.file.headers?.get("User-Agent"))
            }
            hf
        } else {
            val hf = OkHttpDataSource.Factory(client)
                .setDefaultRequestProperties(headers)
                .setUserAgent(defaultHeaders["User-Agent"])
            if (video.file.headers?.containsKey("User-Agent") == true) {
                hf.setUserAgent(video.file.headers?.get("User-Agent"))
            }
            hf
        }

        val upstream = DefaultDataSource.Factory(activity, httpSource)
        val cacheFactory: DataSource.Factory = CacheDataSource.Factory()
            .setCache(VideoCache.getInstance(activity))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val extractorsFactory = subtitleManager.createExtractorsFactory()

        val mediaItem = downloadedMediaItem ?: MediaItem.Builder()
            .setUri(video.file.url.toUri())
            .apply {
                if (mimeType != null) setMimeType(mimeType)
                if (subConfigs.isNotEmpty()) setSubtitleConfigurations(subConfigs)
            }
            .build()
        this.currentMediaItem = mediaItem

        val source = when (video.format) {
            VideoType.M3U8 -> HlsMediaSource.Factory(cacheFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            VideoType.DASH -> DashMediaSource.Factory(cacheFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(cacheFactory, extractorsFactory)
                .createMediaSource(mediaItem)
        }

        this.mediaSource = source
        return Pair(source, mediaItem)
    }

    fun buildExoplayer(
        playbackPosition: Long,
        playbackParameters: PlaybackParameters,
        listener: Player.Listener
    ): ExoPlayer {
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

        val useExtensionDecoder = PrefManager.getVal<Boolean>(PrefName.UseAdditionalCodec)
        val decoder = if (useExtensionDecoder) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        val nextRenderersFactory = NextRenderersFactory(activity)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(decoder)

        subtitleManager.initAssHandler()
        val handler = subtitleManager.assHandler!!
        Logger.log("Libass: Calling nextRenderersFactory.withAssSupport()")
        val renderersFactory = nextRenderersFactory.withAssSupport(handler)

        val assMediaSourceFactory = DefaultMediaSourceFactory(activity)
            .setSubtitleParserFactory(subtitleManager.createSubtitleParserFactory())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(activity, renderersFactory)
            .setMediaSourceFactory(assMediaSourceFactory)
            .setTrackSelector(trackSelector ?: DefaultTrackSelector(activity).also { trackSelector = it })
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        this.exoPlayer = player
        playerView.player = player

        audioFocusListener = AudioFocusListener(activity, player)
        player.addListener(audioFocusListener!!)

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    )
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(true)
                    .build()
            )
            .build()

        Logger.log("Libass: Calling handler.init(exoPlayer)")
        handler.init(player)

        player.playWhenReady = true
        player.playbackParameters = playbackParameters
        mediaSource?.let { player.setMediaSource(it) }
        player.prepare()
        player.seekTo(playbackPosition)

        try {
            val rightNow = Calendar.getInstance()
            mediaSession = MediaSession.Builder(activity, player)
                .setId(rightNow.timeInMillis.toString())
                .build()
            mediaSession?.let { AnimePlayerService.start(activity, it) }
        } catch (e: Exception) {
            toast(e.toString())
        }

        player.addListener(listener)
        player.addAnalyticsListener(EventLogger())
        isInitialized = true
        return player
    }

    fun release() {
        audioFocusListener?.abandonRequest()
        audioFocusListener = null
        isInitialized = false
        exoPlayer?.release()
        exoPlayer = null
        VideoCache.release()
        AnimePlayerService.stop(activity)
        mediaSession?.release()
        mediaSession = null
        subtitleManager.release()
    }
}
