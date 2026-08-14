package ani.dantotsu.connections.subtitles

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object SubSourceSubtitles {
    private const val API_URL = "https://api.subsource.net/api"
    private const val DOWNLOAD_ENDPOINT = "$API_URL/downloadSub"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun getSubtitles(imdbId: String, episode: Int, season: Int? = null): List<SubSourceSub> {
        return withContext(Dispatchers.IO) {
            try {
                val searchBody = """{"query":"$imdbId"}""".toRequestBody(JSON_MEDIA_TYPE)
                val searchReq = Request.Builder()
                    .url("$API_URL/searchMovie")
                    .post(searchBody)
                    .build()
                val searchResp = okHttpClient.newCall(searchReq).execute()
                if (!searchResp.isSuccessful || searchResp.body == null) return@withContext emptyList()

                val searchJson = searchResp.body!!.string()
                val searchResult = Mapper.json.decodeFromString<SubSourceSearchResponse>(searchJson)
                val movieName = searchResult.found.firstOrNull()?.linkName ?: return@withContext emptyList()

                val movieBodyStr = if (season != null && season > 1) {
                    """{"langs":"[]","movieName":"$movieName","season":"season-$season"}"""
                } else {
                    """{"langs":"[]","movieName":"$movieName"}"""
                }
                val movieReq = Request.Builder()
                    .url("$API_URL/getMovie")
                    .post(movieBodyStr.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val movieResp = okHttpClient.newCall(movieReq).execute()
                if (!movieResp.isSuccessful || movieResp.body == null) return@withContext emptyList()

                val movieJson = movieResp.body!!.string()
                val movieData = Mapper.json.decodeFromString<SubSourceMovieResponse>(movieJson)

                val epStr = episode.toString()
                val epPad = episode.toString().padStart(2, '0')
                val epPattern1 = "E$epPad"
                val epPattern2 = "E$epStr"
                val epPattern3 = " $epStr "
                val epPattern4 = " - $epStr"

                val matched = movieData.subs.filter { sub ->
                    val rel = sub.releaseName ?: ""
                    rel.contains(epPattern1, ignoreCase = true) ||
                    rel.contains(epPattern2, ignoreCase = true) ||
                    rel.contains(epPattern3, ignoreCase = true) ||
                    rel.contains(epPattern4, ignoreCase = true) ||
                    rel.contains("Episode $epStr", ignoreCase = true) ||
                    rel.contains("Ep $epStr", ignoreCase = true) ||
                    rel.contains("Ep. $epStr", ignoreCase = true) ||
                    rel.contains(" $epPad ", ignoreCase = true)
                }

                Logger.log("SubSource: Found ${matched.size} matching subs for ep $episode")

                matched.map { sub ->
                    SubSourceSub(
                        id = sub.subId?.toString() ?: sub.linkName ?: "",
                        releaseName = sub.releaseName ?: "SubSource Subtitle",
                        lang = sub.lang ?: "English",
                        movie = movieName,
                        isHearingImpaired = sub.hi == 1
                    )
                }
            } catch (e: Exception) {
                Logger.log("SubSource error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getDownloadUrl(sub: SubSourceSub): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bodyStr = """{"movie":"${sub.movie}","lang":"${sub.lang}","id":"${sub.id}"}"""
                val req = Request.Builder()
                    .url("$API_URL/getSub")
                    .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                if (!resp.isSuccessful || resp.body == null) return@withContext null
                val json = resp.body!!.string()
                val linkData = Mapper.json.decodeFromString<SubSourceLinkResponse>(json)
                "$DOWNLOAD_ENDPOINT/${linkData.sub.downloadToken}"
            } catch (e: Exception) {
                Logger.log("SubSource getDownloadUrl error: ${e.message}")
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
data class SubSourceSearchResponse(
    val success: Boolean = false,
    val found: List<SubSourceFound> = emptyList()
)

@Serializable
data class SubSourceFound(
    val linkName: String = ""
)

@Serializable
data class SubSourceMovieResponse(
    val success: Boolean = false,
    val subs: List<SubSourceItem> = emptyList()
)

@Serializable
data class SubSourceItem(
    val subId: Int? = null,
    val linkName: String? = null,
    val lang: String? = null,
    val releaseName: String? = null,
    val hi: Int? = null
)

@Serializable
data class SubSourceLinkResponse(
    val sub: SubSourceToken
)

@Serializable
data class SubSourceToken(
    val downloadToken: String
)
