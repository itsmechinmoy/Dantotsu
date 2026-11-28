package ani.dantotsu.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Anonymous multi-host image uploader for local photos/GIFs in comments and AniList markdown.
 * Uses isolated [OkHttpClient] instances so AniList credentials are never sent to image hosts.
 */
object LocalMediaUploader {

    data class UploadOutcome(
        val url: String,
        val showsOnAniList: Boolean
    )

    class UploadFailure(message: String) : IOException(message)

    private data class PickedImage(
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String
    )

    private fun interface ImageHost {
        suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String): Result<String>
        val showsOnAniList: Boolean get() = true
    }

    private class PomfAwareHost(
        private val delegate: suspend (ByteArray, String, String) -> Result<String>,
        override val showsOnAniList: Boolean
    ) : ImageHost {
        override suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String): Result<String> =
            delegate(bytes, fileName, mimeType)
    }

    private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    private const val COPY_BUFFER_SIZE = 16 * 1024
    private const val JPEG_QUALITY = 92
    private const val MAX_REASON_CHARS = 160
    private const val COOLDOWN_MILLIS = 10L * 60L * 1000L
    private const val USER_AGENT = "Dantotsu (Android; media upload)"

    private val SUPPORTED_TYPES = setOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/heic",
        "image/heif"
    )

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val imgbbClient: OkHttpClient by lazy {
        defaultClient.newBuilder()
            .cookieJar(SessionCookies())
            .build()
    }

    private val hosts: List<ImageHost> = listOf(
        PomfAwareHost(::uploadToCatbox, showsOnAniList = true),
        PomfAwareHost(::uploadToImgbb, showsOnAniList = true),
        PomfAwareHost(::uploadToPomf, showsOnAniList = false)
    )

    private val benchedUntil = LongArray(hosts.size)

    suspend fun uploadUri(context: Context, uri: Uri): Result<UploadOutcome> {
        val picked = readPickedImage(context, uri).getOrElse { return Result.failure(it) }
        val now = System.currentTimeMillis()
        val order = hosts.indices.filter { synchronized(benchedUntil) { benchedUntil[it] <= now } }
            .ifEmpty { hosts.indices.toList() }

        var last: Result<UploadOutcome> = Result.failure(UploadFailure("No image host is available."))
        for (index in order) {
            val host = hosts[index]
            last = host.upload(picked.bytes, picked.fileName, picked.mimeType)
                .map { url -> UploadOutcome(url = url, showsOnAniList = host.showsOnAniList) }
            synchronized(benchedUntil) {
                benchedUntil[index] = if (last.isSuccess) 0L else System.currentTimeMillis() + COOLDOWN_MILLIS
            }
            if (last.isSuccess) return last
        }
        return last
    }

    private suspend fun uploadToCatbox(bytes: ByteArray, fileName: String, mimeType: String): Result<String> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://catbox.moe/user/api.php")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        val response = try {
            execute(defaultClient, request)
        } catch (e: IOException) {
            return Result.failure(UploadFailure("Could not reach catbox.moe - check your connection."))
        }

        return response.use { res ->
            val text = runCatching { res.body.string().trim() }.getOrDefault("")
            when {
                !res.isSuccessful -> Result.failure(
                    UploadFailure(
                        when (res.code) {
                            429 -> "catbox.moe is rate limiting uploads."
                            in 500..599 -> "catbox.moe is unavailable (HTTP ${res.code})."
                            else -> readableProse(text) ?: "catbox.moe refused the upload (HTTP ${res.code})."
                        }
                    )
                )
                isHttpsServedUrl(text, exactHost = "files.catbox.moe") -> Result.success(text)
                else -> Result.failure(UploadFailure(readableProse(text) ?: "catbox.moe returned no URL."))
            }
        }
    }

    private suspend fun uploadToImgbb(bytes: ByteArray, fileName: String, mimeType: String): Result<String> {
        val siteUrl = "https://imgbb.com"
        val token = try {
            val pageReq = Request.Builder()
                .url("$siteUrl/")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            execute(imgbbClient, pageReq).use { res ->
                if (res.isSuccessful) {
                    val html = runCatching { res.body.string() }.getOrDefault("")
                    Regex("""auth_token\s*=\s*"([0-9a-fA-F]+)"""").find(html)?.groupValues?.get(1)
                } else {
                    null
                }
            }
        } catch (_: IOException) {
            null
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("source", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .addFormDataPart("type", "file")
            .addFormDataPart("action", "upload")
            .addFormDataPart("name", fileName)
            .addFormDataPart("timestamp", System.currentTimeMillis().toString())
            .apply { if (!token.isNullOrEmpty()) addFormDataPart("auth_token", token) }
            .build()
        val request = Request.Builder()
            .url("$siteUrl/json")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Origin", siteUrl)
            .header("Referer", "$siteUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()

        val response = try {
            execute(imgbbClient, request)
        } catch (_: IOException) {
            return Result.failure(UploadFailure("Could not reach imgbb.com - check your connection."))
        }

        return response.use { res ->
            val text = runCatching { res.body.string() }.getOrDefault("")
            if (!res.isSuccessful) {
                Result.failure(UploadFailure(proseNonJson(text) ?: "imgbb.com refused the upload (HTTP ${res.code})."))
            } else {
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@use Result.failure(UploadFailure(proseNonJson(text) ?: "imgbb.com returned no URL."))
                val statusCode = json.optInt("status_code", -1)
                val url = json.optJSONObject("image")?.optString("url").orEmpty()
                if (statusCode == 200 && isHttpsServedUrl(url, exactHost = "i.ibb.co")) {
                    Result.success(url)
                } else {
                    val err = json.optJSONObject("error")?.optString("message").takeUnless { it.isNullOrBlank() }
                        ?: json.optString("status_txt").takeUnless { it.isNullOrBlank() }
                    Result.failure(UploadFailure(err ?: proseNonJson(text) ?: "imgbb.com did not return a URL."))
                }
            }
        }
    }

    private suspend fun uploadToPomf(bytes: ByteArray, fileName: String, mimeType: String): Result<String> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files[]", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://pomf.lain.la/upload.php")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        val response = try {
            execute(defaultClient, request)
        } catch (_: IOException) {
            return Result.failure(UploadFailure("Could not reach image host - check your connection."))
        }

        return response.use { res ->
            val text = runCatching { res.body.string() }.getOrDefault("")
            if (!res.isSuccessful) {
                Result.failure(UploadFailure("Image host refused the upload (HTTP ${res.code})."))
            } else {
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: return@use Result.failure(UploadFailure(proseNonJson(text) ?: "Image host returned no URL."))
                val success = json.optBoolean("success", false)
                val url = json.optJSONArray("files")?.optJSONObject(0)?.optString("url").orEmpty()
                if (success && isHttpsServedUrl(url, hostSuffix = ".lain.la")) {
                    Result.success(url)
                } else {
                    val err = json.optString("error").takeUnless { it.isNullOrBlank() }
                    Result.failure(UploadFailure(err ?: proseNonJson(text) ?: "Image host returned no URL."))
                }
            }
        }
    }

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    continuation.resumeWith(Result.success(response))
                }
            })
        }

    private suspend fun readPickedImage(context: Context, uri: Uri): Result<PickedImage> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val declaredName = queryDisplayName(resolver, uri)
            val declaredType = runCatching { resolver.getType(uri) }.getOrNull()?.lowercase(Locale.ROOT)
            val raw = readCappedBytes(resolver, uri).getOrElse { return@withContext Result.failure(it) }
            if (raw.isEmpty()) return@withContext Result.failure(UploadFailure("Selected file is empty."))

            val actualType = sniffMimeType(raw) ?: declaredType?.takeIf { it in SUPPORTED_TYPES }
                ?: return@withContext Result.failure(UploadFailure("Unsupported image format. Pick a PNG, JPEG, GIF, or WebP."))

            if (actualType == "image/heic" || actualType == "image/heif") {
                return@withContext recodeHeicToJpeg(raw, declaredName)
            }

            Result.success(
                PickedImage(
                    bytes = raw,
                    fileName = buildFileName(actualType, declaredName),
                    mimeType = actualType
                )
            )
        }

    private fun readCappedBytes(resolver: ContentResolver, uri: Uri): Result<ByteArray> = runCatching {
        val input = resolver.openInputStream(uri)
            ?: throw UploadFailure("Selected file could not be opened.")
        input.use { stream ->
            val out = ByteArrayOutputStream(COPY_BUFFER_SIZE * 4)
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_FILE_BYTES) {
                    throw UploadFailure("Image is larger than 20 MB.")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private fun recodeHeicToJpeg(raw: ByteArray, declaredName: String?): Result<PickedImage> {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(raw))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    ?: return Result.failure(UploadFailure("Could not decode HEIC image. Please pick a JPEG or PNG."))
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val jpeg = out.toByteArray()
            if (jpeg.size.toLong() > MAX_FILE_BYTES) {
                return Result.failure(UploadFailure("Converted image is larger than 20 MB."))
            }
            Result.success(
                PickedImage(
                    bytes = jpeg,
                    fileName = buildFileName("image/jpeg", declaredName),
                    mimeType = "image/jpeg"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(UploadFailure("Could not convert image. Please pick a JPEG or PNG."))
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        val projection = arrayOf<String?>(OpenableColumns.DISPLAY_NAME)
        val cursor = resolver.query(uri, projection, null, null, null) ?: return@runCatching null
        cursor.use {
            val col = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && it.moveToFirst()) {
                it.getString(col)?.takeIf { name -> name.isNotBlank() }
            } else {
                null
            }
        }
    }.getOrNull()

    private fun sniffMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return "image/jpeg"

        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return "image/png"

        if (bytes.size >= 6) {
            val header6 = String(bytes, 0, 6, Charsets.US_ASCII)
            if (header6 == "GIF87a" || header6 == "GIF89a") return "image/gif"
        }

        if (bytes.size >= 12) {
            val riff = String(bytes, 0, 4, Charsets.US_ASCII)
            val webp = String(bytes, 8, 4, Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return "image/webp"

            val ftyp = String(bytes, 4, 4, Charsets.US_ASCII)
            if (ftyp == "ftyp") {
                val brand = String(bytes, 8, 4, Charsets.US_ASCII)
                if (brand in setOf("heic", "heix", "hevc", "hevx")) return "image/heic"
                if (brand in setOf("mif1", "msf1", "heif")) return "image/heif"
            }
        }
        return null
    }

    private fun buildFileName(mimeType: String, declaredName: String?): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val base = declaredName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "upload"
        return "$base.$ext"
    }

    private fun readableProse(text: String): String? {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        if (line.isEmpty() || line.startsWith("<")) return null
        return if (line.length <= MAX_REASON_CHARS) line else line.take(MAX_REASON_CHARS) + "…"
    }

    private fun proseNonJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        return readableProse(text)
    }

    private fun isHttpsServedUrl(
        text: String,
        exactHost: String? = null,
        hostSuffix: String? = null
    ): Boolean {
        if (text.any { it.isWhitespace() }) return false
        val url = runCatching { text.toHttpUrlOrNull() }.getOrNull() ?: return false
        if (url.scheme != "https") return false
        val hostMatch = when {
            exactHost != null -> url.host.equals(exactHost, ignoreCase = true)
            hostSuffix != null -> url.host.endsWith(hostSuffix, ignoreCase = true) && url.host.length > hostSuffix.length
            else -> true
        }
        return hostMatch && url.pathSegments.any { it.isNotBlank() }
    }

    private class SessionCookies : CookieJar {
        private val byHost = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(byHost) {
                val now = System.currentTimeMillis()
                byHost[url.host] = (cookies + byHost[url.host].orEmpty())
                    .distinctBy { it.name }
                    .filter { it.expiresAt > now }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(byHost) { byHost[url.host].orEmpty().filter { it.matches(url) } }
    }
}
