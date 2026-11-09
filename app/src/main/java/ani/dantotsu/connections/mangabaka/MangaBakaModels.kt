package ani.dantotsu.connections.mangabaka

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaBakaToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") var expiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null
) : java.io.Serializable

@Serializable
data class MangaBakaProfileResponse(
    val data: MangaBakaProfileData? = null
)

@Serializable
data class MangaBakaProfileData(
    val id: String? = null,
    val nickname: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    val picture: String? = null
)

@Serializable
data class MangaBakaUserInfo(
    val sub: String? = null,
    val name: String? = null,
    val picture: String? = null
)

@Serializable
data class MangaBakaSourceResponse(
    val data: MangaBakaSourceData? = null
)

@Serializable
data class MangaBakaSourceData(
    val series: List<MangaBakaSeriesRef>? = null
)

@Serializable
data class MangaBakaSeriesRef(
    val id: Long
)

@Serializable
data class MangaBakaLibraryEntryResponse(
    val data: MangaBakaLibraryEntry? = null
)

@Serializable
data class MangaBakaLibraryEntry(
    val state: String? = null,
    val rating: Double? = null,
    @SerialName("progress_chapter") val progressChapter: Int? = null,
    @SerialName("number_of_rereads") val numberOfRereads: Int? = null,
    val note: String? = null
)
