package ani.dantotsu.media.manga

import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.content.ContextCompat.getString
import androidx.core.content.ContextCompat.startActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.currContext
import ani.dantotsu.databinding.CustomDialogLayoutBinding
import ani.dantotsu.databinding.DialogLayoutBinding
import ani.dantotsu.databinding.ItemMediaSourceBinding
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.SourceSearchDialogFragment
import ani.dantotsu.media.anime.handleProgress
import ani.dantotsu.openSettings
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.webview.CookieCatcher
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.MangaReadSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.OfflineMangaParser
import ani.dantotsu.px
import ani.dantotsu.settings.FAQActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.WebViewUtil
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch


class MangaReadAdapter(
    private val media: Media,
    private val fragment: MangaReadFragment,
    private val mangaReadSources: MangaReadSources
) : RecyclerView.Adapter<MangaReadAdapter.ViewHolder>() {

    var subscribe: MediaDetailsActivity.PopImageButton? = null
    private var _binding: ItemMediaSourceBinding? = null

    fun clearBinding() {
        _binding = null
        subscribe = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        _binding = null
        subscribe = null
    }
    val hiddenScanlators = mutableListOf<String>()
    var scanlatorSelectionListener: ScanlatorSelectionListener? = null
    var options = listOf<String>()
        set(value) {
            field = value
            updateScanlatorDropdown()
        }

    private fun clearCustomValsForMedia(mediaId: String, suffix: String) {
        val customVals = PrefManager.getAllCustomValsForMedia("$mediaId$suffix")
        customVals.forEach { (key) ->
            PrefManager.removeCustomVal(key)
            Log.d("PrefManager", "Removed key: $key")
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val bind =
            ItemMediaSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(bind)
    }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        _binding = binding
        val offline = !isOnline(binding.root.context) || PrefManager.getVal(PrefName.OfflineMode)

        setupHeaderControls(binding, offline)
        val source = setupSourceDropdown(binding)
        setupSubscribeButton(binding)

        binding.mediaTorrentButton.visibility = View.GONE
        binding.mediaNestedButton.setOnClickListener {
            showLayoutOptionsDialog(source)
        }

        handleChapters()
    }

    private fun setupHeaderControls(binding: ItemMediaSourceBinding, offline: Boolean) {
        binding.sourceTitle.setText(R.string.chaps)

        binding.faqbutton.setOnClickListener {
            val intent = Intent(fragment.requireContext(), FAQActivity::class.java)
            startActivity(fragment.requireContext(), intent, null)
        }

        binding.mediaSourceSearch.setOnClickListener {
            SourceSearchDialogFragment().show(
                fragment.requireActivity().supportFragmentManager,
                null
            )
        }

        binding.sourceTitle.setOnLongClickListener {
            fragment.requireContext().customAlertDialog().apply {
                setTitle(" Delete Progress for all chapters of ${media.nameRomaji}")
                setMessage("This will delete all the locally stored progress for chapters")
                setPosButton(R.string.ok) {
                    clearCustomValsForMedia("${media.id}", "_Chapter")
                    clearCustomValsForMedia("${media.id}", "_Vol")
                    snackString("Deleted the progress of Chapters for ${media.nameRomaji}")
                }
                setNegButton(R.string.no)
                show()
            }
            true
        }

        binding.mediaSourceNameContainer.isGone = offline
        binding.mediaSourceSettings.isGone = offline
        binding.mediaSourceSearch.isGone = offline
        binding.mediaSourceTitle.isGone = offline
    }

    private fun setupSourceDropdown(binding: ItemMediaSourceBinding): Int {
        var source = media.selected!!.sourceIndex.let { if (it >= mangaReadSources.names.size) 0 else it }
        setLanguageList(media.selected!!.langIndex, source)
        updateScanlatorDropdown()
        if (mangaReadSources.names.isNotEmpty() && source in 0 until mangaReadSources.names.size) {
            binding.mediaSource.setText(mangaReadSources.names[source])
            mangaReadSources[source].apply {
                binding.mediaSourceTitle.text = showUserText
                showUserTextListener = { MainScope().launch { binding.mediaSourceTitle.text = it } }
            }
        }
        hiddenScanlators.clear()
        media.selected?.scanlators?.let {
            hiddenScanlators.addAll(it)
        }
        val displayNames = mangaReadSources.names.filter { it != "Local" }
        binding.mediaSource.setAdapter(
            ArrayAdapter(
                fragment.requireContext(),
                R.layout.item_dropdown,
                displayNames
            )
        )
        binding.mediaSourceTitle.isSelected = true
        binding.mediaSource.setOnItemClickListener { _, _, i, _ ->
            val actualIndex = mangaReadSources.names.indexOf(displayNames[i])
            fragment.onSourceChange(actualIndex).apply {
                binding.mediaSourceTitle.text = showUserText
                showUserTextListener = { MainScope().launch { binding.mediaSourceTitle.text = it } }
                source = actualIndex
                setLanguageList(0, actualIndex)
            }
            subscribeButton(false)
            val invalidate = actualIndex == mangaReadSources.names.size - 1
            fragment.loadChapters(actualIndex, invalidate)
        }

        binding.mediaSourceLanguage.setOnItemClickListener { _, _, i, _ ->
            (mangaReadSources[source] as? DynamicMangaParser)?.let { ext ->
                ext.sourceLanguage = i
                fragment.onLangChange(i, ext.saveName)
                fragment.onSourceChange(media.selected!!.sourceIndex).apply {
                    binding.mediaSourceTitle.text = showUserText
                    showUserTextListener = { MainScope().launch { binding.mediaSourceTitle.text = it } }
                    setLanguageList(i, source)
                }
                subscribeButton(false)
                fragment.loadChapters(media.selected!!.sourceIndex, true)
            }
        }

        binding.mediaSourceSettings.setOnClickListener {
            (mangaReadSources[source] as? DynamicMangaParser)?.let { ext ->
                fragment.openSettings(ext.extension)
            }
        }
        return source
    }

    private fun setupSubscribeButton(binding: ItemMediaSourceBinding) {
        subscribe = MediaDetailsActivity.PopImageButton(
            fragment.lifecycleScope,
            binding.mediaSourceSubscribe,
            R.drawable.ic_round_notifications_active_24,
            R.drawable.ic_round_notifications_none_24,
            R.color.bg_opp,
            R.color.violet_400,
            fragment.subscribed,
            true
        ) {
            fragment.onNotificationPressed(it, binding.mediaSource.text.toString())
        }

        subscribeButton(false)

        binding.mediaSourceSubscribe.setOnLongClickListener {
            openSettings(fragment.requireContext(), CHANNEL_SUBSCRIPTION_CHECK)
        }
    }

    private class MangaLayoutOptionsState(
        var reversed: Boolean,
        var style: Int,
        var run: Boolean = false,
        var refresh: Boolean = false
    )

    private fun showLayoutOptionsDialog(source: Int) {
        val dialogBinding = DialogLayoutBinding.inflate(fragment.layoutInflater)
        val state = MangaLayoutOptionsState(
            reversed = media.selected!!.recyclerReversed,
            style = media.selected!!.recyclerStyle ?: PrefManager.getVal(PrefName.MangaDefaultView)
        )

        setupLayoutSortOptions(dialogBinding, state)
        setupLayoutStyleOptions(dialogBinding, state)
        setupLayoutWebviewOption(dialogBinding, source, state)
        setupLayoutDownloadOptions(dialogBinding)
        setupLayoutResetProgress(dialogBinding)
        showLayoutMainDialog(dialogBinding, source, state)
    }

    private fun setupLayoutSortOptions(dialogBinding: DialogLayoutBinding, state: MangaLayoutOptionsState) {
        dialogBinding.mediaSourceTop.rotation = if (state.reversed) -90f else 90f
        dialogBinding.sortText.text = if (state.reversed) "Down to Up" else "Up to Down"
        dialogBinding.mediaSourceTop.setOnClickListener {
            state.reversed = !state.reversed
            dialogBinding.mediaSourceTop.rotation = if (state.reversed) -90f else 90f
            dialogBinding.sortText.text = if (state.reversed) "Down to Up" else "Up to Down"
            state.run = true
        }
    }

    private fun setupLayoutStyleOptions(dialogBinding: DialogLayoutBinding, state: MangaLayoutOptionsState) {
        dialogBinding.mediaSourceGrid.visibility = View.GONE
        var selected = when (state.style) {
            0 -> dialogBinding.mediaSourceList
            1 -> dialogBinding.mediaSourceCompact
            else -> dialogBinding.mediaSourceList
        }
        val layoutRes = if (state.style == 1) R.string.compact else R.string.list
        dialogBinding.layoutText.setText(layoutRes)
        selected.alpha = 1f

        fun updateSelected(button: ImageButton, newStyle: Int, textRes: Int) {
            selected.alpha = 0.33f
            selected = button
            selected.alpha = 1f
            state.style = newStyle
            dialogBinding.layoutText.setText(textRes)
            state.run = true
        }

        dialogBinding.mediaSourceList.setOnClickListener {
            updateSelected(it as ImageButton, 0, R.string.list)
        }
        dialogBinding.mediaSourceCompact.setOnClickListener {
            updateSelected(it as ImageButton, 1, R.string.compact)
        }
    }

    private fun setupLayoutWebviewOption(
        dialogBinding: DialogLayoutBinding,
        source: Int,
        state: MangaLayoutOptionsState
    ) {
        dialogBinding.mediaWebviewContainer.setOnClickListener {
            if (!WebViewUtil.supportsWebView(fragment.requireContext())) {
                toast(R.string.webview_not_installed)
            }
            if (mangaReadSources.names.isNotEmpty() && source in 0 until mangaReadSources.names.size) {
                val sourceAHH = mangaReadSources[source] as? DynamicMangaParser
                val sourceHttp = sourceAHH?.extension?.sources?.firstOrNull() as? HttpSource
                val url = sourceHttp?.baseUrl
                url?.let {
                    state.refresh = true
                    val headersMap = try {
                        sourceHttp.headers.toMultimap().mapValues { entry -> entry.value.getOrNull(0) ?: "" }
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    val intent = Intent(fragment.requireContext(), CookieCatcher::class.java)
                        .putExtra("url", url)
                        .putExtra("headers", headersMap as HashMap<String, String>)
                    startActivity(fragment.requireContext(), intent, null)
                }
            }
        }
    }

    private fun setupLayoutDownloadOptions(dialogBinding: DialogLayoutBinding) {
        if (media.format == "LOCAL") {
            dialogBinding.animeDownloadContainer.visibility = View.GONE
            dialogBinding.mediaWebviewContainer.visibility = View.GONE
        } else {
            dialogBinding.mediaDownloadTop.visibility = View.VISIBLE
            dialogBinding.mediaDownloadTop.setOnClickListener {
                showMultiDownloadDialog(dialogBinding)
            }
        }
    }

    private fun setupLayoutResetProgress(dialogBinding: DialogLayoutBinding) {
        dialogBinding.resetProgress.setOnClickListener {
            fragment.requireContext().customAlertDialog().apply {
                setTitle(" Delete Progress for all chapters of ${media.nameRomaji}")
                setMessage("This will delete all the locally stored progress for chapters")
                setPosButton(R.string.ok) {
                    clearCustomValsForMedia("${media.id}", "_Chapter")
                    clearCustomValsForMedia("${media.id}", "_Vol")
                    snackString("Deleted the progress of Chapters for ${media.nameRomaji}")
                }
                setNegButton(R.string.no)
                show()
            }
        }
        dialogBinding.resetProgressDef.text = getString(currContext()!!, R.string.clear_stored_chapter)
    }

    private fun showLayoutMainDialog(
        dialogBinding: DialogLayoutBinding,
        source: Int,
        state: MangaLayoutOptionsState
    ) {
        fragment.requireContext().customAlertDialog().apply {
            setTitle("Options")
            setCustomView(dialogBinding.root)
            setPosButton("OK") {
                if (state.run) fragment.onIconPressed(state.style, state.reversed)
                val value = dialogBinding.downloadNo.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    fragment.multiDownload(value)
                }
                if (state.refresh) fragment.loadChapters(source, true)
            }
            setNegButton("Cancel") {
                if (state.refresh) fragment.loadChapters(source, true)
            }
            show()
        }
    }

    private fun showMultiDownloadDialog(dialogBinding: DialogLayoutBinding) {
        fragment.requireContext().customAlertDialog().apply {
            setTitle("Multi Chapter Downloader")
            setMessage("Enter the number of chapters to download")
            val input = View.inflate(currContext(), R.layout.dialog_layout, null)
            val editText = input.findViewById<EditText>(R.id.downloadNo)
            setCustomView(input)
            setPosButton(R.string.ok) {
                val value = editText.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    dialogBinding.downloadNo.setText(value.toString(), TextView.BufferType.EDITABLE)
                    fragment.multiDownload(value)
                } else {
                    toast("Please enter a valid number")
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }    fun subscribeButton(enabled: Boolean) {
        subscribe?.enabled(enabled)
    }

    // Chips
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

        fun handleChapters() {
        val binding = _binding ?: return
        val rawChapters = media.manga?.chapters
        if (rawChapters.isNullOrEmpty()) {
            binding.sourceContinue.visibility = View.GONE
            binding.sourceNotFound.visibility = View.GONE
            binding.faqbutton.visibility = View.GONE
            clearChips()
            binding.sourceProgressBar.visibility = View.VISIBLE
            return
        }

        val filteredChapters = rawChapters.filter { chapter ->
            if (mangaReadSources[media.selected!!.sourceIndex] is OfflineMangaParser) {
                true
            } else {
                chapter.value.scanlator !in hiddenScanlators
            }
        }

        val continueChap = findContinueChapter(filteredChapters)
        bindContinueChapter(binding, continueChap, filteredChapters)
        binding.sourceProgressBar.visibility = View.GONE

        handleMissingSourceSearch(binding, filteredChapters.isNotEmpty())
    }

    private fun findContinueChapter(filteredChapters: Map<String, ani.dantotsu.media.manga.MangaChapter>): ani.dantotsu.media.manga.MangaChapter? {
        val anilistEp = (media.userProgress ?: 0).plus(1)
        val appEp = PrefManager.getNullableCustomVal(
            "${media.id}_current_chp",
            null,
            String::class.java
        )?.let { MediaNameAdapter.findChapterNumber(it)?.toInt() ?: it.toIntOrNull() }
            ?: PrefManager.getNullableCustomVal("${media.id}_current_chp_num", null, String::class.java)?.toIntOrNull()
            ?: 1

        val maxAvailableChapter = filteredChapters.values.maxOfOrNull {
            MediaNameAdapter.findChapterNumber(it.number) ?: 0f
        } ?: filteredChapters.size.toFloat()

        if (media.userProgress != null && media.userProgress!!.toFloat() >= maxAvailableChapter && (media.userProgress ?: 0) >= appEp) {
            return null
        }

        val targetChpNum = (if (anilistEp > appEp) anilistEp else appEp).toFloat()
        return filteredChapters.values.find { ch ->
            val num = MediaNameAdapter.findChapterNumber(ch.number)
            num != null && (num == targetChpNum || num.toInt() == targetChpNum.toInt())
        } ?: filteredChapters.values.elementAtOrNull(targetChpNum.toInt() - 1)
    }

    private fun bindContinueChapter(
        binding: ItemMediaSourceBinding,
        initialContinueChap: ani.dantotsu.media.manga.MangaChapter?,
        filteredChapters: Map<String, ani.dantotsu.media.manga.MangaChapter>
    ) {
        var continueChap = initialContinueChap
        if (continueChap != null) {
            handleProgress(
                binding.itemMediaProgressCont,
                binding.itemMediaProgress,
                binding.itemMediaProgressEmpty,
                media.id,
                continueChap.number
            )
            val progressWeight = (binding.itemMediaProgress.layoutParams as LinearLayout.LayoutParams).weight
            if (progressWeight > 0.8f) {
                val chapterList = filteredChapters.values.toList()
                val currIdx = chapterList.indexOf(continueChap)
                if (currIdx != -1 && currIdx + 1 < chapterList.size) {
                    continueChap = chapterList[currIdx + 1]
                    handleProgress(
                        binding.itemMediaProgressCont,
                        binding.itemMediaProgress,
                        binding.itemMediaProgressEmpty,
                        media.id,
                        continueChap.number
                    )
                } else {
                    continueChap = null
                }
            }

            if (continueChap != null) {
                binding.sourceContinue.visibility = View.VISIBLE
                binding.itemMediaImage.loadImage(media.banner ?: media.cover)
                binding.mediaSourceContinueText.text = binding.root.context.getString(
                    R.string.continue_chapter,
                    continueChap.number,
                    if (!continueChap.title.isNullOrEmpty()) continueChap.title else ""
                )
                binding.sourceContinue.setOnClickListener {
                    fragment.onMangaChapterClick(continueChap)
                }
                if (fragment.continueEp && (binding.itemMediaProgress.layoutParams as LinearLayout.LayoutParams).weight < 0.8f) {
                    binding.sourceContinue.performClick()
                    fragment.continueEp = false
                }
            } else {
                binding.sourceContinue.visibility = View.GONE
            }
        } else {
            binding.sourceContinue.visibility = View.GONE
        }
    }

    private fun handleMissingSourceSearch(binding: ItemMediaSourceBinding, sourceFound: Boolean) {
        val isDownloadedSource = mangaReadSources[media.selected!!.sourceIndex] is OfflineMangaParser
        binding.sourceNotFound.text = if (isDownloadedSource && !sourceFound) {
            binding.root.context.getString(R.string.download_not_found)
        } else {
            binding.root.context.getString(R.string.source_not_found)
        }
        binding.sourceNotFound.isGone = sourceFound
        binding.faqbutton.isGone = sourceFound

        if (!sourceFound && PrefManager.getVal(PrefName.SearchSources)) {
            if (binding.mediaSource.adapter.count > media.selected!!.sourceIndex + 1) {
                val nextIndex = media.selected!!.sourceIndex + 1
                binding.mediaSource.setText(
                    binding.mediaSource.adapter.getItem(nextIndex).toString(),
                    false
                )
                fragment.onSourceChange(nextIndex).apply {
                    binding.mediaSourceTitle.text = showUserText
                    showUserTextListener = { MainScope().launch { binding.mediaSourceTitle.text = it } }
                    setLanguageList(0, nextIndex)
                }
                subscribeButton(false)
                val invalidate = nextIndex == mangaReadSources.names.size - 1
                fragment.loadChapters(nextIndex, invalidate)
            }
        }
    }    private fun setLanguageList(lang: Int, source: Int) {
        val binding = _binding
        if (mangaReadSources is MangaSources) {
            val parser = mangaReadSources[source] as? DynamicMangaParser
            if (parser != null) {
                (mangaReadSources[source] as? DynamicMangaParser)?.let { ext ->
                    ext.sourceLanguage = lang
                }
                try {
                    binding?.mediaSourceLanguage?.setText(parser.extension.sources[lang].lang)
                } catch (e: IndexOutOfBoundsException) {
                    binding?.mediaSourceLanguage?.setText(
                        parser.extension.sources.firstOrNull()?.lang ?: "Unknown"
                    )
                }
                val adapter = ArrayAdapter(
                    fragment.requireContext(),
                    R.layout.item_dropdown,
                    parser.extension.sources.map { LanguageMapper.getLanguageName(it.lang) }
                )
                val items = adapter.count
                binding?.mediaSourceLanguageContainer?.isVisible = items > 1

                binding?.mediaSourceLanguage?.setAdapter(adapter)

            }
        }
    }

    fun updateScanlatorDropdown() {
        val binding = _binding ?: return
        if (options.size > 1) {
            binding.mediaSourceScanlatorContainer.visibility = View.VISIBLE
            binding.mediaSourceScanlatorContainer.hint = currActivity()?.getString(R.string.scanlator)

            val allText = currActivity()?.getString(R.string.all_scanlators) ?: "All Scanlators"
            val dropdownItems = listOf(allText) + options

            val selectedText = if (hiddenScanlators.isEmpty() || hiddenScanlators.size >= options.size) {
                allText
            } else {
                val shown = options.firstOrNull { it !in hiddenScanlators }
                shown ?: allText
            }

            binding.mediaSourceScanlator.setText(selectedText, false)
            val adapter = ArrayAdapter(
                fragment.requireContext(),
                R.layout.item_dropdown,
                dropdownItems
            )
            binding.mediaSourceScanlator.setAdapter(adapter)
            binding.mediaSourceScanlator.setOnItemClickListener { _, _, i, _ ->
                if (i == 0) {
                    hiddenScanlators.clear()
                } else {
                    val selectedOption = options.getOrNull(i - 1)
                    hiddenScanlators.clear()
                    if (selectedOption != null) {
                        hiddenScanlators.addAll(options.filter { it != selectedOption })
                    }
                }
                fragment.onScanlatorChange(hiddenScanlators)
                scanlatorSelectionListener?.onScanlatorsSelected()
            }
        } else {
            binding.mediaSourceScanlatorContainer.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = 1

    inner class ViewHolder(val binding: ItemMediaSourceBinding) :
        RecyclerView.ViewHolder(binding.root)
}

interface ScanlatorSelectionListener {
    fun onScanlatorsSelected()
}
