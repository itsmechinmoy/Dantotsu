package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadQueueBinding
import ani.dantotsu.databinding.ItemDownloadQueueBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.findValidName
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.download.novel.NovelServiceDataSingleton
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.statusBarHeight
import ani.dantotsu.media.MediaType
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.SizeFormatter
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.loadImage
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadQueueBinding
    private lateinit var adapter: DownloadsAdapter
    private var updateJob: Job? = null
    private var selectedTabPosition = 0 // 0: Anime, 1: Manga, 2: Novel
    private val folderSizeCache = mutableMapOf<String, Long>()
    private val coverPathCache = mutableMapOf<String, String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDownloadQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.downloadQueueContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.downloadQueueBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.autoDeleteSwitch.isChecked = PrefManager.getCustomVal("auto_delete_downloads", false)
        binding.autoDeleteSwitch.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setCustomVal("auto_delete_downloads", isChecked)
        }

        adapter = DownloadsAdapter(
            coverPathCache = coverPathCache,
            onCancelQueueClick = { item -> cancelTask(item) },
            onDeleteMediaClick = { title, type -> showDeleteMediaDialog(title, type) }
        )

        binding.downloadQueueRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.downloadQueueRecyclerView.adapter = adapter

        binding.downloadQueueClear.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.clear_queue)
                setMessage(R.string.clear_queue_confirm)
                setPosButton(R.string.yes) {
                    clearAllQueues()
                }
                setNegButton(R.string.no)
                show()
            }
        }

        setupTabLayout()
    }

    override fun onResume() {
        super.onResume()
        folderSizeCache.clear()
        coverPathCache.clear()
        updateDownloadedMediaSizes(getSelectedMediaType())
        startUpdating()
    }

    override fun onPause() {
        super.onPause()
        stopUpdating()
    }

    private fun setupTabLayout() {
        val tabLayout = binding.downloadQueueTabLayout
        tabLayout.addTab(tabLayout.newTab().setText("Anime"))
        tabLayout.addTab(tabLayout.newTab().setText("Manga"))
        tabLayout.addTab(tabLayout.newTab().setText("Novel"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTabPosition = tab.position
                folderSizeCache.clear()
                coverPathCache.clear()
                updateDownloadedMediaSizes(getSelectedMediaType())
                startUpdating()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun getSelectedMediaType(): MediaType {
        return when (selectedTabPosition) {
            0 -> MediaType.ANIME
            1 -> MediaType.MANGA
            else -> MediaType.NOVEL
        }
    }

    private fun startUpdating() {
        stopUpdating()
        updateJob = lifecycleScope.launch {
            while (isActive) {
                refreshUI()

                // Update tab titles with active queue count and prevent flickering
                val tabLayout = binding.downloadQueueTabLayout
                val animeCount = AnimeServiceDataSingleton.currentTasks.size + AnimeServiceDataSingleton.downloadQueue.size
                val mangaCount = MangaServiceDataSingleton.currentTasks.size + MangaServiceDataSingleton.downloadQueue.size
                val novelCount = NovelServiceDataSingleton.currentTasks.size + NovelServiceDataSingleton.downloadQueue.size

                val tab0 = tabLayout.getTabAt(0)
                val text0 = if (animeCount > 0) "Anime ($animeCount)" else "Anime"
                if (tab0?.text != text0) tab0?.text = text0

                val tab1 = tabLayout.getTabAt(1)
                val text1 = if (mangaCount > 0) "Manga ($mangaCount)" else "Manga"
                if (tab1?.text != text1) tab1?.text = text1

                val tab2 = tabLayout.getTabAt(2)
                val text2 = if (novelCount > 0) "Novel ($novelCount)" else "Novel"
                if (tab2?.text != text2) tab2?.text = text2

                // Update status text
                val currentType = getSelectedMediaType()
                val currentTypeActiveList = getActiveQueueTasks(currentType)
                val activeCount = currentTypeActiveList.count { it.isActive }
                val queuedCount = currentTypeActiveList.size - activeCount
                binding.downloadQueueStatus.text = if (currentTypeActiveList.isNotEmpty() || getDownloadedMediaCount(currentType) > 0) {
                    "Downloading: $activeCount • Queued: $queuedCount"
                } else {
                    getString(R.string.download_queue_desc)
                }

                delay(1000)
            }
        }
    }

    private fun stopUpdating() {
        updateJob?.cancel()
        updateJob = null
    }

    private fun refreshUI() {
        val currentType = getSelectedMediaType()
        val newItems = getDownloadsListItems(currentType)
        adapter.submitList(newItems)

        binding.downloadQueueEmptyState.visibility = if (newItems.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadQueueRecyclerView.visibility = if (newItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun getDownloadedMediaCount(type: MediaType): Int {
        val downloadsManager = Injekt.get<DownloadsManager>()
        return when (type) {
            MediaType.ANIME -> downloadsManager.animeDownloadedTypes.groupBy { it.titleName }.size
            MediaType.MANGA -> downloadsManager.mangaDownloadedTypes.groupBy { it.titleName }.size
            MediaType.NOVEL -> downloadsManager.novelDownloadedTypes.groupBy { it.titleName }.size
        }
    }

    private fun getActiveQueueTasks(type: MediaType): List<QueueItem> {
        val activeList = when (type) {
            MediaType.ANIME -> AnimeServiceDataSingleton.currentTasks.toList().map { QueueItem.Anime(it, true) }
            MediaType.MANGA -> MangaServiceDataSingleton.currentTasks.toList().map { QueueItem.Manga(it, true) }
            MediaType.NOVEL -> NovelServiceDataSingleton.currentTasks.toList().map { QueueItem.Novel(it, true) }
        }
        val queuedList = when (type) {
            MediaType.ANIME -> AnimeServiceDataSingleton.downloadQueue.toList().map { QueueItem.Anime(it, false) }
            MediaType.MANGA -> MangaServiceDataSingleton.downloadQueue.toList().map { QueueItem.Manga(it, false) }
            MediaType.NOVEL -> NovelServiceDataSingleton.downloadQueue.toList().map { QueueItem.Novel(it, false) }
        }

        val queueItems = mutableListOf<QueueItem>()
        queueItems.addAll(activeList)
        for (item in queuedList) {
            if (!queueItems.any { it.uniqueId == item.uniqueId }) {
                queueItems.add(item)
            }
        }
        return queueItems
    }

    private fun getDownloadsListItems(type: MediaType): List<DownloadsListItem> {
        val listItems = mutableListOf<DownloadsListItem>()

        // 1. Active Queue Tasks
        val queueItems = getActiveQueueTasks(type)
        if (queueItems.isNotEmpty()) {
            listItems.add(DownloadsListItem.Header(getString(R.string.download_queue_header)))
            listItems.addAll(queueItems.map { DownloadsListItem.QueueItemWrapper(it) })
        }

        // 2. Downloaded Media Items (CRUD & Insights)
        val downloadsManager = Injekt.get<DownloadsManager>()
        val downloadedTypes = when (type) {
            MediaType.ANIME -> downloadsManager.animeDownloadedTypes
            MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
            MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
        }
        val grouped = downloadedTypes.groupBy { it.titleName }
        val mediaItems = grouped.map { (title, list) ->
            val sizeBytes = folderSizeCache[title] ?: 0L
            DownloadsListItem.DownloadedMediaItem(
                title = title,
                type = type,
                count = list.size,
                sizeBytes = sizeBytes
            )
        }.sortedBy { it.title }

        if (mediaItems.isNotEmpty()) {
            val totalSizeBytes = mediaItems.sumOf { it.sizeBytes }
            val totalTitles = mediaItems.size
            val totalStr = SizeFormatter.formatBytes(totalSizeBytes)
            val headerTitle = getString(R.string.download_insights_header) + " ($totalStr • $totalTitles titles)"
            listItems.add(DownloadsListItem.Header(headerTitle))
            listItems.addAll(mediaItems)
        }

        return listItems
    }

    private fun updateDownloadedMediaSizes(type: MediaType) {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadsManager = Injekt.get<DownloadsManager>()
            val downloadedTypes = when (type) {
                MediaType.ANIME -> downloadsManager.animeDownloadedTypes
                MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
                MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
            }
            val titles = downloadedTypes.map { it.titleName }.distinct()
            for (title in titles) {
                val folder = DownloadsManager.getSubDirectory(this@DownloadQueueActivity, type, false, title, null)
                val size = getFolderSize(folder)
                folderSizeCache[title] = size

                val coverFile = folder?.findFile("cover.jpg")
                val coverPath = if (coverFile != null && coverFile.exists()) {
                    coverFile.uri.toString()
                } else {
                    val cachedCover = PrefManager.getCustomVal<String>("local_cover_${title}", "")
                    if (cachedCover.isNotEmpty() && cachedCover != "null") cachedCover else null
                }
                coverPathCache[title] = coverPath
            }

            // Also cache covers for active queue items
            val activeQueue = getActiveQueueTasks(type)
            for (item in activeQueue) {
                val itemType = item.mediaType
                val itemTitle = item.title
                if (!coverPathCache.containsKey(itemTitle)) {
                    val folder = DownloadsManager.getSubDirectory(this@DownloadQueueActivity, itemType, false, itemTitle, null)
                    val coverFile = folder?.findFile("cover.jpg")
                    val coverPath = if (coverFile != null && coverFile.exists()) {
                        coverFile.uri.toString()
                    } else {
                        val cachedCover = PrefManager.getCustomVal<String>("local_cover_${itemTitle}", "")
                        if (cachedCover.isNotEmpty() && cachedCover != "null") cachedCover else null
                    }
                    coverPathCache[itemTitle] = coverPath
                }
            }

            withContext(Dispatchers.Main) {
                refreshUI()
            }
        }
    }

    private fun getFolderSize(directory: DocumentFile?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size = 0L
        for (file in directory.listFiles()) {
            if (file.isDirectory) {
                size += getFolderSize(file)
            } else {
                size += file.length()
            }
        }
        return size
    }

    private fun cancelTask(item: QueueItem) {
        when (item) {
            is QueueItem.Anime -> {
                val intent = Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                    putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, item.uniqueId)
                }
                sendBroadcast(intent)
            }
            is QueueItem.Manga -> {
                val intent = Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                    putExtra(MangaDownloaderService.EXTRA_CHAPTER, item.uniqueId)
                }
                sendBroadcast(intent)
            }
            is QueueItem.Novel -> {
                val intent = Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                    putExtra(NovelDownloaderService.EXTRA_CHAPTER, item.uniqueId)
                }
                sendBroadcast(intent)
            }
        }
        startUpdating()
    }

    private fun showDeleteMediaDialog(title: String, type: MediaType) {
        customAlertDialog().apply {
            setTitle("Delete Downloads")
            setMessage(getString(R.string.clear_media_confirm, title))
            setPosButton(R.string.yes) {
                val downloadsManager = Injekt.get<DownloadsManager>()
                downloadsManager.removeMedia(title, type)
                folderSizeCache.remove(title)
                updateDownloadedMediaSizes(getSelectedMediaType())
                startUpdating()
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun clearAllQueues() {
        // Clear Queues
        AnimeServiceDataSingleton.downloadQueue.clear()
        MangaServiceDataSingleton.downloadQueue.clear()
        NovelServiceDataSingleton.downloadQueue.clear()

        // Cancel running tasks
        val animeActive = AnimeServiceDataSingleton.currentTasks.toList()
        for (task in animeActive) {
            val intent = Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, task.getTaskName())
            }
            sendBroadcast(intent)
        }

        val mangaActive = MangaServiceDataSingleton.currentTasks.toList()
        for (task in mangaActive) {
            val intent = Intent(MangaDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                putExtra(MangaDownloaderService.EXTRA_CHAPTER, task.chapter)
            }
            sendBroadcast(intent)
        }

        val novelActive = NovelServiceDataSingleton.currentTasks.toList()
        for (task in novelActive) {
            val intent = Intent(NovelDownloaderService.ACTION_CANCEL_DOWNLOAD).apply {
                putExtra(NovelDownloaderService.EXTRA_CHAPTER, task.chapter)
            }
            sendBroadcast(intent)
        }

        startUpdating()
    }
}

sealed class QueueItem {
    abstract val title: String
    abstract val subTitle: String
    abstract val type: String
    abstract val uniqueId: String
    abstract val isActive: Boolean
    abstract val mediaType: MediaType

    data class Anime(val task: AnimeDownloaderService.AnimeDownloadTask, override val isActive: Boolean) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.episode
        override val type: String get() = "Anime"
        override val uniqueId: String get() = task.getTaskName()
        override val mediaType: MediaType get() = MediaType.ANIME
    }

    data class Manga(val task: MangaDownloaderService.DownloadTask, override val isActive: Boolean) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.chapter
        override val type: String get() = "Manga"
        override val uniqueId: String get() = task.chapter
        override val mediaType: MediaType get() = MediaType.MANGA
    }

    data class Novel(val task: NovelDownloaderService.DownloadTask, override val isActive: Boolean) : QueueItem() {
        override val title: String get() = task.title
        override val subTitle: String get() = task.chapter
        override val type: String get() = "Novel"
        override val uniqueId: String get() = task.chapter
        override val mediaType: MediaType get() = MediaType.NOVEL
    }
}

