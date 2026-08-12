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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private var activeSubtitles = ArrayDeque<String>(3)
    private var lastSubtitle: String? = null
    private var lastPosition: Long = 0

    @Volatile var pendingSubtitleLabel: String? = null
    @Volatile var initialSubtitleLabel: String? = null

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
        return DefaultExtractorsFactory().withAssMkvSupport(assSubtitleParserFactory, handler)
    }

    fun createSubtitleParserFactory(): AssSubtitleParserFactory {
        initAssHandler()
        return AssSubtitleParserFactory(assHandler!!)
    }

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
                if (file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "clearTransientSubtitleCache error: ${e.message}")
        }
    }

    fun applyOnlineSubtitle(subtitle: StremioSub) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(subtitle.url).build()
                val response = client.newCall(request).execute()
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

                val subtitleFile = File(activity.cacheDir, "online_subtitle_${subtitle.id}.$extension")
                subtitleFile.writeText(cleanedContent)

                withContext(Dispatchers.Main) {
                    applySubtitleFromFile(subtitleFile, subtitle.lang, mimeType)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}", activity)
                }
            }
        }
    }

    private fun applySubtitleFromFile(file: File, lang: String, mimeType: String) {
        val player = getPlayer() ?: return
        val label = "Online: $lang"
        val subUri = Uri.fromFile(file)
        val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
            .setMimeType(mimeType)
            .setLanguage(lang)
            .setLabel(label)
            .setId(file.name)
            .build()

        val currentMediaItem = player.currentMediaItem ?: return
        val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations?.toMutableList() ?: mutableListOf()
        val alreadyExists = existingSubtitles.any { it.id == file.name }
        if (alreadyExists) {
            pendingSubtitleLabel = label
            selectSubtitleTrack(lang, label)
            return
        }

        existingSubtitles.add(subConfig)
        val newMediaItem = currentMediaItem.buildUpon()
            .setSubtitleConfigurations(existingSubtitles)
            .build()

        pendingSubtitleLabel = label
        val currentPos = player.currentPosition
        player.setMediaItem(newMediaItem, currentPos)
        player.prepare()
    }

    fun applyLocalSubtitle(uri: Uri, media: Media?) {
        val player = getPlayer() ?: return
        try {
            val label = "Local Subtitle"
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

            val currentMediaItem = player.currentMediaItem ?: return
            val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations?.toMutableList() ?: mutableListOf()
            val alreadyAdded = existingSubtitles.any { it.id == stableId }
            if (alreadyAdded) {
                pendingSubtitleLabel = label
                selectSubtitleTrack("", label)
                return
            }

            val subConfig = MediaItem.SubtitleConfiguration.Builder(finalSubUri)
                .setMimeType(finalMimeType)
                .setLanguage("und")
                .setLabel(label)
                .setId(stableId)
                .build()

            existingSubtitles.add(subConfig)

            if (media != null) {
                val mediaId = media.id
                val episodeId = media.anime?.selectedEpisode ?: "1"
                val newLocalSub = Subtitle(
                    language = "[Local] ${uri.lastPathSegment ?: "Custom"}",
                    url = uri.toString()
                )
                model.saveLocalSubtitle("$mediaId-$episodeId", newLocalSub)
                PrefManager.setCustomVal("subLang_$mediaId", newLocalSub.language)
            }

            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(existingSubtitles)
                .build()

            pendingSubtitleLabel = label
            val currentPos = player.currentPosition
            player.setMediaItem(newMediaItem, currentPos)
            player.prepare()
        } catch (e: Exception) {
            snackString("Failed to load subtitle: ${e.message}", activity)
        }
    }

    private fun selectSubtitleTrack(langCode: String, targetLabel: String?) {
        val player = getPlayer() ?: return
        try {
            val tracks = player.currentTracks
            for (groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[groupIndex]
                if (group.type == TRACK_TYPE_TEXT) {
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val trackLabel = format.label ?: ""
                        if (targetLabel != null && trackLabel == targetLabel) {
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
        val isDisabled = trackGroup.getTrackFormat(0).language == "none"
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
            .setOverrideForType(TrackSelectionOverride(trackGroup.mediaTrackGroup, index))
            .build()

        if (type == TRACK_TYPE_TEXT) {
            setupSubFormatting(playerView)
            applySubtitleStyles(customSubtitleView)
        }
        playerView.subtitleView?.alpha = when (isDisabled) {
            false -> PrefManager.getVal(PrefName.SubAlpha)
            true -> 0f
        }
    }

    fun checkTracksForPendingSubtitles(tracks: Tracks) {
        val userLabel = pendingSubtitleLabel
        val pendingLabel = userLabel ?: initialSubtitleLabel
        if (pendingLabel != null) {
            var matched = false
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type == TRACK_TYPE_TEXT) {
                    for (trackIndex in 0 until group.length) {
                        val trackLabel = group.getTrackFormat(trackIndex).label
                        if (trackLabel == pendingLabel) {
                            pendingSubtitleLabel = null
                            initialSubtitleLabel = null
                            matched = true
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            if (userLabel != null) snackString("Subtitle loaded: $pendingLabel", activity)
                            break
                        }
                    }
                }
                if (matched) return@forEachIndexed
            }
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
        assHandler = null
        assSubtitleView = null
    }
}
