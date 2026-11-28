package ani.dantotsu.together

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.settings.saving.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

enum class WatchRoomStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class WatchRoomMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isSystem: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class WatchRoomState(
    val status: WatchRoomStatus = WatchRoomStatus.DISCONNECTED,
    val room: String = "",
    val server: String = TogetherLink.DEFAULT_SERVER,
    val username: String = "",
    val role: TogetherRole = TogetherRole.GUEST,
    val isReady: Boolean = true,
    val rttMs: Int = 0,
    val participants: List<SyncplayParticipant> = emptyList(),
    val messages: List<WatchRoomMessage> = emptyList(),
    val sharedStream: TogetherStream? = null,
    val localStreamName: String? = null,
    val errorMessage: String? = null
) {
    val isConnected: Boolean get() = status == WatchRoomStatus.CONNECTED
    val isHost: Boolean get() = role == TogetherRole.HOST
    val inviteUrl: String get() = if (room.isNotBlank()) TogetherLink.createUrl(room, server) else ""
}

interface WatchRoomPlayerBridge {
    fun isPlayerReady(): Boolean
    fun getCurrentPositionMs(): Long
    fun isPlayerPaused(): Boolean
    fun syncPlay()
    fun syncPause()
    fun syncSeekTo(positionMs: Long)
    fun syncPlaybackSpeed(speed: Float)
    fun getDefaultPlaybackSpeed(): Float
    fun getCurrentShareableStream(): TogetherStream?
}

object WatchRoomManager {
    private const val PREF_SERVER = "together_last_server"
    private const val PREF_USERNAME = "together_last_username"
    private const val SUPPRESS_SYNC_MS = 1200L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = SyncplayClient()
    private val localTagId = UUID.randomUUID().toString().replace("-", "").take(8)
    private var lastStreamId: String = ""

    private val _state = MutableStateFlow(
        WatchRoomState(
            server = loadSavedServer(),
            username = loadDefaultUsername()
        )
    )
    val state: StateFlow<WatchRoomState> = _state.asStateFlow()

    @Volatile
    private var playerBridge: WatchRoomPlayerBridge? = null
    private var eventCollectorJob: Job? = null
    private var syncTickerJob: Job? = null
    private var suppressOutgoingUntilMs: Long = 0L
    private var localPausedSnapshot: Boolean = true
    private var speedBoostedDown: Boolean = false

    init {
        eventCollectorJob = scope.launch {
            client.events.collect { event ->
                handleClientEvent(event)
            }
        }
    }

    fun loadSavedServer(): String =
        PrefManager.getNullableCustomVal(PREF_SERVER, null, String::class.java)
            ?.takeIf { it.isNotBlank() } ?: TogetherLink.DEFAULT_SERVER

    fun loadDefaultUsername(): String {
        val saved = PrefManager.getNullableCustomVal(PREF_USERNAME, null, String::class.java)
            ?.takeIf { it.isNotBlank() }
        if (saved != null) return saved
        return Anilist.username?.takeIf { it.isNotBlank() }
            ?: MAL.username?.takeIf { it.isNotBlank() }
            ?: "DantotsuUser"
    }

    fun generateRandomRoomName(): String = "dt-${UUID.randomUUID().toString().take(6)}"

    fun createRoom(
        roomName: String = generateRandomRoomName(),
        server: String = loadSavedServer(),
        username: String = loadDefaultUsername()
    ) {
        connect(roomName, server, username, TogetherRole.HOST)
    }

    fun joinRoom(
        roomName: String,
        server: String = loadSavedServer(),
        username: String = loadDefaultUsername()
    ) {
        connect(roomName, server, username, TogetherRole.GUEST)
    }

    private fun connect(roomName: String, server: String, username: String, role: TogetherRole) {
        val cleanRoom = roomName.trim().ifBlank { generateRandomRoomName() }
        val cleanServer = TogetherLink.normalizeServer(server)
        val cleanUser = username.trim().ifBlank { loadDefaultUsername() }

        PrefManager.setCustomVal(PREF_SERVER, cleanServer)
        PrefManager.setCustomVal(PREF_USERNAME, cleanUser)

        _state.update {
            it.copy(
                status = WatchRoomStatus.CONNECTING,
                room = cleanRoom,
                server = cleanServer,
                username = cleanUser,
                role = role,
                participants = emptyList(),
                messages = listOf(WatchRoomMessage(sender = "System", text = "Connecting to $cleanRoom ($cleanServer)...", isSystem = true)),
                sharedStream = null,
                errorMessage = null
            )
        }
        client.connect(cleanServer, cleanRoom, cleanUser)
    }

    fun leaveRoom() {
        syncTickerJob?.cancel()
        syncTickerJob = null
        if (_state.value.isHost && _state.value.isConnected) {
            client.sendSideband(lastStreamId.ifBlank { "room" }, "X:close")
        }
        client.disconnect()
        _state.update {
            it.copy(
                status = WatchRoomStatus.DISCONNECTED,
                participants = emptyList(),
                sharedStream = null,
                errorMessage = null
            )
        }
    }

