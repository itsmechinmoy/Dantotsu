package ani.dantotsu.media.anime.cast

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.core.net.toUri
import ani.dantotsu.util.Logger
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class CastProxyServer(
    baseClient: OkHttpClient = OkHttpClient(),
    private val contentResolver: ContentResolver,
    private val ipAddress: String,
    private val port: Int = DEFAULT_PORT,
) : NanoHTTPD(port) {

    private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
        val naiveTrustManager =
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            }

        val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
            val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory

        sslSocketFactory(insecureSocketFactory, naiveTrustManager)
        hostnameVerifier { _, _ -> true }
        return this
    }

    private val client = baseClient.newBuilder()
        .ignoreAllSSLErrors()
        .build()

    override fun serve(session: IHTTPSession?): Response? {
        val uri = session?.uri ?: return super.serve(session)
        return when {
            uri.startsWith("/local") -> localServe(session)
            uri.startsWith("/proxy") -> proxyServe(session)
            else -> super.serve(session)
        }
    }

    private class AfdStream(
        private val afd: AssetFileDescriptor,
        private val stream: FileInputStream,
    ) : InputStream() {
        override fun read(): Int = stream.read()
        override fun read(b: ByteArray): Int = stream.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
        override fun skip(n: Long): Long = stream.skip(n)
        override fun available(): Int = stream.available()

        override fun close() {
            try {
                stream.close()
            } finally {
                afd.close()
            }
        }
    }

    private fun localServe(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull() ?: return badRequest("No url param")
        val uri = try {
            url.toUri()
        } catch (e: Exception) {
            Logger.log("CastProxyServer: Failed to parse uri $url: ${e.message}")
            return badRequest("Invalid uri")
        }
        val afd = try {
            contentResolver.openAssetFileDescriptor(uri, "r")
                ?: return notFound("Unable to open file")
        } catch (e: Exception) {
            Logger.log("CastProxyServer: Failed to open content uri: $url: ${e.message}")
            return notFound("File not found")
        }

        val mime = contentResolver.getType(uri) ?: guessMimeFromUri(uri)
        val fileLength = afd.length
        val rangeHeader = session.headers["range"]

        val stream = afd.createInputStream()
        val afdStream = AfdStream(afd, stream)

        if (rangeHeader != null && fileLength > 0) {
            try {
                val range = rangeHeader.replace("bytes=", "").split("-")
                val start = range.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = range.getOrNull(1)?.toLongOrNull() ?: (fileLength - 1)
                val length = end - start + 1

                afdStream.skip(start)

                val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, afdStream, length)
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                return response.apply { addCorsHeaders(this) }
            } catch (e: Exception) {
                Logger.log("CastProxyServer: Error processing Range header: ${e.message}")
            }
        }

        val response = if (fileLength >= 0) {
            newFixedLengthResponse(Response.Status.OK, mime, stream, fileLength)
        } else {
            newChunkedResponse(Response.Status.OK, mime, stream)
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response.apply { addCorsHeaders(this) }
    }

    private fun guessMimeFromUri(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast(".", "") ?: ""
        return when (ext.lowercase()) {
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wmv" -> "video/x-ms-wmv"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mpd" -> "application/dash+xml"
            else -> "application/octet-stream"
        }
    }

    private fun proxyServe(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull() ?: return badRequest("No url param")
        val headersJson = session.parameters["header"]?.firstOrNull()
        val headers = mutableMapOf<String, String>()
        if (!headersJson.isNullOrBlank()) {
            try {
                val jsonObject = JSONObject(headersJson)
                jsonObject.keys().forEach { key ->
                    headers[key] = jsonObject.getString(key)
                }
            } catch (e: Exception) {
                Logger.log("CastProxyServer: Failed to parse headers JSON: ${e.message}")
            }
        }

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        session.headers["range"]?.let { requestBuilder.addHeader("Range", it) }

        return try {
            val resp = client.newCall(requestBuilder.build()).execute()
            val contentType = resp.header("Content-Type") ?: ""
            val status = Response.Status.lookup(resp.code) ?: Response.Status.OK

            val response = if (resp.isM3U8()) {
                val body = resp.body?.string() ?: ""
                val proxied = proxyM3U8(body, url, headers)
                newFixedLengthResponse(status, "application/vnd.apple.mpegurl", proxied)
            } else if (resp.isDash()) {
                val body = resp.body?.string() ?: ""
                val proxied = proxyDash(body, url, headers)
                newFixedLengthResponse(status, "application/dash+xml", proxied)
            } else {
                val body = resp.body
                val length = body?.contentLength() ?: -1L
                val stream = body?.byteStream()
                val mime = contentType.ifEmpty { "application/octet-stream" }
                val returnResp = if (length >= 0) {
                    newFixedLengthResponse(status, mime, stream, length)
                } else {
                    newChunkedResponse(status, mime, stream)
                }
                resp.header("Content-Range")?.let { returnResp.addHeader("Content-Range", it) }
                resp.header("Accept-Ranges")?.let { returnResp.addHeader("Accept-Ranges", it) }
                returnResp
            }

            response.apply { addCorsHeaders(this) }
        } catch (e: Exception) {
            Logger.log("CastProxyServer: Error proxying request to $url: ${e.message}")
            badRequest("Proxy request failed: ${e.message}")
        }
    }

    private fun okhttp3.Response.isM3U8(): Boolean {
        val contentType = header("Content-Type") ?: ""
        if (contentType.endsWith("vnd.apple.mpegurl") || contentType.endsWith("x-mpegURL")) {
            return true
        }

        val path = request.url.pathSegments.lastOrNull()
        return path?.endsWith(".m3u8") == true || path?.endsWith(".m3u") == true
    }

    private fun proxyM3U8(playlist: String, baseUrl: String, headers: Map<String, String>): String {
        return buildString {
            playlist.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> appendLine(line)
                    HLS_TAGS.any { trimmed.startsWith(it) } -> appendLine(
                        proxyAttrUris(line, baseUrl, headers)
                    )
                    trimmed.startsWith('#') -> appendLine(line)
                    else -> {
                        val full = resolveUrl(baseUrl, trimmed)
                        appendLine(getProxyUrl(full, headers))
                    }
                }
            }
        }
    }

    private fun proxyAttrUris(line: String, baseUrl: String, headers: Map<String, String>): String {
        return ATTRIBUTE_REGEX.replace(line) { m ->
            val uri = m.groupValues[1]
            val full = resolveUrl(baseUrl, uri)
            val proxied = getProxyUrl(full, headers)
            "URI=\"$proxied\""
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("//")) return "https:$relative"
        return try {
            URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    private fun okhttp3.Response.isDash(): Boolean {
        val contentType = header("Content-Type") ?: ""
        if (contentType.endsWith("dash+xml")) {
            return true
        }

        val path = request.url.pathSegments.lastOrNull()
        return path?.endsWith(".mpd") == true
    }

    private fun proxyDash(manifest: String, baseUrl: String, headers: Map<String, String>): String {
        val document = Jsoup.parse(manifest, baseUrl, Parser.xmlParser()).apply {
            outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml)
        }

        DASH_ATTRS.forEach { attr ->
            document.select("[$attr]").forEach { element ->
                val value = element.attr(attr)
                val full = resolveUrl(baseUrl, value)
                element.attr(attr, getDashProxyUrl(full, headers))
            }
        }

        return document.outerHtml()
    }

    private fun getDashProxyUrl(targetUrl: String, headers: Map<String, String>): String {
        val tokens = mutableListOf<String>()
        val prefix = "DASH_TOKEN_PREFIX"

        val placeholderUrl = DASH_TOKEN_REGEX.replace(targetUrl) { m ->
            val placeholder = "$prefix${tokens.size}END"
            tokens.add(m.value)
            placeholder
        }

        var proxied = getProxyUrl(placeholderUrl, headers)
        tokens.forEachIndexed { index, token ->
            proxied = proxied.replace("$prefix${index}END", token)
        }
        return proxied
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "origin, accept, content-type, authorization, range")
        response.addHeader("Access-Control-Expose-Headers", "content-range, content-length, accept-ranges")
    }

    fun getProxyUrl(targetUrl: String, headers: Map<String, String>): String {
        val headersJson = JSONObject(headers).toString()
        return "http://$ipAddress:$port".toHttpUrl().newBuilder().apply {
            addPathSegment("proxy")
            addQueryParameter("url", targetUrl)
            addQueryParameter("header", headersJson)
        }.build().toString()
    }

    fun getLocalUrl(localUri: String): String {
        return "http://$ipAddress:$port".toHttpUrl().newBuilder().apply {
            addPathSegment("local")
            addQueryParameter("url", localUri)
        }.build().toString()
    }

    private fun notFound(message: String): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", message)
    }

    private fun badRequest(message: String): Response {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", message)
    }

    companion object {
        const val DEFAULT_PORT = 19876
        private val ATTRIBUTE_REGEX = Regex("""URI="([^"]+)"""")
        private val HLS_TAGS = listOf("#EXT-X-KEY", "#EXT-X-MEDIA", "#EXT-X-I-FRAME-STREAM-INF")
        private val DASH_ATTRS = listOf("initialization", "media", "index")
        private val DASH_TOKEN_REGEX = Regex("""\$[A-Za-z0-9]*(?:%0\d+d)?\$""")

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (networkInterface in interfaces) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            } catch (_: Exception) {}
            return "127.0.0.1"
        }
    }
}
