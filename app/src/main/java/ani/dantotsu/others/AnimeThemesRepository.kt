package ani.dantotsu.others

import ani.dantotsu.media.Media
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AnimeThemeVideo(
    val link: String,
    val audioUrl: String? = null,
    val resolution: Int? = null,
    val source: String? = null,
    val overlap: String? = null,
    val uncensored: Boolean = false,
    val subbed: Boolean = false,
    val lyrics: Boolean = false,
    val tags: List<String> = emptyList()
) {
    val label: String
        get() = buildList {
            resolution?.let { add("${it}p") }
            if (!source.isNullOrBlank()) add(source)
            if (uncensored) add("NC")
            if (subbed) add("Subbed")
            if (lyrics) add("Lyrics")
            tags.forEach { if (it !in this) add(it) }
        }.joinToString(" · ").ifBlank { "Video" }
}

data class AnimeThemeEntry(
    val version: Int? = null,
    val episodes: String? = null,
    val spoiler: Boolean = false,
    val nsfw: Boolean = false,
    val videos: List<AnimeThemeVideo> = emptyList()
)

data class AnimeThemeTrack(
    val type: String, // "OP" or "ED"
    val slug: String, // "OP1", "ED1", etc.
    val sequence: Int,
    val songTitle: String,
    val artist: String? = null,
    val entries: List<AnimeThemeEntry> = emptyList(),
    val youtubeQuery: String = songTitle
) {
    val primaryVideo: AnimeThemeVideo?
        get() = entries.firstOrNull { it.videos.isNotEmpty() }?.videos?.firstOrNull()

    val hasDirectStream: Boolean
        get() = entries.any { e -> e.videos.any { it.link.isNotBlank() || !it.audioUrl.isNullOrBlank() } }

    val primaryEpisodes: String?
        get() = entries.firstNotNullOfOrNull { it.episodes?.takeIf(String::isNotBlank) }

    val hasSpoiler: Boolean
        get() = entries.any { it.spoiler }
}

data class AnimeThemeCollection(
    val animeSlug: String?,
    val themes: List<AnimeThemeTrack>
)

object AnimeThemesRepository {
    private const val TAG = "AnimeThemesRepository"
    private const val API_BASE = "https://api.animethemes.moe"
    private const val INCLUDE_QUERY = "animethemes.song.artists,animethemes.animethemeentries.videos.audio"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, AnimeThemeCollection>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun getThemes(media: Media): AnimeThemeCollection = withContext(Dispatchers.IO) {
        val anime = media.anime ?: return@withContext AnimeThemeCollection(null, emptyList())
        val cacheKey = "${media.id}:${media.idMAL ?: 0}:${anime.op.size}:${anime.ed.size}"
        cache[cacheKey]?.let { return@withContext it }

        val malId = media.idMAL?.takeIf { it > 0 }
        val apiResult = (if (malId != null) {
            fetchFromApi("MyAnimeList", malId.toString())
        } else null) ?: media.id.takeIf { it > 0 }?.let { anilistId ->
            fetchFromApi("AniList", anilistId.toString())
        }

        val collection = if (apiResult != null && apiResult.themes.isNotEmpty()) {
            apiResult
        } else {
            buildFallbackFromMal(media)
        }

        if (collection.themes.isNotEmpty()) {
            cache[cacheKey] = collection
        }
        collection
    }

