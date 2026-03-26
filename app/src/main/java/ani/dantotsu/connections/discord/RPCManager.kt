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
import android.content.Intent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

    /** Heartbeat job to keep headless sessions alive (e.g. during 20+ min watch). */
    private var heartbeatJob: Job? = null
    private const val HEARTBEAT_INTERVAL_MS = 9 * 60 * 1000L

    /** Auto-clear job \u2014 clears the RPC if the video is left paused for too long. */
    private var autoClearJob: Job? = null
    private const val AUTO_CLEAR_INTERVAL_MS = 1 * 60 * 1000L

    /** Tracks whether the DiscordService has already been started to avoid redundant calls */
    private var serviceStarted = false


    // ΓöÇΓöÇΓöÇ Public API ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

    /**
     * Set / update Discord Rich Presence.
     *
     * @param context Android context (used to locate the token cache directory)
     * @param data    The presence data built by the calling screen
     */
    fun setPresence(context: Context, data: RPC.Companion.RPCData) {
        if (!serviceStarted) {
            runCatching { 
                context.startService(Intent(context, DiscordService::class.java))
                serviceStarted = true
            }.onFailure { e ->
                Logger.log("RPCManager: Failed to start DiscordService (missing manifest entry?): ${e.message}")
            }
        }
        // Cancel any pending auto-clear since we're updating presence

        // Debounce: only the last call within 500ms actually fires
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            Logger.log("RPCManager: Attempting to use Headless RPC...")
            val activity = buildDiscordActivity(data)
            val isPaused = data.state?.contains("Paused", ignoreCase = true) == true

            runCatching {
                ensureHeadlessRpc(context)?.newActivity(activity)
                Logger.log("RPCManager: Headless RPC update succeeded.")
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.log("RPCManager: HeadlessRPC failed \u2013 ${e.message}")
            }

            // Schedule heartbeat or auto-clear based on playback state
            heartbeatJob?.cancel()
            autoClearJob?.cancel()

            if (isPaused) {
                // If paused, schedule an auto-cleanup after 5 minutes
                autoClearJob = scope.launch {
                    delay(AUTO_CLEAR_INTERVAL_MS)
                    Logger.log("RPCManager: Auto-clearing Headless RPC due to pause timeout.")
                    clearPresence(context)
                }
            } else {
                // If playing continuously, schedule heartbeat
                heartbeatJob = scope.launch {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        Logger.log("RPCManager: Sending heartbeat for Headless RPC...")
                        runCatching {
                            ensureHeadlessRpc(context)?.newActivity(activity)
                        }.onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Logger.log("RPCManager: HeadlessRPC heartbeat failed \u2013 ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Clear / stop Discord Rich Presence.
     */
    fun clearPresence(context: Context) {
        Logger.log("RPCManager: Clearing presence...")
        debounceJob?.cancel()
        heartbeatJob?.cancel()
        autoClearJob?.cancel()
        
        // Delay stopping the service. If the app is being abruptly killed (swiped from Recents),
        // onTaskRemoved will fire before 2 seconds elapse. If it's a normal exit (user pressed Back),
        // the service will gracefully stop after 2 seconds, preventing Android from leaving zombie service records.
        scope.launch {
            delay(2000)
            runCatching { 
                context.stopService(Intent(context, DiscordService::class.java)) 
                serviceStarted = false
            }
        }

        scope.launch {
            val rpc = headlessRpc
            if (rpc == null) {
                Logger.log("RPCManager: Error - headlessRpc is null, cannot clear!")
            } else {
                rpc.runCatching { clear() }
                    .onSuccess { Logger.log("RPCManager: headlessRpc.clear() finished normally") }
                    .onFailure { Logger.log("RPCManager: headlessRpc.clear() threw exception - ${it.message}") }
            }
        }
    }

    /**
     * Clear presence synchronously when the app is swiped from Recents.
     * We use a dedicated Thread and strictly block process termination 
     * for up to 2 seconds to guarantee the HTTP request leaves the device.
     * This uses a raw OkHttp request to completely bypass Coroutine Mutexes
     * and suspend functions, which can easily deadlock during process death.
     */
    fun clearPresenceOnKill(context: Context) {
        debounceJob?.cancel()
        heartbeatJob?.cancel()
        autoClearJob?.cancel()

        val rpc = headlessRpc
        var accessToken = rpc?.tokenManager?.accessToken
        var sessionToken = rpc?.activityToken

        if (accessToken == null || sessionToken == null) {
            // App was resurrected specifically for onTaskRemoved! Reconstruct from cache.
            val discordDir = File(context.filesDir, "discord")
            accessToken = runCatching { discordDir.resolve("discord_access.txt").readText() }.getOrNull()
            sessionToken = PrefManager.getNullableCustomVal("discord_activity_token", null, String::class.java)
        }

        if (accessToken.isNullOrEmpty() || sessionToken.isNullOrEmpty()) {
            Logger.log("RPCManager: Missing tokens for emergency kill cleanup. Aborting.")
            return
        }

        val thread = Thread {
            try {
                Logger.log("RPCManager: App kill emergency raw cleanup starting...")
                val client = DiscordHttpClient.instance

                // 2. Delete session
                val deletePayload = "{\"token\":\"$sessionToken\"}"
                val delReq = okhttp3.Request.Builder()
                    .url("https://discord.com/api/v10/users/@me/headless-sessions/delete")
                    .header("Authorization", "Bearer $accessToken")
                    .post(deletePayload.toRequestBody("application/json".toMediaType()))
                    .build()
                runCatching { 
                    client.newCall(delReq).execute().use { response ->
                        if (response.isSuccessful) {
                            Logger.log("RPCManager: Emergency deleteSession succeeded")
                        } else {
                            Logger.log("RPCManager: Emergency deleteSession failed: ${response.code}")
                        }
                    } 
                }

                Logger.log("RPCManager: App kill emergency raw cleanup successful")
            } catch (e: Exception) {
                Logger.log("RPCManager: App kill emergency raw cleanup failed - ${e.message}")
            }
        }
        thread.start()
        
        // Block the main thread for max 2 seconds. 
        // This physically prevents Android from terminating the process 
        // until the OkHttp request finishes or the 2 seconds elapse.
        try {
            thread.join(2000) 
        } catch (e: InterruptedException) {
            // Ignore
        }
    }

    /**
     * Call this when the user logs out of Discord to release all resources.
     */
    fun reset() {
        debounceJob?.cancel()
        heartbeatJob?.cancel()
        autoClearJob?.cancel()
        serviceStarted = false
        headlessRpc?.stop()
        headlessRpc = null
    }

    /**
     * Returns the token expiry timestamp in millis, or 0 if unknown/not logged in.
     */
    fun getTokenExpiresAt(): Long {
        return headlessRpc?.tokenManager?.getTokenExpiresAt() ?: 0L
    }

    // ΓöÇΓöÇΓöÇ Private helpers ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

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
                if (it.url.isValidUrl()) {
                    buttons.add(DiscordActivity.Button(label = it.label, url = it.url))
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
                    buttons.add(DiscordActivity.Button(label = profileLabel ?: "View Profile", url = url))
                }
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

    /** Validate that a URL is a proper http/https link. */
    private fun String?.isValidUrl(): Boolean {
        return this != null && isNotEmpty() &&
                (startsWith("http://") || startsWith("https://"))
    }


}
