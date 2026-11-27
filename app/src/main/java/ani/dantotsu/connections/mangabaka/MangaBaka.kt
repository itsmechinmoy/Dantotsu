package ani.dantotsu.connections.mangabaka

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import ani.dantotsu.client
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import java.security.MessageDigest
import java.security.SecureRandom

object MangaBaka {
    val query: MangaBakaQueries = MangaBakaQueries()
    const val clientId = "NdZDwxSEMCdPvWmGOkOXbviYOyJjqZpd"
    const val redirectUri = "dantotsu://mangabaka"
    const val apiHost = "https://api.mangabaka.org/v1"
    const val oauthHost = "https://mangabaka.org/auth/oauth2"

    var username: String? = null
    var avatar: String? = null
    var token: String? = null
    var userId: String? = null

    fun loginIntent(context: Context) {
        val verifierBytes = ByteArray(64)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        PrefManager.setVal(PrefName.MangaBakaCodeChallenge, codeVerifier)

        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val stateBytes = ByteArray(16)
        SecureRandom().nextBytes(stateBytes)
        val state = Base64.encodeToString(
            stateBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val request = "$oauthHost/authorize?" +
            "client_id=$clientId" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&response_type=code" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256" +
            "&scope=${Uri.encode("openid profile library.read library.write offline_access")}" +
            "&state=$state" +
            "&prompt=consent"

        try {
            CustomTabsIntent.Builder().build().launchUrl(
                context,
                Uri.parse(request)
            )
        } catch (e: ActivityNotFoundException) {
            openLinkInBrowser(request)
        }
    }

    suspend fun refreshToken(): MangaBakaToken? {
        return tryWithSuspend {
            val savedToken = PrefManager.getNullableVal<MangaBakaToken>(PrefName.MangaBakaToken, null)
                ?: return@tryWithSuspend null
            val refreshTok = savedToken.refreshToken ?: return@tryWithSuspend null
            val res = client.post(
                "$oauthHost/token",
                data = mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshTok
                )
            ).parsed<MangaBakaToken>()
            saveResponse(res)
            token = res.accessToken
            return@tryWithSuspend res
        }
    }

    suspend fun getSavedToken(): Boolean {
        return tryWithSuspend(false) {
            var res: MangaBakaToken =
                PrefManager.getNullableVal<MangaBakaToken>(PrefName.MangaBakaToken, null)
                    ?: return@tryWithSuspend false
            if (System.currentTimeMillis() > res.expiresIn) {
                res = refreshToken() ?: return@tryWithSuspend false
            }
            token = res.accessToken
            username = PrefManager.getVal(PrefName.MangaBakaUserName, null as String?)
            avatar = PrefManager.getVal(PrefName.MangaBakaAvatar, null as String?)
            userId = PrefManager.getVal(PrefName.MangaBakaUserId, null as String?)
            return@tryWithSuspend true
        } ?: false
    }

    fun removeSavedToken() {
        token = null
        username = null
        avatar = null
        userId = null
        PrefManager.removeVal(PrefName.MangaBakaToken)
        PrefManager.removeVal(PrefName.MangaBakaUserName)
        PrefManager.removeVal(PrefName.MangaBakaAvatar)
        PrefManager.removeVal(PrefName.MangaBakaUserId)
        PrefManager.removeVal(PrefName.MangaBakaCodeChallenge)
    }

    fun saveResponse(res: MangaBakaToken) {
        val seconds = if (res.expiresIn > 0) res.expiresIn else 3600L
        res.expiresIn = System.currentTimeMillis() + (seconds * 1000L)
        PrefManager.setVal(PrefName.MangaBakaToken, res)
    }
}
