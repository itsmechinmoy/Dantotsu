package ani.dantotsu.connections.discord.rpc

/** Activity payload sent to the Discord app. Lengths are capped by [DiscordRpcCodec]. */
data class DiscordPresence(
    val name: String,
    val type: DiscordActivityType = DiscordActivityType.WATCHING,
    val details: String? = null,
    val state: String? = null,
    val largeImageKey: String? = null,
    val largeImageText: String? = null,
    val smallImageKey: String? = null,
    val smallImageText: String? = null,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val buttons: List<DiscordPresenceButton> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Discord presence name must not be blank" }
        require(buttons.size <= 2) { "Discord allows at most 2 buttons (got ${buttons.size})" }
    }
}

data class DiscordPresenceButton(
    val label: String,
    val url: String,
)

enum class DiscordActivityType(val value: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    CUSTOM_STATUS(4),
    COMPETING(5),
    HANG_STATUS(6),
}

interface DiscordRpcLogger {
    fun log(message: String)
    fun warn(message: String)
}

object NoopDiscordRpcLogger : DiscordRpcLogger {
    override fun log(message: String) = Unit
    override fun warn(message: String) = Unit
}
