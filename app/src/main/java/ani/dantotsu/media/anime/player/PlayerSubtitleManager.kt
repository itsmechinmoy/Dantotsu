package ani.dantotsu.media.anime.player

import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.connections.subtitles.OpenSubRestItem
import ani.dantotsu.connections.subtitles.OpenSubtitlesRestApi
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.SubSourceSub
import ani.dantotsu.connections.subtitles.SubSourceSubtitles
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale

@UnstableApi
class PlayerSubtitleManager(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val customSubtitleView: Xubtitle,
    private val model: MediaDetailsViewModel,
    private val getPlayer: () -> ExoPlayer?
) {

    var assHandler: AssHandler? = null
        private set
    var assSubtitleView: AssSubtitleView? = null
        private set

    var subtitleDelayMs: Long = 0L
        private set
    var audioDelayMs: Long = 0L
        private set

    var activeSubtitleDisplayName: String? = null
        private set
    var activeSubtitleId: String? = null
        private set

    private var currentActiveSubFile: File? = null
    private var currentActiveSubRawContent: String? = null
    private var currentActiveSubFormat: String = "SRT"
    private var currentActiveSubLang: String = ""
    private var currentActiveSubMimeType: String = MimeTypes.APPLICATION_SUBRIP
    private var serverSubJob: Job? = null

    @Volatile var pendingSubtitleLabel: String? = null
    @Volatile var pendingTrackId: String? = null
    @Volatile var initialSubtitleLabel: String? = null

    fun setActiveServerSubtitle(sub: Subtitle?) {
        if (sub == null) {
            currentActiveSubFile = null
            currentActiveSubRawContent = null
            activeSubtitleDisplayName = null
            activeSubtitleId = null
            serverSubJob?.cancel()
            return
        }
        val formatStr = when (sub.type) {
            SubtitleType.ASS -> "ASS"
            SubtitleType.VTT -> "VTT"
            SubtitleType.SRT -> "SRT"
            else -> "SRT"
        }
        val mimeType = when (sub.type) {
            SubtitleType.ASS -> MimeTypes.TEXT_SSA
            SubtitleType.VTT -> MimeTypes.TEXT_VTT
            SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        currentActiveSubFormat = formatStr
        currentActiveSubLang = sub.language
        currentActiveSubMimeType = mimeType
        activeSubtitleDisplayName = "${sub.language} [Server]"
        activeSubtitleId = sub.language

        // Fetch and cache server subtitle text in background so delay/sync works for server subtitles
        serverSubJob?.cancel()
        val rawUrl = sub.file.url
        val resolvedUrl = resolveSubtitleUrl(rawUrl, "", "")
        if (resolvedUrl.isNotBlank()) {
            serverSubJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = Injekt.get<NetworkHelper>().client
                    val requestBuilder = Request.Builder().url(resolvedUrl)
                    defaultHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                    sub.file.headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val content = response.body?.string()
                            if (!content.isNullOrBlank()) {
                                val ext = when (formatStr) {
                                    "ASS" -> "ass"
                                    "VTT" -> "vtt"
                                    else -> "srt"
                                }
                                val langName = sub.language
                                val file = File(activity.cacheDir, "server_sub_${langName.hashCode()}.$ext")
                                file.writeText(content)
                                currentActiveSubFile = file
                                currentActiveSubRawContent = content

                                val effectiveDelay = subtitleDelayMs - audioDelayMs
                                if (effectiveDelay != 0L) {
                                    withContext(Dispatchers.Main) {
                                        applyDelayInternal()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("PlayerSubtitleManager: Server sub cache error: ${e.message}")
                }
            }
        }
    }

    fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
        Logger.log("PlayerSubtitleManager: Subtitle delay set to ${delayMs}ms")
        applyDelayInternal()
    }

    fun setAudioDelay(delayMs: Long) {
        audioDelayMs = delayMs
        Logger.log("PlayerSubtitleManager: Audio delay set to ${delayMs}ms")
        applyDelayInternal()
    }

    private fun applyDelayInternal() {
        val rawContent = currentActiveSubRawContent
        val file = currentActiveSubFile
        val effectiveDelay = subtitleDelayMs - audioDelayMs
        if (rawContent != null && file != null) {
            try {
                // Clean up previous shifted files
                activity.cacheDir.listFiles()?.filter {
                    it.isFile && it.name.startsWith("shifted_${file.nameWithoutExtension}_")
                }?.forEach { it.delete() }

                if (effectiveDelay == 0L) {
                    applyShiftedSubtitle(file, currentActiveSubLang, currentActiveSubMimeType, isShifted = false)
                    return
                }
                val shiftedContent = shiftSubtitleTimestamps(rawContent, currentActiveSubFormat, effectiveDelay)
                val ext = file.extension.ifBlank { "srt" }
                val shiftedFile = File(activity.cacheDir, "shifted_${file.nameWithoutExtension}_${effectiveDelay}.$ext")
                shiftedFile.writeText(shiftedContent)
                applyShiftedSubtitle(shiftedFile, currentActiveSubLang, currentActiveSubMimeType, isShifted = true)
            } catch (e: Exception) {
                Logger.log("PlayerSubtitleManager: Failed to shift subtitle: ${e.message}")
            }
        } else if (serverSubJob?.isActive == true) {
            Logger.log("PlayerSubtitleManager: Subtitle caching in progress; will apply ${effectiveDelay}ms once cached")
        }
    }

    fun shiftSubtitleTimestamps(content: String, format: String, delayMs: Long): String {
        if (delayMs == 0L) return content

        fun shiftSrtVttTime(timeStr: String): String {
            val isComma = timeStr.contains(",")
            val delimiter = if (isComma) "," else "."
            val cleanStr = timeStr.trim().replace(",", ".")
            val parts = cleanStr.split(":", ".")
            val hours: Long
            val mins: Long
            val secs: Long
            val millis: Long
            if (parts.size == 4) {
                hours = parts[0].toLongOrNull() ?: 0L
                mins = parts[1].toLongOrNull() ?: 0L
                secs = parts[2].toLongOrNull() ?: 0L
                millis = parts[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else if (parts.size == 3) {
                hours = 0L
                mins = parts[0].toLongOrNull() ?: 0L
                secs = parts[1].toLongOrNull() ?: 0L
                millis = parts[2].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else return timeStr

            val totalMs = (hours * 3600000L) + (mins * 60000L) + (secs * 1000L) + millis
            val newTotalMs = (totalMs + delayMs).coerceAtLeast(0L)

            val newHours = newTotalMs / 3600000L
            val newMins = (newTotalMs % 3600000L) / 60000L
            val newSecs = (newTotalMs % 60000L) / 1000L
            val newMillis = newTotalMs % 1000L

            return "%02d:%02d:%02d%s%03d".format(newHours, newMins, newSecs, delimiter, newMillis)
        }

        fun shiftAssTime(timeStr: String): String {
            val parts = timeStr.trim().split(":", ".")
            if (parts.size != 4) return timeStr
            val hours = parts[0].toLongOrNull() ?: 0L
            val mins = parts[1].toLongOrNull() ?: 0L
            val secs = parts[2].toLongOrNull() ?: 0L
            val centis = parts[3].padEnd(2, '0').take(2).toLongOrNull() ?: 0L

            val totalMs = (hours * 3600000L) + (mins * 60000L) + (secs * 1000L) + (centis * 10L)
            val newTotalMs = (totalMs + delayMs).coerceAtLeast(0L)

            val newHours = newTotalMs / 3600000L
            val newMins = (newTotalMs % 3600000L) / 60000L
            val newSecs = (newTotalMs % 60000L) / 1000L
            val newCentis = (newTotalMs % 1000L) / 10L

            return "%d:%02d:%02d.%02d".format(newHours, newMins, newSecs, newCentis)
        }

        val srtVttRegex = Regex("""^(\d{1,2}:\d{2}:\d{2}[,\.]\d{3}|\d{2}:\d{2}[,\.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,\.]\d{3}|\d{2}:\d{2}[,\.]\d{3})(.*)$""")
        val assDialogueRegex = Regex("""^(Dialogue:\s*[^,]+,)(\d+:\d{2}:\d{2}\.\d{2}),(\d+:\d{2}:\d{2}\.\d{2})(,.*)$""", RegexOption.IGNORE_CASE)

        return when (format.uppercase(Locale.ROOT)) {
            "ASS", "SSA" -> {
                content.lines().joinToString("\n") { line ->
                    val match = assDialogueRegex.find(line.trim())
                    if (match != null) {
                        val prefix = match.groupValues[1]
                        val start = shiftAssTime(match.groupValues[2])
                        val end = shiftAssTime(match.groupValues[3])
                        val suffix = match.groupValues[4]
                        "$prefix$start,$end$suffix"
                    } else {
                        line
                    }
                }
            }
            else -> {
                content.lines().joinToString("\n") { line ->
                    val trimmed = line.trim()
                    val match = srtVttRegex.find(trimmed)
                    if (match != null) {
                        val start = shiftSrtVttTime(match.groupValues[1])
                        val end = shiftSrtVttTime(match.groupValues[2])
                        val extra = match.groupValues[3]
                        "$start --> $end$extra"
                    } else {
                        line
                    }
                }
            }
        }
    }

    private fun applyShiftedSubtitle(file: File, lang: String, mimeType: String, isShifted: Boolean) {
        val player = getPlayer() ?: return
        val currentMediaItem = player.currentMediaItem ?: return

        val shiftedTrackId = if (isShifted) "shifted_sub_${System.currentTimeMillis()}" else file.name
        val baseLabel = activeSubtitleDisplayName ?: if (lang.isNotBlank() && lang != "und") lang else "Subtitle"
        val effectiveDelay = subtitleDelayMs - audioDelayMs
        val label = if (isShifted) {
            val sign = if (effectiveDelay >= 0) "+" else ""
            "[Sync] $baseLabel (${sign}${effectiveDelay}ms)"
        } else {
            baseLabel
        }

        val subUri = Uri.fromFile(file)
        val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
            .setMimeType(mimeType)
            .setLanguage(lang.ifBlank { "und" })
            .setLabel(label)
            .setId(shiftedTrackId)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .build()

        val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations
            ?.filter { it.id != null && !it.id!!.startsWith("shifted_sub_") && it.id != "shifted_active_sub" }
            ?.toMutableList() ?: mutableListOf()

        if (isShifted) {
            existingSubtitles.add(subConfig)
        }

        pendingTrackId = shiftedTrackId
        pendingSubtitleLabel = label
        val currentPos = player.currentPosition

        val exoActivity = activity as? ExoplayerView
        if (exoActivity != null) {
            exoActivity.playerManager.applyUpdatedSubtitles(existingSubtitles, currentPos)
        } else {
            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(existingSubtitles)
                .build()
            player.setMediaItem(newMediaItem, currentPos)
            player.prepare()
            player.play()
        }
    }

    private var activeSubtitles = ArrayDeque<String>(3)
    private var lastSubtitle: String? = null
    private var lastPosition: Long = 0

    fun initAssHandler() {
        if (assHandler == null) {
            Logger.log("Libass: Creating AssHandler with OVERLAY_OPEN_GL")
            assHandler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
            val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val assView = AssSubtitleView(activity, assHandler!!)
            assView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentFrame?.addView(assView)
            assSubtitleView = assView
        }
    }

    fun createExtractorsFactory(): ExtractorsFactory {
        initAssHandler()
        val handler = assHandler!!
        val assSubtitleParserFactory = AssSubtitleParserFactory(handler)
        val compositeParserFactory = createSubtitleParserFactory()
        val defaultExtractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setTsExtractorTimestampSearchBytes(1500 * androidx.media3.extractor.ts.TsExtractor.TS_PACKET_SIZE)
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setMatroskaExtractorFlags(androidx.media3.extractor.mkv.MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            .setSubtitleParserFactory(compositeParserFactory)
        return defaultExtractorsFactory.withAssMkvSupport(assSubtitleParserFactory, handler)
    }

    fun createSubtitleParserFactory(): SubtitleParser.Factory {
        initAssHandler()
        val handler = assHandler!!
        val assFactory = AssSubtitleParserFactory(handler)
        val defaultFactory = DefaultSubtitleParserFactory()
        return object : SubtitleParser.Factory {
            override fun supportsFormat(format: Format): Boolean {
                return assFactory.supportsFormat(format) || defaultFactory.supportsFormat(format)
            }

            override fun getCueReplacementBehavior(format: Format): Int {
                return if (assFactory.supportsFormat(format)) {
                    assFactory.getCueReplacementBehavior(format)
                } else {
                    defaultFactory.getCueReplacementBehavior(format)
                }
            }

            override fun create(format: Format): SubtitleParser {
                return if (assFactory.supportsFormat(format)) {
                    assFactory.create(format)
                } else {
                    defaultFactory.create(format)
                }
            }
        }
    }

    companion object {
        fun resolveSubtitleUrl(subtitleUrl: String, vararg baseUrls: String): String {
            val subtitleUri = runCatching { URI(subtitleUrl) }.getOrElse {
                Logger.log("Failed to parse subtitle URL '$subtitleUrl': ${it.message}")
                return subtitleUrl
            }
            if (subtitleUri.isAbsolute) return subtitleUri.toString()

            baseUrls.forEach { baseUrl ->
                val resolved = runCatching {
                    if (baseUrl.isBlank()) null else URI(baseUrl).resolve(subtitleUri).takeIf { it.isAbsolute }?.toString()
                }.getOrNull()
                if (!resolved.isNullOrBlank()) return resolved
            }
            return subtitleUrl
        }

        fun buildSubtitleId(index: Int, language: String, url: String): String {
            val normalizedLanguage = language.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
            val normalizedUrlTail = runCatching { URI(url).path.substringAfterLast('/').ifBlank { "track" } }
                .getOrDefault("track")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
            return "ext_sub_${index}_${normalizedLanguage}_${normalizedUrlTail}"
        }
    }

    fun buildSubtitleConfigurations(
        subtitles: List<Subtitle>,
        embedUrl: String,
        currentVideoUrl: String,
        hasExtSubtitles: Boolean
    ): List<MediaItem.SubtitleConfiguration> {
        return subtitles.mapIndexed { index, subtitle ->
            val subtitleUrl = if (!hasExtSubtitles) currentVideoUrl else subtitle.file.url
            val resolvedSubtitleUrl = resolveSubtitleUrl(subtitleUrl, embedUrl, currentVideoUrl)
            val subtitleId = buildSubtitleId(index, subtitle.language, resolvedSubtitleUrl)
            val subtitleLangCodeRaw = LanguageMapper.getLanguageCode(subtitle.language)
            val subtitleLanguageCode =
                subtitleLangCodeRaw.takeUnless { it.equals("all", ignoreCase = true) || it.isBlank() } ?: "und"
            val subtitleMime = when (subtitle.type) {
                SubtitleType.VTT -> MimeTypes.TEXT_VTT
                SubtitleType.ASS -> MimeTypes.TEXT_SSA
                SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                SubtitleType.UNKNOWN -> {
                    Logger.log("Warning: subtitle type unknown for '$resolvedSubtitleUrl', defaulting to SRT")
                    MimeTypes.APPLICATION_SUBRIP
                }
            }
            MediaItem.SubtitleConfiguration.Builder(resolvedSubtitleUrl.toUri())
                .setMimeType(subtitleMime)
                .setId(subtitleId)
                .setLanguage(subtitleLanguageCode)
                .setLabel(subtitle.language)
                .build()
        }
    }

    fun setupSubFormatting(playerView: PlayerView) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
        val outline = when (PrefManager.getVal<Int>(PrefName.Outline)) {
            0 -> EDGE_TYPE_OUTLINE
            1 -> EDGE_TYPE_DEPRESSED
            2 -> EDGE_TYPE_DROP_SHADOW
            3 -> EDGE_TYPE_NONE
            else -> EDGE_TYPE_OUTLINE
        }
        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground)
        val subWindow = PrefManager.getVal<Int>(PrefName.SubWindow)
        val font = when (PrefManager.getVal<Int>(PrefName.Font)) {
            0 -> ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
            1 -> ResourcesCompat.getFont(activity, R.font.poppins_bold)
            2 -> ResourcesCompat.getFont(activity, R.font.poppins)
            3 -> ResourcesCompat.getFont(activity, R.font.poppins_thin)
            4 -> ResourcesCompat.getFont(activity, R.font.century_gothic_regular)
            5 -> ResourcesCompat.getFont(activity, R.font.levenim_mt_bold)
            6 -> ResourcesCompat.getFont(activity, R.font.blocky)
            else -> ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
        }
        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()

        playerView.subtitleView?.let { subtitles ->
            subtitles.setApplyEmbeddedStyles(false)
            subtitles.setApplyEmbeddedFontSizes(false)
            subtitles.setStyle(
                CaptionStyleCompat(
                    primaryColor,
                    subBackground,
                    subWindow,
                    outline,
                    secondaryColor,
                    font
                )
            )
            subtitles.alpha = when (PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
                true -> PrefManager.getVal(PrefName.SubAlpha)
                false -> 0f
            }
            subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            subtitles.setBottomPaddingFraction(0.0f)
        }
    }

    fun applySubtitleStyles(textView: Xubtitle) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground)
        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
        val subStroke = PrefManager.getVal<Float>(PrefName.SubStroke)
        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
        val font = when (PrefManager.getVal<Int>(PrefName.Font)) {
            0 -> ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
            1 -> ResourcesCompat.getFont(activity, R.font.poppins_bold)
            2 -> ResourcesCompat.getFont(activity, R.font.poppins)
            3 -> ResourcesCompat.getFont(activity, R.font.poppins_thin)
            4 -> ResourcesCompat.getFont(activity, R.font.century_gothic_regular)
            5 -> ResourcesCompat.getFont(activity, R.font.levenim_mt_bold)
            6 -> ResourcesCompat.getFont(activity, R.font.blocky)
            else -> ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
        }

        textView.setBackgroundColor(subBackground)
        textView.setTextColor(primaryColor)
        textView.typeface = font
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        textView.apply {
            when (PrefManager.getVal<Int>(PrefName.Outline)) {
                0 -> applyOutline(secondaryColor, subStroke)
                1 -> applyShineEffect(secondaryColor)
                2 -> applyDropShadow(secondaryColor, subStroke)
                3 -> {}
                else -> applyOutline(secondaryColor, subStroke)
            }
        }
        textView.alpha = when (PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
            true -> PrefManager.getVal(PrefName.SubAlpha)
            false -> 0f
        }
        val textElevation = PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50 * activity.resources.displayMetrics.heightPixels
        val positionOffset = 10f
        textView.translationY = -textElevation + positionOffset
    }

    fun handleCues(cueGroup: CueGroup, subtitle: Subtitle?) {
        val player = getPlayer() ?: return
        val exoSubtitleView = playerView.subtitleView
        val libassActive = assHandler?.hasTracks() == true || subtitle?.type == SubtitleType.ASS
        if (libassActive) {
            exoSubtitleView?.visibility = View.GONE
            customSubtitleView.visibility = View.GONE
            customSubtitleView.text = ""
            return
        }

        if (PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)) {
            exoSubtitleView?.visibility = View.GONE
            customSubtitleView.visibility = View.VISIBLE
            val newCues = cueGroup.cues.map { it.text?.toString() ?: "" }

            if (newCues.isEmpty()) {
                customSubtitleView.text = ""
                activeSubtitles.clear()
                lastSubtitle = null
                lastPosition = 0
                return
            }

            val currentPosition = player.currentPosition
            if ((lastSubtitle?.length ?: 0) < 20 || (lastPosition != 0L && currentPosition - lastPosition > 1500)) {
                activeSubtitles.clear()
            }

            for (newCue in newCues) {
                if (newCue !in activeSubtitles) {
                    if (activeSubtitles.size >= 2) {
                        activeSubtitles.removeLast()
                    }
                    activeSubtitles.addFirst(newCue)
                    lastSubtitle = newCue
                    lastPosition = currentPosition
                }
            }
            customSubtitleView.text = activeSubtitles.joinToString("\n")
        } else {
            customSubtitleView.text = ""
            customSubtitleView.visibility = View.GONE
            exoSubtitleView?.visibility = View.VISIBLE
        }
    }

    fun clearTransientSubtitleCache(episodeId: String) {
        model.clearFetchedSubtitles(episodeId)
        model.clearLocalSubtitles(episodeId)
        try {
            activity.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_") || file.name.startsWith("shifted_") || file.name.startsWith("server_sub_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "clearTransientSubtitleCache error: ${e.message}")
        }
    }

    fun applyOnlineSubtitleUrl(url: String, id: String, lang: String, displayName: String = lang, provider: String = "Online") {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = Injekt.get<NetworkHelper>().client
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            snackString("Failed to download subtitle: HTTP ${response.code}", activity)
                        }
                        return@launch
                    }

                    val subtitleContent = response.body?.string()
                    if (subtitleContent.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            snackString("Subtitle file is empty", activity)
                        }
                        return@launch
                    }

                    val detectedFormat = when {
                        subtitleContent.trimStart().startsWith("WEBVTT") -> "VTT"
                        subtitleContent.contains("[Script Info]") || subtitleContent.contains("\\[Events\\]") -> "ASS"
                        subtitleContent.contains("<tt ") || subtitleContent.contains("<tt>") -> "TTML"
                        else -> "SRT"
                    }

                    val cleanedContent = if (detectedFormat == "ASS") {
                        stripAssPositioning(subtitleContent)
                    } else {
                        subtitleContent
                    }

                    val mimeType = when (detectedFormat) {
                        "VTT" -> MimeTypes.TEXT_VTT
                        "ASS" -> MimeTypes.TEXT_SSA
                        "TTML" -> MimeTypes.APPLICATION_TTML
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }

                    val extension = when (detectedFormat) {
                        "VTT" -> "vtt"
                        "ASS" -> "ass"
                        "TTML" -> "ttml"
                        else -> "srt"
                    }

                    val subtitleFile = File(activity.cacheDir, "online_subtitle_${id.hashCode()}.$extension")
                    subtitleFile.writeText(cleanedContent)

                    withContext(Dispatchers.Main) {
                        applySubtitleFromFile(subtitleFile, lang, mimeType, displayName, id, provider)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}", activity)
                }
            }
        }
    }

    fun applyOnlineSubtitle(subtitle: StremioSub, displayName: String = subtitle.lang, provider: String = "OpenSubtitles") {
        applyOnlineSubtitleUrl(subtitle.url, subtitle.id.ifBlank { subtitle.url }, subtitle.lang, displayName, provider)
    }

    fun applyWyzieSubtitle(subtitle: WyzieSub) {
        val display = subtitle.displayLabel.ifBlank { subtitle.language }
        applyOnlineSubtitleUrl(subtitle.url, subtitle.url, subtitle.language, display, "Wyzie")
    }

    fun applySubSourceSubtitle(sub: SubSourceSub) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = SubSourceSubtitles.downloadSubtitleContent(sub.id)
            if (result != null) {
                val (filename, content) = result
                val detectedFormat = when {
                    filename.endsWith(".vtt", ignoreCase = true) || content.trimStart().startsWith("WEBVTT") -> "VTT"
                    filename.endsWith(".ass", ignoreCase = true) || filename.endsWith(".ssa", ignoreCase = true) || content.contains("[Script Info]") -> "ASS"
                    filename.endsWith(".ttml", ignoreCase = true) || content.contains("<tt>") -> "TTML"
                    else -> "SRT"
                }
                val cleaned = if (detectedFormat == "ASS") stripAssPositioning(content) else content
                val mimeType = when (detectedFormat) {
                    "VTT" -> MimeTypes.TEXT_VTT
                    "ASS" -> MimeTypes.TEXT_SSA
                    "TTML" -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                val ext = when (detectedFormat) {
                    "VTT" -> "vtt"
                    "ASS" -> "ass"
                    "TTML" -> "ttml"
                    else -> "srt"
                }
                val cacheFile = File(activity.cacheDir, "online_subtitle_${sub.id.hashCode()}.$ext")
                cacheFile.writeText(cleaned)
                val display = sub.releaseName.ifBlank { sub.lang }
                withContext(Dispatchers.Main) {
                    applySubtitleFromFile(cacheFile, sub.lang, mimeType, display, sub.id, "SubSource")
                }
            } else {
                withContext(Dispatchers.Main) {
                    snackString("Failed to download SubSource subtitle", activity)
                }
            }
        }
    }

    fun applyOpenSubRestSubtitle(item: OpenSubRestItem) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val downloadUrl = OpenSubtitlesRestApi.getDownloadUrl(item.fileId)
            if (downloadUrl != null) {
                val display = item.fileName.ifBlank { item.language }
                applyOnlineSubtitleUrl(downloadUrl, item.fileId.toString(), item.language, display, "OpenSubtitles")
            } else {
                withContext(Dispatchers.Main) {
                    snackString("Failed to get OpenSubtitles download link", activity)
                }
            }
        }
    }

    private fun applySubtitleFromFile(
        file: File,
        lang: String,
        mimeType: String,
        displayName: String = lang,
        id: String = file.name,
        provider: String = "Online"
    ) {
        val player = getPlayer() ?: return
        currentActiveSubFile = file
        currentActiveSubRawContent = runCatching { file.readText() }.getOrNull()
        currentActiveSubFormat = when (mimeType) {
            MimeTypes.TEXT_VTT -> "VTT"
            MimeTypes.TEXT_SSA -> "ASS"
            MimeTypes.APPLICATION_TTML -> "TTML"
            else -> "SRT"
        }
        currentActiveSubLang = lang
        currentActiveSubMimeType = mimeType
        activeSubtitleDisplayName = "$displayName ($provider)"
        activeSubtitleId = id

        val label = "Online: $displayName"
        val subUri = Uri.fromFile(file)
        val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
            .setMimeType(mimeType)
            .setLanguage(lang.ifBlank { "und" })
            .setLabel(label)
            .setId(file.name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .build()

        val currentMediaItem = player.currentMediaItem ?: return
        val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations?.toMutableList() ?: mutableListOf()
        val alreadyExists = existingSubtitles.any { it.id == file.name }
        if (alreadyExists) {
            pendingTrackId = file.name
            pendingSubtitleLabel = label
            selectSubtitleTrack(file.name, label)
            return
        }

        existingSubtitles.add(subConfig)
        pendingTrackId = file.name
        pendingSubtitleLabel = label
        val currentPos = player.currentPosition

        val exoActivity = activity as? ExoplayerView
        if (exoActivity != null) {
            exoActivity.playerManager.applyUpdatedSubtitles(existingSubtitles, currentPos)
        } else {
            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(existingSubtitles)
                .build()
            player.setMediaItem(newMediaItem, currentPos)
            player.prepare()
            player.play()
        }
    }

    fun applyLocalSubtitle(uri: Uri, media: Media?) {
        val player = getPlayer() ?: return
        try {
            val contentResolver = activity.applicationContext.contentResolver
            val rawMime = contentResolver.getType(uri)
            val uriStr = uri.toString().lowercase(Locale.ROOT)
            val finalMimeType = when {
                rawMime == "application/octet-stream" || rawMime == null -> when {
                    uriStr.contains(".vtt") -> MimeTypes.TEXT_VTT
                    uriStr.contains(".ssa") || uriStr.contains(".ass") -> MimeTypes.TEXT_SSA
                    uriStr.contains(".ttml") || uriStr.contains(".xml") -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                else -> rawMime
            }

            val subtitleBytes = try {
                if (uri.scheme == "file") {
                    File(uri.path ?: "").readBytes()
                } else {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: Exception) {
                null
            }

            if (subtitleBytes == null) {
                snackString("Failed to read subtitle file", activity)
                return
            }

            val ext = when (finalMimeType) {
                MimeTypes.TEXT_VTT -> "vtt"
                MimeTypes.TEXT_SSA -> "ass"
                MimeTypes.APPLICATION_TTML -> "ttml"
                else -> "srt"
            }
            val cacheFile = File(activity.cacheDir, "local_sub_${uri.toString().hashCode()}.$ext")
            if (finalMimeType == MimeTypes.TEXT_SSA) {
                cacheFile.writeText(stripAssPositioning(subtitleBytes.toString(Charsets.UTF_8)))
            } else {
                cacheFile.writeBytes(subtitleBytes)
            }

            val finalSubUri = Uri.fromFile(cacheFile)
            val stableId = "local_sub_${uri.toString().hashCode()}"
            val fileName = uri.lastPathSegment ?: "Custom"
            val label = "Local: $fileName"

            currentActiveSubFile = cacheFile
            currentActiveSubRawContent = if (finalMimeType == MimeTypes.TEXT_SSA) stripAssPositioning(subtitleBytes.toString(Charsets.UTF_8)) else subtitleBytes.toString(Charsets.UTF_8)
            currentActiveSubFormat = ext.uppercase(Locale.ROOT)
            currentActiveSubLang = "und"
            currentActiveSubMimeType = finalMimeType
            activeSubtitleDisplayName = "[Local] $fileName"
            activeSubtitleId = stableId

            val currentMediaItem = player.currentMediaItem ?: return
            val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations?.toMutableList() ?: mutableListOf()
            val alreadyAdded = existingSubtitles.any { it.id == stableId }
            if (alreadyAdded) {
                pendingTrackId = stableId
                pendingSubtitleLabel = label
                selectSubtitleTrack(stableId, label)
                return
            }

            val subConfig = MediaItem.SubtitleConfiguration.Builder(finalSubUri)
                .setMimeType(finalMimeType)
                .setLanguage("und")
                .setLabel(label)
                .setId(stableId)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .build()

            existingSubtitles.add(subConfig)

            if (media != null) {
                val mediaId = media.id
                val episodeId = media.anime?.selectedEpisode ?: "1"
                val newLocalSub = Subtitle(
                    language = "[Local] $fileName",
                    url = uri.toString()
                )
                model.saveLocalSubtitle("$mediaId-$episodeId", newLocalSub)
                PrefManager.setCustomVal("subLang_$mediaId", newLocalSub.language)
            }

            pendingTrackId = stableId
            pendingSubtitleLabel = label
            val currentPos = player.currentPosition

            val exoActivity = activity as? ExoplayerView
            if (exoActivity != null) {
                exoActivity.playerManager.applyUpdatedSubtitles(existingSubtitles, currentPos)
            } else {
                val newMediaItem = currentMediaItem.buildUpon()
                    .setSubtitleConfigurations(existingSubtitles)
                    .build()
                player.setMediaItem(newMediaItem, currentPos)
                player.prepare()
                player.play()
            }
        } catch (e: Exception) {
            snackString("Failed to load subtitle: ${e.message}", activity)
        }
    }

    private fun selectSubtitleTrack(targetTrackId: String?, targetLabel: String?) {
        val player = getPlayer() ?: return
        try {
            val tracks = player.currentTracks
            for (groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[groupIndex]
                if (group.type == TRACK_TYPE_TEXT) {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val trackId = format.id ?: ""
                        val trackLabel = format.label ?: ""
                        if ((targetTrackId != null && trackId == targetTrackId) ||
                            (targetLabel != null && trackLabel == targetLabel)
                        ) {
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            snackString("Subtitle loaded: $trackLabel", activity)
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "selectSubtitleTrack error: ${e.message}")
        }
    }

    fun onSetTrackGroupOverride(
        trackGroup: Tracks.Group,
        type: @C.TrackType Int,
        index: Int = 0
    ) {
        val player = getPlayer() ?: return
        val format = trackGroup.getTrackFormat(index)
        val isDisabled = format.language == "none"
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
            .setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, index))
            .build()

        if (type == TRACK_TYPE_TEXT) {
            setupSubFormatting(playerView)
            applySubtitleStyles(customSubtitleView)
            playerView.subtitleView?.visibility = if (isDisabled) View.GONE else View.VISIBLE
        }
        playerView.subtitleView?.alpha = when (isDisabled) {
            false -> PrefManager.getVal(PrefName.SubAlpha)
            true -> 0f
        }
    }

    fun checkTracksForPendingSubtitles(tracks: Tracks) {
        val targetTrackId = pendingTrackId
        val userLabel = pendingSubtitleLabel
        val pendingLabel = userLabel ?: initialSubtitleLabel

        if (targetTrackId == null && pendingLabel == null) return

        var matched = false
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val trackId = format.id ?: ""
                    val trackLabel = format.label ?: ""
                    val trackLang = format.language ?: ""

                    val isExactIdMatch = targetTrackId != null && trackId == targetTrackId
                    val isExactLabelMatch = pendingLabel != null && trackLabel.equals(pendingLabel, ignoreCase = true)
                    val isFuzzyMatch = targetTrackId == null && pendingLabel != null && (
                        trackLang.equals(pendingLabel, ignoreCase = true) ||
                        (pendingLabel.equals("English", ignoreCase = true) && (trackLang.equals("en", ignoreCase = true) || trackLang.equals("eng", ignoreCase = true) || trackLabel.contains("English", ignoreCase = true) || trackLabel.contains("Eng", ignoreCase = true))) ||
                        (trackLabel.isNotBlank() && trackLabel.contains(pendingLabel, ignoreCase = true))
                    )

                    if (isExactIdMatch || isExactLabelMatch || isFuzzyMatch) {
                        pendingTrackId = null
                        pendingSubtitleLabel = null
                        initialSubtitleLabel = null
                        matched = true
                        onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                        if (userLabel != null && targetTrackId != null) {
                            if (targetTrackId.startsWith("shifted_sub_")) {
                                val effectiveDelay = subtitleDelayMs - audioDelayMs
                                snackString("Sync applied: ${effectiveDelay}ms", activity)
                            } else {
                                snackString("Subtitle loaded: $trackLabel", activity)
                            }
                        }
                        break
                    }
                }
            }
            if (matched) break
        }
    }

    private fun stripAssPositioning(assContent: String): String {
        val lines = assContent.lines().toMutableList()
        var inEvents = false
        var inStyles = false
        val styleFormatMap = mutableMapOf<String, Int>()

        for (i in lines.indices) {
            val line = lines[i]
            val trimmedLine = line.trim()
            if (trimmedLine.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                inStyles = false
                continue
            } else if (trimmedLine.equals("[V4+ Styles]", ignoreCase = true) ||
                trimmedLine.equals("[V4 Styles]", ignoreCase = true)
            ) {
                inStyles = true
                inEvents = false
                continue
            } else if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]")) {
                inEvents = false
                inStyles = false
                continue
            }

            if (inStyles) {
                if (trimmedLine.startsWith("Format:", ignoreCase = true)) {
                    val parts = trimmedLine.substringAfter(":").split(",")
                    styleFormatMap.clear()
                    parts.forEachIndexed { index, name ->
                        styleFormatMap[name.trim().lowercase(Locale.ROOT)] = index
                    }
                } else if (trimmedLine.startsWith("Style:", ignoreCase = true) && styleFormatMap.isNotEmpty()) {
                    val styleContent = trimmedLine.substringAfter("Style:")
                    val parts = styleContent.split(",").toMutableList()
                    val alignIdx = styleFormatMap["alignment"]
                    if (alignIdx != null && alignIdx < parts.size) {
                        parts[alignIdx] = "2"
                    }
                    val marginVIdx = styleFormatMap["marginv"]
                    if (marginVIdx != null && marginVIdx < parts.size) {
                        parts[marginVIdx] = "0"
                    }
                    lines[i] = "Style: ${parts.joinToString(",")}"
                }
            }

            if (inEvents && (trimmedLine.startsWith("Dialogue:", ignoreCase = true) ||
                        trimmedLine.startsWith("Comment:", ignoreCase = true))
            ) {
                var modifiedLine = line
                modifiedLine = modifiedLine.replace(Regex("\\\\pos\\([^)]*\\)"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\move\\([^)]*\\)"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\an[1-9]"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\a[1-9]+"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\org\\([^)]*\\)"), "")
                lines[i] = modifiedLine
            }
        }
        return lines.joinToString("\n")
    }

    fun release() {
        serverSubJob?.cancel()
        serverSubJob = null
        assHandler = null
        assSubtitleView = null
    }
}
