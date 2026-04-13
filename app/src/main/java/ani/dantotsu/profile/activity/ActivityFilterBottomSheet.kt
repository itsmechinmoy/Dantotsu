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

        // Set the current filter selection
        setSelectedFilter(currentFilter)

        binding.filterAll.setOnClickListener {
            currentFilter = ActivityFilterType.ALL
            setSelectedFilter(ActivityFilterType.ALL)
        }

        binding.filterAnimeProgress.setOnClickListener {
            currentFilter = ActivityFilterType.ANIME_PROGRESS
            setSelectedFilter(ActivityFilterType.ANIME_PROGRESS)
        }

        binding.filterMangaProgress.setOnClickListener {
            currentFilter = ActivityFilterType.MANGA_PROGRESS
            setSelectedFilter(ActivityFilterType.MANGA_PROGRESS)
        }

        binding.filterStatus.setOnClickListener {
            currentFilter = ActivityFilterType.STATUS
            setSelectedFilter(ActivityFilterType.STATUS)
        }

        binding.filterMessages.setOnClickListener {
            currentFilter = ActivityFilterType.MESSAGES
            setSelectedFilter(ActivityFilterType.MESSAGES)
        }

        binding.filterText.setOnClickListener {
            currentFilter = ActivityFilterType.TEXT
            setSelectedFilter(ActivityFilterType.TEXT)
        }

        binding.applyFiltersButton.setOnClickListener {
            onFilterApplied?.invoke(currentFilter)
            dismiss()
        }
    }

    private fun setSelectedFilter(filter: ActivityFilterType) {
        val context = requireContext()
        val selectedColor = ContextCompat.getColor(context, R.color.yt_red)
        val defaultColor = ContextCompat.getColor(context, R.color.bg_opp)

        binding.filterAll.setBackgroundResource(
            if (filter == ActivityFilterType.ALL) R.drawable.bg_selected_filter else R.drawable.bg_filter_chip
        )
        binding.filterAll.setTextColor(if (filter == ActivityFilterType.ALL) selectedColor else defaultColor)

        binding.filterAnimeProgress.setBackgroundResource(
            if (filter == ActivityFilterType.ANIME_PROGRESS) R.drawable.bg_selected_filter else R.drawable.bg_filter_chip
        )
        binding.filterAnimeProgress.setTextColor(if (filter == ActivityFilterType.ANIME_PROGRESS) selectedColor else defaultColor)

        binding.filterMangaProgress.setBackgroundResource(
            if (filter == ActivityFilterType.MANGA_PROGRESS) R.drawable.bg_selected_filter else R.drawable.bg_filter_chip
        )
        binding.filterMangaProgress.setTextColor(if (filter == ActivityFilterType.MANGA_PROGRESS) selectedColor else defaultColor)

        binding.filterStatus.setBackgroundResource(
            if (filter == ActivityFilterType.STATUS) R.drawable.bg_selected_filter else R.drawable.bg_filter_chip
        )
        binding.filterStatus.setTextColor(if (filter == ActivityFilterType.STATUS) selectedColor else defaultColor)

        binding.filterMessages.setBackgroundResource(
            if (filter == ActivityFilterType.MESSAGES) R.drawable.bg_selected_filter else R.drawable.bg_filter_chip
        )
        binding.filterMessages.setTextColor(if (filter == ActivityFilterType.MESSAGES) selectedColor else defaultColor)

        binding.filterText.setBackgroundResource(
            if (filter == ActivityFilterType.TEXT) R.drawable.bg_selected_filter else R.drawable.bg_filter_chip
        )
        binding.filterText.setTextColor(if (filter == ActivityFilterType.TEXT) selectedColor else defaultColor)
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