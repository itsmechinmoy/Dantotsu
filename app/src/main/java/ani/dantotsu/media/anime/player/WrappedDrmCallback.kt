package ani.dantotsu.media.anime.player

import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import ani.dantotsu.util.Logger
import org.json.JSONObject
import java.util.UUID

/**
 * Normalises license responses from servers that do not return a bare Widevine blob -
 * base64 text, or a JSON envelope like `{"status":"OK","license":"<base64>"}`. MediaDrm
 * expects decoded protobuf and fails either form with ERROR_DRM_LICENSE_PARSE
 * (cdmError 70). The HTTP work is left to [HttpMediaDrmCallback].
 */
@UnstableApi
class WrappedDrmCallback(private val delegate: HttpMediaDrmCallback) : MediaDrmCallback {

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest,
    ): MediaDrmCallback.Response = delegate.executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest,
    ): MediaDrmCallback.Response {
        val response = delegate.executeKeyRequest(uuid, request)
        val unwrapped = unwrap(response.data)
        return if (unwrapped === response.data) response else MediaDrmCallback.Response(unwrapped)
    }

    private fun unwrap(raw: ByteArray): ByteArray {
        // A Widevine SignedMessage is a protobuf, so it starts with 0x08.
        if (raw.isEmpty() || raw[0] == 0x08.toByte()) return raw

        if (raw[0] == '{'.code.toByte()) {
            val text = String(raw, Charsets.UTF_8)
            val encoded = runCatching {
                val obj = JSONObject(text)
                obj.optString("license").ifEmpty { obj.optString("licenseMessage") }
            }.getOrNull()
            if (encoded.isNullOrEmpty()) {
                throw IllegalStateException("license server returned an error: ${text.take(200)}")
            }
            Logger.log("DRM: unwrapped JSON license envelope")
            return decodeBase64(encoded) ?: raw
        }

        return decodeBase64(String(raw, Charsets.UTF_8))?.also {
            Logger.log("DRM: decoded base64 license (${raw.size} -> ${it.size} bytes)")
        } ?: raw
    }

    private fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.decode(value.trim(), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() && it[0] == 0x08.toByte() }

    companion object {
        /** Session managers that route license responses through [WrappedDrmCallback]. */
        fun providerFor(dataSourceFactory: DataSource.Factory) =
            DrmSessionManagerProvider { mediaItem: MediaItem ->
                val config = mediaItem.localConfiguration?.drmConfiguration
                val licenseUri = config?.licenseUri
                if (config == null || licenseUri == null) {
                    DrmSessionManager.DRM_UNSUPPORTED
                } else {
                    val http = HttpMediaDrmCallback(licenseUri.toString(), true, dataSourceFactory)
                    config.licenseRequestHeaders.forEach { (name, value) ->
                        http.setKeyRequestProperty(name, value)
                    }
                    DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(config.scheme, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(config.multiSession)
                        .setPlayClearSamplesWithoutKeys(config.playClearContentWithoutKey)
                        .build(WrappedDrmCallback(http))
                        // A downloaded item carries the id of a license the CDM already
                        // holds, so restoring it needs no network.
                        .also { it.setMode(DefaultDrmSessionManager.MODE_PLAYBACK, config.keySetId) }
                }
            }
    }
}
