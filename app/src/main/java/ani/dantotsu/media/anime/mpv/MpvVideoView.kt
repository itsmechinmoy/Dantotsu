package ani.dantotsu.media.anime.mpv

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native SurfaceView-backed MPV player wrapping `io.github.animiruorg:mpv-android-lib`.
 * Exposes reactive flows for position, duration, buffering, playback state, and tracks.
 */
class MpvVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var mpv: MPV? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val videoTracks: StateFlow<List<VideoTrack>> = _videoTracks.asStateFlow()

    private val _currentAudioTrack = MutableStateFlow<AudioTrack?>(null)
    val currentAudioTrack: StateFlow<AudioTrack?> = _currentAudioTrack.asStateFlow()

    private val _currentSubtitleTrack = MutableStateFlow<SubtitleTrack?>(null)
    val currentSubtitleTrack: StateFlow<SubtitleTrack?> = _currentSubtitleTrack.asStateFlow()

    private val _currentVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val currentVideoTrack: StateFlow<VideoTrack?> = _currentVideoTrack.asStateFlow()

    private val _videoScaleMode = MutableStateFlow(VideoScaleMode.FIT)
    val videoScaleMode: StateFlow<VideoScaleMode> = _videoScaleMode.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    var onError: ((String) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onFileLoaded: (() -> Unit)? = null

    var isInitialized = false
        private set
    var surfaceReady = false
        private set
    var playWhenReady = true
        private set

    private var isFileLoaded = false
    private var initialBufferingDone = false
    private var pendingMediaState: PendingMediaState? = null
    private var lastGoodPositionMs = 0L
    private var wasBackgrounded = false
    private var httpError: String? = null
    private var activeContentPfd: android.os.ParcelFileDescriptor? = null
    private val isShutdown = AtomicBoolean(false)

    private val eventObserver = object : MPV.EventObserver {
        override fun eventProperty(property: String) = mainHandler.post { handlePropertyChange(property) }.let {}
        override fun eventProperty(property: String, value: Long) = mainHandler.post { handlePropertyChange(property) }.let {}
        override fun eventProperty(property: String, value: Boolean) = mainHandler.post { handlePropertyChange(property) }.let {}
        override fun eventProperty(property: String, value: String) = mainHandler.post { handlePropertyChange(property) }.let {}
        override fun eventProperty(property: String, value: Double) = mainHandler.post { handlePropertyChange(property) }.let {}
        override fun eventProperty(property: String, value: MPVNode) = mainHandler.post { handlePropertyChange(property) }.let {}
        override fun event(eventId: Int, data: MPVNode) {
            mainHandler.post { handleEvent(eventId, data) }
        }
    }

    private val logObserver = object : MPV.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (text.contains("HTTP error", ignoreCase = true)) {
                httpError = text.removePrefix("http: ").trim()
            }
            if (level <= MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN) {
                Logger.log("MPV/$prefix: $text")
            }
        }
    }

    init {
        holder.addCallback(this)
    }

    private fun prepareMpvDirs(): Pair<String, String> {
        val configDir = File(context.filesDir, "mpv_config").apply { mkdirs() }
        val fontsDir = File(configDir, "fonts").apply { mkdirs() }
        val cacheDir = context.cacheDir.absolutePath

        File(configDir, "mpv.conf").writeText(PrefManager.getVal<String>(PrefName.MpvConf))
        File(configDir, "input.conf").writeText(PrefManager.getVal<String>(PrefName.MpvInput))

        runCatching {
            val outCa = File(configDir, "cacert.pem")
            if (!outCa.exists()) {
                context.assets.open("cacert.pem").use { input ->
                    outCa.outputStream().use { input.copyTo(it) }
                }
            }
        }

        val fontResources = mapOf(
            "poppins_semi_bold.ttf" to R.font.poppins_semi_bold,
            "poppins_bold.ttf" to R.font.poppins_bold,
            "poppins.ttf" to R.font.poppins,
            "poppins_thin.ttf" to R.font.poppins_thin,
            "century_gothic_regular.ttf" to R.font.century_gothic_regular,
            "levenim_mt_bold.ttf" to R.font.levenim_mt_bold,
            "blocky.ttf" to R.font.blocky,
        )
        fontResources.forEach { (filename, resId) ->
            val outFile = File(fontsDir, filename)
            if (!outFile.exists()) {
                runCatching {
                    context.resources.openRawResource(resId).use { input ->
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        return configDir.absolutePath to cacheDir
    }

    fun ensureInitialized() {
        if (isInitialized && !isShutdown.get()) return
        if (isShutdown.get()) {
            releaseInternal()
            isShutdown.set(false)
        }

        val (configDir, cacheDir) = prepareMpvDirs()
        val vo = if (PrefManager.getVal(PrefName.MpvGpuNext)) "gpu-next" else "gpu"

        val instance = MPV(context) { m ->
            m.setOptionString("config", "yes")
            m.setOptionString("config-dir", configDir)
            m.setOptionString("gpu-shader-cache-dir", cacheDir)
            m.setOptionString("icc-cache-dir", cacheDir)
            m.setOptionString("vo", vo)
            m.setOptionString("profile", "fast")
            m.setOptionString("idle", "yes")
            m.setOptionString("keep-open", "always")
            m.setOptionString("ytdl", "no")
            m.setOptionString("input-default-bindings", "yes")

            val caFile = File(configDir, "cacert.pem")
            if (caFile.exists()) {
                m.setOptionString("tls-verify", "yes")
                m.setOptionString("tls-ca-file", caFile.absolutePath)
            } else {
                m.setOptionString("tls-verify", "no")
            }

            val hwdec = PrefManager.getVal<String>(PrefName.MpvHwDec).ifBlank { "mediacodec,mediacodec-copy" }
            m.setOptionString("hwdec", hwdec)
            m.setOptionString("vd-lavc-film-grain", "cpu")

            applyDecoderInitOptions(m)
            applyAudioInitOptions(m)
            applyCacheInitOptions(m)
            applySubtitleInitOptions(m, configDir)

            m.setOptionString("video-sync", "audio")
            m.setOptionString("initial-audio-sync", "yes")
            m.setOptionString("framedrop", "vo")
            m.setOptionString("vd-lavc-framedrop", "nonkey")
            m.setOptionString("network-timeout", "15")
            m.setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")

            val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).apply { mkdirs() }
            m.setOptionString("screenshot-directory", screenshotDir.absolutePath)
        }

        mpv = instance
        instance.addObserver(eventObserver)
        instance.addLogObserver(logObserver)
        observeProperties(instance)
        isInitialized = true
    }

    private fun applyDecoderInitOptions(m: MPV) {
        val vfFilters = mutableListOf<String>()
        if (PrefManager.getVal(PrefName.MpvYuv420p)) vfFilters += "format=yuv420p"
        when (PrefManager.getVal<Int>(PrefName.MpvDebanding)) {
            1 -> vfFilters += "gradfun=radius=12"
            2 -> m.setOptionString("deband", "yes")
        }
        if (vfFilters.isNotEmpty()) {
            m.setOptionString("vf", vfFilters.joinToString(","))
        }
        m.setOptionString("brightness", PrefManager.getVal<Int>(PrefName.MpvBrightness).toString())
        m.setOptionString("contrast", PrefManager.getVal<Int>(PrefName.MpvContrast).toString())
        m.setOptionString("saturation", PrefManager.getVal<Int>(PrefName.MpvSaturation).toString())
        m.setOptionString("gamma", PrefManager.getVal<Int>(PrefName.MpvGamma).toString())
        m.setOptionString("hue", PrefManager.getVal<Int>(PrefName.MpvHue).toString())
    }

    private fun applyAudioInitOptions(m: MPV) {
        m.setOptionString("audio-channels", PrefManager.getVal<String>(PrefName.MpvAudioChannels).ifBlank { "auto-safe" })
        m.setOptionString("audio-pitch-correction", if (PrefManager.getVal(PrefName.MpvAudioPitchCorrection)) "yes" else "no")
        m.setOptionString("audio-delay", (PrefManager.getVal<Int>(PrefName.MpvAudioDelay) / 1000.0).toString())
        val volMax = PrefManager.getVal<Int>(PrefName.MpvVolumeBoostCap).coerceIn(100, 200)
        m.setOptionString("volume-max", volMax.toString())
        m.setOptionString("volume", "100")
        m.setOptionString("audio-buffer", "0.5")
    }

    private fun applyCacheInitOptions(m: MPV) {
        val bufferSecs = PrefManager.getVal<Int>(PrefName.PlayerBufferSize).coerceIn(10, 240)
        m.setOptionString("demuxer-thread", "yes")
        m.setOptionString("demuxer-readahead-secs", bufferSecs.toString())
        m.setOptionString("cache", "yes")
        m.setOptionString("cache-pause", "yes")
        m.setOptionString("cache-pause-initial", "yes")
        m.setOptionString("cache-pause-wait", "3")
        val defaultMb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        val cacheMb = PrefManager.getVal<Int>(PrefName.MpvDemuxerCacheMb).coerceIn(16, 256).takeIf { it > 0 } ?: defaultMb
        val cacheBytes = cacheMb.toLong() * 1024L * 1024L
        m.setOptionString("demuxer-max-bytes", cacheBytes.toString())
        m.setOptionString("demuxer-max-back-bytes", cacheBytes.toString())
    }

    private fun applySubtitleInitOptions(m: MPV, configDir: String) {
        val enabled = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        m.setOptionString("sub-visibility", if (enabled) "yes" else "no")
        m.setOptionString("sub-fonts-dir", File(configDir, "fonts").absolutePath)
        val fontNames = listOf("Poppins SemiBold", "Poppins Bold", "Poppins", "Poppins Thin", "Century Gothic", "Levenim MT", "Blocky")
        val fontName = fontNames.getOrNull(PrefManager.getVal(PrefName.Font)) ?: "Poppins SemiBold"
        m.setOptionString("sub-font", fontName)
        m.setOptionString("sub-font-size", (PrefManager.getVal<Int>(PrefName.FontSize).coerceIn(10, 80) * 2).toString())
        m.setOptionString("sub-ass-override", PrefManager.getVal<String>(PrefName.MpvSubAssOverride).ifBlank { "yes" })
        m.setOptionString("sub-delay", (PrefManager.getVal<Int>(PrefName.MpvSubDelay) / 1000.0).toString())
        m.setOptionString("sub-speed", PrefManager.getVal<Float>(PrefName.MpvSubSpeed).toString())
        m.setOptionString("secondary-sub-delay", (PrefManager.getVal<Int>(PrefName.MpvSubSecondaryDelay) / 1000.0).toString())
        m.setOptionString("sub-color", intColorToMpvHex(PrefManager.getVal(PrefName.PrimaryColor)))
        m.setOptionString("sub-outline-color", intColorToMpvHex(PrefManager.getVal(PrefName.SecondaryColor)))
        m.setOptionString("sub-back-color", intColorToMpvHex(PrefManager.getVal(PrefName.SubBackground)))
        m.setOptionString("sub-outline-size", (PrefManager.getVal<Float>(PrefName.SubStroke) / 2f).toString())
        m.setOptionString("sub-pos", (100 - PrefManager.getVal<Float>(PrefName.SubBottomMargin).toInt().coerceIn(0, 50)).toString())
    }

    private fun intColorToMpvHex(color: Int): String {
        val a = Color.alpha(color) / 255.0
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return "%.3f/%.3f/%.3f/%.3f".format(java.util.Locale.US, r, g, b, a)
    }

    private fun observeProperties(m: MPV) {
        listOf(
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "speed" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "sid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "aid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "vid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "demuxer-cache-duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
        ).forEach { (prop, format) ->
            m.observeProperty(prop, format)
        }
    }

    private fun handleEvent(eventId: Int, data: MPVNode) {
        val m = mpv ?: return
        if (isShutdown.get()) return
        when (eventId) {
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                isFileLoaded = true
                initialBufferingDone = false
                httpError = null
                pendingMediaState?.let { pending ->
                    attachPendingExternalTracks(m, pending)
                    if (pending.startPositionMs > 0) {
                        seekTo(pending.startPositionMs)
                    }
                    pendingMediaState = null
                }
                refreshTracks()
                forceUpdateDurationAndPosition()
                refreshPlayerState()
                val statsPage = PrefManager.getVal<Int>(PrefName.MpvStatsPage)
                if (statsPage in 1..3) {
                    m.command("script-binding", "stats/display-stats-toggle")
                    m.command("script-binding", "stats/display-page-$statsPage")
                }
                onFileLoaded?.invoke()
            }
            MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                val errorMsg = data.asMap()?.get("file_error")?.asString()
                if (!errorMsg.isNullOrBlank()) {
                    val detail = listOfNotNull(errorMsg, httpError).joinToString(": ")
                    httpError = null
                    isFileLoaded = false
                    _playbackState.value = PlaybackState.IDLE
                    _isPlaying.value = false
                    onError?.invoke(detail)
                    return
                }
                if (pendingMediaState != null || !isFileLoaded || wasBackgrounded) return
                if (_duration.value > 0L) {
                    _playbackState.value = PlaybackState.ENDED
                    _isPlaying.value = false
                    onEnded?.invoke()
                }
            }
            MPV.mpvEvent.MPV_EVENT_SHUTDOWN -> {
                isFileLoaded = false
                _playbackState.value = PlaybackState.IDLE
                _isPlaying.value = false
                isShutdown.set(true)
            }
        }
    }

    private fun attachPendingExternalTracks(m: MPV, state: PendingMediaState) {
        state.audioTracks.forEach { audio ->
            if (audio.headers.isNotEmpty()) {
                val hdr = audio.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                m.setPropertyString("http-header-fields", hdr)
            }
            val cmd = mutableListOf("audio-add", audio.url, "auto")
            if (!audio.label.isNullOrBlank()) cmd.add(audio.label)
            if (!audio.language.isNullOrBlank()) cmd.add(audio.language)
            m.command(*cmd.toTypedArray())
        }
        state.subtitles.forEach { sub ->
            if (sub.headers.isNotEmpty()) {
                val hdr = sub.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                m.setPropertyString("http-header-fields", hdr)
            }
            val cmd = mutableListOf("sub-add", sub.url, "auto")
            if (!sub.label.isNullOrBlank()) cmd.add(sub.label)
            if (!sub.language.isNullOrBlank()) cmd.add(sub.language)
            m.command(*cmd.toTypedArray())
        }
    }

    private fun handlePropertyChange(property: String) {
        val m = mpv ?: return
        if (isShutdown.get()) return
        when (property) {
            "time-pos" -> {
                val pos = ((m.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong()
                _currentPosition.value = pos.coerceAtLeast(0L)
                _bufferedPosition.value = (_currentPosition.value + ((m.getPropertyDouble("demuxer-cache-duration") ?: 0.0) * 1000.0).toLong()).coerceAtLeast(_currentPosition.value)
            }
            "duration" -> _duration.value = ((m.getPropertyDouble("duration") ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
            "speed" -> _playbackSpeed.value = (m.getPropertyDouble("speed") ?: 1.0).toFloat()
            "track-list" -> refreshTracks()
            "vid" -> {
                val vid = m.getPropertyString("vid")?.toIntOrNull() ?: 0
                _currentVideoTrack.value = _videoTracks.value.firstOrNull { it.id == vid }
            }
            "aid" -> {
                val aid = m.getPropertyString("aid")?.toIntOrNull() ?: 0
                _currentAudioTrack.value = _audioTracks.value.firstOrNull { it.id == aid }
            }
            "sid" -> {
                val sid = m.getPropertyString("sid")?.toIntOrNull() ?: -1
                _currentSubtitleTrack.value = _subtitleTracks.value.firstOrNull { it.id == sid }
            }
            "demuxer-cache-duration" -> {
                val cacheMs = ((m.getPropertyDouble("demuxer-cache-duration") ?: 0.0) * 1000.0).toLong()
                _bufferedPosition.value = (_currentPosition.value + cacheMs).coerceAtLeast(_currentPosition.value)
                refreshPlayerState()
            }
            "pause", "paused-for-cache", "core-idle", "seeking", "eof-reached" -> refreshPlayerState()
        }
    }

    private fun forceUpdateDurationAndPosition() {
        val m = mpv ?: return
        val dur = ((m.getPropertyDouble("duration") ?: 0.0) * 1000.0).toLong()
        if (dur > 0L) _duration.value = dur
        val pos = ((m.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong()
        _currentPosition.value = pos.coerceAtLeast(0L)
    }

    private fun refreshPlayerState() {
        val m = mpv ?: return
        if (isShutdown.get()) return
        val isPaused = m.getPropertyBoolean("pause") ?: true
        val isCoreIdle = m.getPropertyBoolean("core-idle") ?: true
        val eof = m.getPropertyBoolean("eof-reached") ?: false
        val seeking = m.getPropertyBoolean("seeking") ?: false
        val pausedForCache = m.getPropertyBoolean("paused-for-cache") ?: false
        val cacheDur = m.getPropertyDouble("demuxer-cache-duration") ?: 0.0

        if (!initialBufferingDone && isFileLoaded && (!isCoreIdle || cacheDur >= 1.5)) {
            initialBufferingDone = true
        }

        val state = when {
            eof && !wasBackgrounded -> PlaybackState.ENDED
            !isFileLoaded -> if (pendingMediaState != null) PlaybackState.BUFFERING else PlaybackState.IDLE
            !initialBufferingDone || seeking || pausedForCache -> PlaybackState.BUFFERING
            isPaused -> PlaybackState.PAUSED
            isCoreIdle -> PlaybackState.BUFFERING
            else -> PlaybackState.PLAYING
        }
        _playbackState.value = state
        _isPlaying.value = state == PlaybackState.PLAYING
    }

    private fun refreshTracks() {
        val m = mpv ?: return
        val node = m.getPropertyNode("track-list") ?: return
        val (audio, sub, video) = TrackParser.parseTrackList(node)
        _audioTracks.value = audio
        _subtitleTracks.value = sub
        _videoTracks.value = video
        val aid = m.getPropertyString("aid")?.toIntOrNull()
        _currentAudioTrack.value = audio.firstOrNull { it.id == aid } ?: audio.firstOrNull { it.isSelected }
        val sid = m.getPropertyString("sid")?.toIntOrNull()
        _currentSubtitleTrack.value = sub.firstOrNull { it.id == sid } ?: sub.firstOrNull { it.isSelected }
        val vid = m.getPropertyString("vid")?.toIntOrNull()
        _currentVideoTrack.value = video.firstOrNull { it.id == vid } ?: video.firstOrNull { it.isSelected }
    }

    fun loadMedia(
        videoUrl: String,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        audioTracks: List<ExternalAudio> = emptyList(),
        subtitles: List<ExternalSubtitle> = emptyList(),
        cencKeyHex: String? = null,
    ) {
        val state = PendingMediaState(videoUrl, headers, startPositionMs, audioTracks, subtitles, cencKeyHex)
        pendingMediaState = state
        wasBackgrounded = false
        playWhenReady = true
        ensureInitialized()
        if (!surfaceReady) {
            _playbackState.value = PlaybackState.BUFFERING
            return
        }
        loadMediaInternal(state)
    }

    private fun loadMediaInternal(state: PendingMediaState) {
        val m = mpv ?: return
        isFileLoaded = false
        initialBufferingDone = false
        httpError = null
        _playbackState.value = PlaybackState.BUFFERING
        _duration.value = 0L
        _currentPosition.value = 0L
        _bufferedPosition.value = 0L
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        _videoTracks.value = emptyList()

        val headerStr = if (state.headers.isNotEmpty() && state.videoUrl.startsWith("http", ignoreCase = true)) {
            state.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
        } else ""
        m.setPropertyString("http-header-fields", headerStr)

        if (!state.cencKeyHex.isNullOrBlank()) {
            m.setOptionString("demuxer-lavf-o", "cenc_decryption_key=${state.cencKeyHex}")
        } else {
            m.setOptionString("demuxer-lavf-o", "")
        }

        runCatching { activeContentPfd?.close() }
        activeContentPfd = null

        val targetUrl = if (state.videoUrl.startsWith("content://")) {
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(state.videoUrl), "r")
            }.getOrNull()
            if (pfd != null) {
                activeContentPfd = pfd
                "fd://${pfd.fd}"
            } else {
                state.videoUrl
            }
        } else {
            state.videoUrl
        }

        m.command("set", "pause", if (playWhenReady) "no" else "yes")
        m.command("loadfile", targetUrl, "replace")
    }

    fun play() {
        playWhenReady = true
        if (!isInitialized || isShutdown.get()) return
        mpv?.command("set", "pause", "no")
    }

    fun pause() {
        playWhenReady = false
        if (!isInitialized || isShutdown.get()) return
        mpv?.command("set", "pause", "yes")
    }

    fun togglePlayPause() {
        if (playWhenReady || _isPlaying.value) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        if (!isInitialized || isShutdown.get()) return
        val clamped = positionMs.coerceAtLeast(0L)
        mpv?.command("seek", (clamped / 1000.0).toString(), "absolute")
        _currentPosition.value = clamped
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!isInitialized || isShutdown.get()) return
        val clamped = speed.coerceIn(0.25f, 50.0f)
        mpv?.setPropertyDouble("speed", clamped.toDouble())
        _playbackSpeed.value = clamped
    }

    fun selectAudioTrack(trackId: Int) {
        if (!isInitialized || isShutdown.get()) return
        val m = mpv ?: return
        if (trackId == -1) m.setPropertyString("aid", "no") else m.setPropertyInt("aid", trackId)
    }

    fun selectSubtitleTrack(trackId: Int) {
        if (!isInitialized || isShutdown.get()) return
        val m = mpv ?: return
        if (trackId == -1) m.setPropertyString("sid", "no") else m.setPropertyInt("sid", trackId)
    }

    fun addAndSelectExternalSubtitle(url: String, headers: Map<String, String> = emptyMap(), label: String? = null, language: String? = null) {
        if (!isInitialized || isShutdown.get()) return
        val m = mpv ?: return
        if (headers.isNotEmpty() && url.startsWith("http", ignoreCase = true)) {
            val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
            m.setPropertyString("http-header-fields", hdr)
        }
        val cmd = mutableListOf("sub-add", url, "select")
        if (!label.isNullOrBlank()) cmd.add(label)
        if (!language.isNullOrBlank()) cmd.add(language)
        m.command(*cmd.toTypedArray())
        m.setPropertyString("sub-visibility", "yes")
    }

    fun setSubDelaySeconds(seconds: Double) {
        if (!isInitialized || isShutdown.get()) return
        mpv?.setPropertyDouble("sub-delay", seconds)
    }

    fun setAudioDelaySeconds(seconds: Double) {
        if (!isInitialized || isShutdown.get()) return
        mpv?.setPropertyDouble("audio-delay", seconds)
    }

    fun setSubVisibility(visible: Boolean) {
        if (!isInitialized || isShutdown.get()) return
        mpv?.setPropertyString("sub-visibility", if (visible) "yes" else "no")
    }

    fun getVideoWidth(): Int = _currentVideoTrack.value?.width ?: (mpv?.getPropertyInt("width") ?: 0)
    fun getVideoHeight(): Int = _currentVideoTrack.value?.height ?: (mpv?.getPropertyInt("height") ?: 0)

    fun setVideoScaleMode(mode: VideoScaleMode) {
        _videoScaleMode.value = mode
        val m = mpv ?: return
        if (!isInitialized || isShutdown.get()) return
        when (mode) {
            VideoScaleMode.FIT -> {
                m.setPropertyBoolean("keepaspect", true)
                m.setPropertyString("video-unscaled", "no")
                m.setPropertyDouble("panscan", 0.0)
            }
            VideoScaleMode.STRETCH -> {
                m.setPropertyBoolean("keepaspect", false)
                m.setPropertyString("video-unscaled", "no")
                m.setPropertyDouble("panscan", 0.0)
            }
            VideoScaleMode.CROP -> {
                m.setPropertyBoolean("keepaspect", true)
                m.setPropertyString("video-unscaled", "no")
                m.setPropertyDouble("panscan", 1.0)
            }
            VideoScaleMode.ZOOM -> {
                m.setPropertyBoolean("keepaspect", true)
                m.setPropertyString("video-unscaled", "no")
                m.setPropertyDouble("panscan", 0.5)
            }
            VideoScaleMode.ORIGINAL -> {
                m.setPropertyBoolean("keepaspect", true)
                m.setPropertyString("video-unscaled", "yes")
                m.setPropertyDouble("panscan", 0.0)
            }
        }
    }

    fun applyRuntimePreferences() {
        val m = mpv ?: return
        if (!isInitialized || isShutdown.get()) return
        val configDir = File(context.filesDir, "mpv_config").absolutePath
        applySubtitleInitOptions(m, configDir)
        m.setPropertyDouble("brightness", PrefManager.getVal<Int>(PrefName.MpvBrightness).toDouble())
        m.setPropertyDouble("contrast", PrefManager.getVal<Int>(PrefName.MpvContrast).toDouble())
        m.setPropertyDouble("saturation", PrefManager.getVal<Int>(PrefName.MpvSaturation).toDouble())
        m.setPropertyDouble("gamma", PrefManager.getVal<Int>(PrefName.MpvGamma).toDouble())
        m.setPropertyDouble("hue", PrefManager.getVal<Int>(PrefName.MpvHue).toDouble())
        m.setPropertyString("audio-channels", PrefManager.getVal<String>(PrefName.MpvAudioChannels).ifBlank { "auto-safe" })
        m.setPropertyDouble("audio-delay", PrefManager.getVal<Int>(PrefName.MpvAudioDelay) / 1000.0)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val m = mpv ?: return false
        if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) return false
        var mapped = KeyMapping[event.keyCode]
        if (mapped == null) {
            if (!event.isPrintingKey) return false
            val ch = event.unicodeChar
            if (ch and KeyCharacterMap.COMBINING_ACCENT != 0) return false
            mapped = ch.toChar().toString()
        }
        if (event.repeatCount > 0) return true
        val mod = mutableListOf<String>()
        if (event.isShiftPressed) mod += "shift"
        if (event.isCtrlPressed) mod += "ctrl"
        if (event.isAltPressed) mod += "alt"
        if (event.isMetaPressed) mod += "meta"
        mod += mapped
        m.command(if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup", mod.joinToString("+"))
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ensureInitialized()
        surfaceReady = true
        val m = mpv ?: return
        val vo = if (PrefManager.getVal(PrefName.MpvGpuNext)) "gpu-next" else "gpu"
        m.attachSurface(holder.surface)
        m.setOptionString("force-window", "yes")
        m.setPropertyString("vo", vo)
        m.setOptionString("vid", "auto")

        val pending = pendingMediaState
        when {
            pending != null -> loadMediaInternal(pending)
            isFileLoaded && wasBackgrounded -> {
                wasBackgrounded = false
                seekTo(lastGoodPositionMs)
                if (playWhenReady) m.command("set", "pause", "no")
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        mpv?.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        lastGoodPositionMs = _currentPosition.value
        val m = mpv ?: return
        if (!isShutdown.get()) {
            if (isFileLoaded) {
                m.command("set", "pause", "yes")
                wasBackgrounded = true
            }
            m.setPropertyString("vid", "no")
            m.setPropertyString("vo", "null")
            m.setPropertyString("force-window", "no")
            m.detachSurface()
        }
    }

    private fun releaseInternal() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { activeContentPfd?.close() }
        activeContentPfd = null
        mpv?.let {
            it.removeObserver(eventObserver)
            it.removeLogObserver(logObserver)
            runCatching { it.close() }
        }
        mpv = null
    }

    fun release() {
        if (!isInitialized) return
        isInitialized = false
        surfaceReady = false
        isFileLoaded = false
        pendingMediaState = null
        releaseInternal()
        isShutdown.set(true)
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
    }
}
