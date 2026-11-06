package ani.dantotsu.connections.anilist

import android.content.Context
import ani.dantotsu.client
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline / rate-limit fallback client that queries the static JSON index hosted on GitHub's raw CDN:
 * https://github.com/itsmechinmoy/dartotsu-media-index
 *
 * Provides media metadata lookup and title search when AniList GraphQL is unreachable or rate-limited.
 */
object MediaIndexClient {
    private const val BASE_URL =
        "https://raw.githubusercontent.com/itsmechinmoy/dartotsu-media-index/main"
    private const val SHARD_SIZE = 5000
    private const val MAX_CACHE_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private const val CACHE_TIME_MINUTES = (MAX_CACHE_AGE_MS / 60000L).toInt()

    private val shardCache = ConcurrentHashMap<String, List<JsonObject>>()
    private val titleCache = ConcurrentHashMap<String, List<JsonObject>>()

    private fun getCacheDir(context: Context): File {
        return File(context.cacheDir, "media_index").apply { if (!exists()) mkdirs() }
    }

    /**
     * Fetch media metadata by AniList ID from the static index shards.
     */
    suspend fun getMedia(id: Int, isAnime: Boolean? = null, context: Context? = currContext()): Media? =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext null
            if (isAnime != null) {
                findInShard(ctx, id, if (isAnime) "anime" else "manga")
            } else {
                // Unknown type: probe anime shard first, then manga
                findInShard(ctx, id, "anime") ?: findInShard(ctx, id, "manga")
            }
        }

    /**
     * Fetch multiple media entries by IDs in batch.
     */
    suspend fun getMediaList(ids: List<Int>, context: Context? = currContext()): List<Media> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            val ctx = context ?: return@withContext emptyList()

            val result = mutableListOf<Media>()
            val idsByShard = ids.groupBy { it / SHARD_SIZE }

            for ((shardIdx, shardIds) in idsByShard) {
                val shardStr = String.format("%04d", shardIdx)
                val targetIds = shardIds.toSet()

                // Check anime
                val animeRecords = loadShard(ctx, "anime", shardStr)
                result.addAll(extractMatchingRecords(animeRecords, targetIds, isAnime = true))

                // Check manga for remaining
                val foundIds = result.map { it.id }.toSet()
                val remainingIds = targetIds.filterNot { foundIds.contains(it) }.toSet()
                if (remainingIds.isNotEmpty()) {
                    val mangaRecords = loadShard(ctx, "manga", shardStr)
                    result.addAll(extractMatchingRecords(mangaRecords, remainingIds, isAnime = false))
                }
            }
            result
        }

    private fun extractMatchingRecords(
        records: List<JsonObject>,
        targetIds: Set<Int>,
        isAnime: Boolean
    ): List<Media> {
        val result = mutableListOf<Media>()
        for (rec in records) {
            val rId = rec["id"]?.jsonPrimitive?.intOrNull
            if (rId != null && targetIds.contains(rId)) {
                result.add(parseMedia(rec, isAnime = isAnime))
            }
        }
        return result
    }

    /**
     * Offline title search against the flat search index.
     */
    suspend fun searchByTitle(
        query: String,
        isAnime: Boolean,
        limit: Int = 30,
        context: Context? = currContext()
    ): List<Media> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val ctx = context ?: return@withContext emptyList()
        val sub = if (isAnime) "anime" else "manga"

        val titles = loadTitles(ctx, sub)
        val lower = query.trim().lowercase()

        val matchingIds = titles.asSequence().filter { item ->
            val romaji = item["romaji"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val english = item["english"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val native = item["native"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            romaji.contains(lower) || english.contains(lower) || native.contains(lower)
        }.mapNotNull { it["id"]?.jsonPrimitive?.intOrNull }.take(limit).toList()

        if (matchingIds.isEmpty()) return@withContext emptyList()

        val byShard = matchingIds.groupBy { it / SHARD_SIZE }
        val results = mutableListOf<Media>()

        for ((shardIdx, shardIds) in byShard) {
            val shardStr = String.format("%04d", shardIdx)
            val records = loadShard(ctx, sub, shardStr)
            val idSet = shardIds.toSet()
            results.addAll(extractMatchingRecords(records, idSet, isAnime = isAnime))
        }
        results
    }

    private suspend fun findInShard(context: Context, id: Int, sub: String): Media? {
        val shardIndex = String.format("%04d", id / SHARD_SIZE)
        val records = loadShard(context, sub, shardIndex)
        val json = records.firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == id } ?: return null
        return parseMedia(json, isAnime = sub == "anime")
    }

    private suspend fun loadShard(context: Context, sub: String, shardIndex: String): List<JsonObject> {
        val key = "$sub/$shardIndex"
        shardCache[key]?.let { return it }

        val cacheFile = File(getCacheDir(context), "$sub-$shardIndex.json")
        val isExpired = !cacheFile.exists() ||
                System.currentTimeMillis() - cacheFile.lastModified() > MAX_CACHE_AGE_MS

        if (!isExpired) {
            try {
                val cached = parseJsonArray(cacheFile.readText())
                if (cached.isNotEmpty()) {
                    shardCache[key] = cached
                    return cached
                }
            } catch (e: Exception) {
                Logger.log("MediaIndex loadShard cache error: ${e.message}")
            }
        }

        // Fetch from CDN
        try {
            val url = "$BASE_URL/data/$sub/$shardIndex.json"
            val res = client.get(url, cacheTime = CACHE_TIME_MINUTES)
            if (res.code == 200 && res.text.startsWith("[")) {
                cacheFile.writeText(res.text)
                val parsed = parseJsonArray(res.text)
                shardCache[key] = parsed
                return parsed
            }
        } catch (e: Exception) {
            Logger.log("MediaIndex CDN fetch failed for $key: ${e.message}")
        }

        // Fallback to expired cache file if available
        if (cacheFile.exists()) {
            try {
                val cached = parseJsonArray(cacheFile.readText())
                shardCache[key] = cached
                return cached
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    private suspend fun loadTitles(context: Context, sub: String): List<JsonObject> {
        titleCache[sub]?.let { return it }

        val cacheFile = File(getCacheDir(context), "${sub}_titles.json")
        val isExpired = !cacheFile.exists() ||
                System.currentTimeMillis() - cacheFile.lastModified() > MAX_CACHE_AGE_MS

        if (!isExpired) {
            try {
                val cached = parseJsonArray(cacheFile.readText())
                if (cached.isNotEmpty()) {
                    titleCache[sub] = cached
                    return cached
                }
            } catch (e: Exception) {
                Logger.log("MediaIndex loadTitles cache error: ${e.message}")
            }
        }

        // Fetch from CDN
        try {
            val url = "$BASE_URL/data/${sub}_titles.json"
            val res = client.get(url, cacheTime = CACHE_TIME_MINUTES)
            if (res.code == 200 && res.text.startsWith("[")) {
                cacheFile.writeText(res.text)
                val parsed = parseJsonArray(res.text)
                titleCache[sub] = parsed
                return parsed
            }
        } catch (e: Exception) {
            Logger.log("MediaIndex CDN fetch titles failed for $sub: ${e.message}")
        }

        if (cacheFile.exists()) {
            try {
                val cached = parseJsonArray(cacheFile.readText())
                titleCache[sub] = cached
                return cached
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    private fun parseJsonArray(raw: String): List<JsonObject> {
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrDefault(emptyList())
    }

    private fun parseFuzzyDate(obj: JsonObject?): FuzzyDate? {
        if (obj == null) return null
        return FuzzyDate(
            obj["year"]?.jsonPrimitive?.intOrNull,
            obj["month"]?.jsonPrimitive?.intOrNull,
            obj["day"]?.jsonPrimitive?.intOrNull
        )
    }

    private fun parseMedia(json: JsonObject, isAnime: Boolean): Media {
        val id = json["id"]?.jsonPrimitive?.intOrNull ?: 0
        val titleObj = json["title"]?.jsonObject
        val coverObj = json["coverImage"]?.jsonObject
        val startObj = json["startDate"]?.jsonObject
        val endObj = json["endDate"]?.jsonObject
        val airingObj = json["nextAiringEpisode"]?.jsonObject

        val english = titleObj?.get("english")?.jsonPrimitive?.contentOrNull
        val romaji = titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull.orEmpty()
        val userPreferred = titleObj?.get("userPreferred")?.jsonPrimitive?.contentOrNull
            ?: romaji.ifEmpty { english.orEmpty() }

        val cover = coverObj?.get("extraLarge")?.jsonPrimitive?.contentOrNull
            ?: coverObj?.get("large")?.jsonPrimitive?.contentOrNull

        val genresList = json["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        val episodes = json["episodes"]?.jsonPrimitive?.intOrNull
        val chapters = json["chapters"]?.jsonPrimitive?.intOrNull
        val nextEp = airingObj?.get("episode")?.jsonPrimitive?.intOrNull

        return Media(
            id = id,
            idMAL = json["idMal"]?.jsonPrimitive?.intOrNull,
            name = english,
            nameRomaji = romaji,
            userPreferredName = userPreferred,
            cover = cover,
            banner = json["bannerImage"]?.jsonPrimitive?.contentOrNull,
            status = json["status"]?.jsonPrimitive?.contentOrNull,
            format = json["format"]?.jsonPrimitive?.contentOrNull,
            isAdult = json["isAdult"]?.jsonPrimitive?.booleanOrNull ?: false,
            meanScore = json["meanScore"]?.jsonPrimitive?.intOrNull,
            popularity = json["popularity"]?.jsonPrimitive?.intOrNull,
            favourites = json["favourites"]?.jsonPrimitive?.intOrNull,
            countryOfOrigin = json["countryOfOrigin"]?.jsonPrimitive?.contentOrNull,
            genres = ArrayList(genresList),
            startDate = parseFuzzyDate(startObj),
            endDate = parseFuzzyDate(endObj),
            anime = if (isAnime) Anime(
                totalEpisodes = episodes,
                nextAiringEpisode = nextEp?.minus(1)
            ) else null,
            manga = if (!isAnime) Manga(
                totalChapters = chapters
            ) else null,
            shareLink = "https://anilist.co/${if (isAnime) "anime" else "manga"}/$id"
        )
    }
}
