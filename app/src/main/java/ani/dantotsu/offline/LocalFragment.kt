package ani.dantotsu.offline

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.LayoutAnimationController
import android.widget.AbsListView
import android.widget.AutoCompleteTextView
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import ani.dantotsu.R
import ani.dantotsu.bottomBar
import ani.dantotsu.download.anime.OfflineAnimeAdapter
import ani.dantotsu.download.anime.OfflineAnimeModel
import ani.dantotsu.download.anime.OfflineAnimeSearchListener
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.Selected
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalFragment : Fragment(), OfflineAnimeSearchListener {

    private val localAnimeSource by lazy { LocalAnimeSource(Injekt.get<Application>()) }
    private val localMangaSource by lazy { LocalMangaSource(Injekt.get<Application>()) }
    private var downloads: List<OfflineAnimeModel> = listOf()
    
    private lateinit var gridView: GridView
    private lateinit var adapter: OfflineAnimeAdapter
    private lateinit var total: TextView
    private lateinit var noAnimeText: TextView
    private var scanJob: Job = Job()

    enum class LocalMediaType { ANIME, MANGA, NOVEL }
    private var currentMode = LocalMediaType.ANIME
    private val localNovelSource by lazy { tachiyomi.source.local.entries.novel.LocalNovelSource(Injekt.get<Application>()) }

    // SAF folder
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)
            var localDir = docFile?.findFile("local")
            if (localDir == null) {
                localDir = docFile?.createDirectory("local")
            }
            localDir?.findFile("anime") ?: localDir?.createDirectory("anime")
            localDir?.findFile("manga") ?: localDir?.createDirectory("manga")
            localDir?.findFile("novel") ?: localDir?.createDirectory("novel")
            localDir?.findFile(".nomedia") ?: localDir?.createFile("", ".nomedia")
            
            PrefManager.setVal(PrefName.LocalDir, uri.toString())
            snackString("Master 'local' folder selected/created! Place anime, manga, and novels in their respective subfolders and refresh.")
            scanLocal()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_offline_page, container, false)

        val textInputLayout = view.findViewById<TextInputLayout>(R.id.offlineMangaSearchBar)
        textInputLayout.hint = when (currentMode) {
            LocalMediaType.ANIME -> "Local Anime"
            LocalMediaType.MANGA -> "Local Manga"
            LocalMediaType.NOVEL -> "Local Novel"
        }
        val currentColor = textInputLayout.boxBackgroundColor
        val semiTransparentColor = (currentColor and 0x00FFFFFF) or 0xA8000000.toInt()
        textInputLayout.boxBackgroundColor = semiTransparentColor
        val materialCardView = view.findViewById<MaterialCardView>(R.id.offlineMangaAvatarContainer)
        materialCardView.setCardBackgroundColor(semiTransparentColor)
        val color = requireContext().getThemeColor(android.R.attr.windowBackground)

        // folder picker
        val folderButton = view.findViewById<ShapeableImageView>(R.id.offlineMangaUserAvatar)
        folderButton.setImageResource(R.drawable.ic_round_source_24)
        folderButton.setSafeOnClickListener {
            folderPickerLauncher.launch(null)
        }

        if (!(PrefManager.getVal(PrefName.ImmersiveMode) as Boolean)) {
            view.rootView.fitsSystemWindows = true
        }

        textInputLayout.boxBackgroundColor = (color and 0x00FFFFFF) or 0x28000000
        materialCardView.setCardBackgroundColor((color and 0x00FFFFFF) or 0x28000000)

        val searchView = view.findViewById<AutoCompleteTextView>(R.id.animeSearchBarText)
        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchQuery(s.toString())
            }
        })

        var style: Int = PrefManager.getVal(PrefName.OfflineView)
        val layoutList = view.findViewById<ImageView>(R.id.downloadedList)
        val layoutCompact = view.findViewById<ImageView>(R.id.downloadedGrid)
        var selected = when (style) {
            0 -> layoutList
            1 -> layoutCompact
            else -> layoutList
        }
        selected.alpha = 1f

        fun selected(it: ImageView) {
            selected.alpha = 0.33f
            selected = it
            selected.alpha = 1f
        }

        layoutList.setOnClickListener {
            selected(it as ImageView)
            style = 0
            PrefManager.setVal(PrefName.OfflineView, style)
            gridView.visibility = View.GONE
            gridView = view.findViewById(R.id.gridView)
            adapter.notifyNewGrid()
            grid()
        }

        layoutCompact.setOnClickListener {
            selected(it as ImageView)
            style = 1
            PrefManager.setVal(PrefName.OfflineView, style)
            gridView.visibility = View.GONE
            gridView = view.findViewById(R.id.gridView1)
            adapter.notifyNewGrid()
            grid()
        }

        gridView =
            if (style == 0) view.findViewById(R.id.gridView) else view.findViewById(R.id.gridView1)
        total = view.findViewById(R.id.total)
        noAnimeText = view.findViewById(R.id.noMangaOffline)

        // Anime/Manga/Novel tabs
        val titleContainer = view.findViewById<android.widget.LinearLayout>(R.id.animeTitleContainer)
        val parentLayout = titleContainer.parent as android.widget.LinearLayout
        val titleIndex = parentLayout.indexOfChild(titleContainer)

        val tabLayout = com.google.android.material.tabs.TabLayout(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * resources.displayMetrics.density).toInt()
            ).apply {
                setMargins(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            tabGravity = com.google.android.material.tabs.TabLayout.GRAVITY_FILL
            
            
            setTabTextColors(
                requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
                requireContext().getThemeColor(com.google.android.material.R.attr.colorPrimary)
            )
            setSelectedTabIndicatorColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorPrimary))
        }

        val animeTab = tabLayout.newTab().setText("ANIME")
        val mangaTab = tabLayout.newTab().setText("MANGA")
        val novelTab = tabLayout.newTab().setText("NOVEL")

        tabLayout.addTab(animeTab, currentMode == LocalMediaType.ANIME)
        tabLayout.addTab(mangaTab, currentMode == LocalMediaType.MANGA)
        tabLayout.addTab(novelTab, currentMode == LocalMediaType.NOVEL)

        parentLayout.addView(tabLayout, 0)

        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                if (tab == animeTab && currentMode != LocalMediaType.ANIME) {
                    currentMode = LocalMediaType.ANIME
                    textInputLayout.hint = "Local Anime"
                    scanLocal()
                } else if (tab == mangaTab && currentMode != LocalMediaType.MANGA) {
                    currentMode = LocalMediaType.MANGA
                    textInputLayout.hint = "Local Manga"
                    scanLocal()
                } else if (tab == novelTab && currentMode != LocalMediaType.NOVEL) {
                    currentMode = LocalMediaType.NOVEL
                    textInputLayout.hint = "Local Novel"
                    scanLocal()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        val swipeRefresh = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.localRefresh)
        swipeRefresh.setOnRefreshListener {
            animeDownloadsCache = null
            mangaDownloadsCache = null
            novelDownloadsCache = null
            scanLocal()
            swipeRefresh.isRefreshing = false
        }

        grid()
        return view
    }

    private fun grid() {
        gridView.visibility = View.VISIBLE
        val fadeIn = AlphaAnimation(0f, 1f)
        fadeIn.duration = 300
        gridView.layoutAnimation = LayoutAnimationController(fadeIn)
        adapter = OfflineAnimeAdapter(requireContext(), downloads, this)
        scanLocal()
        gridView.adapter = adapter
        gridView.scheduleLayoutAnimation()
        gridView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as OfflineAnimeModel
            lifecycleScope.launch {
                val media = when (currentMode) {
                    LocalMediaType.ANIME -> createMediaFromLocalAnime(item)
                    LocalMediaType.MANGA -> createMediaFromLocalManga(item)
                    LocalMediaType.NOVEL -> createMediaFromLocalNovel(item)
                }
                MediaDetailsActivity.mediaSingleton = media
                ContextCompat.startActivity(
                    requireActivity(),
                    Intent(requireContext(), MediaDetailsActivity::class.java)
                        .putExtra("download", true),
                    null
                )
            }
        }
    }

    override fun onSearchQuery(query: String) {
        adapter.onSearchQuery(query)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scrollTop = view.findViewById<CardView>(R.id.mangaPageScrollTop)
        scrollTop.setOnClickListener {
            gridView.smoothScrollToPositionFromTop(0, 0)
        }

        scrollTop.visibility = View.GONE

        gridView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                val first = view.getChildAt(0)
                val visibility = first != null && first.top < 0
                scrollTop.translationY =
                    -(navBarHeight + bottomBar.height + bottomBar.marginBottom).toFloat()
                scrollTop.isVisible = visibility
            }
        })
        initActivity(requireActivity())
    }

    override fun onResume() {
        super.onResume()
        scanLocal()
    }

    override fun onPause() {
        super.onPause()
        downloads = listOf()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloads = listOf()
    }

    override fun onStop() {
        super.onStop()
        downloads = listOf()
    }

    private fun updateEmptyState() {
        when (currentMode) {
            LocalMediaType.ANIME -> {
                val dirSet = PrefManager.getVal<String>(PrefName.LocalDir).isNotBlank()
                if (!dirSet) {
                    noAnimeText.text = "Tap the folder icon to select your local anime folder"
                    noAnimeText.visibility = View.VISIBLE
                    total.text = "Local Anime"
                } else if (downloads.isEmpty()) {
                    noAnimeText.text = "No local anime found in selected folder"
                    noAnimeText.visibility = View.VISIBLE
                    total.text = "Local Anime (0)"
                } else {
                    noAnimeText.visibility = View.GONE
                    total.text = "Local Anime (${downloads.size})"
                }
            }
            LocalMediaType.MANGA -> {
                val dirSet = PrefManager.getVal<String>(PrefName.LocalDir).isNotBlank()
                if (!dirSet) {
                    noAnimeText.text = "Tap the folder icon to select your local manga folder"
                    noAnimeText.visibility = View.VISIBLE
                    total.text = "Local Manga"
                } else if (downloads.isEmpty()) {
                    noAnimeText.text = "No local manga found in selected folder"
                    noAnimeText.visibility = View.VISIBLE
                    total.text = "Local Manga (0)"
                } else {
                    noAnimeText.visibility = View.GONE
                    total.text = "Local Manga (${downloads.size})"
                }
            }
            LocalMediaType.NOVEL -> {
                val dirSet = PrefManager.getVal<String>(PrefName.LocalDir).isNotBlank()
                if (!dirSet) {
                    noAnimeText.text = "Tap the folder icon to select your local novel folder"
                    noAnimeText.visibility = View.VISIBLE
                    total.text = "Local Novel"
                } else if (downloads.isEmpty()) {
                    noAnimeText.text = "No local novels found in selected folder"
                    noAnimeText.visibility = View.VISIBLE
                    total.text = "Local Novel (0)"
                } else {
                    noAnimeText.visibility = View.GONE
                    total.text = "Local Novel (${downloads.size})"
                }
            }
        }
    }

    private fun scanLocal() {
        when (currentMode) {
            LocalMediaType.ANIME -> scanLocalAnime()
            LocalMediaType.MANGA -> scanLocalManga()
            LocalMediaType.NOVEL -> scanLocalNovel()
        }
    }

    private fun scanLocalAnime() {
        if (animeDownloadsCache != null) {
            downloads = animeDownloadsCache!!
            adapter.setItems(downloads)
            updateEmptyState()
            return
        }

        downloads = listOf()
        adapter.setItems(downloads)
        noAnimeText.visibility = View.GONE
        total.text = "Local Anime (Scanning...)"
        if (scanJob.isActive) {
            scanJob.cancel()
        }
        scanJob = Job()
        CoroutineScope(Dispatchers.IO + scanJob).launch {
            try {
                val animesPage = localAnimeSource.getPopularAnime(1)
                val newDownloads = animesPage.animes.map { sAnime ->
                    val details = localAnimeSource.getAnimeDetails(sAnime)
                    val episodes = localAnimeSource.getEpisodeList(sAnime)

                    val title = details.title
                    val totalEps = episodes.size.toString()

                    // al cover cache
                    val cachedCover = ani.dantotsu.settings.saving.PrefManager
                        .getCustomVal<String>("local_cover_${title}", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }
                    val cachedBanner = ani.dantotsu.settings.saving.PrefManager
                        .getCustomVal<String>("local_banner_${title}", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }

                    // AniList URL else fallback 
                    val coverUri = if (cachedCover != null) {
                        Uri.parse(cachedCover)
                    } else {
                        details.thumbnail_url?.let { Uri.parse(it) }
                    }
                    val bannerUri = if (cachedBanner != null) {
                        Uri.parse(cachedBanner)
                    } else {
                        null
                    }

                    OfflineAnimeModel(
                        title = title,
                        folderName = sAnime.url ?: title,
                        score = "0",
                        totalEpisode = totalEps,
                        totalEpisodeList = totalEps,
                        watchedEpisode = "~",
                        type = "local",
                        episodes = " Episodes",
                        isOngoing = false,
                        isUserScored = false,
                        image = coverUri,
                        banner = bannerUri,
                        description = details.description,
                        genres = details.genre,
                        status = when (details.status) {
                            SAnime.ONGOING -> "RELEASING"
                            SAnime.COMPLETED -> "FINISHED"
                            SAnime.CANCELLED -> "CANCELLED"
                            SAnime.ON_HIATUS -> "HIATUS"
                            else -> "UNKNOWN"
                        },
                        author = details.author ?: details.artist
                    )
                }
                downloads = newDownloads
                animeDownloadsCache = newDownloads
                withContext(Dispatchers.Main) {
                    adapter.setItems(downloads)
                    updateEmptyState()
                }
            } catch (e: Exception) {
                Logger.log("Error scanning local anime: ${e.message}")
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    updateEmptyState()
                }
            }
        }
    }

    private fun scanLocalManga() {
        if (mangaDownloadsCache != null) {
            downloads = mangaDownloadsCache!!
            adapter.setItems(downloads)
            updateEmptyState()
            return
        }

        downloads = listOf()
        adapter.setItems(downloads)
        noAnimeText.visibility = View.GONE
        total.text = "Local Manga (Scanning...)"
        if (scanJob.isActive) {
            scanJob.cancel()
        }
        scanJob = Job()
        CoroutineScope(Dispatchers.IO + scanJob).launch {
            try {
                val mangasPage = localMangaSource.getPopularManga(1)
                val newDownloads = mangasPage.mangas.map { sManga ->
                    val details = localMangaSource.getMangaDetails(sManga)
                    val chapters = localMangaSource.getChapterList(sManga)

                    val title = details.title
                    val totalChaps = chapters.size.toString()

                    // al covers cache
                    val cachedCover = ani.dantotsu.settings.saving.PrefManager
                        .getCustomVal<String>("local_cover_${title}", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }
                    val cachedBanner = ani.dantotsu.settings.saving.PrefManager
                        .getCustomVal<String>("local_banner_${title}", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }

                    val coverUri = if (cachedCover != null) {
                        Uri.parse(cachedCover)
                    } else {
                        details.thumbnail_url?.let { Uri.parse(it) }
                    }
                    val bannerUri = if (cachedBanner != null) {
                        Uri.parse(cachedBanner)
                    } else {
                        null
                    }

                    OfflineAnimeModel(
                        title = title,
                        folderName = sManga.url ?: title,
                        score = "0",
                        totalEpisode = totalChaps,
                        totalEpisodeList = totalChaps,
                        watchedEpisode = "~",
                        type = "local",
                        episodes = " Chapters",
                        isOngoing = false,
                        isUserScored = false,
                        image = coverUri,
                        banner = bannerUri,
                        description = details.description,
                        genres = details.genre,
                        status = when (details.status) {
                            SManga.ONGOING -> "RELEASING"
                            SManga.COMPLETED -> "FINISHED"
                            SManga.CANCELLED -> "CANCELLED"
                            SManga.ON_HIATUS -> "HIATUS"
                            else -> "UNKNOWN"
                        },
                        author = details.author ?: details.artist
                    )
                }
                downloads = newDownloads
                mangaDownloadsCache = newDownloads
                withContext(Dispatchers.Main) {
                    adapter.setItems(downloads)
                    updateEmptyState()
                }
            } catch (e: Exception) {
                Logger.log("Error scanning local manga: ${e.message}")
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    updateEmptyState()
                }
            }
        }
    }

    private fun createMediaFromLocalAnime(item: OfflineAnimeModel): Media {
        val localSourceIndex = AnimeSources.list.indexOfFirst { it.name == "Local" }
            .takeIf { it >= 0 } ?: 0

        return Media(
            id = 0,
            name = item.title,
            nameRomaji = item.title,
            userPreferredName = item.title,
            isAdult = false,
            description = item.description,
            status = item.status ?: "UNKNOWN",
            genres = item.genres?.split(",")?.map { it.trim() } as? ArrayList<String> ?: arrayListOf(),
            anime = ani.dantotsu.media.anime.Anime(
                totalEpisodes = item.totalEpisode.toIntOrNull()
            ),
        ).also {
            it.format = "LOCAL"
            it.folderName = item.folderName
            it.cover = ani.dantotsu.settings.saving.PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.image?.toString()
            it.banner = ani.dantotsu.settings.saving.PrefManager.getCustomVal<String>("local_banner_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.banner?.toString()
            it.selected = Selected(
                sourceIndex = localSourceIndex,
                server = "Local"
            )
        }
    }

    private fun createMediaFromLocalManga(item: OfflineAnimeModel): Media {
        val localSourceIndex = MangaSources.list.indexOfFirst { it.name == "Local" }
            .takeIf { it >= 0 } ?: 0

        return Media(
            id = 0,
            name = item.title,
            nameRomaji = item.title,
            userPreferredName = item.title,
            isAdult = false,
            description = item.description,
            status = item.status ?: "UNKNOWN",
            genres = item.genres?.split(",")?.map { it.trim() } as? ArrayList<String> ?: arrayListOf(),
            manga = ani.dantotsu.media.manga.Manga(
                totalChapters = item.totalEpisode.toIntOrNull()
            ),
        ).also {
            it.format = "LOCAL"
            it.folderName = item.folderName
            it.cover = ani.dantotsu.settings.saving.PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.image?.toString()
            it.banner = ani.dantotsu.settings.saving.PrefManager.getCustomVal<String>("local_banner_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.banner?.toString()
            it.selected = Selected(
                sourceIndex = localSourceIndex,
                server = "Local"
            )
        }
    }

    private fun scanLocalNovel() {
        if (novelDownloadsCache != null) {
            downloads = novelDownloadsCache!!
            adapter.setItems(downloads)
            updateEmptyState()
            return
        }

        downloads = listOf()
        adapter.setItems(downloads)
        noAnimeText.visibility = View.GONE
        total.text = "Local Novel (Scanning...)"
        if (scanJob.isActive) {
            scanJob.cancel()
        }
        scanJob = Job()
        CoroutineScope(Dispatchers.IO + scanJob).launch {
            try {
                val mangasPage = localNovelSource.getPopularManga(1)
                val newDownloads = mangasPage.mangas.map { sManga ->
                    val details = localNovelSource.getMangaDetails(sManga)
                    val chapters = localNovelSource.getChapterList(sManga)

                    val title = details.title
                    val totalChaps = chapters.size.toString()

                    val cachedCover = ani.dantotsu.settings.saving.PrefManager
                        .getCustomVal<String>("local_cover_${title}", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }
                    val cachedBanner = ani.dantotsu.settings.saving.PrefManager
                        .getCustomVal<String>("local_banner_${title}", "")
                        .takeIf { s -> s.isNotEmpty() && s != "null" }

                    val coverUri = if (cachedCover != null) {
                        Uri.parse(cachedCover)
                    } else {
                        details.thumbnail_url?.let { Uri.parse(it) }
                    }
                    val bannerUri = if (cachedBanner != null) {
                        Uri.parse(cachedBanner)
                    } else {
                        null
                    }

                    OfflineAnimeModel(
                        title = title,
                        folderName = sManga.url ?: title,
                        score = "0",
                        totalEpisode = totalChaps,
                        totalEpisodeList = totalChaps,
                        watchedEpisode = "~",
                        type = "local",
                        episodes = " Volumes",
                        isOngoing = false,
                        isUserScored = false,
                        image = coverUri,
                        banner = bannerUri,
                        description = details.description,
                        genres = details.genre,
                        status = when (details.status) {
                            SManga.ONGOING -> "RELEASING"
                            SManga.COMPLETED -> "FINISHED"
                            SManga.CANCELLED -> "CANCELLED"
                            SManga.ON_HIATUS -> "HIATUS"
                            else -> "UNKNOWN"
                        },
                        author = details.author ?: details.artist
                    )
                }
                downloads = newDownloads
                novelDownloadsCache = newDownloads
                withContext(Dispatchers.Main) {
                    adapter.setItems(downloads)
                    updateEmptyState()
                }
            } catch (e: Exception) {
                Logger.log("Error scanning local novel: ${e.message}")
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    updateEmptyState()
                }
            }
        }
    }

    private fun createMediaFromLocalNovel(item: OfflineAnimeModel): Media {
        val localSourceIndex = ani.dantotsu.parsers.NovelSources.list.indexOfFirst { it.name == "Local" }
            .takeIf { it >= 0 } ?: 0

        return Media(
            id = 0,
            name = item.title,
            nameRomaji = item.title,
            userPreferredName = item.title,
            isAdult = false,
            description = item.description,
            status = item.status ?: "UNKNOWN",
            genres = item.genres?.split(",")?.map { it.trim() } as? ArrayList<String> ?: arrayListOf(),
            anime = null,
            manga = ani.dantotsu.media.manga.Manga(
                totalChapters = item.totalEpisode.toIntOrNull()
            )
        ).also {
            it.format = "LOCAL_NOVEL" 
            it.folderName = item.folderName
            it.cover = ani.dantotsu.settings.saving.PrefManager.getCustomVal<String>("local_cover_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.image?.toString()
            it.banner = ani.dantotsu.settings.saving.PrefManager.getCustomVal<String>("local_banner_${item.title}", "")
                .takeIf { s -> s.isNotEmpty() && s != "null" } ?: item.banner?.toString()
            it.selected = Selected(
                sourceIndex = localSourceIndex,
                server = "Local"
            )
        }
    }

    companion object {
       // cache to memory 
        var animeDownloadsCache: List<OfflineAnimeModel>? = null
        var mangaDownloadsCache: List<OfflineAnimeModel>? = null
        var novelDownloadsCache: List<OfflineAnimeModel>? = null
    }
}
