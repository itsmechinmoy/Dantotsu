package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.Anilist.executeQuery
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.connections.anilist.api.ToggleLike
import ani.dantotsu.currContext
import com.google.gson.Gson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class AnilistMutations {

    suspend fun updateSettings(
        timezone: String? = null,
        titleLanguage: String? = null,
        staffNameLanguage: String? = null,
        activityMergeTime: Int? = null,
        airingNotifications: Boolean? = null,
        displayAdultContent: Boolean? = null,
        restrictMessagesToFollowing: Boolean? = null,
        scoreFormat: String? = null,
        rowOrder: String? = null,
    ) {
        val query = """
            mutation (
                ${"$"}timezone: String,
                ${"$"}titleLanguage: UserTitleLanguage,
                ${"$"}staffNameLanguage: UserStaffNameLanguage,
                ${"$"}activityMergeTime: Int,
                ${"$"}airingNotifications: Boolean,
                ${"$"}displayAdultContent: Boolean,
                ${"$"}restrictMessagesToFollowing: Boolean,
                ${"$"}scoreFormat: ScoreFormat,
                ${"$"}rowOrder: String
            ) {
                UpdateUser(
                    timezone: ${"$"}timezone,
                    titleLanguage: ${"$"}titleLanguage,
                    staffNameLanguage: ${"$"}staffNameLanguage,
                    activityMergeTime: ${"$"}activityMergeTime,
                    airingNotifications: ${"$"}airingNotifications,
                    displayAdultContent: ${"$"}displayAdultContent,
                    restrictMessagesToFollowing: ${"$"}restrictMessagesToFollowing,
                    scoreFormat: ${"$"}scoreFormat,
                    rowOrder: ${"$"}rowOrder,
                ) {
                    id
                    options {
                        timezone
                        titleLanguage
                        staffNameLanguage
                        activityMergeTime
                        airingNotifications
                        displayAdultContent
                        restrictMessagesToFollowing
                    }
                    mediaListOptions {
                      scoreFormat
                      rowOrder
                    }
                }
            }
        """.trimIndent()

        val variables = """
            {
                ${timezone?.let { """"timezone":"$it"""" } ?: ""}
                ${titleLanguage?.let { """"titleLanguage":"$it"""" } ?: ""}
                ${staffNameLanguage?.let { """"staffNameLanguage":"$it"""" } ?: ""}
                ${activityMergeTime?.let { """"activityMergeTime":$it""" } ?: ""}
                ${airingNotifications?.let { """"airingNotifications":$it""" } ?: ""}
                ${displayAdultContent?.let { """"displayAdultContent":$it""" } ?: ""}
                ${restrictMessagesToFollowing?.let { """"restrictMessagesToFollowing":$it""" } ?: ""}
                ${scoreFormat?.let { """"scoreFormat":"$it"""" } ?: ""}
                ${rowOrder?.let { """"rowOrder":"$it"""" } ?: ""}
            }
        """.trimIndent().replace("\n", "").replace("""    """, "").replace(",}", "}")

        executeQuery<JsonObject>(query, variables)
    }

    suspend fun toggleFav(anime: Boolean = true, id: Int) {
        val query = """
            mutation (${"$"}animeId: Int, ${"$"}mangaId: Int) {
                ToggleFavourite(animeId: ${"$"}animeId, mangaId: ${"$"}mangaId) {
                    anime {
                        edges {
                            id
                        }
                    }
                    manga {
                        edges {
                            id
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = if (anime) """{"animeId":"$id"}""" else """{"mangaId":"$id"}"""
        executeQuery<JsonObject>(query, variables)
    }

    suspend fun toggleFav(type: FavType, id: Int): Boolean {
        val filter = when (type) {
            FavType.ANIME -> "animeId"
            FavType.MANGA -> "mangaId"
            FavType.CHARACTER -> "characterId"
            FavType.STAFF -> "staffId"
            FavType.STUDIO -> "studioId"
        }
        val query = """
            mutation {
                ToggleFavourite($filter: $id) {
                    anime {
                        pageInfo {
                            total
                        }
                    }
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null && result != null
    }

    enum class FavType {
        ANIME, MANGA, CHARACTER, STAFF, STUDIO
    }

    suspend fun deleteCustomList(name: String, type: String): Boolean {
        val query = """
            mutation (${"$"}name: String, ${"$"}type: MediaType) {
                DeleteCustomList(customList: ${"$"}name, type: ${"$"}type) {
                    deleted
                }
            }
        """.trimIndent()
        val variables = """
            {
                "name": "$name",
                "type": "$type"
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query, variables)
        return result?.get("errors") == null
    }

    suspend fun updateCustomLists(
        animeCustomLists: List<String>?,
        mangaCustomLists: List<String>?
    ): Boolean {
        val query = """
            mutation (${"$"}animeListOptions: MediaListOptionsInput, ${"$"}mangaListOptions: MediaListOptionsInput) {
                UpdateUser(animeListOptions: ${"$"}animeListOptions, mangaListOptions: ${"$"}mangaListOptions) {
                    mediaListOptions {
                        animeList {
                            customLists
                        }
                        mangaList {
                            customLists
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = """
            {
                ${animeCustomLists?.let { """"animeListOptions": {"customLists": ${Gson().toJson(it)}}""" } ?: ""}
                ${if (animeCustomLists != null && mangaCustomLists != null) "," else ""}
                ${mangaCustomLists?.let { """"mangaListOptions": {"customLists": ${Gson().toJson(it)}}""" } ?: ""}
            }
        """.trimIndent().replace("\n", "").replace("""    """, "").replace(",}", "}")

        val result = executeQuery<JsonObject>(query, variables)
        return result?.get("errors") == null
    }

    suspend fun editList(
        mediaID: Int,
        progress: Int? = null,
        progressVolumes: Int? = null,
        score: Int? = null,
        repeat: Int? = null,
        notes: String? = null,
        status: String? = null,
        private: Boolean? = null,
        priority: Int? = null,
        hiddenFromStatusLists: Boolean? = null,
        startedAt: FuzzyDate? = null,
        completedAt: FuzzyDate? = null,
        customList: List<String>? = null,
        advancedScores: List<Double>? = null
    ) {
        val headerParams = mutableListOf(
            "${"$"}mediaID: Int",
            "${"$"}progress: Int",
            "${"$"}progressVolumes: Int",
            "${"$"}private: Boolean",
            "${"$"}priority: Int",
            "${"$"}hiddenFromStatusLists: Boolean",
            "${"$"}repeat: Int",
            "${"$"}notes: String",
            "${"$"}customLists: [String]",
            "${"$"}scoreRaw: Int",
            "${"$"}status: MediaListStatus"
        )
        val entryArgs = mutableListOf(
            "mediaId: ${"$"}mediaID",
            "progress: ${"$"}progress",
            "progressVolumes: ${"$"}progressVolumes",
            "repeat: ${"$"}repeat",
            "notes: ${"$"}notes",
            "private: ${"$"}private",
            "priority: ${"$"}priority",
            "hiddenFromStatusLists: ${"$"}hiddenFromStatusLists",
            "scoreRaw: ${"$"}scoreRaw",
            "status: ${"$"}status",
            "customLists: ${"$"}customLists"
        )

        if (startedAt != null) {
            headerParams.add("${"$"}start: FuzzyDateInput = ${startedAt.toVariableString()}")
            entryArgs.add("startedAt: ${"$"}start")
        }
        if (completedAt != null) {
            headerParams.add("${"$"}completed: FuzzyDateInput = ${completedAt.toVariableString()}")
            entryArgs.add("completedAt: ${"$"}completed")
        }
        if (advancedScores != null) {
            headerParams.add("${"$"}advancedScores: [Float]")
            entryArgs.add("advancedScores: ${"$"}advancedScores")
        }

        val query = """
            mutation (
                ${headerParams.joinToString(",\n                ")}
            ) {
                SaveMediaListEntry(
                    ${entryArgs.joinToString(",\n                    ")}
                ) {
                    score(format: POINT_10_DECIMAL)
                    startedAt {
                        year
                        month
                        day
                    }
                    completedAt {
                        year
                        month
                        day
                    }
                }
            }
        """.trimIndent()

        val variables = """{"mediaID":$mediaID
            ${if (private != null) ""","private":$private""" else ""}
            ${if (priority != null) ""","priority":$priority""" else ""}
            ${if (hiddenFromStatusLists != null) ""","hiddenFromStatusLists":$hiddenFromStatusLists""" else ""}
            ${if (progress != null) ""","progress":$progress""" else ""}
            ${if (progressVolumes != null) ""","progressVolumes":$progressVolumes""" else ""}
            ${if (score != null) ""","scoreRaw":$score""" else ""}
            ${if (repeat != null) ""","repeat":$repeat""" else ""}
            ${if (notes != null) ""","notes":"${notes.replace("\n", "\\n")}"""" else ""}
            ${if (status != null) ""","status":"$status"""" else ""}
            ${if (customList != null) ""","customLists":[${customList.joinToString { "\"$it\"" }}]""" else ""}
            ${if (advancedScores != null) ""","advancedScores":[${advancedScores.joinToString(",")}]""" else ""}
            }""".replace("\n", "").replace("""    """, "")
        println(variables)
        executeQuery<JsonObject>(query, variables, show = true)
        Anilist.query.invalidateUserStatusCache()
        Anilist.query.invalidateHomePageCache()
    }

    suspend fun deleteList(listId: Int) {
        val query = """
            mutation(${"$"}id: Int) {
                DeleteMediaListEntry(id: ${"$"}id) {
                    deleted
                }
            }
        """.trimIndent()
        val variables = """{"id":"$listId"}"""
        executeQuery<JsonObject>(query, variables)
        Anilist.query.invalidateUserStatusCache()
        Anilist.query.invalidateHomePageCache()
    }

    suspend fun rateReview(reviewId: Int, rating: String): Query.RateReviewResponse? {
        val query = """
            mutation {
                RateReview(reviewId: $reviewId, rating: $rating) {
                    id
                    mediaId
                    mediaType
                    summary
                    body(asHtml: true)
                    rating
                    ratingAmount
                    userRating
                    score
                    private
                    siteUrl
                    createdAt
                    updatedAt
                    user {
                        id
                        name
                        bannerImage
                        avatar {
                            medium
                            large
                        }
                    }
                }
            }
        """.trimIndent()
        return executeQuery<Query.RateReviewResponse>(query)
    }

    suspend fun toggleFollow(id: Int): Query.ToggleFollow? {
        return executeQuery<Query.ToggleFollow>(
            """
            mutation {
                ToggleFollow(userId: $id) {
                    id
                    isFollowing
                    isFollower
                }
            }
        """.trimIndent()
        )
    }

    suspend fun toggleBlock(userId: Int): Boolean {
        val query = """
            mutation {
                ToggleBlock(userId: $userId) {
                    id
                    isBlocked
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null && result != null
    }

    suspend fun toggleLike(id: Int, type: String): ToggleLike? {
        return executeQuery<ToggleLike>(
            """
            mutation Like {
                ToggleLikeV2(id: $id, type: $type) {
                    __typename
                }
            }
        """.trimIndent()
        )
    }

    suspend fun toggleActivitySubscription(activityId: Int, subscribe: Boolean): Boolean {
        val result = executeQuery<JsonObject>(
            """
            mutation {
                ToggleActivitySubscription(activityId: $activityId, subscribe: $subscribe) {
                    __typename
                }
            }
        """.trimIndent()
        )
        val errors = result?.get("errors") as? JsonArray

        if (result != null && errors.isNullOrEmpty()) {
            Anilist.query.invalidateUserStatusCache()
            return true
        }
        return false
    }

    suspend fun toggleActivityPin(activityId: Int, pinned: Boolean): Boolean {
        val result = executeQuery<JsonObject>(
            """
            mutation {
                ToggleActivityPin(id: $activityId, pinned: $pinned) {
                    __typename
                }
            }
        """.trimIndent()
        )
        val errors = result?.get("errors") as? JsonArray
        if (result != null && errors.isNullOrEmpty()) {
            Anilist.query.invalidateUserStatusCache()
            return true
        }
        return false
    }

    suspend fun postActivity(text: String, edit: Int? = null): String {
        val encodedText = text.stringSanitizer()
        val query = """
            mutation {
                SaveTextActivity(${if (edit != null) "id: $edit," else ""} text: $encodedText) {
                    siteUrl
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        if (errors == null && result != null) {
            Anilist.query.invalidateUserStatusCache()
        }
        return errors?.toString() ?: (currContext()?.getString(ani.dantotsu.R.string.success)
            ?: "Success")
    }

    suspend fun postMessage(
        userId: Int,
        text: String,
        edit: Int? = null,
        isPrivate: Boolean = false
    ): String {
        val encodedText = text.replace("", "").stringSanitizer()
        val query = """
            mutation {
                SaveMessageActivity(
                    ${if (edit != null) "id: $edit," else ""}
                    recipientId: $userId,
                    message: $encodedText,
                    private: $isPrivate
                ) {
                    id
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: (currContext()?.getString(ani.dantotsu.R.string.success)
            ?: "Success")
    }

    suspend fun postReply(activityId: Int, text: String, edit: Int? = null): String {
        val encodedText = text.stringSanitizer()
        val query = """
            mutation {
                SaveActivityReply(
                    ${if (edit != null) "id: $edit," else ""}
                    activityId: $activityId,
                    text: $encodedText
                ) {
                    id
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: (currContext()?.getString(ani.dantotsu.R.string.success)
            ?: "Success")
    }

    suspend fun postReview(
        summary: String,
        body: String,
        mediaId: Int,
        score: Int,
        edit: Int? = null,
        isPrivate: Boolean = false
    ): String {
        val encodedSummary = summary.stringSanitizer()
        val encodedBody = body.stringSanitizer()
        val query = """
            mutation {
                SaveReview(
                    ${if (edit != null) "id: $edit," else ""}
                    mediaId: $mediaId,
                    summary: $encodedSummary,
                    body: $encodedBody,
                    score: $score,
                    private: $isPrivate
                ) {
                    siteUrl
                    id
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: (currContext()?.getString(ani.dantotsu.R.string.success)
            ?: "Success")
    }

    suspend fun deleteReview(reviewId: Int): Boolean {
        val query = """
            mutation {
                DeleteReview(id: $reviewId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun saveRecommendation(
        mediaId: Int,
        mediaRecommendationId: Int,
        rating: String? = null
    ): Boolean {
        val ratingArg = if (rating != null) ", rating: $rating" else ""
        val query = """
            mutation {
                SaveRecommendation(
                    mediaId: $mediaId,
                    mediaRecommendationId: $mediaRecommendationId
                    $ratingArg
                ) {
                    id
                    rating
                    userRating
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun saveThread(
        title: String,
        body: String,
        categories: List<Int>? = null,
        mediaCategories: List<Int>? = null,
        edit: Int? = null
    ): String {
        val encodedTitle = title.stringSanitizer()
        val encodedBody = body.stringSanitizer()
        val categoriesArg = if (!categories.isNullOrEmpty()) ", categories: [${categories.joinToString(",")}]" else ""
        val mediaCategoriesArg = if (!mediaCategories.isNullOrEmpty()) ", mediaCategories: [${mediaCategories.joinToString(",")}]" else ""
        val query = """
            mutation {
                SaveThread(
                    ${if (edit != null) "id: $edit," else ""}
                    title: $encodedTitle,
                    body: $encodedBody
                    $categoriesArg
                    $mediaCategoriesArg
                ) {
                    id
                    siteUrl
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: (currContext()?.getString(ani.dantotsu.R.string.success)
            ?: "Success")
    }

    suspend fun deleteThread(threadId: Int): Boolean {
        val query = """
            mutation {
                DeleteThread(id: $threadId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun saveThreadComment(
        threadId: Int,
        comment: String,
        parentCommentId: Int? = null,
        edit: Int? = null
    ): String {
        val encodedComment = comment.stringSanitizer()
        val parentArg = if (parentCommentId != null) ", parentCommentId: $parentCommentId" else ""
        val query = """
            mutation {
                SaveThreadComment(
                    ${if (edit != null) "id: $edit," else ""}
                    threadId: $threadId,
                    comment: $encodedComment
                    $parentArg
                ) {
                    id
                    siteUrl
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: (currContext()?.getString(ani.dantotsu.R.string.success)
            ?: "Success")
    }

    suspend fun deleteThreadComment(commentId: Int): Boolean {
        val query = """
            mutation {
                DeleteThreadComment(id: $commentId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun toggleThreadSubscription(threadId: Int, subscribe: Boolean): Boolean {
        val query = """
            mutation {
                ToggleThreadSubscription(threadId: $threadId, subscribe: $subscribe) {
                    id
                    isSubscribed
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun updateUserBio(about: String): Boolean {
        val encodedAbout = about.stringSanitizer()
        val query = """
            mutation {
                UpdateUser(about: $encodedAbout) {
                    id
                    about
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null && result != null
    }

    suspend fun deleteActivityReply(activityId: Int): Boolean {
        val query = """
            mutation {
                DeleteActivityReply(id: $activityId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors == null
    }

    suspend fun deleteActivity(activityId: Int): Boolean {
        val query = """
            mutation {
                DeleteActivity(id: $activityId) {
                    deleted
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        if (errors == null && result != null) {
            Anilist.query.invalidateUserStatusCache()
        }
        return errors == null
    }

    private fun String.stringSanitizer(): String {
        val sb = StringBuilder()
        var i = 0
        while (i < this.length) {
            val codePoint = this.codePointAt(i)
            if (codePoint > 0xFFFF) {
                sb.append("&#").append(codePoint).append(";")
                i += 2
            } else {
                sb.append(this[i])
                i++
            }
        }
        return Gson().toJson(sb.toString())
    }
}
