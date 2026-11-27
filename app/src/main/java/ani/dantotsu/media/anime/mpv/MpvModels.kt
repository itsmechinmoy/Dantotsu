package ani.dantotsu.media.anime.mpv

import `is`.xyz.mpv.MPVNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
}

enum class AudioChannels(val title: String, val property: String, val value: String) {
    Auto("Auto", "audio-channels", "auto-safe"),
    Mono("Mono", "audio-channels", "mono"),
    Stereo("Stereo", "audio-channels", "stereo"),
    Surround51("5.1 Surround", "audio-channels", "5.1,stereo"),
    ;

    companion object {
        fun fromPrefValue(value: String): AudioChannels =
            entries.firstOrNull { it.value == value } ?: Auto
    }
}

enum class VideoScaleMode {
    FIT,
    STRETCH,
    CROP,
    ZOOM,
    ORIGINAL,
}

enum class Decoder(val title: String, val value: String) {
    AutoCopy("AutoCopy", "auto-copy"),
    Auto("Auto", "auto"),
    SW("SW", "no"),
    HW("HW", "mediacodec"),
    HWPlus("HW+", "mediacodec,mediacodec-copy"),
}

data class AudioTrack(
    val id: Int,
    val name: String,
    val language: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
    val isSelected: Boolean = false,
)

data class SubtitleTrack(
    val id: Int,
    val name: String,
    val language: String?,
    val codec: String? = null,
    val isDefault: Boolean = false,
    val isSelected: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
)

data class ExternalSubtitle(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val language: String? = null,
    val label: String? = null,
)

data class VideoTrack(
    val id: Int,
    val name: String,
    val codec: String? = null,
    val resolution: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val isSelected: Boolean = false,
)

data class ExternalAudio(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val label: String? = null,
    val language: String? = null,
)

data class PendingMediaState(
    val videoUrl: String,
    val headers: Map<String, String>,
    val startPositionMs: Long,
    val audioTracks: List<ExternalAudio>,
    val subtitles: List<ExternalSubtitle>,
    val cencKeyHex: String? = null,
)

@Serializable
private data class RawMpvTrack(
    val id: Int,
    val type: String,
    val title: String? = null,
    val lang: String? = null,
    val codec: String? = null,
    @SerialName("codec-profile") val codecProfile: String? = null,
    val default: Boolean = false,
    val selected: Boolean = false,
    val forced: Boolean = false,
    val external: Boolean = false,
    @SerialName("demux-channel-count") val demuxChannelCount: Int? = null,
    @SerialName("audio-channels") val audioChannels: Int? = null,
    @SerialName("channel-layout") val channelLayout: String? = null,
    @SerialName("demux-samplerate") val demuxSampleRate: Int? = null,
    @SerialName("demux-bitrate") val demuxBitrate: Long? = null,
    @SerialName("codec-bitrate") val codecBitrate: Long? = null,
    @SerialName("demux-w") val demuxW: Int? = null,
    @SerialName("demux-h") val demuxH: Int? = null,
    @SerialName("demux-fps") val demuxFps: Double? = null,
    val metadata: Map<String, String?>? = null,
)

object TrackParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseTrackList(node: MPVNode?): Triple<List<AudioTrack>, List<SubtitleTrack>, List<VideoTrack>> {
        if (node == null) return Triple(emptyList(), emptyList(), emptyList())
        val rawTracks = runCatching {
            json.decodeFromString<List<RawMpvTrack>>(node.toJson())
        }.getOrNull() ?: return Triple(emptyList(), emptyList(), emptyList())

        val audio = mutableListOf<AudioTrack>()
        val subtitle = mutableListOf<SubtitleTrack>()
        val video = mutableListOf<VideoTrack>()

