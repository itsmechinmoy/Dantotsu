package ani.dantotsu.others

import ani.dantotsu.FileUrl
import ani.dantotsu.client
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.math.abs

object TmdbService {
    private const val TAG = "TmdbService"
    private const val API_KEY = "926bf284e333aa31eba7658bca87200a"
    private val BASE_URLS = listOf(
        "https://api.themoviedb.org/3",
        "https://api.tmdb.org/3"
    )
    private const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private suspend fun getTmdbJson(pathAndQuery: String): JsonObject? {
        val separator = if ('?' in pathAndQuery) '&' else '?'
        for (baseUrl in BASE_URLS) {
            val url = "$baseUrl$pathAndQuery${separator}api_key=$API_KEY"
            try {
                val response = client.get(url)
                val text = response.text
                if (text.isBlank()) continue
                val obj = json.parseToJsonElement(text).jsonObject
                if (obj.containsKey("status_code") && obj["status_code"]?.jsonPrimitive?.intOrNull != null &&
                    obj["status_code"]?.jsonPrimitive?.intOrNull != 1
                ) {
                    continue
                }
                return obj
            } catch (e: Exception) {
                Logger.log("$TAG: request failed for $baseUrl$pathAndQuery: ${e.message}")
            }
        }
        return null
    }

    suspend fun getClearLogo(tmdbId: Int, isMovie: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext null
        val type = if (isMovie) "movie" else "tv"
        val root = getTmdbJson("/$type/$tmdbId/images") ?: return@withContext null
        extractBestLogoFromImages(root)
    }

