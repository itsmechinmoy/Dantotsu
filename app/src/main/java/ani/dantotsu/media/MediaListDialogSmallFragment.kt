package ani.dantotsu.media

import android.os.Bundle
import android.text.InputFilter.LengthFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.updateLayoutParams
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
import ani.dantotsu.databinding.BottomSheetMediaListSmallBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.tryWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable


class MediaListDialogSmallFragment : BottomSheetDialogFragment() {

    private lateinit var media: Media

    companion object {
        fun newInstance(m: Media): MediaListDialogSmallFragment =
            MediaListDialogSmallFragment().apply {
                arguments = Bundle().apply {
                    putSerializable("media", m as Serializable)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            media = it.getSerialized("media")!!
        }
    }

    private var _binding: BottomSheetMediaListSmallBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMediaListSmallBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }
        val scope = viewLifecycleOwner.lifecycleScope
        setupDeleteButton(scope)

        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE
        val statuses = resources.getStringArray(R.array.status)
        val statusStrings = if (media.manga == null) {
            resources.getStringArray(R.array.status_anime)
        } else {
            resources.getStringArray(R.array.status_manga)
        }

        setupStatusDropdown(statuses, statusStrings)
        val total = setupProgressInput()
        setupScoreInput()
        setupIncrementDecrement(statusStrings, total)
        val (removeList, getRemove) = setupRescueAndPrivacySwitches()
        setupSaveButton(scope, statuses, statusStrings, removeList, getRemove)
    }

    private fun setupDeleteButton(scope: CoroutineScope) {
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

    private fun setupStatusDropdown(statuses: Array<String>, statusStrings: Array<String>) {
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

    private fun setupProgressInput(): Int? {
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
        return total
    }

    private fun setupScoreInput() {
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

    private fun setupIncrementDecrement(statusStrings: Array<String>, total: Int?) {
        val maxLimit = total ?: 5000
        binding.mediaListIncrement.setOnClickListener {
            if (binding.mediaListStatus.text.toString() == statusStrings[0]) {
                binding.mediaListStatus.setText(statusStrings[1], false)
            }
            val init = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0
            if (init < maxLimit) {
                binding.mediaListProgress.setText("${init + 1}")
            }
            if (init + 1 == maxLimit) {
                binding.mediaListStatus.setText(statusStrings[2], false)
            }
        }
        binding.mediaListDecrement.setOnClickListener {
            val init = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0
            if (init > 0) {
                binding.mediaListProgress.setText("${init - 1}")
            }
        }
    }

    private fun setupRescueAndPrivacySwitches(): Pair<Set<String>, () -> Boolean?> {
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
        return Pair(removeList) { remove }
    }

    private suspend fun executeSave(statuses: Array<String>, statusStrings: Array<String>) =
        withContext(Dispatchers.IO) {
            val progress = _binding?.mediaListProgress?.text.toString().toIntOrNull()
            val progressVolumes = media.userProgressVolumes
            val score = _binding?.mediaListScore?.text.toString().toDoubleOrNull()?.times(10)?.toInt()
            val statusText = _binding?.mediaListStatus?.text.toString()
            val status = statuses[statusStrings.indexOf(statusText).coerceAtLeast(0)]
            val startD = media.userStartedAt
            val endD = media.userCompletedAt
            val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
            if (rescueMode) {
                val pending = PendingProgressUpdate(
                    mediaId = media.id,
                    idMAL = media.idMAL,
                    isAnime = media.anime != null,
                    progress = progress ?: 0,
                    status = status,
                    score = score,
                    isPrivate = media.isListPrivate,
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
                    status = status,
                    private = media.isListPrivate
                )
            }
            MAL.query.editList(
                media.idMAL,
                media.anime != null,
                progress,
                score,
                status,
                start = startD,
                end = endD
            )
        }

    private fun setupSaveButton(
        scope: CoroutineScope,
        statuses: Array<String>,
        statusStrings: Array<String>,
        removeList: Set<String>,
        getRemove: () -> Boolean?
    ) {
        binding.mediaListSave.setOnClickListener {
            scope.launch {
                executeSave(statuses, statusStrings)
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