    fun toggleReady() {
        val current = _state.value
        if (!current.isConnected) return
        val next = !current.isReady
        _state.update { it.copy(isReady = next) }
        client.sendReady(next, manuallyInitiated = true)
    }

    fun sendChat(message: String) {
        if (!_state.value.isConnected || message.isBlank()) return
        client.sendChat(message)
    }

    fun shareCurrentPlayerStream(): Boolean {
        val bridge = playerBridge ?: return false
        val stream = bridge.getCurrentShareableStream() ?: return false
        return publishSharedStream(stream)
    }

    fun publishSharedStream(stream: TogetherStream): Boolean {
        val st = _state.value
        if (!st.isConnected) return false
        if (!TogetherStream.isValidShareUrl(stream.videoUrl)) return false

        val payload = TogetherStream.serializeStreamPayload(stream)
        val streamId = UUID.randomUUID().toString().replace("-", "").take(6)
        lastStreamId = streamId
        val annotated = stream.copy(sharedBy = st.username, sharedById = streamId)
        _state.update {
            it.copy(
                role = TogetherRole.HOST,
                sharedStream = annotated,
                localStreamName = "${stream.mediaTitle} ${stream.episodeLabel}".trim()
            )
        }
        updateFileMetadata(annotated.mediaTitle + " - " + annotated.episodeLabel)
        client.sendSideband(streamId, payload)
        appendSystemMessage("Shared stream: ${stream.mediaTitle} (${stream.episodeLabel})")
        return true
    }

    fun attachPlayer(bridge: WatchRoomPlayerBridge, initialLabel: String? = null) {
        playerBridge = bridge
        val st = _state.value
        if (!initialLabel.isNullOrBlank()) {
            _state.update { it.copy(localStreamName = initialLabel) }
        }
        if (st.isConnected) {
            updateFileMetadata(initialLabel ?: st.localStreamName ?: "Dantotsu Player")
            startSyncTicker()
            if (st.isHost) {
                bridge.getCurrentShareableStream()?.let { stream ->
                    if (TogetherStream.isValidShareUrl(stream.videoUrl)) {
                        publishSharedStream(stream)
                    }
                }
            }
        }
    }

    fun detachPlayer(bridge: WatchRoomPlayerBridge) {
        if (playerBridge === bridge) {
            playerBridge = null
            syncTickerJob?.cancel()
            syncTickerJob = null
            if (_state.value.isConnected) {
                updateFileMetadata("Browsing Dantotsu")
            }
        }
    }

    fun notifyLocalPlayPause(paused: Boolean, positionMs: Long) {
        if (!_state.value.isConnected) return
        if (System.currentTimeMillis() < suppressOutgoingUntilMs) return
        localPausedSnapshot = paused
        client.sendState(
            positionSec = (positionMs / 1000.0).coerceAtLeast(0.0),
            paused = paused,
            doSeek = false,
            clientRttSec = _state.value.rttMs / 1000.0
        )
    }

    fun notifyLocalSeek(positionMs: Long, paused: Boolean) {
        if (!_state.value.isConnected) return
        if (System.currentTimeMillis() < suppressOutgoingUntilMs) return
        client.sendState(
            positionSec = (positionMs / 1000.0).coerceAtLeast(0.0),
            paused = paused,
            doSeek = true,
            clientRttSec = _state.value.rttMs / 1000.0
        )
    }

    private fun startSyncTicker() {
        syncTickerJob?.cancel()
        syncTickerJob = scope.launch {
            while (isActive) {
                delay(1500)
                val bridge = playerBridge ?: continue
                if (!_state.value.isConnected || !bridge.isPlayerReady()) continue
                val posSec = bridge.getCurrentPositionMs() / 1000.0
                val paused = bridge.isPlayerPaused()
                client.sendState(
                    positionSec = posSec,
                    paused = paused,
                    doSeek = false,
                    clientRttSec = _state.value.rttMs / 1000.0
                )
            }
        }
    }

    private fun updateFileMetadata(label: String) {
        val st = _state.value
        if (!st.isConnected) return
        val roleCode = if (st.isHost) "h" else "g"
        val cleanLabel = label.take(220)
        client.sendSetFile("$cleanLabel [dt|$roleCode|$localTagId]")
    }

    private fun appendSystemMessage(text: String) {
        _state.update {
            val next = (it.messages + WatchRoomMessage(sender = "System", text = text, isSystem = true)).takeLast(120)
            it.copy(messages = next)
        }
    }

