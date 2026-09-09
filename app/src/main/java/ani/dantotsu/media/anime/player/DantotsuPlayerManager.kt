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
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssSupport
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.zip.GZIPInputStream

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

    private val DEFAULT_MIN_BUFFER_MS = 15_000
    private val DEFAULT_MAX_BUFFER_MS = 45_000
    private val BUFFER_FOR_PLAYBACK_MS = 1_500
    private val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    private val BACK_BUFFER_DURATION_MS = 15_000

    fun initTrackSelector() {
        val subLanguages = arrayOf(
            "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
            "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
            "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
            "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
            "Turkish", "Ukrainian", "Urdu", "Vietnamese"
        )
        val langName = subLanguages.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
        val langCode = LanguageMapper.getLanguageCode(langName)
        val selector = DefaultTrackSelector(activity)
        val params = selector.buildUponParameters()
        if (langCode.isNotBlank() && !langCode.equals("all", ignoreCase = true)) {
            params.setPreferredTextLanguage(langCode)
        }
        trackSelector = selector.apply {
            parameters = params.build()
        }
    }

    fun buildMediaSource(
        video: Video,
        subConfigs: List<MediaItem.SubtitleConfiguration>,
        mimeType: String?,
        downloadedMediaItem: MediaItem?,
        mediaMetadata: MediaMetadata? = null,
        audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList()
    ): Pair<MediaSource, MediaItem> {
        val isLocalhost = runCatching {
            val host = video.file.url.toUri().host
            host == "127.0.0.1" || host == "localhost"
        }.getOrDefault(false)

        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video.file.headers?.let {
            headers.putAll(it)
        }
        if (isLocalhost) {
            headers.remove("Accept-Encoding")
            headers.remove("accept-encoding")
        }

        val httpClient = client.newBuilder().apply {
            connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            // Strip Accept-Encoding on the wire for localhost/127.0.0.1 NanoHTTPD server.
            // When NanoHTTPD forwards requests upstream to CDNs (like imgnex/megaplay),
            // having NO Accept-Encoding header allows NanoHTTPD's internal OkHttpClient
            // to enable transparentGzip = true, which automatically decompresses
            // gzipped M3U8 playlists. If Accept-Encoding is sent (even "identity" or "gzip"),
            // transparentGzip is disabled in NanoHTTPD and it returns mangled binary gzip
            // which causes ExoPlayer ParserException: Input does not start with #EXTM3U.
            addNetworkInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isLocal = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
                val newRequest = if (isLocal) {
                    request.newBuilder()
                        .removeHeader("Accept-Encoding")
                        .build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isLocal = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
                val newRequest = if (isLocal) {
                    request.newBuilder()
                        .removeHeader("Accept-Encoding")
                        .build()
                } else {
                    request
                }
                val response = chain.proceed(newRequest)
                if (isLocal && response.isSuccessful) {
                    val isM3u8 = request.url.encodedPath.contains("m3u8") ||
                        request.url.query?.contains(".m3u8") == true ||
                        response.header("Content-Type")?.contains("mpegurl", ignoreCase = true) == true
                    val hasGzipEncoding = response.header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true

                    val body = response.body
                    if (body != null && (isM3u8 || hasGzipEncoding)) {
                        val rawBytes = body.bytes()
                        val isGzip = (rawBytes.size >= 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) || hasGzipEncoding
                        val decompressedBytes = if (isGzip) {
                            try {
                                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
                            } catch (_: Exception) {
                                rawBytes
                            }
                        } else {
                            rawBytes
                        }

                        val finalBytes = if (isM3u8) {
                            var text = decompressedBytes.toString(Charsets.UTF_8)
                            if (text.startsWith("\uFEFF")) {
                                text = text.substring(1)
                            }
                            text.trimStart().toByteArray(Charsets.UTF_8)
                        } else {
                            decompressedBytes
                        }

                        val strippedHeaders = response.headers.newBuilder()
                            .removeAll("Content-Encoding")
                            .removeAll("Content-Length")
                            .build()

                        val newBody = okhttp3.ResponseBody.create(body.contentType(), finalBytes)
                        response.newBuilder()
                            .headers(strippedHeaders)
                            .body(newBody)
                            .build()
                    } else {
                        response
                    }
                } else {
                    response
                }
            }
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
        // Some providers wrap the license response; normalise it before the CDM sees it.
        val drmProvider = WrappedDrmCallback.providerFor(httpDataSourceFactory)
        val assMediaSourceFactory = DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
            .setSubtitleParserFactory(assParserFactory)
            .setDrmSessionManagerProvider(drmProvider)

        val mediaItem = downloadedMediaItem?.buildUpon()?.apply {
            if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
        }?.build() ?: MediaItem.Builder()
            .setUri(video.file.url.toUri())
            .apply {
                if (mimeType != null) setMimeType(mimeType)
                if (subConfigs.isNotEmpty()) setSubtitleConfigurations(subConfigs)
                if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
                video.drm?.let { drm ->
                    // The device CDM does the decryption; this only says where the
                    // license server is.
                    val uuid = when (drm.scheme.lowercase()) {
                        "playready" -> C.PLAYREADY_UUID
                        "clearkey" -> C.CLEARKEY_UUID
                        else -> C.WIDEVINE_UUID
                    }
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(uuid)
                            .setLicenseUri(drm.licenseUrl)
                            .setLicenseRequestHeaders(drm.licenseHeaders)
                            // Video and audio can carry separate keys.
                            .setMultiSession(true)
                            .build()
                    )
                    Logger.log("DRM: ${drm.scheme} license @ ${drm.licenseUrl}")
                }
            }
            .build()
        this.currentMediaItem = mediaItem

        // A downloaded episode is played from its own uri, which is not the one
        // the extension handed us, so the scheme has to come from the media item.
        val isContentUri =
            (mediaItem.localConfiguration?.uri?.scheme ?: video.file.url.substringBefore(":")) == "content"
        val isLocalhostTorrent = runCatching {
            val host = video.file.url.toUri().host
            host == "127.0.0.1" || host == "localhost"
        }.getOrDefault(false)

        val activeFactory = if (isContentUri) {
            val localDataSourceFactory = DefaultDataSource.Factory(activity)
            DefaultMediaSourceFactory(localDataSourceFactory, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .setDrmSessionManagerProvider(drmProvider)
        } else if (isLocalhostTorrent) {
            // Direct upstream for localhost torrent streams - avoids double writing to flash via VideoCache
            DefaultMediaSourceFactory(upstream, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .setDrmSessionManagerProvider(drmProvider)
        } else {
            assMediaSourceFactory
        }
        this.activeMediaSourceFactory = activeFactory
        val primarySource = activeFactory.createMediaSource(mediaItem)

        val audioSources = mutableListOf<MediaSource>()
        audioTracks.forEach { audioTrack ->
            val audioUrl = audioTrack.url
            if (audioUrl.isNotBlank() && audioUrl != video.file.url) {
                val audioMimeType = when {
                    audioUrl.contains(".m3u8", ignoreCase = true) || audioUrl.contains("/m3u8", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    audioUrl.contains(".mpd", ignoreCase = true) || audioUrl.contains("/mpd", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                    else -> null
                }
                val audioMediaItem = MediaItem.Builder()
                    .setUri(audioUrl.toUri())
                    .apply {
                        if (audioMimeType != null) setMimeType(audioMimeType)
                    }
                    .build()
                val audioSource = activeFactory.createMediaSource(audioMediaItem)
                audioSources.add(audioSource)
            }
        }

        val finalSource = if (audioSources.isNotEmpty()) {
            MergingMediaSource(primarySource, *audioSources.toTypedArray())
        } else {
            primarySource
        }

        this.mediaSource = finalSource
        return Pair(finalSource, mediaItem)
    }

    var activeMediaSourceFactory: MediaSource.Factory? = null

    fun applyUpdatedSubtitles(newSubConfigs: List<MediaItem.SubtitleConfiguration>, position: Long) {
        val player = exoPlayer ?: return
        val currentItem = currentMediaItem ?: return

        val newMediaItem = currentItem.buildUpon()
            .setSubtitleConfigurations(newSubConfigs)
            .build()
        this.currentMediaItem = newMediaItem

        val factory = activeMediaSourceFactory
        if (factory != null) {
            val newSource = factory.createMediaSource(newMediaItem)
            this.mediaSource = newSource
            player.setMediaSource(newSource, position)
        } else {
            player.setMediaItem(newMediaItem, position)
        }
        player.prepare()
        player.play()
    }

    fun buildExoplayer(
        playbackPosition: Long,
        playbackParameters: PlaybackParameters,
        listener: Player.Listener,
        forceDefaultRenderers: Boolean = false
    ): ExoPlayer {
        releaseExoPlayer()

        val isTorrentStream = mediaSource?.let {
            currentMediaItem?.localConfiguration?.uri?.host == "127.0.0.1"
        } == true
        val targetBufferBytes = if (isTorrentStream) 96 * 1024 * 1024 else 32 * 1024 * 1024
        val maxBufferMs = if (isTorrentStream) 90_000 else DEFAULT_MAX_BUFFER_MS
        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(BACK_BUFFER_DURATION_MS, true)
            .setBufferDurationsMs(
                DEFAULT_MIN_BUFFER_MS,
                maxBufferMs,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val useExtensionDecoder = !forceDefaultRenderers
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

        val mediaSourceFactory = activeMediaSourceFactory ?: DefaultMediaSourceFactory(activity)
            .setSubtitleParserFactory(subtitleManager.createSubtitleParserFactory())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val newTrackSelector = DefaultTrackSelector(activity)
        this.trackSelector = newTrackSelector

        val player = ExoPlayer.Builder(activity, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
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