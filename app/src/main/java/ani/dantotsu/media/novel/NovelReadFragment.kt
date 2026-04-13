package ani.dantotsu.media.novel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.databinding.FragmentMediaSourceBinding
import ani.dantotsu.dp
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.findValidName
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.download.novel.NovelServiceDataSingleton
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.novel.novelreader.NovelReaderActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.setBaseline
import ani.dantotsu.toPx
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.StoragePermissions.Companion.accessAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelReadFragment : Fragment(),
    DownloadTriggerCallback,
    DownloadedCheckCallback {

    private var _binding: FragmentMediaSourceBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()

    lateinit var media: Media
    var source = 0
    lateinit var novelName: String

    private lateinit var headerAdapter: NovelReadAdapter
    private lateinit var novelResponseAdapter: NovelResponseAdapter
    private var progress = View.VISIBLE


    var style: Int = 0
    var reverse: Boolean = false

    private var continueEp: Boolean = false
    var loaded = false

    private var isShowingChapters = false
    private var currentChapterLinks: List<ani.dantotsu.FileUrl> = emptyList()
    private var currentChapterResponses: List<ShowResponse>? = null
    private var storedSearchResults: List<ShowResponse>? = null
    private var start = 0
    private var end: Int? = null
    private var page = 0

    override fun downloadTrigger(novelDownloadPackage: NovelDownloadPackage) {
        Logger.log("novel link: ${novelDownloadPackage.link}")
        activity?.let {
            fun continueDownload() {
                val parser = model.novelSources[source] as? ani.dantotsu.parsers.novel.LnReaderNovelParser
                val downloadTask = NovelDownloaderService.DownloadTask(
                    title = media.mainName(),
                    chapter = novelDownloadPackage.novelName,
                    downloadLink = novelDownloadPackage.link,
                    originalLink = novelDownloadPackage.originalLink,
                    sourceMedia = media,
                    coverUrl = novelDownloadPackage.coverUrl,
                    retries = 2,
                    lnReaderParser = parser
                )
                NovelServiceDataSingleton.downloadQueue.offer(downloadTask)
                CoroutineScope(Dispatchers.IO).launch {

                    if (!NovelServiceDataSingleton.isServiceRunning) {
                        val intent = Intent(context, NovelDownloaderService::class.java)
                        withContext(Dispatchers.Main) {
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                        NovelServiceDataSingleton.isServiceRunning = true
                    }
                }
            }
            if (!StoragePermissions.hasDirAccess(it)) {
                (it as MediaDetailsActivity).accessAlertDialog(it.launcher) { success ->
                    if (success) {
                        continueDownload()
                    } else {
                        snackString(getString(R.string.download_permission_required))
                    }
                }
            } else {
                continueDownload()
            }
        }
    }

    override fun downloadedCheckWithStart(novel: ShowResponse): Boolean {
        if (media.format == "LOCAL" || media.format == "LOCAL_NOVEL") {
            try {
                val fileUri = android.net.Uri.parse(novel.link)
                val intent = Intent(context, NovelReaderActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(fileUri, "application/epub+zip")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
                return true
            } catch (e: Exception) {
                Logger.log(e)
                return false
            }
        }

        val downloadsManager = Injekt.get<DownloadsManager>()
        if (downloadsManager.queryDownload(
                DownloadedType(
                    media.mainName(),
                    novel.name.findValidName(),
                    MediaType.NOVEL
                )
            )
        ) {
            try {
                val directory =
                    DownloadsManager.getSubDirectory(
                        context ?: currContext()!!,
                        MediaType.NOVEL,
                        false,
                        media.mainName(),
                        novel.name
                    )
                val file = directory?.findFile("0.epub")
                if (file?.exists() == false) return false
                val fileUri = file?.uri ?: return false
                val intent = Intent(context, NovelReaderActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(fileUri, "application/epub+zip")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
                return true
            } catch (e: Exception) {
                Logger.log(e)
                return false
            }
        } else {
            return false
        }
    }

    override fun downloadedCheck(novel: ShowResponse): Boolean {
        if (media.format == "LOCAL") return true

        val downloadsManager = Injekt.get<DownloadsManager>()
        return downloadsManager.queryDownload(
            DownloadedType(
                media.mainName(),
                novel.name.findValidName(),
                MediaType.NOVEL
            )
        )
    }

    override fun deleteDownload(novel: ShowResponse) {
        val downloadsManager = Injekt.get<DownloadsManager>()
        downloadsManager.removeDownload(
            DownloadedType(
                media.mainName(),
                novel.name.findValidName(),
                MediaType.NOVEL
            )
        ) {}
    }

    private val downloadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!this@NovelReadFragment::novelResponseAdapter.isInitialized) return
            when (intent.action) {
                ACTION_DOWNLOAD_STARTED -> {
                    val link = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    link?.let {
                        novelResponseAdapter.startDownload(it)
                    }
                }

                ACTION_DOWNLOAD_FINISHED -> {
                    val link = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    link?.let {
                        novelResponseAdapter.stopDownload(it)
                    }
                }

                ACTION_DOWNLOAD_FAILED -> {
                    val link = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    link?.let {
                        novelResponseAdapter.purgeDownload(it)
                    }
                }

                ACTION_DOWNLOAD_PROGRESS -> {
                    val link = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    val progress = intent.getIntExtra("progress", 0)
                    link?.let {
                        novelResponseAdapter.updateDownloadProgress(it, progress)
                    }
                }
            }
        }
    }

    fun onNovelClick(novel: ShowResponse) {
        if (novelResponseAdapter.isDownloading(novel.link)) {
            return
        }
        ani.dantotsu.settings.saving.PrefManager.setCustomVal("${media.id}_last_read_volume", novel.name)

        if (isShowingChapters) {
            if (!downloadedCheckWithStart(novel)) {
                onChapterClick(novel)
            }
            return
        }

        if (downloadedCheckWithStart(novel)) {
            return
        }

        val parser = model.novelSources[source] as? ani.dantotsu.parsers.novel.LnReaderNovelParser
        if (parser != null) {
            // LNReader handling
            headerAdapter.progress?.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                model.loadNovelChapters(media, source, invalidate = true)
            }
        } else {
            // EPUB source
            val bookDialog = BookDialog.newInstance(novelName, novel, source)
            bookDialog.setCallback(object : BookDialog.Callback {
                override fun onDownloadTriggered(link: String) {
                    downloadTrigger(
                        NovelDownloadPackage(
                            link,
                            media.cover ?: novel.coverUrl.url,
                            novel.name,
                            novel.link
                        )
                    )
                    bookDialog.dismiss()
                }
            })
            bookDialog.show(parentFragmentManager, "dialog")
        }
    }

    private fun onChapterClick(chapter: ShowResponse) {
        val parser = model.novelSources[source] as? ani.dantotsu.parsers.novel.LnReaderNovelParser
            ?: return

        NovelReaderSession.chapters = currentChapterLinks
        NovelReaderSession.currentIndex = currentChapterLinks.indexOfFirst { it.url == chapter.link }.coerceAtLeast(0)
        NovelReaderSession.parser = parser

        val intent = Intent(requireContext(), NovelReaderActivity::class.java)
        startActivity(intent)
    }

    fun onBackFromChapters(): Boolean {
        if (isShowingChapters) {
            isShowingChapters = false
            headerAdapter.clearChips()
            novelResponseAdapter.clear()
            storedSearchResults?.let { novelResponseAdapter.submitList(it) }
            novelResponseAdapter.updateType(style)
            return true
        }
        return false
    }

    fun resetChapterState() {
        isShowingChapters = false
        currentChapterLinks = emptyList()
        currentChapterResponses = null
        headerAdapter.clearChips()
        novelResponseAdapter.clear()
    }

    fun overrideNovelSource(selected: ShowResponse) {
        resetChapterState()
        headerAdapter.progress?.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            model.overrideNovelChapters(source, selected, media.id)
            model.loadNovelChapters(media, source, invalidate = true)
        }
    }

    fun onChipClicked(page: Int, s: Int, e: Int) {
        this.page = page
        start = s
        end = e
        media.selected?.chip = page
        if (media.selected != null) {
            model.saveSelected(media.id, media.selected!!)
        }
        reloadChapters()
    }
    
    private fun reloadChapters() {
        val listToDisplay = if (isShowingChapters) currentChapterResponses else response
        if (listToDisplay != null) {
            var chunk = listToDisplay
            if (isShowingChapters && end != null) {
                val safeEnd = minOf(end!!, chunk.size - 1)
                if (start <= safeEnd && start < chunk.size) {
                    chunk = chunk.slice(start..safeEnd)
                } else {
                    chunk = emptyList()
                }
            }
            novelResponseAdapter.clear()
            val sortedList = if (reverse) chunk.reversed() else chunk
            novelResponseAdapter.submitList(sortedList)
        }
    }
    
    private fun updateChaptersTabs() {
        val chapterResponses = currentChapterResponses ?: return
        val total = chapterResponses.size
        val divisions = total.toDouble() / 10
        start = 0
        end = null
        val limit = when {
            (divisions < 25) -> 25
            (divisions < 50) -> 50
            else -> 100
        }
        headerAdapter.clearChips()
        if (total > limit) {
            val arr = chapterResponses.map { it.name }.toTypedArray()
            val stored = kotlin.math.ceil((total).toDouble() / limit).toInt()
            val position = (media.selected?.chip ?: 0).coerceIn(0, stored - 1)
            val last = if (position + 1 == stored) total else (limit * (position + 1))
            start = limit * position
            end = last - 1
            headerAdapter.updateChips(
                limit,
                arr,
                (1..stored).toList().toTypedArray(),
                position
            )
        }
    }

    var response: List<ShowResponse>? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_DOWNLOAD_STARTED)
            addAction(ACTION_DOWNLOAD_FINISHED)
            addAction(ACTION_DOWNLOAD_FAILED)
            addAction(ACTION_DOWNLOAD_PROGRESS)
        }

        ContextCompat.registerReceiver(
            requireContext(),
            downloadStatusReceiver,
            intentFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        val baselineAnchor = (activity as MediaDetailsActivity).binding.mediaBottomBarContainer ?: (activity as MediaDetailsActivity).binding.commentMessageContainer
        baselineAnchor?.let {
            val includeSystemPaddings = it != (activity as MediaDetailsActivity).binding.mediaBottomBarContainer
            binding.mediaSourceRecycler.setBaseline(it, includeSystemNavBar = includeSystemPaddings)
            binding.mediaSourceRecycler.clipToPadding = false
        }

        val screenWidth = resources.displayMetrics.widthPixels.dp
        var maxGridSize = (screenWidth / 100f).roundToInt()
        maxGridSize = max(4, maxGridSize - (maxGridSize % 2))

        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), maxGridSize)
        gridLayoutManager.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {

                if (position == 0 && ::headerAdapter.isInitialized) return maxGridSize
                return when (style) {
                    1 -> 1
                    else -> maxGridSize
                }
            }
        }
        binding.mediaSourceRecycler.layoutManager = gridLayoutManager

        binding.ScrollTop.setOnClickListener {
            binding.mediaSourceRecycler.scrollToPosition(10)
            binding.mediaSourceRecycler.smoothScrollToPosition(0)
        }
        binding.mediaSourceRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val position = gridLayoutManager.findFirstVisibleItemPosition()
                if (position > 2) {
                    binding.ScrollTop.translationY = -(navBarHeight + 12.toPx).toFloat()
                    binding.ScrollTop.visibility = View.VISIBLE
                } else {
                    binding.ScrollTop.visibility = View.GONE
                }
            }
        })
        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaSourceRecycler.scrollToPosition(0)
        }

        continueEp = model.continueMedia ?: false
        model.getMedia().observe(viewLifecycleOwner) {
            if (it != null) {
                media = it
                novelName = media.userPreferredName
                progress = View.GONE
                binding.mediaInfoProgressBar.visibility = progress
                if (!loaded) {
                    val sel = media.selected
                    source = sel?.sourceIndex ?: 0
                    val isLocal = sel?.server == "Local" || media.format == "LOCAL_NOVEL" || media.format == "LOCAL"
                    searchQuery = if (isLocal) (media.name ?: media.nameRomaji) else (sel?.server ?: media.name ?: media.nameRomaji)
                    headerAdapter = NovelReadAdapter(media, this, model.novelSources)
                    novelResponseAdapter = NovelResponseAdapter(
                        this,
                        this,
                        this
                    )

                    binding.mediaSourceRecycler.adapter =
                        ConcatAdapter(headerAdapter, novelResponseAdapter)
                    loaded = true

                    val isLnReader = source in 0 until model.novelSources.names.size
                            && model.novelSources[source] is ani.dantotsu.parsers.novel.LnReaderNovelParser
                    if (isLnReader) {
                        headerAdapter.progress?.visibility = View.VISIBLE
                        lifecycleScope.launch(Dispatchers.IO) {
                            model.loadNovelChapters(media, source)
                        }
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed({
                            search(searchQuery, source, auto = sel?.server == null || isLocal)
                        }, 100)
                    }
                }
            }
        }
        model.novelResponses.observe(viewLifecycleOwner) {
            if (it != null) {
                response = it
                searching = false
                val sortedList = if (reverse) it.reversed() else it
                novelResponseAdapter.submitList(sortedList)
                headerAdapter.updateContinue(it)
                headerAdapter.progress?.visibility = View.GONE
            }
        }

        model.getNovelChapters().observe(viewLifecycleOwner) { loadedChapters ->
            if (loadedChapters != null) {
                val chapters = loadedChapters[source]
                if (chapters != null) {
                    isShowingChapters = true
                    currentChapterResponses = chapters
                    currentChapterLinks = chapters.map { ch ->
                        ani.dantotsu.FileUrl(ch.link)
                    }
                    updateChaptersTabs()
                    novelResponseAdapter.clear()
                    var chunk = chapters
                    if (end != null) {
                        chunk = chunk.slice(start..end!!)
                    }
                    val sorted = if (reverse) chunk.reversed() else chunk
                    novelResponseAdapter.submitList(sorted)
                    novelResponseAdapter.updateType(style) 
                    headerAdapter.updateContinue(chapters)
                    headerAdapter.progress?.visibility = View.GONE
                } else {
                    headerAdapter.progress?.visibility = View.GONE
                }
            }
        }
    }

    lateinit var searchQuery: String
    private var searching = false
    fun search(query: String, source: Int, save: Boolean = false, auto: Boolean = false) {
        if (!searching) {
            this.source = source
            isShowingChapters = false
            headerAdapter.clearChips()
            novelResponseAdapter.clear()
            searchQuery = query
            headerAdapter.progress?.visibility = View.VISIBLE
            
            val isLnReader = source in 0 until model.novelSources.names.size
                    && model.novelSources[source] is ani.dantotsu.parsers.novel.LnReaderNovelParser

            if (isLnReader) {
                lifecycleScope.launch(Dispatchers.IO) {
                    model.loadNovelChapters(media, source, invalidate = true)
                }
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (auto || query == "") model.autoSearchNovels(media)
                    else model.searchNovels(query, source)
                }
            }
            searching = true
            if (save) {
                val selected = model.loadSelected(media)
                selected.server = query
                model.saveSelected(media.id, selected)
            }
        }
    }

    fun onSourceChange(i: Int) {
        val selected = model.loadSelected(media)
        selected.sourceIndex = i
        source = i
        selected.server = null
        model.saveSelected(media.id, selected)
        media.selected = selected
    }

    fun onLayoutChanged(newStyle: Int, newReverse: Boolean) {
        val styleChanged = style != newStyle
        val reverseChanged = reverse != newReverse
        style = newStyle
        reverse = newReverse

        if (styleChanged) {
            novelResponseAdapter.updateType(newStyle)
        }
        if (reverseChanged) {
            reloadChapters()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMediaSourceBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onDestroy() {
        model.mangaReadSources?.flushText()
        requireContext().unregisterReceiver(downloadStatusReceiver)
        super.onDestroy()
    }

    fun multiDownload(n: Int) {
        lifecycleScope.launch {
            val selected = media.userProgress ?: 0
            val chapters = currentChapterResponses ?: return@launch
            if (chapters.isEmpty() || n < 1) return@launch

            val progressChapterIndex = (chapters.indexOfFirst {
                ani.dantotsu.media.MediaNameAdapter.findChapterNumber(it.name)?.toInt() == selected
            } + 1).coerceAtLeast(0)
            
            val endIndex = (progressChapterIndex + n).coerceAtMost(chapters.size)
            val chaptersToDownload = chapters.subList(progressChapterIndex, endIndex)
            
            for (chapter in chaptersToDownload) {
                try {
                    downloadNovelSequentially(chapter)
                } catch (e: Exception) {
                    ani.dantotsu.snackString("Failed to download chapter: ${chapter.name}")
                }
            }
            ani.dantotsu.snackString("All downloads completed!")
        }
    }

    private suspend fun downloadNovelSequentially(chapter: ShowResponse) {
        withContext(Dispatchers.Main) {
            val downloaded = downloadedCheck(chapter)
            if (!downloaded && !novelResponseAdapter.isDownloading(chapter.link)) {
                val pack = NovelDownloadPackage(
                    chapter.link, chapter.coverUrl.url, chapter.name, chapter.link
                )
                downloadTrigger(pack)
                novelResponseAdapter.startDownload(chapter.link)
            }
        }
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.delay(2000)
        }
    }

    private var state: Parcelable? = null
    override fun onResume() {
        super.onResume()
        binding.mediaInfoProgressBar.visibility = progress
        binding.mediaSourceRecycler.layoutManager?.onRestoreInstanceState(state)
    }

    override fun onPause() {
        super.onPause()
        state = binding.mediaSourceRecycler.layoutManager?.onSaveInstanceState()
    }

    companion object {
        const val ACTION_DOWNLOAD_STARTED = "ani.dantotsu.ACTION_DOWNLOAD_STARTED"
        const val ACTION_DOWNLOAD_FINISHED = "ani.dantotsu.ACTION_DOWNLOAD_FINISHED"
        const val ACTION_DOWNLOAD_FAILED = "ani.dantotsu.ACTION_DOWNLOAD_FAILED"
        const val ACTION_DOWNLOAD_PROGRESS = "ani.dantotsu.ACTION_DOWNLOAD_PROGRESS"
        const val EXTRA_NOVEL_LINK = "extra_novel_link"
    }
}

interface DownloadTriggerCallback {
    fun downloadTrigger(novelDownloadPackage: NovelDownloadPackage)
}

interface DownloadedCheckCallback {
    fun downloadedCheck(novel: ShowResponse): Boolean
    fun downloadedCheckWithStart(novel: ShowResponse): Boolean
    fun deleteDownload(novel: ShowResponse)
}