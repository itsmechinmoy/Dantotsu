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
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord.saveToken
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import org.json.JSONObject

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
                android.util.Log.d("WebView", "Navigating to: $currentUrl")

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
                            login( token.trim('"'))
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
        Toast.makeText(this, "Logged in successfully", Toast.LENGTH_SHORT).show()
        finish()
        saveToken(token)
        startMainActivity(this@Login)
    }

}
