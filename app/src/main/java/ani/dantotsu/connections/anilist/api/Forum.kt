package ani.dantotsu.connections.anilist.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ForumThreadsResponse(
    @SerialName("data")
    val data: Data? = null
) : java.io.Serializable {
    @Serializable
    data class Data(
        @SerialName("Page")
        val page: ThreadPage? = null,
        @SerialName("Thread")
        val thread: ForumThread? = null
    ) : java.io.Serializable
}

@Serializable
data class ThreadCommentsResponse(
    @SerialName("data")
    val data: Data? = null
) : java.io.Serializable {
    @Serializable
    data class Data(
        @SerialName("Page")
        val page: ThreadCommentsPage? = null
    ) : java.io.Serializable
}

@Serializable
data class ThreadPage(
    @SerialName("pageInfo")
    val pageInfo: PageInfo? = null,
    @SerialName("threads")
    val threads: List<ForumThread> = emptyList()
) : java.io.Serializable

@Serializable
data class ThreadCommentsPage(
    @SerialName("pageInfo")
    val pageInfo: PageInfo? = null,
    @SerialName("threadComments")
    val threadComments: List<ThreadComment> = emptyList()
) : java.io.Serializable

@Serializable
data class ThreadUser(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String? = null,
    @SerialName("avatar") val avatar: UserAvatar? = null,
    @SerialName("bannerImage") val bannerImage: String? = null,
) : java.io.Serializable

@Serializable
data class ThreadMedia(
    @SerialName("id") val id: Int = 0,
    @SerialName("title") val title: ThreadMediaTitle? = null,
    @SerialName("coverImage") val coverImage: ThreadMediaCoverImage? = null,
) : java.io.Serializable

@Serializable
data class ThreadMediaTitle(
    @SerialName("userPreferred") val userPreferred: String? = null,
    @SerialName("romaji") val romaji: String? = null,
    @SerialName("english") val english: String? = null,
) : java.io.Serializable

@Serializable
data class ThreadMediaCoverImage(
    @SerialName("medium") val medium: String? = null,
    @SerialName("large") val large: String? = null,
) : java.io.Serializable

@Serializable
data class ForumThread(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("title")
    val title: String? = null,
    @SerialName("body")
    val body: String? = null,
    @SerialName("userId")
    val userId: Int? = null,
    @SerialName("replyCount")
    var replyCount: Int? = null,
    @SerialName("viewCount")
    val viewCount: Int? = null,
    @SerialName("isLocked")
    val isLocked: Boolean? = null,
    @SerialName("isSticky")
    val isSticky: Boolean? = null,
    @SerialName("isSubscribed")
    var isSubscribed: Boolean? = null,
    @SerialName("isLiked")
    var isLiked: Boolean? = null,
    @SerialName("likeCount")
    var likeCount: Int? = null,
    @SerialName("createdAt")
    val createdAt: Int? = null,
    @SerialName("updatedAt")
    val updatedAt: Int? = null,
    @SerialName("siteUrl")
    val siteUrl: String? = null,
    @SerialName("user")
    val user: ThreadUser? = null,
    @SerialName("categories")
    val categories: List<ThreadCategory>? = null,
    @SerialName("mediaCategories")
    val mediaCategories: List<ThreadMedia>? = null
) : java.io.Serializable

@Serializable
data class ThreadCategory(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("name")
    val name: String? = null
) : java.io.Serializable

@Serializable
data class ThreadComment(
    @SerialName("id")
    val id: Int = 0,
    @SerialName("userId")
    val userId: Int? = null,
    @SerialName("threadId")
    val threadId: Int? = null,
    @SerialName("comment")
    val comment: String? = null,
    @SerialName("isLocked")
    val isLocked: Boolean? = null,
    @SerialName("isLiked")
    var isLiked: Boolean? = null,
    @SerialName("likeCount")
    var likeCount: Int? = null,
    @SerialName("createdAt")
    val createdAt: Int? = null,
    @SerialName("updatedAt")
    val updatedAt: Int? = null,
    @SerialName("siteUrl")
    val siteUrl: String? = null,
    @SerialName("user")
    val user: ThreadUser? = null,
    @SerialName("childComments")
    val childComments: JsonElement? = null
) : java.io.Serializable
