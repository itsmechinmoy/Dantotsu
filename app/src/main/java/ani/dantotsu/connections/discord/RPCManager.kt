package ani.dantotsu.connections.discord

import android.content.Context
import ani.dantotsu.connections.discord.rpc.DiscordActivityType
import ani.dantotsu.connections.discord.rpc.DiscordPresence
import ani.dantotsu.connections.discord.rpc.DiscordPresenceButton
import ani.dantotsu.connections.discord.rpc.DiscordRpcClient
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Rich Presence facade. Sends through the installed Discord app ([DiscordRpcClient]),
 * so no Discord login or token is needed. Without the Discord app nothing is shown.
 */
object RPCManager {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var client: DiscordRpcClient? = null

    /** Debounce job — only the last setPresence within 500ms fires. */
    private var debounceJob: Job? = null
    private const val DEBOUNCE_MS = 500L

    /** Auto-clear job — clears the RPC if the video is left paused for too long. */
    private var autoClearJob: Job? = null
    private const val AUTO_CLEAR_INTERVAL_MS = 5 * 60 * 1000L

    /** Screen that set the current presence; a clear from any other screen is ignored. */
    @Volatile
    private var owner: WeakReference<Any>? = null

    fun isAvailable(context: Context): Boolean = DiscordRpcClient.isAvailable(context)

