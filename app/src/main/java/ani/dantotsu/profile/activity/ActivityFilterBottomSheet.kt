package ani.dantotsu.profile.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetActivityFilterBinding

class ActivityFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetActivityFilterBinding? = null
    private val binding get() = _binding!!

    private var currentFilter: ActivityFilterType = ActivityFilterType.ALL
    private var onFilterApplied: ((ActivityFilterType) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetActivityFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.filterChipGroup.check(getChipId(currentFilter))

        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener

            currentFilter = when (id) {
                R.id.filterAll -> ActivityFilterType.ALL
                R.id.filterAnimeProgress -> ActivityFilterType.ANIME_PROGRESS
                R.id.filterMangaProgress -> ActivityFilterType.MANGA_PROGRESS
                R.id.filterStatus -> ActivityFilterType.STATUS
                R.id.filterMessages -> ActivityFilterType.MESSAGES
                R.id.filterText -> ActivityFilterType.TEXT
                else -> return@setOnCheckedStateChangeListener
            }
        }

        binding.applyFiltersButton.setOnClickListener {
            onFilterApplied?.invoke(currentFilter)
            dismiss()
        }
    }

    private fun getChipId(filter: ActivityFilterType): Int {
        return when (filter) {
            ActivityFilterType.ALL -> R.id.filterAll
            ActivityFilterType.ANIME_PROGRESS -> R.id.filterAnimeProgress
            ActivityFilterType.MANGA_PROGRESS -> R.id.filterMangaProgress
            ActivityFilterType.STATUS -> R.id.filterStatus
            ActivityFilterType.MESSAGES -> R.id.filterMessages
            ActivityFilterType.TEXT -> R.id.filterText
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            currentFilter: ActivityFilterType,
            onFilterApplied: (ActivityFilterType) -> Unit
        ): ActivityFilterBottomSheet {
            return ActivityFilterBottomSheet().apply {
                this.currentFilter = currentFilter
                this.onFilterApplied = onFilterApplied
            }
        }
    }
}

enum class ActivityFilterType {
    ALL,
    ANIME_PROGRESS,
    MANGA_PROGRESS,
    STATUS,
    MESSAGES,
    TEXT
}