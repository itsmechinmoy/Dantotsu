package ani.dantotsu.connections.comick

import ani.dantotsu.client
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlin.math.roundToInt

class ComickQueries {

    private fun getAuthHeaders(): Map<String, String> {
        val tok = Comick.token ?: return emptyMap()
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
                "${Comick.resource}/me",
                headers = headers
            ).parsed<ComickUser>()

            val name = res.username ?: "ComickUser"
            val id = res.id ?: ""
            val avatar = res.avatarUrl

            Comick.username = name
            Comick.userId = id
            Comick.avatar = avatar

            PrefManager.setVal(PrefName.ComickUserName, name)
            PrefManager.setVal(PrefName.ComickUserId, id)
            if (avatar != null) {
                PrefManager.setVal(PrefName.ComickAvatar, avatar)
            }
            true
        } ?: false
    }

    suspend fun findComickHid(media: Media): String? {
        val cached = PrefManager.getCustomVal<String?>("comick_hid_${media.id}", null)
        if (!cached.isNullOrBlank()) return cached

        return tryWithSuspend {
            val title = media.userPreferredName?.ifBlank { null } ?: media.name ?: media.nameRomaji
            if (title.isNullOrBlank()) return@tryWithSuspend null

            val isAnime = media.anime != null
            val mediaType = if (isAnime) "anime" else "manga"

            val searchRes = runCatching {
                client.get(
                    "${Comick.apiHost}/v1.0/search/?q=${java.net.URLEncoder.encode(title, "UTF-8")}&limit=10&media_type=$mediaType"
                ).parsed<List<ComickSearchItem>>()
            }.getOrNull() ?: return@tryWithSuspend null

            var matchedHid: String? = null

            for (item in searchRes) {
                val slug = item.slug ?: item.hid ?: continue
                val detail = runCatching {
                    client.get("${Comick.apiHost}/v1.0/comic/$slug/?media_type=$mediaType")
                        .parsed<ComickDetailResponse>()
                }.getOrNull()

                val links = detail?.comic?.links
                if (links?.al == media.id.toString() || (media.idMAL != null && links?.mal == media.idMAL.toString())) {
                    matchedHid = detail?.comic?.hid ?: item.hid
                    break
                }
            }

            if (matchedHid == null && searchRes.isNotEmpty()) {
                matchedHid = searchRes.first().hid
            }

            if (!matchedHid.isNullOrBlank()) {
                PrefManager.setCustomVal("comick_hid_${media.id}", matchedHid)
            }
            matchedHid
        }
    }

    suspend fun updateProgress(media: Media, progress: Int): Boolean {
        if (Comick.token == null) return false
        val hid = findComickHid(media) ?: return false

        return tryWithSuspend(false) {
            val isDone = media.userStatus == "COMPLETED"
            val statusVal = if (isDone) 2 else 1
            val body = mapOf(
                "status" to statusVal,
                "progress" to mapOf("number" to "$progress")
            )
            writeLibraryEntry(hid, body)
        } ?: false
    }

    suspend fun editList(
        media: Media,
        status: String?,
        progress: Int?,
        score: Double?
    ): Boolean {
        if (Comick.token == null) return false
        val hid = findComickHid(media) ?: return false

        return tryWithSuspend(false) {
            val comickStatus = when (status) {
                "CURRENT" -> 1
                "COMPLETED" -> 2
                "PAUSED" -> 3
                "DROPPED" -> 4
                "PLANNING" -> 5
                else -> 1
            }
            val body = mutableMapOf<String, Any>("status" to comickStatus)
            if (progress != null && progress >= 0) {
                body["progress"] = mapOf("number" to "$progress")
            }
            if (score != null && score > 0) {
                val ratingVal = if (score > 10.0) {
                    (score / 10.0).roundToInt().coerceIn(1, 10)
                } else {
                    score.roundToInt().coerceIn(1, 10)
                }
                body["rating"] = ratingVal
            }
            writeLibraryEntry(hid, body)
        } ?: false
    }

    private suspend fun writeLibraryEntry(hid: String, body: Map<String, Any>): Boolean {
        val headers = getAuthHeaders() + ("Content-Type" to "application/json")
        val path = "${Comick.resource}/me/library/$hid"
        return try {
            val patchRes = runCatching {
                client.patch(path, headers = headers, json = body)
            }
            if (patchRes.isSuccess && patchRes.getOrNull()?.code in 200..299) {
                true
            } else {
                val putRes = client.put(path, headers = headers, json = body)
                putRes.code in 200..299
            }
        } catch (e: Exception) {
            Logger.log("Comick write error: ${e.message}")
            false
        }
    }
}
