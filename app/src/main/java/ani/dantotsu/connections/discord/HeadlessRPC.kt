package ani.dantotsu.connections.discord

import ani.dantotsu.connections.discord.models.DiscordActivity
import ani.dantotsu.connections.discord.models.DiscordSession
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import ani.dantotsu.settings.saving.PrefManager

/**
 * Discord Headless Sessions RPC client.
 * Uses OAuth2 Bearer token (via TokenManager PKCE) to POST rich presence
 * to https://discord.com/api/v10/users/@me/headless-sessions
 *
 * No WebSocket, no foreground service needed.
 * Ported from brahmkshatriya/echo-discord RPC.kt
 */
class HeadlessRPC(
    val authToken: String,
    filesDir: File,
    ifUnauthorized: Throwable? = null,
) {
    private val client = DiscordHttpClient.instance
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val tokenManager = TokenManager(
        authToken = authToken,
        filesDir = filesDir,
        ifUnauthorized = ifUnauthorized,
        testAccessToken = { bearer ->
            val resp = client.newCall(
                Request.Builder()
                    .url("https://discord.com/api/v10/users/@me")
                    .header("Authorization", "Bearer $bearer")
                    .head()
                    .build()
            ).execute()
            if (!resp.isSuccessful) throw IllegalStateException("Cached bearer token invalid (${resp.code})")
        }
    )

    /** Session token returned by the headless sessions API; passed on subsequent updates */
    internal var activityToken: String? = null
    private val mutex = Mutex()

    /** Send (or update) a rich presence activity. */
    suspend fun newActivity(activity: DiscordActivity?) = mutex.withLock {
        if (activity == null) {
            Logger.log("HeadlessRPC: Activity is null, calling deleteSession")
            if (activityToken != null) {
                runCatching { postSession(DiscordSession(activities = emptyList(), token = activityToken)) }
            }
            deleteSessionInternal()
            return@withLock
        }
        Logger.log("HeadlessRPC: Sending new activity to Discord...")
        postSession(
            DiscordSession(
                activities = listOf(activity),
                token = activityToken
            )
        )
    }

    /** Delete the current headless session (clears Discord status). */
    suspend fun deleteSession() = mutex.withLock {
        deleteSessionInternal()
    }

    private suspend fun deleteSessionInternal() = withContext(Dispatchers.IO) {
        val sessionToken = activityToken ?: run {
            Logger.log("HeadlessRPC: No active session token to delete.")
            return@withContext
        }
        try {
            val bearer = tokenManager.getToken()
            val resp = client.newCall(
                Request.Builder()
                    .url("https://discord.com/api/v10/users/@me/headless-sessions/delete")
                    .header("Authorization", "Bearer $bearer")
                    .post(
                        """{"token":"$sessionToken"}"""
                            .toRequestBody("application/json".toMediaType())
                    )
                    .build()
            ).execute()
            if (!resp.isSuccessful) {
                Logger.log("HeadlessRPC: deleteSession failed: ${resp.code} ${resp.body?.string()}")
            } else {
                Logger.log("HeadlessRPC: deleteSession succeeded")
            }
        } catch (e: Exception) {
            Logger.log("HeadlessRPC: deleteSession Network Error - ${e.message}")
        }
        activityToken = null
        PrefManager.removeCustomVal("discord_activity_token")
    }

    private suspend fun postSession(session: DiscordSession) = withContext(Dispatchers.IO) {
        try {
            var retryCount = 0
            while (retryCount < 3) {
                val bearer = tokenManager.getToken()
                val body = json.encodeToString(DiscordSession.serializer(), session)
                val resp = client.newCall(
                    Request.Builder()
                        .url("https://discord.com/api/v10/users/@me/headless-sessions")
                        .header("Authorization", "Bearer $bearer")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()

                val respBody = resp.body?.string() ?: ""
                
                if (resp.code == 429) {
                    val retryAfter = resp.header("Retry-After")?.toLongOrNull() ?: 5L
                    val backoffMs = (retryAfter * 1000 + 500) * (retryCount + 1)
                    Logger.log("HeadlessRPC: Rate limited (429). Backing off ${backoffMs}ms (attempt ${retryCount + 1}/3)")
                    kotlinx.coroutines.delay(backoffMs)
                    retryCount++
                    continue
                }

                if (!resp.isSuccessful) {
                    Logger.log("HeadlessRPC: POST failed: ${resp.code} $respBody")
                    if (resp.code == 401 && retryCount == 0) {
                        Logger.log("HeadlessRPC: 401 — clearing token and retrying with fresh token")
                        tokenManager.clear()
                        retryCount++
                        continue
                    }
                    throw IllegalStateException("headless-sessions POST failed: ${resp.code} $respBody")
                }

                Logger.log("HeadlessRPC: POST succeeded! Saving activity token")
                runCatching {
                    val responseJson = json.decodeFromString<JsonObject>(respBody)
                    activityToken = responseJson["token"]?.jsonPrimitive?.content
                    PrefManager.setCustomVal("discord_activity_token", activityToken)
                }
                break
            }
        } catch (e: Exception) {
            Logger.log("HeadlessRPC: postSession Network Error - ${e.message}")
            throw e
        }
    }

    /** Fetch the current Discord user's details using the raw auth token. */
    suspend fun getUserDetails(): JsonObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder()
                .url("https://discord.com/api/v9/oauth2/authorize?client_id=${TokenManager.CLIENT_ID}")
                .header("Authorization", authToken)
                .get()
                .build()
        ).execute()
        val body = resp.body?.string() ?: "{}"
        json.decodeFromString<JsonObject>(body)["user"]!!.jsonObject
    }

    /** Clear the presence and invalidate stored Bearer token. */
    fun stop() {
        tokenManager.clear()
        activityToken = null
        PrefManager.removeCustomVal("discord_activity_token")
    }

    /** Clear the current activity by deleting the session. */
    suspend fun clear() = mutex.withLock {
        deleteSessionInternal()
    }
}
