package ani.dantotsu.connections.discord

/**
 * Headless Sessions API implementation ported and adapted from:
 * https://github.com/brahmkshatriya/echo-discord
 */

import android.content.Context
import ani.dantotsu.connections.discord.models.DiscordActivity
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
import java.io.File

/**
 * Headless Rich Presence facade.
 *
 * Uses the Discord Headless Sessions API (pure HTTP, no foreground service).
 * Requires an OAuth2 Bearer token with `activities.write` scope (managed by [TokenManager]).
 */
object RPCManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Lazily created HeadlessRPC instance; reset on user logout. */
    private var headlessRpc: HeadlessRPC? = null

    /** Debounce job — only the last setPresence within 500ms fires. */
    private var debounceJob: Job? = null
    private const val DEBOUNCE_MS = 500L

    /** Auto-clear job — clears stale presence after 2 minutes of no updates. */
    private var autoClearJob: Job? = null
    private const val AUTO_CLEAR_MS = 2 * 60 * 1000L

    // ΓöÇΓöÇΓöÇ Public API ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

    /**
     * Set / update Discord Rich Presence.
     *
     * @param context Android context (used to locate the token cache directory)
     * @param data    The presence data built by the calling screen
     */
    fun setPresence(context: Context, data: RPC.Companion.RPCData) {
        // Cancel any pending auto-clear since we're updating presence
        autoClearJob?.cancel()

        // Debounce: only the last call within 500ms actually fires
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            Logger.log("RPCManager: Attempting to use Headless RPC...")
            runCatching {
                ensureHeadlessRpc(context)?.newActivity(buildDiscordActivity(data))
                Logger.log("RPCManager: Headless RPC update succeeded.")
            }.onFailure { e ->
                Logger.log("RPCManager: HeadlessRPC failed \u2013 ${e.message}")
            }

            // Schedule auto-clear: if no new setPresence comes within 2 min, clear
            autoClearJob = scope.launch {
                delay(AUTO_CLEAR_MS)
                Logger.log("RPCManager: Auto-clearing stale presence after ${AUTO_CLEAR_MS / 1000}s")
                headlessRpc?.runCatching { clear() }
            }
        }
    }

    /**
     * Clear / stop Discord Rich Presence.
     */
    fun clearPresence(context: Context) {
        Logger.log("RPCManager: Clearing presence...")
        debounceJob?.cancel()
        autoClearJob?.cancel()
        scope.launch {
            headlessRpc?.runCatching { clear() }
        }
    }

    /**
     * Call this when the user logs out of Discord to release all resources.
     */
    fun reset() {
        debounceJob?.cancel()
        autoClearJob?.cancel()
        headlessRpc?.stop()
        headlessRpc = null
    }

    /**
     * Returns the token expiry timestamp in millis, or 0 if unknown/not logged in.
     */
    fun getTokenExpiresAt(): Long {
        return headlessRpc?.tokenManager?.getTokenExpiresAt() ?: 0L
    }

    // ΓöÇΓöÇΓöÇ Private helpers ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

    private fun ensureHeadlessRpc(context: Context): HeadlessRPC? {
        val token = Discord.token ?: return null
        if (headlessRpc == null || headlessRpc?.authToken != token) {
            headlessRpc?.stop()
            headlessRpc = HeadlessRPC(
                authToken = token,
                filesDir = File(context.filesDir, "discord"),
            )
        }
        return headlessRpc
    }

    /**
     * Convert [RPC.Companion.RPCData] to a [DiscordActivity] for the headless API.
     *
     * Discord resolves external image URLs server-side, so we pass URLs directly
     * (no need to proxy through /external-assets).
     */
    private suspend fun buildDiscordActivity(data: RPC.Companion.RPCData): DiscordActivity {
        val isManga = data.type == RPC.Type.WATCHING &&
                (data.state?.contains("Reading", ignoreCase = true) == true ||
                        data.state?.contains("Chapter", ignoreCase = true) == true)

        val mode = if (isManga) PrefManager.getVal(PrefName.DiscordRPCModeManga, "dantotsu") else PrefManager.getVal(PrefName.DiscordRPCModeAnime, "dantotsu")
        val useIconPref = if (isManga) PrefManager.getVal<Boolean>(PrefName.DiscordRPCShowIconManga, true) else PrefManager.getVal<Boolean>(PrefName.DiscordRPCShowIconAnime, true)

        // Select Small Icon based on mode
        val (smallIconUrl, smallIconText) = if (useIconPref && mode != "nothing") {
            when (mode) {
                "anilist" -> Discord.small_Image_AniList to "AniList"
                "mal" -> Discord.small_Image_MAL to "MyAnimeList"
                else -> Discord.small_Image to "Dantotsu"
            }
        } else {
            null to null
        }

        // Build Buttons based on mode
        val buttons = mutableListOf<DiscordActivity.Button>()
        if (mode != "nothing") {
            // Button 1: Media Link (Primary Tracker)
            val trackers = runCatching { data.buttons.filter { it.url.contains("anilist.co") || it.url.contains("myanimelist.net") } }.getOrDefault(emptyList())
            val primaryTracker = when (mode) {
                "mal" -> trackers.find { it.url.contains("myanimelist.net") } ?: trackers.find { it.url.contains("anilist.co") }
                else -> trackers.find { it.url.contains("anilist.co") } ?: trackers.find { it.url.contains("myanimelist.net") }
            }
            
            primaryTracker?.let {
                buttons.add(DiscordActivity.Button(label = it.label, url = it.url))
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

            userProfileUrl?.let {
                buttons.add(DiscordActivity.Button(label = profileLabel ?: "View Profile", url = it))
            }
        }

        return DiscordActivity(
            applicationId = data.applicationId,
            name = data.activityName?.takeIf { it.isNotBlank() } ?: "Dantotsu",
            platform = "android", // Required by Discord
            type = data.type?.ordinal,
            statusDisplayType = 0,
            details = data.details,
            state = data.state,
            assets = DiscordActivity.Assets(
                largeImage = data.largeImage?.url,
                largeText = data.largeImage?.label,
                largeUrl = null,
                smallImage = smallIconUrl,
                smallText = smallIconText,
                smallUrl = null,
            ),
            timestamps = if (data.startTimestamp != null)
                DiscordActivity.Timestamps(
                    start = data.startTimestamp,
                    end = data.stopTimestamp
                )
            else null,
            buttons = buttons.take(2).takeIf { it.isNotEmpty() },
        )
    }
}
