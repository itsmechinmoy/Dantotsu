package ani.dantotsu.connections.comick

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComickToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") var expiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null
) : java.io.Serializable

@Serializable
data class ComickUser(
    val id: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
) : java.io.Serializable

@Serializable
data class ComickSearchItem(
    val hid: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val md_titles: List<ComickMdTitle>? = null,
    val links: ComickLinks? = null
) : java.io.Serializable

@Serializable
data class ComickMdTitle(
    val title: String? = null
) : java.io.Serializable

@Serializable
data class ComickLinks(
    val al: String? = null,
    val mal: String? = null
) : java.io.Serializable

@Serializable
data class ComickDetailResponse(
    val comic: ComickComicDetail? = null
) : java.io.Serializable

@Serializable
data class ComickComicDetail(
    val hid: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val links: ComickLinks? = null
) : java.io.Serializable
