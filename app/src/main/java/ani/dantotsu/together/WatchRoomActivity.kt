package ani.dantotsu.together

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityWatchRoomBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.anime.MpvPlayerActivity
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WatchRoomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWatchRoomBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityWatchRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.watchRoomBack.setOnClickListener { finish() }

        val currentState = WatchRoomManager.state.value
        binding.watchRoomUsernameInput.setText(currentState.username)
        binding.watchRoomServerInput.setText(currentState.server)

        val autoTitle = intent.getStringExtra(EXTRA_MEDIA_TITLE)
        if (!autoTitle.isNullOrBlank()) {
            binding.watchRoomSubtitle.text = autoTitle
        }

        handleIncomingUri()

        binding.watchRoomRandomButton.setOnClickListener {
            binding.watchRoomNameInput.setText(WatchRoomManager.generateRandomRoomName())
        }

        binding.watchRoomCreateButton.setOnClickListener {
            val user = binding.watchRoomUsernameInput.text?.toString().orEmpty()
            val server = binding.watchRoomServerInput.text?.toString().orEmpty()
            val rawRoom = binding.watchRoomNameInput.text?.toString().orEmpty()
            val invite = TogetherLink.parse(rawRoom)
            val targetRoom = invite?.room ?: rawRoom.ifBlank { WatchRoomManager.generateRandomRoomName() }
            val targetServer = invite?.server ?: server
            binding.watchRoomNameInput.setText(targetRoom)
            WatchRoomManager.createRoom(targetRoom, targetServer, user)
        }

        binding.watchRoomJoinButton.setOnClickListener {
            val user = binding.watchRoomUsernameInput.text?.toString().orEmpty()
            val server = binding.watchRoomServerInput.text?.toString().orEmpty()
            val rawRoom = binding.watchRoomNameInput.text?.toString().orEmpty().trim()
            val invite = TogetherLink.parse(rawRoom)
            val targetRoom = invite?.room ?: rawRoom
            if (targetRoom.isBlank()) {
                snackString("Enter a room code or invite link")
                return@setOnClickListener
            }
            val targetServer = invite?.server ?: server
            WatchRoomManager.joinRoom(targetRoom, targetServer, user)
        }

        binding.watchRoomReadyButton.setOnClickListener { WatchRoomManager.toggleReady() }

        binding.watchRoomCopyInviteButton.setOnClickListener {
            val url = WatchRoomManager.state.value.inviteUrl
            if (url.isNotBlank()) {
                copyToClipboard(url, toast = true)
            }
        }

        binding.watchRoomShareInviteButton.setOnClickListener {
            val url = WatchRoomManager.state.value.inviteUrl
            if (url.isNotBlank()) {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join my Dantotsu WatchRoom: $url")
                }
                startActivity(Intent.createChooser(share, getString(R.string.watch_rooms)))
            }
        }

        binding.watchRoomLeaveButton.setOnClickListener { WatchRoomManager.leaveRoom() }

        binding.watchRoomPlaySharedButton.setOnClickListener {
            val stream = WatchRoomManager.state.value.sharedStream ?: return@setOnClickListener
            MpvPlayerActivity.launchTogetherStream(this, stream)
        }

        binding.watchRoomChatSend.setOnClickListener { submitChat() }
        binding.watchRoomChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitChat()
                true
            } else false
        }

        lifecycleScope.launch {
            WatchRoomManager.state.collectLatest { renderState(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUri()
    }

    private fun handleIncomingUri() {
        val invite = TogetherLink.parse(intent?.data)
            ?: TogetherLink.parse(intent?.getStringExtra(EXTRA_INVITE_URL))
        if (invite != null) {
            binding.watchRoomNameInput.setText(invite.room)
            if (!invite.server.isNullOrBlank()) {
                binding.watchRoomServerInput.setText(invite.server)
            }
            WatchRoomManager.joinRoom(
                roomName = invite.room,
                server = invite.server ?: WatchRoomManager.loadSavedServer(),
                username = binding.watchRoomUsernameInput.text?.toString()
                    ?.takeIf { it.isNotBlank() } ?: WatchRoomManager.loadDefaultUsername()
            )
        }
    }

    private fun submitChat() {
        val text = binding.watchRoomChatInput.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        WatchRoomManager.sendChat(text)
        binding.watchRoomChatInput.setText("")
    }

    private fun renderState(st: WatchRoomState) {
        val (statusText, bgAttr, fgAttr) = when (st.status) {
            WatchRoomStatus.CONNECTED -> Triple(
                "Connected",
                com.google.android.material.R.attr.colorPrimaryContainer,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
            WatchRoomStatus.CONNECTING -> Triple(
                "Connecting...",
                com.google.android.material.R.attr.colorSecondaryContainer,
                com.google.android.material.R.attr.colorOnSecondaryContainer
            )
            WatchRoomStatus.ERROR -> Triple(
                "Error",
                com.google.android.material.R.attr.colorErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer
            )
            WatchRoomStatus.DISCONNECTED -> Triple(
                "Offline",
                com.google.android.material.R.attr.colorSurfaceVariant,
                com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        }
        binding.watchRoomStatusBadge.text = statusText
        binding.watchRoomStatusBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(getThemeColor(bgAttr))
        binding.watchRoomStatusBadge.setTextColor(getThemeColor(fgAttr))

        binding.watchRoomSubtitle.text = if (st.isConnected) {
            "${st.server} · ${st.username}"
        } else {
            st.errorMessage ?: st.server
        }

        val activeOrConnecting = st.isConnected || st.status == WatchRoomStatus.CONNECTING
        binding.watchRoomSetupCard.isVisible = !activeOrConnecting
        binding.watchRoomActiveCard.isVisible = activeOrConnecting
        binding.watchRoomMembersCard.isVisible = st.isConnected
        binding.watchRoomChatCard.isVisible = activeOrConnecting

        binding.watchRoomActiveName.text = "Room: ${st.room}"
        binding.watchRoomActiveMeta.text = buildString {
            append(if (st.isHost) "Host" else "Guest")
            if (st.rttMs > 0) append(" · Ping ${st.rttMs}ms")
        }
        binding.watchRoomReadyButton.text = if (st.isReady) "Ready ✓" else "Not Ready"

        val shared = st.sharedStream
        binding.watchRoomStreamCard.isVisible = st.isConnected && shared != null
        if (shared != null) {
            binding.watchRoomSharedTitle.text = buildString {
                append(shared.mediaTitle.ifBlank { "Shared Video" })
                if (shared.episodeLabel.isNotBlank()) append(" — ${shared.episodeLabel}")
            }
            binding.watchRoomSharedMeta.text = "Shared by ${shared.sharedBy ?: "Room"}"
        }

        binding.watchRoomMembersList.removeAllViews()
        if (st.participants.isEmpty() && st.isConnected) {
            val tv = TextView(this).apply {
                text = "• ${st.username} (You)"
                textSize = 13f
                setPadding(0, 6, 0, 6)
            }
            binding.watchRoomMembersList.addView(tv)
        } else {
            st.participants.forEach { p ->
                val tv = TextView(this).apply {
                    val roleTag = when (p.role) {
                        TogetherRole.HOST -> " [Host]"
                        TogetherRole.GUEST -> " [Guest]"
                        null -> ""
                    }
                    val readyTag = when (p.isReady) {
                        true -> " ✓"
                        false -> " (paused)"
                        null -> ""
                    }
                    val selfTag = if (p.username == st.username) " (You)" else ""
                    text = "• ${p.username}$selfTag$roleTag$readyTag"
                    textSize = 13f
                    setPadding(0, 6, 0, 6)
                }
                binding.watchRoomMembersList.addView(tv)
            }
        }

        binding.watchRoomChatLog.text = st.messages.takeLast(30).joinToString("\n") { msg ->
            if (msg.isSystem) "[System] ${msg.text}" else "${msg.sender}: ${msg.text}"
        }.ifBlank { "No messages yet." }
    }

    companion object {
        const val EXTRA_INVITE_URL = "invite_url"
        const val EXTRA_MEDIA_TITLE = "media_title"
    }
}
