package ani.dantotsu.others.webview

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.themes.ThemeManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.getSerializableExtraCompat
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CookieCatcher : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()

        //get url from intent
        val url = intent.getStringExtra("url") ?: getString(R.string.cursed_yt)
        val headers = intent
            .getSerializableExtraCompat<HashMap<String, String>>("headers")
            ?: hashMapOf()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = Application.getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }
        setContentView(R.layout.activity_discord)

        val webView = findViewById<WebView>(R.id.discordWebview)

        val cookies: CookieManager? = Injekt.get<NetworkHelper>().cookieJar.manager
        cookies?.setAcceptThirdPartyCookies(webView, true)

        webView.setDefaultSettings()
        val ua = headers["User-Agent"] ?: NetworkHelper.defaultUserAgentProvider()
        webView.setUserAgent(ua)

        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                cookies?.flush()
            }
        }

        webView.loadUrl(url, headers)
    }

    override fun onPause() {
        super.onPause()
        Injekt.get<NetworkHelper>().cookieJar.flush()
    }

    override fun onDestroy() {
        Injekt.get<NetworkHelper>().cookieJar.flush()
        super.onDestroy()
    }
}