        for (t in rawTracks) {
            when (t.type) {
                "audio" -> audio += parseAudioTrack(t)
                "sub" -> subtitle += parseSubtitleTrack(t)
                "video" -> video += parseVideoTrack(t)
            }
        }
        return Triple(audio, subtitle, video)
    }

    private fun parseAudioTrack(t: RawMpvTrack): AudioTrack {
        val channels = t.demuxChannelCount ?: t.audioChannels
        val langName = langCodeToName(t.lang)
            ?: t.title?.takeIf { it.isNotBlank() }
            ?: t.metadata?.get("comment")?.takeIf { it.isNotBlank() }
            ?: t.lang?.uppercase()
            ?: "Track ${t.id}"

        val channelStr = when {
            t.channelLayout?.contains("5.1", ignoreCase = true) == true || channels == 6 -> "5.1 Surround"
            t.channelLayout?.contains("7.1", ignoreCase = true) == true || channels == 8 -> "7.1 Surround"
            t.channelLayout?.contains("stereo", ignoreCase = true) == true || channels == 2 -> "Stereo"
            t.channelLayout?.contains("mono", ignoreCase = true) == true || channels == 1 -> "Mono"
            channels != null -> "${channels}ch"
            else -> null
        }

        val codecStr = buildString {
            append(t.codec?.uppercase() ?: "AAC")
            if (!t.codecProfile.isNullOrBlank()) append(" [${t.codecProfile}]")
        }
        val srStr = t.demuxSampleRate?.let { if (it >= 1000) "${it / 1000}kHz" else "${it}Hz" }
        val brStr = (t.demuxBitrate ?: t.codecBitrate)?.let { if (it >= 1000) "${it / 1000}kbps" else null }
        val details = listOfNotNull(codecStr, channelStr, srStr, brStr).joinToString(" · ")
        val display = if (details.isNotBlank()) "$langName ($details)" else langName

        return AudioTrack(
            id = t.id,
            name = display,
            language = t.lang,
            codec = t.codec,
            channels = channels,
            isDefault = t.default,
            isSelected = t.selected,
        )
    }

    private fun parseSubtitleTrack(t: RawMpvTrack): SubtitleTrack {
        val langName = langCodeToName(t.lang)
        val comment = t.metadata?.get("comment")
        val baseName = when {
            !t.title.isNullOrBlank() -> t.title.trim()
            !comment.isNullOrBlank() -> comment.trim()
            langName != null -> langName
            !t.lang.isNullOrBlank() -> t.lang.uppercase()
            else -> "Subtitle ${t.id}"
        }
        val codecStr = when (t.codec?.lowercase()) {
            "webvtt" -> "WebVTT"
            "ass", "ssa" -> "ASS"
            "subrip", "srt" -> "SRT"
            "dvd_subtitle", "dvdsub" -> "DVD Sub"
            "hdmv_pgs_subtitle", "pgssub" -> "PGS"
            "mov_text" -> "MOV Text"
            else -> t.codec?.uppercase()
        }
        val tags = listOfNotNull(
            codecStr,
            if (t.external) "External" else null,
            if (t.forced) "Forced" else null,
            if (t.default) "Default" else null,
        )
        val displayName = if (tags.isNotEmpty()) "$baseName · ${tags.joinToString(" · ")}" else baseName

        return SubtitleTrack(
            id = t.id,
            name = displayName,
            language = t.lang,
            codec = t.codec,
            isDefault = t.default,
            isSelected = t.selected,
            isForced = t.forced,
            isExternal = t.external,
        )
    }

    private fun parseVideoTrack(t: RawMpvTrack): VideoTrack {
        val width = t.demuxW ?: 0
        val height = t.demuxH ?: 0
        val resolution = if (width > 0 && height > 0) "${width}x$height" else null
        val heightLabel = when {
            height >= 2160 -> "4K"
            height > 0 -> "${height}p"
            else -> null
        }
        val codecStr = buildString {
            append(t.codec?.uppercase() ?: "VIDEO")
            if (!t.codecProfile.isNullOrBlank()) append(" [${t.codecProfile}]")
        }
        val fpsStr = t.demuxFps?.let {
            val rounded = (it * 10.0).roundToLong() / 10.0
            if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}fps" else "${rounded}fps"
        }
        val brStr = (t.demuxBitrate ?: t.codecBitrate)?.let { if (it >= 1000) "${it / 1000}kbps" else null }
        val details = listOfNotNull(codecStr, fpsStr, brStr).joinToString(" · ")
        val name = if (heightLabel != null) "$heightLabel · $details" else details.ifBlank { "Video ${t.id}" }

        return VideoTrack(
            id = t.id,
            name = name,
            codec = t.codec,
            resolution = resolution,
            width = width,
            height = height,
            isSelected = t.selected,
        )
    }

    fun langCodeToName(lang: String?): String? = when (lang?.lowercase()) {
        "ja", "jpn" -> "Japanese"
        "en", "eng" -> "English"
        "zh", "zho", "chi" -> "Chinese"
        "ko", "kor" -> "Korean"
        "fr", "fra", "fre" -> "French"
        "es", "spa" -> "Spanish"
        "de", "deu", "ger" -> "German"
        "ru", "rus" -> "Russian"
        "it", "ita" -> "Italian"
        "pt", "por" -> "Portuguese"
        "ar", "ara" -> "Arabic"
        "hi", "hin" -> "Hindi"
        "th", "tha" -> "Thai"
        "vi", "vie" -> "Vietnamese"
        "ms", "msa", "may" -> "Malay"
        "id", "ind" -> "Indonesian"
        "tr", "tur" -> "Turkish"
        "pl", "pol" -> "Polish"
        "cs", "ces", "cze" -> "Czech"
        "hu", "hun" -> "Hungarian"
        "nl", "nld", "dut" -> "Dutch"
        "sv", "swe" -> "Swedish"
        "no", "nor" -> "Norwegian"
        "da", "dan" -> "Danish"
        "fi", "fin" -> "Finnish"
        "he", "heb" -> "Hebrew"
        "uk", "ukr" -> "Ukrainian"
        "ro", "ron", "rum" -> "Romanian"
        "bg", "bul" -> "Bulgarian"
        "hr", "hrv" -> "Croatian"
        "sk", "slk", "slo" -> "Slovak"
        "el", "ell", "gre" -> "Greek"
        else -> null
    }
}