    private fun fetchFromApi(site: String, externalId: String): AnimeThemeCollection? {
        val url = "$API_BASE/anime?filter[has]=resources&filter[site]=$site&filter[external_id]=$externalId&include=$INCLUDE_QUERY"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_UA)
                .header("Origin", "https://animethemes.moe")
                .header("Referer", "https://animethemes.moe/")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("$TAG: HTTP ${response.code} for $site=$externalId")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank() || body.trimStart().startsWith("<")) return null
                parseApiResponse(body)
            }
        } catch (e: Exception) {
            Logger.log("$TAG: fetch failed for $site=$externalId: ${e.message}")
            null
        }
    }

    private fun parseApiResponse(rawJson: String): AnimeThemeCollection? {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val animeList = root["anime"]?.jsonArray ?: return null
        val firstAnime = animeList.firstOrNull()?.jsonObject ?: return null
        val animeSlug = firstAnime["slug"]?.jsonPrimitive?.contentOrNull
        val rawThemes = firstAnime["animethemes"]?.jsonArray ?: return null

        val parsed = rawThemes.mapNotNull { el ->
            val obj = el.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.uppercase()?.let {
                if (it == "ED") "ED" else "OP"
            } ?: "OP"
            val sequence = obj["sequence"]?.jsonPrimitive?.intOrNull ?: 1
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "$type$sequence"

            val songObj = obj["song"] as? JsonObject
            val songTitle = songObj?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val artists = songObj["artists"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }

            val entries = obj["animethemeentries"]?.jsonArray?.mapNotNull { entryEl ->
                val entryObj = entryEl.jsonObject
                val version = entryObj["version"]?.jsonPrimitive?.intOrNull
                val episodes = entryObj["episodes"]?.jsonPrimitive?.contentOrNull
                val spoiler = entryObj["spoiler"]?.jsonPrimitive?.booleanOrNull ?: false
                val nsfw = entryObj["nsfw"]?.jsonPrimitive?.booleanOrNull ?: false
                val videos = entryObj["videos"]?.jsonArray?.mapNotNull { vidEl ->
                    val vidObj = vidEl.jsonObject
                    val link = vidObj["link"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: vidObj["basename"]?.jsonPrimitive?.contentOrNull?.let { "https://v.animethemes.moe/$it" }
                        ?: return@mapNotNull null
                    val audioObj = vidObj["audio"] as? JsonObject
                    val audioUrl = audioObj?.get("link")?.jsonPrimitive?.contentOrNull
                        ?: audioObj?.get("basename")?.jsonPrimitive?.contentOrNull?.let { "https://a.animethemes.moe/$it" }
                    val tags = vidObj["tags"]?.jsonPrimitive?.contentOrNull
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    AnimeThemeVideo(
                        link = link,
                        audioUrl = audioUrl,
                        resolution = vidObj["resolution"]?.jsonPrimitive?.intOrNull,
                        source = vidObj["source"]?.jsonPrimitive?.contentOrNull,
                        overlap = vidObj["overlap"]?.jsonPrimitive?.contentOrNull,
                        uncensored = vidObj["uncen"]?.jsonPrimitive?.booleanOrNull ?: false,
                        subbed = vidObj["subbed"]?.jsonPrimitive?.booleanOrNull ?: false,
                        lyrics = vidObj["lyrics"]?.jsonPrimitive?.booleanOrNull ?: false,
                        tags = tags
                    )
                }.orEmpty()
                AnimeThemeEntry(version, episodes, spoiler, nsfw, videos)
            }.orEmpty()

            val ytQuery = listOfNotNull(songTitle, artists).joinToString(" ")
            AnimeThemeTrack(
                type = type,
                slug = slug,
                sequence = sequence,
                songTitle = songTitle,
                artist = artists,
                entries = entries,
                youtubeQuery = ytQuery
            )
        }

        val sorted = parsed.sortedWith(compareBy({ if (it.type == "OP") 0 else 1 }, { it.sequence }))
        return AnimeThemeCollection(animeSlug, sorted)
    }

    private fun buildFallbackFromMal(media: Media): AnimeThemeCollection {
        val anime = media.anime ?: return AnimeThemeCollection(null, emptyList())
        val items = mutableListOf<AnimeThemeTrack>()
        anime.op.forEachIndexed { idx, raw ->
            parseMalThemeLine("OP", idx + 1, raw)?.let { items.add(it) }
        }
        anime.ed.forEachIndexed { idx, raw ->
            parseMalThemeLine("ED", idx + 1, raw)?.let { items.add(it) }
        }
        return AnimeThemeCollection(anime.slug, items)
    }

    private fun parseMalThemeLine(type: String, index: Int, rawLine: String): AnimeThemeTrack? {
        val cleaned = rawLine.trim()
        if (cleaned.isBlank()) return null

        val seqRegex = Regex("""^(?:(\d+):|#?(\d+)\s*:)\s*""")
        val seqMatch = seqRegex.find(cleaned)
        val seq = seqMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: index
        val remainder = if (seqMatch != null) cleaned.removeRange(seqMatch.range).trim() else cleaned

        val epMatch = Regex("""\(eps?\s*([^)]+)\)\s*$""", RegexOption.IGNORE_CASE).find(remainder)
        val episodes = epMatch?.groupValues?.getOrNull(1)?.trim()
        val withoutEps = if (epMatch != null) remainder.removeRange(epMatch.range).trim() else remainder

        val quotedMatch = Regex("""^"([^"]+)"\s*(?:by\s+(.+))?$""", RegexOption.IGNORE_CASE).find(withoutEps)
        val title: String
        val artist: String?
        if (quotedMatch != null) {
            title = quotedMatch.groupValues[1].trim()
            artist = quotedMatch.groupValues[2].trim().takeIf { it.isNotBlank() }
        } else {
            val parts = withoutEps.split(Regex("""\s+by\s+""", RegexOption.IGNORE_CASE), limit = 2)
            title = parts[0].trim().trim('"')
            artist = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }

        val ytQuery = listOfNotNull(title, artist).joinToString(" ").ifBlank { title }
        return AnimeThemeTrack(
            type = type,
            slug = "$type$seq",
            sequence = seq,
            songTitle = title,
            artist = artist,
            entries = if (episodes != null) listOf(AnimeThemeEntry(episodes = episodes)) else emptyList(),
            youtubeQuery = ytQuery
        )
    }

    fun buildYoutubeSearchUrl(track: AnimeThemeTrack, animeTitle: String): String {
        val q = "${track.youtubeQuery} $animeTitle ${track.type}"
        return "https://www.youtube.com/results?search_query=${URLEncoder.encode(q, "UTF-8")}"
    }
}
