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
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.AudioFocusListener
import ani.dantotsu.media.anime.MediaDataSourceFactory
import ani.dantotsu.media.anime.VideoCache
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.StreamTypeDetector
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import io.github.peerless2012.ass.media.kt.withAssSupport
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
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

    private fun buildPlayerHttpClient(isLocalhost: Boolean): okhttp3.OkHttpClient {
        return client.newBuilder().apply {
            connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            addNetworkInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isLocal = StreamTypeDetector.isLocalhost(host)
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
                val isLocal = StreamTypeDetector.isLocalhost(host)
                val newRequest = if (isLocal) {
                    request.newBuilder()
                        .removeHeader("Accept-Encoding")
                        .build()
                } else {
                    request
                }
                val response = chain.proceed(newRequest)
                if (isLocal && response.isSuccessful) {
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val isMediaContent = contentType.startsWith("video/") ||
                            contentType.startsWith("audio/") ||
                            contentType.startsWith("image/")

                    val isSegment = isMediaContent || StreamTypeDetector.isSegmentUrl(request.url)
                    val isM3u8 = !isSegment && (
                            contentType.contains("mpegurl") ||
                                    StreamTypeDetector.isM3u8Url(request.url)
                            )
                    val hasGzipEncoding = response.header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true

                    val body = response.body
                    if (body != null && !isSegment && (isM3u8 || hasGzipEncoding)) {
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

                        val startsWithHls = StreamTypeDetector.startsWithHlsMagic(decompressedBytes)
                        val finalBytes = if (startsWithHls || isM3u8) {
                            var text = decompressedBytes.toString(Charsets.UTF_8)
                            if (text.startsWith("\uFEFF")) {
                                text = text.substring(1)
                            }
                            text.trimStart().toByteArray(Charsets.UTF_8)
                        } else {
                            decompressedBytes
                        }

                        val newContentType = if (isM3u8 && !contentType.contains("mpegurl")) {
                            "application/vnd.apple.mpegurl; charset=utf-8".toMediaTypeOrNull()
                        } else {
                            response.body?.contentType()
                        }

                        val newBody = ResponseBody.create(newContentType, finalBytes)
                        response.newBuilder()
                            .removeHeader("Content-Encoding")
                            .header("Content-Length", finalBytes.size.toString())
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
    }

    private fun buildMediaItem(
        video: Video,
        subConfigs: List<MediaItem.SubtitleConfiguration>,
        mimeType: String?,
        downloadedMediaItem: MediaItem?,
        mediaMetadata: MediaMetadata?,
        isLocalhost: Boolean,
        isLocalhostTorrent: Boolean,
        isLocalhostMp4: Boolean
    ): MediaItem {
        return downloadedMediaItem?.buildUpon()?.apply {
            if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
        }?.build() ?: MediaItem.Builder()
            .setUri(video.file.url.toUri())
            .apply {
                val effectiveMimeType = mimeType ?: if (isLocalhost && !isLocalhostTorrent && !isLocalhostMp4 && video.format != VideoType.DASH && video.format != VideoType.CONTAINER) {
                    androidx.media3.common.MimeTypes.APPLICATION_M3U8
                } else null
                if (effectiveMimeType != null) setMimeType(effectiveMimeType)
                if (subConfigs.isNotEmpty()) setSubtitleConfigurations(subConfigs)
                if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
                video.drm?.let { drm ->
                    val uuid = when (drm.scheme.lowercase()) {
                        "playready" -> C.PLAYREADY_UUID
                        "clearkey" -> C.CLEARKEY_UUID
                        else -> C.WIDEVINE_UUID
                    }
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(uuid)
                            .setLicenseUri(drm.licenseUrl)
                            .setLicenseRequestHeaders(drm.licenseHeaders)
                            .setMultiSession(true)
                            .build()
                    )
                    Logger.log("DRM: ${drm.scheme} license @ ${drm.licenseUrl}")
                }
            }
            .build()
    }

    private fun resolveActiveMediaSourceFactory(
        isContentUri: Boolean,
        isLocalhostTorrent: Boolean,
        isLocalhost: Boolean,
        upstream: DataSource.Factory,
        assMediaSourceFactory: DefaultMediaSourceFactory,
        extractorsFactory: ExtractorsFactory,
        assParserFactory: androidx.media3.extractor.text.SubtitleParser.Factory,
        drmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider
    ): DefaultMediaSourceFactory {
        return if (isContentUri) {
            val localDataSourceFactory = DefaultDataSource.Factory(activity)
            DefaultMediaSourceFactory(localDataSourceFactory, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .setDrmSessionManagerProvider(drmProvider)
        } else if (isLocalhostTorrent || isLocalhost) {
            DefaultMediaSourceFactory(upstream, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .setDrmSessionManagerProvider(drmProvider)
        } else {
            assMediaSourceFactory
        }
    }

    private fun mergeAudioTracks(
        primarySource: MediaSource,
        audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track>,
        videoUrl: String,
        activeFactory: DefaultMediaSourceFactory
    ): MediaSource {
        val audioSources = mutableListOf<MediaSource>()
        audioTracks.forEach { audioTrack ->
            val audioUrl = audioTrack.url
            if (audioUrl.isNotBlank() && audioUrl != videoUrl) {
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

        return if (audioSources.isNotEmpty()) {
            MergingMediaSource(primarySource, *audioSources.toTypedArray())
        } else {
            primarySource
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
        val videoUri = runCatching { video.file.url.toUri() }.getOrNull()
        val isLocalhost = StreamTypeDetector.isLocalhost(videoUri)
        val isLocalhostTorrent = StreamTypeDetector.isTorrentStream(videoUri)
        val isLocalhostMp4 = StreamTypeDetector.isLocalMp4(videoUri)

        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video.file.headers?.let {
            headers.putAll(it)
        }
        if (isLocalhost) {
            headers.remove("Accept-Encoding")
            headers.remove("accept-encoding")
        }

        val httpClient = buildPlayerHttpClient(isLocalhost)
        val httpDataSourceFactory = MediaDataSourceFactory.resolveHttpFactory(
            context = activity,
            headers = headers,
            okHttpClient = httpClient,
            isLocalhost = isLocalhost,
            isLocalhostMp4 = isLocalhostMp4
        )

        val upstream = MediaDataSourceFactory.resolveUpstreamFactory(
            activity,
            headers,
            httpDataSourceFactory,
            isLocalhost,
            isLocalhostTorrent
        )
        val cacheFactory = MediaDataSourceFactory.resolveCacheFactory(
            activity,
            video.file.url,
            upstream
        )

        val extractorsFactory = subtitleManager.createExtractorsFactory()
        val assParserFactory = subtitleManager.createSubtitleParserFactory()
        val drmProvider = WrappedDrmCallback.providerFor(httpDataSourceFactory)
        val assMediaSourceFactory = DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
            .setSubtitleParserFactory(assParserFactory)
            .setDrmSessionManagerProvider(drmProvider)

        val mediaItem = buildMediaItem(
            video, subConfigs, mimeType, downloadedMediaItem, mediaMetadata,
            isLocalhost, isLocalhostTorrent, isLocalhostMp4
        )
        this.currentMediaItem = mediaItem

        val isContentUri =
            (mediaItem.localConfiguration?.uri?.scheme ?: video.file.url.substringBefore(":")) == "content"

        val activeFactory = resolveActiveMediaSourceFactory(
            isContentUri, isLocalhostTorrent, isLocalhost, upstream,
            assMediaSourceFactory, extractorsFactory, assParserFactory, drmProvider
        )
        this.activeMediaSourceFactory = activeFactory
        val primarySource = activeFactory.createMediaSource(mediaItem)

        val finalSource = mergeAudioTracks(primarySource, audioTracks, video.file.url, activeFactory)
        this.mediaSource = finalSource
        return Pair(finalSource, mediaItem)
    }

    var activeMediaSourceFactory: MediaSource.Factory? = null

    fun applyUpdatedSubtitles(newSubConfigs: List<MediaItem.SubtitleConfiguration>, position: Long) {
        val player = exoPlayer ?: return
        val currentItem = currentMediaItem ?: return

        // Snapshot current track parameters so audio selection survives the media-source rebuild.
        val savedTrackParams = player.trackSelectionParameters

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
        // Restore track selection so the audio track (and any disabled text tracks) are not reset.
        player.trackSelectionParameters = savedTrackParams
        player.play()
    }

    fun buildExoplayer(
        playbackPosition: Long,
        playbackParameters: PlaybackParameters,
        listener: Player.Listener,
        forceDefaultRenderers: Boolean = false
    ): ExoPlayer {
        releaseExoPlayer()

        val uri = currentMediaItem?.localConfiguration?.uri
        val isTorrentStream = StreamTypeDetector.isTorrentStream(uri)
        val targetBufferBytes = if (isTorrentStream) 48 * 1024 * 1024 else androidx.media3.common.C.LENGTH_UNSET
        val maxBufferMs = if (isTorrentStream) 60_000 else DEFAULT_MAX_BUFFER_MS
        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(BACK_BUFFER_DURATION_MS, false)
            .setBufferDurationsMs(
                DEFAULT_MIN_BUFFER_MS,
                maxBufferMs,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        subtitleManager.initAssHandler()
        val handler = subtitleManager.assHandler!!
        Logger.log("Libass: Calling renderersFactory.withAssSupport()")
        val renderersFactory = DefaultRenderersFactory(activity)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(object : MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunnelingDecoder: Boolean
                ): List<MediaCodecInfo> {
                    val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                    if (mimeType.equals(MimeTypes.AUDIO_AAC, ignoreCase = true)) {
                        // Prefer Google's software decoder for AAC to fix AAC Main (mp4a.40.1) crashes on hardware decoders (especially Samsung/Qualcomm)
                        val (softwareDecoders, hardwareDecoders) = decoders.partition {
                            it.name.startsWith("c2.android.", ignoreCase = true) ||
                            it.name.startsWith("OMX.google.", ignoreCase = true) ||
                            it.name.contains("sw", ignoreCase = true)
                        }
                        return softwareDecoders + hardwareDecoders
                    }
                    return decoders
                }
            })
            .withAssSupport(handler)

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