package ani.dantotsu.media

import android.os.Bundle
import android.text.InputFilter.LengthFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.DatePickerFragment
import ani.dantotsu.InputFilterMinMax
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.PendingProgressUpdate
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.databinding.BottomSheetMediaListBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.tryWith
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MediaListDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMediaListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun setupStatusAndProgress(media: Media, statusStrings: Array<String>, statuses: Array<String>) {
        val userStatus = if (media.userStatus != null) {
            statusStrings[statuses.indexOf(media.userStatus).coerceAtLeast(0)]
        } else {
            statusStrings[0]
        }

        binding.mediaListStatus.setText(userStatus)
        binding.mediaListStatus.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, statusStrings)
        )

        var total: Int? = null
        binding.mediaListProgress.setText(if (media.userProgress != null) media.userProgress.toString() else "")
        if (media.anime?.totalEpisodes != null) {
            total = media.anime.totalEpisodes
            binding.mediaListProgress.filters = arrayOf(
                InputFilterMinMax(0.0, total.toDouble(), binding.mediaListStatus),
                LengthFilter(total.toString().length)
            )
        } else if (media.manga?.totalChapters != null) {
            total = media.manga.totalChapters
            binding.mediaListProgress.filters = arrayOf(
                InputFilterMinMax(0.0, total.toDouble(), binding.mediaListStatus),
                LengthFilter(total.toString().length)
            )
        }
        binding.mediaListProgressLayout.suffixText = " / ${total ?: '?'}"
    }

    private fun setupVolumeProgress(media: Media) {
        if (media.manga != null) {
            binding.mediaListVolumeProgressLayout.visibility = View.VISIBLE
            val volumeTotal = media.manga.totalVolumes
            binding.mediaListVolumeProgress.setText(if (media.userVolumeProgress != null) media.userVolumeProgress.toString() else "")
            if (volumeTotal != null) {
                binding.mediaListVolumeProgress.filters = arrayOf(
                    InputFilterMinMax(0.0, volumeTotal.toDouble()),
                    LengthFilter(volumeTotal.toString().length)
                )
            }
            binding.mediaListVolumeProgressLayout.suffixText = " / ${volumeTotal ?: '?'}"
        } else {
            binding.mediaListVolumeProgressLayout.visibility = View.GONE
        }
    }

    private fun setupDatePickers(media: Media, statusStrings: Array<String>): Pair<DatePickerHelper, DatePickerHelper> {
        val start = DatePickerHelper(requireContext(), binding.mediaListStartLayout, media.userStartedAt)
        binding.mediaListStart.setText(start.date.toString())
        binding.mediaListStart.setOnClickListener {
            tryWith(false) {
                if (!start.dialog.isShowing) start.dialog.show()
            }
        }
        binding.mediaListStart.setOnFocusChangeListener { _, b ->
            tryWith(false) {
                if (b && !start.dialog.isShowing) start.dialog.show()
            }
        }

        val end = DatePickerHelper(requireContext(), binding.mediaListEndLayout, media.userCompletedAt)
        binding.mediaListEnd.setText(end.date.toString())
        binding.mediaListEnd.setOnClickListener {
            tryWith(false) {
                if (!end.dialog.isShowing) end.dialog.show()
            }
        }
        binding.mediaListEnd.setOnFocusChangeListener { _, b ->
            tryWith(false) {
                if (b && !end.dialog.isShowing) end.dialog.show()
            }
        }

        binding.mediaListStatus.setOnItemClickListener { _, _, position, _ ->
            if (position == 2) {
                if (start.date == null) {
                    start.date = FuzzyDate().getToday()
                    binding.mediaListStart.setText(start.date.toString())
                }
                end.date = FuzzyDate().getToday()
                binding.mediaListEnd.setText(end.date.toString())
            }
            if (position == 1 && start.date == null) {
                start.date = FuzzyDate().getToday()
                binding.mediaListStart.setText(start.date.toString())
            }
        }
        return start to end
    }

    private fun setupScoreAndNotes(media: Media, statusStrings: Array<String>) {
        binding.mediaListScore.setText(if (media.userScore != 0) (media.userScore.toDouble() / 10).toString() else "")
        binding.mediaListScore.filters = arrayOf(
            InputFilterMinMax(0.0, 10.0),
            LengthFilter(4)
        )

        binding.mediaListProgress.setOnFocusChangeListener { _, b ->
            if (b) {
                if (binding.mediaListStatus.text.toString() == statusStrings[0]) {
                    binding.mediaListStatus.setText(statusStrings[1], false)
                }
                val init = if (binding.mediaListProgress.text.toString() != "") binding.mediaListProgress.text.toString() else "0"
                binding.mediaListProgress.setText(init)
            }
        }

        val repeats = if (media.manga == null) R.string.total_rewatches else R.string.total_rereads
        binding.mediaListRepeatLayout.hint = getString(repeats)
        binding.mediaListRepeat.setText(if (media.userRepeat != 0) media.userRepeat.toString() else "")

        binding.mediaListNotes.setText(media.userNotes ?: "")

        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            binding.mediaListPrivate.visibility = View.GONE
        } else {
            binding.mediaListPrivate.visibility = View.VISIBLE
        }
        binding.mediaListPrivate.isChecked = media.isListPrivate ?: false
        binding.mediaListPrivate.setOnCheckedChangeListener { _, checked ->
            media.isListPrivate = checked
        }
    }

    private fun setupCustomLists(media: Media) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (!rescueMode && media.inCustomListsOf != null) {
            binding.mediaListCustomListContainer.visibility = View.VISIBLE
            binding.mediaListCustomListContainer.removeAllViews()
            media.inCustomListsOf.forEach {
                CheckBox(requireContext()).apply {
                    text = it.key
                    isChecked = it.value
                    setOnCheckedChangeListener { _, isChecked ->
                        media.inCustomListsOf[it.key] = isChecked
                    }
                    binding.mediaListCustomListContainer.addView(this)
                }
            }
        } else {
            binding.mediaListCustomListContainer.visibility = View.GONE
        }
    }

    private fun setupActionButtons(
        media: Media,
        start: DatePickerHelper,
        end: DatePickerHelper,
        statuses: Array<String>,
        statusStrings: Array<String>,
        scope: CoroutineScope
    ) {
        binding.mediaListSave.setOnClickListener {
            binding.mediaListLayout.visibility = View.GONE
            binding.mediaListProgressBar.visibility = View.VISIBLE

            val score = ((binding.mediaListScore.text.toString().toDoubleOrNull() ?: 0.0) * 10).toInt()
            val progress = binding.mediaListProgress.text.toString().toIntOrNull()
            val volumeProgress = binding.mediaListVolumeProgress.text.toString().toIntOrNull()
            val repeat = binding.mediaListRepeat.text.toString().toIntOrNull()
            val notes = binding.mediaListNotes.text.toString()
            val status = statuses[statusStrings.indexOf(binding.mediaListStatus.text.toString())]
            val startD = start.date
            val endD = end.date
            val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)

            if (rescueMode) {
                val pending = PendingProgressUpdate(
                    mediaId = media.id,
                    idMAL = media.idMAL,
                    anime = media.anime != null,
                    progress = progress,
                    volumeProgress = volumeProgress,
                    score = score,
                    repeat = repeat,
                    notes = notes,
                    status = status,
                    startedAt = startD,
                    completedAt = endD
                )
                scope.launch {
                    val ok = tryWithSuspend(post = false) {
                        MAL.setMediaListStatus(pending)
                    } == true
                    if (ok) {
                        Refresh.all()
                        dismissAllowingStateLoss()
                    } else {
                        binding.mediaListProgressBar.visibility = View.GONE
                        binding.mediaListLayout.visibility = View.VISIBLE
                    }
                }
            } else {
                media.saveList(
                    scope,
                    score = score,
                    progress = progress,
                    progressVolumes = volumeProgress,
                    repeat = repeat,
                    notes = notes,
                    status = status,
                    private = media.isListPrivate ?: false,
                    startedAt = startD,
                    completedAt = endD,
                    customList = media.inCustomListsOf?.mapNotNull { if (it.value) it.key else null }
                ) {
                    Refresh.all()
                    dismissAllowingStateLoss()
                }
            }
        }

        binding.mediaListDelete.setOnClickListener {
            scope.launch {
                media.deleteFromList(scope, onSuccess = {
                    Refresh.all()
                    snackString(getString(R.string.deleted_from_list))
                    dismissAllowingStateLoss()
                })
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }
        val model: MediaDetailsViewModel by activityViewModels()
        val scope = viewLifecycleOwner.lifecycleScope

        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (media != null) {
                binding.mediaListProgressBar.visibility = View.GONE
                binding.mediaListLayout.visibility = View.VISIBLE

                val statuses: Array<String> = resources.getStringArray(R.array.status)
                val statusStrings = if (media.manga == null) {
                    resources.getStringArray(R.array.status_anime)
                } else {
                    resources.getStringArray(R.array.status_manga)
                }

                setupStatusAndProgress(media, statusStrings, statuses)
                setupVolumeProgress(media)
                val (start, end) = setupDatePickers(media, statusStrings)
                setupScoreAndNotes(media, statusStrings)
                setupCustomLists(media)
                setupActionButtons(media, start, end, statuses, statusStrings, scope)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
