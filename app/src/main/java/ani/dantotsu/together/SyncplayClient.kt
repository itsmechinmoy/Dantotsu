package ani.dantotsu.together

import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocketFactory

data class SyncplayParticipant(
    val username: String,
    val room: String? = null,
    val filename: String? = null,
    val durationSec: Double? = null,
    val isReady: Boolean? = null,
    val role: TogetherRole? = null,
    val tagId: String? = null
)

sealed class SyncplayEvent {
    data class Connected(val server: String, val room: String, val username: String) : SyncplayEvent()
    data class Disconnected(val reason: String? = null) : SyncplayEvent()
    data class Error(val message: String) : SyncplayEvent()
    data class StateUpdate(
        val positionSec: Double?,
        val paused: Boolean?,
        val doSeek: Boolean,
        val setBy: String?,
        val serverRttMs: Int,
        val clientLatencySec: Double?,
        val hasClientIgnoring: Boolean,
        val hasServerIgnoring: Boolean
    ) : SyncplayEvent()
    data class ChatReceived(val username: String, val message: String) : SyncplayEvent()
    data class SidebandReceived(val sender: String, val streamId: String, val payload: String) : SyncplayEvent()
    data class UsersChanged(val participants: List<SyncplayParticipant>) : SyncplayEvent()
}

class SyncplayClient {
    companion object {
        private const val TAG = "SyncplayClient"
        private const val SYNCPLAY_VERSION = "1.2.255"
        private const val REAL_VERSION = "1.7.3"
        private const val CHUNK_TIMEOUT_MS = 12_000L
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private var scope: CoroutineScope? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var keepAliveJob: Job? = null

    private val _events = MutableSharedFlow<SyncplayEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SyncplayEvent> = _events

    private var serverHost: String = "syncplay.pl"
    private var serverPort: Int = 8999
    private var currentRoom: String = ""
    private var currentUsername: String = ""

    private val participants = linkedMapOf<String, SyncplayParticipant>()
    private val chunkBuffer = linkedMapOf<String, ChunkAccumulator>()

    private data class ChunkAccumulator(
        val sender: String,
        val streamId: String,
        val count: Int,
        val createdAt: Long = System.currentTimeMillis(),
        val parts: MutableMap<Int, String> = mutableMapOf()
    )

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(server: String, room: String, username: String) {
        disconnect()
        val (host, port) = TogetherLink.parseHostPort(server)
        serverHost = host
        serverPort = port
        currentRoom = room.trim()
        currentUsername = username.trim().ifBlank { "Dantotsu-${UUID.randomUUID().toString().take(4)}" }

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        newScope.launch {
            try {
                val rawSocket = Socket()
                rawSocket.connect(InetSocketAddress(host, port), 12_000)
                rawSocket.soTimeout = 0
                attachSocket(rawSocket, allowStartTls = true)
                sendHello()
                requestRoomList()
            } catch (e: Exception) {
                Logger.log("$TAG: Connect failed: ${e.message}")
                _events.tryEmit(SyncplayEvent.Error(e.message ?: "Unable to connect to $host:$port"))
                cleanupConnection(e.message)
            }
        }
    }

    private fun attachSocket(s: Socket, allowStartTls: Boolean) {
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

        readerJob?.cancel()
        readerJob = scope?.launch {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (allowStartTls && line.contains("\"TLS\"") && line.contains("true")) {
                        // Ignore optional TLS upgrade prompt if plaintext continues cleanly;
                        // Syncplay servers accept continuing on the established connection.
                    }
                    handleIncomingLine(line)
                }
                cleanupConnection("Connection closed by server")
            } catch (e: Exception) {
                if (isActive) {
                    cleanupConnection(e.message ?: "Connection lost")
                }
            }
        }

