package ani.dantotsu.connections.discord

import android.annotation.SuppressLint
import android.app.Application.getProcessName
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord.saveToken
import ani.dantotsu.MainActivity
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
class Login : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }
        setContentView(R.layout.activity_discord)

        val webView = findViewById<WebView>(R.id.discordWebview)

        webView.apply {
            settings.javaScriptEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.domStorageEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(
                    """window.LOCAL_STORAGE = localStorage""".trimIndent()
                ) {}
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val currentUrl = request?.url.toString()
                if (currentUrl != "https://discord.com/login") {
                    view?.postDelayed({
                        view.evaluateJavascript(
                            """
                            (function() {
                                return window.LOCAL_STORAGE.getItem('token');
                            })();
                            """.trimIndent()
                        ) { result ->
                            val token = result?.let {
                                JSONObject("{\"token\":$it}").getString("token")
                            } ?: ""
                            login(token.trim('"'))
                        }
                    }, 2000)
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }

        webView.loadUrl("https://discord.com/login")
    }

    private fun login(token: String) {
        if (token.isEmpty() || token == "null") {
            Toast.makeText(this, "Failed to retrieve token", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Pre-fetch OAuth2 Bearer token for Headless RPC ΓÇô best effort only.
        // Whether it succeeds or fails, we always save the token and go home.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                TokenManager(
                    authToken = token,
                    filesDir = File(filesDir, "discord")
                ).getToken()
            } // ignore result
            
            runCatching {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://discord.com/api/v9/users/@me")
                    .header("Authorization", token)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { jsonString ->
                            val json = JSONObject(jsonString)
                            val id = json.optString("id")
                            val username = json.optString("username")
                            val avatar = json.optString("avatar")
                            
                            PrefManager.setVal(PrefName.DiscordId, id)
                            PrefManager.setVal(PrefName.DiscordUserName, username)
                            PrefManager.setVal(PrefName.DiscordAvatar, avatar)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                saveToken(token)   // updates both disk and Discord.token in-memory
                Toast.makeText(this@Login, "Logged in successfully", Toast.LENGTH_SHORT).show()
                val intent = android.content.Intent(this@Login, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onDestroy() {
        findViewById<WebView>(R.id.discordWebview)?.destroy()
        super.onDestroy()
    }
}
