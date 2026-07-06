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

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val input = client.getInputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val uri = parts[1]

                if (method != "GET" && method != "HEAD") {
                    sendError(client.getOutputStream(), 405, "Method Not Allowed")
                    return
                }

                // URI format: /stream?hash=xxx&index=yyy
                val queryStartIndex = uri.indexOf('?')
                if (queryStartIndex == -1) {
                    sendError(client.getOutputStream(), 400, "Bad Request")
                    return
                }
                val query = uri.substring(queryStartIndex + 1)
                val params = query.split("&").associate {
                    val pair = it.split("=")
                    val key = pair.getOrNull(0) ?: ""
                    val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                    key to value
                }

                val hash = params["hash"] ?: params["link"] ?: ""
                val indexStr = params["index"] ?: ""
                if (hash.isEmpty() || indexStr.isEmpty()) {
                    sendError(client.getOutputStream(), 400, "Missing parameters")
                    return
                }

                val fileIndex = indexStr.toIntOrNull() ?: 0
                val torrentHandle = getTorrentHandle(hash)
                if (torrentHandle == null || !torrentHandle.isValid) {
                    sendError(client.getOutputStream(), 404, "Torrent not found")
                    return
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
                    sendError(client.getOutputStream(), 400, "Metadata timeout")
                    return
                }

                val fileStorage = torrentInfo.files()
                if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) {
                    sendError(client.getOutputStream(), 400, "Invalid file index")
                    return
                }

                // Prioritize this file's pieces and sequential downloading
                torrentHandle.setSequentialRange(0)

                val priorities = Array(fileStorage.numFiles()) { Priority.IGNORE }
                priorities[fileIndex] = Priority.TOP_PRIORITY
                torrentHandle.prioritizeFiles(priorities)

                val fileSize = fileStorage.fileSize(fileIndex)
                val fileOffset = fileStorage.fileOffset(fileIndex)
                val pieceLength = torrentInfo.pieceLength().toLong()

                // Read headers to check for Range
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

                    if (startStr.isNotEmpty()) {
                        startByte = startStr.toLong()
                    }
                    if (endStr.isNotEmpty()) {
                        endByte = endStr.toLong()
                    }
                }

                if (startByte < 0 || startByte >= fileSize || endByte < startByte || endByte >= fileSize) {
                    sendError(client.getOutputStream(), 416, "Requested Range Not Satisfiable")
                    return
                }

                val contentSize = endByte - startByte + 1
                val output = client.getOutputStream()

                // Send response headers
                val contentType = getMimeType(fileStorage.filePath(fileIndex))
                val responseHeaders = if (rangeHeader != null) {
                    "HTTP/1.1 206 Partial Content\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: $contentSize\r\n" +
                            "Content-Range: bytes $startByte-$endByte/$fileSize\r\n" +
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

                if (method == "HEAD") return

                Logger.log("TorrentHttpServer: Start streaming file $fileIndex from $startByte to $endByte (size: $contentSize)")

                val savePath = getSavePath()
                val targetFile = File(fileStorage.filePath(fileIndex, savePath))

                var currentPosition = startByte
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                var fileChannel: RandomAccessFile? = null

                try {
                    while (currentPosition <= endByte) {
                        val torrentByteOffset = fileOffset + currentPosition
                        val pieceIndex = (torrentByteOffset / pieceLength).toInt()

                        // 1. Wait for the piece to be downloaded/verified
                        var waitCount = 0
                        var loggedWait = false
                        while (!torrentHandle.havePiece(pieceIndex)) {
                            if (!isRunning || !torrentHandle.isValid) {
                                break
                            }
                            if (!loggedWait) {
                                Logger.log("TorrentHttpServer: Waiting for piece $pieceIndex (current position: $currentPosition)...")
                                loggedWait = true
                            }
                            // Prioritize the piece and set deadlines
                            if (waitCount % 100 == 0) {
                                torrentHandle.piecePriority(pieceIndex, Priority.TOP_PRIORITY)
                                torrentHandle.setPieceDeadline(pieceIndex, 1000)
                                // Pre-buffer subsequent pieces
                                for (i in 1..4) {
                                    val nextPiece = pieceIndex + i
                                    if (nextPiece < torrentInfo.numPieces()) {
                                        torrentHandle.piecePriority(nextPiece, Priority.TOP_PRIORITY)
                                        torrentHandle.setPieceDeadline(nextPiece, 1000 + i * 1000)
                                    }
                                }
                            }
                            Thread.sleep(50)
                            waitCount++
                        }
                        if (!isRunning || !torrentHandle.isValid) break

                        // 2. Determine how many bytes we can read from the current piece
                        val pieceEndByteInTorrent = (pieceIndex.toLong() + 1) * pieceLength
                        val pieceEndPositionInFile = pieceEndByteInTorrent - fileOffset
                        val remainingToRead = endByte - currentPosition + 1
                        val maxInPiece = pieceEndPositionInFile - currentPosition
                        val toRead = minOf(buffer.size.toLong(), remainingToRead, maxInPiece).toInt()

                        if (toRead <= 0) break

                        // 3. Read directly from targetFile on disk
                        var bytesRead = -1
                        var readAttempts = 0
                        while (readAttempts < 100) {
                            if (!isRunning || !torrentHandle.isValid) break
                            try {
                                if (targetFile.exists()) {
                                    if (fileChannel == null) {
                                        fileChannel = RandomAccessFile(targetFile, "r")
                                    }
                                    if (fileChannel.length() > currentPosition) {
                                        fileChannel.seek(currentPosition)
                                        bytesRead = fileChannel.read(buffer, 0, toRead)
                                        if (bytesRead > 0) {
                                            break
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore and retry
                            }
                            Thread.sleep(50)
                            readAttempts++
                        }

                        if (bytesRead <= 0) {
                            Logger.log("TorrentHttpServer: Failed to read from disk at position $currentPosition")
                            break
                        }

                        // 4. Write to output stream
                        output.write(buffer, 0, bytesRead)
                        output.flush()
                        currentPosition += bytesRead
                    }
                    Logger.log("TorrentHttpServer: Finished streaming requested range for file $fileIndex")
                } catch (e: Exception) {
                    Logger.log("TorrentHttpServer: Exception during stream: ${e.message}")
                } finally {
                    fileChannel?.close()
                }

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
