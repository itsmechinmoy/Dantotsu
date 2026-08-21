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

object OpenSubtitlesRestApi {
    private const val API_KEY = "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2"
    private const val HOST = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "Cloudstream3 v0.2"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun search(imdbId: String, episode: Int, season: Int? = null, queryText: String? = null): List<OpenSubRestItem> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanImdb = imdbId.removePrefix("tt")
                val urls = mutableListOf<String>()

                urls.add("$HOST/subtitles?imdb_id=$cleanImdb&episode_number=$episode")
                if (season != null && season > 1) {
                    urls.add("$HOST/subtitles?imdb_id=$cleanImdb&season_number=$season&episode_number=$episode")
                }
                if (!queryText.isNullOrBlank()) {
                    urls.add("$HOST/subtitles?query=${java.net.URLEncoder.encode(queryText, "UTF-8")}&episode_number=$episode")
                }

                val results = mutableListOf<OpenSubRestItem>()
                for (url in urls) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .addHeader("Api-Key", API_KEY)
                            .addHeader("User-Agent", USER_AGENT)
                            .addHeader("Accept", "application/json")
                            .build()
                        val resp = okHttpClient.newCall(req).execute()
                        if (resp.isSuccessful && resp.body != null) {
                            val json = resp.body!!.string()
                            val parsed = Mapper.json.decodeFromString<OpenSubRestResponse>(json)
                            parsed.data.forEach { item ->
                                val file = item.attributes.files.firstOrNull() ?: return@forEach
                                val fileId = file.fileId ?: return@forEach
                                val fileName = file.fileName ?: item.attributes.release ?: "OpenSubtitles Subtitle"
                                val lang = item.attributes.language ?: "English"
                                val isHi = item.attributes.hearingImpaired == true
                                results.add(
                                    OpenSubRestItem(
                                        fileId = fileId,
                                        fileName = fileName,
                                        language = lang,
                                        hearingImpaired = isHi
                                    )
                                )
                            }
                            if (results.isNotEmpty()) break
                        }
                    } catch (_: Exception) {}
                }
                results
            } catch (e: Exception) {
                Logger.log("OpenSubtitlesRestApi error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getDownloadUrl(fileId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bodyStr = """{"file_id":$fileId}"""
                val req = Request.Builder()
                    .url("$HOST/download")
                    .addHeader("Api-Key", API_KEY)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                if (resp.isSuccessful && resp.body != null) {
                    val json = resp.body!!.string()
                    val parsed = Mapper.json.decodeFromString<OpenSubDownloadResponse>(json)
                    parsed.link
                } else null
            } catch (e: Exception) {
                Logger.log("OpenSubtitles download error: ${e.message}")
                null
            }
        }
    }
}

@Serializable
data class OpenSubRestItem(
    val fileId: Int,
    val fileName: String,
    val language: String,
    val hearingImpaired: Boolean = false
)

@Serializable
data class OpenSubRestResponse(
    val data: List<OpenSubData> = emptyList()
)

@Serializable
data class OpenSubData(
    val id: String? = null,
    val attributes: OpenSubAttributes
)

@Serializable
data class OpenSubAttributes(
    val language: String? = null,
    val release: String? = null,
    val files: List<OpenSubFile> = emptyList(),
    @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null
)

@Serializable
data class OpenSubFile(
    @SerialName("file_id") val fileId: Int? = null,
    @SerialName("file_name") val fileName: String? = null
)

@Serializable
data class OpenSubDownloadResponse(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null
)
