package ani.dantotsu.notifications.comment

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MediaNameFetch {
    companion object {
        private const val GRAPHQL_QUERY = """
            query(${'$'}ids: [Int]) {
                Page(page: 1, perPage: 50) {
                    media(id_in: ${'$'}ids) {
                        id
                        title {
                            userPreferred
                            romaji
                            english
                        }
                        coverImage {
                            medium
                            color
                        }
                    }
                }
            }
        """

        fun cacheMedia(mediaId: Int, title: String, coverImage: String = "", color: String = "#222222") {
            if (mediaId <= 0 || title.isBlank() || title.equals("Unknown", ignoreCase = true)) return
            PrefManager.setCustomVal("media_title_$mediaId", title)
            if (coverImage.isNotBlank()) {
                PrefManager.setCustomVal("media_cover_$mediaId", coverImage)
            }
            if (color.isNotBlank()) {
                PrefManager.setCustomVal("media_color_$mediaId", color)
            }
        }

        fun getCachedMedia(mediaId: Int): ReturnedData? {
            val title = PrefManager.getCustomVal<String?>("media_title_$mediaId", null)
            if (!title.isNullOrBlank() && !title.equals("Unknown", ignoreCase = true)) {
                val cover = PrefManager.getCustomVal<String?>("media_cover_$mediaId", null) ?: ""
                val color = PrefManager.getCustomVal<String?>("media_color_$mediaId", null) ?: "#222222"
                return ReturnedData(title, cover, color)
            }
            return null
        }

        suspend fun fetchMediaTitles(ids: List<Int>): Map<Int, ReturnedData> {
            val validIds = ids.filter { it > 0 }.distinct()
            if (validIds.isEmpty()) return emptyMap()

            val mediaMap = mutableMapOf<Int, ReturnedData>()
            val idsToFetch = mutableListOf<Int>()

            for (id in validIds) {
                val cached = getCachedMedia(id)
                if (cached != null) {
                    mediaMap[id] = cached
                } else {
                    idsToFetch.add(id)
                }
            }

            if (idsToFetch.isNotEmpty()) {
                try {
                    val url = "https://graphql.anilist.co/"
                    val token = Anilist.token ?: PrefManager.getVal(PrefName.AnilistToken, null as String?)
                    val headers = mutableMapOf(
                        "Content-Type" to "application/json; charset=utf-8",
                        "Accept" to "application/json"
                    )
                    if (!token.isNullOrEmpty()) {
                        headers["Authorization"] = "Bearer $token"
                    }

                    withContext(Dispatchers.IO) {
                        for (chunk in idsToFetch.chunked(50)) {
                            val jsonPayload = buildString {
                                append("{\"query\":")
                                append(Json.encodeToString(GRAPHQL_QUERY))
                                append(",\"variables\":{\"ids\":[")
                                append(chunk.joinToString(","))
                                append("]}}")
                            }
                            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

                            val response = client.post(
                                url,
                                headers = headers,
                                requestBody = requestBody
                            )

                            if (response.code == 200) {
                                val mediaResponse = parseMediaResponseWithGson(response.text)
                                mediaResponse.data?.page?.media?.forEach { mediaItem ->
                                    val id = mediaItem.id ?: return@forEach
                                    val title = mediaItem.title?.userPreferred
                                        ?: mediaItem.title?.romaji
                                        ?: mediaItem.title?.english
                                        ?: return@forEach
                                    val cover = mediaItem.coverImage?.medium ?: ""
                                    val color = mediaItem.coverImage?.color ?: "#222222"
                                    val returned = ReturnedData(title, cover, color)
                                    mediaMap[id] = returned
                                    cacheMedia(id, title, cover, color)
                                }
                            } else {
                                Logger.log("MediaNameFetch response code: ${response.code}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("MediaNameFetch network error: ${e.message}")
                    Logger.log(e)
                }
            }

            // Fill any remaining with cache fallback or "Unknown"
            validIds.forEach { id ->
                mediaMap.putIfAbsent(id, getCachedMedia(id) ?: ReturnedData("Unknown", "", "#222222"))
            }

            return mediaMap
        }

        private fun parseMediaResponseWithGson(response: String): MediaPageResponse {
            val gson = Gson()
            val type = object : TypeToken<MediaPageResponse>() {}.type
            return gson.fromJson(response, type)
        }

        data class ReturnedData(val title: String, val coverImage: String, val color: String)

        data class MediaPageResponse(val data: PageData?)
        data class PageData(
            @SerializedName("Page")
            val page: MediaList?
        )
        data class MediaList(val media: List<MediaItem>?)
        data class MediaItem(
            val id: Int?,
            val title: MediaTitle?,
            val coverImage: MediaCoverImage?
        )
        data class MediaTitle(
            val userPreferred: String?,
            val romaji: String?,
            val english: String?
        )
        data class MediaCoverImage(
            val medium: String?,
            val color: String?
        )
    }
}