    private fun handleClientEvent(event: SyncplayEvent) {
        when (event) {
            is SyncplayEvent.Connected -> {
                _state.update {
                    it.copy(
                        status = WatchRoomStatus.CONNECTED,
                        room = event.room,
                        server = event.server,
                        username = event.username,
                        errorMessage = null
                    )
                }
                appendSystemMessage("Joined room '${event.room}' as ${event.username}")
                client.sendReady(_state.value.isReady, manuallyInitiated = false)
                val activeLabel = playerBridge?.getCurrentShareableStream()?.let {
                    "${it.mediaTitle} - ${it.episodeLabel}"
                } ?: _state.value.localStreamName ?: "Browsing Dantotsu"
                updateFileMetadata(activeLabel)
                if (playerBridge != null) {
                    startSyncTicker()
                }
                if (!_state.value.isHost) {
                    client.sendSideband(localTagId, "W:$localTagId")
                } else {
                    playerBridge?.getCurrentShareableStream()?.let { stream ->
                        publishSharedStream(stream)
                    }
                }
            }
            is SyncplayEvent.Disconnected -> {
                syncTickerJob?.cancel()
                syncTickerJob = null
                _state.update {
                    it.copy(
                        status = WatchRoomStatus.DISCONNECTED,
                        participants = emptyList(),
                        errorMessage = event.reason
                    )
                }
                appendSystemMessage(event.reason ?: "Disconnected from room")
            }
            is SyncplayEvent.Error -> {
                _state.update {
                    it.copy(
                        status = if (it.isConnected) it.status else WatchRoomStatus.ERROR,
                        errorMessage = event.message
                    )
                }
                appendSystemMessage("Error: ${event.message}")
            }
            is SyncplayEvent.UsersChanged -> {
                _state.update { it.copy(participants = event.participants) }
            }
            is SyncplayEvent.ChatReceived -> {
                _state.update {
                    val next = (it.messages + WatchRoomMessage(sender = event.username, text = event.message)).takeLast(120)
                    it.copy(messages = next)
                }
            }
            is SyncplayEvent.SidebandReceived -> {
                handleSidebandPayload(event.sender, event.streamId, event.payload)
            }
            is SyncplayEvent.StateUpdate -> {
                handleRemoteState(event)
            }
        }
    }

    private fun handleSidebandPayload(sender: String, streamId: String, payload: String) {
        when {
            payload.startsWith("S:") -> {
                val stream = TogetherStream.deserializeStreamPayload(payload, sharedBy = sender, sharedById = streamId)
                if (stream != null) {
                    _state.update { it.copy(sharedStream = stream) }
                    if (sender != _state.value.username) {
                        appendSystemMessage("$sender shared: ${stream.mediaTitle} (${stream.episodeLabel})")
                    }
                }
            }
            payload.startsWith("W:") -> {
                if (_state.value.isHost) {
                    playerBridge?.getCurrentShareableStream()?.let { publishSharedStream(it) }
                        ?: _state.value.sharedStream?.let { publishSharedStream(it) }
                }
            }
            payload == "X:close" -> {
                if (sender != _state.value.username) {
                    appendSystemMessage("Host closed the WatchRoom")
                }
            }
            payload == "R:pause" -> {
                val bridge = playerBridge ?: return
                if (_state.value.isHost && bridge.isPlayerReady() && !bridge.isPlayerPaused()) {
                    scope.launch(Dispatchers.Main) { bridge.syncPause() }
                }
            }
            payload == "R:resume" -> {
                val bridge = playerBridge ?: return
                if (_state.value.isHost && bridge.isPlayerReady() && bridge.isPlayerPaused()) {
                    scope.launch(Dispatchers.Main) { bridge.syncPlay() }
                }
            }
        }
    }

    private fun handleRemoteState(update: SyncplayEvent.StateUpdate) {
        if (update.serverRttMs > 0) {
            _state.update { it.copy(rttMs = update.serverRttMs) }
        }
        val bridge = playerBridge ?: return
        if (!bridge.isPlayerReady()) return
        val selfName = _state.value.username
        if (update.setBy == selfName && !update.doSeek) return
        if (update.hasClientIgnoring || update.hasServerIgnoring) return

        val remotePaused = update.paused
        val remotePosSec = update.positionSec

        scope.launch(Dispatchers.Main) {
            suppressOutgoingUntilMs = System.currentTimeMillis() + SUPPRESS_SYNC_MS

            if (remotePaused != null && remotePaused != bridge.isPlayerPaused()) {
                if (remotePaused) bridge.syncPause() else bridge.syncPlay()
            }

            if (remotePosSec != null) {
                val compensatedSec = if (remotePaused == false) {
                    remotePosSec + (_state.value.rttMs / 2000.0)
                } else {
                    remotePosSec
                }
                val targetMs = (compensatedSec * 1000.0).toLong().coerceAtLeast(0L)
                val localMs = bridge.getCurrentPositionMs()
                val diffMs = abs(targetMs - localMs)

                when {
                    update.doSeek || diffMs > 2500L -> {
                        if (speedBoostedDown) {
                            bridge.syncPlaybackSpeed(bridge.getDefaultPlaybackSpeed())
                            speedBoostedDown = false
                        }
                        bridge.syncSeekTo(targetMs)
                    }
                    // Gently slow down if slightly ahead (500ms-2500ms) like Syncplay/Dartotsu
                    localMs - targetMs in 500L..2500L && remotePaused == false -> {
                        bridge.syncPlaybackSpeed(0.95f)
                        speedBoostedDown = true
                    }
                    speedBoostedDown -> {
                        bridge.syncPlaybackSpeed(bridge.getDefaultPlaybackSpeed())
                        speedBoostedDown = false
                    }
                }
            }
        }
    }
}
