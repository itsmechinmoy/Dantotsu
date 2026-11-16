package ani.dantotsu.connections.discord

/**
 * Picks the Rich Presence small image and text: watching, paused or reading.
 */
object DiscordPresenceMapper {

    enum class Activity { WATCHING, PAUSED, READING }

    data class SmallImage(val url: String, val text: String)

    // TODO: CDN URLs of the app's Discord art assets
    //  (https://cdn.discordapp.com/app-assets/<application id>/<asset id>.png).
    //  Empty falls back to Discord.small_Image.
    const val WATCHING_SMALL_IMAGE_URL = ""
    const val PAUSED_SMALL_IMAGE_URL = ""
    const val READING_SMALL_IMAGE_URL = ""

    const val WATCHING_SMALL_TEXT = "Watching on Dantotsu"
    const val PAUSED_SMALL_TEXT = "Paused on Dantotsu"
    const val READING_SMALL_TEXT = "Reading on Dantotsu"

    /** Null when not watching or reading (browsing). */
    fun activityOf(type: RPC.Type?, isManga: Boolean, isPaused: Boolean): Activity? = when {
        type != RPC.Type.WATCHING -> null
        isManga -> Activity.READING
        isPaused -> Activity.PAUSED
        else -> Activity.WATCHING
    }

    fun smallImage(activity: Activity?): SmallImage = when (activity) {
        Activity.WATCHING -> SmallImage(WATCHING_SMALL_IMAGE_URL.orLogo(), WATCHING_SMALL_TEXT)
        Activity.PAUSED -> SmallImage(PAUSED_SMALL_IMAGE_URL.orLogo(), PAUSED_SMALL_TEXT)
        Activity.READING -> SmallImage(READING_SMALL_IMAGE_URL.orLogo(), READING_SMALL_TEXT)
        null -> SmallImage(Discord.small_Image, "Dantotsu")
    }

    private fun String.orLogo() = ifBlank { Discord.small_Image }
}
