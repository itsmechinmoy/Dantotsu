package ani.dantotsu.addons.download

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import ani.dantotsu.util.Logger
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.SessionState
import com.google.gson.Gson
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri
import okhttp3.RequestBody.Companion.toRequestBody

class NativeVideoDownloader(private val context: Context) : DownloadAddonApiV2 {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextSessionId = AtomicLong(1000)
    private val activeSessions = ConcurrentHashMap<Long, DownloadSession>()
    private val uriMap = ConcurrentHashMap<String, Uri>()

    // aria2 process variables
    private var aria2Process: Process? = null
    private var rpcPort: Int = 6800
    private var aria2Secret: String = ""
    private val aria2Mutex = Mutex()

    sealed class DownloadSession {
        abstract val sessionId: Long
        abstract val downloadPath: String
        abstract fun cancel()
        abstract fun getStatus(): String
        abstract fun getStackTrace(): String?
        abstract fun hadError(): Boolean

        data class FFMpegSession(
            override val sessionId: Long,
            override val downloadPath: String
        ) : DownloadSession() {
            override fun cancel() {
                FFmpegKit.cancel(sessionId)
            }

            override fun getStatus(): String {
                FFmpegKitConfig.getFFmpegSessions().forEach {
                    if (it.sessionId == sessionId) {
                        return when (it.state) {
                            SessionState.COMPLETED -> "COMPLETED"
                            SessionState.FAILED -> "FAILED"
                            SessionState.RUNNING -> "RUNNING"
                            else -> "UNKNOWN"
                        }
                    }
                }
                return "UNKNOWN"
            }

            override fun getStackTrace(): String? {
                FFmpegKitConfig.getFFmpegSessions().forEach {
                    if (it.sessionId == sessionId) {
                        return it.failStackTrace
                    }
                }
                return null
            }

            override fun hadError(): Boolean {
                FFmpegKitConfig.getFFmpegSessions().forEach {
                    if (it.sessionId == sessionId) {
                        return it.returnCode.isValueError
                    }
                }
                return false
            }
        }

        class Aria2Session(
            override val sessionId: Long,
            override val downloadPath: String,
            val context: Context,
            val job: Job
        ) : DownloadSession() {
            @Volatile
            var currentStatus: String = "RUNNING"
            @Volatile
            var failReason: String? = null
            @Volatile
            var hasError: Boolean = false

            @Volatile
            var downloadedBytes: Long = 0L
            @Volatile
            var totalBytes: Long = 0L

            override fun cancel() {
                currentStatus = "FAILED"
                failReason = "Cancelled by user"
                job.cancel()
            }

            override fun getStatus(): String = currentStatus
            override fun getStackTrace(): String? = failReason
            override fun hadError(): Boolean = hasError
        }

        class HlsSession(
            override val sessionId: Long,
            override val downloadPath: String,
            val context: Context,
            val job: Job
        ) : DownloadSession() {
            @Volatile
            var currentStatus: String = "RUNNING"
            @Volatile
            var failReason: String? = null
            @Volatile
            var hasError: Boolean = false

            val downloadedBytes = AtomicLong(0L)
            @Volatile
            var totalBytes: Long = 0L

            override fun cancel() {
                currentStatus = "FAILED"
                failReason = "Cancelled by user"
                job.cancel()
            }

            override fun getStatus(): String = currentStatus
            override fun getStackTrace(): String? = failReason
            override fun hadError(): Boolean = hasError
        }

        class ComplexHlsSession(
            override val sessionId: Long,
            override val downloadPath: String,
            val context: Context,
            val job: Job
        ) : DownloadSession() {
            @Volatile
            var currentStatus: String = "RUNNING"
            @Volatile
            var failReason: String? = null
            @Volatile
            var hasError: Boolean = false

            val downloadedBytes = AtomicLong(0L)
            @Volatile
            var totalBytes: Long = 0L

            override fun cancel() {
                currentStatus = "FAILED"
                failReason = "Cancelled by user"
                job.cancel()
            }

            override fun getStatus(): String = currentStatus
            override fun getStackTrace(): String? = failReason
            override fun hadError(): Boolean = hasError
        }
    }

