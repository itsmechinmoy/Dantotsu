package ani.dantotsu.connections.discord.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Activity payload for Discord Headless Sessions API.
 * POST https://discord.com/api/v10/users/@me/headless-sessions
 *
 * Modelled on brahmkshatriya/echo-discord Activity.kt
 */
@Serializable
data class DiscordActivity(
    @SerialName("application_id")
    val applicationId: String? = null,

    val name: String? = null,
    val platform: String? = "android",
    val type: Int? = null,

    /** 0 = activity name, 1 = artist name, 2 = track name */
    @SerialName("status_display_type")
    val statusDisplayType: Int? = null,

    val details: String? = null,

    @SerialName("details_url")
    val detailsUrl: String? = null,

    val state: String? = null,

    @SerialName("state_url")
    val stateUrl: String? = null,

    val assets: Assets? = null,
    val timestamps: Timestamps? = null,
    val buttons: List<Button>? = null,
) {
    @Serializable
    data class Assets(
        @SerialName("large_text")
        val largeText: String? = null,

        @SerialName("large_image")
        val largeImage: String? = null,

        @SerialName("large_url")
        val largeUrl: String? = null,

        @SerialName("small_image")
        val smallImage: String? = null,

        @SerialName("small_url")
        val smallUrl: String? = null,

        @SerialName("small_text")
        val smallText: String? = null,
    )

    @Serializable
    data class Button(
        val label: String? = null,
        val url: String? = null,
    )

    @Serializable
    data class Timestamps(
        val start: Long? = null,
        val end: Long? = null,
    )
}

/**
 * Wrapper for the headless sessions POST body.
 */
@Serializable
data class DiscordSession(
    val activities: List<DiscordActivity>? = null,
    /** Session token returned by a previous call; null for first call */
    val token: String? = null,
)
