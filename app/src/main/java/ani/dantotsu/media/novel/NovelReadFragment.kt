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
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.cardview.widget.CardView
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import ani.dantotsu.parsers.novel.ConfigurableNovelSource
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.settings.extensionprefs.NovelSourcePreferencesFragment
import ani.dantotsu.util.customAlertDialog
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
import ani.dantotsu.isOnline
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
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
    private var sourceRequested = false
    private var isReceiverRegistered = false

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
                if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(it)) {
                    snackString(getString(R.string.download_wifi_only_warning))
                    return
                }
                val parser = model.novelSources[source] as? ani.dantotsu.parsers.novel.LnReaderNovelParser
                val downloadTask = NovelDownloaderService.DownloadTask(
                    title = media.mainName(),
                    chapter = novelDownloadPackage.novelName,
                    downloadLink = novelDownloadPackage.link,
                    originalLink = novelDownloadPackage.originalLink,
                    sourceMedia = media,
                    coverUrl = novelDownloadPackage.coverUrl,
                    retries = 2,
                    simultaneousDownloads = 2,
                    parser = parser
                )

                NovelServiceDataSingleton.downloadQueue.offer(downloadTask)
                NovelServiceDataSingleton.isServiceRunning = true

                val intent = Intent(it, NovelDownloaderService::class.java)
                ContextCompat.startForegroundService(it, intent)
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

    private fun hasEpubOrZipFiles(dir: androidx.documentfile.provider.DocumentFile): Boolean {
        return dir.listFiles().any {
            it.isFile && (it.name?.endsWith(".epub", ignoreCase = true) == true || it.type == "application/epub+zip")
        }
    }

    override fun downloadedCheckWithStart(novel: ShowResponse): Boolean {
        if (media.format == "LOCAL" || media.format == "LOCAL_NOVEL") return true

        val titleToSearch = novel.extra?.get("title") ?: media.mainName()
        val dir = DownloadsManager.findSubDirectory(
            context ?: currContext()!!,
            MediaType.NOVEL,
            titleToSearch,
            novel.name
        ) ?: return false

        return try {
            if (hasEpubOrZipFiles(dir)) return true
            val downloadsManager = Injekt.get<DownloadsManager>()
            findMatchingDownloadedType(downloadsManager, novel) != null
        } catch (e: Exception) {
            Logger.log(e)
            false
        }
    }

    private fun findMatchingDownloadedType(
        downloadsManager: DownloadsManager,
        novel: ShowResponse
    ): DownloadedType? {
        val extraTitle = novel.extra?.get("title")
        val validName = novel.name.findValidName()
        return downloadsManager.novelDownloadedTypes.firstOrNull { item ->
            val titleMatches = item.titleName.equals(media.mainName(), ignoreCase = true) ||
                    item.titleName.equals(media.name, ignoreCase = true) ||
                    item.titleName.equals(media.nameRomaji, ignoreCase = true) ||
                    (extraTitle != null && item.titleName.equals(extraTitle, ignoreCase = true))
            val chapterMatches = item.chapterName.equals(novel.name, ignoreCase = true) ||
                    item.chapterName.equals(validName, ignoreCase = true)
            titleMatches && chapterMatches
        }
    }

    private fun hasEpubFilesOnDisk(novel: ShowResponse): Boolean {
        val titleToSearch = novel.extra?.get("title") ?: media.mainName()
        val dir = DownloadsManager.findSubDirectory(
            context ?: currContext()!!,
            MediaType.NOVEL,
            titleToSearch,
            novel.name
        ) ?: return false

        return dir.findFile("0.epub") != null || dir.listFiles().any {
            it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true
        }
    }

    override fun downloadedCheck(novel: ShowResponse): Boolean {
        if (media.format == "LOCAL" || media.format == "LOCAL_NOVEL" ||
            novel.link.startsWith("content://") || novel.link.startsWith("file://")
        ) return true

        val downloadsManager = Injekt.get<DownloadsManager>()
        if (findMatchingDownloadedType(downloadsManager, novel) != null) return true

        return hasEpubFilesOnDisk(novel)
    }

    override fun deleteDownload(novel: ShowResponse) {
        val downloadsManager = Injekt.get<DownloadsManager>()
        val downloadedItem = findMatchingDownloadedType(downloadsManager, novel)
        val titleToUse = downloadedItem?.titleName ?: novel.extra?.get("title") ?: media.mainName()
        val chapterToUse = downloadedItem?.chapterName ?: novel.name.findValidName()
        downloadsManager.removeDownload(
            DownloadedType(
                titleToUse,
                chapterToUse,
                MediaType.NOVEL
            )
        ) {}
    }

    private val downloadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DOWNLOAD_STARTED -> {
                    val novelLink = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    novelLink?.let { novelResponseAdapter.startDownload(it) }
                }

                ACTION_DOWNLOAD_FINISHED -> {
                    val novelLink = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    novelLink?.let { novelResponseAdapter.stopDownload(it) }
                }

                ACTION_DOWNLOAD_FAILED -> {
                    val novelLink = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    novelLink?.let {
                        novelResponseAdapter.purgeDownload(it)
                    }
                }

                ACTION_DOWNLOAD_PROGRESS -> {
                    val novelLink = intent.getStringExtra(EXTRA_NOVEL_LINK)
                    val progress = intent.getIntExtra("progress", 0)
                    novelLink?.let {
                        novelResponseAdapter.updateDownloadProgress(
                            it,
                            progress
                        )
                    }
                }
            }
        }
    }

    fun onNovelClick(novel: ShowResponse) {
        val isLnReader = source in 0 until model.novelSources.names.size
                && model.novelSources[source] is ani.dantotsu.parsers.novel.LnReaderNovelParser

        if (isLnReader) {
            val position = currentChapterResponses?.indexOf(novel) ?: 0
            val intent = Intent(requireContext(), NovelReaderActivity::class.java).apply {
                putExtra("media", media)
                putExtra("novel_title", novel.name)
                putExtra("chapter_index", position)
                putExtra("chapter_responses", ArrayList(currentChapterResponses ?: emptyList()))
                putExtra("source_index", source)
            }
            startActivity(intent)
        } else {
            val intent = Intent(requireContext(), NovelReaderActivity::class.java).apply {
                val extras = Bundle().apply {
                    putParcelable("media", media)
                    putString("novel_title", novel.name)
                    putString("novel_link", novel.link)
                    putString("novel_cover", novel.coverUrl.url)
                    putSerializable("novel_headers", novel.coverUrl.headers as java.io.Serializable)
                    putInt("source_index", source)
                    putString("actual_novel_title", novel.name)
                    putInt("user_progress", media.userProgress ?: 0)
                }
                putExtras(extras)
            }
            startActivity(intent)
        }
    }

    fun onChapterClick(novel: ShowResponse, position: Int) {
        val intent = Intent(requireContext(), NovelReaderActivity::class.java).apply {
            putExtra("media", media)
            putExtra("novel_title", novel.name)
            putExtra("chapter_index", position)
            putExtra("chapter_responses", ArrayList(currentChapterResponses ?: emptyList()))
            putExtra("source_index", source)
        }
        startActivity(intent)
    }

    fun onBackFromChapters() {
        isShowingChapters = false
        headerAdapter.clearChips()
        novelResponseAdapter.clear()
        val responses = storedSearchResults ?: response ?: emptyList()
        val sortedList = if (reverse) responses.reversed() else responses
        novelResponseAdapter.submitList(sortedList)
        headerAdapter.updateContinue(responses)
    }

    fun resetChapterState() {
        isShowingChapters = false
        currentChapterLinks = emptyList()
        currentChapterResponses = null
        storedSearchResults = null
    }

    fun overrideNovelSource(novel: ShowResponse, newSourceIndex: Int) {
        source = newSourceIndex
        isShowingChapters = false
        headerAdapter.clearChips()
        novelResponseAdapter.clear()
        headerAdapter.startLoading()
        search(novel.name, newSourceIndex, save = true)
    }

    fun onChipClicked(i: Int, s: Int, e: Int) {
        media.selected?.chip = i
        start = s
        end = e
        model.saveSelected(media.id, media.selected!!)
        reloadChapters()
    }

    private fun reloadChapters() {
        val chapters = currentChapterResponses ?: return
        var chunk = chapters
        if (end != null) {
            chunk = chunk.slice(start..end!!)
        }
        val sorted = if (reverse) chunk.reversed() else chunk
        novelResponseAdapter.clear()
        novelResponseAdapter.submitList(sorted)
        novelResponseAdapter.updateType(style)
        headerAdapter.updateContinue(chapters)
    }

    private fun updateChaptersTabs() {
        val chapterResponses = currentChapterResponses ?: return
        val total = chapterResponses.size
        val divisions = total.toDouble() / 10
        start = 0
        end = null
        val limit = when {
            divisions < 25 -> 25
            divisions < 50 -> 50
            else -> 100
        }
        headerAdapter.clearChips()
        if (total > limit) {
            val arr = chapterResponses.map { it.name }.toTypedArray()
            val stored = kotlin.math.ceil(total.toDouble() / limit).toInt()

            val targetIndex = findTargetChapterIndex(chapterResponses)
            val targetChip = if (targetIndex >= 0) (targetIndex / limit).coerceIn(0, stored - 1) else 0
            val position = if ((media.selected?.chip ?: 0) == 0 && targetChip > 0) {
                media.selected?.chip = targetChip
                targetChip
            } else {
                (media.selected?.chip ?: 0).coerceIn(0, stored - 1)
            }
            val last = if (position + 1 == stored) total else limit * (position + 1)
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

    private fun findTargetChapterIndex(chapterResponses: List<ShowResponse>): Int {
        val lastReadName = PrefManager.getCustomVal("${media.id}_last_read_volume", "")
        var targetIndex = if (lastReadName.isNotBlank()) {
            chapterResponses.indexOfFirst { it.name == lastReadName }
        } else {
            -1
        }
        if (targetIndex == -1 && (media.userProgress ?: 0) > 0) {
            targetIndex = ((media.userProgress ?: 1) - 1).coerceIn(0, chapterResponses.size - 1)
        }
        return targetIndex
    }

    var response: List<ShowResponse>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        registerDownloadReceiver()
        adjustRecyclerBottomPadding()
        val gridLayoutManager = setupRecyclerLayout()
        setupSwipeRefresh()
        setupScrollTop(gridLayoutManager)
        setupMediaObserver()
        observeNovelResponses()
        observeNovelChapters()
    }

    private fun registerDownloadReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_DOWNLOAD_STARTED)
                addAction(ACTION_DOWNLOAD_FINISHED)
                addAction(ACTION_DOWNLOAD_FAILED)
                addAction(ACTION_DOWNLOAD_PROGRESS)
            }
            ContextCompat.registerReceiver(
                requireContext(),
                downloadStatusReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    private fun adjustRecyclerBottomPadding() {
        (activity as? MediaDetailsActivity)?.let { detailsAct ->
            if (detailsAct.bindingReady) {
                val actBinding = detailsAct.binding
                val anchor = actBinding.mediaBottomBarContainer ?: actBinding.commentMessageContainer
                if (anchor != null) {
                    val navBarPad = anchor != actBinding.mediaBottomBarContainer
                    binding.mediaSourceRecycler.setBaseline(anchor, includeSystemNavBar = navBarPad)
                    binding.mediaSourceRecycler.clipToPadding = false
                }
            }
        }
    }

    private fun setupRecyclerLayout(): androidx.recyclerview.widget.GridLayoutManager {
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
        return gridLayoutManager
    }

    private fun setupSwipeRefresh() {
        binding.mediaSourceSwipeRefresh.apply {
            val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            setColorSchemeColors(primaryColor)
            setProgressBackgroundColorSchemeResource(R.color.nav_bg)
            setOnRefreshListener {
                if (!this@NovelReadFragment::media.isInitialized) {
                    isRefreshing = false
                    return@setOnRefreshListener
                }
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ctx = context
                    val offline = (ctx != null && !isOnline(ctx)) || PrefManager.getVal(PrefName.OfflineMode)
                    if (offline && media.format != "LOCAL") {
                        media.selected!!.sourceIndex = model.novelSources.list.lastIndex
                    }
                    model.loadNovelChapters(media, source, invalidate = true)
                    withContext(Dispatchers.Main) {
                        _binding?.mediaSourceSwipeRefresh?.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun setupScrollTop(gridLayoutManager: androidx.recyclerview.widget.GridLayoutManager) {
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
    }

    private fun setupMediaObserver() {
        continueEp = model.continueMedia ?: false
        model.getMedia().observe(viewLifecycleOwner) {
            if (it != null) {
                handleMediaLoaded(it)
            }
        }
    }

    private fun handleMediaLoaded(it: Media) {
        media = it
        novelName = media.userPreferredName
        progress = View.GONE
        binding.mediaInfoProgressBar.visibility = progress
        if (!loaded) {
            initializeAdaptersAndQuery()
        }
    }

    private fun initializeAdaptersAndQuery() {
        val sel = media.selected
        source = sel?.sourceIndex ?: 0
        val isLocal = sel?.server == "Local" || media.format == "LOCAL_NOVEL" || media.format == "LOCAL"
        searchQuery = if (isLocal) media.name ?: media.nameRomaji else sel?.server ?: media.name ?: media.nameRomaji
        headerAdapter = NovelReadAdapter(media, this, model.novelSources)
        novelResponseAdapter = NovelResponseAdapter(this, this, this)

        binding.mediaSourceRecycler.adapter = ConcatAdapter(headerAdapter, novelResponseAdapter)
        loaded = true

        val isLnReader = source in 0 until model.novelSources.names.size &&
                model.novelSources[source] is ani.dantotsu.parsers.novel.LnReaderNovelParser
        if (isLnReader) {
            sourceRequested = true
            binding.mediaSourceRecycler.post { headerAdapter.startLoading() }
            lifecycleScope.launch(Dispatchers.IO) {
                model.loadNovelChapters(media, source)
            }
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                search(searchQuery, source, auto = sel?.server == null || isLocal)
            }, 100)
        }
    }

    private fun observeNovelResponses() {
        model.novelResponses.observe(viewLifecycleOwner) {
            if (it != null && sourceRequested) {
                response = it
                searching = false
                val sortedList = if (reverse) it.reversed() else it
                novelResponseAdapter.submitList(sortedList)
                headerAdapter.updateContinue(it)
                headerAdapter.progress?.visibility = View.GONE
                headerAdapter.handleSourceNotFound(it.isEmpty())
            }
        }
    }

    private fun observeNovelChapters() {
        model.getNovelChapters().observe(viewLifecycleOwner) { loadedChapters ->
            if (loadedChapters != null && loadedChapters.containsKey(source)) {
                handleLoadedChapters(loadedChapters[source])
            }
        }
    }

    private fun handleLoadedChapters(chapters: List<ShowResponse>?) {
        searching = false
        if (!chapters.isNullOrEmpty()) {
            isShowingChapters = true
            currentChapterResponses = chapters
            currentChapterLinks = chapters.map { ch ->
                val headers = mutableMapOf("X-Chapter-Name" to ch.name)
                ch.extra?.get("releaseTime")?.let { headers["X-Release-Time"] = it }
                ch.extra?.get("chapterNumber")?.let { headers["X-Chapter-Number"] = it }
                ani.dantotsu.FileUrl(ch.link, headers)
            }
            updateChaptersTabs()
            novelResponseAdapter.clear()
            val chunk = if (end != null) chapters.slice(start..end!!) else chapters
            val sorted = if (reverse) chunk.reversed() else chunk
            novelResponseAdapter.submitList(sorted)
            novelResponseAdapter.updateType(style)
            headerAdapter.updateContinue(chapters)
            headerAdapter.progress?.visibility = View.GONE
            headerAdapter.handleSourceNotFound(false)
        } else {
            headerAdapter.progress?.visibility = View.GONE
            headerAdapter.handleSourceNotFound(true)
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
            sourceRequested = true
            headerAdapter.startLoading()

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
        searching = false
        resetChapterState()
        novelResponseAdapter.clear()
        headerAdapter.startLoading()
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

    override fun onDestroyView() {
        if (isReceiverRegistered) {
            try {
                context?.unregisterReceiver(downloadStatusReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        if (::headerAdapter.isInitialized) {
            headerAdapter.clearBinding()
        }
        super.onDestroyView()
        _binding?.mediaSourceRecycler?.adapter = null
        _binding = null
    }

    override fun onDestroy() {
        model.mangaReadSources?.flushText()
        if (isReceiverRegistered) {
            try {
                context?.unregisterReceiver(downloadStatusReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
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

    fun openSettings(pkg: NovelExtension.Installed) {
        val changeUIVisibility: (Boolean) -> Unit = { show ->
            val activity = activity
            if (activity is MediaDetailsActivity && isAdded) {
                activity.findViewById<AppBarLayout>(R.id.mediaAppBar).isVisible = show
                activity.findViewById<ViewPager2>(R.id.mediaViewPager).isVisible = show
                activity.findViewById<CardView>(R.id.mediaCover).isVisible = show
                activity.findViewById<CardView>(R.id.mediaClose).isVisible = show
                activity.navBar.isVisible = show
                activity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
            }
        }
        val configurable = pkg.sources.filterIsInstance<ConfigurableNovelSource>()
        val hasPrefMethod = pkg.sources.any { src ->
            src.javaClass.methods.any { it.name == "setupPreferenceScreen" }
        }
        if (configurable.isNotEmpty() || hasPrefMethod) {
            changeUIVisibility(false)
            val fragment = NovelSourcePreferencesFragment().getInstance(pkg.pkgName) {
                changeUIVisibility(true)
                val sel = media.selected?.sourceIndex ?: 0
                lifecycleScope.launch {
                    model.loadNovelChapters(media, sel, invalidate = true)
                }
            }
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                .replace(R.id.fragmentExtensionsContainer, fragment)
                .addToBackStack(null)
                .commit()
        } else {
            requireContext().customAlertDialog().apply {
                setTitle(pkg.name)
                val msg = buildString {
                    append("Package: ${pkg.pkgName}
")
                    append("Version: ${pkg.versionName}
")
                    pkg.repository?.let { append("Repository: $it
") }
                    append("Sources: ${pkg.sources.size}")
                }
                setMessage(msg)
                setPosButton(R.string.ok)
                show()
            }
        }
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