    override fun cancelDownload(sessionId: Long) {
        activeSessions[sessionId]?.cancel()
    }

    override fun getDownloadedBytes(sessionId: Long): Long {
        val session = activeSessions[sessionId] ?: return -1L
        return when (session) {
            is DownloadSession.Aria2Session -> session.downloadedBytes
            is DownloadSession.HlsSession -> session.downloadedBytes.get()
            is DownloadSession.ComplexHlsSession -> session.downloadedBytes.get()
            else -> -1L
        }
    }

    override fun getEstimatedTotalBytes(sessionId: Long): Long {
        val session = activeSessions[sessionId] ?: return -1L
        return when (session) {
            is DownloadSession.Aria2Session -> session.totalBytes
            is DownloadSession.HlsSession -> session.totalBytes
            is DownloadSession.ComplexHlsSession -> session.totalBytes
            else -> -1L
        }
    }

    override fun setDownloadPath(context: Context, uri: Uri): String {
        val path = FFmpegKitConfig.getSafParameterForWrite(context, uri)
        uriMap[path] = uri
        return path
    }

    override fun getReadPath(context: Context, uri: Uri): String {
        return FFmpegKitConfig.getSafParameter(context, uri, "r")
    }

    override suspend fun executeFFProbe(
        videoUrl: String,
        headers: Map<String, String>,
        logCallback: (String) -> Unit
    ) {
        val headersStr = buildHeadersString(headers)
        val request = "${headersStr}-i \"$videoUrl\" -show_entries format=duration -of csv=\"p=0\""
        FFprobeKit.executeAsync(
            request,
            { session ->
                val output = session.output ?: session.allLogsAsString
                if (output != null) {
                    val duration = output.lines().map { it.trim() }.firstOrNull { it.toDoubleOrNull() != null }
                    if (duration != null) {
                        logCallback(duration)
                    }
                }
            }, { log ->
                val msg = log.message
                if (msg != null && msg.trim().toDoubleOrNull() != null) {
                    logCallback(msg.trim())
                }
            })
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override suspend fun executeFFMpeg(
        videoUrl: String,
        downloadPath: String,
        headers: Map<String, String>,
        subtitleUrls: List<Pair<String, String>>,
        audioUrls: List<Pair<String, String>>,
        statCallback: (Double) -> Unit
    ): Long {
        val sessionId = nextSessionId.incrementAndGet()
        val isHls = videoUrl.contains(".m3u8", ignoreCase = true) || videoUrl.contains("m3u8", ignoreCase = true)
        val isDash = videoUrl.contains(".mpd", ignoreCase = true) || videoUrl.contains("mpd", ignoreCase = true)

        if (isHls && subtitleUrls.isEmpty() && audioUrls.isEmpty()) {
            // HLS stream with no extra subtitle/audio tracks -> download using parallel HLS engine
            val tempFile = File(context.cacheDir, "hls_dl_${sessionId}.ts")
            val targetUri = uriMap[downloadPath]
            
            val job = scope.launch {
                try {
                    activeSessions[sessionId]?.let { (it as? DownloadSession.HlsSession)?.currentStatus = "RUNNING" }
                    
                    // Fetch total length if possible to drive statistics callback
                    var totalLength = 0.0
                    executeFFProbe(videoUrl, headers) { durationStr ->
                        durationStr.toDoubleOrNull()?.let { totalLength = it }
                    }

                    runParallelHlsDownload(videoUrl, headers, tempFile, sessionId) { progressPercent ->
                        val duration = if (totalLength > 0.0) totalLength else 100.0
                        statCallback(progressPercent.toDouble() * duration * 10.0)
                    }

                    // Copy completed temp file to SAF path
                    if (targetUri != null) {
                        copyFileToUri(tempFile, targetUri)
                    } else {
                        tempFile.copyTo(File(downloadPath), overwrite = true)
                    }
                    
                    val session = activeSessions[sessionId] as? DownloadSession.HlsSession
                    session?.currentStatus = "COMPLETED"
                } catch (e: Exception) {
                    val session = activeSessions[sessionId] as? DownloadSession.HlsSession
                    session?.currentStatus = "FAILED"
                    session?.hasError = true
                    session?.failReason = e.message
                    Logger.log("Built-in: Parallel HLS download failed: ${e.message}")
                    e.printStackTrace()
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }

            activeSessions[sessionId] = DownloadSession.HlsSession(
                sessionId = sessionId,
                downloadPath = downloadPath,
                context = context,
                job = job
            )
            return sessionId

        } else if (!isHls && !isDash && subtitleUrls.isEmpty() && audioUrls.isEmpty()) {
            // Progressive HTTP/HTTPS URL -> Download using multi-connection aria2 subprocess (with OkHttp fallback)
            val tempFile = File(context.cacheDir, "aria_dl_${sessionId}.bin")
            val targetUri = uriMap[downloadPath]

            val job = scope.launch {
                try {
                    var useAria2 = true
                    try {
                        ensureAria2Running()
                    } catch (ariaException: Exception) {
                        Logger.log("Built-in: aria2 failed to start, falling back to OkHttp progressive downloader: ${ariaException.message}")
                        useAria2 = false
                    }

                    var totalLength = 0.0
                    try {
                        executeFFProbe(videoUrl, headers) { durationStr ->
                            durationStr.toDoubleOrNull()?.let { totalLength = it }
                        }
                    } catch (probeException: Exception) {
                        Logger.log("Built-in: FFProbe failed to get duration: ${probeException.message}")
                    }

                    if (useAria2) {
                        val gid = callAria2AddUri(videoUrl, headers, tempFile)
                            ?: throw IOException("Failed to add URI to aria2")

                        val session = activeSessions[sessionId] as? DownloadSession.Aria2Session
                        session?.currentStatus = "RUNNING"

                        // Poll status
                        while (isActive) {
                            val statusMap = callAria2TellStatus(gid)
                            if (statusMap == null) {
                                delay(1000.milliseconds)
                                continue
                            }
                            val status = statusMap["status"] as? String ?: "active"
                            val totalBytes = (statusMap["totalLength"] as? String)?.toLongOrNull() ?: 0L
                            val completedBytes = (statusMap["completedLength"] as? String)?.toLongOrNull() ?: 0L
                            val errorCode = statusMap["errorCode"] as? String ?: "0"

                            if (status == "complete") {
                                break
                            } else if (status == "error" || errorCode != "0") {
                                throw IOException("aria2 error code: $errorCode")
                            } else if (status == "removed") {
                                throw IOException("aria2 download removed")
                            }

                            val activeSession = activeSessions[sessionId] as? DownloadSession.Aria2Session
                            if (activeSession != null) {
                                activeSession.downloadedBytes = completedBytes
                                activeSession.totalBytes = totalBytes
                            }

                            if (totalBytes > 0L) {
                                val percent = (completedBytes * 100 / totalBytes).toInt()
                                val duration = if (totalLength > 0.0) totalLength else 100.0
                                statCallback(percent.toDouble() * duration * 10.0)
                            }
                            delay(1000.milliseconds)
                        }
                    } else {
                        // Fallback to OkHttp progressive downloader
                        val session = activeSessions[sessionId] as? DownloadSession.Aria2Session
                        session?.currentStatus = "RUNNING"

                        val client = Injekt.get<NetworkHelper>().downloadClient
                        val okHeaders = headers.toHeaders()
                        val req = Request.Builder().url(videoUrl).headers(okHeaders).build()

                        client.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) throw IOException("HTTP error code: ${res.code}")
                            val body = res.body
                            val contentLength = body.contentLength()

                            if (session != null && contentLength > 0L) {
                                session.totalBytes = contentLength
                            }

                            body.byteStream().use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    val buffer = ByteArray(65536)
                                    var bytesRead: Int
                                    var totalBytesRead = 0L
                                    while (isActive) {
                                        bytesRead = input.read(buffer)
                                        if (bytesRead == -1) break
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead

                                        if (session != null) {
                                            session.downloadedBytes = totalBytesRead
                                        }

                                        if (contentLength > 0L) {
                                            val percent = (totalBytesRead * 100 / contentLength).toInt()
                                            val duration = if (totalLength > 0.0) totalLength else 100.0
                                            statCallback(percent.toDouble() * duration * 10.0)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Copy completed temp file to SAF path
                    if (targetUri != null) {
                        copyFileToUri(tempFile, targetUri)
                    } else {
                        tempFile.copyTo(File(downloadPath), overwrite = true)
                    }

                    val activeSession = activeSessions[sessionId] as? DownloadSession.Aria2Session
                    activeSession?.currentStatus = "COMPLETED"
                } catch (e: Exception) {
                    val activeSession = activeSessions[sessionId] as? DownloadSession.Aria2Session
                    activeSession?.currentStatus = "FAILED"
                    activeSession?.hasError = true
                    activeSession?.failReason = e.message
                    Logger.log("Built-in: aria2/okhttp download failed: ${e.message}")
                    e.printStackTrace()
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }

            activeSessions[sessionId] = DownloadSession.Aria2Session(
                sessionId = sessionId,
                downloadPath = downloadPath,
                context = context,
                job = job
            )
            return sessionId

        } else if (isHls) {
            // Complex HLS stream with subtitle/audio tracks -> download HLS video/audio/subtitles locally first, then mux using FFmpeg
            val tempVideoFile = File(context.cacheDir, "hls_dl_${sessionId}_video.ts")
            val targetUri = uriMap[downloadPath]

            val job = scope.launch {
                val localTempFiles = mutableListOf<File>()
                localTempFiles.add(tempVideoFile)
                try {
                    activeSessions[sessionId]?.let { (it as? DownloadSession.ComplexHlsSession)?.currentStatus = "RUNNING" }

                    // 1. Fetch total length if possible to drive statistics callback
                    var totalLength = 0.0
                    try {
                        executeFFProbe(videoUrl, headers) { durationStr ->
                            durationStr.toDoubleOrNull()?.let { totalLength = it }
                        }
                    } catch (e: Exception) {
                        Logger.log("Failed to execute FFProbe for duration: ${e.message}")
                    }

                    // 2. Download video HLS stream
                    val complexSession = activeSessions[sessionId] as? DownloadSession.ComplexHlsSession
                    runParallelHlsDownload(videoUrl, headers, tempVideoFile, sessionId) { progressPercent ->
                        val duration = if (totalLength > 0.0) totalLength else 100.0
                        statCallback(progressPercent.toDouble() * duration * 10.0)
                        
                        // Also update downloaded/total bytes
                        if (complexSession != null) {
                            complexSession.downloadedBytes.set(tempVideoFile.length())
                            val count = progressPercent.toDouble()
                            if (count > 0) {
                                complexSession.totalBytes = (tempVideoFile.length() * 100 / count).toLong()
                            }
                        }
                    }

                    val client = Injekt.get<NetworkHelper>().downloadClient
                    val okHeaders = headers.toHeaders()

                    // 3. Download subtitles
                    val localSubtitles = mutableListOf<Pair<String, String>>()
                    for ((index, sub) in subtitleUrls.withIndex()) {
                        val subTempFile = File(context.cacheDir, "hls_dl_${sessionId}_sub_${index}.vtt")
                        localTempFiles.add(subTempFile)
                        val req = Request.Builder().url(sub.first).headers(okHeaders).build()
                        client.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) throw IOException("Failed to download subtitle: ${sub.first}, code: ${res.code}")
                            val body = res.body
                            subTempFile.outputStream().use { out ->
                                body.byteStream().copyTo(out)
                            }
                            localSubtitles.add(subTempFile.absolutePath to sub.second)
                        }
                    }

                    // 4. Download audio tracks
                    val localAudio = mutableListOf<Pair<String, String>>()
                    for ((index, audio) in audioUrls.withIndex()) {
                        val audioTempFile = File(context.cacheDir, "hls_dl_${sessionId}_audio_${index}.ts")
                        localTempFiles.add(audioTempFile)
                        runParallelHlsDownload(audio.first, headers, audioTempFile, sessionId) {}
                        localAudio.add(audioTempFile.absolutePath to audio.second)
                    }

                    // 5. Mux using FFmpeg locally
                    val finalTempFile = File(context.cacheDir, "hls_dl_${sessionId}_muxed.mkv")
                    localTempFiles.add(finalTempFile)

                    val command = StringBuilder()
                    command.append("-i \"${tempVideoFile.absolutePath}\" ")
                    for (sub in localSubtitles) {
                        command.append("-i \"${sub.first}\" ")
                    }
                    for (audio in localAudio) {
                        command.append("-i \"${audio.first}\" ")
                    }

                    // Map video and audio from main input 0, ignoring other tracks (like timed_id3)
                    command.append("-map 0:v? -map 0:a? ")

                    // Map subtitle tracks from input files (from index 1 to localSubtitles.size)
                    for (i in localSubtitles.indices) {
                        val inputIndex = 1 + i
                        command.append("-map $inputIndex:s? ")
                    }

                    // Map audio tracks from extra audio files
                    for (i in localAudio.indices) {
                        val inputIndex = 1 + localSubtitles.size + i
                        command.append("-map $inputIndex:a? ")
                    }
                    command.append("-c copy ")
                    if (localSubtitles.isNotEmpty()) {
                        command.append("-c:s srt ")
                    }
                    for ((index, sub) in localSubtitles.withIndex()) {
                        command.append("-metadata:s:s:$index language=\"${sub.second}\" ")
                    }
                    for ((index, audio) in localAudio.withIndex()) {
                        command.append("-metadata:s:a:${index + 1} language=\"${audio.second}\" ")
                    }
                    command.append("\"${finalTempFile.absolutePath}\"")

                    val exec = FFmpegKit.execute(command.toString())
                    if (exec.returnCode?.isValueError == true) {
                        throw IOException("FFmpeg muxing failed: ${exec.allLogsAsString}")
                    }

                    // 6. Copy final muxed file to output destination
                    if (targetUri != null) {
                        copyFileToUri(finalTempFile, targetUri)
                    } else {
                        finalTempFile.copyTo(File(downloadPath), overwrite = true)
                    }

                    val session = activeSessions[sessionId] as? DownloadSession.ComplexHlsSession
                    session?.currentStatus = "COMPLETED"
                } catch (e: Exception) {
                    val session = activeSessions[sessionId] as? DownloadSession.ComplexHlsSession
                    session?.currentStatus = "FAILED"
                    session?.hasError = true
                    session?.failReason = e.message
                    Logger.log("Built-in: Complex HLS download failed: ${e.message}")
                    e.printStackTrace()
                } finally {
                    localTempFiles.forEach { file ->
                        if (file.exists()) file.delete()
                    }
                }
            }

            activeSessions[sessionId] = DownloadSession.ComplexHlsSession(
                sessionId = sessionId,
                downloadPath = downloadPath,
                context = context,
                job = job
            )
            return sessionId

        } else {
            // Complex non-HLS stream or has separate tracks/manifests -> fall back to embedded FFmpeg
            Logger.log("Built-in: Falling back to embedded FFmpeg downloader for session $sessionId")
            val command = StringBuilder()
            val headersStr = buildHeadersString(headers)
            command.append("${headersStr}-allowed_extensions ALL -extension_picky 0 -allowed_segment_extensions ALL -i \"$videoUrl\" ")

            for (sub in subtitleUrls) {
                command.append("${headersStr}-i \"${sub.first}\" ")
            }
            for (audio in audioUrls) {
                command.append("${headersStr}-i \"${audio.first}\" ")
            }

            val totalInputs = 1 + subtitleUrls.size + audioUrls.size
            if (totalInputs > 1) {
                for (i in 0 until totalInputs) {
                    command.append("-map $i ")
                }
            }
            command.append("-c copy ")
            if (subtitleUrls.isNotEmpty()) {
                command.append("-c:s srt ")
            }
            for ((index, sub) in subtitleUrls.withIndex()) {
                command.append("-metadata:s:s:$index language=\"${sub.second}\" ")
            }
            for ((index, audio) in audioUrls.withIndex()) {
                command.append("-metadata:s:a:${index + 1} language=\"${audio.second}\" ")
            }
            command.append("\"$downloadPath\" ")

            val exec = FFmpegKit.executeAsync(command.toString(),
                { session ->
                    Logger.log("Built-in FFmpeg session exited: state=${session.state} rc=${session.returnCode}")
                }, {
                    // console logs
                }) {
                statCallback(it.time)
            }
            val rawId = exec.sessionId
            activeSessions[rawId] = DownloadSession.FFMpegSession(rawId, downloadPath)
            return rawId
        }
    }

    override suspend fun customFFMpeg(
        command: String,
        videoUrls: List<String>,
        logCallback: (String) -> Unit
    ): Long {
        val actualCommand = if (command == "1" && videoUrls.size >= 2) {
            "-i ${videoUrls[0]} -c copy ${videoUrls[1]}"
        } else {
            var cmd = command
            videoUrls.forEachIndexed { index, url ->
                cmd = cmd.replace("{$index}", url)
            }
            cmd
        }
        val exec = FFmpegKit.executeAsync(actualCommand,
            { session ->
                Logger.log("Built-in Custom FFmpeg exited: ${session.state} rc=${session.returnCode}")
            }, {
                logCallback(it.message)
            }) {
            // stats
        }
        val rawId = exec.sessionId
        activeSessions[rawId] = DownloadSession.FFMpegSession(rawId, "")
        return rawId
    }

    override suspend fun customFFProbe(
        command: String,
        videoUrls: List<String>,
        logCallback: (String) -> Unit
    ) {
        var cmd = command
        videoUrls.forEachIndexed { index, url ->
            cmd = cmd.replace("{$index}", url)
        }
        FFprobeKit.executeAsync(cmd,
            {
                // logs
            }, {
                logCallback(it.message)
            })
    }

    override fun getState(sessionId: Long): String {
        return activeSessions[sessionId]?.getStatus() ?: "UNKNOWN"
    }

    override fun getStackTrace(sessionId: Long): String? {
        return activeSessions[sessionId]?.getStackTrace()
    }

    override fun hadError(sessionId: Long): Boolean {
        return activeSessions[sessionId]?.hadError() ?: false
    }

    // ==========================================
    // PARALLEL HLS SEGMENT DOWNLOADER (AniZen style)
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun runParallelHlsDownload(
        playlistUrl: String,
        headers: Map<String, String>,
        tempFile: File,
        sessionId: Long,
        progressCallback: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val client = Injekt.get<NetworkHelper>().downloadClient
        val okHeaders = headers.toHeaders()
        
        var currentUrl = playlistUrl
        var lines: List<String>

        // Resolve Master Playlist to picked variant
        while (true) {
            val req = Request.Builder().url(currentUrl).headers(okHeaders).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Failed HLS resolution: ${res.code}")
                lines = res.body.string().lines()
            }
            val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
            if (isMaster) {
                val baseUrl = currentUrl.substringBeforeLast("/") + "/"
                val subUrl = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: throw IOException("Variant HLS playlist empty")
                currentUrl = if (subUrl.startsWith("http")) subUrl else baseUrl + subUrl
                continue
            }
            break
        }

        val baseUrl = currentUrl.substringBeforeLast("/") + "/"
        val segments = mutableListOf<String>()
        var encryptionKeyUrl: String? = null
        var mediaSequence = 0

        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = line.substringAfter(":").trim().toIntOrNull() ?: 0
            } else if (line.startsWith("#EXT-X-KEY:METHOD=AES-128")) {
                val match = Regex("URI=\"([^\"]+)\"").find(line)
                encryptionKeyUrl = match?.groupValues?.get(1)
                if (encryptionKeyUrl != null && !encryptionKeyUrl.startsWith("http")) {
                    encryptionKeyUrl = baseUrl + encryptionKeyUrl
                }
            } else if (!line.startsWith("#") && line.isNotBlank()) {
                segments.add(if (line.startsWith("http")) line else baseUrl + line)
            }
        }

        if (segments.isEmpty()) throw IOException("HLS segments list is empty")

        var secretKey: SecretKeySpec? = null
        if (encryptionKeyUrl != null) {
            val req = Request.Builder().url(encryptionKeyUrl).headers(okHeaders).build()
            client.newCall(req).execute().use { res ->
                val keyBytes = res.body.bytes()
                secretKey = SecretKeySpec(keyBytes, "AES")
            }
        }

        val segmentQueue = segments.mapIndexed { index, url -> index to url }.toMutableList()
        val downloadedCount = java.util.concurrent.atomic.LongAdder()
        
        val host = playlistUrl.toUri().host ?: ""
        val threadCount = calculateDynamicConcurrency(host)
        val segmentFolder = File(context.cacheDir, "hls_parts_${System.currentTimeMillis()}")
        segmentFolder.mkdirs()

        try {
            coroutineScope {
                repeat(threadCount) {
                    launch {
                        while (isActive) {
                            val seg = synchronized(segmentQueue) {
                                if (segmentQueue.isNotEmpty()) segmentQueue.removeAt(0) else null
                            } ?: break
                            val partFile = File(segmentFolder, "seg_${seg.first}.part")
                            
                            var success = false
                            var attempts = 0
                            while (!success) {
                                attempts++
                                try {
                                    val req = Request.Builder().url(seg.second).headers(okHeaders).build()
                                    client.newCall(req).execute().use { res ->
                                        if (!res.isSuccessful) throw IOException("Res code: ${res.code}")
                                        val body = res.body
                                        
                                        body.byteStream().use { input ->
                                            FileOutputStream(partFile).use { fileOut ->
                                                if (secretKey != null) {
                                                    val seqNum = mediaSequence + seg.first
                                                    val ivBytes = ByteBuffer.allocate(16).putLong(8, seqNum.toLong()).array()
                                                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                                    cipher.init(Cipher.DECRYPT_MODE,
                                                        secretKey, IvParameterSpec(ivBytes))
                                                    javax.crypto.CipherOutputStream(fileOut, cipher).use { cipherOut ->
                                                        input.copyTo(cipherOut)
                                                    }
                                                } else {
                                                    input.copyTo(fileOut)
                                                }
                                            }
                                        }
                                        val dataSize = partFile.length()
                                        downloadedCount.increment()
                                        val hlsSession = activeSessions[sessionId] as? DownloadSession.HlsSession
                                        if (hlsSession != null) {
                                            hlsSession.downloadedBytes.addAndGet(dataSize)
                                            val count = downloadedCount.sum().toDouble()
                                            if (count > 0) {
                                                hlsSession.totalBytes = (hlsSession.downloadedBytes.get() * segments.size / count).toLong()
                                            }
                                        }
                                        val percent = (downloadedCount.sum().toDouble() * 100 / segments.size).toInt()
                                        progressCallback(percent)
                                        success = true
                                    }
                                } catch (e: Exception) {
                                    if (attempts >= 5) throw e
                                    delay(500.milliseconds)
                                }
                            }
                        }
                    }
                }
            }

            // Merge segment files
            FileOutputStream(tempFile).use { outStream ->
                val outChannel = outStream.channel
                for (i in segments.indices) {
                    val partFile = File(segmentFolder, "seg_${i}.part")
                    if (partFile.exists()) {
                        java.io.FileInputStream(partFile).use { inStream ->
                            val inChannel = inStream.channel
                            inChannel.transferTo(0, inChannel.size(), outChannel)
                        }
                    }
                }
            }
        } finally {
            segmentFolder.deleteRecursively()
        }
    }

