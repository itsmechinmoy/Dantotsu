package ani.dantotsu.connections.mapping

import android.content.Context
import ani.dantotsu.client
import ani.dantotsu.currContext
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class AnimeIds(
    val anilistId: Int,
    val malId: Int? = null,
    val kitsuId: Int? = null,
    val simklId: Int? = null,
    val anidbId: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val animePlanetId: String? = null,
    val anisearchId: Int? = null,
    val livechartId: Int? = null
)

data class MangaIds(
    val anilistId: Int,
    val malId: Int? = null,
    val kitsuId: Int? = null,
    val mangaupdatesId: Long? = null,
    val mangaupdatesSlug: String? = null,
    val mangabakaId: Int? = null,
    val animePlanetId: String? = null,
    val shikimoriId: Int? = null,
    val totalChapters: Int? = null
)

/**
 * Universal cross-service ID mapper for Anime and Manga.
 * Ported from Dartotsu with full support for all major tracker and database services:
 * AniList, MyAnimeList, Kitsu, Simkl, AniDB, IMDb, TMDB, TVDB, MangaUpdates, MangaBaka,
 * Anime-Planet, AniSearch, LiveChart, Shikimori.
 *
 * Sources:
 * - Anime: https://github.com/Fribb/anime-lists (anime-list-mini.json)
 * - Manga: https://github.com/itsmechinmoy/dartotsu_manga_mapper (manga-list-mini.json)
 */
object IdMapper {
    private const val ANIME_SOURCE =
        "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json"
    private const val MANGA_SOURCE =
        "https://raw.githubusercontent.com/itsmechinmoy/dartotsu_manga_mapper/main/manga-list-mini.json"
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    // Forward maps: AniList ID -> IDs bundle
    private val animeById = ConcurrentHashMap<Int, AnimeIds>()
    private val mangaById = ConcurrentHashMap<Int, MangaIds>()

    // Reverse maps: Service ID -> AniList ID
    private val malToAnilistAnime = ConcurrentHashMap<Int, Int>()
    private val malToAnilistManga = ConcurrentHashMap<Int, Int>()
    private val kitsuToAnilistAnime = ConcurrentHashMap<Int, Int>()
    private val kitsuToAnilistManga = ConcurrentHashMap<Int, Int>()
    private val simklToAnilist = ConcurrentHashMap<Int, Int>()
    private val anidbToAnilist = ConcurrentHashMap<Int, Int>()
    private val muToAnilist = ConcurrentHashMap<Long, Int>()
    private val mbToAnilist = ConcurrentHashMap<Int, Int>()

    @Volatile
    private var animeLoaded = false

    @Volatile
    private var mangaLoaded = false

    @Volatile
    private var isWarming = false

    private fun getFile(context: Context, anime: Boolean): File {
        val dir = File(context.cacheDir, "id_mapper").apply { if (!exists()) mkdirs() }
        return File(dir, if (anime) "anime-ids.json" else "manga-ids.json")
    }