        keepAliveJob?.cancel()
        keepAliveJob = scope?.launch {
            while (isActive) {
                delay(25_000)
                if (isConnected) {
                    requestRoomList()
                }
            }
        }
    }

    private fun sendLine(obj: JsonObject) {
        val line = obj.toString() + "\r\n"
        scope?.launch {
            runCatching {
                synchronized(this@SyncplayClient) {
                    writer?.write(line)
                    writer?.flush()
                }
            }.onFailure {
                Logger.log("$TAG: Send error: ${it.message}")
            }
        }
    }

    private fun sendHello() {
        sendLine(buildJsonObject {
            put("Hello", buildJsonObject {
                put("username", JsonPrimitive(currentUsername))
                put("room", buildJsonObject {
                    put("name", JsonPrimitive(currentRoom))
                })
                put("version", JsonPrimitive(SYNCPLAY_VERSION))
                put("realversion", JsonPrimitive(REAL_VERSION))
                put("features", buildJsonObject {
                    put("sharedPlaylists", JsonPrimitive(false))
                    put("chat", JsonPrimitive(true))
                    put("featureList", JsonPrimitive(true))
                    put("readiness", JsonPrimitive(true))
                    put("managedRooms", JsonPrimitive(false))
                })
            })
        })
    }

    fun requestRoomList() {
        sendLine(buildJsonObject {
            put("List", JsonPrimitive(true))
        })
    }

    fun sendSetFile(name: String, durationSec: Double? = null, sizeBytes: Long = 1024L) {
        sendLine(buildJsonObject {
            put("Set", buildJsonObject {
                put("file", buildJsonObject {
                    put("name", JsonPrimitive(name.take(250)))
                    durationSec?.let { put("duration", JsonPrimitive(it)) }
                    put("size", JsonPrimitive(sizeBytes))
                })
            })
        })
    }

    fun sendReady(isReady: Boolean, manuallyInitiated: Boolean = true) {
        sendLine(buildJsonObject {
            put("Set", buildJsonObject {
                put("ready", buildJsonObject {
                    put("isReady", JsonPrimitive(isReady))
                    put("manuallyInitiated", JsonPrimitive(manuallyInitiated))
                })
            })
        })
    }

    fun sendState(
        positionSec: Double,
        paused: Boolean,
        doSeek: Boolean = false,
        clientRttSec: Double = 0.05,
        latencyCalculation: Double? = null,
        clientIgnoringOnTheFly: Int? = null,
        serverIgnoringOnTheFly: Int? = null
    ) {
        sendLine(buildJsonObject {
            put("State", buildJsonObject {
                put("playstate", buildJsonObject {
                    put("position", JsonPrimitive(positionSec))
                    put("paused", JsonPrimitive(paused))
                    if (doSeek) put("doSeek", JsonPrimitive(true))
                })
                put("ping", buildJsonObject {
                    put("clientRtt", JsonPrimitive(clientRttSec))
                    latencyCalculation?.let { put("latencyCalculation", JsonPrimitive(it)) }
                    put("clientLatencyCalculation", JsonPrimitive(System.currentTimeMillis() / 1000.0))
                })
                if (clientIgnoringOnTheFly != null || serverIgnoringOnTheFly != null) {
                    put("ignoringOnTheFly", buildJsonObject {
                        clientIgnoringOnTheFly?.let { put("client", JsonPrimitive(it)) }
                        serverIgnoringOnTheFly?.let { put("server", JsonPrimitive(it)) }
                    })
                }
            })
        })
    }

    fun sendChat(message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return
        sendLine(buildJsonObject {
            put("Chat", JsonPrimitive(clean))
        })
    }

    fun sendSideband(streamId: String, payload: String) {
        val chunks = TogetherStream.encodeSidebandChunks(streamId, payload)
        chunks.forEach { chunk ->
            sendLine(buildJsonObject {
                put("Chat", JsonPrimitive(chunk))
            })
        }
    }

    private fun handleIncomingLine(line: String) {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return

        root["Hello"]?.jsonObject?.let { helloObj ->
            isConnected = true
            val actualUsername = helloObj["username"]?.jsonPrimitive?.contentOrNull ?: currentUsername
            currentUsername = actualUsername
            val actualRoom = helloObj["room"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: currentRoom
            currentRoom = actualRoom
            _events.tryEmit(SyncplayEvent.Connected("$serverHost:$serverPort", currentRoom, currentUsername))
        }

        root["Error"]?.jsonObject?.let { errObj ->
            val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: "Syncplay server error"
            _events.tryEmit(SyncplayEvent.Error(msg))
        }

        root["State"]?.jsonObject?.let { stateObj ->
            handleStatePacket(stateObj)
        }

        root["Set"]?.jsonObject?.let { setObj ->
            handleSetPacket(setObj)
        }

        root["List"]?.jsonObject?.let { listObj ->
            handleListPacket(listObj)
        }

        root["Chat"]?.jsonObject?.let { chatObj ->
            handleChatPacket(chatObj)
        }
    }

    private fun handleStatePacket(stateObj: JsonObject) {
        val playstate = stateObj["playstate"] as? JsonObject
        val position = playstate?.get("position")?.jsonPrimitive?.doubleOrNull
        val paused = playstate?.get("paused")?.jsonPrimitive?.booleanOrNull
        val doSeek = playstate?.get("doSeek")?.jsonPrimitive?.booleanOrNull ?: false
        val setBy = playstate?.get("setBy")?.jsonPrimitive?.contentOrNull

        val ping = stateObj["ping"] as? JsonObject
        val clientLatency = ping?.get("latencyCalculation")?.jsonPrimitive?.doubleOrNull
        val serverRttSec = ping?.get("serverRtt")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val serverRttMs = (serverRttSec * 1000.0).toInt().coerceAtLeast(0)

        val ignoring = stateObj["ignoringOnTheFly"] as? JsonObject
        val serverIgn = ignoring?.get("server")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val clientIgn = ignoring?.get("client")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        // Acknowledge server ping immediately per Syncplay protocol
        if (clientLatency != null) {
            sendState(
                positionSec = position ?: 0.0,
                paused = paused ?: true,
                doSeek = false,
                clientRttSec = serverRttSec,
                latencyCalculation = clientLatency,
                clientIgnoringOnTheFly = clientIgn,
                serverIgnoringOnTheFly = serverIgn
            )
        }

        _events.tryEmit(
            SyncplayEvent.StateUpdate(
                positionSec = position,
                paused = paused,
                doSeek = doSeek,
                setBy = setBy,
                serverRttMs = serverRttMs,
                clientLatencySec = clientLatency,
                hasClientIgnoring = clientIgn != null && clientIgn > 0,
                hasServerIgnoring = serverIgn != null && serverIgn > 0
            )
        )
    }

    private fun handleSetPacket(setObj: JsonObject) {
        val userObj = setObj["user"] as? JsonObject
        if (userObj != null) {
            userObj.forEach { (name, value) ->
                val obj = value as? JsonObject ?: return@forEach
                val eventObj = obj["event"] as? JsonObject
                if (eventObj?.get("left")?.jsonPrimitive?.booleanOrNull == true) {
                    participants.remove(name)
                } else {
                    val fileObj = obj["file"] as? JsonObject
                    val rawFileName = fileObj?.get("name")?.jsonPrimitive?.contentOrNull
                    val (role, tagId) = parseRoleTag(rawFileName)
                    val existing = participants[name]
                    participants[name] = SyncplayParticipant(
                        username = name,
                        room = obj["room"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: existing?.room,
                        filename = rawFileName ?: existing?.filename,
                        durationSec = fileObj?.get("duration")?.jsonPrimitive?.doubleOrNull ?: existing?.durationSec,
                        isReady = existing?.isReady,
                        role = role ?: existing?.role,
                        tagId = tagId ?: existing?.tagId
                    )
                }
            }
            _events.tryEmit(SyncplayEvent.UsersChanged(participants.values.toList()))
        }

        val readyObj = setObj["ready"] as? JsonObject
        if (readyObj != null) {
            val name = readyObj["username"]?.jsonPrimitive?.contentOrNull
            val isReady = readyObj["isReady"]?.jsonPrimitive?.booleanOrNull
            if (name != null) {
                val existing = participants[name] ?: SyncplayParticipant(name)
                participants[name] = existing.copy(isReady = isReady)
                _events.tryEmit(SyncplayEvent.UsersChanged(participants.values.toList()))
            }
        }
    }

    private fun handleListPacket(listObj: JsonObject) {
        val roomMembers = listObj[currentRoom] as? JsonObject ?: return
        val updated = linkedMapOf<String, SyncplayParticipant>()
        roomMembers.forEach { (name, value) ->
            val obj = value as? JsonObject ?: return@forEach
            val fileObj = obj["file"] as? JsonObject
            val rawFileName = fileObj?.get("name")?.jsonPrimitive?.contentOrNull
            val (role, tagId) = parseRoleTag(rawFileName)
            val isReady = obj["isReady"]?.jsonPrimitive?.booleanOrNull
            updated[name] = SyncplayParticipant(
                username = name,
                room = currentRoom,
                filename = rawFileName,
                durationSec = fileObj?.get("duration")?.jsonPrimitive?.doubleOrNull,
                isReady = isReady,
                role = role,
                tagId = tagId
            )
        }
        participants.clear()
        participants.putAll(updated)
        _events.tryEmit(SyncplayEvent.UsersChanged(participants.values.toList()))
    }

    private fun handleChatPacket(chatObj: JsonObject) {
        val sender = chatObj["username"]?.jsonPrimitive?.contentOrNull ?: "Room"
        val message = chatObj["message"]?.jsonPrimitive?.contentOrNull ?: return
        if (message.startsWith(TogetherStream.WIRE_PREFIX)) {
            processSidebandChunk(sender, message)
        } else {
            _events.tryEmit(SyncplayEvent.ChatReceived(sender, message))
        }
    }

    private fun processSidebandChunk(sender: String, raw: String) {
        val parts = raw.removePrefix(TogetherStream.WIRE_PREFIX).split('|', limit = 4)
        if (parts.size < 4) return
        val streamId = parts[0]
        val idx = parts[1].toIntOrNull() ?: return
        val count = parts[2].toIntOrNull() ?: return
        val chunk = parts[3]

        val now = System.currentTimeMillis()
        chunkBuffer.entries.removeIf { now - it.value.createdAt > CHUNK_TIMEOUT_MS }

        val key = "$sender|$streamId"
        val acc = chunkBuffer.getOrPut(key) {
            ChunkAccumulator(sender = sender, streamId = streamId, count = count)
        }
        acc.parts[idx] = chunk
        if (acc.parts.size >= acc.count) {
            chunkBuffer.remove(key)
            val fullPayload = (0 until acc.count).joinToString("") { acc.parts[it].orEmpty() }
            if (fullPayload.isNotBlank()) {
                _events.tryEmit(SyncplayEvent.SidebandReceived(sender, streamId, fullPayload))
            }
        }
    }

    private fun parseRoleTag(filename: String?): Pair<TogetherRole?, String?> {
        if (filename.isNullOrBlank()) return null to null
        val match = Regex("""\[dt\|([hg])\|([a-fA-F0-9]+)]""").find(filename) ?: return null to null
        val role = if (match.groupValues[1] == "h") TogetherRole.HOST else TogetherRole.GUEST
        return role to match.groupValues[2]
    }

    fun disconnect() {
        cleanupConnection(null)
    }

    private fun cleanupConnection(reason: String?) {
        val wasConnected = isConnected || socket != null
        isConnected = false
        readerJob?.cancel()
        keepAliveJob?.cancel()
        readerJob = null
        keepAliveJob = null
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
        participants.clear()
        chunkBuffer.clear()
        scope?.cancel()
        scope = null
        if (wasConnected && reason != null) {
            _events.tryEmit(SyncplayEvent.Disconnected(reason))
        }
    }
}
