package ani.dantotsu.together

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.BottomSheetWatchRoomBinding
import ani.dantotsu.media.anime.MpvPlayerActivity
import ani.dantotsu.snackString
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WatchRoomBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWatchRoomBinding? = null
    private val binding get() = _binding!!

    private var mediaTitle: String? = null
    private var inPlayer: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaTitle = arguments?.getString(ARG_MEDIA_TITLE)
        inPlayer = arguments?.getBoolean(ARG_IN_PLAYER, false) == true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWatchRoomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sheetWatchRoomFullPage.setOnClickListener {
            startActivity(
                Intent(requireContext(), WatchRoomActivity::class.java).apply {
                    mediaTitle?.let { putExtra(WatchRoomActivity.EXTRA_MEDIA_TITLE, it) }
                }
            )
            dismiss()
        }

        binding.sheetQuickCreate.setOnClickListener {
            val room = WatchRoomManager.generateRandomRoomName()
            WatchRoomManager.createRoom(roomName = room)
            snackString("Created WatchRoom: $room")
        }

        binding.sheetJoinButton.setOnClickListener {
            val raw = binding.sheetJoinInput.text?.toString().orEmpty().trim()
            if (raw.isBlank()) {
                snackString("Enter a room code or invite link")
                return@setOnClickListener
            }
            val invite = TogetherLink.parse(raw)
            WatchRoomManager.joinRoom(
                roomName = invite?.room ?: raw,
                server = invite?.server ?: WatchRoomManager.loadSavedServer()
            )
        }

        binding.sheetCopyInvite.setOnClickListener {
            val url = WatchRoomManager.state.value.inviteUrl
            if (url.isNotBlank()) {
                copyToClipboard(url, toast = true)
            }
        }

        binding.sheetShareStream.isVisible = inPlayer
        binding.sheetShareStream.setOnClickListener {
            if (WatchRoomManager.shareCurrentPlayerStream()) {
                snackString("Stream shared with WatchRoom")
            } else {
                snackString("Only public HTTP/HTTPS or magnet streams can be shared")
            }
        }

        binding.sheetPlayShared.setOnClickListener {
            val stream = WatchRoomManager.state.value.sharedStream ?: return@setOnClickListener
            MpvPlayerActivity.launchTogetherStream(requireContext(), stream)
            dismiss()
        }

        binding.sheetChatSend.setOnClickListener { submitQuickChat() }
        binding.sheetChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitQuickChat()
                true
            } else false
        }

        binding.sheetLeaveButton.setOnClickListener {
            WatchRoomManager.leaveRoom()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            WatchRoomManager.state.collectLatest { renderState(it) }
        }
    }

    private fun submitQuickChat() {
        val text = binding.sheetChatInput.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        WatchRoomManager.sendChat(text)
        binding.sheetChatInput.setText("")
    }

    private fun renderState(st: WatchRoomState) {
        val connectedOrBusy = st.isConnected || st.status == WatchRoomStatus.CONNECTING
        binding.sheetSetupGroup.isVisible = !connectedOrBusy
        binding.sheetConnectedGroup.isVisible = connectedOrBusy

        binding.sheetWatchRoomTitle.text = if (st.isConnected) {
            "WatchRoom · ${st.room}"
        } else {
            mediaTitle?.let { "Watch Party — $it" } ?: "Watch Party"
        }

        binding.sheetWatchRoomStatus.text = when (st.status) {
            WatchRoomStatus.CONNECTED ->
                "Connected to ${st.server} as ${st.username} (${if (st.isHost) "Host" else "Guest"})"
            WatchRoomStatus.CONNECTING -> "Connecting to ${st.room}..."
            WatchRoomStatus.ERROR -> st.errorMessage ?: "Connection failed"
            WatchRoomStatus.DISCONNECTED ->
                if (!mediaTitle.isNullOrBlank()) {
                    "Start a room and play an episode to watch $mediaTitle together."
                } else {
                    "Start a Syncplay room so friends can watch together."
                }
        }

        binding.sheetShareStream.isVisible = st.isConnected && inPlayer
        val shared = st.sharedStream
        binding.sheetPlayShared.isVisible = st.isConnected && !inPlayer && shared != null
        if (shared != null) {
            binding.sheetPlayShared.text = "Watch ${shared.mediaTitle} ${shared.episodeLabel}".trim()
        }

        val memberNames = st.participants.joinToString(", ") { p ->
            when (p.role) {
                TogetherRole.HOST -> "${p.username} (Host)"
                TogetherRole.GUEST -> p.username
                null -> p.username
            }
        }.ifBlank { st.username }
        binding.sheetParticipantsSummary.text = "In room: $memberNames"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_MEDIA_TITLE = "media_title"
        private const val ARG_IN_PLAYER = "in_player"

        fun newInstance(mediaTitle: String? = null, inPlayer: Boolean = false): WatchRoomBottomSheet {
            return WatchRoomBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MEDIA_TITLE, mediaTitle)
                    putBoolean(ARG_IN_PLAYER, inPlayer)
                }
            }
        }
    }
}
