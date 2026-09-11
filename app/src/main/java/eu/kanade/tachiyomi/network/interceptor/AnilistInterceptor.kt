package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class AnilistInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val host = originalRequest.url.host

        return if (host.contains("anilist.co")) {
            val builder = originalRequest.newBuilder()
            if (originalRequest.header("Referer").isNullOrEmpty()) {
                builder.header("Referer", "https://anilist.co/")
            }
            if (originalRequest.header("Origin").isNullOrEmpty()) {
                builder.header("Origin", "https://anilist.co")
            }
            chain.proceed(builder.build())
        } else {
            chain.proceed(originalRequest)
        }
    }
}
