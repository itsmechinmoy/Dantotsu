package ani.dantotsu.torrent

import ani.dantotsu.util.Logger
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
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

    private data class HttpRequest(
        val method: String,
        val params: Map<String, String>
    )

    private data class StreamContext(
        val torrentHandle: com.frostwire.jlibtorrent.TorrentHandle,
        val torrentInfo: com.frostwire.jlibtorrent.TorrentInfo,
        val fileStorage: com.frostwire.jlibtorrent.FileStorage,
        val fileIndex: Int,
        val fileSize: Long,
        val fileOffset: Long,
        val pieceLength: Long,
        val totalPieces: Int
    )

    private data class ByteRange(
        val startByte: Long,
        val endByte: Long,
        val isRange: Boolean
    )

    private fun parseHttpRequest(reader: java.io.BufferedReader, output: OutputStream): HttpRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val uri = parts[1]

        if (method != "GET" && method != "HEAD") {
            sendError(output, 405, "Method Not Allowed")
            return null
        }

        val queryStartIndex = uri.indexOf('?')
        if (queryStartIndex == -1) {
            sendError(output, 400, "Bad Request")
            return null
        }
        val path = uri.substring(0, queryStartIndex)
        if (!path.startsWith("/stream")) {
            sendError(output, 404, "Not Found")
            return null
        }
        val query = uri.substring(queryStartIndex + 1)
        val params = query.split("&").associate {
            val pair = it.split("=")
            val key = pair.getOrNull(0) ?: ""
            val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
            key to value
        }
        return HttpRequest(method, params)
    }

    private fun resolveTorrentStreamContext(params: Map<String, String>, output: OutputStream): StreamContext? {
        val hash = params["hash"] ?: params["link"] ?: ""
        val indexStr = params["index"] ?: ""
        if (hash.isEmpty() || indexStr.isEmpty()) {
            sendError(output, 400, "Missing parameters")
            return null
        }

        val fileIndex = indexStr.toIntOrNull() ?: 0
        val torrentHandle = getTorrentHandle(hash)
        if (torrentHandle == null || !torrentHandle.isValid) {
            sendError(output, 404, "Torrent not found")
            return null
        }

        var torrentInfo = torrentHandle.torrentFile()
        if (torrentInfo == null) {
            var waitMetadata = 0
            while (torrentHandle.torrentFile() == null && waitMetadata < 300) {
                if (!isRunning || !torrentHandle.isValid) break
                Thread.sleep(100)
                waitMetadata++
            }
            torrentInfo = torrentHandle.torrentFile()
        }

        if (torrentInfo == null) {
            sendError(output, 400, "Metadata timeout")
            return null
        }

        val fileStorage = torrentInfo.files()
        if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) {
            sendError(output, 400, "Invalid file index")
            return null
        }

        val priorities = Array(fileStorage.numFiles()) { Priority.IGNORE }
        priorities[fileIndex] = Priority.TOP_PRIORITY
        torrentHandle.prioritizeFiles(priorities)

        return StreamContext(
            torrentHandle = torrentHandle,
            torrentInfo = torrentInfo,
            fileStorage = fileStorage,
            fileIndex = fileIndex,
            fileSize = fileStorage.fileSize(fileIndex),
            fileOffset = fileStorage.fileOffset(fileIndex),
            pieceLength = torrentInfo.pieceLength().toLong(),
            totalPieces = torrentInfo.numPieces()
        )
    }

    private fun parseRangeHeader(reader: java.io.BufferedReader, fileSize: Long, output: OutputStream): ByteRange? {
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
        var isRange = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isRange = true
            val rangeParts = rangeHeader.substring(6).split("-")
            val startStr = rangeParts.getOrNull(0)?.trim() ?: ""
            val endStr = rangeParts.getOrNull(1)?.trim() ?: ""

            if (startStr.isNotEmpty()) {
                startByte = startStr.toLong()
            }
            if (endStr.isNotEmpty()) {
                endByte = endStr.toLong()
            }
        }

        if (startByte < 0 || startByte >= fileSize || endByte < startByte || endByte >= fileSize) {
            sendError(output, 416, "Requested Range Not Satisfiable")
            return null
        }

        return ByteRange(startByte, endByte, isRange)
    }

    private fun writeResponseHeaders(output: OutputStream, context: StreamContext, range: ByteRange) {
        val contentSize = range.endByte - range.startByte + 1
        val contentType = getMimeType(context.fileStorage.filePath(context.fileIndex))
        val responseHeaders = if (range.isRange) {
            "HTTP/1.1 206 Partial Content\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: $contentSize\r\n" +
                    "Content-Range: bytes ${range.startByte}-${range.endByte}/${context.fileSize}\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${context.fileSize}\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
        }
        output.write(responseHeaders.toByteArray())
        output.flush()
    }

    private fun updateStreamingLookahead(
        context: StreamContext,
        pieceIndex: Int,
        lastPrebufferedPiece: Int,
        lastLookaheadPieces: Int
    ): Pair<Int, Int> {
        if (pieceIndex == lastPrebufferedPiece) {
            return lastPrebufferedPiece to lastLookaheadPieces
        }

        if (lastPrebufferedPiece != -1 && kotlin.math.abs(pieceIndex - lastPrebufferedPiece) > 2) {
            for (p in lastPrebufferedPiece until lastPrebufferedPiece + lastLookaheadPieces) {
                if (p < context.totalPieces && p != pieceIndex) {
                    try {
                        context.torrentHandle.resetPieceDeadline(p)
                        context.torrentHandle.piecePriority(p, Priority.DEFAULT)
                    } catch (_: Exception) {}
                }
            }
        }

        val targetBufferBytes = 20L * 1024L * 1024L
        val safePieceLen = context.pieceLength.coerceAtLeast(1L)
        val lookaheadPieces = (targetBufferBytes / safePieceLen).toInt().coerceIn(4, 32)
        for (i in 0 until lookaheadPieces) {
            val nextPiece = pieceIndex + i
            if (nextPiece < context.totalPieces) {
                context.torrentHandle.piecePriority(nextPiece, Priority.TOP_PRIORITY)
                val deadlineMs = if (i == 0) 0 else 150 + i * 150
                context.torrentHandle.setPieceDeadline(nextPiece, deadlineMs)
            }
        }
        return pieceIndex to lookaheadPieces
    }

    private fun readAndStreamPieceBytes(
        output: OutputStream,
        context: StreamContext,
        targetFile: File,
        currentPosition: Long,
        endByte: Long,
        pieceIndex: Int,
        buffer: ByteArray,
        fileChannelHolder: Array<RandomAccessFile?>
    ): Int {
        val pieceEndByteInTorrent = (pieceIndex.toLong() + 1) * context.pieceLength
        val pieceEndPositionInFile = pieceEndByteInTorrent - context.fileOffset
        val remainingToRead = endByte - currentPosition + 1
        val maxInPiece = pieceEndPositionInFile - currentPosition
        val toRead = minOf(buffer.size.toLong(), remainingToRead, maxInPiece).toInt()

        if (toRead <= 0) return 0

        var bytesRead = -1
        var readAttempts = 0
        while (readAttempts < 200) {
            if (!isRunning || !context.torrentHandle.isValid) break
            try {
                if (targetFile.exists()) {
                    if (fileChannelHolder[0] == null) {
                        fileChannelHolder[0] = RandomAccessFile(targetFile, "r")
                    }
                    val channel = fileChannelHolder[0]
                    if (channel != null && channel.length() > currentPosition) {
                        channel.seek(currentPosition)
                        bytesRead = channel.read(buffer, 0, toRead)
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

        if (bytesRead > 0) {
            output.write(buffer, 0, bytesRead)
            output.flush()
        }
        return bytesRead
    }

    private fun streamTorrentFile(output: OutputStream, context: StreamContext, range: ByteRange) {
        val contentSize = range.endByte - range.startByte + 1
        Logger.log("TorrentHttpServer: Streaming file ${context.fileIndex} from ${range.startByte} to ${range.endByte} (size: $contentSize)")

        val savePath = getSavePath()
        val targetFile = File(context.fileStorage.filePath(context.fileIndex, savePath))

        var currentPosition = range.startByte
        val buffer = ByteArray(128 * 1024)
        val fileChannelHolder = arrayOfNulls<RandomAccessFile>(1)
        var lastPrebufferedPiece = -1
        var lastLookaheadPieces = 0

        try {
            while (currentPosition <= range.endByte) {
                val torrentByteOffset = context.fileOffset + currentPosition
                val pieceIndex = (torrentByteOffset / context.pieceLength).toInt()

                val (newPrebuffered, newLookahead) = updateStreamingLookahead(
                    context, pieceIndex, lastPrebufferedPiece, lastLookaheadPieces
                )
                lastPrebufferedPiece = newPrebuffered
                lastLookaheadPieces = newLookahead

                var waitCount = 0
                var loggedWait = false
                while (!context.torrentHandle.havePiece(pieceIndex)) {
                    if (!isRunning || !context.torrentHandle.isValid) break
                    if (!loggedWait) {
                        Logger.log("TorrentHttpServer: Waiting for piece $pieceIndex at position $currentPosition...")
                        loggedWait = true
                    }
                    Thread.sleep(50)
                    waitCount++
                }
                if (!isRunning || !context.torrentHandle.isValid) break

                val bytesRead = readAndStreamPieceBytes(
                    output, context, targetFile, currentPosition, range.endByte,
                    pieceIndex, buffer, fileChannelHolder
                )
                if (bytesRead <= 0) {
                    Logger.log("TorrentHttpServer: Failed to read from disk at position $currentPosition")
                    break
                }
                currentPosition += bytesRead
            }
            Logger.log("TorrentHttpServer: Finished streaming requested range for file ${context.fileIndex}")
        } catch (e: Exception) {
            Logger.log("TorrentHttpServer: Client disconnected or stream error: ${e.message}")
        } finally {
            fileChannelHolder[0]?.close()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val reader = input.bufferedReader()
                val output = client.getOutputStream()

                val request = parseHttpRequest(reader, output) ?: return
                val context = resolveTorrentStreamContext(request.params, output) ?: return
                val range = parseRangeHeader(reader, context.fileSize, output) ?: return

                writeResponseHeaders(output, context, range)
                if (request.method == "HEAD") return

                streamTorrentFile(output, context, range)
            } catch (_: Exception) {
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
