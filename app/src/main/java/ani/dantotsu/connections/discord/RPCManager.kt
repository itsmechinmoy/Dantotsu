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
import kotlinx.coroutines.SupervisorJob
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

    // ΓöÇΓöÇΓöÇ Public API ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

    /**
     * Set / update Discord Rich Presence.
     *
     * @param context Android context (used to locate the token cache directory)
     * @param data    The presence data built by the calling screen
     */
    fun setPresence(context: Context, data: RPC.Companion.RPCData) {
        scope.launch {
            Logger.log("RPCManager: Attempting to use Headless RPC...")
            runCatching {
                ensureHeadlessRpc(context)?.newActivity(buildDiscordActivity(data))
                Logger.log("RPCManager: Headless RPC update succeeded.")
            }.onFailure { e ->
                Logger.log("RPCManager: HeadlessRPC failed ΓÇô ${e.message}")
            }
        }
    }

    /**
     * Clear / stop Discord Rich Presence.
     */
    fun clearPresence(context: Context) {
        Logger.log("RPCManager: Clearing presence...")
        scope.launch {
            headlessRpc?.runCatching { clear() }
        }
    }

    /**
     * Call this when the user logs out of Discord to release all resources.
     */
    fun reset() {
        headlessRpc?.stop()
        headlessRpc = null
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

        var iconService = if (isManga) {
            PrefManager.getVal(PrefName.DiscordIconServiceManga, "DANTOTSU")
        } else {
            PrefManager.getVal(PrefName.DiscordIconService, "DANTOTSU")
        }
        if (isManga && iconService == "SIMKL") {
            val anilistToken = PrefManager.getVal(PrefName.AnilistToken, "")
            val malToken = MAL.token
            iconService = when {
                anilistToken.isNotEmpty() -> "ANILIST"
                malToken != null -> "MAL"
                else -> "DANTOTSU"
            }
        }

        val (smallIconUrl, smallIconText) = when (iconService) {
            "ANILIST" -> Discord.small_Image_AniList to "AniList"
            "MAL" -> Discord.small_Image_MAL to "MyAnimeList"
            "SIMKL" -> Discord.small_Image_Simkl to "Simkl"
            else -> Discord.small_Image to "Dantotsu"
        }

        val buttons = data.buttons.take(2).map { link ->
            DiscordActivity.Button(label = link.label, url = link.url)
        }.takeIf { it.isNotEmpty() }

        return DiscordActivity(
            applicationId = data.applicationId,
            name = data.activityName,
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
            buttons = buttons,
        )
    }
}