sealed class DownloadsListItem {
    data class Header(val title: String) : DownloadsListItem()
    data class QueueItemWrapper(val queueItem: QueueItem) : DownloadsListItem()
    data class DownloadedMediaItem(
        val title: String,
        val type: MediaType,
        val count: Int,
        val sizeBytes: Long
    ) : DownloadsListItem()
}

class DownloadsDiffCallback : DiffUtil.ItemCallback<DownloadsListItem>() {
    override fun areItemsTheSame(oldItem: DownloadsListItem, newItem: DownloadsListItem): Boolean {
        return when {
            oldItem is DownloadsListItem.Header && newItem is DownloadsListItem.Header -> oldItem.title == newItem.title
            oldItem is DownloadsListItem.QueueItemWrapper && newItem is DownloadsListItem.QueueItemWrapper ->
                oldItem.queueItem.type == newItem.queueItem.type && oldItem.queueItem.uniqueId == newItem.queueItem.uniqueId
            oldItem is DownloadsListItem.DownloadedMediaItem && newItem is DownloadsListItem.DownloadedMediaItem ->
                oldItem.title == newItem.title && oldItem.type == newItem.type
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: DownloadsListItem, newItem: DownloadsListItem): Boolean {
        return oldItem == newItem
    }
}

class DownloadsAdapter(
    private val coverPathCache: Map<String, String?>,
    private val onCancelQueueClick: (QueueItem) -> Unit,
    private val onDeleteMediaClick: (String, MediaType) -> Unit
) : ListAdapter<DownloadsListItem, RecyclerView.ViewHolder>(DownloadsDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_QUEUE = 1
        private const val TYPE_MEDIA = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DownloadsListItem.Header -> TYPE_HEADER
            is DownloadsListItem.QueueItemWrapper -> TYPE_QUEUE
            is DownloadsListItem.DownloadedMediaItem -> TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_downloads_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_QUEUE -> {
                val binding = ItemDownloadQueueBinding.inflate(inflater, parent, false)
                QueueViewHolder(binding)
            }
            else -> {
                val binding = ItemDownloadQueueBinding.inflate(inflater, parent, false)
                MediaViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is HeaderViewHolder -> {
                val header = item as DownloadsListItem.Header
                (holder.itemView as TextView).text = header.title
            }
            is QueueViewHolder -> {
                val wrapper = item as DownloadsListItem.QueueItemWrapper
                val queueItem = wrapper.queueItem
                holder.binding.itemDownloadTitle.text = queueItem.title
                holder.binding.itemDownloadSubtitle.text = "${queueItem.subTitle} • ${queueItem.type}"

                val progress = when (queueItem) {
                    is QueueItem.Anime -> AnimeServiceDataSingleton.progress[queueItem.uniqueId]
                    is QueueItem.Manga -> MangaServiceDataSingleton.progress[queueItem.uniqueId]
                    is QueueItem.Novel -> NovelServiceDataSingleton.progress[queueItem.uniqueId]
                }

                // Load cover image
                val coverPath = coverPathCache[queueItem.title]
                if (coverPath != null) {
                    holder.binding.itemDownloadImage.loadImage(coverPath)
                    holder.binding.itemDownloadImageCard.visibility = View.VISIBLE
                } else {
                    holder.binding.itemDownloadImageCard.visibility = View.GONE
                }

                holder.binding.itemDownloadCancel.setImageResource(R.drawable.ic_circle_cancel)

                if (queueItem.isActive) {
                    holder.binding.itemDownloadProgressLayout.visibility = View.VISIBLE
                    if (progress != null) {
                        holder.binding.itemDownloadProgressBar.isIndeterminate = false
                        holder.binding.itemDownloadProgressBar.progress = progress
                        holder.binding.itemDownloadProgressText.text = "$progress%"
                    } else {
                        holder.binding.itemDownloadProgressBar.isIndeterminate = true
                        holder.binding.itemDownloadProgressText.text = "0%"
                    }
                } else {
                    holder.binding.itemDownloadProgressLayout.visibility = View.GONE
                }

                holder.binding.itemDownloadCancel.setOnClickListener {
                    onCancelQueueClick(queueItem)
                }
            }
            is MediaViewHolder -> {
                val media = item as DownloadsListItem.DownloadedMediaItem
                holder.binding.itemDownloadTitle.text = media.title

                val countUnit = when (media.type) {
                    MediaType.ANIME -> if (media.count == 1) "episode" else "episodes"
                    else -> if (media.count == 1) "chapter" else "chapters"
                }
                val countText = "${media.count} $countUnit"
                val sizeStr = SizeFormatter.formatBytes(media.sizeBytes)
                holder.binding.itemDownloadSubtitle.text = "$countText • $sizeStr"

                // Load cover image
                val coverPath = coverPathCache[media.title]
                if (coverPath != null) {
                    holder.binding.itemDownloadImage.loadImage(coverPath)
                    holder.binding.itemDownloadImageCard.visibility = View.VISIBLE
                } else {
                    holder.binding.itemDownloadImageCard.visibility = View.GONE
                }

                holder.binding.itemDownloadCancel.setImageResource(R.drawable.ic_round_delete_24)
                holder.binding.itemDownloadCancel.setOnClickListener {
                    onDeleteMediaClick(media.title, media.type)
                }

                holder.binding.itemDownloadProgressLayout.visibility = View.GONE
            }
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class QueueViewHolder(val binding: ItemDownloadQueueBinding) : RecyclerView.ViewHolder(binding.root)
    class MediaViewHolder(val binding: ItemDownloadQueueBinding) : RecyclerView.ViewHolder(binding.root)
}
