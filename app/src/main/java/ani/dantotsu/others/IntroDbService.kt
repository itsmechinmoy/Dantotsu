package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object IntroDbService {
    private const val TAG = "IntroDbService"
    private const val INTRODB_APP_URL = "https://api.introdb.app/segments"
    private const val THE_INTRODB_URL = "https://api.theintrodb.org/v3/media"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun getSkipTimes(
        imdbId: String? = null,
        tmdbId: Int? = null,
        season: String = "1",
        episode: String
    ): List<AniSkip.Stamp>? = withContext(Dispatchers.IO) {
        val epInt = episode.toIntOrNull() ?: return@withContext null

        // 1. Try IntroDB App API via IMDB ID
        if (!imdbId.isNullOrBlank()) {
            val fromImdb = fetchFromIntroDbApp(imdbId, season, episode, epInt)
            if (!fromImdb.isNullOrEmpty()) return@withContext fromImdb
        }

        // 2. Try TheIntroDb API via TMDB ID
        if (tmdbId != null && tmdbId > 0) {
            val fromTmdb = fetchFromTheIntroDb(tmdbId, season, episode, epInt)
            if (!fromTmdb.isNullOrEmpty()) return@withContext fromTmdb
        }

        null
    }

    private suspend fun fetchFromIntroDbApp(
        imdbId: String,
        season: String,
        episode: String,
        episodeInt: Int
    ): List<AniSkip.Stamp>? {
        val url = "$INTRODB_APP_URL?imdb_id=$imdbId&season=$season&episode=$episode"
        return try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return null
            val root = json.parseToJsonElement(text).jsonObject

            val stamps = mutableListOf<AniSkip.Stamp>()

            root["intro"]?.jsonObject?.let { obj ->
                val start = obj["start_sec"]?.jsonPrimitive?.doubleOrNull
                val end = obj["end_sec"]?.jsonPrimitive?.doubleOrNull
                if (start != null && end != null && end > start) {
                    stamps.add(
                        AniSkip.Stamp(
                            interval = AniSkip.AniSkipInterval(start, end),
                            skipType = "op",
                            skipId = "introdb_op_${imdbId}_$episodeInt",
                            episodeLength = 0.0
                        )
                    )
                }
            }

            root["outro"]?.jsonObject?.let { obj ->
                val start = obj["start_sec"]?.jsonPrimitive?.doubleOrNull
                val end = obj["end_sec"]?.jsonPrimitive?.doubleOrNull
                if (start != null && end != null && end > start) {
                    stamps.add(
                        AniSkip.Stamp(
                            interval = AniSkip.AniSkipInterval(start, end),
                            skipType = "ed",
                            skipId = "introdb_ed_${imdbId}_$episodeInt",
                            episodeLength = 0.0
                        )
                    )
                }
            }

            root["recap"]?.jsonObject?.let { obj ->
                val start = obj["start_sec"]?.jsonPrimitive?.doubleOrNull
                val end = obj["end_sec"]?.jsonPrimitive?.doubleOrNull
                if (start != null && end != null && end > start) {
                    stamps.add(
                        AniSkip.Stamp(
                            interval = AniSkip.AniSkipInterval(start, end),
                            skipType = "recap",
                            skipId = "introdb_recap_${imdbId}_$episodeInt",
                            episodeLength = 0.0
                        )
                    )
                }
            }

            if (stamps.isNotEmpty()) stamps else null
        } catch (e: Exception) {
            Logger.log("$TAG: fetchFromIntroDbApp failed for $url: ${e.message}")
            null
        }
    }

    private suspend fun fetchFromTheIntroDb(
        tmdbId: Int,
        season: String,
        episode: String,
        episodeInt: Int
    ): List<AniSkip.Stamp>? {
        val url = "$THE_INTRODB_URL?tmdb_id=$tmdbId&season=$season&episode=$episode"
        return try {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return null
            val root = json.parseToJsonElement(text).jsonObject

            val stamps = mutableListOf<AniSkip.Stamp>()

            fun parseSegments(type: String, key: String) {
                val array = root[key]?.jsonArray ?: return
                for (item in array) {
                    val obj = item.jsonObject
                    val startMs = obj["start_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                    val endMs = obj["end_ms"]?.jsonPrimitive?.longOrNull ?: continue
                    if (endMs > startMs) {
                        stamps.add(
                            AniSkip.Stamp(
                                interval = AniSkip.AniSkipInterval(startMs / 1000.0, endMs / 1000.0),
                                skipType = type,
                                skipId = "theintrodb_${type}_${tmdbId}_$episodeInt",
                                episodeLength = 0.0
                            )
                        )
                    }
                }
            }

            parseSegments("op", "intro")
            parseSegments("ed", "credits")
            parseSegments("recap", "recap")

            if (stamps.isNotEmpty()) stamps else null
        } catch (e: Exception) {
            Logger.log("$TAG: fetchFromTheIntroDb failed for $url: ${e.message}")
            null
        }
    }
}
