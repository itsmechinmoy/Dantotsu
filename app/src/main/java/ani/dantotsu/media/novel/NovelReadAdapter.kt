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
import ani.dantotsu.others.webview.CookieCatcher
import ani.dantotsu.settings.FAQActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.util.system.WebViewUtil

class NovelReadAdapter(
    private val media: Media,
    private val fragment: NovelReadFragment,
    private val novelReadSources: NovelReadSources
) : RecyclerView.Adapter<NovelReadAdapter.ViewHolder>() {

    var progress: View? = null
    var _binding: ItemNovelHeaderBinding? = null

    fun clearBinding() {
        _binding = null
        progress = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        _binding = null
        progress = null
    }

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
            bindLocalHeader(binding)
        } else {
            bindRemoteHeader(binding)
        }
    }

    private fun bindLocalHeader(binding: ItemNovelHeaderBinding) {
        binding.novelSourceContainer.visibility = View.GONE
        binding.divider.visibility = View.GONE
        binding.searchBar.visibility = View.GONE
        binding.novelLocalHeader.visibility = View.VISIBLE
        binding.novelLocalFilter.setOnClickListener {
            showFilterDialog()
        }
    }

    private fun bindRemoteHeader(binding: ItemNovelHeaderBinding) {
        val search = setupNovelSearch(binding)
        setupNovelSourceSelection(binding, search)
        setupNovelActionButtons(binding)
    }

    private fun setupNovelSearch(binding: ItemNovelHeaderBinding): () -> Boolean {
        fun search(): Boolean {
            val query = binding.searchBarText.text.toString()
            val source = media.selected!!.sourceIndex.let { if (it >= novelReadSources.names.size) 0 else it }
            fragment.source = source
            binding.searchBarText.clearFocus()
            imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            fragment.search(query, source, true)
            return true
        }

        binding.searchBarText.setText(fragment.searchQuery)
        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                IME_ACTION_SEARCH -> search()
                else -> false
            }
        }
        binding.searchBar.setEndIconOnClickListener { search() }
        return ::search
    }

    private fun setupNovelSourceSelection(binding: ItemNovelHeaderBinding, search: () -> Boolean) {
        val source = media.selected!!.sourceIndex.let { if (it >= novelReadSources.names.size) 0 else it }
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
        updateLayoutForSource(binding, source)

        binding.mediaSource.setOnItemClickListener { _, _, i, _ ->
            val actualIndex = novelReadSources.names.indexOf(displayNames[i])
            fragment.onSourceChange(actualIndex)
            updateLayoutForSource(binding, actualIndex)
            search()
        }
    }

    private fun updateLayoutForSource(binding: ItemNovelHeaderBinding, idx: Int) {
        val isLocal = idx in 0 until novelReadSources.names.size && (novelReadSources.names[idx] == "Local" || novelReadSources.names[idx] == "Downloaded")
        val isLnReader = idx in 0 until novelReadSources.names.size
                && novelReadSources[idx] is ani.dantotsu.parsers.novel.LnReaderNovelParser
        val isDynamic = idx in 0 until novelReadSources.names.size
                && novelReadSources[idx] is ani.dantotsu.parsers.novel.DynamicNovelParser

        val isVisible = !isLocal && !isLnReader
        binding.searchBar.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.divider.visibility = if (isLocal) View.GONE else View.VISIBLE
        binding.wrongTitleButton.visibility = if (isLocal) View.GONE else View.VISIBLE
        binding.mediaSourceTitle.visibility = if (isLocal) View.GONE else View.VISIBLE
        binding.mediaSourceTitle.text = "Found : ${media.name ?: media.nameRomaji}"
        binding.mediaSourceTitle.isSelected = true
        binding.chaptersHeaderRow.visibility = View.VISIBLE

        binding.mediaSourceSettings.visibility = if (isDynamic) View.VISIBLE else View.GONE
        if (isDynamic) {
            binding.mediaSourceSettings.setOnClickListener {
                val parser = novelReadSources[idx] as? ani.dantotsu.parsers.novel.DynamicNovelParser
                if (parser != null) {
                    fragment.openSettings(parser.extension)
                }
            }
        }
    }

    private fun setupNovelActionButtons(binding: ItemNovelHeaderBinding) {
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
                        fragment.overrideNovelSource(selected, fragment.source)
                    } else {
                        fragment.resetChapterState()
                        fragment.onNovelClick(selected)
                    }
                }
            }
            dialog.show(fragment.requireActivity().supportFragmentManager, "SourceSearchDialogFragment")
        }
    }

    private class NovelFilterState(
        var reversed: Boolean,
        var style: Int,
        var run: Boolean = false
    )

    private fun showFilterDialog() {
        val dialogBinding = DialogLayoutBinding.inflate(fragment.layoutInflater)
        val state = NovelFilterState(
            reversed = fragment.reverse,
            style = fragment.style
        )

        setupFilterSort(dialogBinding, state)
        setupFilterStyle(dialogBinding, state)
        setupFilterDownloads(dialogBinding)
        setupFilterWebview(dialogBinding)
        setupFilterResetProgress(dialogBinding)
        showFilterMainDialog(dialogBinding, state)
    }

    private fun setupFilterSort(dialogBinding: DialogLayoutBinding, state: NovelFilterState) {
        dialogBinding.mediaSourceTop.rotation = if (state.reversed) -90f else 90f
        dialogBinding.sortText.text = if (state.reversed) "Down to Up" else "Up to Down"
        dialogBinding.mediaSourceTop.setOnClickListener {
            state.reversed = !state.reversed
            dialogBinding.mediaSourceTop.rotation = if (state.reversed) -90f else 90f
            dialogBinding.sortText.text = if (state.reversed) "Down to Up" else "Up to Down"
            state.run = true
        }
    }

    private fun setupFilterStyle(dialogBinding: DialogLayoutBinding, state: NovelFilterState) {
        dialogBinding.mediaSourceGrid.visibility = View.VISIBLE
        var selected = when (state.style) {
            0 -> dialogBinding.mediaSourceList
            1 -> dialogBinding.mediaSourceCompact
            else -> dialogBinding.mediaSourceGrid
        }
        when (state.style) {
            0 -> dialogBinding.layoutText.setText(R.string.list)
            1 -> dialogBinding.layoutText.setText(R.string.compact)
            else -> dialogBinding.layoutText.text = "Cover"
        }
        selected.alpha = 1f

        fun updateSelected(button: ImageButton, newStyle: Int, text: String? = null, textRes: Int? = null) {
            selected.alpha = 0.33f
            selected = button
            selected.alpha = 1f
            state.style = newStyle
            if (textRes != null) {
                dialogBinding.layoutText.setText(textRes)
            } else if (text != null) {
                dialogBinding.layoutText.text = text
            }
            state.run = true
        }

        dialogBinding.mediaSourceList.setOnClickListener {
            updateSelected(it as ImageButton, 0, textRes = R.string.list)
        }
        dialogBinding.mediaSourceCompact.setOnClickListener {
            updateSelected(it as ImageButton, 1, textRes = R.string.compact)
        }
        dialogBinding.mediaSourceGrid.setOnClickListener {
            updateSelected(it as ImageButton, 2, text = "Cover")
        }
    }

    private fun setupFilterDownloads(dialogBinding: DialogLayoutBinding) {
        if (fragment.media.format == "LOCAL") {
            dialogBinding.animeDownloadContainer.visibility = View.GONE
        } else {
            dialogBinding.animeDownloadContainer.visibility = View.VISIBLE
            dialogBinding.mediaDownloadTop.visibility = View.VISIBLE
            dialogBinding.mediaDownloadTop.setOnClickListener {
                fragment.requireContext().customAlertDialog().apply {
                    setTitle("Multi Chapter Downloader")
                    setMessage("Enter the number of chapters to download")
                    val input = View.inflate(ani.dantotsu.currContext(), R.layout.dialog_layout, null)
                    val editText = input.findViewById<android.widget.EditText>(R.id.downloadNo)
                    setCustomView(input)
                    setPosButton(R.string.ok) {
                        val value = editText.text.toString().toIntOrNull()
                        if (value != null && value > 0) {
                            dialogBinding.downloadNo.setText(value.toString(), android.widget.TextView.BufferType.EDITABLE)
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
    }

    private fun setupFilterWebview(dialogBinding: DialogLayoutBinding) {
        val currentSource = fragment.source
        val isLocal = currentSource in 0 until novelReadSources.names.size && (novelReadSources.names[currentSource] == "Local" || novelReadSources.names[currentSource] == "Downloaded")
        if (isLocal) {
            dialogBinding.mediaWebviewContainer.visibility = View.GONE
        } else {
            dialogBinding.mediaWebviewContainer.visibility = View.VISIBLE
            dialogBinding.mediaWebviewContainer.setOnClickListener {
                if (!WebViewUtil.supportsWebView(fragment.requireContext())) {
                    ani.dantotsu.toast(R.string.webview_not_installed)
                    return@setOnClickListener
                }
                val src = fragment.source
                if (novelReadSources.names.isNotEmpty() && src in 0 until novelReadSources.names.size) {
                    val currentParser = novelReadSources[src]
                    val url = currentParser?.hostUrl?.takeIf { it.isNotBlank() && it != "Empty" && it != "Offline" }
                    url?.let {
                        val intent = Intent(fragment.requireContext(), CookieCatcher::class.java)
                            .putExtra("url", it)
                            .putExtra("headers", hashMapOf<String, String>())
                        fragment.requireContext().startActivity(intent)
                    }
                }
            }
        }
    }

    private fun setupFilterResetProgress(dialogBinding: DialogLayoutBinding) {
        dialogBinding.resetProgressDef.text = "Clear stored chapter details"
        dialogBinding.resetProgress.setOnClickListener {
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

    private fun showFilterMainDialog(dialogBinding: DialogLayoutBinding, state: NovelFilterState) {
        fragment.requireContext().customAlertDialog().apply {
            setTitle("Options")
            setCustomView(dialogBinding.root)
            setPosButton(R.string.ok) {
                if (state.run) {
                    fragment.onLayoutChanged(state.style, state.reversed)
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