    suspend fun findTmdbByImdb(imdbId: String): Pair<Int, Boolean>? = withContext(Dispatchers.IO) {
        val cleanId = imdbId.trim()
        if (!cleanId.startsWith("tt")) return@withContext null
        val root = getTmdbJson("/find/$cleanId?external_source=imdb_id") ?: return@withContext null

        val tvId = root["tv_results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.intOrNull
        if (tvId != null && tvId > 0) return@withContext tvId to false

        val movieId = root["movie_results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.intOrNull
        if (movieId != null && movieId > 0) return@withContext movieId to true

        null
    }

    suspend fun searchClearLogo(
        titles: List<String>,
        year: Int? = null,
        preferMovie: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val queries = buildSearchVariants(titles)
        if (queries.isEmpty()) return@withContext null

        val types = if (preferMovie) listOf("movie", "tv") else listOf("tv", "movie")
        var bestId: Int? = null
        var bestIsMovie = preferMovie
        var bestScore = Int.MIN_VALUE

        for (queryTitle in queries.take(3)) {
            val encoded = URLEncoder.encode(queryTitle, "UTF-8")
            for (type in types) {
                val isMovieType = type == "movie"
                val root = getTmdbJson("/search/$type?query=$encoded") ?: continue
                val results = root["results"]?.jsonArray ?: continue
                for (entry in results.take(8)) {
                    val obj = entry.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val candTitle = (obj["name"] ?: obj["title"])?.jsonPrimitive?.contentOrNull.orEmpty()
                    val origTitle = (obj["original_name"] ?: obj["original_title"])?.jsonPrimitive?.contentOrNull.orEmpty()
                    val dateStr = (obj["first_air_date"] ?: obj["release_date"])?.jsonPrimitive?.contentOrNull.orEmpty()
                    val candYear = dateStr.take(4).toIntOrNull()
                    val popularity = obj["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0

                    val score = scoreCandidate(queryTitle, candTitle, origTitle, year, candYear, popularity)
                    if (score > bestScore) {
                        bestScore = score
                        bestId = id
                        bestIsMovie = isMovieType
                    }
                }
            }
            if (bestId != null && bestScore >= 75) break
        }

        val resolvedId = bestId ?: return@withContext null
        if (bestScore < 30) return@withContext null
        getClearLogo(resolvedId, bestIsMovie)
    }

    private fun extractBestLogoFromImages(root: JsonObject): String? {
        val logos = root["logos"]?.jsonArray ?: return null
        if (logos.isEmpty()) return null

        val candidates = logos.mapNotNull { item ->
            val obj = item.jsonObject
            val path = obj["file_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lang = obj["iso_639_1"]?.jsonPrimitive?.contentOrNull
            val voteAvg = obj["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
            Triple(path, lang, voteAvg * 100.0 + width / 100.0)
        }
        if (candidates.isEmpty()) return null

        val best = candidates
            .sortedByDescending { (_, lang, qualityScore) ->
                val langRank = when {
                    lang.equals("en", ignoreCase = true) -> 3000.0
                    lang.isNullOrBlank() -> 2000.0
                    lang.equals("ja", ignoreCase = true) -> 1000.0
                    else -> 0.0
                }
                langRank + qualityScore
            }
            .first()
            .first

        val normalizedPath = if (best.endsWith(".svg", ignoreCase = true)) {
            best.dropLast(4) + ".png"
        } else {
            best
        }
        return "$IMAGE_BASE_W500$normalizedPath"
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun buildSearchVariants(titles: List<String>): List<String> {
        val result = linkedSetOf<String>()
        val stripPatterns = listOf(
            Regex("(?i)\\s*[:\\-–]\\s*(season|part|cour|arc|chapter|movie|ova|special)\\b.*$"),
            Regex("(?i)\\s+(?:season|part|cour)\\s*\\d+.*$"),
            Regex("(?i)\\s+(?:\\d+(?:st|nd|rd|th))\\s+season.*$"),
            Regex("(?i)\\s+(?:the\\s+)?final\\s+season.*$"),
            Regex("(?i)\\s+(?:ii|iii|iv|v|2|3|4)$")
        )
        for (raw in titles) {
            val clean = raw.trim()
            if (clean.isBlank()) continue
            result.add(clean)
            var stripped = clean
            for (pattern in stripPatterns) {
                stripped = pattern.replace(stripped, "").trim()
            }
            if (stripped.length >= 3 && stripped != clean) {
                result.add(stripped)
            }
            val beforeColon = clean.substringBefore(":").trim()
            if (beforeColon.length >= 4 && beforeColon != clean) {
                result.add(beforeColon)
            }
        }
        return result.toList()
    }

    private fun scoreCandidate(
        query: String,
        candidateTitle: String,
        originalTitle: String,
        targetYear: Int?,
        candidateYear: Int?,
        popularity: Double
    ): Int {
        val normQuery = normalizeTitle(query)
        val normCand = normalizeTitle(candidateTitle)
        val normOrig = normalizeTitle(originalTitle)
        if (normQuery.isBlank() || (normCand.isBlank() && normOrig.isBlank())) return -1

        var score = 0
        when {
            normQuery == normCand || normQuery == normOrig -> score += 80
            normCand.startsWith(normQuery) || normQuery.startsWith(normCand) -> score += 55
            normOrig.startsWith(normQuery) || normQuery.startsWith(normOrig) -> score += 50
            else -> {
                val queryTokens = normQuery.split(' ').filter { it.length > 1 }.toSet()
                val candTokens = normCand.split(' ').filter { it.length > 1 }.toSet()
                val overlap = queryTokens.intersect(candTokens).size
                if (queryTokens.isNotEmpty() && overlap > 0) {
                    score += (overlap * 40) / maxOf(queryTokens.size, candTokens.size)
                }
            }
        }
        if (targetYear != null && candidateYear != null) {
            val diff = abs(targetYear - candidateYear)
            when {
                diff == 0 -> score += 20
                diff <= 1 -> score += 12
                diff <= 3 -> score += 4
                diff >= 6 -> score -= 10
            }
        }
        score += (popularity.coerceAtMost(50.0) / 10.0).toInt()
        return score
    }

    suspend fun getEpisodeDetails(tmdbId: Int, season: Int = 1): Map<String, Episode>? = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext null
        val root = getTmdbJson("/tv/$tmdbId/season/$season") ?: return@withContext null
        val episodesArray = root["episodes"]?.jsonArray ?: return@withContext null

        val result = mutableMapOf<String, Episode>()
        for (item in episodesArray) {
            val epObj = item.jsonObject
            val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: continue
            val name = epObj["name"]?.jsonPrimitive?.contentOrNull
            val overview = epObj["overview"]?.jsonPrimitive?.contentOrNull
            val stillPath = epObj["still_path"]?.jsonPrimitive?.contentOrNull
            val thumbUrl = if (!stillPath.isNullOrBlank()) "$IMAGE_BASE_W500$stillPath" else null

            val key = epNum.toString()
            result[key] = Episode(
                number = key,
                title = name,
                desc = overview,
                thumb = thumbUrl?.let { FileUrl[it] },
                extra = buildMap {
                    epObj["air_date"]?.jsonPrimitive?.contentOrNull?.let { put("airDate", it) }
                    put("season", season.toString())
                    put("episode", key)
                }
            )
        }

        if (result.isNotEmpty()) result else null
    }
}
