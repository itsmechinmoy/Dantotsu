package ani.dantotsu.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.databinding.BottomSheetSearchBinding
import ani.dantotsu.media.SearchActivity

class SearchBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSearchBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rescueMode: Boolean = ani.dantotsu.settings.saving.PrefManager.getVal(
            ani.dantotsu.settings.saving.PrefName.RescueMode
        )

        binding.animeSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.ANIME)
            dismiss()
        }
        binding.mangaSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.MANGA)
            dismiss()
        }

        if (rescueMode) {
            listOf(binding.characterSearch, binding.staffSearch, binding.studioSearch, binding.userSearch).forEach {
                it.alpha = 0.4f
                it.setOnClickListener {
                    ani.dantotsu.toast(getString(ani.dantotsu.R.string.rescue_mode_active))
                }
            }
        } else {
            binding.characterSearch.setOnClickListener {
                startActivity(requireContext(), SearchType.CHARACTER)
                dismiss()
            }
            binding.staffSearch.setOnClickListener {
                startActivity(requireContext(), SearchType.STAFF)
                dismiss()
            }
            binding.studioSearch.setOnClickListener {
                startActivity(requireContext(), SearchType.STUDIO)
                dismiss()
            }
            binding.userSearch.setOnClickListener {
                startActivity(requireContext(), SearchType.USER)
                dismiss()
            }
        }
    }

    private fun startActivity(context: Context, type: SearchType) {
        ContextCompat.startActivity(
            context,
            Intent(context, SearchActivity::class.java).putExtra("type", type.toAnilistString()),
            null
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = SearchBottomSheet()
    }
}