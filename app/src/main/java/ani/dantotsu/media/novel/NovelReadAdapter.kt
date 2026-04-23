package ani.dantotsu.media.novel

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import androidx.core.content.ContextCompat.startActivity
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogLayoutBinding
import ani.dantotsu.databinding.ItemNovelHeaderBinding
import ani.dantotsu.media.Media
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.NovelReadSources
import ani.dantotsu.settings.FAQActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.customAlertDialog

class NovelReadAdapter(
    private val media: Media,
    private val fragment: NovelReadFragment,
    private val novelReadSources: NovelReadSources
) : RecyclerView.Adapter<NovelReadAdapter.ViewHolder>() {

    var progress: View? = null
    var _binding: ItemNovelHeaderBinding? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovelReadAdapter.ViewHolder {
        val binding =
            ItemNovelHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        progress = binding.progress.root
        return ViewHolder(binding)
    }

    private val imm = fragment.requireContext()
        .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        _binding = binding
        progress = binding.progress.root

        binding.faqbutton.setOnClickListener {
            startActivity(
                fragment.requireContext(),
                Intent(fragment.requireContext(), FAQActivity::class.java),
                null
            )
        }

        val isLocal = media.format == "LOCAL" || media.format == "LOCAL_NOVEL"

        if (isLocal) {

            binding.novelSourceContainer.visibility = View.GONE
            binding.divider.visibility = View.GONE
            binding.searchBar.visibility = View.GONE


            binding.novelLocalHeader.visibility = View.VISIBLE

            binding.novelLocalFilter.setOnClickListener {
                showFilterDialog()
            }
        } else {
            fun search(): Boolean {
                val query = binding.searchBarText.text.toString()
                val source =
                    media.selected!!.sourceIndex.let { if (it >= novelReadSources.names.size) 0 else it }
                fragment.source = source

                binding.searchBarText.clearFocus()
                imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
                fragment.search(query, source, true)
                return true
            }

            val source =
                media.selected!!.sourceIndex.let { if (it >= novelReadSources.names.size) 0 else it }
            if (novelReadSources.names.isNotEmpty() && source in 0 until novelReadSources.names.size) {
                binding.mediaSource.setText(novelReadSources.names[source], false)
            }
            val displayNames = novelReadSources.names.filter { it != "Local" }
            binding.mediaSource.setAdapter(
                ArrayAdapter(
                    fragment.requireContext(),
                    R.layout.item_dropdown,
                    displayNames
                )
            )

            fun updateLayoutForSource(idx: Int) {
                val isLnReader = idx in 0 until novelReadSources.names.size
                        && novelReadSources[idx] is ani.dantotsu.parsers.novel.LnReaderNovelParser
                if (isLnReader) {
                    binding.searchBar.visibility = View.GONE
                    binding.divider.visibility = View.GONE
                    binding.wrongTitleButton.visibility = View.VISIBLE
                    binding.mediaSourceTitle.visibility = View.VISIBLE
                    binding.mediaSourceTitle.text = "Found : ${media.name ?: media.nameRomaji}"
                    binding.mediaSourceTitle.isSelected = true
                    binding.chaptersHeaderRow.visibility = View.VISIBLE
                } else {
                    binding.searchBar.visibility = View.VISIBLE
                    binding.divider.visibility = View.VISIBLE
                    binding.wrongTitleButton.visibility = View.GONE
                    binding.mediaSourceTitle.visibility = View.GONE
                    binding.chaptersHeaderRow.visibility = View.GONE
                }

                // novel ext setting
                binding.mediaSourceSettings.visibility = View.GONE
            }
            updateLayoutForSource(source)

            // menu
            binding.mediaNestedButton.setOnClickListener {
                showFilterDialog()
            }

            binding.wrongTitleButton.setOnClickListener {
                val dialog = ani.dantotsu.media.SourceSearchDialogFragment().apply {
                    isNovel = true
                    media = fragment.media
                    onSourceSelected = { selected ->
                        val isLnReader = fragment.model.novelSources[fragment.source] is ani.dantotsu.parsers.novel.LnReaderNovelParser
                        if (isLnReader) {
                            fragment.overrideNovelSource(selected)
                        } else {
                            fragment.resetChapterState()
                            fragment.onNovelClick(selected)
                        }
                    }
                }
                dialog.show(fragment.requireActivity().supportFragmentManager, "SourceSearchDialogFragment")
            }

            binding.mediaSource.setOnItemClickListener { _, _, i, _ ->
                val actualIndex = novelReadSources.names.indexOf(displayNames[i])
                fragment.onSourceChange(actualIndex)
                updateLayoutForSource(actualIndex)
                search()
            }

            binding.searchBarText.setText(fragment.searchQuery)
            binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
                return@setOnEditorActionListener when (actionId) {
                    IME_ACTION_SEARCH -> search()
                    else -> false
                }
            }
            binding.searchBar.setEndIconOnClickListener { search() }
        }
    }

    private fun showFilterDialog() {
        val dialogBinding = DialogLayoutBinding.inflate(fragment.layoutInflater)
        var run = false
        var reversed = fragment.reverse
        var style = fragment.style

        dialogBinding.apply {

            mediaSourceTop.rotation = if (reversed) -90f else 90f
            sortText.text = if (reversed) "Down to Up" else "Up to Down"
            mediaSourceTop.setOnClickListener {
                reversed = !reversed
                mediaSourceTop.rotation = if (reversed) -90f else 90f
                sortText.text = if (reversed) "Down to Up" else "Up to Down"
                run = true
            }


            mediaSourceGrid.visibility = View.VISIBLE
            var selected = when (style) {
                0 -> mediaSourceList
                1 -> mediaSourceCompact
                2 -> mediaSourceGrid
                else -> mediaSourceGrid
            }
            when (style) {
                0 -> layoutText.setText(R.string.list)
                1 -> layoutText.setText(R.string.compact)
                2 -> layoutText.text = "Cover"
                else -> layoutText.text = "Cover"
            }
            selected.alpha = 1f
            fun selected(it: ImageButton) {
                selected.alpha = 0.33f
                selected = it
                selected.alpha = 1f
            }
            mediaSourceList.setOnClickListener {
                selected(it as ImageButton)
                style = 0
                layoutText.setText(R.string.list)
                run = true
            }
            mediaSourceCompact.setOnClickListener {
                selected(it as ImageButton)
                style = 1
                layoutText.setText(R.string.compact)
                run = true
            }
            mediaSourceGrid.setOnClickListener {
                selected(it as ImageButton)
                style = 2
                layoutText.text = "Cover"
                run = true
            }


            if (fragment.media.format == "LOCAL") {
                animeDownloadContainer.visibility = View.GONE
            } else {
                animeDownloadContainer.visibility = View.VISIBLE
                mediaDownloadTop.visibility = View.VISIBLE
                mediaDownloadTop.setOnClickListener {
                    fragment.requireContext().customAlertDialog().apply {
                        setTitle("Multi Chapter Downloader")
                        setMessage("Enter the number of chapters to download")
                        val input = View.inflate(ani.dantotsu.currContext(), R.layout.dialog_layout, null)
                        val editText = input.findViewById<android.widget.EditText>(R.id.downloadNo)
                        setCustomView(input)
                        setPosButton(R.string.ok) {
                            val value = editText.text.toString().toIntOrNull()
                            if (value != null && value > 0) {
                                downloadNo.setText(value.toString(), android.widget.TextView.BufferType.EDITABLE)
                                fragment.multiDownload(value)
                            } else {
                                ani.dantotsu.toast("Please enter a valid number")
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            }
            mangaScanlatorContainer.visibility = View.GONE
            mediaWebviewContainer.visibility = View.GONE


            resetProgressDef.text = "Clear stored chapter details"
            resetProgress.setOnClickListener {
                fragment.requireContext().customAlertDialog().apply {
                    setTitle("Delete Progress for all chapters of ${fragment.media.nameRomaji}")
                    setMessage("This will delete all the locally stored progress for chapters")
                    setPosButton(R.string.ok) {
                        val prefix = "${fragment.media.id}_"
                        val regex = Regex("^${prefix}\\d+$")
                        PrefManager.getAllCustomValsForMedia(prefix)
                            .keys
                            .filter { it.matches(regex) }
                            .onEach { key -> PrefManager.removeCustomVal(key) }
                        ani.dantotsu.snackString("Deleted the progress of Chapters for ${fragment.media.nameRomaji}")
                    }
                    setNegButton(R.string.no)
                    show()
                }
            }
        }

        fragment.requireContext().customAlertDialog().apply {
            setTitle("Options")
            setCustomView(dialogBinding.root)
            setPosButton(R.string.ok) {
                if (run) {
                    fragment.onLayoutChanged(style, reversed)
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    fun updateContinue(responses: List<ShowResponse>) {
        if (progress == null) return
        val lastReadName = PrefManager.getCustomVal("${media.id}_last_read_volume", "")
        
        var continueNovel: ShowResponse? = null
        if (lastReadName.isNotBlank() && responses.isNotEmpty()) {
            continueNovel = responses.firstOrNull { it.name == lastReadName } ?: responses.first()
        } else if (responses.isNotEmpty()) {
            continueNovel = responses.first()
        }

        val binding = ItemNovelHeaderBinding.bind(progress!!.parent as View)
        if (continueNovel != null) {
            binding.sourceContinue.visibility = View.VISIBLE
            binding.itemMediaImage.loadImage(media.banner ?: media.cover)
            binding.mediaSourceContinueText.text = fragment.getString(R.string.continue_reading) + "\n" + continueNovel.name
            binding.sourceContinue.setOnClickListener {
                fragment.onNovelClick(continueNovel)
            }
        } else {
            binding.sourceContinue.visibility = View.GONE
        }
    }

    fun updateChips(limit: Int, names: Array<String>, arr: Array<Int>, selected: Int = 0) {
        val binding = _binding
        if (binding != null) {
            ani.dantotsu.util.PaginationChipHelper.buildChips(
                context = fragment.requireContext(),
                chipGroup = binding.mediaSourceChipGroup,
                scrollView = binding.mediaWatchChipScroll,
                limit = limit,
                names = names,
                arr = arr,
                selected = selected,
                onChipClicked = { position, start, end ->
                    fragment.onChipClicked(position, start, end)
                }
            )
        }
    }

    fun clearChips() {
        _binding?.mediaSourceChipGroup?.removeAllViews()
    }

    fun startLoading() {
        _binding?.sourceContinue?.visibility = View.GONE
        _binding?.sourceNotFound?.visibility = View.GONE
        _binding?.faqbutton?.visibility = View.GONE
        clearChips()
        progress?.visibility = View.VISIBLE
    }

    fun handleSourceNotFound(isEmpty: Boolean) {
        _binding?.sourceNotFound?.isGone = !isEmpty
        _binding?.faqbutton?.isGone = !isEmpty
    }

    override fun getItemCount(): Int = 1

    inner class ViewHolder(val binding: ItemNovelHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)
}