package ani.dantotsu.connections.discord

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages OAuth2 PKCE token exchange for Discord headless sessions.
 * Exchanges a raw user auth token for a Bearer token with `activities.write` scope.
 * Token is cached to disk; re-acquired on failure.
 *
 * Ported from brahmkshatriya/echo-discord TokenManager.kt
 */
class TokenManager(
    val authToken: String,
    val filesDir: File,
    val ifUnauthorized: Throwable? = null,
    val testAccessToken: suspend (String) -> Unit = {},
) {
    val client = DiscordHttpClient.instance
    val mutex = Mutex()
    var accessToken: String? = null
    private var tokenExpiresAt: Long = 0L

    fun clear() {
        accessToken = null
        tokenExpiresAt = 0L
        filesDir.resolve("discord_access.txt").delete()
        filesDir.resolve("discord_refresh.txt").delete()
        filesDir.resolve("discord_expiry.txt").delete()
    }

    suspend fun getToken() = mutex.withLock {
        val isExpired = tokenExpiresAt > 0L && System.currentTimeMillis() > tokenExpiresAt
        if (accessToken == null || isExpired) runCatching {
            if (isExpired) {
                Logger.log("TokenManager: Token expired, proactively refreshing...")
                throw IllegalStateException("Token expired")
            }
            accessToken = filesDir.resolve("discord_access.txt").readText()
            tokenExpiresAt = runCatching {
                filesDir.resolve("discord_expiry.txt").readText().toLong()
            }.getOrDefault(0L)
            if (tokenExpiresAt > 0L && System.currentTimeMillis() > tokenExpiresAt) {
                Logger.log("TokenManager: Cached token expired, refreshing...")
                throw IllegalStateException("Cached token expired")
            }
            testAccessToken(accessToken!!)
            Logger.log("TokenManager: Used cached Discord access token")
        }.onFailure {
            Logger.log("TokenManager: Cached token invalid/missing, attempting refresh or PKCE")
            runCatching {
                val refreshFile = filesDir.resolve("discord_refresh.txt")
                if (!refreshFile.exists()) throw IllegalStateException("No refresh token cached")
                val response = refreshOAuthToken(refreshFile.readText())
                saveTokens(response)
                Logger.log("TokenManager: Token refreshed successfully via refresh_token")
            }.onFailure {
                // fallback to PKCE
                val response = createAccessToken()
                saveTokens(response)
                Logger.log("TokenManager: New access token saved to disk via PKCE logic")
            }
        }
        accessToken!!
    }

    /** Returns the token expiry timestamp (millis), or 0 if unknown. */
    fun getTokenExpiresAt(): Long {
        if (tokenExpiresAt == 0L) {
            tokenExpiresAt = runCatching {
                filesDir.resolve("discord_expiry.txt").readText().toLong()
            }.getOrDefault(0L)
        }
        return tokenExpiresAt
    }

    private fun saveTokens(response: TokenResponse) {
        val accessFile = filesDir.resolve("discord_access.txt")
        accessFile.parentFile?.mkdirs()
        accessToken = response.accessToken
        accessFile.writeText(accessToken!!)
        
        response.refreshToken?.let {
            filesDir.resolve("discord_refresh.txt").writeText(it)
        }

        // Track expiry: refresh 1 minute early to avoid edge-case failures
        response.expiresIn?.let {
            tokenExpiresAt = System.currentTimeMillis() + (it * 1000) - 60_000
            filesDir.resolve("discord_expiry.txt").writeText(tokenExpiresAt.toString())
            Logger.log("TokenManager: Token expires at $tokenExpiresAt (in ${it}s, refreshing 60s early)")
        }
    }

    @Serializable
    data class TokenResponse(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("refresh_token")
        val refreshToken: String? = null,
        @SerialName("token_type")
        val tokenType: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null,
        val scope: String? = null,
    )

    private suspend fun createAccessToken(): TokenResponse = withContext(Dispatchers.IO) {
        val codeVerifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(codeVerifier)

        val httpUrl = "https://discord.com/api/v9/oauth2/authorize"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("state", "undefined")
            .build()

        val payloadJson = buildJsonObject {
            put("authorize", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val authorizeResp = client.newCall(
            Request.Builder()
                .url(httpUrl)
                .header("Authorization", authToken)
                .post(payloadJson)
                .build()
        ).execute()

        if (!authorizeResp.isSuccessful) {
            val errorBody = authorizeResp.body?.string() ?: "empty"
            Logger.log("TokenManager: OAuth2 authorize failed: ${authorizeResp.code} $errorBody")
            throw ifUnauthorized
                ?: IllegalStateException("OAuth2 authorize failed: ${authorizeResp.code} $errorBody")
        }

        val locationJson = Json.decodeFromString<JsonObject>(authorizeResp.body!!.string())
        val location = locationJson["location"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No location in OAuth2 response")
        val code = location.toHttpUrl().queryParameter("code")
            ?: throw IllegalStateException("No code in OAuth2 redirect")

        Logger.log("TokenManager: Got OAuth2 code, exchanging for Bearer token...")

        val tokenResp = client.newCall(
            Request.Builder()
                .url("https://discord.com/api/v10/oauth2/token")
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("code", code)
                        .add("code_verifier", codeVerifier)
                        .add("grant_type", "authorization_code")
                        .add("redirect_uri", REDIRECT_URI)
                        .build()
                )
                .build()
        ).execute()

        val tokenBody = tokenResp.body?.string()
        if (!tokenResp.isSuccessful) {
            Logger.log("TokenManager: Token exchange failed: ${tokenResp.code} $tokenBody")
            throw IllegalStateException("Token exchange failed: ${tokenResp.code} $tokenBody")
        }
            
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<TokenResponse>(tokenBody ?: throw IllegalStateException("Empty token response"))
        Logger.log("TokenManager: Successfully obtained Discord Bearer token!")
        response.accessToken ?: throw IllegalStateException("No access_token in response: $tokenBody")
        response
    }

    private suspend fun refreshOAuthToken(refreshToken: String): TokenResponse = withContext(Dispatchers.IO) {
        val tokenResp = client.newCall(
            Request.Builder()
                .url("https://discord.com/api/v10/oauth2/token")
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)
                        .build()
                )
                .build()
        ).execute()

        val tokenBody = tokenResp.body?.string()
        if (!tokenResp.isSuccessful) {
            throw IllegalStateException("Refresh failed: ${tokenResp.code} $tokenBody")
        }
            
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<TokenResponse>(tokenBody ?: "")
        response.accessToken ?: throw IllegalStateException("No access_token in refresh response: $tokenBody")
        response
    }

    private val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private fun randomString(length: Int = 128): String = buildString {
        repeat(length) { append(chars[Random.nextInt(chars.length)]) }
    }

    private fun generateCodeVerifier() = randomString()

    @OptIn(ExperimentalEncodingApi::class)
    private fun generateCodeChallenge(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(code.toByteArray(StandardCharsets.UTF_8))
        return Base64.encode(hashed)
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')
    }

    companion object {
        // PreMiD client ID ΓÇô widely used for headless Discord RPC (same as Echo)
        const val CLIENT_ID = "503557087041683458"
        const val REDIRECT_URI = "https://login.premid.app"
        val SCOPES = listOf("identify", "activities.write")
    }
}
