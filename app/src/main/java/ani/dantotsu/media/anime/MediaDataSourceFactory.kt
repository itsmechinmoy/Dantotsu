package ani.dantotsu.media.anime

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ani.dantotsu.util.Logger
import okhttp3.OkHttpClient
import org.chromium.net.CronetProvider
import java.util.concurrent.Executors

/**
 * Multi-tier HTTP DataSource resolver for Media3 / ExoPlayer.
 *
 *   Tier 1 (GMS devices) — [CronetDataSource] via Google Play Services [CronetProvider].
 *                          Provides HTTP/3 (QUIC) + HTTP/2. ~90 KB APK footprint (glue only;
 *                          the actual Chromium engine is already on the device via Play Services).
 *                          On F-Droid builds [CronetProvider] class is absent at runtime
 *                          → caught via [runCatching] → falls through cleanly.
 *
 *   Tier 2 (fallback)    — [OkHttpDataSource] (existing HTTP/2 baseline). Always available.
 *
 * Localhost/loopback streams always use the OkHttp factory (Tier 2) so that the
 * existing gzip-decompression and Accept-Encoding interceptors remain active for
 * the NanoHTTPD torrent proxy.
 */
@UnstableApi
object MediaDataSourceFactory {

    /**
     * Resolves and returns the best available [HttpDataSource.Factory] for the
     * given [context] and request [headers].
     *
     * @param context      Android context (used for Cronet provider lookup).
     * @param headers      Default request headers to set on the factory.
     * @param okHttpClient The OkHttpClient used as the Tier 2 fallback.
     * @param isLocalhost  When true, skips QUIC tiers and returns OkHttp directly.
     *                     QUIC over loopback is undefined and the NanoHTTPD proxy
     *                     requires the OkHttp interceptor chain.
     */
    fun resolveHttpFactory(
        context: Context,
        headers: Map<String, String>,
        okHttpClient: OkHttpClient,
        isLocalhost: Boolean = false,
    ): HttpDataSource.Factory {
        if (isLocalhost) {
            Logger.log("DataSource: localhost stream — using OkHttp (HTTP/2, QUIC bypassed)")
            return buildOkHttpFactory(okHttpClient, headers)
        }

        // Tier 1 — GMS Cronet (absent on F-Droid; caught safely)
        tryCronetProvider(context, headers)?.let { return it }

        // Tier 2 — OkHttp (HTTP/2, always available)
        Logger.log("DataSource: using OkHttp (HTTP/2)")
        return buildOkHttpFactory(okHttpClient, headers)
    }

    // -------------------------------------------------------------------------
    // Tier 1 — GMS CronetProvider
    // -------------------------------------------------------------------------

    /**
     * Attempts to build a [CronetDataSource.Factory] via the GMS Cronet provider.
     *
     * Catches [Throwable] (not just [Exception]) because on F-Droid builds the
     * [CronetProvider] class is absent → [NoClassDefFoundError] (a [LinkageError]).
     */
    private fun tryCronetProvider(
        context: Context,
        headers: Map<String, String>,
    ): HttpDataSource.Factory? = runCatching {
        val providers = CronetProvider.getAllProviders(context)
        val provider = providers.firstOrNull { p ->
            p.isEnabled && p.name != CronetProvider.PROVIDER_NAME_FALLBACK
        } ?: run {
            Logger.log("DataSource: no active GMS Cronet provider found")
            return@runCatching null
        }
        val engine = provider.createBuilder()
            .enableQuic(true)
            .enableHttp2(true)
            .build()
        CronetDataSource.Factory(engine, Executors.newCachedThreadPool())
            .apply { setDefaultRequestProperties(headers) }
            .also { Logger.log("DataSource: using GMS Cronet '${provider.name}' (HTTP/3 + HTTP/2)") }
    }.getOrElse { e ->
        // NoClassDefFoundError on F-Droid, or runtime failure on GMS device — safe to swallow.
        Logger.log("DataSource: GMS Cronet unavailable — ${e.message}")
        null
    }

    // -------------------------------------------------------------------------
    // Tier 2 (also used directly by DrmDownloader for license requests)
    // -------------------------------------------------------------------------

    /**
     * Builds an [OkHttpDataSource.Factory] with the supplied [headers].
     * DRM license fetches always use this path (they need the OkHttp cookie /
     * CloudFlare interceptor chain and are not latency-critical).
     */
    fun buildOkHttpFactory(
        okHttpClient: OkHttpClient,
        headers: Map<String, String>,
    ): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(headers)
            headers["User-Agent"]?.let { setUserAgent(it) }
        }
}
