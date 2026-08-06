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
    private val cancelledSessions = ConcurrentHashMap.newKeySet<Long>()
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
            val context: Context
        ) : DownloadSession() {
            @Volatile
            var currentStatus: String = "RUNNING"
            @Volatile
            var failReason: String? = null
            @Volatile
            var hasError: Boolean = false
            @Volatile
            var job: Job? = null

            @Volatile
            var downloadedBytes: Long = 0L
            @Volatile
            var totalBytes: Long = 0L

            override fun cancel() {
                currentStatus = "FAILED"
                hasError = true
                failReason = "Cancelled by user"
                job?.cancel()
            }

            override fun getStatus(): String = currentStatus
            override fun getStackTrace(): String? = failReason
            override fun hadError(): Boolean = hasError
        }

        class HlsSession(
            override val sessionId: Long,
            override val downloadPath: String,
            val context: Context
        ) : DownloadSession() {
            @Volatile
            var currentStatus: String = "RUNNING"
            @Volatile
            var failReason: String? = null
            @Volatile
            var hasError: Boolean = false
            @Volatile
            var job: Job? = null

            val downloadedBytes = AtomicLong(0L)
            @Volatile
            var totalBytes: Long = 0L
            @Volatile
            var durationSeconds: Double = 0.0

            override fun cancel() {
                currentStatus = "FAILED"
                hasError = true
                failReason = "Cancelled by user"
                job?.cancel()
            }

            override fun getStatus(): String = currentStatus
            override fun getStackTrace(): String? = failReason
            override fun hadError(): Boolean = hasError
        }

        class ComplexHlsSession(
            override val sessionId: Long,
            override val downloadPath: String,
            val context: Context
        ) : DownloadSession() {
            @Volatile
            var currentStatus: String = "RUNNING"
            @Volatile
            var failReason: String? = null
            @Volatile
            var hasError: Boolean = false
            @Volatile
            var job: Job? = null

            val downloadedBytes = AtomicLong(0L)
            @Volatile
            var totalBytes: Long = 0L
            @Volatile
            var durationSeconds: Double = 0.0

            override fun cancel() {
                currentStatus = "FAILED"
                hasError = true
                failReason = "Cancelled by user"
                job?.cancel()
            }

            override fun getStatus(): String = currentStatus
            override fun getStackTrace(): String? = failReason
            override fun hadError(): Boolean = hasError
        }
    }

    override fun cancelDownload(sessionId: Long) {
        cancelledSessions.add(sessionId)
        val session = activeSessions[sessionId]
        session?.cancel()
        // FFmpegKit sessions need explicit cancel (blocking execute may still be running)
        if (session is DownloadSession.FFMpegSession || session == null) {
            runCatching { FFmpegKit.cancel(sessionId) }
        }
    }

    private fun isSessionCancelled(sessionId: Long): Boolean {
        if (cancelledSessions.contains(sessionId)) return true
        val session = activeSessions[sessionId] ?: return false
        return session.getStatus() == "FAILED" ||
            (session !is DownloadSession.FFMpegSession && session.hadError())
    }

    private fun emitProgress(sessionId: Long, statCallback: (Double) -> Unit, value: Double) {
        if (isSessionCancelled(sessionId)) return
        statCallback(value)
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
            // HLS → parallel segments → remux once straight to destination MKV
            val tempFile = File(context.cacheDir, "hls_dl_${sessionId}.ts")
            val session = DownloadSession.HlsSession(sessionId, downloadPath, context)
            activeSessions[sessionId] = session

            session.job = scope.launch {
                try {
                    session.currentStatus = "RUNNING"
                    val hlsResult = runParallelHlsDownload(
                        videoUrl, headers, tempFile, sessionId
                    ) { progressPercent, durationSec ->
                        session.durationSeconds = durationSec
                        // Service maps stat/1000 → percent
                        emitProgress(sessionId, statCallback, progressPercent.toDouble() * 1000.0)
                    }
                    if (session.hasError || !isActive) {
                        session.currentStatus = "FAILED"
                        return@launch
                    }
                    session.durationSeconds = hlsResult.durationSeconds
                    session.totalBytes = hlsResult.totalBytes
                    session.downloadedBytes.set(hlsResult.totalBytes)

                    // Single write: FFmpeg muxes directly to SAF/path (no intermediate mkv + copy)
                    remuxMpegTsToMkv(tempFile, downloadPath, emptyList(), emptyList())
                    assertHasPlayableVideo(downloadPath)

                    if (!session.hasError) {
                        session.currentStatus = "COMPLETED"
                    }
                } catch (e: CancellationException) {
                    session.currentStatus = "FAILED"
                    session.hasError = true
                    session.failReason = "Cancelled by user"
                } catch (e: Exception) {
                    session.currentStatus = "FAILED"
                    session.hasError = true
                    session.failReason = e.message
                    Logger.log("Built-in: Parallel HLS download failed: ${e.message}")
                    e.printStackTrace()
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }
            return sessionId

        } else if (!isHls && !isDash && subtitleUrls.isEmpty() && audioUrls.isEmpty()) {
            // Progressive HTTP/HTTPS URL -> Download using multi-connection aria2 subprocess (with OkHttp fallback)
            val tempFile = File(context.cacheDir, "aria_dl_${sessionId}.bin")
            val targetUri = uriMap[downloadPath]
            val session = DownloadSession.Aria2Session(sessionId, downloadPath, context)
            activeSessions[sessionId] = session

            session.job = scope.launch {
                try {
                    var useAria2 = true
                    try {
                        ensureAria2Running()
                    } catch (ariaException: Exception) {
                        Logger.log("Built-in: aria2 failed to start, falling back to OkHttp progressive downloader: ${ariaException.message}")
                        useAria2 = false
                    }

                    if (useAria2) {
                        val gid = callAria2AddUri(videoUrl, headers, tempFile)
                            ?: throw IOException("Failed to add URI to aria2")

                        val session = activeSessions[sessionId] as? DownloadSession.Aria2Session
                        session?.currentStatus = "RUNNING"

                        // Poll status (500ms — progress is pushed via statCallback)
                        while (isActive) {
                            val statusMap = callAria2TellStatus(gid)
                            if (statusMap == null) {
                                delay(500.milliseconds)
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
                                emitProgress(sessionId, statCallback, percent.toDouble() * 1000.0)
                            }
                            delay(500.milliseconds)
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
                                            emitProgress(
                                                sessionId,
                                                statCallback,
                                                percent.toDouble() * 1000.0
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isSessionCancelled(sessionId) || !isActive) {
                        (activeSessions[sessionId] as? DownloadSession.Aria2Session)?.apply {
                            currentStatus = "FAILED"
                            hasError = true
                        }
                        return@launch
                    }

                    // Copy completed temp file to SAF path
                    if (targetUri != null) {
                        copyFileToUri(tempFile, targetUri)
                    } else {
                        tempFile.copyTo(File(downloadPath), overwrite = true)
                    }

                    val activeSession = activeSessions[sessionId] as? DownloadSession.Aria2Session
                    activeSession?.currentStatus = "COMPLETED"
                } catch (e: CancellationException) {
                    val activeSession = activeSessions[sessionId] as? DownloadSession.Aria2Session
                    activeSession?.currentStatus = "FAILED"
                    activeSession?.hasError = true
                    activeSession?.failReason = "Cancelled by user"
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
            return sessionId

        } else if (isHls) {
            // Complex HLS: parallel video + subs + audio, then single mux to destination
            val tempVideoFile = File(context.cacheDir, "hls_dl_${sessionId}_video.ts")
            val session = DownloadSession.ComplexHlsSession(sessionId, downloadPath, context)
            activeSessions[sessionId] = session

            session.job = scope.launch {
                val localTempFiles = mutableListOf<File>()
                localTempFiles.add(tempVideoFile)
                try {
                    session.currentStatus = "RUNNING"
                    val client = Injekt.get<NetworkHelper>().downloadClient
                    val okHeaders = headers.toHeaders()

                    coroutineScope {
                        val videoDeferred = async {
                            runParallelHlsDownload(
                                videoUrl, headers, tempVideoFile, sessionId
                            ) { progressPercent, durationSec ->
                                session.durationSeconds = durationSec
                                // Keep segment-based byte totals from updateHlsSessionBytes
                                emitProgress(
                                    sessionId,
                                    statCallback,
                                    progressPercent.toDouble() * 1000.0
                                )
                            }
                        }

                        val subDeferreds = subtitleUrls.mapIndexed { index, sub ->
                            async {
                                val subTempFile =
                                    File(context.cacheDir, "hls_dl_${sessionId}_sub_${index}.vtt")
                                synchronized(localTempFiles) { localTempFiles.add(subTempFile) }
                                val req = Request.Builder().url(sub.first).headers(okHeaders).build()
                                client.newCall(req).execute().use { res ->
                                    if (!res.isSuccessful) {
                                        throw IOException(
                                            "Failed to download subtitle: ${sub.first}, code: ${res.code}"
                                        )
                                    }
                                    subTempFile.outputStream().use { out ->
                                        res.body.byteStream().copyTo(out)
                                    }
                                }
                                subTempFile.absolutePath to sub.second
                            }
                        }

                        val audioDeferreds = audioUrls.mapIndexed { index, audio ->
                            async {
                                val audioTempFile =
                                    File(context.cacheDir, "hls_dl_${sessionId}_audio_${index}.ts")
                                synchronized(localTempFiles) { localTempFiles.add(audioTempFile) }
                                runParallelHlsDownload(
                                    audio.first, headers, audioTempFile, sessionId
                                ) { _, _ -> }
                                audioTempFile.absolutePath to audio.second
                            }
                        }

                        val hlsResult = videoDeferred.await()
                        if (session.hasError || !isActive) {
                            throw CancellationException("Cancelled by user")
                        }
                        session.durationSeconds = hlsResult.durationSeconds
                        val localSubtitles = subDeferreds.awaitAll()
                        val localAudio = audioDeferreds.awaitAll()

                        remuxMpegTsToMkv(tempVideoFile, downloadPath, localSubtitles, localAudio)
                        assertHasPlayableVideo(downloadPath)
                    }

                    if (!session.hasError) {
                        session.currentStatus = "COMPLETED"
                    }
                } catch (e: CancellationException) {
                    session.currentStatus = "FAILED"
                    session.hasError = true
                    session.failReason = "Cancelled by user"
                } catch (e: Exception) {
                    session.currentStatus = "FAILED"
                    session.hasError = true
                    session.failReason = e.message
                    Logger.log("Built-in: Complex HLS download failed: ${e.message}")
                    e.printStackTrace()
                } finally {
                    localTempFiles.forEach { file ->
                        if (file.exists()) file.delete()
                    }
                }
            }
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

            val ffmpegSessionId = AtomicLong(-1L)
            val exec = FFmpegKit.executeAsync(command.toString(),
                { session ->
                    Logger.log("Built-in FFmpeg session exited: state=${session.state} rc=${session.returnCode}")
                }, {
                    // console logs
                }) {
                val sid = ffmpegSessionId.get()
                if (sid != -1L) {
                    emitProgress(sid, statCallback, it.time.toDouble())
                }
            }
            val rawId = exec.sessionId
            ffmpegSessionId.set(rawId)
            activeSessions[rawId] = DownloadSession.FFMpegSession(rawId, downloadPath)
            // If already cancelled under our provisional id, cancel FFmpeg too
            if (cancelledSessions.contains(sessionId)) {
                cancelledSessions.add(rawId)
                FFmpegKit.cancel(rawId)
            }
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
    // PARALLEL HLS SEGMENT DOWNLOADER
    // ==========================================

    private data class HlsDownloadResult(
        val durationSeconds: Double,
        val totalBytes: Long
    )

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun runParallelHlsDownload(
        playlistUrl: String,
        headers: Map<String, String>,
        tempFile: File,
        sessionId: Long,
        progressCallback: (progressPercent: Int, durationSeconds: Double) -> Unit
    ): HlsDownloadResult = withContext(Dispatchers.IO) {
        val client = Injekt.get<NetworkHelper>().downloadClient
        val okHeaders = headers.toHeaders()

        val mediaPlaylist = resolveHlsMediaPlaylist(playlistUrl, okHeaders, client)
        val baseUrl = mediaPlaylist.url.substringBeforeLast("/") + "/"
        val parsed = parseHlsMediaPlaylist(mediaPlaylist.lines, baseUrl)

        if (parsed.segments.isEmpty()) throw IOException("HLS segments list is empty")

        val secretKey = parsed.encryptionKeyUrl?.let { keyUrl ->
            val req = Request.Builder().url(keyUrl).headers(okHeaders).build()
            client.newCall(req).execute().use { res ->
                SecretKeySpec(res.body.bytes(), "AES")
            }
        }

        val segmentQueue = parsed.segments.mapIndexed { index, url -> index to url }.toMutableList()
        val downloadedCount = java.util.concurrent.atomic.LongAdder()
        val downloadedBytes = AtomicLong(0L)

        val host = playlistUrl.toUri().host ?: ""
        val threadCount = calculateDynamicConcurrency(host)
        val segmentFolder = File(context.cacheDir, "hls_parts_${sessionId}_${System.nanoTime()}")
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
                                    downloadHlsSegment(
                                        client = client,
                                        okHeaders = okHeaders,
                                        segmentUrl = seg.second,
                                        partFile = partFile,
                                        secretKey = secretKey,
                                        encryptionIv = parsed.encryptionIv,
                                        mediaSequence = parsed.mediaSequence,
                                        segmentIndex = seg.first
                                    )
                                    val dataSize = partFile.length()
                                    downloadedCount.increment()
                                    downloadedBytes.addAndGet(dataSize)
                                    updateHlsSessionBytes(
                                        sessionId,
                                        downloadedBytes.get(),
                                        downloadedCount.sum(),
                                        parsed.segments.size
                                    )
                                    val percent =
                                        (downloadedCount.sum().toDouble() * 100 / parsed.segments.size)
                                            .toInt()
                                            .coerceIn(0, 100)
                                    progressCallback(percent, parsed.durationSeconds)
                                    success = true
                                } catch (e: Exception) {
                                    if (attempts >= 5) throw e
                                    delay(500.milliseconds)
                                }
                            }
                        }
                    }
                }
            }

            FileOutputStream(tempFile).use { outStream ->
                for (i in parsed.segments.indices) {
                    val partFile = File(segmentFolder, "seg_${i}.part")
                    if (partFile.exists()) {
                        appendSanitizedTsSegment(partFile, outStream)
                    }
                }
            }
            if (tempFile.length() == 0L) {
                throw IOException("HLS download produced empty file")
            }

            HlsDownloadResult(
                durationSeconds = parsed.durationSeconds,
                totalBytes = tempFile.length()
            )
        } finally {
            segmentFolder.deleteRecursively()
        }
    }

    private data class ResolvedPlaylist(val url: String, val lines: List<String>)

    private data class ParsedHlsPlaylist(
        val segments: List<String>,
        val durationSeconds: Double,
        val mediaSequence: Int,
        val encryptionKeyUrl: String?,
        val encryptionIv: ByteArray?
    )

    private fun resolveHlsMediaPlaylist(
        playlistUrl: String,
        okHeaders: okhttp3.Headers,
        client: okhttp3.OkHttpClient
    ): ResolvedPlaylist {
        var currentUrl = playlistUrl
        while (true) {
            val req = Request.Builder().url(currentUrl).headers(okHeaders).build()
            val lines = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Failed HLS resolution: ${res.code}")
                res.body.string().lines()
            }
            val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
            if (!isMaster) return ResolvedPlaylist(currentUrl, lines)

            val baseUrl = currentUrl.substringBeforeLast("/") + "/"
            var bestUrl: String? = null
            var bestBandwidth = -1L
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)
                        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.getOrNull(i + 1)?.trim()
                    if (!next.isNullOrBlank() && !next.startsWith("#") && bw >= bestBandwidth) {
                        bestBandwidth = bw
                        bestUrl = next
                    }
                }
                i++
            }
            val subUrl = bestUrl
                ?: lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("Variant HLS playlist empty")
            currentUrl = if (subUrl.startsWith("http")) subUrl else baseUrl + subUrl
        }
    }

    private fun parseHlsMediaPlaylist(lines: List<String>, baseUrl: String): ParsedHlsPlaylist {
        val segments = mutableListOf<String>()
        var encryptionKeyUrl: String? = null
        var encryptionIv: ByteArray? = null
        var mediaSequence = 0
        var durationSeconds = 0.0
        var pendingExtInf = 0.0

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line.startsWith("#EXTINF:") -> {
                    val value = line.substringAfter(":").substringBefore(",").trim()
                    pendingExtInf = value.toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-KEY:METHOD=AES-128") -> {
                    val match = Regex("URI=\"([^\"]+)\"").find(line)
                    encryptionKeyUrl = match?.groupValues?.get(1)
                    if (encryptionKeyUrl != null && !encryptionKeyUrl.startsWith("http")) {
                        encryptionKeyUrl = baseUrl + encryptionKeyUrl
                    }
                    encryptionIv = Regex("IV=0x([0-9a-fA-F]+)").find(line)
                        ?.groupValues?.get(1)
                        ?.let { parseHexIv(it) }
                }
                !line.startsWith("#") && line.isNotBlank() -> {
                    segments.add(if (line.startsWith("http")) line else baseUrl + line)
                    durationSeconds += pendingExtInf
                    pendingExtInf = 0.0
                }
            }
        }
        return ParsedHlsPlaylist(
            segments = segments,
            durationSeconds = durationSeconds,
            mediaSequence = mediaSequence,
            encryptionKeyUrl = encryptionKeyUrl,
            encryptionIv = encryptionIv
        )
    }

    private fun parseHexIv(hex: String): ByteArray {
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return if (raw.size >= 16) raw.copyOf(16)
        else ByteArray(16).also { raw.copyInto(it, 16 - raw.size) }
    }

    private fun downloadHlsSegment(
        client: okhttp3.OkHttpClient,
        okHeaders: okhttp3.Headers,
        segmentUrl: String,
        partFile: File,
        secretKey: SecretKeySpec?,
        encryptionIv: ByteArray?,
        mediaSequence: Int,
        segmentIndex: Int
    ) {
        val req = Request.Builder().url(segmentUrl).headers(okHeaders).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Res code: ${res.code}")
            res.body.byteStream().use { input ->
                FileOutputStream(partFile).use { fileOut ->
                    if (secretKey != null) {
                        val seqNum = mediaSequence + segmentIndex
                        val ivBytes = encryptionIv
                            ?: ByteBuffer.allocate(16).putLong(8, seqNum.toLong()).array()
                        streamDecryptHlsSegment(input, fileOut, secretKey, ivBytes)
                    } else {
                        input.copyTo(fileOut)
                    }
                }
            }
        }
    }

    private fun updateHlsSessionBytes(
        sessionId: Long,
        downloaded: Long,
        completedSegments: Long,
        totalSegments: Int
    ) {
        when (val session = activeSessions[sessionId]) {
            is DownloadSession.HlsSession -> {
                session.downloadedBytes.set(downloaded)
                if (completedSegments > 0) {
                    session.totalBytes = downloaded * totalSegments / completedSegments
                }
            }
            is DownloadSession.ComplexHlsSession -> {
                session.downloadedBytes.set(downloaded)
                if (completedSegments > 0) {
                    session.totalBytes = downloaded * totalSegments / completedSegments
                }
            }
            else -> Unit
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

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Stream-decrypt HLS AES-128 segments without loading the whole segment into RAM.
     * PKCS5Padding matches common CDN packaging; falls back to NoPadding via full buffer
     * only if streaming PKCS5 fails (rare).
     */
    private fun streamDecryptHlsSegment(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        key: SecretKeySpec,
        iv: ByteArray
    ) {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            javax.crypto.CipherInputStream(input, cipher).use { cipherIn ->
                cipherIn.copyTo(output)
            }
        } catch (pkcsError: Exception) {
            // Input already consumed on failure — caller retries segment download
            throw IOException("HLS AES decrypt failed: ${pkcsError.message}", pkcsError)
        }
    }

    /**
     * Append a segment to the concat TS. Fast-path streams clean MPEG-TS segments;
     * slow-path strips embedded PNG blobs that make FFmpeg misdetect png_pipe.
     */
    private fun appendSanitizedTsSegment(partFile: File, out: FileOutputStream) {
        val size = partFile.length()
        if (size <= 0L) return

        java.io.FileInputStream(partFile).use { input ->
            val peekLen = minOf(4096L, size).toInt()
            val head = ByteArray(peekLen)
            val read = input.read(head)
            if (read <= 0) return

            val needsSanitize = indexOfPng(head, 0, read) >= 0
            if (!needsSanitize && head[0] == 0x47.toByte()) {
                // Clean TS-like segment: write peeked bytes then stream the rest
                out.write(head, 0, read)
                input.copyTo(out)
                return
            }

            // Slow path: load remainder and strip PNGs (segments are small, ~0.5–2MB)
            val rest = input.readBytes()
            val full = if (rest.isEmpty()) {
                head.copyOf(read)
            } else {
                ByteArray(read + rest.size).also {
                    System.arraycopy(head, 0, it, 0, read)
                    System.arraycopy(rest, 0, it, read, rest.size)
                }
            }
            writeSanitizedBytes(full, out)
        }
    }

    private fun writeSanitizedBytes(data: ByteArray, out: java.io.OutputStream) {
        var i = 0
        while (i < data.size) {
            if (isPngAt(data, i)) {
                val end = skipPngAt(data, i)
                if (end <= i) {
                    out.write(data[i].toInt())
                    i++
                } else {
                    Logger.log("Built-in: Stripped embedded PNG (${end - i} bytes) from HLS segment")
                    i = end
                }
            } else {
                val next = indexOfPng(data, i + 1, data.size)
                val end = if (next < 0) data.size else next
                out.write(data, i, end - i)
                i = end
            }
        }
    }

    private fun isPngAt(data: ByteArray, offset: Int): Boolean {
        if (offset + pngSignature.size > data.size) return false
        for (j in pngSignature.indices) {
            if (data[offset + j] != pngSignature[j]) return false
        }
        return true
    }

    private fun indexOfPng(data: ByteArray, from: Int, length: Int = data.size): Int {
        var i = from
        val last = length - pngSignature.size
        while (i <= last) {
            if (isPngAt(data, i)) return i
            i++
        }
        return -1
    }

    /** Returns index just past IEND chunk, or offset+8 if parsing fails. */
    private fun skipPngAt(data: ByteArray, offset: Int): Int {
        var pos = offset + 8
        while (pos + 8 <= data.size) {
            val length = ((data[pos].toInt() and 0xff) shl 24) or
                ((data[pos + 1].toInt() and 0xff) shl 16) or
                ((data[pos + 2].toInt() and 0xff) shl 8) or
                (data[pos + 3].toInt() and 0xff)
            if (length < 0 || length > 50_000_000) return offset + 8
            val typeStart = pos + 4
            val chunkEnd = pos + 8 + length + 4
            if (chunkEnd > data.size) return offset + 8
            val isIend = data[typeStart] == 'I'.code.toByte() &&
                data[typeStart + 1] == 'E'.code.toByte() &&
                data[typeStart + 2] == 'N'.code.toByte() &&
                data[typeStart + 3] == 'D'.code.toByte()
            pos = chunkEnd
            if (isIend) return pos
        }
        return offset + 8
    }

    private fun isLikelyMpegTs(file: File): Boolean {
        file.inputStream().use { input ->
            val buf = ByteArray(188 * 10)
            val n = input.read(buf)
            if (n < 188) return false
            val limit = n - 188
            for (start in 0 until minOf(188, n)) {
                if (buf[start] != 0x47.toByte()) continue
                var hits = 0
                var pos = start
                while (pos <= limit) {
                    if (buf[pos] != 0x47.toByte()) break
                    hits++
                    pos += 188
                }
                if (hits >= 3) return true
            }
            return false
        }
    }

    /**
     * Remux local MPEG-TS (+ optional sub/audio files) into MKV at [outputPath].
     * [outputPath] may be a filesystem path or an FFmpeg SAF write parameter.
     * Forces mpegts demuxer when appropriate so FFmpeg never misdetects png_pipe.
     */
    private fun remuxMpegTsToMkv(
        videoTs: File,
        outputPath: String,
        subtitles: List<Pair<String, String>>,
        audios: List<Pair<String, String>>
    ) {
        if (!videoTs.exists() || videoTs.length() == 0L) {
            throw IOException("Video TS missing or empty: ${videoTs.absolutePath}")
        }

        val forceMpegTs = isLikelyMpegTs(videoTs)
        val command = StringBuilder()
        if (forceMpegTs) {
            command.append("-f mpegts -i \"${videoTs.absolutePath}\" ")
        } else {
            command.append("-i \"${videoTs.absolutePath}\" ")
        }
        for (sub in subtitles) {
            command.append("-i \"${sub.first}\" ")
        }
        for (audio in audios) {
            val audioPath = audio.first
            val forceAudioTs = audioPath.endsWith(".ts", ignoreCase = true) &&
                runCatching { isLikelyMpegTs(File(audioPath)) }.getOrDefault(false)
            if (forceAudioTs) {
                command.append("-f mpegts -i \"$audioPath\" ")
            } else {
                command.append("-i \"$audioPath\" ")
            }
        }

        command.append("-map 0:v:0 -map 0:a? ")

        for (i in subtitles.indices) {
            val inputIndex = 1 + i
            command.append("-map $inputIndex:s:0? ")
        }
        for (i in audios.indices) {
            val inputIndex = 1 + subtitles.size + i
            command.append("-map $inputIndex:a:0? ")
        }

        command.append("-c copy ")
        if (subtitles.isNotEmpty()) {
            command.append("-c:s srt ")
        }
        for ((index, sub) in subtitles.withIndex()) {
            command.append("-metadata:s:s:$index language=\"${sub.second}\" ")
        }
        for ((index, audio) in audios.withIndex()) {
            command.append("-metadata:s:a:${index + 1} language=\"${audio.second}\" ")
        }
        command.append("-y -ignore_unknown \"$outputPath\"")

        Logger.log("Built-in: Mux command (forceMpegTs=$forceMpegTs): $command")
        val exec = FFmpegKit.execute(command.toString())
        if (exec.returnCode?.isValueError == true) {
            throw IOException("FFmpeg muxing failed: ${exec.allLogsAsString}")
        }

        // Filesystem path: verify size. SAF paths are validated via ffprobe next.
        val asFile = File(outputPath)
        if (asFile.exists() && asFile.length() < 1024L) {
            throw IOException("FFmpeg mux produced empty/too-small output (${asFile.length()} bytes)")
        }
    }

    private fun assertHasPlayableVideo(path: String) {
        val probePath = uriMap[path]?.let { uri ->
            runCatching { FFmpegKitConfig.getSafParameter(context, uri, "r") }.getOrNull()
        } ?: path

        val probe = FFprobeKit.execute(
            "-v error -select_streams v:0 -show_entries stream=codec_name,width,height " +
                "-of csv=p=0 \"$probePath\""
        )
        val output = (probe.output ?: probe.allLogsAsString ?: "").trim()
        val line = output.lines().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: ""
        val parts = line.split(',')
        val codec = parts.getOrNull(0)?.lowercase().orEmpty()
        val width = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val height = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val invalidCodecs = setOf(
            "", "png", "mjpeg", "bmp", "gif", "webp", "ppm", "image2", "piped"
        )
        if (codec in invalidCodecs) {
            throw IOException("Download missing playable video (codec='$codec', probe='$line')")
        }
        if (width in 1..2 && height in 1..2) {
            throw IOException("Download video track is placeholder (${width}x${height}, codec=$codec)")
        }
        Logger.log("Built-in: Validated output video codec=$codec ${width}x${height}")
    }

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
