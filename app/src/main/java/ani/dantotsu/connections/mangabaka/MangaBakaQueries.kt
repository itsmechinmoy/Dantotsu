package ani.dantotsu.connections.mangabaka

import ani.dantotsu.client
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger

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
            // Try AniList mapping first
            var seriesId: Long? = null
            if (media.id > 0) {
                val res = runCatching {
                    client.get("${MangaBaka.apiHost}/source/anilist/${media.id}")
                        .parsed<MangaBakaSourceResponse>()
                }.getOrNull()
                seriesId = res?.data?.series?.firstOrNull()?.id
            }

            // Fallback to MyAnimeList mapping if available
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
        val headers = getAuthHeaders()
        if (headers.isEmpty()) return false

        val path = "${MangaBaka.apiHost}/my/library/$seriesId"
        return try {
            val putRes = runCatching {
                client.put(path, headers = headers, data = body)
            }
            if (putRes.isSuccess && putRes.getOrNull()?.code in 200..299) {
                true
            } else {
                val postRes = client.post(path, headers = headers, data = body)
                postRes.code in 200..299
            }
        } catch (e: Exception) {
            Logger.log("MangaBaka write error: ${e.message}")
            false
        }
    }
}
