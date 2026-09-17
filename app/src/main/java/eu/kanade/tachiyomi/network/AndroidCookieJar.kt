package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import ani.dantotsu.snackString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    val manager: CookieManager? = try {
        CookieManager.getInstance()
    } catch (e: Exception) {
        snackString("Webview is outdated, please update your webview")
        null
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()

        cookies.forEach { manager?.setCookie(urlString, it.toString()) }
        manager?.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val cookies = manager?.getCookie(url.toString())

        return if (!cookies.isNullOrEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val urlString = url.toString()
        val cookies = manager?.getCookie(urlString) ?: return 0

        fun List<String>.filterNames(): List<String> {
            return if (cookieNames != null) {
                this.filter { it in cookieNames }
            } else {
                this
            }
        }

        val count = cookies.split(";")
            .map { it.substringBefore("=") }
            .filterNames()
            .onEach { manager.setCookie(urlString, "$it=;Max-Age=$maxAge") }
            .count()
        manager.flush()
        return count
    }

    fun removeAll() {
        manager?.removeAllCookies {
            manager.flush()
        }
    }

    fun flush() {
        manager?.flush()
    }
}