    /**
     * Set / update Discord Rich Presence.
     *
     * @param data    The presence data built by the calling screen
     * @param owner   The calling screen, so a late clear from a closing screen can't wipe a newer presence
     */
    fun setPresence(context: Context, data: RPC.Companion.RPCData, owner: Any? = null) {
        this.owner = owner?.let(::WeakReference)
        val appContext = context.applicationContext
        debounceJob?.cancel()
        autoClearJob?.cancel()
        if (!isAvailable(appContext)) return
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val presence = runCatching { buildPresence(data) }
                .onFailure { Logger.log("RPCManager: bad presence - ${it.message}") }
                .getOrNull() ?: return@launch
            val rpc = client ?: DiscordRpcClient(appContext, Discord.application_Id.toLong())
                .also { client = it }
            rpc.setPresence(presence)

            if (data.state?.contains("Paused", ignoreCase = true) == true) {
                autoClearJob = scope.launch {
                    delay(AUTO_CLEAR_INTERVAL_MS)
                    Logger.log("RPCManager: clearing presence after a long pause")
                    clearPresence(appContext)
                }
            }
        }
    }

    /**
     * Clear / stop Discord Rich Presence and release the connection to the Discord app.
     * With [owner], only clears if that screen set the current presence.
     */
    fun clearPresence(context: Context, owner: Any? = null) {
        if (owner != null && owner !== this.owner?.get()) return
        this.owner = null
        debounceJob?.cancel()
        autoClearJob?.cancel()
        scope.launch {
            client?.shutdown()
            client = null
        }
    }

    fun shouldSuppressForAdultMedia(isAdultMedia: Boolean): Boolean {
        return isAdultMedia && PrefManager.getVal(PrefName.DiscordRPCDisableAdultMedia, false)
    }

    // ─── Private helpers ──────────────────────────────────────────────────────────────────

    /** Discord fetches external image URLs itself, so covers are sent as URLs. */
    private fun buildPresence(data: RPC.Companion.RPCData): DiscordPresence {
        val isManga = data.type == RPC.Type.WATCHING &&
                (data.state?.contains("Reading", ignoreCase = true) == true ||
                        data.state?.contains("Chapter", ignoreCase = true) == true)

        val mode = if (isManga) PrefManager.getVal(PrefName.DiscordRPCModeManga, "dantotsu") else PrefManager.getVal(PrefName.DiscordRPCModeAnime, "dantotsu")
        val useIconPref = if (isManga) PrefManager.getVal<Boolean>(PrefName.DiscordRPCShowIconManga, true) else PrefManager.getVal<Boolean>(PrefName.DiscordRPCShowIconAnime, true)

        val largeImage = data.largeImage?.url?.let { coverUrl(it) ?: Discord.small_Image }
        val showSmallIcon = useIconPref && mode != "nothing" && 
                            largeImage != null && 
                            largeImage != Discord.small_Image

        // Select Small Icon based on mode
        val (smallIconUrl, smallIconText) = if (showSmallIcon) {
            when (mode) {
                "anilist" -> Discord.small_Image_AniList to "AniList"
                "mal" -> Discord.small_Image_MAL to "MyAnimeList"
                else -> {
                    val isPaused = data.state?.contains("Paused", ignoreCase = true) == true
                    val activity = DiscordPresenceMapper.activityOf(data.type, isManga, isPaused)
                    DiscordPresenceMapper.smallImage(activity).let { it.url to it.text }
                }
            }
        } else {
            null to null
        }

        val buttons = buildButtons(data, mode)

        return DiscordPresence(
            name = data.activityName?.takeIf { it.isNotBlank() } ?: "Dantotsu",
            type = data.type?.let { DiscordActivityType.valueOf(it.name) } ?: DiscordActivityType.PLAYING,
            details = data.details,
            state = data.state,
            largeImageKey = largeImage,
            largeImageText = data.largeImage?.label,
            smallImageKey = smallIconUrl,
            smallImageText = smallIconText,
            startTimestamp = data.startTimestamp,
            endTimestamp = data.startTimestamp?.let { data.stopTimestamp },
            buttons = buttons.take(2),
        )
    }

    /** Covers Discord can fetch: http(s) with a real host, at most 300 chars (longer can't be cut). */
    private fun coverUrl(url: String): String? {
        val u = url.trim()
        if (u.length > 300) return null
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        return u.takeIf { uri.host?.contains('.') == true }
    }

    /** Validate that a URL is a proper http/https link. */
    private fun String?.isValidUrl(): Boolean {
        return this != null && isNotEmpty() &&
                (startsWith("http://") || startsWith("https://"))
    }
    private fun buildButtons(data: RPC.Companion.RPCData, mode: String): List<DiscordPresenceButton> {
        // Build Buttons based on mode
        val buttons = mutableListOf<DiscordPresenceButton>()
        if (mode != "nothing") {
            // Button 1: Media Link (Primary Tracker)
            val trackers = runCatching { data.buttons.filter { it.url.contains("anilist.co") || it.url.contains("myanimelist.net") } }.getOrDefault(emptyList())
            val primaryTracker = when (mode) {
                "mal" -> trackers.find { it.url.contains("myanimelist.net") } ?: trackers.find { it.url.contains("anilist.co") }
                else -> trackers.find { it.url.contains("anilist.co") } ?: trackers.find { it.url.contains("myanimelist.net") }
            }
            
            primaryTracker?.let {
                if (it.url.isValidUrl()) {
                    buttons.add(DiscordPresenceButton(label = it.label, url = it.url))
                }
            }

            // Button 2: User Profile Link
            val anilistUser = PrefManager.getVal(PrefName.AnilistUserName, "")
            val malUser = MAL.username ?: PrefManager.getVal(PrefName.MALUserName, "")
            
            val (userProfileUrl, profileLabel) = when (mode) {
                "mal" -> (if (malUser.isNotEmpty()) "https://myanimelist.net/profile/$malUser" else null) to "View Profile"
                "anilist" -> (if (anilistUser.isNotEmpty()) "https://anilist.co/user/$anilistUser/" else null) to "View Profile"
                "dantotsu" -> (if (anilistUser.isNotEmpty()) "https://dantotsu.app/u/$anilistUser" else null) to "Dantotsu Profile"
                else -> null to null
            }

            userProfileUrl?.let { url ->
                if (url.isValidUrl()) {
                    buttons.add(DiscordPresenceButton(label = profileLabel ?: "View Profile", url = url))
                }
            }
        }


        return buttons
    }

}
