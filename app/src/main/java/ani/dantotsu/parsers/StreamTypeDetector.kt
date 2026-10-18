package ani.dantotsu.parsers

import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URL
import java.net.URLDecoder

object StreamTypeDetector {
    private val TORRENT_HASH_REGEX = Regex("""/[0-9a-fA-F]{40}(?:/|$)""")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    fun isLocalhost(host: String?): Boolean = host != null && host in LOOPBACK_HOSTS

    fun isLocalhost(uri: Uri?): Boolean = isLocalhost(uri?.host)

    fun isLocalhost(url: URL?): Boolean = isLocalhost(url?.host)

    fun isLocalhostUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { isLocalhost(Uri.parse(url)) }.getOrDefault(false)
    }

    fun isTorrentStream(host: String?, port: Int, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        if (port == 11470 || port == 8090) return true
        val p = path ?: ""
        val q = query ?: ""
        return p.contains("torrent", ignoreCase = true) ||
                q.contains("torrent", ignoreCase = true) ||
                q.contains("infohash", ignoreCase = true) ||
                q.contains("tr=") ||
                TORRENT_HASH_REGEX.containsMatchIn(p)
    }

    fun isTorrentStream(uri: Uri?): Boolean =
        if (uri == null) false else isTorrentStream(uri.host, uri.port, uri.path, uri.query)

    fun isTorrentStream(url: URL?): Boolean =
        if (url == null) false else isTorrentStream(url.host, url.port, url.path, url.query)

    fun isTorrentStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { isTorrentStream(Uri.parse(url)) }.getOrDefault(false)
    }

    private fun extractTargetUrl(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val idx = query.indexOf("url=")
        if (idx != -1) {
            val end = query.indexOf('&', idx)
            val raw = if (end != -1) query.substring(idx + 4, end) else query.substring(idx + 4)
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        val fileIdx = query.indexOf("file=")
        if (fileIdx != -1) {
            val end = query.indexOf('&', fileIdx)
            val raw = if (end != -1) query.substring(fileIdx + 5, end) else query.substring(fileIdx + 5)
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
        return null
    }

    fun isLocalMp4(host: String?, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        val p = path ?: ""
        val q = query ?: ""
        val target = extractTargetUrl(q)
        if (target != null) {
            val targetPath = target.substringBefore('?')
            if (targetPath.endsWith(".mp4", ignoreCase = true) || targetPath.endsWith(".m4v", ignoreCase = true)) return true
            if (targetPath.endsWith(".m3u8", ignoreCase = true) || targetPath.endsWith(".mpd", ignoreCase = true)) return false
        }
        return p.endsWith(".mp4", ignoreCase = true) || p.endsWith(".m4v", ignoreCase = true) ||
                q.contains(".mp4", ignoreCase = true) || q.contains("format=mp4", ignoreCase = true)
    }

    fun isLocalMp4(uri: Uri?): Boolean =
        if (uri == null) false else isLocalMp4(uri.host, uri.path, uri.query)

    fun isLocalMp4(url: URL?): Boolean =
        if (url == null) false else isLocalMp4(url.host, url.path, url.query)

    fun isLocalMp4Url(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { isLocalMp4(Uri.parse(url)) }.getOrDefault(false)
    }

    fun isLocalDash(host: String?, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        val p = path ?: ""
        val q = query ?: ""
        val target = extractTargetUrl(q)
        if (target != null) {
            val targetPath = target.substringBefore('?')
            if (targetPath.endsWith(".mpd", ignoreCase = true)) return true
            if (targetPath.endsWith(".m3u8", ignoreCase = true) || targetPath.endsWith(".mp4", ignoreCase = true)) return false
        }
        return p.endsWith(".mpd", ignoreCase = true) || p.contains("/dash/", ignoreCase = true) ||
                q.contains(".mpd", ignoreCase = true) || q.contains("format=mpd", ignoreCase = true)
    }

    fun isLocalDash(uri: Uri?): Boolean =
        if (uri == null) false else isLocalDash(uri.host, uri.path, uri.query)

    fun isLocalDash(url: URL?): Boolean =
        if (url == null) false else isLocalDash(url.host, url.path, url.query)

    fun isLocalDashUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { isLocalDash(Uri.parse(url)) }.getOrDefault(false)
    }

    fun isLocalhostHls(host: String?, port: Int, path: String?, query: String?): Boolean =
        isLocalhost(host) && !isTorrentStream(host, port, path, query) && !isLocalMp4(host, path, query) && !isLocalDash(host, path, query)

    fun isLocalhostHls(uri: Uri?): Boolean =
        if (uri == null) false else isLocalhostHls(uri.host, uri.port, uri.path, uri.query)

    fun isLocalhostHls(url: URL?): Boolean =
        if (url == null) false else isLocalhostHls(url.host, url.port, url.path, url.query)

    fun isLocalhostHlsUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching { isLocalhostHls(Uri.parse(url)) }.getOrDefault(false)
    }

    fun isSegmentUrl(httpUrl: HttpUrl): Boolean {
        val path = httpUrl.encodedPath
        if (isSegmentPath(path)) return true
        val targetUrl = httpUrl.queryParameter("url") ?: httpUrl.queryParameter("file")
        if (!targetUrl.isNullOrBlank()) {
            val targetPath = targetUrl.toHttpUrlOrNull()?.encodedPath
                ?: targetUrl.substringBefore('?').substringAfterLast('/')
            if (isSegmentPath(targetPath)) return true
        }
        return false
    }

    private fun isSegmentPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/segment") ||
                lower.contains("/seg") ||
                lower.contains("/chunk") ||
                lower.contains("/fragment") ||
                lower.endsWith(".ts") ||
                lower.endsWith(".m4s") ||
                lower.endsWith(".mp4") ||
                lower.endsWith(".m4a") ||
                lower.endsWith(".m4v") ||
                lower.endsWith(".aac") ||
                lower.endsWith(".vtt") ||
                lower.endsWith(".key")
    }

    fun isM3u8Url(httpUrl: HttpUrl): Boolean {
        if (httpUrl.encodedPath.endsWith(".m3u8", ignoreCase = true)) return true
        val targetUrl = httpUrl.queryParameter("url") ?: httpUrl.queryParameter("file")
        if (!targetUrl.isNullOrBlank()) {
            val targetPath = targetUrl.toHttpUrlOrNull()?.encodedPath
                ?: targetUrl.substringBefore('?').substringAfterLast('/')
            if (targetPath.endsWith(".m3u8", ignoreCase = true)) return true
        }
        return false
    }

    fun startsWithHlsMagic(bytes: ByteArray): Boolean {
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            offset = 3
        }
        while (offset < bytes.size && (bytes[offset] == ' '.code.toByte() ||
                    bytes[offset] == '\t'.code.toByte() ||
                    bytes[offset] == '\r'.code.toByte() ||
                    bytes[offset] == '\n'.code.toByte())) {
            offset++
        }
        val magic = "#EXTM3U".toByteArray(Charsets.US_ASCII)
        if (bytes.size - offset < magic.size) return false
        for (i in magic.indices) {
            if (bytes[offset + i] != magic[i]) return false
        }
        return true
    }
}