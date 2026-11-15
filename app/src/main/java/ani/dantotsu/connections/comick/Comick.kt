package ani.dantotsu.connections.comick

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

object Comick {
    val query: ComickQueries = ComickQueries()
    const val clientId = "SXkcnjKFxxboxRydpAQcjnnPWEDbbtFh"
    const val redirectUri = "app.dantotsu:/comick-auth"
    const val apiHost = "https://api.comick.dev"
    const val oauthHost = "https://comick.dev/api/auth/oauth2"
    const val resource = "https://api.comick.dev/integrations/v1"

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
        PrefManager.setVal(PrefName.ComickCodeChallenge, codeVerifier)

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
            "&scope=${Uri.encode("library:read library:write offline_access")}" +
            "&resource=${Uri.encode(resource)}" +
            "&state=$state"

        try {
            CustomTabsIntent.Builder().build().launchUrl(
                context,
                Uri.parse(request)
            )
        } catch (e: ActivityNotFoundException) {
            openLinkInBrowser(request)
        }
    }

    suspend fun refreshToken(): ComickToken? {
        return tryWithSuspend {
            val savedToken = PrefManager.getNullableVal<ComickToken>(PrefName.ComickToken, null)
                ?: return@tryWithSuspend null
            val refreshTok = savedToken.refreshToken ?: return@tryWithSuspend null
            val res = client.post(
                "$oauthHost/token",
                data = mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshTok,
                    "resource" to resource
                ),
                headers = mapOf("Accept" to "application/json")
            ).parsed<ComickToken>()
            saveResponse(res)
            token = res.accessToken
            return@tryWithSuspend res
        }
    }

    suspend fun getSavedToken(): Boolean {
        return tryWithSuspend(false) {
            var res: ComickToken =
                PrefManager.getNullableVal<ComickToken>(PrefName.ComickToken, null)
                    ?: return@tryWithSuspend false
            if (System.currentTimeMillis() > res.expiresIn) {
                res = refreshToken() ?: return@tryWithSuspend false
            }
            token = res.accessToken
            username = PrefManager.getVal(PrefName.ComickUserName, null as String?)
            avatar = PrefManager.getVal(PrefName.ComickAvatar, null as String?)
            userId = PrefManager.getVal(PrefName.ComickUserId, null as String?)
            return@tryWithSuspend true
        } ?: false
    }

    fun removeSavedToken() {
        token = null
        username = null
        avatar = null
        userId = null
        PrefManager.removeVal(PrefName.ComickToken)
        PrefManager.removeVal(PrefName.ComickUserName)
        PrefManager.removeVal(PrefName.ComickAvatar)
        PrefManager.removeVal(PrefName.ComickUserId)
        PrefManager.removeVal(PrefName.ComickCodeChallenge)
    }

    fun saveResponse(res: ComickToken) {
        val seconds = if (res.expiresIn > 0) res.expiresIn else 3600L
        res.expiresIn = System.currentTimeMillis() + (seconds * 1000L)
        PrefManager.setVal(PrefName.ComickToken, res)
    }
}
