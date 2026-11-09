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
import kotlinx.coroutines.CoroutineScope
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }
        val model: MediaDetailsViewModel by activityViewModels()
        val scope = viewLifecycleOwner.lifecycleScope

        model.getMedia().observe(this) { media ->
            if (media != null) {
                bindMediaDetails(media, scope)
            }
        }
    }

    private fun bindMediaDetails(media: Media, scope: CoroutineScope) {
        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE

        val statuses = resources.getStringArray(R.array.status)
        val statusStrings = if (media.manga == null) {
            resources.getStringArray(R.array.status_anime)
        } else {
            resources.getStringArray(R.array.status_manga)
        }

        setupStatusDropdown(media, statuses, statusStrings)
        val (total, volumeTotal) = setupProgressInputs(media)
        setupScoreInput(media)
        val (start, end) = setupDatePickers(media)
        setupCompletionAndSteppers(statusStrings, total, volumeTotal, start, end)
        val (removeList, getRemove) = setupPrivacyAndCustomLists(media)
        setupSaveButton(media, scope, statuses, statusStrings, start, end, removeList, getRemove)
        setupDeleteButton(media, scope)
    }

    private fun setupDeleteButton(media: Media, scope: CoroutineScope) {
        binding.mediaListDelete.setOnClickListener {
            scope.launch {
                media.deleteFromList(scope, onSuccess = {
                    Refresh.all()
                    snackString(getString(R.string.deleted_from_list))
                    dismissAllowingStateLoss()
                }, onError = { e ->
                    withContext(Dispatchers.Main) {
                        snackString(getString(R.string.delete_fail_reason, e.message))
                    }
                }, onNotFound = {
                    snackString(getString(R.string.no_list_id))
                })
            }
        }
    }

    private fun setupStatusDropdown(media: Media, statuses: Array<String>, statusStrings: Array<String>) {
        val userStatus = if (media.userStatus != null) {
            statusStrings[statuses.indexOf(media.userStatus).coerceAtLeast(0)]
        } else {
            statusStrings[0]
        }
        binding.mediaListStatus.setText(userStatus)
        binding.mediaListStatus.setAdapter(
            ArrayAdapter(
                requireContext(),
                R.layout.item_dropdown,
                statusStrings
            )
        )
    }

    private fun setupProgressInputs(media: Media): Pair<Int?, Int?> {
        var total: Int? = null
        binding.mediaListProgress.setText(if (media.userProgress != null) media.userProgress.toString() else "")
        val maxEpisodes = media.anime?.totalEpisodes
        val maxChapters = media.manga?.totalChapters
        if (maxEpisodes != null) {
            total = maxEpisodes
            binding.mediaListProgress.filters = arrayOf(
                InputFilterMinMax(0.0, total.toDouble(), binding.mediaListStatus),
                LengthFilter(total.toString().length)
            )
        } else if (maxChapters != null) {
            total = maxChapters
            binding.mediaListProgress.filters = arrayOf(
                InputFilterMinMax(0.0, total.toDouble(), binding.mediaListStatus),
                LengthFilter(total.toString().length)
            )
        }
        binding.mediaListProgressLayout.suffixText = " / ${total ?: '?'}"
        binding.mediaListProgressLayout.suffixTextView.updateLayoutParams {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.mediaListProgressLayout.suffixTextView.gravity = Gravity.CENTER

        val volumeTotal = media.manga?.totalVolumes
        if (media.manga != null) {
            binding.mediaListVolumeProgressLayout.visibility = View.VISIBLE
            binding.mediaListVolumeProgress.setText(media.userProgressVolumes?.toString() ?: "")
            if (volumeTotal != null) {
                binding.mediaListVolumeProgress.filters = arrayOf(
                    InputFilterMinMax(0.0, volumeTotal.toDouble()),
                    LengthFilter(volumeTotal.toString().length)
                )
            }
            binding.mediaListVolumeProgressLayout.suffixText = " / ${volumeTotal ?: "?"}"
            binding.mediaListVolumeProgressLayout.suffixTextView.updateLayoutParams {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.mediaListVolumeProgressLayout.suffixTextView.gravity = Gravity.CENTER
        } else {
            binding.mediaListVolumeProgressLayout.visibility = View.GONE
        }
        return Pair(total, volumeTotal)
    }

    private fun setupScoreInput(media: Media) {
        binding.mediaListScore.setText(
            if (media.userScore != 0) (media.userScore / 10.0).toString() else ""
        )
        binding.mediaListScore.filters =
            arrayOf(InputFilterMinMax(0.0, 10.0), LengthFilter(10.0.toString().length))
        binding.mediaListScoreLayout.suffixTextView.updateLayoutParams {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.mediaListScoreLayout.suffixTextView.gravity = Gravity.CENTER
    }

    private fun setupDatePickers(media: Media): Pair<DatePickerFragment, DatePickerFragment> {
        val start = DatePickerFragment(requireActivity(), media.userStartedAt)
        val end = DatePickerFragment(requireActivity(), media.userCompletedAt)
        binding.mediaListStart.setText(media.userStartedAt.toStringOrEmpty())
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
        binding.mediaListEnd.setText(media.userCompletedAt.toStringOrEmpty())
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
        start.dialog.setOnDismissListener { _binding?.mediaListStart?.setText(start.date.toStringOrEmpty()) }
        end.dialog.setOnDismissListener { _binding?.mediaListEnd?.setText(end.date.toStringOrEmpty()) }
        return Pair(start, end)
    }

    private fun setupCompletionAndSteppers(
        statusStrings: Array<String>,
        total: Int?,
        volumeTotal: Int?,
        start: DatePickerFragment,
        end: DatePickerFragment
    ) {
        fun onComplete() {
            if (total != null) binding.mediaListProgress.setText(total.toString())
            if (volumeTotal != null) {
                binding.mediaListVolumeProgress.setText(volumeTotal.toString())
            }
            if (start.date.year == null) {
                start.date = FuzzyDate().getToday()
                binding.mediaListStart.setText(start.date.toString())
            }
            end.date = FuzzyDate().getToday()
            binding.mediaListEnd.setText(end.date.toString())
        }

        var startBackupDate: FuzzyDate? = null
        var endBackupDate: FuzzyDate? = null
        var progressBackup: String? = null
        binding.mediaListStatus.setOnItemClickListener { _, _, i, _ ->
            if (i == 2 && total != null) {
                startBackupDate = start.date
                endBackupDate = end.date
                progressBackup = binding.mediaListProgress.text.toString()
                onComplete()
            } else {
                if (progressBackup != null) binding.mediaListProgress.setText(progressBackup)
                if (startBackupDate != null) {
                    binding.mediaListStart.setText(startBackupDate.toStringOrEmpty())
                    start.date = startBackupDate
                }
                if (endBackupDate != null) {
                    binding.mediaListEnd.setText(endBackupDate.toStringOrEmpty())
                    end.date = endBackupDate
                }
            }
        }

        val maxLimit = total ?: 5000
        binding.mediaListIncrement.setOnClickListener {
            if (binding.mediaListStatus.text.toString() == statusStrings[0]) {
                binding.mediaListStatus.setText(statusStrings[1], false)
            }
            val init = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0
            if (init < maxLimit) {
                binding.mediaListProgress.setText("${init + 1}")
            }
            if (total != null && init + 1 == total) {
                binding.mediaListStatus.setText(statusStrings[2], false)
                onComplete()
            }
        }

        binding.mediaListDecrement.setOnClickListener {
            val init = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0
            if (init > 0) {
                binding.mediaListProgress.setText("${init - 1}")
            }
        }
    }

    private fun setupPrivacyAndCustomLists(media: Media): Pair<Set<String>, () -> Boolean?> {
        val isRescueMode = PrefManager.getVal<Boolean>(PrefName.RescueMode)
        if (isRescueMode) {
            binding.mediaListPrivate.apply { (parent as? ViewGroup)?.removeView(this) }
        } else {
            binding.mediaListPrivate.visibility = View.VISIBLE
        }
        binding.mediaListPrivate.isChecked = media.isListPrivate
        binding.mediaListPrivate.setOnCheckedChangeListener { _, checked ->
            media.isListPrivate = checked
        }
        val removeList = PrefManager.getCustomVal<Set<String>>("removeList", emptySet())
        var remove: Boolean? = null
        binding.mediaListShow.isChecked = media.id.toString() in removeList
        binding.mediaListShow.setOnCheckedChangeListener { _, checked ->
            remove = checked
        }
        binding.mediaListRewatch.setText(media.userRepeat.toString())
        media.notes?.let { binding.mediaListNotes.setText(it) }

        if (media.inCustomListsOf?.isEmpty() != false || isRescueMode) {
            binding.mediaListAddCustomList.apply {
                (parent as? ViewGroup)?.removeView(this)
            }
        }

        if (!isRescueMode) {
            media.inCustomListsOf?.forEach {
                MaterialSwitch(requireContext()).apply {
                    isChecked = it.value
                    text = it.key
                    setOnCheckedChangeListener { _, isChecked ->
                        media.inCustomListsOf?.put(it.key, isChecked)
                    }
                    binding.mediaListCustomListContainer.addView(this)
                }
            }
        }
        return Pair(removeList) { remove }
    }

    private suspend fun executeSaveMedia(
        media: Media,
        statuses: Array<String>,
        statusStrings: Array<String>,
        startD: FuzzyDate,
        endD: FuzzyDate
    ) = withContext(Dispatchers.IO) {
        val progress = _binding?.mediaListProgress?.text.toString().toIntOrNull()
        val score = _binding?.mediaListScore?.text.toString().toDoubleOrNull()?.times(10)?.toInt()
        val progressVolumes = _binding?.mediaListVolumeProgress?.text.toString().toIntOrNull()
        val statusText = _binding?.mediaListStatus?.text.toString()
        val status = statuses[statusStrings.indexOf(statusText).coerceAtLeast(0)]
        val rewatch = _binding?.mediaListRewatch?.text?.toString()?.toIntOrNull()
        val notes = _binding?.mediaListNotes?.text?.toString()
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val pending = PendingProgressUpdate(
                mediaId = media.id,
                idMAL = media.idMAL,
                isAnime = media.anime != null,
                progress = progress ?: 0,
                status = status,
                score = score,
                rewatch = rewatch,
                notes = notes,
                isPrivate = media.isListPrivate,
                startDate = startD,
                endDate = endD,
                customLists = media.inCustomListsOf?.mapNotNull { if (it.value) it.key else null },
            )
            val existing: List<PendingProgressUpdate> =
                PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
            val updated = existing.filterNot { it.mediaId == media.id } + pending
            PrefManager.setVal(PrefName.PendingProgressUpdates, updated)
        } else {
            Anilist.mutation.editList(
                mediaID = media.id,
                progress = progress,
                progressVolumes = progressVolumes,
                score = score,
                repeat = rewatch,
                notes = notes,
                status = status,
                private = media.isListPrivate,
                startedAt = startD,
                completedAt = endD,
                customList = media.inCustomListsOf?.mapNotNull { if (it.value) it.key else null }
            )
        }
        MAL.query.editList(
            media.idMAL,
            media.anime != null,
            progress,
            score,
            status,
            rewatch,
            startD,
            endD
        )
    }

    private fun setupSaveButton(
        media: Media,
        scope: CoroutineScope,
        statuses: Array<String>,
        statusStrings: Array<String>,
        start: DatePickerFragment,
        end: DatePickerFragment,
        removeList: Set<String>,
        getRemove: () -> Boolean?
    ) {
        binding.mediaListSave.setOnClickListener {
            scope.launch {
                executeSaveMedia(media, statuses, statusStrings, start.date, end.date)
                val remove = getRemove()
                if (remove == true) {
                    PrefManager.setCustomVal("removeList", removeList.plus(media.id.toString()))
                } else if (remove == false) {
                    PrefManager.setCustomVal("removeList", removeList.minus(media.id.toString()))
                }
                Refresh.all()
                snackString(getString(R.string.list_updated))
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
