package ani.dantotsu.connections.mangabaka

import ani.dantotsu.client
import ani.dantotsu.connections.mapping.IdMapper
import ani.dantotsu.media.Media
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

class MangaBakaQueries {

    private fun getAuthHeaders(): Map<String, String> {
        val tok = MangaBaka.token ?: return emptyMap()
        return mapOf(
            "Authorization" to "Bearer $tok",
            "Accept" to "application/json"
        )
    }

    suspend fun getUserData(): Boolean {
        return tryWithSuspend(false) {
            val headers = getAuthHeaders()
            if (headers.isEmpty()) return@tryWithSuspend false

            val res = client.get(
                "${MangaBaka.apiHost}/my/profile",
                headers = headers
            ).parsed<MangaBakaProfileResponse>()

            val profile = res.data ?: return@tryWithSuspend false
            val name = profile.nickname ?: profile.preferredUsername ?: profile.id ?: "User"
            val id = profile.id ?: ""
            var avatar = profile.picture

            if (avatar.isNullOrBlank()) {
                val userinfoRes = runCatching {
                    client.get(
                        "${MangaBaka.oauthHost}/userinfo",
                        headers = headers
                    ).parsed<MangaBakaUserInfo>()
                }.getOrNull()
                avatar = userinfoRes?.picture
            }

            MangaBaka.username = name
            MangaBaka.userId = id
            MangaBaka.avatar = avatar

            PrefManager.setVal(PrefName.MangaBakaUserName, name)
            PrefManager.setVal(PrefName.MangaBakaUserId, id)
            if (avatar != null) {
                PrefManager.setVal(PrefName.MangaBakaAvatar, avatar)
            }
            true
        } ?: false
    }

    suspend fun findMangaBakaId(media: Media): Long? {
        val cached = PrefManager.getCustomVal<Long?>("mb_id_${media.id}", null)
        if (cached != null && cached > 0) return cached

        return tryWithSuspend {
            var seriesId: Long? = null
            if (media.id > 0) {
                val res = runCatching {
                    client.get("${MangaBaka.apiHost}/source/anilist/${media.id}")
                        .parsed<MangaBakaSourceResponse>()
                }.getOrNull()
                seriesId = res?.data?.series?.firstOrNull()?.id
            }

            if (seriesId == null && media.idMAL != null && media.idMAL!! > 0) {
                val res = runCatching {
                    client.get("${MangaBaka.apiHost}/source/my-anime-list/${media.idMAL}")
                        .parsed<MangaBakaSourceResponse>()
                }.getOrNull()
                seriesId = res?.data?.series?.firstOrNull()?.id
            }

            if (seriesId != null && seriesId > 0) {
                PrefManager.setCustomVal("mb_id_${media.id}", seriesId)
            }
            seriesId
        }
    }

