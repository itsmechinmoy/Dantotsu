package ani.dantotsu.connections.discord

/**
 * Shared Rich Presence data types used by [RPCManager] and calling screens.
 */
@Suppress("MemberVisibilityCanBePrivate")
class RPC {

    enum class Type {
        PLAYING, STREAMING, LISTENING, WATCHING, COMPETING
    }

    data class Link(val label: String, val url: String)

    companion object {
        data class RPCData(
            val applicationId: String,
            val type: Type? = null,
            val activityName: String? = null,
            val details: String? = null,
            val state: String? = null,
            val largeImage: Link? = null,
            val smallImage: Link? = null,
            val status: String? = null,
            val startTimestamp: Long? = null,
            val stopTimestamp: Long? = null,
            val buttons: MutableList<Link> = mutableListOf()
        )
    }
}
