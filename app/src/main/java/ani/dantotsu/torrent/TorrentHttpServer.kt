package ani.dantotsu.torrent

import ani.dantotsu.util.Logger
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class TorrentHttpServer(
    private val port: Int,
    private val getTorrentHandle: (String) -> TorrentHandle?,
    private val getSavePath: () -> String
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var isRunning = false
    private val requestCounter = AtomicLong(0L)
    private val activeRequests = ConcurrentHashMap<Pair<String, Int>, ActiveStream>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        isRunning = true
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            executor.submit {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        activeSockets.add(socket)
                        executor.submit { handleClient(socket) }
                    } catch (e: Exception) {
                        // socket closed or error
                    }
                }
            }
            Logger.log("TorrentHttpServer: Started listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            isRunning = false
            serverSocket = null
            Logger.log("TorrentHttpServer: Failed to start server: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        activeSockets.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {}
        }
        activeSockets.clear()
        activeRequests.clear()
        executor.shutdownNow()
        Logger.log("TorrentHttpServer: Stopped")
    }

    private data class StreamRequest(val method: String, val hash: String, val fileIndex: Int)

    private data class ByteRange(val start: Long, val end: Long, val isPartial: Boolean)

    private data class ActiveStream(
        val id: Long,
        @Volatile var windowStart: Int = -1,
        @Volatile var windowEnd: Int = -1
    )

    private class StreamingState(
        var lastPrebufferedPiece: Int = -1,
        var lastLookaheadPieces: Int = 0
    )

    private fun parseQueryParams(uri: String): Map<String, String>? {
        val queryStartIndex = uri.indexOf('?')
        if (queryStartIndex == -1) return null
        val path = uri.substring(0, queryStartIndex)
        if (!path.startsWith("/stream")) return null
        val query = uri.substring(queryStartIndex + 1)
        return query.split("&").associate {
            val pair = it.split("=")
            val key = pair.getOrNull(0) ?: ""
            val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
            key to value
        }
    }

    private fun parseStreamRequest(reader: BufferedReader, output: OutputStream): StreamRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val uri = parts[1]
        if (method != "GET" && method != "HEAD") {
            sendError(output, 405, "Method Not Allowed")
            return null
        }
        val params = parseQueryParams(uri)
        if (params == null) {
            val code = if (uri.contains("?")) 404 else 400
            val msg = if (code == 404) "Not Found" else "Bad Request"
            sendError(output, code, msg)
            return null
        }
        val hash = params["hash"] ?: params["link"] ?: ""
        val indexStr = params["index"] ?: ""
        if (hash.isEmpty() || indexStr.isEmpty()) {
            sendError(output, 400, "Missing parameters")
            return null
        }
        return StreamRequest(method, hash, indexStr.toIntOrNull() ?: 0)
    }

    private fun waitForTorrentMetadata(torrentHandle: TorrentHandle, socket: Socket): TorrentInfo? {
        var torrentInfo = torrentHandle.torrentFile()
        if (torrentInfo != null) return torrentInfo
        var waitMetadata = 0
        while (torrentHandle.torrentFile() == null && waitMetadata < METADATA_WAIT_ATTEMPTS) {
            if (!isRunning || !torrentHandle.isValid || socket.isClosed) break
            Thread.sleep(100)
            waitMetadata++
        }
        return torrentHandle.torrentFile()
    }

    private fun resolveTorrentAndInfo(hash: String, socket: Socket, output: OutputStream): Pair<TorrentHandle, TorrentInfo>? {
        var torrentHandle = getTorrentHandle(hash)
        var waitHandle = 0
        while ((torrentHandle == null || !torrentHandle.isValid) && waitHandle < HANDLE_WAIT_ATTEMPTS && isRunning && !socket.isClosed) {
            Thread.sleep(HANDLE_WAIT_POLL_MS)
            torrentHandle = getTorrentHandle(hash)
            waitHandle++
        }
        if (torrentHandle == null || !torrentHandle.isValid) {
            sendError(output, 404, "Torrent not found")
            return null
        }
        val torrentInfo = waitForTorrentMetadata(torrentHandle, socket)
        if (torrentInfo == null) {
            sendError(output, 400, "Metadata timeout")
            return null
        }
        return Pair(torrentHandle, torrentInfo)
    }

    private fun parseByteRange(reader: BufferedReader, fileSize: Long, output: OutputStream): ByteRange? {
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                rangeHeader = line.substring(6).trim()
            }
        }
        var startByte = 0L
        var endByte = fileSize - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeParts = rangeHeader.substring(6).split("-")
            val startStr = rangeParts.getOrNull(0)?.trim() ?: ""
            val endStr = rangeParts.getOrNull(1)?.trim() ?: ""
            if (startStr.isNotEmpty()) startByte = startStr.toLong()
            if (endStr.isNotEmpty()) endByte = endStr.toLong()
        }
        if (startByte < 0 || startByte >= fileSize || endByte < startByte || endByte >= fileSize) {
            sendError(output, 416, "Requested Range Not Satisfiable")
            return null
        }
        return ByteRange(startByte, endByte, rangeHeader != null)
    }

    private fun sendStreamingResponseHeaders(
        output: OutputStream,
        range: ByteRange,
        contentType: String,
        fileSize: Long
    ) {
        val contentSize = range.end - range.start + 1
        val responseHeaders = if (range.isPartial) {
            "HTTP/1.1 206 Partial Content\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $contentSize\r\n" +
                    "Content-Range: bytes ${range.start}-${range.end}/$fileSize\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
        }
        output.write(responseHeaders.toByteArray())
        output.flush()
    }

    private fun deprioritizePreviousWindow(
        torrentHandle: TorrentHandle,
        totalPieces: Int,
        pieceIndex: Int,
        state: StreamingState
    ) {
        if (state.lastPrebufferedPiece != -1 && kotlin.math.abs(pieceIndex - state.lastPrebufferedPiece) > 2) {
            for (p in state.lastPrebufferedPiece until state.lastPrebufferedPiece + state.lastLookaheadPieces) {
                if (p < totalPieces && p != pieceIndex) {
                    try {
                        torrentHandle.resetPieceDeadline(p)
                        torrentHandle.piecePriority(p, Priority.DEFAULT)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun updateLookaheadWindow(
        torrentHandle: TorrentHandle,
        pieceIndex: Int,
        totalPieces: Int,
        pieceLength: Long,
        state: StreamingState,
        activeStream: ActiveStream
    ) {
        if (pieceIndex == state.lastPrebufferedPiece) return
        deprioritizePreviousWindow(torrentHandle, totalPieces, pieceIndex, state)
        state.lastPrebufferedPiece = pieceIndex
        val targetBufferBytes = 20L * 1024L * 1024L
        val safePieceLen = pieceLength.coerceAtLeast(1L)
        val lookaheadPieces = (targetBufferBytes / safePieceLen).toInt().coerceIn(4, 32)
        state.lastLookaheadPieces = lookaheadPieces
        activeStream.windowStart = pieceIndex
        activeStream.windowEnd = minOf(totalPieces - 1, pieceIndex + lookaheadPieces - 1)
        for (i in 0 until lookaheadPieces) {
            val nextPiece = pieceIndex + i
            if (nextPiece < totalPieces) {
                torrentHandle.piecePriority(nextPiece, Priority.TOP_PRIORITY)
                val deadlineMs = if (i == 0) 0 else 150 + i * 150
                torrentHandle.setPieceDeadline(nextPiece, deadlineMs)
            }
        }
    }

    private fun clearWindowDeadlines(
        torrentHandle: TorrentHandle,
        totalPieces: Int,
        state: StreamingState,
        streamKey: Pair<String, Int>,
        activeStream: ActiveStream
    ) {
        if (state.lastPrebufferedPiece == -1 || !torrentHandle.isValid) return
        val currentOwner = activeRequests[streamKey]
        for (p in state.lastPrebufferedPiece until state.lastPrebufferedPiece + state.lastLookaheadPieces) {
            if (p !in 0 until totalPieces) continue
            if (currentOwner != null &&
                currentOwner !== activeStream &&
                currentOwner.windowStart != -1 &&
                p in currentOwner.windowStart..currentOwner.windowEnd
            ) {
                continue
            }
            try {
                torrentHandle.resetPieceDeadline(p)
            } catch (_: Exception) {}
        }
    }

    private fun clientGone(socket: Socket): Boolean {
        if (socket.isClosed || socket.isInputShutdown || socket.isOutputShutdown) return true
        val previous = try { socket.soTimeout } catch (_: Exception) { return true }
        return try {
            socket.soTimeout = 1
            socket.getInputStream().read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: Exception) {
            true
        } finally {
            try { socket.soTimeout = previous } catch (_: Exception) {}
        }
    }

    private fun waitForCurrentPiece(
        torrentHandle: TorrentHandle,
        pieceIndex: Int,
        currentPosition: Long,
        socket: Socket,
        isSuperseded: () -> Boolean
    ): Boolean {
        var loggedWait = false
        var waitedMs = 0L
        while (!torrentHandle.havePiece(pieceIndex)) {
            if (!isRunning || !torrentHandle.isValid || socket.isClosed || isSuperseded()) return false
            if (waitedMs >= PIECE_WAIT_TIMEOUT_MS) {
                Logger.log("TorrentHttpServer: Timed out waiting for piece $pieceIndex after ${waitedMs}ms")
                return false
            }
            if (waitedMs > 0 && waitedMs % CLIENT_CHECK_INTERVAL_MS == 0L && clientGone(socket)) {
                Logger.log("TorrentHttpServer: Client disconnected while waiting for piece $pieceIndex")
                return false
            }
            if (!loggedWait) {
                Logger.log("TorrentHttpServer: Waiting for piece $pieceIndex at position $currentPosition...")
                loggedWait = true
            }
            Thread.sleep(PIECE_WAIT_POLL_MS)
            waitedMs += PIECE_WAIT_POLL_MS
        }
        return isRunning && torrentHandle.isValid && !socket.isClosed && !isSuperseded()
    }

    private fun readChunkFromDisk(
        targetFile: File,
        currentPosition: Long,
        toRead: Int,
        buffer: ByteArray,
        fileChannelHolder: Array<RandomAccessFile?>,
        socket: Socket,
        isSuperseded: () -> Boolean
    ): Int {
        var bytesRead = -1
        var readAttempts = 0
        while (readAttempts < 200) {
            if (!isRunning || socket.isClosed || isSuperseded()) break
            try {
                if (targetFile.exists()) {
                    if (fileChannelHolder[0] == null) {
                        fileChannelHolder[0] = RandomAccessFile(targetFile, "r")
                    }
                    val ch = fileChannelHolder[0]
                    if (ch != null && ch.length() > currentPosition) {
                        ch.seek(currentPosition)
                        bytesRead = ch.read(buffer, 0, toRead)
                        if (bytesRead > 0) break
                    } else {
                        fileChannelHolder[0]?.close()
                        fileChannelHolder[0] = null
                    }
                }
            } catch (_: Exception) {
                fileChannelHolder[0]?.close()
                fileChannelHolder[0] = null
            }
            Thread.sleep(50)
            readAttempts++
        }
        return bytesRead
    }

    private fun prioritizeTargetFile(torrentHandle: TorrentHandle, fileStorage: FileStorage, fileIndex: Int) {
        // Setting file priorities resets every piece priority. A resume opens several range requests
        // at once (header, cues, resume point), so redoing it per request wipes the other reads' windows.
        val current = runCatching { torrentHandle.filePriorities() }.getOrNull()
        if (current != null && current.size == fileStorage.numFiles() &&
            current.withIndex().all { (i, p) -> if (i == fileIndex) p == Priority.TOP_PRIORITY else p == Priority.IGNORE }
        ) return
        val priorities = Array(fileStorage.numFiles()) { Priority.IGNORE }
        priorities[fileIndex] = Priority.TOP_PRIORITY
        torrentHandle.prioritizeFiles(priorities)
    }

    private fun streamFileRange(
        torrentHandle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        range: ByteRange,
        output: OutputStream,
        socket: Socket,
        streamKey: Pair<String, Int>,
        activeStream: ActiveStream
    ) {
        val fileStorage = torrentInfo.files()
        val fileOffset = fileStorage.fileOffset(fileIndex)
        val pieceLength = torrentInfo.pieceLength().toLong()
        val totalPieces = torrentInfo.numPieces()
        val savePath = getSavePath()
        val targetFile = File(fileStorage.filePath(fileIndex, savePath))

        var currentPosition = range.start
        val buffer = ByteArray(128 * 1024)
        val fileChannelHolder: Array<RandomAccessFile?> = arrayOf(null)
        val state = StreamingState()
        val isSuperseded = { activeRequests[streamKey] !== activeStream }
        var aborted = false

        try {
            while (currentPosition <= range.end) {
                if (!isRunning || socket.isClosed || isSuperseded()) {
                    aborted = true
                    break
                }
                val torrentByteOffset = fileOffset + currentPosition
                val pieceIndex = (torrentByteOffset / pieceLength).toInt()
                updateLookaheadWindow(torrentHandle, pieceIndex, totalPieces, pieceLength, state, activeStream)

                if (!waitForCurrentPiece(torrentHandle, pieceIndex, currentPosition, socket, isSuperseded)) {
                    aborted = true
                    break
                }

                val pieceEndByteInTorrent = (pieceIndex.toLong() + 1) * pieceLength
                val pieceEndPositionInFile = pieceEndByteInTorrent - fileOffset
                val remainingToRead = range.end - currentPosition + 1
                val maxInPiece = pieceEndPositionInFile - currentPosition
                val toRead = minOf(buffer.size.toLong(), remainingToRead, maxInPiece).toInt()
                if (toRead <= 0) break

                val bytesRead = readChunkFromDisk(
                    targetFile, currentPosition, toRead, buffer, fileChannelHolder, socket, isSuperseded
                )
                if (bytesRead <= 0) {
                    Logger.log("TorrentHttpServer: Failed to read from disk at position $currentPosition")
                    aborted = true
                    break
                }
                output.write(buffer, 0, bytesRead)
                output.flush()
                currentPosition += bytesRead
            }
            if (!aborted) {
                Logger.log("TorrentHttpServer: Finished streaming requested range for file $fileIndex")
            }
        } catch (e: Exception) {
            aborted = true
            Logger.log("TorrentHttpServer: Client disconnected or stream error: ${e.message}")
        } finally {
            if (aborted) {
                clearWindowDeadlines(torrentHandle, totalPieces, state, streamKey, activeStream)
            }
            fileChannelHolder[0]?.close()
        }
    }

    private fun handleClient(socket: Socket) {
        var streamKey: Pair<String, Int>? = null
        var activeStream: ActiveStream? = null
        try {
            socket.use { client ->
                try {
                    // Enable TCP_NODELAY for lower streaming latency
                    client.tcpNoDelay = true

                    val output = client.getOutputStream()
                    val reader = client.getInputStream().bufferedReader()
                    val request = parseStreamRequest(reader, output) ?: return
                    val torrentData = resolveTorrentAndInfo(request.hash, client, output) ?: return
                    val (torrentHandle, torrentInfo) = torrentData
                    val fileStorage = torrentInfo.files()
                    if (request.fileIndex < 0 || request.fileIndex >= fileStorage.numFiles()) {
                        sendError(output, 400, "Invalid file index")
                        return
                    }

                    prioritizeTargetFile(torrentHandle, fileStorage, request.fileIndex)

                    val fileSize = fileStorage.fileSize(request.fileIndex)
                    val range = parseByteRange(reader, fileSize, output) ?: return

                    val contentType = getMimeType(fileStorage.filePath(request.fileIndex))
                    sendStreamingResponseHeaders(output, range, contentType, fileSize)

                    if (request.method == "HEAD") return

                    val key = Pair(request.hash.uppercase(), request.fileIndex)
                    val pieceLength = torrentInfo.pieceLength().toLong().coerceAtLeast(1L)
                    val initialPiece = ((fileStorage.fileOffset(request.fileIndex) + range.start) / pieceLength).toInt()
                    val stream = ActiveStream(requestCounter.incrementAndGet(), initialPiece, initialPiece)
                    streamKey = key
                    activeStream = stream
                    activeRequests[key] = stream

                    Logger.log("TorrentHttpServer: Streaming file ${request.fileIndex} from ${range.start} to ${range.end} (size: ${range.end - range.start + 1})")
                    streamFileRange(torrentHandle, torrentInfo, request.fileIndex, range, output, client, key, stream)
                } catch (e: Exception) {
                    // Connection reset by peer or similar
                }
            }
        } finally {
            activeSockets.remove(socket)
            val key = streamKey
            val stream = activeStream
            if (key != null && stream != null) {
                activeRequests.remove(key, stream)
            }
        }
    }

    private fun sendError(output: OutputStream, statusCode: Int, message: String) {
        try {
            val response = """
                HTTP/1.1 $statusCode $message
                Content-Type: text/plain
                Content-Length: ${message.length}
                Connection: close
                
                $message
            """.trimIndent().replace("\n", "\r\n")
            output.write(response.toByteArray())
            output.flush()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "srt" -> "text/srt"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        const val HANDLE_WAIT_POLL_MS = 50L
        const val HANDLE_WAIT_ATTEMPTS = 60
        const val PIECE_WAIT_POLL_MS = 50L
        // below the player's 150 s read timeout for local torrents; a slow swarm is a stall, not a dead source
        const val PIECE_WAIT_TIMEOUT_MS = 120_000L
        const val METADATA_WAIT_ATTEMPTS = 900
        const val CLIENT_CHECK_INTERVAL_MS = 500L
    }
}
