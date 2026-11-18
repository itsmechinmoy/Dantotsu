package ani.dantotsu.connections.discord.rpc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON frames for the Discord app's RPC service: SET_ACTIVITY out, READY / response / ERROR in.
 * Outgoing strings are trimmed, stripped of control characters and capped to Discord's limits.
 * Bad input never throws.
 */
object DiscordRpcCodec {

    const val NAME_MAX = 128
    const val DETAILS_MAX = 128
    const val STATE_MAX = 128
    const val IMAGE_KEY_MAX = 300
    const val IMAGE_TEXT_MAX = 128
    const val BUTTON_LABEL_MAX = 64
    const val BUTTON_URL_MAX = 512
    const val FALLBACK_NAME = "Dantotsu"

    private const val LOG_STRING_MAX = 200
    private val CONTROL_CHARS = Regex("[\\p{Cc}\\p{Cf}]")
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSetActivity(presence: DiscordPresence, pid: Int, applicationId: Long): String =
        frame(pid, activityToJson(presence, applicationId))

    fun encodeClear(pid: Int): String = frame(pid, JsonNull)

    fun parseInbound(frame: String): InboundFrame {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
            ?: return InboundFrame.EMPTY
        val command = obj.str("cmd")?.let { sanitize(it, LOG_STRING_MAX) }
        val event = obj.str("evt")?.let { sanitize(it, LOG_STRING_MAX) }
        val nonce = obj.str("nonce")?.let { sanitize(it, LOG_STRING_MAX) }
        val isError = event == "ERROR" || command == "ERROR"
        var errorCode: Int? = null
        var errorMessage: String? = null
        if (isError) {
            val data = obj["data"] as? JsonObject
            errorCode = (data?.get("code") as? JsonPrimitive)?.intOrNull
            errorMessage = (data?.get("message") as? JsonPrimitive)?.content
                ?.let { sanitize(it, LOG_STRING_MAX) }
        }
        return InboundFrame(
            command = command,
            event = event,
            nonce = nonce,
            isReady = event == "READY",
            // A SET_ACTIVITY frame with evt=ERROR is a rejection, not a success.
            isActivityResponse = command == "SET_ACTIVITY" && !isError,
            isError = isError,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    private fun frame(pid: Int, activity: kotlinx.serialization.json.JsonElement): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", pid)
                put("activity", activity)
            }
            put("nonce", java.util.UUID.randomUUID().toString())
        })

    private fun activityToJson(p: DiscordPresence, applicationId: Long): JsonObject = buildJsonObject {
        put("application_id", applicationId)
        put("name", sanitize(p.name, NAME_MAX) ?: FALLBACK_NAME)
        put("type", p.type.value)
        sanitize(p.details, DETAILS_MAX)?.let { put("details", it) }
        sanitize(p.state, STATE_MAX)?.let { put("state", it) }
        if (p.startTimestamp != null || p.endTimestamp != null) {
            putJsonObject("timestamps") {
                p.startTimestamp?.let { put("start", it) }
                p.endTimestamp?.let { put("end", it) }
            }
        }
        if (p.largeImageKey != null || p.largeImageText != null ||
            p.smallImageKey != null || p.smallImageText != null
        ) {
            putJsonObject("assets") {
                sanitize(p.largeImageKey, IMAGE_KEY_MAX)?.let { put("large_image", it) }
                sanitize(p.largeImageText, IMAGE_TEXT_MAX)?.let { put("large_text", it) }
                sanitize(p.smallImageKey, IMAGE_KEY_MAX)?.let { put("small_image", it) }
                sanitize(p.smallImageText, IMAGE_TEXT_MAX)?.let { put("small_text", it) }
            }
        }
        if (p.buttons.isNotEmpty()) {
            putJsonArray("buttons") {
                p.buttons.forEach { b ->
                    addJsonObject {
                        put("label", sanitize(b.label, BUTTON_LABEL_MAX) ?: "")
                        put("url", sanitize(b.url, BUTTON_URL_MAX) ?: "")
                    }
                }
            }
        }
    }

    /** Null when blank, so optional fields are left out. Cuts by code point to keep emoji whole. */
    private fun sanitize(s: String?, max: Int): String? {
        val cleaned = s?.trim()?.replace(CONTROL_CHARS, "")
        if (cleaned.isNullOrEmpty()) return null
        val cps = cleaned.codePoints().limit(max.toLong()).toArray()
        return String(cps, 0, cps.size)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    data class InboundFrame(
        val command: String?,
        val event: String?,
        val nonce: String?,
        val isReady: Boolean,
        val isActivityResponse: Boolean,
        val isError: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
    ) {
        companion object {
            val EMPTY = InboundFrame(null, null, null, false, false, false, null, null)
        }
    }
}
