package ani.dantotsu.parsers

import android.content.Context
import ani.dantotsu.FileUrl
import ani.dantotsu.currContext
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.media.manga.ImageData
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.NO_HOSTER_LIST
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.copyFrom
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.anime.getPreferenceKey
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.copyFrom
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.core.util.lang.awaitSingle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class DynamicAnimeParser(extension: AnimeExtension.Installed) : AnimeParser() {
    val extension: AnimeExtension.Installed
    var sourceLanguage = 0
        set(value) {
            field = if (extension.sources.isNotEmpty()) {
                value.coerceIn(0, extension.sources.size - 1)
            } else {
                0
            }
        }

    init {
        this.extension = extension
    }

    override val name = extension.name
    override val saveName = extension.name
    override val hostUrl =
        (extension.sources.firstOrNull() as? AnimeHttpSource)?.baseUrl ?: extension.sources.firstOrNull()?.name ?: ""
    override val isNSFW = extension.isNsfw
    override val icon = extension.icon

    private var userSelectDub: Boolean? = null

    override var selectDub: Boolean
        get() = userSelectDub ?: getDub()
        set(value) {
            userSelectDub = value
            setDub(value)
        }

    private val isDubAvailableCache = mutableMapOf<Int, Boolean>()

    private fun getDub(): Boolean {
        val configurableSource = extension.sources.getOrNull(sourceLanguage) as? ConfigurableAnimeSource
            ?: return false
        currContext()?.let { context ->
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            sharedPreferences.all.filterValues { MediaNameAdapter.getSubDub(it.toString()) != MediaNameAdapter.SubDubType.NULL }
                .forEach { value ->
                    return when (MediaNameAdapter.getSubDub(value.value.toString())) {
                        MediaNameAdapter.SubDubType.SUB -> false
                        MediaNameAdapter.SubDubType.DUB -> true
                        MediaNameAdapter.SubDubType.NULL -> false
                    }
                }
        }
        return false
    }

    // Sources that publish dubs as separate entries are handled by remapping which
    // entry backs the title; ones that keep every dub inside a single entry can only
    // be steered through their own preference. setSubDub is anchored, so only whole
    // "sub"/"dub" values match and every other preference is left alone.
    private fun setDub(setDub: Boolean) {
        val configurableSource = extension.sources.getOrNull(sourceLanguage) as? ConfigurableAnimeSource
            ?: return
        val context = currContext() ?: return
        val target = if (setDub) MediaNameAdapter.SubDubType.DUB else MediaNameAdapter.SubDubType.SUB
        val preferences = context.getSharedPreferences(
            configurableSource.getPreferenceKey(),
            Context.MODE_PRIVATE
        )
        isDubAvailableCache.clear()
        preferences.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val replacement = MediaNameAdapter.setSubDub(value, target) ?: return@forEach
            if (replacement == value) return@forEach
            preferences.edit().putString(key, replacement).apply()
            Logger.log("setDub: $key -> $replacement")
        }
    }

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean {
        val targetLang = (sourceLang ?: sourceLanguage).coerceAtLeast(0)
        isDubAvailableCache[targetLang]?.let { return it }
        val configurableSource = extension.sources.getOrNull(targetLang) as? ConfigurableAnimeSource
            ?: return false
        currContext()?.let { context ->
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            val available = sharedPreferences.all.any { (_, value) ->
                MediaNameAdapter.setSubDub(
                    value.toString(),
                    MediaNameAdapter.SubDubType.NULL
                ) != null
            }
            isDubAvailableCache[targetLang] = available
            return available
        }
        return false
    }


    private suspend fun fetchSeasonEpisodes(
        source: AnimeSource,
        sAnime: SAnime,
        seasons: List<SAnime>?
    ): List<SEpisode> {
        if (seasons.isNullOrEmpty()) {
            return source.getEpisodeListCompat(sAnime)
        }
        val semaphore = Semaphore(3)
        val allEpisodes = coroutineScope {
            seasons.map { season ->
                async {
                    semaphore.withPermit {
                        val seasonAnime = runCatching {
                            if (source is AnimeHttpSource) source.getAnimeDetails(season) else season
                        }.getOrDefault(season)
                        val seasonEpisodes = runCatching {
                            source.getEpisodeListCompat(seasonAnime)
                        }.getOrDefault(emptyList())
                        seasonEpisodes.forEach { ep ->
                            if (ep.scanlator.isNullOrBlank()) {
                                ep.scanlator = seasonAnime.title.ifBlank { null } ?: season.title
                            }
                        }
                        seasonEpisodes
                    }
                }
            }.awaitAll().flatten()
        }
        return if (allEpisodes.isEmpty()) {
            source.getEpisodeListCompat(sAnime)
        } else {
            allEpisodes
        }
    }

    private fun preprocessLoadedEpisodes(episodes: List<SEpisode>) {
        episodes.forEach { ep ->
            val normalizedName = ep.name.replace('\\', '/')
            if (normalizedName.contains('/')) {
                val parts = normalizedName.split('/').filter { it.isNotBlank() }
                if (parts.size > 1) {
                    if (ep.scanlator.isNullOrBlank()) {
                        ep.scanlator = parts.dropLast(1).joinToString(" / ")
                    }
                    ep.name = parts.last()
                }
            }
            val detected = MediaNameAdapter.findEpisodeNumber(ep.name)
                ?: MediaNameAdapter.findEpisodeNumber(ep.url)
            if (detected != null && (ep.episode_number <= 0f || ep.episode_number == -1f)) {
                ep.episode_number = detected
            }
        }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = withContext(Dispatchers.IO) {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? AnimeSource
            ?: return@withContext emptyList()
        try {
            val networkAnime = runCatching {
                source.getAnimeDetails(sAnime)
            }.getOrNull()
            if (networkAnime != null) {
                runCatching {
                    sAnime.copyFrom(networkAnime)
                }
            }
            val seasons = runCatching {
                source.getSeasonListCompat(sAnime)
            }.getOrNull()

            val res = fetchSeasonEpisodes(source, sAnime, seasons)
            if (res.isEmpty()) return@withContext emptyList()

            preprocessLoadedEpisodes(res)

            val sortedEpisodes = sortLoadedEpisodes(res)
            return@withContext sortedEpisodes.map { sEpisodeToEpisode(it) }
        } catch (e: Exception) {
            Logger.log("Exception: $e")
        }
        return@withContext emptyList()
    }

    private fun episodesAreIncrementing(episodes: List<SEpisode>): Boolean {
        val sortedEpisodes = episodes.sortedBy { it.episode_number }
        val takenNumbers = mutableListOf<Float>()
        sortedEpisodes.forEach {
            if (it.episode_number !in takenNumbers) {
                takenNumbers.add(it.episode_number)
            } else {
                return false
            }
        }
        return true
    }
    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? AnimeSource ?: return@withContext emptyList()

        return@withContext try {
            val videos = getVideoList(source, sEpisode)

            videos.map { videoToVideoServer(it) }
        } catch (e: Exception) {
            Logger.log("Exception occurred: ${e.message}")
            emptyList()
        }
    }
    private fun getActiveHostersToFetch(source: AnimeSource, hosters: List<Hoster>): List<Hoster> {
        val sortedHosters = runCatching {
            if (source is AnimeHttpSource) source.run { hosters.sortHosters() } else hosters
        }.getOrElse { hosters }

        val activeHosters = sortedHosters.filterNot { it.lazy }
        return if (activeHosters.isEmpty() && sortedHosters.isNotEmpty()) {
            listOf(sortedHosters.first())
        } else {
            activeHosters
        }
    }

    private suspend fun resolveDirectVideos(source: AnimeSource, directVideos: List<Video>): List<Video> {
        if (directVideos.isEmpty()) return emptyList()
        return coroutineScope {
            directVideos.map {
                async(Dispatchers.IO) {
                    resolveVideo(source, it)
                }
            }.awaitAll()
        }
    }

    private fun videoIdentifier(video: Video): String {
        return if (video.videoUrl.isNotBlank() && video.videoUrl != "null") video.videoUrl
        else if (video.url.isNotBlank() && video.url != "null") video.url
        else video.videoTitle
    }

    private fun isValidVideo(video: Video): Boolean {
        val hasVideoUrl = video.videoUrl.isNotBlank() && video.videoUrl != "null"
        val hasUrl = video.url.isNotBlank() && video.url != "null"
        return hasVideoUrl || hasUrl
    }

    private fun filterAndDeduplicateVideos(videos: List<Video>): List<Video> {
        return videos
            .distinctBy { videoIdentifier(it) }
            .filter { isValidVideo(it) }
    }

    suspend fun getVideoList(
        source: AnimeSource,
        episode: SEpisode
    ): List<Video> = withContext(Dispatchers.IO) {
        val hosters = runCatching {
            source.getHosterList(episode)
        }.getOrElse { emptyList() }

        // If hosters exist (lib 16), don't call deprecated getVideoList(episode) which throws
        val directVideos = if (hosters.isEmpty()) {
            runCatching {
                source.getVideoList(episode)
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val hostersToFetch = getActiveHostersToFetch(source, hosters)
        val hosterVideos = fetchHosterVideos(source, hostersToFetch)
        val resolvedDirect = resolveDirectVideos(source, directVideos)
        val allVideos = filterAndDeduplicateVideos(resolvedDirect + hosterVideos)

        return@withContext runCatching {
            if (source is AnimeHttpSource) source.run { allVideos.sortVideos() } else allVideos
        }.getOrElse { allVideos }
    }

    private fun isFullyResolved(video: Video?): Boolean {
        if (video == null) return false
        val url = video.videoUrl
        return url.isNotBlank() && url != "null"
    }

    private suspend fun tryModernResolve(source: AnimeSource, video: Video): Video? {
        val resolved = runCatching {
            if (source is AnimeHttpSource) source.resolveVideo(video) else null
        }.getOrNull()
        return if (isFullyResolved(resolved)) resolved else null
    }

    private suspend fun tryLegacyResolve(source: AnimeSource, video: Video): Video? {
        val newUrl = runCatching {
            if (source is AnimeHttpSource) source.getVideoUrl(video) else null
        }.getOrNull()
        return if (!newUrl.isNullOrEmpty() && newUrl != "null") {
            video.copy(videoUrl = newUrl, initialized = true)
        } else {
            null
        }
    }

    private suspend fun resolveVideo(
        source: AnimeSource,
        video: Video
    ): Video = withContext(Dispatchers.IO) {
        if (video.initialized && isFullyResolved(video)) {
            return@withContext video
        }

        val modern = tryModernResolve(source, video)
        if (modern != null) return@withContext modern

        if (!isFullyResolved(video)) {
            val legacy = tryLegacyResolve(source, video)
            if (legacy != null) return@withContext legacy
        }

        return@withContext video
    }


    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        return VideoServerPassthrough(server)
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? AnimeCatalogueSource
            ?: return emptyList()
        return try {
            val res = try {
                source.getSearchAnime(1, query, source.getFilterList())
            } catch (e: UnsupportedOperationException) {
                source.fetchSearchAnime(1, query, source.getFilterList()).awaitSingle()
            } catch (e: NoSuchMethodError) {
                source.fetchSearchAnime(1, query, source.getFilterList()).awaitSingle()
            }

            Logger.log("query: $query")
            convertAnimesPageToShowResponse(res)
        } catch (e: CloudflareBypassException) {
            Logger.log("Exception in search: $e")
            Logger.log(e)
            withContext(Dispatchers.Main) {
                snackString("Failed to bypass Cloudflare")
            }
            emptyList()
        } catch (e: Exception) {
            Logger.log("General exception in search: $e")
            Logger.log(e)
            emptyList()
        }
    }


    private fun convertAnimesPageToShowResponse(animesPage: AnimesPage): List<ShowResponse> {
        return animesPage.animes.map { sAnime ->
            // Extract required fields from sAnime
            val name = sAnime.title
            val link = sAnime.url
            val coverUrl = sAnime.thumbnail_url ?: ""

            // Create a new ShowResponse
            ShowResponse(name, link, coverUrl, sAnime)
        }
    }

    private fun sEpisodeToEpisode(sEpisode: SEpisode): Episode {
        //if the float episode number is a whole number, convert it to an int
        val episodeNumberInt =
            if (sEpisode.episode_number % 1 == 0f) {
                sEpisode.episode_number.toInt()
            } else {
                sEpisode.episode_number
            }
        return Episode(
            if (episodeNumberInt.toInt() != -1) {
                if (sEpisode.episode_number % 1 == 0f) {
                    episodeNumberInt.toInt().toString()
                } else {
                    sEpisode.episode_number.toString()
                }
            } else {
                sEpisode.name
            },
            sEpisode.url,
            sEpisode.name,
            null,
            null,
            false,
            null,
            sEpisode
        )
    }

    private fun videoToVideoServer(video: Video): VideoServer {
        val targetUrl = video.videoUrl.takeIf { it.isNotBlank() && it != "null" }
            ?: video.url.takeIf { it.isNotBlank() && it != "null" }
            ?: ""
        val headersMap = video.headers?.toMultimap()?.mapValues { it.value.joinToString() } ?: mapOf()
        return VideoServer(
            video.videoTitle.ifBlank { video.quality },
            FileUrl(targetUrl, headersMap),
            null,
            video
        )
    }

    private suspend fun fetchHosterVideos(source: AnimeSource, hostersToFetch: List<Hoster>): List<Video> {
        if (hostersToFetch.isEmpty()) return emptyList()
        return coroutineScope {
            hostersToFetch.map { hoster ->
                async(Dispatchers.IO) {
                    val videos = when {
                        !hoster.videoList.isNullOrEmpty() -> hoster.videoList
                        else -> runCatching { source.getVideoList(hoster) }.getOrElse { emptyList() }
                    }

                    videos.map { video ->
                        val resolved = resolveVideo(source, video)
                        val named = hoster.hosterName.isNotBlank() && hoster.hosterName != NO_HOSTER_LIST
                        val title = if (named) "${hoster.hosterName} - ${resolved.videoTitle}" else resolved.videoTitle

                        resolved.copy(
                            videoTitle = title,
                            initialized = true,
                            memo = if (named) {
                                JsonObject(resolved.memo + ("hoster" to JsonPrimitive(hoster.hosterName)))
                            } else resolved.memo
                        )
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun sortLoadedEpisodes(res: List<SEpisode>): List<SEpisode> {
        return if (res.all { it.episode_number > 0f }) {
            res.sortedBy { it.episode_number }
        } else if (res[0].episode_number == -1f) {
            val sortedByStringNumber = res.sortedBy {
                val number = it.episode_number.takeIf { n -> n > 0f }
                    ?: MediaNameAdapter.findEpisodeNumber(it.name)
                    ?: MediaNameAdapter.findEpisodeNumber(it.url)
                    ?: Float.MAX_VALUE
                it.episode_number = number
                number
            }
            var incrementingNumber = 1f
            sortedByStringNumber.map {
                if (it.episode_number == Float.MAX_VALUE) {
                    it.episode_number = incrementingNumber++
                }
                it
            }
        } else if (episodesAreIncrementing(res)) {
            res.sortedBy { it.episode_number }
        } else {
            var episodeCounter = 1f
            val seasonGroups = res.groupBy { MediaNameAdapter.findSeasonNumber(it.name) ?: 0 }
            seasonGroups.keys.sortedBy { it }.flatMap { season ->
                seasonGroups[season]?.sortedBy { it.episode_number }?.map { episode ->
                    if (episode.episode_number <= 0f) {
                        val potentialNumber = MediaNameAdapter.findEpisodeNumber(episode.name)
                            ?: MediaNameAdapter.findEpisodeNumber(episode.url)
                        if (potentialNumber != null) {
                            episode.episode_number = potentialNumber
                        } else {
                            episode.episode_number = episodeCounter
                        }
                        episodeCounter++
                    }
                    episode
                } ?: emptyList()
            }
        }
    }

}

class DynamicMangaParser(extension: MangaExtension.Installed) : MangaParser() {
    private val mangaCache by lazy {
        try {
            Injekt.get<MangaCache>()
        } catch (_: Throwable) {
            MangaCache()
        }
    }
    val extension: MangaExtension.Installed
    var sourceLanguage = 0
        set(value) {
            field = if (extension.sources.isNotEmpty()) {
                value.coerceIn(0, extension.sources.size - 1)
            } else {
                0
            }
        }

    init {
        this.extension = extension
    }

    override val name = extension.name
    override val saveName = extension.name
    override val hostUrl =
        (extension.sources.firstOrNull() as? HttpSource)?.baseUrl ?: extension.sources.firstOrNull()?.name ?: ""
    override val isNSFW = extension.isNsfw
    override val icon = extension.icon

    override suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? MangaSource ?: return emptyList()

        return try {
            val (networkManga, res) = try {
                val update = runCatching { source.getMangaUpdate(sManga, emptyList(), fetchDetails = true, fetchChapters = true) }.getOrNull()
                if (update != null && (update.chapters.isNotEmpty() || update.manga.title.isNotBlank())) {
                    Pair(update.manga, update.chapters)
                } else {
                    val details = runCatching { source.getMangaDetails(sManga) }.getOrNull()
                    val chapters = runCatching { source.getChapterList(sManga) }.getOrDefault(emptyList())
                    Pair(details, chapters)
                }
            } catch (e: Exception) {
                Pair(null, emptyList<eu.kanade.tachiyomi.source.model.SChapter>())
            }

            if (networkManga != null) {
                sManga.copyFrom(networkManga)
            }
            val reversedRes = res.reversed()
            val chapterList = reversedRes.map { sChapterToMangaChapter(it) }
            chapterList
        } catch (e: Exception) {
            Logger.log("loadChapters Exception: $e")
            emptyList()
        }
    }


    override suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? HttpSource ?: return emptyList()
        val imageDataList: MutableList<ImageData> = mutableListOf()
        val ret = coroutineScope {
            try {
                Logger.log("source.name " + source.name)
                val res = source.getPageList(sChapter)
                val reIndexedPages =
                    res.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }

                val semaphore = Semaphore(5)
                val deferreds = reIndexedPages.map { page ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val imageUrl = if (page.imageUrl.isNullOrBlank()) {
                                runCatching { source.getImageUrl(page) }.getOrNull() ?: page.imageUrl
                            } else {
                                page.imageUrl
                            }
                            val resolvedPage = if (imageUrl != page.imageUrl) {
                                Page(page.index, page.url, imageUrl, page.uri)
                            } else {
                                page
                            }
                            mangaCache.put(resolvedPage.imageUrl ?: "", ImageData(resolvedPage, source))
                            imageDataList += ImageData(resolvedPage, source)
                            Logger.log("put page: ${resolvedPage.imageUrl}")
                            pageToMangaImage(resolvedPage)
                        }
                    }
                }

                deferreds.awaitAll()

            } catch (e: Exception) {
                Logger.log("loadImages Exception: $e")
                snackString("Failed to load images: $e")
                emptyList()
            }
        }
        return ret
    }

    suspend fun imageList(sChapter: SChapter): List<ImageData> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? HttpSource ?: return emptyList()

        return coroutineScope {
            try {
                Logger.log("source.name " + source.name)
                val res = source.getPageList(sChapter)
                val reIndexedPages =
                    res.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }

                val semaphore = Semaphore(5)
                val deferreds = reIndexedPages.map { page ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val imageUrl = if (page.imageUrl.isNullOrBlank()) {
                                runCatching { source.getImageUrl(page) }.getOrNull() ?: page.imageUrl
                            } else {
                                page.imageUrl
                            }
                            val resolvedPage = if (imageUrl != page.imageUrl) {
                                Page(page.index, page.url, imageUrl, page.uri)
                            } else {
                                page
                            }
                            ImageData(resolvedPage, source)
                        }
                    }
                }

                deferreds.awaitAll()
            } catch (e: Exception) {
                Logger.log("loadImages Exception: $e")
                snackString("Failed to load images: $e")
                emptyList()
            }
        }
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? MangaSource ?: return emptyList()

        return try {
            val res = try {
                source.getSearchManga(1, query, source.getFilterList())
            } catch (e: Throwable) {
                if (source is CatalogueSource) {
                    source.fetchSearchManga(1, query, source.getFilterList()).awaitSingle()
                } else {
                    throw e
                }
            }
            Logger.log("res observable: $res")
            convertMangasPageToShowResponse(res)
        } catch (e: CloudflareBypassException) {
            Logger.log("Exception in search: $e")
            withContext(Dispatchers.Main) {
                snackString("Failed to bypass Cloudflare")
            }
            emptyList()
        } catch (e: Exception) {
            Logger.log("General exception in search: $e")
            emptyList()
        }
    }


    private fun convertMangasPageToShowResponse(mangasPage: MangasPage): List<ShowResponse> {
        return mangasPage.mangas.map { sManga ->
            // Extract required fields from sManga
            val name = sManga.title
            val link = sManga.url
            val coverUrl = sManga.thumbnail_url ?: ""

            // Create a new ShowResponse
            ShowResponse(name, link, coverUrl, sManga)
        }
    }

    private fun pageToMangaImage(page: Page): MangaImage {
        var headersMap = mapOf<String, String>()
        var url = ""

        page.imageUrl?.let {
            val splitUrl = it.split("&")
            url = it

            headersMap = splitUrl.mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx != -1) {
                    try {
                        val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                        val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                        Pair(key, value)
                    } catch (e: UnsupportedEncodingException) {
                        null
                    }
                } else {
                    null
                }
            }.toMap()
        }

        return MangaImage(
            FileUrl(url, headersMap),
            false,
            page
        )
    }


    private fun sChapterToMangaChapter(sChapter: SChapter): MangaChapter {
        return MangaChapter(
            sChapter.name,
            sChapter.url,
            sChapter.name,
            null,
            sChapter.scanlator?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown",
            sChapter,
            sChapter.date_upload
        )
    }
}

