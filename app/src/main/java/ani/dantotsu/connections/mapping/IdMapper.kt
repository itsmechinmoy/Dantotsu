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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-service ID mapper for Anime and Manga.
 * Ported from Dartotsu's IdMapper to maintain feature parity.
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

    // Anime mappings
    private val anilistToMalAnime = ConcurrentHashMap<Int, Int>()
    private val malToAnilistAnime = ConcurrentHashMap<Int, Int>()

    // Manga mappings
    private val anilistToMalManga = ConcurrentHashMap<Int, Int>()
    private val malToAnilistManga = ConcurrentHashMap<Int, Int>()
    private val anilistToChaptersManga = ConcurrentHashMap<Int, Int>()
    private val anilistToMuManga = ConcurrentHashMap<Int, Long>()
    private val anilistToMbManga = ConcurrentHashMap<Int, Int>()

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
        val isStale = !file.exists() || (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS)

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
                for (elem in array) {
                    val obj = elem.jsonObject
                    val alId = obj["anilist_id"]?.jsonPrimitive?.intOrNull ?: continue
                    val malId = obj["mal_id"]?.jsonPrimitive?.intOrNull
                    if (malId != null && malId > 0) {
                        anilistToMalAnime[alId] = malId
                        malToAnilistAnime[malId] = alId
                    }
                }
                animeLoaded = true
            } else {
                for (elem in array) {
                    val obj = elem.jsonObject
                    val alId = obj["anilist_id"]?.jsonPrimitive?.intOrNull ?: continue
                    val malId = obj["mal_id"]?.jsonPrimitive?.intOrNull
                    if (malId != null && malId > 0) {
                        anilistToMalManga[alId] = malId
                        malToAnilistManga[malId] = alId
                    }

                    val chapters = obj["total_chapters"]?.jsonPrimitive?.intOrNull
                        ?: obj["total_chapters"]?.jsonPrimitive?.doubleOrNull?.toInt()
                    if (chapters != null && chapters > 0) {
                        anilistToChaptersManga[alId] = chapters
                    }

                    val muId = obj["mangaupdates_id"]?.jsonPrimitive?.longOrNull
                    if (muId != null && muId > 0L) {
                        anilistToMuManga[alId] = muId
                    }

                    val mbId = obj["mangabaka_id"]?.jsonPrimitive?.intOrNull
                    if (mbId != null && mbId > 0) {
                        anilistToMbManga[alId] = mbId
                    }
                }
                mangaLoaded = true
            }
        } catch (e: Exception) {
            Logger.log("IdMapper parse error (${if (anime) "anime" else "manga"}): ${e.message}")
        }
    }

    /**
     * Map AniList ID to MyAnimeList ID.
     */
    suspend fun getMalId(anilistId: Int, isAnime: Boolean, context: Context? = currContext()): Int? {
        ensureLoaded(context, isAnime)
        return if (isAnime) anilistToMalAnime[anilistId] else anilistToMalManga[anilistId]
    }

    /**
     * Map MyAnimeList ID to AniList ID.
     */
    suspend fun getAnilistId(malId: Int, isAnime: Boolean, context: Context? = currContext()): Int? {
        ensureLoaded(context, isAnime)
        return if (isAnime) malToAnilistAnime[malId] else malToAnilistManga[malId]
    }

    /**
     * Synchronous getter for cached manga chapter count from the index.
     */
    fun getCachedChapters(anilistId: Int): Int? {
        return anilistToChaptersManga[anilistId]
    }

    /**
     * Map AniList ID to MangaUpdates ID (numeric).
     */
    fun getMangaUpdatesId(anilistId: Int): Long? {
        return anilistToMuManga[anilistId]
    }

    /**
     * Map AniList ID to MangaBaka ID.
     */
    fun getMangaBakaId(anilistId: Int): Int? {
        return anilistToMbManga[anilistId]
    }
}