    suspend fun getRecommendations(limit: Int = 13): ArrayList<Media> {
        if (MangaBaka.token == null) return arrayListOf()
        return tryWithSuspend<ArrayList<Media>>(false) {
            val allowAdult = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
            val url = buildRecommendationsUrl(limit, allowAdult)
            var res = client.get(url, headers = getAuthHeaders())
            if (res.code == 401 && MangaBaka.refreshToken() != null) {
                res = client.get(url, headers = getAuthHeaders())
            }
            if (res.code !in 200..299) return@tryWithSuspend arrayListOf()

            val root = runCatching { Json.parseToJsonElement(res.text) as? JsonObject }.getOrNull()
                ?: return@tryWithSuspend arrayListOf()
            val arr = root["data"] as? JsonArray ?: root["results"] as? JsonArray ?: return@tryWithSuspend arrayListOf()

            val seen = mutableSetOf<Long>()
            val list = arrayListOf<Media>()
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val mbId = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                if (!seen.add(mbId)) continue
                val media = mapRecommendation(mbId, obj)
                if (!allowAdult && media.isAdult) continue
                list.add(media)
            }
            list
        } ?: arrayListOf()
    }

    private fun buildRecommendationsUrl(limit: Int, allowAdult: Boolean): String {
        val base = "${MangaBaka.apiHost}/my/series/recommendations?limit=$limit&page=1&sort_by=relevance_desc"
        return if (allowAdult) {
            base
        } else {
            "$base&content_rating=safe&content_rating=suggestive"
        }
    }

    private suspend fun mapRecommendation(mbId: Long, obj: JsonObject): Media {
        val titles = obj["titles"] as? JsonArray
        val primaryTitle = extractPrimaryTitle(titles)
            ?: obj["title"]?.jsonPrimitive?.contentOrNull()
            ?: "MangaBaka #$mbId"
        val englishTitle = extractEnglishTitle(titles) ?: primaryTitle
        val score = obj["score"]?.jsonPrimitive?.doubleOrNull
        val contentRating = obj["content_rating"]?.jsonPrimitive?.contentOrNull()?.lowercase()
        val isAdult = contentRating in ADULT_RATINGS
        val coverUrl = resolveRecommendationCover(obj["cover_image"] ?: obj["cover"])
        val mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull()?.uppercase() ?: "MANGA"

        val sourceObj = obj["source"] as? JsonObject
        val sourceAlId = (sourceObj?.get("anilist") as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
        val sourceMalId = (sourceObj?.get("my_anime_list") as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
        val resolvedAlId = sourceAlId ?: runCatching {
            IdMapper.getAnilistIdFromMangaBaka(mbId.toInt())
        }.getOrNull()

        val canonicalUrl = obj["canonical_url"]?.jsonPrimitive?.contentOrNull()
            ?: "https://mangabaka.org/$mbId"

        return Media(
            id = resolvedAlId ?: mbId.toInt(),
            idMAL = sourceMalId,
            name = englishTitle,
            nameRomaji = primaryTitle,
            userPreferredName = englishTitle,
            cover = coverUrl,
            isAdult = isAdult,
            meanScore = score?.takeIf { it > 0.0 }?.roundToInt(),
            format = mediaType,
            shareLink = canonicalUrl,
            manga = Manga(),
        )
    }

    private fun extractPrimaryTitle(titles: JsonArray?): String? {
        if (titles == null) return null
        val primary = titles.firstNotNullOfOrNull { item ->
            val t = item as? JsonObject ?: return@firstNotNullOfOrNull null
            if (t["is_primary"]?.jsonPrimitive?.booleanOrNull == true) {
                t["title"]?.jsonPrimitive?.contentOrNull()
            } else {
                null
            }
        }
        return primary ?: titles.firstNotNullOfOrNull { item ->
            (item as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull()
        }
    }

    private fun extractEnglishTitle(titles: JsonArray?): String? {
        if (titles == null) return null
        return titles.firstNotNullOfOrNull { item ->
            val t = item as? JsonObject ?: return@firstNotNullOfOrNull null
            if (t["language"]?.jsonPrimitive?.contentOrNull() == "en") {
                t["title"]?.jsonPrimitive?.contentOrNull()
            } else {
                null
            }
        }
    }

    private fun resolveRecommendationCover(value: JsonElement?): String? {
        if (value == null) return null
        if (value is JsonPrimitive && value.isString) {
            return value.content.takeIf { it.isNotBlank() }
        }
        val cover = value as? JsonObject ?: return null
        val raw = cover["raw"] as? JsonObject
        val rawUrl = raw?.get("url")?.jsonPrimitive?.contentOrNull()
        val rawSize = raw?.get("size")?.jsonPrimitive?.longOrNull
        if (!rawUrl.isNullOrBlank() && rawSize != null && rawSize in 1 until 400_000L) {
            return rawUrl
        }
        for (sizeKey in listOf("x350", "x250")) {
            val variant = cover[sizeKey]
            val candidate = if (variant is JsonObject) {
                variant["x2"]?.jsonPrimitive?.contentOrNull()
                    ?: variant["x1"]?.jsonPrimitive?.contentOrNull()
            } else {
                variant?.jsonPrimitive?.contentOrNull()
            }
            if (!candidate.isNullOrBlank()) return candidate
        }
        return rawUrl?.takeIf { it.isNotBlank() }
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null

    suspend fun updateProgress(media: Media, progress: Int): Boolean {
        if (MangaBaka.token == null) return false
        val seriesId = findMangaBakaId(media) ?: return false

        return tryWithSuspend(false) {
            val state = if (media.userStatus == "COMPLETED") "completed" else "reading"
            val body = mapOf(
                "state" to state,
                "progress_chapter" to progress
            )
            writeLibraryEntry(seriesId, body)
        } ?: false
    }

    suspend fun editList(
        media: Media,
        status: String?,
        progress: Int?,
        score: Double?
    ): Boolean {
        if (MangaBaka.token == null) return false
        val seriesId = findMangaBakaId(media) ?: return false

        return tryWithSuspend(false) {
            val mbState = when (status) {
                "COMPLETED" -> "completed"
                "DROPPED" -> "dropped"
                "PAUSED" -> "paused"
                "PLANNING" -> "plan_to_read"
                "REPEATING" -> "rereading"
                else -> "reading"
            }
            val body = mutableMapOf<String, Any>("state" to mbState)
            if (progress != null && progress >= 0) {
                body["progress_chapter"] = progress
            }
            if (score != null && score > 0) {
                body["rating"] = score
            }
            writeLibraryEntry(seriesId, body)
        } ?: false
    }

    private suspend fun writeLibraryEntry(seriesId: Long, body: Map<String, Any>): Boolean {
        var headers = getAuthHeaders() + ("Content-Type" to "application/json")
        if (headers.size <= 1) return false

        val path = "${MangaBaka.apiHost}/my/library/$seriesId"
        return try {
            var putRes = client.put(path, headers = headers, json = body)
            if (putRes.code == 401 && MangaBaka.refreshToken() != null) {
                headers = getAuthHeaders() + ("Content-Type" to "application/json")
                putRes = client.put(path, headers = headers, json = body)
            }
            if (putRes.code in 200..299) {
                true
            } else {
                val postRes = client.post(path, headers = headers, json = body)
                postRes.code in 200..299
            }
        } catch (e: Exception) {
            Logger.log("MangaBaka write error: ${e.message}")
            false
        }
    }

    companion object {
        private val ADULT_RATINGS = setOf("erotica", "pornographic")
    }
}