class VideoServerPassthrough(private val videoServer: VideoServer) : VideoExtractor() {
    override val server: VideoServer
        get() = videoServer

    override suspend fun extract(): VideoContainer {
        val vidList = listOfNotNull(videoServer.video?.let { aniVideoToSaiVideo(it) })
        val headersMap = videoServer.video?.headers?.toMultimap()?.mapValues { it.value.joinToString() } ?: mapOf()
        val videoUrl = vidList.firstOrNull()?.file?.url ?: ""
        val subList = videoServer.video?.subtitleTracks?.map { trackToSubtitle(it, videoUrl, headersMap) } ?: emptyList()
        val audioList = videoServer.video?.audioTracks ?: emptyList()

        return if (vidList.isNotEmpty()) {
            VideoContainer(vidList, subList, audioList)
        } else {
            throw Exception("No videos found")
        }
    }

    private fun parseFormatFromQuery(query: String?, videoUrl: String): VideoType? {
        if (query == null) return null
        val queryPairs = query.split("&").mapNotNull {
            val idx = it.indexOf("=")
            if (idx != -1) {
                val key = runCatching { java.net.URLDecoder.decode(it.substring(0, idx), "UTF-8") }.getOrNull() ?: ""
                val value = runCatching { java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8") }.getOrNull() ?: ""
                Pair(key, value)
            } else null
        }
        val typeParam = queryPairs.find { it.first.equals("type", ignoreCase = true) }?.second ?: ""
        if (typeParam.isNotBlank()) {
            val f = getVideoType(typeParam)
            if (f != null) return f
        }
        val targetParam = queryPairs.find { it.first == "url" || it.first == "file" }?.second ?: ""
        if (targetParam.isNotBlank()) {
            val f = getVideoType(targetParam)
            if (f != null) return f
        }
        if (videoUrl.contains(".m3u8", ignoreCase = true) || query.contains(".m3u8", ignoreCase = true)) {
            return VideoType.M3U8
        }
        return null
    }

    private fun parseFormatFromStream(urlObj: java.net.URL): VideoType? {
        if (StreamTypeDetector.isLocalMp4(urlObj)) {
            return VideoType.CONTAINER
        }
        if (StreamTypeDetector.isLocalhost(urlObj) && !StreamTypeDetector.isTorrentStream(urlObj)) {
            return if (StreamTypeDetector.isLocalDash(urlObj)) VideoType.DASH else VideoType.M3U8
        }
        return null
    }

    private fun parseFormatFromTitle(aniVideo: Video): VideoType? {
        val titleOrQuality = "${aniVideo.videoTitle} ${aniVideo.quality}"
        return if (titleOrQuality.contains("hls", ignoreCase = true) || titleOrQuality.contains("m3u8", ignoreCase = true)) {
            VideoType.M3U8
        } else if (titleOrQuality.contains("dash", ignoreCase = true) || titleOrQuality.contains("mpd", ignoreCase = true)) {
            VideoType.DASH
        } else {
            null
        }
    }

    private fun determineVideoFormat(videoUrl: String, aniVideo: Video): VideoType {
        try {
            val urlObj = java.net.URL(videoUrl)
            return getVideoType(urlObj.path)
                ?: parseFormatFromQuery(urlObj.query, videoUrl)
                ?: parseFormatFromStream(urlObj)
                ?: parseFormatFromTitle(aniVideo)
                ?: run {
                    Logger.log("Unknown video format: $videoUrl")
                    VideoType.CONTAINER
                }
        } catch (malformed: java.net.MalformedURLException) {
            if (videoUrl.startsWith("magnet:") || videoUrl.endsWith(".torrent")) {
                return VideoType.CONTAINER
            }
            throw malformed
        }
    }

    private fun aniVideoToSaiVideo(aniVideo: Video): ani.dantotsu.parsers.Video {
        // Find the number value from the .quality string
        val number = Regex("""\d+""").find(aniVideo.quality)?.value?.toInt() ?: 0

        // Check for null video URL 
        val videoUrl = aniVideo.videoUrl.takeIf { it.isNotBlank() && it != "null" }
            ?: aniVideo.url.takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Video URL is null")

        val format = determineVideoFormat(videoUrl, aniVideo)

        val headersMap: Map<String, String> =
            aniVideo.headers?.toMultimap()?.mapValues { it.value.joinToString() } ?: mapOf()

        val hosterName = (aniVideo.memo["hoster"] as? JsonPrimitive)?.contentOrNull
        return Video(
            number,
            format,
            FileUrl(videoUrl, headersMap),
            null,
            hosterName,
            parseDrmInfo(aniVideo.internalData),
            preferred = aniVideo.preferred,
            hoster = hosterName
        )
    }

    /**
     * Extract DRM parameters an extension passed through [Video.internalData].
     *
     * Expected shape (all fields optional except licenseUrl):
     * ```json
     * {
     *   "drmScheme": "widevine",
     *   "licenseUrl": "https://example.com/license",
     *   "licenseHeaders": { "Authorization": "Bearer ..." }
     * }
     * ```
     * Returns null for anything absent or malformed, so a clear stream - or an
     * extension using internalData for its own unrelated purposes - is unaffected.
     */
    private fun parseDrmInfo(internalData: String): DrmInfo? {
        if (internalData.isBlank()) return null
        return runCatching {
            val obj = JSONObject(internalData)
            val licenseUrl = obj.optString("licenseUrl")
            if (licenseUrl.isBlank()) return null

            fun headersOf(source: JSONObject?, name: String) =
                source?.optJSONObject(name)?.let { json ->
                    json.keys().asSequence().associateWith { key -> json.optString(key) }
                } ?: mapOf()

            val offline = obj.optJSONObject("offline")?.let { off ->
                val manifest = off.optString("manifestUrl")
                val offlineLicense = off.optString("licenseUrl")
                if (manifest.isBlank() || offlineLicense.isBlank()) {
                    null
                } else {
                    OfflineDrmInfo(
                        manifestUrl = manifest,
                        licenseUrl = offlineLicense,
                        licenseHeaders = headersOf(off, "licenseHeaders"),
                        headers = headersOf(off, "headers"),
                    )
                }
            }

            DrmInfo(
                scheme = obj.optString("drmScheme").ifBlank { "widevine" },
                licenseUrl = licenseUrl,
                licenseHeaders = headersOf(obj, "licenseHeaders"),
                offline = offline,
            )
        }.getOrNull()
    }

    private fun isContainerFormat(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.contains("/mp4") || lower.contains("format=mp4")
    }

    private fun isM3u8Format(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower == "m3u8" || lower.endsWith(".m3u8")) return true
        val patterns = listOf(".m3u8", "/m3u8", "/pl/", "/playlist/", "/hls/", "format=m3u8", "type=m3u8")
        return patterns.any { lower.contains(it) }
    }

