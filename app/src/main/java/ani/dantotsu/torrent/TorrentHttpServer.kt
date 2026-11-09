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
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class TorrentHttpServer(
    private val port: Int,
    private val getTorrentHandle: (String) -> TorrentHandle?,
    private val getSavePath: () -> String
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var isRunning = false

    fun start() {
        isRunning = true
        try {
            serverSocket = ServerSocket(port)
            executor.submit {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.submit { handleClient(socket) }
                    } catch (e: Exception) {
                        // socket closed or error
                    }
                }
            }
            Logger.log("TorrentHttpServer: Started listening on port $port")
        } catch (e: Exception) {
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
        executor.shutdownNow()
        Logger.log("TorrentHttpServer: Stopped")
    }

    private data class StreamRequest(val method: String, val hash: String, val fileIndex: Int)

    private data class ByteRange(val start: Long, val end: Long, val isPartial: Boolean)

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

    private fun waitForTorrentMetadata(torrentHandle: TorrentHandle): TorrentInfo? {
        var torrentInfo = torrentHandle.torrentFile()
        if (torrentInfo != null) return torrentInfo
        var waitMetadata = 0
        while (torrentHandle.torrentFile() == null && waitMetadata < 300) {
            if (!isRunning || !torrentHandle.isValid) break
            Thread.sleep(100)
            waitMetadata++
        }
        return torrentHandle.torrentFile()
    }

    private fun resolveTorrentAndInfo(hash: String, output: OutputStream): Pair<TorrentHandle, TorrentInfo>? {
        val torrentHandle = getTorrentHandle(hash)
        if (torrentHandle == null || !torrentHandle.isValid) {
            sendError(output, 404, "Torrent not found")
            return null
        }
        val torrentInfo = waitForTorrentMetadata(torrentHandle)
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
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "Connection: keep-alive\r\n" +
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
        state: StreamingState
    ) {
        if (pieceIndex == state.lastPrebufferedPiece) return
        deprioritizePreviousWindow(torrentHandle, totalPieces, pieceIndex, state)
        state.lastPrebufferedPiece = pieceIndex
        val targetBufferBytes = 20L * 1024L * 1024L
        val safePieceLen = pieceLength.coerceAtLeast(1L)
        val lookaheadPieces = (targetBufferBytes / safePieceLen).toInt().coerceIn(4, 32)
        state.lastLookaheadPieces = lookaheadPieces
        for (i in 0 until lookaheadPieces) {
            val nextPiece = pieceIndex + i
            if (nextPiece < totalPieces) {
                torrentHandle.piecePriority(nextPiece, Priority.TOP_PRIORITY)
                val deadlineMs = if (i == 0) 0 else 150 + i * 150
                torrentHandle.setPieceDeadline(nextPiece, deadlineMs)
            }
        }
    }

    private fun waitForCurrentPiece(
        torrentHandle: TorrentHandle,
        pieceIndex: Int,
        currentPosition: Long
    ): Boolean {
        var loggedWait = false
        while (!torrentHandle.havePiece(pieceIndex)) {
            if (!isRunning || !torrentHandle.isValid) return false
            if (!loggedWait) {
                Logger.log("TorrentHttpServer: Waiting for piece $pieceIndex at position $currentPosition...")
                loggedWait = true
            }
            Thread.sleep(50)
        }
        return isRunning && torrentHandle.isValid
    }

    private fun readChunkFromDisk(
        targetFile: File,
        currentPosition: Long,
        toRead: Int,
        buffer: ByteArray,
        fileChannelHolder: Array<RandomAccessFile?>
    ): Int {
        var bytesRead = -1
        var readAttempts = 0
        while (readAttempts < 200) {
            if (!isRunning) break
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
        val priorities = Array(fileStorage.numFiles()) { Priority.IGNORE }
        priorities[fileIndex] = Priority.TOP_PRIORITY
        torrentHandle.prioritizeFiles(priorities)
    }

    private fun streamFileRange(
        torrentHandle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        range: ByteRange,
        output: OutputStream
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

        try {
            while (currentPosition <= range.end) {
                val torrentByteOffset = fileOffset + currentPosition
                val pieceIndex = (torrentByteOffset / pieceLength).toInt()
                updateLookaheadWindow(torrentHandle, pieceIndex, totalPieces, pieceLength, state)

                if (!waitForCurrentPiece(torrentHandle, pieceIndex, currentPosition)) break

                val pieceEndByteInTorrent = (pieceIndex.toLong() + 1) * pieceLength
                val pieceEndPositionInFile = pieceEndByteInTorrent - fileOffset
                val remainingToRead = range.end - currentPosition + 1
                val maxInPiece = pieceEndPositionInFile - currentPosition
                val toRead = minOf(buffer.size.toLong(), remainingToRead, maxInPiece).toInt()
                if (toRead <= 0) break

                val bytesRead = readChunkFromDisk(targetFile, currentPosition, toRead, buffer, fileChannelHolder)
                if (bytesRead <= 0) {
                    Logger.log("TorrentHttpServer: Failed to read from disk at position $currentPosition")
                    break
                }
                output.write(buffer, 0, bytesRead)
                output.flush()
                currentPosition += bytesRead
            }
            Logger.log("TorrentHttpServer: Finished streaming requested range for file $fileIndex")
        } catch (e: Exception) {
            Logger.log("TorrentHttpServer: Client disconnected or stream error: ${e.message}")
        } finally {
            fileChannelHolder[0]?.close()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                // Enable TCP_NODELAY for lower streaming latency
                client.tcpNoDelay = true

                val output = client.getOutputStream()
                val reader = client.getInputStream().bufferedReader()
                val request = parseStreamRequest(reader, output) ?: return
                val torrentData = resolveTorrentAndInfo(request.hash, output) ?: return
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

                Logger.log("TorrentHttpServer: Streaming file ${request.fileIndex} from ${range.start} to ${range.end} (size: ${range.end - range.start + 1})")
                streamFileRange(torrentHandle, torrentInfo, request.fileIndex, range, output)
            } catch (e: Exception) {
                // Connection reset by peer or similar
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
}