    private fun calculateDynamicConcurrency(host: String): Int {
        if (host.contains("animepahe") || host.contains("sibnet") || host.contains("video.sibnet")) return 1
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return if (activityManager?.isLowRamDevice == true) 4 else 16
    }

    // ==========================================
    // aria2 SUBPROCESS MANAGEMENT
    // ==========================================
    private suspend fun ensureAria2Running(): Unit = aria2Mutex.withLock {
        if (aria2Process != null) return
        rpcPort = findFreePort(6800)
        aria2Secret = UUID.randomUUID().toString()
        val binaryPath = "${context.applicationInfo.nativeLibraryDir}/libaria2c.so"

        val procBuilder = ProcessBuilder(
            binaryPath,
            "--enable-rpc=true",
            "--rpc-listen-port=$rpcPort",
            "--rpc-listen-all=false",
            "--rpc-secret=$aria2Secret",
            "--daemon=false",
            "--max-connection-per-server=16",
            "--split=16",
            "--check-certificate=false",
            "--rpc-max-request-size=10M"
        )
        procBuilder.redirectErrorStream(true)
        val proc = procBuilder.start()
        aria2Process = proc

        // Read outputs to prevent blocking
        Thread {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    while (reader.readLine() != null) {
                        // ignore/discard logs
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }.start()

        delay(500.milliseconds)
    }

    private suspend fun callAria2AddUri(
        videoUrl: String,
        headers: Map<String, String>,
        tempFile: File
    ): String? {
        val uris = listOf(videoUrl)
        val options = mutableMapOf<String, Any>(
            "dir" to tempFile.parentFile!!.absolutePath,
            "out" to tempFile.name
        )
        if (headers.isNotEmpty()) {
            options["header"] = headers.map { "${it.key}: ${it.value}" }
        }
        val params = listOf("token:$aria2Secret", uris, options)
        val resultJson = callAria2Rpc("aria2.addUri", params) ?: return null
        return try {
            JsonParser.parseString(resultJson).asString
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun callAria2TellStatus(gid: String): Map<String, Any>? {
        val params = listOf("token:$aria2Secret", gid, listOf("status", "totalLength", "completedLength", "downloadSpeed", "errorCode"))
        val resultJson = callAria2Rpc("aria2.tellStatus", params) ?: return null
        return try {
            val obj = JsonParser.parseString(resultJson).asJsonObject
            val map = mutableMapOf<String, Any>()
            obj.entrySet().forEach { (k, v) ->
                map[k] = v.asString
            }
            map
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun callAria2Rpc(method: String, params: List<Any>): String? = withContext(Dispatchers.IO) {
        val url = "http://localhost:$rpcPort/jsonrpc"
        val requestMap = mapOf(
            "jsonrpc" to "2.0",
            "id" to "dantotsu",
            "method" to method,
            "params" to params
        )
        val requestBody =
            Gson().toJson(requestMap)
                .toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        try {
            val client = Injekt.get<NetworkHelper>().client
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    val jsonObject = JsonParser.parseString(responseBody).asJsonObject
                    if (jsonObject.has("result")) {
                        return@withContext jsonObject.get("result").toString()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log("Built-in: RPC call failed: ${e.message}")
        }
        return@withContext null
    }

    // ==========================================
    // SAF UTILS & GENERAL HELPERS
    // ==========================================
    private fun copyFileToUri(source: File, targetUri: Uri) {
        context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
            source.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw IOException("Could not open output stream for SAF URI: $targetUri")
    }

    private fun findFreePort(startPort: Int): Int {
        var port = startPort
        while (port < 65535) {
            try {
                ServerSocket(port).use {
                    return port
                }
            } catch (_: IOException) {
                port++
            }
        }
        return startPort
    }

    private fun buildHeadersString(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val sb = StringBuilder("-headers \"")
        for ((key, value) in headers) {
            sb.append("$key: $value\r\n")
        }
        sb.append("\" ")
        return sb.toString()
    }
}
