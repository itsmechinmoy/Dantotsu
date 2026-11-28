package ani.dantotsu.together

import android.net.Uri
import java.net.URLEncoder

data class TogetherInvite(
    val room: String,
    val server: String? = null
)

object TogetherLink {
    const val SCHEME = "dantotsu"
    const val PARTNER_SCHEME = "dartotsu"
    const val HOST = "together"
    const val DEFAULT_SERVER = "syncplay.pl:8999"

    fun createUrl(room: String, server: String = DEFAULT_SERVER): String {
        val cleanRoom = room.trim()
        val normalizedServer = normalizeServer(server)
        val encodedRoom = URLEncoder.encode(cleanRoom, "UTF-8")
        return if (normalizedServer != DEFAULT_SERVER) {
            val encodedServer = URLEncoder.encode(normalizedServer, "UTF-8")
            "$SCHEME://$HOST?room=$encodedRoom&server=$encodedServer"
        } else {
            "$SCHEME://$HOST?room=$encodedRoom"
        }
    }

    fun parse(rawInput: String?): TogetherInvite? {
        val raw = rawInput?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return parse(uri)
    }

    fun parse(uri: Uri?): TogetherInvite? {
        if (uri == null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != SCHEME && scheme != PARTNER_SCHEME) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null

        val room = uri.getQueryParameter("room")?.trim()
            ?: uri.pathSegments?.firstOrNull()?.trim()
        if (room.isNullOrBlank()) return null

        val server = uri.getQueryParameter("server")?.trim()?.takeIf { it.isNotBlank() }
        return TogetherInvite(room = room, server = server?.let { normalizeServer(it) })
    }

    fun normalizeServer(input: String?): String {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isBlank()) return DEFAULT_SERVER
        val cleaned = trimmed.removePrefix("syncplay://").substringBefore("/")
        return if (':' in cleaned) cleaned else "$cleaned:8999"
    }

    fun parseHostPort(server: String): Pair<String, Int> {
        val norm = normalizeServer(server)
        val host = norm.substringBefore(':').ifBlank { "syncplay.pl" }
        val port = norm.substringAfter(':', "8999").toIntOrNull()?.coerceIn(1, 65535) ?: 8999
        return host to port
    }
}
