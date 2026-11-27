package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetTrackGroupsBinding
import ani.dantotsu.databinding.ItemSubtitleTextBinding
import ani.dantotsu.media.anime.mpv.AudioTrack
import java.util.Locale

class TrackGroupDialogFragment(
    private var instance: MpvPlayerActivity,
    private var audioTracks: List<AudioTrack>,
    private var overrideTrackNames: List<Pair<String, String>>? = null
) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetTrackGroupsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTrackGroupsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.selectionTitle.text = getString(R.string.audio_tracks)
        binding.subtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.subtitlesRecycler.adapter = TrackGroupAdapter()
    }

    inner class TrackGroupAdapter : RecyclerView.Adapter<TrackGroupAdapter.StreamViewHolder>() {
        inner class StreamViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemSubtitleTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val binding = holder.binding
            val track = audioTracks[position]
            val overridePair = overrideTrackNames?.getOrNull(
                position - (audioTracks.size - (overrideTrackNames?.size ?: 0))
            )

            val displayText = if (overridePair != null) {
                "[${overridePair.second}] ${overridePair.first}"
            } else {
                when (val language = track.language?.lowercase()) {
                    null -> track.name.ifBlank { getString(R.string.unknown_track, "Track $position") }
                    "none" -> getString(R.string.disabled_track)
                    else -> {
                        val locale = try {
                            if (language.contains("-")) {
                                val parts = language.split("-")
                                Locale(parts[0], parts[1])
                            } else {
                                Locale(language)
                            }
                        } catch (_: Exception) {
                            null
                        }
                        locale?.let {
                            if (track.name.isNotBlank() && track.name != "Track ${track.id}") {
                                "[${it.language}] ${track.name}"
                            } else {
                                "[${it.language}] ${it.displayName}"
                            }
                        } ?: track.name.ifBlank { getString(R.string.unknown_track, language) }
                    }
                }
            }

            binding.subtitleTitle.text = if (track.isSelected) {
                "✔ $displayText"
            } else {
                displayText
            }

            binding.root.setOnClickListener {
                dismiss()
                instance.selectAudioTrack(track.id)
            }
        }

        override fun getItemCount(): Int = audioTracks.size
    }

    override fun onDestroyView() {
        _binding?.subtitlesRecycler?.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
