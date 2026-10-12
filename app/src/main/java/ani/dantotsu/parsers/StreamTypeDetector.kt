package ani.dantotsu.parsers

import android.net.Uri
import java.net.URL

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

    fun isLocalMp4(host: String?, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        val p = path ?: ""
        val q = query ?: ""
        return p.contains("mp4", ignoreCase = true) || q.contains("mp4", ignoreCase = true)
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
        return p.contains("mpd", ignoreCase = true) || q.contains("mpd", ignoreCase = true)
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
}