    /**
     * Non-blocking background warm-up of both mapping indices.
     */
    fun warm(context: Context? = currContext()) {
        val ctx = context ?: return
        if (isWarming || (animeLoaded && mangaLoaded)) return
        isWarming = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!animeLoaded) load(ctx, anime = true)
                if (!mangaLoaded) load(ctx, anime = false)
            } catch (e: Exception) {
                Logger.log("IdMapper warm-up error: ${e.message}")
            } finally {
                isWarming = false
            }
        }
    }

    private suspend fun ensureLoaded(context: Context?, anime: Boolean) {
        val isLoaded = if (anime) animeLoaded else mangaLoaded
        if (!isLoaded) {
            val ctx = context ?: currContext() ?: return
            load(ctx, anime)
        }
    }

    private suspend fun load(context: Context, anime: Boolean) = withContext(Dispatchers.IO) {
        val file = getFile(context, anime)
        val isStale = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS

        if (isStale) {
            try {
                val url = if (anime) ANIME_SOURCE else MANGA_SOURCE
                val res = client.get(url, cacheTime = (MAX_AGE_MS / 60000).toInt())
                if (res.code == 200 && res.text.startsWith("[")) {
                    file.writeText(res.text)
                    parseAndIndex(res.text, anime)
                    return@withContext
                }
            } catch (e: Exception) {
                Logger.log("IdMapper download failed (${if (anime) "anime" else "manga"}): ${e.message}")
            }
        }

        // Read from cached file
        if (file.exists()) {
            try {
                parseAndIndex(file.readText(), anime)
            } catch (e: Exception) {
                Logger.log("IdMapper read cached file failed: ${e.message}")
            }
        }
    }

    private fun parseAndIndex(raw: String, anime: Boolean) {
        try {
            val array = Json.parseToJsonElement(raw).jsonArray
            if (anime) {
                parseAnimeArray(array)
            } else {
                parseMangaArray(array)
            }
        } catch (e: Exception) {
            Logger.log("IdMapper parse error (${if (anime) "anime" else "manga"}): ${e.message}")
        }
    }

    private fun parseAnimeArray(array: JsonArray) {
        for (elem in array) {
            val obj = elem.jsonObject
            val alId = obj["anilist_id"]?.jsonPrimitive?.intOrNull ?: continue
            val malId = obj["mal_id"]?.jsonPrimitive?.intOrNull
            val kitsuId = obj["kitsu_id"]?.jsonPrimitive?.intOrNull
            val simklId = obj["simkl_id"]?.jsonPrimitive?.intOrNull
            val anidbId = obj["anidb_id"]?.jsonPrimitive?.intOrNull
            val imdbId = obj["imdb_id"]?.let { extractImdb(it) }
            val tmdbId = obj["themoviedb_id"]?.let { extractTmdb(it) }
            val tvdbId = obj["tvdb_id"]?.jsonPrimitive?.intOrNull
            val apId = obj["anime-planet_id"]?.jsonPrimitive?.contentOrNull
            val asId = obj["anisearch_id"]?.jsonPrimitive?.intOrNull
            val lcId = obj["livechart_id"]?.jsonPrimitive?.intOrNull

            val ids = AnimeIds(
                anilistId = alId,
                malId = malId,
                kitsuId = kitsuId,
                simklId = simklId,
                anidbId = anidbId,
                imdbId = imdbId,
                tmdbId = tmdbId,
                tvdbId = tvdbId,
                animePlanetId = apId,
                anisearchId = asId,
                livechartId = lcId
            )
            animeById[alId] = ids

            if (malId != null && malId > 0) malToAnilistAnime[malId] = alId
            if (kitsuId != null && kitsuId > 0) kitsuToAnilistAnime[kitsuId] = alId
            if (simklId != null && simklId > 0) simklToAnilist[simklId] = alId
            if (anidbId != null && anidbId > 0) anidbToAnilist[anidbId] = alId
        }
        animeLoaded = true
    }

    private fun parseMangaArray(array: JsonArray) {
        for (elem in array) {
            val obj = elem.jsonObject
            val alId = obj["anilist_id"]?.jsonPrimitive?.intOrNull ?: continue
            val malId = obj["mal_id"]?.jsonPrimitive?.intOrNull
            val kitsuId = obj["kitsu_id"]?.jsonPrimitive?.intOrNull
            val muId = obj["mangaupdates_id"]?.jsonPrimitive?.longOrNull
            val muSlug = obj["mangaupdates_slug"]?.jsonPrimitive?.contentOrNull
            val mbId = obj["mangabaka_id"]?.jsonPrimitive?.intOrNull
            val apId = obj["anime_planet_id"]?.jsonPrimitive?.contentOrNull
            val skId = obj["shikimori_id"]?.jsonPrimitive?.intOrNull
            val chapters = obj["total_chapters"]?.jsonPrimitive?.intOrNull
                ?: obj["total_chapters"]?.jsonPrimitive?.doubleOrNull?.toInt()

            val ids = MangaIds(
                anilistId = alId,
                malId = malId,
                kitsuId = kitsuId,
                mangaupdatesId = muId,
                mangaupdatesSlug = muSlug,
                mangabakaId = mbId,
                animePlanetId = apId,
                shikimoriId = skId,
                totalChapters = chapters
            )
            mangaById[alId] = ids

            if (malId != null && malId > 0) malToAnilistManga[malId] = alId
            if (kitsuId != null && kitsuId > 0) kitsuToAnilistManga[kitsuId] = alId
            if (muId != null && muId > 0L) muToAnilist[muId] = alId
            if (mbId != null && mbId > 0) mbToAnilist[mbId] = alId
        }
        mangaLoaded = true
    }

    private fun extractImdb(element: kotlinx.serialization.json.JsonElement): String? {
        return runCatching {
            element.jsonPrimitive.contentOrNull
        }.getOrNull() ?: runCatching {
            element.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun extractTmdb(element: kotlinx.serialization.json.JsonElement): Int? {
        return runCatching {
            element.jsonPrimitive.intOrNull
        }.getOrNull() ?: runCatching {
            element.jsonObject["tv"]?.jsonPrimitive?.intOrNull
                ?: element.jsonObject["movie"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
    }

    // --- High-level Typed Lookups ---

    /**
     * Map AniList ID to MyAnimeList ID.
     */
    suspend fun getMalId(anilistId: Int, isAnime: Boolean, context: Context? = currContext()): Int? {
        ensureLoaded(context, isAnime)
        return if (isAnime) animeById[anilistId]?.malId else mangaById[anilistId]?.malId
    }

    /**
     * Map MyAnimeList ID to AniList ID.
     */
    suspend fun getAnilistId(malId: Int, isAnime: Boolean, context: Context? = currContext()): Int? {
        ensureLoaded(context, isAnime)
        return if (isAnime) malToAnilistAnime[malId] else malToAnilistManga[malId]
    }

    /**
     * Map AniList ID to Kitsu ID.
     */
    suspend fun getKitsuId(anilistId: Int, isAnime: Boolean, context: Context? = currContext()): Int? {
        ensureLoaded(context, isAnime)
        return if (isAnime) animeById[anilistId]?.kitsuId else mangaById[anilistId]?.kitsuId
    }

    /**
     * Map Kitsu ID to AniList ID.
     */
    suspend fun getAnilistIdFromKitsu(kitsuId: Int, isAnime: Boolean, context: Context? = currContext()): Int? {
        ensureLoaded(context, isAnime)
        return if (isAnime) kitsuToAnilistAnime[kitsuId] else kitsuToAnilistManga[kitsuId]
    }

    /**
     * Map AniList ID to Simkl ID (Anime).
     */
    suspend fun getSimklId(anilistId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = true)
        return animeById[anilistId]?.simklId
    }

    /**
     * Map Simkl ID to AniList ID.
     */
    suspend fun getAnilistIdFromSimkl(simklId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = true)
        return simklToAnilist[simklId]
    }

    /**
     * Map AniList ID to AniDB ID (Anime).
     */
    suspend fun getAnidbId(anilistId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = true)
        return animeById[anilistId]?.anidbId
    }

    /**
     * Map AniList ID to IMDb ID (Anime).
     */
    suspend fun getImdbId(anilistId: Int, context: Context? = currContext()): String? {
        ensureLoaded(context, anime = true)
        return animeById[anilistId]?.imdbId
    }

    /**
     * Map AniList ID to TheMovieDB ID (Anime).
     */
    suspend fun getTmdbId(anilistId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = true)
        return animeById[anilistId]?.tmdbId
    }

    /**
     * Map AniList ID to TheTVDB ID (Anime).
     */
    suspend fun getTvdbId(anilistId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = true)
        return animeById[anilistId]?.tvdbId
    }

    /**
     * Map AniList ID to MangaUpdates ID (numeric).
     */
    suspend fun getMangaUpdatesId(anilistId: Int, context: Context? = currContext()): Long? {
        ensureLoaded(context, anime = false)
        return mangaById[anilistId]?.mangaupdatesId
    }

    /**
     * Map MangaUpdates ID to AniList ID.
     */
    suspend fun getAnilistIdFromMangaUpdates(muId: Long, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = false)
        return muToAnilist[muId]
    }

    /**
     * Map AniList ID to MangaBaka ID.
     */
    suspend fun getMangaBakaId(anilistId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = false)
        return mangaById[anilistId]?.mangabakaId
    }

    /**
     * Map MangaBaka ID to AniList ID.
     */
    suspend fun getAnilistIdFromMangaBaka(mbId: Int, context: Context? = currContext()): Int? {
        ensureLoaded(context, anime = false)
        return mbToAnilist[mbId]
    }

    /**
     * Map AniList ID to Anime-Planet slug.
     */
    suspend fun getAnimePlanetId(anilistId: Int, isAnime: Boolean, context: Context? = currContext()): String? {
        ensureLoaded(context, isAnime)
        return if (isAnime) animeById[anilistId]?.animePlanetId else mangaById[anilistId]?.animePlanetId
    }

    /**
     * Synchronous getter for cached manga chapter count from the index.
     */
    fun getCachedChapters(anilistId: Int): Int? {
        return mangaById[anilistId]?.totalChapters
    }

    /**
     * Get full Anime IDs bundle for an AniList ID.
     */
    suspend fun getAnimeIds(anilistId: Int, context: Context? = currContext()): AnimeIds? {
        ensureLoaded(context, anime = true)
        return animeById[anilistId]
    }

    /**
     * Get full Manga IDs bundle for an AniList ID.
     */
    suspend fun getMangaIds(anilistId: Int, context: Context? = currContext()): MangaIds? {
        ensureLoaded(context, anime = false)
        return mangaById[anilistId]
    }
}
