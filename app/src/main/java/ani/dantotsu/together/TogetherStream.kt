package ani.dantotsu.together

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil

enum class TogetherRole { HOST, GUEST }

data class TogetherSubtitle(
    val url: String,
    val language: String,
    val label: String? = null
)

data class TogetherAudio(
    val url: String,
    val language: String,
    val label: String? = null
)

data class TogetherStream(
    val mediaTitle: String,
    val episodeLabel: String,
    val videoUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<TogetherSubtitle> = emptyList(),
    val audios: List<TogetherAudio> = emptyList(),
    val sharedBy: String? = null,
    val sharedById: String? = null
) {
    companion object {
        const val WIRE_PREFIX = "~dt1|"
        private const val CHUNK_SIZE = 180
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun isValidShareUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            if (trimmed.startsWith("magnet:", ignoreCase = true)) return true
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) {
                return false
            }
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (addr != null && (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress)) {
                return false
            }
            return true
        }

        fun encodeSidebandChunks(streamId: String, payload: String): List<String> {
            val count = maxOf(1, ceil(payload.length / CHUNK_SIZE.toDouble()).toInt())
            return (0 until count).map { idx ->
                val start = idx * CHUNK_SIZE
                val end = minOf(payload.length, start + CHUNK_SIZE)
                "$WIRE_PREFIX$streamId|$idx|$count|${payload.substring(start, end)}"
            }
        }

        fun serializeStreamPayload(stream: TogetherStream): String {
            val obj = buildJsonObject {
                put("i", JsonPrimitive(stream.mediaTitle))
                put("n", JsonPrimitive(stream.episodeLabel))
                put("v", JsonPrimitive(stream.videoUrl))
                if (stream.headers.isNotEmpty()) {
                    put("h", buildJsonObject {
                        stream.headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                }
                if (stream.subtitles.isNotEmpty()) {
                    put("s", buildJsonArray {
                        stream.subtitles.forEach { sub ->
                            add(buildJsonObject {
                                put("f", JsonPrimitive(sub.url))
                                put("l", JsonPrimitive(sub.language))
                                sub.label?.let { put("t", JsonPrimitive(it)) }
                            })
                        }
                    })
                }
                if (stream.audios.isNotEmpty()) {
                    put("a", buildJsonArray {
                        stream.audios.forEach { aud ->
                            add(buildJsonObject {
                                put("f", JsonPrimitive(aud.url))
                                put("l", JsonPrimitive(aud.language))
                                aud.label?.let { put("t", JsonPrimitive(it)) }
                            })
                        }
                    })
                }
            }.toString()

            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { it.write(obj.toByteArray(Charsets.UTF_8)) }
            val b64 = Base64.encodeToString(
                baos.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            return "S:$b64"
        }

        fun deserializeStreamPayload(encodedPayload: String, sharedBy: String? = null, sharedById: String? = null): TogetherStream? {
            if (!encodedPayload.startsWith("S:")) return null
            val b64 = encodedPayload.removePrefix("S:")
            return runCatching {
                val compressed = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val raw = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).readText()
                val obj = json.parseToJsonElement(raw).jsonObject
                val title = obj["i"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val epLabel = obj["n"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = obj["v"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!isValidShareUrl(url)) return null

                val headers = (obj["h"] as? JsonObject)?.mapNotNull { (k, v) ->
                    v.jsonPrimitive.contentOrNull?.let { k to it }
                }?.toMap().orEmpty()

                val subs = (obj["s"] as? JsonArray)?.mapNotNull { el ->
                    val subObj = el as? JsonObject ?: return@mapNotNull null
                    val f = subObj["f"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val l = subObj["l"]?.jsonPrimitive?.contentOrNull ?: "Sub"
                    val t = subObj["t"]?.jsonPrimitive?.contentOrNull
                    TogetherSubtitle(f, l, t)
                }.orEmpty()

                val audios = (obj["a"] as? JsonArray)?.mapNotNull { el ->
                    val audObj = el as? JsonObject ?: return@mapNotNull null
                    val f = audObj["f"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val l = audObj["l"]?.jsonPrimitive?.contentOrNull ?: "Audio"
                    val t = audObj["t"]?.jsonPrimitive?.contentOrNull
                    TogetherAudio(f, l, t)
                }.orEmpty()

                TogetherStream(
                    mediaTitle = title,
                    episodeLabel = epLabel,
                    videoUrl = url,
                    headers = headers,
                    subtitles = subs,
                    audios = audios,
                    sharedBy = sharedBy,
                    sharedById = sharedById
                )
            }.getOrNull()
        }
    }
}
