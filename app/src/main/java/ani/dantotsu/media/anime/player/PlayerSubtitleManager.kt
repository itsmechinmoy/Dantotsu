package ani.dantotsu.media.anime.player

import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
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
import ani.dantotsu.media.anime.EpisodeSubtitleStore
import ani.dantotsu.media.anime.MpvPlayerActivity
import ani.dantotsu.media.anime.mpv.DantotsuPlayerView
import ani.dantotsu.media.anime.mpv.ExternalSubtitle
import ani.dantotsu.media.anime.mpv.MpvVideoView
import ani.dantotsu.media.anime.mpv.SubtitleTrack
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class PlayerSubtitleManager(
    private val activity: AppCompatActivity,
    private val playerView: DantotsuPlayerView,
    private val customSubtitleView: Xubtitle,
    private val model: MediaDetailsViewModel,
    private val getMpv: () -> MpvVideoView?
) {

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
    private var currentActiveSubMimeType: String = MIME_SRT
    private var serverSubJob: Job? = null

    @Volatile var pendingSubtitleLabel: String? = null
    @Volatile var pendingTrackId: String? = null
    @Volatile var initialSubtitleLabel: String? = null

    private val loadedFontNames = mutableSetOf<String>()

    fun setActiveServerSubtitle(sub: Subtitle?) {
        if (sub == null) {
            currentActiveSubFile = null
            currentActiveSubRawContent = null
            activeSubtitleDisplayName = null
            activeSubtitleId = null
            serverSubJob?.cancel()
            return
        }
        val formatStr = subtitleTypeToFormat(sub.type)
        val mimeType = subtitleTypeToMime(sub.type)
        currentActiveSubFormat = formatStr
        currentActiveSubLang = sub.language
        currentActiveSubMimeType = mimeType
        activeSubtitleDisplayName = "${sub.language} [Server]"
        activeSubtitleId = sub.language

        serverSubJob?.cancel()
        val rawUrl = sub.file.url
        val resolvedUrl = resolveSubtitleUrl(rawUrl, "", "")
        if (resolvedUrl.isNotBlank()) {
            fetchAndCacheServerSub(sub, resolvedUrl, formatStr)
        }
    }

    private fun subtitleTypeToFormat(type: SubtitleType): String {
        return when (type) {
            SubtitleType.ASS -> "ASS"
            SubtitleType.VTT -> "VTT"
            SubtitleType.SRT -> "SRT"
            else -> "SRT"
        }
    }

    private fun subtitleTypeToMime(type: SubtitleType): String {
        return when (type) {
            SubtitleType.ASS -> MIME_ASS
            SubtitleType.VTT -> MIME_VTT
            SubtitleType.SRT -> MIME_SRT
            else -> MIME_SRT
        }
    }

    private fun fetchAndCacheServerSub(sub: Subtitle, resolvedUrl: String, formatStr: String) {
        serverSubJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = Injekt.get<NetworkHelper>().client
                val requestBuilder = Request.Builder().url(resolvedUrl)
                defaultHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                sub.file.headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val content = response.body?.string()
                        if (!content.isNullOrBlank()) {
                            val ext = when (formatStr) {
                                "ASS" -> "ass"
                                "VTT" -> "vtt"
                                else -> "srt"
                            }
                            val file = File(activity.cacheDir, "server_sub_${sub.language.hashCode()}.$ext")
                            file.writeText(content)
                            currentActiveSubFile = file
                            currentActiveSubRawContent = content
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log("PlayerSubtitleManager: Server sub cache error: ${e.message}")
            }
        }
    }

    fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
        getMpv()?.setSubDelaySeconds(delayMs / 1000.0)
        Logger.log("PlayerSubtitleManager: Subtitle delay set to ${delayMs}ms")
    }

    fun setAudioDelay(delayMs: Long) {
        audioDelayMs = delayMs
        getMpv()?.setAudioDelaySeconds(delayMs / 1000.0)
        Logger.log("PlayerSubtitleManager: Audio delay set to ${delayMs}ms")
    }

    fun loadLocalFonts() {
        runCatching {
            val targetFontsDir = File(activity.filesDir, "mpv_config/fonts").apply { mkdirs() }
            val fontDirs = buildList {
                add(File(activity.filesDir, "mpv/fonts"))
                add(File(activity.filesDir, "fonts"))
                activity.cacheDir.listFiles()?.forEach { dir ->
                    if (dir.isDirectory && dir.name.contains("font", ignoreCase = true)) {
                        add(dir)
                    }
                }
            }
            fontDirs.forEach { dir ->
                if (dir.isDirectory) {
                    dir.walkTopDown()
                        .filter {
                            it.isFile && (
                                it.extension.equals("ttf", true) ||
                                    it.extension.equals("otf", true) ||
                                    it.extension.equals("ttc", true)
                                )
                        }
                        .forEach { file ->
                            if (loadedFontNames.add(file.name)) {
                                val dest = File(targetFontsDir, file.name)
                                if (!dest.exists() || dest.length() != file.length()) {
                                    file.copyTo(dest, overwrite = true)
                                }
                            }
                        }
                }
            }
        }
    }

    companion object {
        const val MIME_VTT = "text/vtt"
        const val MIME_ASS = "text/x-ssa"
        const val MIME_TTML = "application/ttml+xml"
        const val MIME_SRT = "application/x-subrip"

        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]", "0.0.0.0")
        private fun String.isHttp() = startsWith("http://", true) || startsWith("https://", true)

        private fun decodeParam(raw: String): String? {
            val once = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
            if (once.isHttp()) return once
            return runCatching { URLDecoder.decode(once, "UTF-8") }.getOrNull()?.takeIf { it.isHttp() }
        }

        fun unwrapProxyUrl(url: String): String {
            var current = url
            repeat(5) {
                val uri = runCatching { URI(current) }.getOrNull() ?: return current
                if (uri.host !in LOOPBACK) return current
                current = uri.rawQuery.orEmpty().split('&')
                    .mapNotNull { it.substringAfter('=', "").takeIf(String::isNotEmpty) }
                    .firstNotNullOfOrNull { decodeParam(it) } ?: return current
            }
            return current
        }

        fun resolveSubtitleUrl(subtitleUrl: String, vararg baseUrls: String): String {
            val unwrapped = unwrapProxyUrl(subtitleUrl)
            val subtitleUri = runCatching { URI(unwrapped) }.getOrElse {
                Logger.log("Failed to parse subtitle URL '$unwrapped': ${it.message}")
                return unwrapped
            }
            if (subtitleUri.isAbsolute) return unwrapProxyUrl(subtitleUri.toString())

            baseUrls.forEach { baseUrl ->
                val resolved = runCatching {
                    if (baseUrl.isBlank()) null else URI(baseUrl).resolve(subtitleUri).takeIf { it.isAbsolute }?.toString()
                }.getOrNull()
                if (!resolved.isNullOrBlank()) return unwrapProxyUrl(resolved)
            }
            return unwrapped
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

    private fun isTargetSubtitleMatch(language: String, targetLabel: String?): Boolean {
        if (targetLabel == null) return false
        if (language.equals(targetLabel, ignoreCase = true) || language.contains(targetLabel, ignoreCase = true)) return true
        if (targetLabel.equals("English", ignoreCase = true)) {
            if (language.contains("Eng", ignoreCase = true)) return true
            return Regex("(?i)(?:^|[^a-zA-Z])(en|eng)(?:[^a-zA-Z]|$)").containsMatchIn(language)
        }
        return false
    }

    fun buildExternalSubtitles(
        subtitles: List<Subtitle>,
        embedUrl: String,
        currentVideoUrl: String,
        hasExtSubtitles: Boolean,
        defaultSubLanguage: String? = null
    ): List<ExternalSubtitle> {
        loadLocalFonts()
        val targetLabel = defaultSubLanguage ?: initialSubtitleLabel
        return subtitles.mapIndexedNotNull { index, subtitle ->
            val rawUrl = if (!hasExtSubtitles) currentVideoUrl else subtitle.file.url
            if (rawUrl.isBlank()) return@mapIndexedNotNull null
            val resolvedSubtitleUrl = unwrapProxyUrl(resolveSubtitleUrl(rawUrl, embedUrl, currentVideoUrl))
            val subtitleLangCodeRaw = LanguageMapper.getLanguageCode(subtitle.language)
            val subtitleLanguageCode =
                subtitleLangCodeRaw.takeUnless { it.equals("all", ignoreCase = true) || it.isBlank() } ?: "und"
            val isMatch = isTargetSubtitleMatch(subtitle.language, targetLabel)
            if (isMatch && pendingSubtitleLabel == null) {
                pendingSubtitleLabel = subtitle.language
            }
            ExternalSubtitle(
                url = resolvedSubtitleUrl,
                headers = subtitle.file.headers,
                language = subtitleLanguageCode,
                label = subtitle.language.ifBlank { "Track ${index + 1}" }
            )
        }
    }

    private fun resolveSubtitleFont(): Typeface? {
        return when (PrefManager.getVal<Int>(PrefName.Font)) {
            0 -> ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
            1 -> ResourcesCompat.getFont(activity, R.font.poppins_bold)
            2 -> ResourcesCompat.getFont(activity, R.font.poppins)
            3 -> ResourcesCompat.getFont(activity, R.font.poppins_thin)
            4 -> ResourcesCompat.getFont(activity, R.font.century_gothic_regular)
            5 -> ResourcesCompat.getFont(activity, R.font.levenim_mt_bold)
            6 -> ResourcesCompat.getFont(activity, R.font.blocky)
            else -> ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
        }
    }

    fun setupSubFormatting() {
        loadLocalFonts()
        (getMpv() ?: playerView.mpvView).applyRuntimePreferences()
        val visible = PrefManager.getVal<Boolean>(PrefName.Subtitles)
        (getMpv() ?: playerView.mpvView).setSubVisibility(visible)
    }

    fun applySubtitleStyles(textView: Xubtitle) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground)
        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
        val subStroke = PrefManager.getVal<Float>(PrefName.SubStroke)
        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
        val font = resolveSubtitleFont()

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
        customSubtitleView.visibility = View.GONE
    }

    fun clearOnlineSubtitle(mediaId: Int? = null, clearPersistedForEp: String? = null) {
        currentActiveSubFile = null
        currentActiveSubRawContent = null
        activeSubtitleDisplayName = null
        activeSubtitleId = null
        pendingTrackId = null
        pendingSubtitleLabel = null
        serverSubJob?.cancel()
        if (mediaId != null) {
            val savedLang: String? = PrefManager.getNullableCustomVal("subLang_$mediaId", null, String::class.java)
            if (savedLang?.startsWith("Online:") == true) {
                PrefManager.setCustomVal("subLang_$mediaId", null)
            }
            if (clearPersistedForEp != null) {
                EpisodeSubtitleStore.clearSavedSubtitle(activity, mediaId, clearPersistedForEp)
            }
        }
        try {
            activity.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("online_subtitle_") || file.name.startsWith("shifted_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "clearOnlineSubtitle error: ${e.message}")
        }
    }

    fun restoreSavedOnlineSubtitle(mediaId: Int, episodeNumber: String): Boolean {
        val saved = EpisodeSubtitleStore.getSavedSubtitle(mediaId, episodeNumber) ?: return false
        val savedFilePath = saved.filePath
        if (savedFilePath != null) {
            val file = File(savedFilePath)
            if (file.exists() && file.length() > 0) {
                val mimeType = when {
                    file.name.endsWith(".vtt", ignoreCase = true) -> MIME_VTT
                    file.name.endsWith(".ass", ignoreCase = true) || file.name.endsWith(".ssa", ignoreCase = true) -> MIME_ASS
                    file.name.endsWith(".ttml", ignoreCase = true) -> MIME_TTML
                    else -> MIME_SRT
                }
                applySubtitleFromFile(file, saved.language, mimeType, saved.displayName, saved.id, saved.provider)
                return true
            }
        }
        if (!saved.url.isNullOrBlank()) {
            applyOnlineSubtitleUrl(saved.url, saved.id, saved.language, saved.displayName, saved.provider)
            return true
        }
        return false
    }

    fun clearTransientSubtitleCache(episodeId: String, mediaId: Int? = null) {
        clearOnlineSubtitle(mediaId)
        model.clearFetchedSubtitles(episodeId)
        model.clearLocalSubtitles(episodeId)
        try {
            activity.cacheDir.listFiles()?.forEach { file ->
                if (
                    file.name.startsWith("online_subtitle_") ||
                    file.name.startsWith("local_sub_") ||
                    file.name.startsWith("shifted_") ||
                    file.name.startsWith("server_sub_")
                ) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "clearTransientSubtitleCache error: ${e.message}")
        }
    }

    private fun detectSubtitleFormat(content: String, filename: String? = null): String {
        return when {
            filename?.endsWith(".vtt", ignoreCase = true) == true || content.trimStart().startsWith("WEBVTT") -> "VTT"
            filename?.endsWith(".ass", ignoreCase = true) == true || filename?.endsWith(".ssa", ignoreCase = true) == true ||
                content.contains("[Script Info]") || content.contains("[Events]") -> "ASS"
            filename?.endsWith(".ttml", ignoreCase = true) == true || content.contains("<tt ") || content.contains("<tt>") -> "TTML"
            else -> "SRT"
        }
    }

    private fun formatToMime(format: String): String {
        return when (format) {
            "VTT" -> MIME_VTT
            "ASS" -> MIME_ASS
            "TTML" -> MIME_TTML
            else -> MIME_SRT
        }
    }

    private fun formatToExtension(format: String): String {
        return when (format) {
            "VTT" -> "vtt"
            "ASS" -> "ass"
            "TTML" -> "ttml"
            else -> "srt"
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

                    val detectedFormat = detectSubtitleFormat(subtitleContent)
                    val mimeType = formatToMime(detectedFormat)
                    val extension = formatToExtension(detectedFormat)

                    val subtitleFile = File(activity.cacheDir, "online_subtitle_${id.hashCode()}.$extension")
                    subtitleFile.writeText(subtitleContent)

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

    fun applyOtherServerSubtitle(subtitle: Subtitle, serverName: String, uniqueKey: String) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = Injekt.get<NetworkHelper>().client
                val reqBuilder = Request.Builder().url(subtitle.file.url)
                subtitle.file.headers.forEach { (k, v) ->
                    reqBuilder.addHeader(k, v)
                }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            snackString("Failed to download subtitle from $serverName: HTTP ${response.code}", activity)
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
                    val detectedFormat = detectSubtitleFormat(subtitleContent)
                    val mimeType = formatToMime(detectedFormat)
                    val extension = formatToExtension(detectedFormat)
                    val subtitleFile = File(activity.cacheDir, "server_sub_${uniqueKey.hashCode()}.$extension")
                    subtitleFile.writeText(subtitleContent)
                    withContext(Dispatchers.Main) {
                        val displayName = "${subtitle.language} [$serverName]"
                        applySubtitleFromFile(subtitleFile, subtitle.language, mimeType, displayName, uniqueKey, serverName)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}", activity)
                }
            }
        }
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
                val detectedFormat = detectSubtitleFormat(content, filename)
                val mimeType = formatToMime(detectedFormat)
                val ext = formatToExtension(detectedFormat)
                val cacheFile = File(activity.cacheDir, "online_subtitle_${sub.id.hashCode()}.$ext")
                cacheFile.writeText(content)
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
        val mpv = getMpv() ?: return
        currentActiveSubFile = file
        currentActiveSubRawContent = runCatching { file.readText() }.getOrNull()
        currentActiveSubFormat = when (mimeType) {
            MIME_VTT -> "VTT"
            MIME_ASS -> "ASS"
            MIME_TTML -> "TTML"
            else -> "SRT"
        }
        currentActiveSubLang = lang
        currentActiveSubMimeType = mimeType
        activeSubtitleDisplayName = "$displayName ($provider)"
        activeSubtitleId = id

        val label = "Online: $displayName"
        pendingTrackId = file.name
        pendingSubtitleLabel = label

        (activity as? MpvPlayerActivity)?.let { mpvActivity ->
            val epNum = runCatching { mpvActivity.episode.number }.getOrNull()
            if (epNum != null) {
                EpisodeSubtitleStore.saveSubtitle(
                    context = mpvActivity,
                    mediaId = MpvPlayerActivity.media.id,
                    episodeNumber = epNum,
                    sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                        id = id,
                        displayName = displayName,
                        provider = provider,
                        language = lang
                    ),
                    sourceFile = file
                )
            }
        }

        PrefManager.setVal(PrefName.Subtitles, true)
        mpv.addAndSelectExternalSubtitle(
            url = file.absolutePath,
            label = label,
            language = lang.ifBlank { "und" }
        )
        setupSubFormatting()
        snackString("Subtitle loaded: $displayName", activity)
    }

    private fun resolveLocalSubtitleMimeType(uri: Uri): String {
        val contentResolver = activity.applicationContext.contentResolver
        val rawMime = contentResolver.getType(uri)
        if (rawMime != null && rawMime != "application/octet-stream") return rawMime
        val uriStr = uri.toString().lowercase(Locale.ROOT)
        return when {
            uriStr.contains(".vtt") || uriStr.contains("format=vtt") -> MIME_VTT
            uriStr.contains(".ssa") || uriStr.contains(".ass") || uriStr.contains("format=ass") -> MIME_ASS
            uriStr.contains(".ttml") || uriStr.contains(".xml") -> MIME_TTML
            else -> MIME_SRT
        }
    }

    private fun readSubtitleBytesFromUri(uri: Uri): ByteArray? {
        val contentResolver = activity.applicationContext.contentResolver
        return try {
            if (uri.scheme == "file") {
                File(uri.path ?: "").readBytes()
            } else {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLocalSubtitlePreference(media: Media?, fileName: String, uri: Uri) {
        if (media == null) return
        val mediaId = media.id
        val episodeId = media.anime?.selectedEpisode ?: "1"
        val newLocalSub = Subtitle(
            language = "[Local] $fileName",
            url = uri.toString()
        )
        model.saveLocalSubtitle("$mediaId-$episodeId", newLocalSub)
        PrefManager.setCustomVal("subLang_$mediaId", newLocalSub.language)
    }

    private fun createLocalSubtitleCacheFile(
        uri: Uri,
        finalMimeType: String,
        subtitleBytes: ByteArray
    ): Triple<File, String, String> {
        val ext = when (finalMimeType) {
            MIME_VTT -> "vtt"
            MIME_ASS -> "ass"
            MIME_TTML -> "ttml"
            else -> "srt"
        }
        val cacheFile = File(activity.cacheDir, "local_sub_${uri.toString().hashCode()}.$ext")
        val subtitleText = subtitleBytes.toString(Charsets.UTF_8)
        cacheFile.writeText(subtitleText)
        return Triple(cacheFile, subtitleText, ext)
    }

    fun applyLocalSubtitle(uri: Uri, media: Media?) {
        val mpv = getMpv() ?: return
        try {
            val finalMimeType = resolveLocalSubtitleMimeType(uri)
            val subtitleBytes = readSubtitleBytesFromUri(uri)
            if (subtitleBytes == null) {
                snackString("Failed to read subtitle file", activity)
                return
            }

            val (cacheFile, rawText, ext) = createLocalSubtitleCacheFile(uri, finalMimeType, subtitleBytes)
            val stableId = "local_sub_${uri.toString().hashCode()}"
            val fileName = uri.lastPathSegment ?: "Custom"
            val label = "Local: $fileName"

            currentActiveSubFile = cacheFile
            currentActiveSubRawContent = rawText
            currentActiveSubFormat = ext.uppercase(Locale.ROOT)
            currentActiveSubLang = "und"
            currentActiveSubMimeType = finalMimeType
            activeSubtitleDisplayName = "[Local] $fileName"
            activeSubtitleId = stableId

            saveLocalSubtitlePreference(media, fileName, uri)

            pendingTrackId = stableId
            pendingSubtitleLabel = label

            PrefManager.setVal(PrefName.Subtitles, true)
            mpv.addAndSelectExternalSubtitle(
                url = cacheFile.absolutePath,
                label = label,
                language = "und"
            )
            setupSubFormatting()
            snackString("Subtitle loaded: $fileName", activity)
        } catch (e: Exception) {
            snackString("Failed to load subtitle: ${e.message}", activity)
        }
    }

    fun selectSubtitleTrack(trackId: Int) {
        val mpv = getMpv() ?: return
        mpv.selectSubtitleTrack(trackId)
        val isDisabled = trackId == -1
        PrefManager.setVal(PrefName.Subtitles, !isDisabled)
        mpv.setSubVisibility(!isDisabled)
        if (!isDisabled) {
            setupSubFormatting()
        }
    }

    fun selectSubtitleByLabel(label: String?): Boolean {
        val mpv = getMpv() ?: return false
        val tracks = mpv.subtitleTracks.value
        val match = findMatchingTrack(tracks, label) ?: return false
        selectSubtitleTrack(match.id)
        snackString("Subtitle loaded: ${match.name}", activity)
        return true
    }

    private fun findMatchingTrack(tracks: List<SubtitleTrack>, label: String?): SubtitleTrack? {
        if (label.isNullOrBlank()) return null
        return tracks.firstOrNull { track ->
            track.name.equals(label, ignoreCase = true) ||
                track.language.equals(label, ignoreCase = true) ||
                isTargetSubtitleMatch(track.name, label) ||
                (!track.language.isNullOrBlank() && isTargetSubtitleMatch(track.language, label))
        }
    }

    fun checkTracksForPendingSubtitles(tracks: List<SubtitleTrack>) {
        if (!PrefManager.getVal<Boolean>(PrefName.Subtitles)) return
        val userLabel = pendingSubtitleLabel
        val pendingLabel = userLabel ?: initialSubtitleLabel
        if (pendingLabel.isNullOrBlank()) return

        val match = findMatchingTrack(tracks, pendingLabel) ?: return
        loadLocalFonts()
        pendingTrackId = null
        pendingSubtitleLabel = null
        initialSubtitleLabel = null
        selectSubtitleTrack(match.id)
        if (userLabel != null) {
            snackString("Subtitle loaded: ${match.name}", activity)
        }
    }

    fun release() {
        serverSubJob?.cancel()
        serverSubJob = null
        loadedFontNames.clear()
    }
}
