package ani.dantotsu.connections.mapping

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import ani.dantotsu.client
import ani.dantotsu.currContext
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.StringReader
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
    private const val MANGABAKA_API =
        "https://api.mangabaka.org/v1/source/anilist"
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private const val CACHE_TIME_MINUTES = (MAX_AGE_MS / 60000L).toInt()

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

    // Fast chapter lookup cache and in-flight request tracker
    private val mangaChapters = ConcurrentHashMap<Int, Int>()
    private val pendingChapterRequests = ConcurrentHashMap<Int, ArrayList<(Int?) -> Unit>>()
    private val inFlightRequests = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var animeLoaded = false

    @Volatile
    private var mangaLoaded = false

    @Volatile
    private var isWarming = false

    private fun getHttpClient(): OkHttpClient {
        return if (::okHttpClient.isInitialized) okHttpClient else OkHttpClient()
    }

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
                val animeJob = launch { if (!animeLoaded) load(ctx, anime = true) }
                val mangaJob = launch { if (!mangaLoaded) load(ctx, anime = false) }
                animeJob.join()
                mangaJob.join()
            } catch (e: Throwable) {
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

    private suspend fun fetchRemote(url: String, file: File, anime: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "gzip")
                    .build()
                val response = getHttpClient().newCall(req).execute()
                if (response.isSuccessful) {
                    val body = response.body ?: return@withContext false
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.outputStream().use { out ->
                        body.byteStream().use { inp ->
                            inp.copyTo(out)
                        }
                    }
                    if (tmp.exists() && tmp.length() > 0) {
                        if (file.exists()) file.delete()
                        tmp.renameTo(file)
                        parseStreamFile(file, anime)
                        return@withContext true
                    }
                }
                false
            } catch (e: Throwable) {
                Logger.log("IdMapper download failed (${if (anime) "anime" else "manga"}): ${e.message}")
                false
            }
        }

    private suspend fun load(context: Context, anime: Boolean) = withContext(Dispatchers.IO) {
        val file = getFile(context, anime)
        val isStale = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS

        if (isStale) {
            val url = if (anime) ANIME_SOURCE else MANGA_SOURCE
            if (fetchRemote(url, file, anime)) return@withContext
        }

        // Read from cached file
        if (file.exists()) {
            try {
                parseStreamFile(file, anime)
            } catch (e: Throwable) {
                Logger.log("IdMapper read cached file failed: ${e.message}")
            }
        }
    }

    private fun parseStreamFile(file: File, anime: Boolean) {
        try {
            val reader = JsonReader(file.bufferedReader(Charsets.UTF_8))
            try {
                if (anime) parseAnimeStream(reader) else parseMangaStream(reader)
            } finally {
                reader.close()
            }
        } catch (e: Throwable) {
            Logger.log("IdMapper stream parse error (${if (anime) "anime" else "manga"}): ${e.message}")
        }
    }

    /**
     * Parse raw JSON string into index maps. Kept for backwards compatibility and unit testing.
     */
    fun parseAndIndex(raw: String, anime: Boolean) {
        try {
            val reader = JsonReader(StringReader(raw))
            try {
                if (anime) parseAnimeStream(reader) else parseMangaStream(reader)
            } finally {
                reader.close()
            }
        } catch (e: Throwable) {
            Logger.log("IdMapper parse error (${if (anime) "anime" else "manga"}): ${e.message}")
        }
    }

    private fun JsonReader.nextIntSafe(): Int? {
        return when (peek()) {
            JsonToken.NUMBER -> nextInt()
            JsonToken.STRING -> nextString().toIntOrNull()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextLongSafe(): Long? {
        return when (peek()) {
            JsonToken.NUMBER -> nextLong()
            JsonToken.STRING -> nextString().toLongOrNull()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextStringSafe(): String? {
        return when (peek()) {
            JsonToken.STRING -> nextString()
            JsonToken.NUMBER -> nextString()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextChaptersSafe(): Int? {
        return when (peek()) {
            JsonToken.NUMBER -> {
                val d = nextDouble()
                if (d > 0.0) d.toInt() else null
            }
            JsonToken.STRING -> {
                nextString().toDoubleOrNull()?.toInt()
            }
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextImdbSafe(): String? {
        return when (peek()) {
            JsonToken.STRING -> nextString()
            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                val first = if (hasNext() && peek() == JsonToken.STRING) nextString() else null
                while (hasNext()) skipValue()
                endArray()
                first
            }
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextTmdbSafe(): Int? {
        return when (peek()) {
            JsonToken.NUMBER -> nextInt()
            JsonToken.BEGIN_OBJECT -> {
                beginObject()
                var found: Int? = null
                while (hasNext()) {
                    val k = nextName()
                    if ((k == "tv" || k == "movie") && peek() == JsonToken.NUMBER) {
                        val v = nextInt()
                        if (found == null) found = v
                    } else {
                        skipValue()
                    }
                }
                endObject()
                found
            }
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun parseAnimeStream(reader: JsonReader) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var alId = 0
            var malId: Int? = null
            var kitsuId: Int? = null
            var simklId: Int? = null
            var anidbId: Int? = null
            var imdbId: String? = null
            var tmdbId: Int? = null
            var tvdbId: Int? = null
            var apId: String? = null
            var asId: Int? = null
            var lcId: Int? = null

            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "anilist_id" -> alId = reader.nextIntSafe() ?: 0
                    "mal_id" -> malId = reader.nextIntSafe()
                    "kitsu_id" -> kitsuId = reader.nextIntSafe()
                    "simkl_id" -> simklId = reader.nextIntSafe()
                    "anidb_id" -> anidbId = reader.nextIntSafe()
                    "imdb_id" -> imdbId = reader.nextImdbSafe()
                    "themoviedb_id" -> tmdbId = reader.nextTmdbSafe()
                    "tvdb_id" -> tvdbId = reader.nextIntSafe()
                    "anime-planet_id" -> apId = reader.nextStringSafe()
                    "anisearch_id" -> asId = reader.nextIntSafe()
                    "livechart_id" -> lcId = reader.nextIntSafe()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (alId > 0) {
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
        }
        reader.endArray()
        animeLoaded = true
    }

    private fun parseMangaStream(reader: JsonReader) {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var alId = 0
            var malId: Int? = null
            var kitsuId: Int? = null
            var muId: Long? = null
            var muSlug: String? = null
            var mbId: Int? = null
            var apId: String? = null
            var skId: Int? = null
            var chapters: Int? = null

            while (reader.hasNext()) {
                val name = reader.nextName()
                when (name) {
                    "anilist_id" -> alId = reader.nextIntSafe() ?: 0
                    "mal_id" -> malId = reader.nextIntSafe()
                    "kitsu_id" -> kitsuId = reader.nextIntSafe()
                    "mangaupdates_id" -> muId = reader.nextLongSafe()
                    "mangaupdates_slug" -> muSlug = reader.nextStringSafe()
                    "mangabaka_id" -> mbId = reader.nextIntSafe()
                    "anime_planet_id" -> apId = reader.nextStringSafe()
                    "shikimori_id" -> skId = reader.nextIntSafe()
                    "total_chapters" -> chapters = reader.nextChaptersSafe()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (alId > 0) {
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
                if (chapters != null && chapters > 0) {
                    mangaChapters[alId] = chapters
                }
                if (malId != null && malId > 0) malToAnilistManga[malId] = alId
                if (kitsuId != null && kitsuId > 0) kitsuToAnilistManga[kitsuId] = alId
                if (muId != null && muId > 0L) muToAnilist[muId] = alId
                if (mbId != null && mbId > 0) mbToAnilist[mbId] = alId
            }
        }
        reader.endArray()
        mangaLoaded = true
    }

    private fun extractImdb(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.firstOrNull()?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private fun extractTmdb(element: JsonElement): Int? {
        return when (element) {
            is JsonPrimitive -> element.intOrNull
            is JsonObject -> element["tv"]?.jsonPrimitive?.intOrNull ?: element["movie"]?.jsonPrimitive?.intOrNull
            else -> null
        }
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
     * Synchronous getter for cached manga chapter count from the index or MangaBaka cache.
     */
    fun getCachedChapters(anilistId: Int): Int? {
        return mangaChapters[anilistId] ?: mangaById[anilistId]?.totalChapters
    }

    /**
     * Cache manga chapter count manually or after remote resolution.
     */
    fun cacheChapters(anilistId: Int, count: Int) {
        if (count > 0) {
            mangaChapters[anilistId] = count
            mangaById[anilistId]?.let {
                mangaById[anilistId] = it.copy(totalChapters = count)
            }
        }
    }

    /**
     * Live MangaBaka API lookup for an AniList ID when total_chapters is missing from cache.
     * Hits https://api.mangabaka.org/v1/source/anilist/$anilistId.
     */
    suspend fun fetchMangaBakaChapters(anilistId: Int): Int? = withContext(Dispatchers.IO) {
        mangaChapters[anilistId]?.let { return@withContext it }
        try {
            val url = "$MANGABAKA_API/$anilistId"
            val res = client.get(url, cacheTime = CACHE_TIME_MINUTES)
            if (res.code == 200 && res.text.isNotBlank()) {
                val json = Json.parseToJsonElement(res.text).jsonObject
                val seriesArr = json["data"]?.jsonObject?.get("series")?.jsonArray
                val firstSeries = seriesArr?.firstOrNull()?.jsonObject
                val chapElement = firstSeries?.get("total_chapters")
                val chapters = when (chapElement) {
                    is JsonPrimitive -> chapElement.intOrNull
                        ?: chapElement.doubleOrNull?.toInt()
                        ?: chapElement.contentOrNull?.toDoubleOrNull()?.toInt()
                    else -> null
                }
                if (chapters != null && chapters > 0) {
                    cacheChapters(anilistId, chapters)
                    return@withContext chapters
                }
            }
        } catch (e: Exception) {
            Logger.log("IdMapper MangaBaka fetch error: ${e.message}")
        }
        null
    }

    /**
     * Asynchronously resolve total chapters for a manga by AniList ID.
     * Checks in-memory cache first; if not present, fetches from MangaBaka API and notifies callback.
     */
    fun resolveChaptersAsync(anilistId: Int, callback: (Int?) -> Unit) {
        val cached = getCachedChapters(anilistId)
        if (cached != null) {
            callback(cached)
            return
        }
        pendingChapterRequests.compute(anilistId) { _, list ->
            val callbacks = list ?: ArrayList()
            callbacks.add(callback)
            callbacks
        }
        if (inFlightRequests.add(anilistId)) {
            CoroutineScope(Dispatchers.IO).launch {
                val count = fetchMangaBakaChapters(anilistId)
                val callbacks = pendingChapterRequests.remove(anilistId)
                inFlightRequests.remove(anilistId)
                callbacks?.forEach { cb ->
                    try {
                        cb(count)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
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