    private fun isDashFormat(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".mpd")) return true
        val patterns = listOf(".mpd", "/mpd", "/dash/", "format=mpd")
        return patterns.any { lower.contains(it) }
    }

    private fun getVideoType(fileName: String): VideoType? {
        return when {
            isContainerFormat(fileName) -> VideoType.CONTAINER
            isM3u8Format(fileName) -> VideoType.M3U8
            isDashFormat(fileName) -> VideoType.DASH
            else -> null
        }
    }

    @Suppress("unused")
    private fun headRequest(fileName: String, networkHelper: NetworkHelper): VideoType? {
        return try {
            Logger.log("attempting head request for $fileName")
            val request = Request.Builder()
                .url(fileName)
                .head()
                .build()

            networkHelper.client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")
                val contentDisposition = response.header("Content-Disposition")

                if (contentType != null) {
                    when {
                        contentType.contains("mpegurl", ignoreCase = true) -> VideoType.M3U8
                        contentType.contains("dash", ignoreCase = true) -> VideoType.DASH
                        contentType.contains("mp4", ignoreCase = true) -> VideoType.CONTAINER
                        else -> null
                    }
                } else if (contentDisposition != null) {
                    when {
                        contentDisposition.contains("mpegurl", ignoreCase = true) -> VideoType.M3U8
                        contentDisposition.contains("dash", ignoreCase = true) -> VideoType.DASH
                        contentDisposition.contains("mp4", ignoreCase = true) -> VideoType.CONTAINER
                        else -> null
                    }
                } else {
                    Logger.log("failed head request for $fileName")
                    null
                }

            }
        } catch (e: Exception) {
            Logger.log("Exception in headRequest: $e")
            null
        }

    }

    private fun trackToSubtitle(
        track: Track,
        videoUrl: String = "",
        headers: Map<String, String> = emptyMap()
    ): Subtitle {
        val resolvedUrl = ani.dantotsu.media.anime.player.PlayerSubtitleManager.resolveSubtitleUrl(track.url, videoUrl)
        var type = findSubtitleTypeFromUrl(resolvedUrl)
        if (type == SubtitleType.UNKNOWN) {
            val lower = resolvedUrl.lowercase(Locale.ROOT)
            type = when {
                lower.contains(".ass") || lower.contains(".ssa") || lower.contains("format=ass") -> SubtitleType.ASS
                lower.contains(".vtt") || lower.contains("format=vtt") -> SubtitleType.VTT
                lower.contains(".srt") || lower.contains("format=srt") -> SubtitleType.SRT
                else -> SubtitleType.VTT
            }
        }
        return Subtitle(track.lang, FileUrl(resolvedUrl, headers), type)
    }

    private fun findSubtitleTypeFromUrl(url: String): SubtitleType {
        val normalizedUrl = url.substringBefore('#').substringBefore('?')
        val fromPath = extensionToSubtitleType(normalizedUrl)
        if (fromPath != SubtitleType.UNKNOWN) return fromPath

        val encodedCandidate =
            try {
                val query = URL(url).query ?: return SubtitleType.UNKNOWN
                query
                    .split('&')
                    .asSequence()
                    .mapNotNull { part ->
                        val idx = part.indexOf('=')
                        if (idx == -1) null else URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    }.firstOrNull { value ->
                        extensionToSubtitleType(value) != SubtitleType.UNKNOWN
                    }
            } catch (_: Exception) {
                null
            }

        return extensionToSubtitleType(encodedCandidate ?: "")
    }

    private fun extensionToSubtitleType(value: String): SubtitleType {
        val lower = value.lowercase(Locale.ROOT)
        return when {
            hasExtensionMarker(lower, ".vtt") -> SubtitleType.VTT
            hasExtensionMarker(lower, ".ass", ".ssa") -> SubtitleType.ASS
            hasExtensionMarker(lower, ".srt") -> SubtitleType.SRT
            else -> SubtitleType.UNKNOWN
        }
    }

    private fun hasExtensionMarker(value: String, vararg extensions: String): Boolean {
        val base = value.substringBefore('#').substringBefore('?').substringBefore('&')
        return extensions.any { ext ->
            base.endsWith(ext)
        }
    }
}

private suspend fun AnimeSource.getEpisodeListCompat(anime: SAnime): List<SEpisode> {
    return try {
        getAnimeEpisodeUpdate(anime, emptyList(), fetchDetails = false, fetchEpisodes = true).episodes
    } catch (e: UnsupportedOperationException) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (e: NoSuchMethodError) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (e: AbstractMethodError) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (e: NotImplementedError) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (_: Throwable) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    }
}

private suspend fun AnimeSource.getSeasonListCompat(anime: SAnime): List<SAnime> {
    return try {
        getAnimeSeasonUpdate(anime, emptyList(), fetchDetails = false, fetchSeasons = true).seasons
    } catch (e: UnsupportedOperationException) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (e: NoSuchMethodError) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (e: AbstractMethodError) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (e: NotImplementedError) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (_: Throwable) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    }
}
