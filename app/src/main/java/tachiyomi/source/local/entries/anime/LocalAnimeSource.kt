package tachiyomi.source.local.entries.anime

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import rx.Observable
import tachiyomi.core.metadata.tachiyomi.AnimeDetails
import tachiyomi.core.metadata.comicinfo.EpisodeDetails
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.source.local.filter.anime.AnimeOrderBy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class LocalAnimeSource(
    private val context: Context,
) : AnimeCatalogueSource, UnmeteredSource {

    private val json = Json { ignoreUnknownKeys = true }

    private val POPULAR_FILTERS = AnimeFilterList(AnimeOrderBy.Popular())
    private val LATEST_FILTERS = AnimeFilterList(AnimeOrderBy.Latest())

    val cachedVideoUris = ConcurrentHashMap<String, Uri>()

    override val name = "Local anime source"

    override val id: Long = ID

    override val lang = "other"

    override fun toString() = name

    override val supportsLatest = true

    // Browse related
    override suspend fun getPopularAnime(page: Int) = getSearchAnime(page, "", POPULAR_FILTERS)

    override suspend fun getLatestUpdates(page: Int) = getSearchAnime(page, "", LATEST_FILTERS)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withIOContext {
        val baseDir = getBaseDirectory(context)
            ?: return@withIOContext AnimesPage(emptyList(), false)

        val lastModifiedLimit = if (filters === LATEST_FILTERS) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        
        val localDir = baseDir.findFile("local") ?: baseDir
        val localAnimeDir = localDir.findFile("anime") ?: localDir

        var animeDirs = localAnimeDir.listFiles()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is AnimeOrderBy.Popular -> {
                    animeDirs = if (filter.state!!.ascending) {
                        animeDirs.sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        )
                    } else {
                        animeDirs.sortedWith(
                            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        )
                    }
                }
                is AnimeOrderBy.Latest -> {
                    animeDirs = if (filter.state!!.ascending) {
                        animeDirs.sortedBy { it.lastModified() }
                    } else {
                        animeDirs.sortedByDescending { it.lastModified() }
                    }
                }
                else -> { /* Do nothing */ }
            }
        }

        val animes = animeDirs.map { animeDir ->
            SAnime.create().apply {
                title = animeDir.name.orEmpty()
                url = animeDir.name.orEmpty()

                val coverFile = findCoverFile(animeDir)
                if (coverFile != null) {
                    thumbnail_url = coverFile.uri.toString()
                } else {
                    val firstVideo = animeDir.listFiles().firstOrNull { !it.name.orEmpty().startsWith('.') && isSupportedVideo(it) }
                    if (firstVideo != null) {
                        thumbnail_url = firstVideo.uri.toString()
                    }
                }
            }
        }

        AnimesPage(animes, false)
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularAnime"))
    override fun fetchPopularAnime(page: Int) = fetchSearchAnime(page, "", POPULAR_FILTERS)

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int) = fetchSearchAnime(page, "", LATEST_FILTERS)

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Observable<AnimesPage> {
        return runBlocking {
            Observable.just(getSearchAnime(page, query, filters))
        }
    }

    // Anime details related
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withIOContext {
        val animeDir = getAnimeDir(anime.url, context) ?: return@withIOContext anime

        val coverFile = findCoverFile(animeDir)
        if (coverFile != null) {
            anime.thumbnail_url = coverFile.uri.toString()
        } else {
            val firstVideo = animeDir.listFiles().firstOrNull { !it.name.orEmpty().startsWith('.') && isSupportedVideo(it) }
            if (firstVideo != null) {
                anime.thumbnail_url = firstVideo.uri.toString()
            }
        }

        // details.json
        animeDir.findFile("details.json")?.let { detailsFile ->
            try {
                val inputStream = context.contentResolver.openInputStream(detailsFile.uri)
                val text = inputStream?.bufferedReader()?.readText() ?: return@let
                inputStream.close()
                val details = json.decodeFromString<AnimeDetails>(text)
                details.title?.let { anime.title = it }
                details.author?.let { anime.author = it }
                details.artist?.let { anime.artist = it }
                details.description?.let { anime.description = it }
                details.genre?.let { anime.genre = it.joinToString() }
                details.status?.let { anime.status = it }
            } catch (e: Exception) {
                Logger.log("Error parsing details.json for ${anime.url}: ${e.message}")
            }
        }

        return@withIOContext anime
    }

    // Episodes
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withIOContext {
        val animeDir = getAnimeDir(anime.url, context) ?: return@withIOContext emptyList()

        // ep.json
        val episodesData = try {
            animeDir.findFile("episodes.json")?.let { episodesFile ->
                val inputStream = context.contentResolver.openInputStream(episodesFile.uri)
                val text = inputStream?.bufferedReader()?.readText() ?: return@let null
                inputStream.close()
                json.decodeFromString<List<EpisodeDetails>>(text)
            }
        } catch (e: Exception) {
            Logger.log("Error parsing episodes.json for ${anime.url}: ${e.message}")
            null
        }

        val episodes = animeDir.listFiles()
            .filter { !it.name.orEmpty().startsWith('.') && isSupportedVideo(it) }
            .map { episodeFile ->
                SEpisode.create().apply {
                    url = "${anime.url}/${episodeFile.name.orEmpty()}"
                    cachedVideoUris[url] = episodeFile.uri
                    name = episodeFile.name.orEmpty().substringBeforeLast(".")
                    date_upload = episodeFile.lastModified()

                    // Parse ep number
                    val parsedNumber = MediaNameAdapter.findEpisodeNumber(this.name)
                        ?: Regex("""(\d+(?:\.\d+)?)""").find(this.name)?.value?.toFloatOrNull()
                    episode_number = parsedNumber ?: -1f

                    episodesData?.let { dataList ->
                        dataList.firstOrNull {
                            it.episode_number.equalsTo(episode_number)
                        }?.also { data ->
                            data.name?.also { name = it }
                            data.date_upload?.also { date_upload = parseDate(it) }
                            scanlator = data.scanlator
                        }
                    }
                }
            }
            .sortedWith { e1, e2 ->
                val e = e2.episode_number.compareTo(e1.episode_number)
                if (e == 0) e2.name.compareTo(e1.name, ignoreCase = true) else e
            }

        episodes
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = getSeasonList(anime)


    // Filters
    override fun getFilterList() = AnimeFilterList(AnimeOrderBy.Popular())

    // Unused stuff
    override suspend fun getVideoList(episode: SEpisode) =
        throw UnsupportedOperationException("Unused")

    fun getVideoUri(episode: SEpisode): Uri? {
        val parts = episode.url.split("/", limit = 2)
        if (parts.size < 2) return null
        val animeDir = getAnimeDir(parts[0], context) ?: return null
        val videoFile = animeDir.findFile(parts[1])
        return videoFile?.uri
    }

    private fun findCoverFile(dir: DocumentFile): DocumentFile? {
        COVER_NAMES.forEach { name ->
            dir.findFile(name)?.let { return it }
        }
        return null
    }

    private fun isSupportedVideo(file: DocumentFile): Boolean {
        val name = file.name.orEmpty().lowercase()
        return SUPPORTED_VIDEO_EXTENSIONS.any { name.endsWith(".$it") }
    }

    private fun parseDate(isoDate: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                .parse(isoDate)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < 0.0001
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-anime/"

        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png", "cover.webp"
        )
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)
        private val SUPPORTED_VIDEO_EXTENSIONS = listOf("avi", "flv", "mkv", "mov", "mp4", "webm", "wmv")

        fun getBaseDirectory(context: Context): DocumentFile? {
            val uriString = ani.dantotsu.settings.saving.PrefManager
                .getVal<String>(ani.dantotsu.settings.saving.PrefName.LocalDir)
            if (uriString.isBlank()) return null
            val uri = Uri.parse(uriString)
            return DocumentFile.fromTreeUri(context, uri)
        }

        private fun getAnimeDir(animeUrl: String, context: Context): DocumentFile? {
            val baseDir = getBaseDirectory(context) ?: return null
            val localDir = baseDir.findFile("local") ?: baseDir
            val localAnimeDir = localDir.findFile("anime") ?: localDir
            return localAnimeDir.findFile(animeUrl)?.takeIf { it.isDirectory }
                ?: baseDir.findFile(animeUrl)?.takeIf { it.isDirectory }
        }
    }
}

fun Anime.isLocal(): Boolean = source == LocalAnimeSource.ID

fun AnimeSource.isLocal(): Boolean = id == LocalAnimeSource.ID
