package ani.dantotsu.connections.discord

import ani.dantotsu.connections.discord.models.DiscordActivity
import ani.dantotsu.connections.discord.models.DiscordSession
import ani.dantotsu.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val client = OkHttpClient()
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
    private var activityToken: String? = null

    /** Send (or update) a rich presence activity. */
    suspend fun newActivity(activity: DiscordActivity?) {
        if (activity == null) {
            Logger.log("HeadlessRPC: Activity is null, calling deleteSession")
            deleteSession()
            return
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
    suspend fun deleteSession() = withContext(Dispatchers.IO) {
        val sessionToken = activityToken ?: return@withContext
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
            // If session already gone, just reset
            Logger.log("HeadlessRPC: deleteSession failed: ${resp.code} ${resp.body?.string()}")
        } else {
            Logger.log("HeadlessRPC: deleteSession succeeded")
        }
        activityToken = null
    }

    private suspend fun postSession(session: DiscordSession) = withContext(Dispatchers.IO) {
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
        if (!resp.isSuccessful) {
            Logger.log("HeadlessRPC: POST failed: ${resp.code} $respBody")
            // If 401, clear cached token so it's re-acquired next time
            if (resp.code == 401) tokenManager.clear()
            throw IllegalStateException("headless-sessions POST failed: ${resp.code} $respBody")
        }

        Logger.log("HeadlessRPC: POST succeeded! Saving activity token")
        runCatching {
            val responseJson = json.decodeFromString<JsonObject>(respBody)
            activityToken = responseJson["token"]?.jsonPrimitive?.content
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
    }

    /** Alias for deleteSession ΓÇô clear the current activity. */
    suspend fun clear() = deleteSession()
}
