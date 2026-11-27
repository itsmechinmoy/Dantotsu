package ani.dantotsu.media.anime.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.mpv.DantotsuPlayerView
import ani.dantotsu.media.anime.mpv.ExternalAudio
import ani.dantotsu.media.anime.mpv.ExternalSubtitle
import ani.dantotsu.media.anime.mpv.MpvVideoView
import ani.dantotsu.media.anime.mpv.VideoScaleMode
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.DrmInfo
import ani.dantotsu.parsers.StreamTypeDetector
import ani.dantotsu.parsers.Video
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DantotsuPlayerManager(
    private val activity: AppCompatActivity,
    private val playerView: DantotsuPlayerView,
    private val subtitleManager: PlayerSubtitleManager,
    private val client: OkHttpClient,
    private val onPlayerErrorCallback: (message: String) -> Unit
) {

    val mpvView: MpvVideoView
        get() = playerView.mpvView

    var isInitialized: Boolean = false
        private set

    var currentVideo: Video? = null
        private set

    var currentHeaders: Map<String, String> = emptyMap()
        private set

    private var loadJob: Job? = null
    private var pausedByFocusLoss: Boolean = false

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mpvView.playWhenReady || mpvView.isPlaying.value) {
                    pausedByFocusLoss = true
                    mpvView.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    mpvView.play()
                }
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    fun loadVideo(
        video: Video,
        startPositionMs: Long,
        externalSubtitles: List<ExternalSubtitle>,
        audioTracks: List<Track> = emptyList(),
        speed: Float = 1.0f
    ) {
        loadJob?.cancel()
        currentVideo = video
        val videoUri = runCatching { video.file.url.toUri() }.getOrNull()
        val isLocalhost = StreamTypeDetector.isLocalhost(videoUri)
        val headers = createVideoHeaders(video, isLocalhost)
        currentHeaders = headers

        val extAudio = audioTracks.map { track ->
            val langCode = LanguageMapper.getLanguageCode(track.lang)
                .takeUnless { it.equals("all", ignoreCase = true) || it.isBlank() }
                ?: "und"
            ExternalAudio(
                url = track.url,
                headers = headers,
                label = track.lang,
                language = langCode
            )
        }

        subtitleManager.loadLocalFonts()

        val drm = video.drm
        if (drm != null) {
            if (!drm.scheme.equals("clearkey", ignoreCase = true)) {
                val msg = "DRM (${drm.scheme}) requires an external player; libmpv supports ClearKey and HLS AES-128 natively."
                Logger.log("DantotsuPlayerManager: $msg")
                onPlayerErrorCallback(msg)
                return
            }
            loadJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                val cencKeyHex = resolveClearKeyHex(drm, headers)
                withContext(Dispatchers.Main) {
                    if (cencKeyHex == null && drm.licenseUrl.startsWith("http", ignoreCase = true)) {
                        onPlayerErrorCallback("Failed to acquire ClearKey decryption key")
                        return@withContext
                    }
                    startMpvPlayback(video.file.url, headers, startPositionMs, extAudio, externalSubtitles, speed, cencKeyHex)
                }
            }
            return
        }

        startMpvPlayback(video.file.url, headers, startPositionMs, extAudio, externalSubtitles, speed, cencKeyHex = null)
    }

    private fun startMpvPlayback(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        extAudio: List<ExternalAudio>,
        externalSubtitles: List<ExternalSubtitle>,
        speed: Float,
        cencKeyHex: String?
    ) {
        isInitialized = true
        requestAudioFocus()
        mpvView.loadMedia(
            videoUrl = url,
            headers = headers,
            startPositionMs = startPositionMs,
            audioTracks = extAudio,
            subtitles = externalSubtitles,
            cencKeyHex = cencKeyHex
        )
        if (speed != 1.0f) {
            mpvView.setPlaybackSpeed(speed)
        }
    }

    private fun createVideoHeaders(video: Video, isLocalhost: Boolean): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        headers.putAll(defaultHeaders)
        video.file.headers.let { headers.putAll(it) }
        if (isLocalhost) {
            headers.remove("Accept-Encoding")
            headers.remove("accept-encoding")
        }
        return headers
    }

    private fun resolveClearKeyHex(drm: DrmInfo, headers: Map<String, String>): String? {
        val raw = drm.licenseUrl.trim()
        if (raw.matches(Regex("^[0-9a-fA-F]{32}$"))) {
            return raw.lowercase()
        }
        if (raw.contains(":") && !raw.startsWith("http", ignoreCase = true)) {
            val candidate = raw.substringAfterLast(":").trim()
            if (candidate.matches(Regex("^[0-9a-fA-F]{32}$"))) {
                return candidate.lowercase()
            }
        }
        if (!raw.startsWith("http", ignoreCase = true)) {
            return parseClearKeyJsonToHex(raw)
        }
        return runCatching {
            val reqBuilder = Request.Builder().url(raw)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            drm.licenseHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            val jsonPayload = """{"type":"temporary","kids":[]}""".toRequestBody("application/json".toMediaType())
            reqBuilder.post(jsonPayload)
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // Some ClearKey servers respond to GET instead of POST
                    val getReq = Request.Builder().url(raw).apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        drm.licenseHeaders.forEach { (k, v) -> addHeader(k, v) }
                    }.build()
                    client.newCall(getReq).execute().use { getResp ->
                        if (!getResp.isSuccessful) return null
                        return parseClearKeyJsonToHex(getResp.body?.string().orEmpty())
                    }
                }
                parseClearKeyJsonToHex(body)
            }
        }.onFailure {
            Logger.log("ClearKey resolution error: ${it.message}")
        }.getOrNull()
    }

    private fun parseClearKeyJsonToHex(jsonStr: String): String? {
        val trimmed = jsonStr.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val obj = JSONObject(trimmed)
            val keys = obj.optJSONArray("keys") ?: return null
            if (keys.length() == 0) return null
            val first = keys.getJSONObject(0)
            val k = first.optString("k")
            if (k.isBlank()) return null
            val bytes = Base64.decode(k, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun play() {
        if (isInitialized) mpvView.play()
    }

    fun pause() {
        if (isInitialized) mpvView.pause()
    }

    fun seekTo(positionMs: Long) {
        if (isInitialized) mpvView.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (isInitialized) mpvView.setPlaybackSpeed(speed)
    }

    fun setVideoScaleMode(mode: VideoScaleMode) {
        if (isInitialized) mpvView.setVideoScaleMode(mode)
    }

    fun selectAudioTrack(trackId: Int) {
        if (isInitialized) mpvView.selectAudioTrack(trackId)
    }

    fun selectSubtitleTrack(trackId: Int) {
        if (isInitialized) mpvView.selectSubtitleTrack(trackId)
    }

    fun release() {
        loadJob?.cancel()
        loadJob = null
        abandonAudioFocus()
        isInitialized = false
        mpvView.release()
    }
}
