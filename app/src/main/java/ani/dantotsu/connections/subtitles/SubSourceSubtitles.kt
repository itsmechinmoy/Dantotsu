package ani.dantotsu.connections.subtitles

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object SubSourceSubtitles {
    private const val BASE_URL = "https://api.subsource.net/api/v1"
    private const val DEFAULT_API_KEY = "sk_559a7155a0ffa592d732eb89dc0307e967934cfb92ecb6e097a2f2d850d00490"

    suspend fun getSubtitles(imdbId: String, episode: Int, season: Int? = null): List<SubSourceSub> {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = try {
                    PrefManager.getNullableCustomVal("pref_subsource_api_key", "", String::class.java).orEmpty().ifBlank { DEFAULT_API_KEY }
                } catch (_: Exception) { DEFAULT_API_KEY }

                val searchUrl = if (season != null && season > 0) {
                    "$BASE_URL/movies/search?searchType=imdb&imdb=$imdbId&season=$season"
                } else {
                    "$BASE_URL/movies/search?searchType=imdb&imdb=$imdbId"
                }

                val reqBuilder = Request.Builder().url(searchUrl)
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("X-API-Key", apiKey)
                }

                val resp = okHttpClient.newCall(reqBuilder.build()).execute()
                val json = resp.body.string()
                if (!resp.isSuccessful || json.isBlank()) return@withContext emptyList()
                val searchResult = Mapper.json.decodeFromString<SubSourceSearchResponseV1>(json)
                val movie = searchResult.data.firstOrNull() ?: return@withContext emptyList()
                val movieId = movie.movieId

                // Fetch subtitles for this movie
                val userLangs = try {
                    PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages).joinToString(",") { it.lowercase(Locale.ROOT) }
                } catch (_: Exception) { "" }

                val subUrl = if (userLangs.isNotBlank()) {
                    "$BASE_URL/subtitles?movieId=$movieId&language=$userLangs&limit=100&sort=rating"
                } else {
                    "$BASE_URL/subtitles?movieId=$movieId&limit=100&sort=rating"
                }

                val subReqBuilder = Request.Builder().url(subUrl)
                if (apiKey.isNotBlank()) {
                    subReqBuilder.addHeader("X-API-Key", apiKey)
                }

                val subResp = okHttpClient.newCall(subReqBuilder.build()).execute()
                val subJson = subResp.body.string()
                if (!subResp.isSuccessful || subJson.isBlank()) return@withContext emptyList()
                val subResult = Mapper.json.decodeFromString<SubSourceListResponseV1>(subJson)

                val epStr = episode.toString()
                val epPad = episode.toString().padStart(2, '0')
                val epPattern1 = "E$epPad"
                val epPattern2 = "E$epStr"
                val epPattern3 = " $epStr "
                val epPattern4 = " - $epStr"

                val matched = subResult.data.filter { item ->
                    val relText = (item.releaseInfo.joinToString(" ") + " " + (item.commentary ?: "")).trim()
                    if (relText.isBlank()) return@filter true
                    relText.contains(epPattern1, ignoreCase = true) ||
                    relText.contains(epPattern2, ignoreCase = true) ||
                    relText.contains(epPattern3, ignoreCase = true) ||
                    relText.contains(epPattern4, ignoreCase = true) ||
                    relText.contains("Episode $epStr", ignoreCase = true) ||
                    relText.contains("Ep $epStr", ignoreCase = true) ||
                    relText.contains("Ep. $epStr", ignoreCase = true) ||
                    relText.contains(" $epPad ", ignoreCase = true)
                }

                matched.map { item ->
                    val releaseTitle = item.releaseInfo.joinToString(" • ").ifBlank { item.commentary ?: "SubSource Subtitle" }
                    SubSourceSub(
                        id = item.subtitleId.toString(),
                        releaseName = releaseTitle,
                        lang = item.language.replaceFirstChar { it.uppercase() },
                        movie = movie.title ?: "SubSource",
                        isHearingImpaired = item.hearingImpaired == true
                    )
                }
            } catch (e: Exception) {
                Logger.log("SubSource error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun downloadSubtitleContent(subtitleId: String): Pair<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = try {
                    PrefManager.getNullableCustomVal("pref_subsource_api_key", "", String::class.java).orEmpty().ifBlank { DEFAULT_API_KEY }
                } catch (_: Exception) { DEFAULT_API_KEY }

                val url = "$BASE_URL/subtitles/$subtitleId/download"
                val reqBuilder = Request.Builder().url(url)
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("X-API-Key", apiKey)
                }

                val resp = okHttpClient.newCall(reqBuilder.build()).execute()
                val bytes = resp.body.bytes()
                if (!resp.isSuccessful || bytes.isEmpty()) return@withContext null
                // Parse ZIP stream
                val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (!entry.isDirectory && (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".vtt") || name.endsWith(".ssa"))) {
                        val content = zipIn.readBytes().toString(Charsets.UTF_8)
                        return@withContext Pair(entry.name, content)
                    }
                    entry = zipIn.nextEntry
                }
                // Fallback for non-zip plain text
                val rawText = bytes.toString(Charsets.UTF_8)
                if (rawText.contains("-->") || rawText.contains("[Script Info]")) {
                    Pair("subtitle.srt", rawText)
                } else null
            } catch (e: Exception) {
                Logger.log("SubSource download error: ${e.message}")
                null
            }
        }
    }
}

@Serializable
data class SubSourceSub(
    val id: String,
    val releaseName: String,
    val lang: String,
    val movie: String,
    val isHearingImpaired: Boolean = false
)

@Serializable
data class SubSourceSearchResponseV1(
    val success: Boolean = false,
    val data: List<SubSourceMovieV1> = emptyList()
)

@Serializable
data class SubSourceMovieV1(
    val movieId: Int,
    val title: String? = null,
    val alternateTitle: String? = null,
    val type: String? = null,
    val releaseYear: Int? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val subtitleCount: Int? = null
)

@Serializable
data class SubSourceListResponseV1(
    val success: Boolean = false,
    val data: List<SubSourceItemV1> = emptyList()
)

@Serializable
data class SubSourceItemV1(
    val subtitleId: Int,
    val movieId: Int? = null,
    val language: String = "english",
    val releaseInfo: List<String> = emptyList(),
    val commentary: String? = null,
    val files: Int? = null,
    val hearingImpaired: Boolean? = null,
    val foreignParts: Boolean? = null,
    val framerate: String? = null,
    val productionType: String? = null,
    val releaseType: String? = null,
    val downloads: Int? = null
)
