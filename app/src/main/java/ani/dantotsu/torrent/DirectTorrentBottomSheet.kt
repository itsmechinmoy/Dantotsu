package ani.dantotsu.torrent

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.BottomSheetDirectTorrentBinding
import ani.dantotsu.databinding.ItemTorrentFileBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.Selected
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.torrentServer.model.FileStat
import eu.kanade.tachiyomi.data.torrentServer.model.Torrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import kotlin.math.abs

class DirectTorrentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDirectTorrentBinding? = null
    private val binding get() = _binding!!

    private var initialUrl: String? = null
    private var defaultLinkedMedia: Media? = null
    private val folderLinkedMediaMap = mutableMapOf<String, Media>()
    private var currentSelectedFolderKey: String = "__ALL__"

    private var loadedTorrent: Torrent? = null
    private val folderFilesMap = mutableMapOf<String, MutableList<FileStat>>()
    private val currentFileList = mutableListOf<FileStat>()
    private var torrentAdapter: TorrentFileAdapter? = null
    private var searchJob: Job? = null
    var onTorrentSelected: ((String) -> Unit)? = null

    // Activity launcher for local .torrent file picking
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        handleTorrentFileUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = arguments?.getString(ARG_URL)

        // Restore pre-linked Media if passed
        val mId = arguments?.getInt(ARG_MEDIA_ID, -1) ?: -1
        if (mId != -1) {
            val title = arguments?.getString(ARG_MEDIA_TITLE) ?: ""
            val cover = arguments?.getString(ARG_MEDIA_COVER) ?: ""
            val malId = arguments?.getInt(ARG_MEDIA_ID_MAL, -1)
            val format = arguments?.getString(ARG_MEDIA_FORMAT)
            val eps = arguments?.getInt(ARG_MEDIA_EPISODES, 0)
            defaultLinkedMedia = Media(
                id = mId,
                idMAL = if (malId != null && malId != -1) malId else null,
                name = title,
                nameRomaji = title,
                userPreferredName = title,
                cover = cover,
                banner = cover,
                format = format,
                isAdult = false,
                anime = Anime(totalEpisodes = if (eps != null && eps > 0) eps else null)
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDirectTorrentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup File RecyclerView
        torrentAdapter = TorrentFileAdapter(currentFileList) { clickedStat ->
            onFileClicked(clickedStat)
        }
        binding.torrentFilesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.torrentFilesRecyclerView.adapter = torrentAdapter

        // Setup Linked Media UI
        updateLinkedMediaUi()

        // Link AniList Anime button (supports global or per-season linking)
        binding.torrentLinkAnilistButton.setOnClickListener {
            val selectedFolder = currentSelectedFolderKey
            val isSpecificFolder = selectedFolder != "__ALL__" && selectedFolder.isNotBlank()

            if (isSpecificFolder) {
                if (folderLinkedMediaMap.containsKey(selectedFolder)) {
                    folderLinkedMediaMap.remove(selectedFolder)
                    updateLinkedMediaUi()
                    toast(getString(R.string.torrent_unlinked))
                } else {
                    showAniListSearchDialog(selectedFolder)
                }
            } else {
                if (defaultLinkedMedia != null || folderLinkedMediaMap.isNotEmpty()) {
                    defaultLinkedMedia = null
                    folderLinkedMediaMap.clear()
                    updateLinkedMediaUi()
                    toast(getString(R.string.torrent_unlinked))
                } else {
                    showAniListSearchDialog("__ALL__")
                }
            }
        }

        // Load Button
        binding.torrentLoadButton.setOnClickListener {
            val text = binding.torrentInputEditText.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                if (onTorrentSelected != null) {
                    onTorrentSelected?.invoke(text)
                    dismiss()
                    return@setOnClickListener
                }
                loadTorrent(text)
            } else {
                toast(getString(R.string.torrent_enter_url_or_magnet))
            }
        }

        // File Picker Button
        binding.torrentFilePickerButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/x-bittorrent", "application/octet-stream")
                )
            }
            filePickerLauncher.launch(intent)
        }

        // IME Done
        binding.torrentInputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.torrentLoadButton.performClick()
                true
            } else false
        }

        // Auto-load if initial URL was supplied, otherwise check clipboard
        if (!initialUrl.isNullOrBlank()) {
            binding.torrentInputEditText.setText(initialUrl)
            loadTorrent(initialUrl!!)
        } else {
            checkClipboardForTorrent()
        }
    }

    // ── Clipboard Detection ────────────────────────────────────────────────

    private fun checkClipboardForTorrent() {
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
            if (text.startsWith("magnet:?xt=", ignoreCase = true) ||
                (text.startsWith("http", ignoreCase = true) && text.contains(".torrent", ignoreCase = true))
            ) {
                binding.torrentInputEditText.setText(text)
                snackString(getString(R.string.torrent_pasted_clipboard))
            }
        }
    }

    // ── AniList Link Header UI ─────────────────────────────────────────────

    private fun updateLinkedMediaUi() {
        val b = _binding ?: return
        val selectedFolder = currentSelectedFolderKey
        val isSpecificFolder = selectedFolder != "__ALL__" && selectedFolder.isNotBlank()

        val folderMedia = if (isSpecificFolder) folderLinkedMediaMap[selectedFolder] else null

        if (isSpecificFolder) {
            if (folderMedia != null) {
                b.torrentLinkedMediaCover.isVisible = true
                b.torrentLinkedMediaCover.loadImage(folderMedia.cover ?: folderMedia.banner ?: "")
                b.torrentLinkedMediaTitle.text = folderMedia.userPreferredName
                b.torrentLinkedMediaSubtitle.isVisible = true
                val epCount = folderMedia.anime?.totalEpisodes?.let { "$it eps" } ?: ""
                val fmt = folderMedia.format ?: ""
                b.torrentLinkedMediaSubtitle.text = listOf(selectedFolder, fmt, epCount).filter { it.isNotBlank() }.joinToString(" • ")
                b.torrentLinkAnilistButton.text = getString(R.string.torrent_unlink_season_button)
            } else if (defaultLinkedMedia != null) {
                b.torrentLinkedMediaCover.isVisible = true
                b.torrentLinkedMediaCover.loadImage(defaultLinkedMedia!!.cover ?: defaultLinkedMedia!!.banner ?: "")
                b.torrentLinkedMediaTitle.text = "${defaultLinkedMedia!!.userPreferredName} (Global)"
                b.torrentLinkedMediaSubtitle.isVisible = true
                b.torrentLinkedMediaSubtitle.text = "$selectedFolder • Click Link to set specific season"
                b.torrentLinkAnilistButton.text = getString(R.string.torrent_link_season_button)
            } else {
                b.torrentLinkedMediaCover.isVisible = false
                b.torrentLinkedMediaTitle.text = getString(R.string.torrent_link_season_hint, selectedFolder)
                b.torrentLinkedMediaSubtitle.isVisible = false
                b.torrentLinkAnilistButton.text = getString(R.string.torrent_link_season_button)
            }
        } else {
            // __ALL__ selected
            if (defaultLinkedMedia != null) {
                b.torrentLinkedMediaCover.isVisible = true
                b.torrentLinkedMediaCover.loadImage(defaultLinkedMedia!!.cover ?: defaultLinkedMedia!!.banner ?: "")
                b.torrentLinkedMediaTitle.text = defaultLinkedMedia!!.userPreferredName
                b.torrentLinkedMediaSubtitle.isVisible = true
                val epCount = defaultLinkedMedia!!.anime?.totalEpisodes?.let { "$it eps" } ?: ""
                val fmt = defaultLinkedMedia!!.format ?: ""
                val seasonOverrides = if (folderLinkedMediaMap.isNotEmpty()) " (${folderLinkedMediaMap.size} seasons configured)" else ""
                b.torrentLinkedMediaSubtitle.text = listOf(fmt, epCount).filter { it.isNotBlank() }.joinToString(" • ") + seasonOverrides
                b.torrentLinkAnilistButton.text = getString(R.string.torrent_unlink_all_button)
            } else if (folderLinkedMediaMap.isNotEmpty()) {
                b.torrentLinkedMediaCover.isVisible = false
                b.torrentLinkedMediaTitle.text = getString(R.string.torrent_seasons_linked, folderLinkedMediaMap.size)
                b.torrentLinkedMediaSubtitle.isVisible = true
                b.torrentLinkedMediaSubtitle.text = getString(R.string.torrent_select_season_to_link)
                b.torrentLinkAnilistButton.text = getString(R.string.torrent_unlink_all_button)
            } else {
                b.torrentLinkedMediaCover.isVisible = false
                b.torrentLinkedMediaTitle.text = getString(R.string.torrent_link_anilist_hint)
                b.torrentLinkedMediaSubtitle.isVisible = false
                b.torrentLinkAnilistButton.text = getString(R.string.torrent_link_button)
            }
        }
    }

    // ── Search & Link AniList Dialog ───────────────────────────────────────

    private fun showAniListSearchDialog(targetFolder: String = "__ALL__") {
        val ctx = requireContext()
        val searchResults = mutableListOf<Media>()

        val dialogView = buildSearchDialogView(ctx) { query, onResult ->
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                delay(300)
                val results = searchAniManga(query)
                withContext(Dispatchers.Main) {
                    searchResults.clear()
                    searchResults.addAll(results)
                    onResult(results)
                }
            }
        }

        var dialog: android.app.AlertDialog? = null
        val titleText = if (targetFolder != "__ALL__" && targetFolder.isNotBlank()) {
            getString(R.string.torrent_link_search_title) + " ($targetFolder)"
        } else {
            getString(R.string.torrent_link_search_title)
        }
        ctx.customAlertDialog().apply {
            setTitle(titleText)
            setCustomView(dialogView)
            setNegButton(R.string.cancel)
            attach { dialog = it }
        }.show()

        val list = dialogView.tag as? android.widget.ListView
        list?.setOnItemClickListener { _, _, position, _ ->
            val chosen = searchResults.getOrNull(position)
            if (chosen != null) {
                if (targetFolder == "__ALL__") {
                    defaultLinkedMedia = chosen
                    updateLinkedMediaUi()
                    toast(getString(R.string.torrent_linked_to, chosen.userPreferredName))
                } else {
                    folderLinkedMediaMap[targetFolder] = chosen
                    updateLinkedMediaUi()
                    toast(getString(R.string.torrent_linked_to, "${chosen.userPreferredName} ($targetFolder)"))
                }
            }
            dialog?.dismiss()
        }
    }

    private suspend fun searchAniManga(query: String): List<Media> {
        return try {
            val response = Anilist.query.searchAniManga(
                type = "ANIME",
                search = query
            )
            response?.results ?: emptyList()
        } catch (e: Exception) {
            Logger.log("AniList search error: ${e.message}")
            emptyList()
        }
    }

    private fun buildSearchDialogView(
        ctx: Context,
        onQuery: (String, (List<Media>) -> Unit) -> Unit
    ): View {
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
        }

        val searchEdit = android.widget.EditText(ctx).apply {
            hint = getString(R.string.torrent_link_search_hint)
            setSingleLine(true)
        }
        container.addView(searchEdit)

        val resultAdapter = android.widget.ArrayAdapter<String>(
            ctx,
            android.R.layout.simple_list_item_1
        )
        val resultsList = android.widget.ListView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size) * 6
            )
            adapter = resultAdapter
        }
        container.addView(resultsList)
        container.tag = resultsList

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: return
                if (q.length < 2) return
                onQuery(q) { results ->
                    resultAdapter.clear()
                    if (results.isEmpty()) {
                        resultAdapter.add(getString(R.string.torrent_no_results))
                    } else {
                        results.forEach { m ->
                            val eps = m.anime?.totalEpisodes?.let { " (${it} eps)" } ?: ""
                            resultAdapter.add("${m.userPreferredName}$eps")
                        }
                    }
                    resultAdapter.notifyDataSetChanged()
                }
            }
        })

        return container
    }

    // ── .torrent File Handling ────────────────────────────────────────────

    private fun handleTorrentFileUri(uri: Uri) {
        val ctx = context ?: return
        try {
            val tmpFile = java.io.File(ctx.cacheDir, "tmp_picked.torrent")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            val fileUrl = "file://${tmpFile.absolutePath}"
            binding.torrentInputEditText.setText(fileUrl)
            if (onTorrentSelected != null) {
                onTorrentSelected?.invoke(fileUrl)
                dismiss()
                return
            }
            loadTorrent(fileUrl)
        } catch (e: Exception) {
            Logger.log("File pick error: ${e.message}")
            toast("Could not read .torrent file: ${e.message}")
        }
    }

    // ── Torrent Loading ────────────────────────────────────────────────────

    private fun loadTorrent(url: String) {
        val torrentManager = Injekt.get<TorrentServerManager>()
        val b = _binding ?: return
        b.torrentProgressBar.isVisible = true
        b.torrentStatusText.isVisible = true
        b.torrentStatusText.text = getString(R.string.loading)
        b.torrentInfoContainer.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                ani.dantotsu.addons.torrent.TorrentServerService.start()
                torrentManager.start()
                val torrent = torrentManager.addTorrent(
                    url = url,
                    title = "Torrent Stream",
                    poster = "",
                    data = "",
                    save = false
                )
                loadedTorrent = torrent
                parseTorrentFolders(torrent)

                withContext(Dispatchers.Main) {
                    val bm = _binding ?: return@withContext
                    bm.torrentProgressBar.isVisible = false
                    bm.torrentStatusText.isVisible = false
                    bm.torrentInfoContainer.isVisible = true
                    bm.torrentNameText.text = torrent.name ?: torrent.title ?: "Torrent Stream"
                    bm.torrentSizeText.text =
                        "Total Size: ${formatFileSize(torrent.torrent_size ?: 0L)} • ${torrent.file_stats?.size ?: 0} Files"

                    setupFolderDropdown()
                }
            } catch (e: Exception) {
                Logger.log("DirectTorrentBottomSheet error: ${e.message}")
                withContext(Dispatchers.Main) {
                    val bm = _binding ?: return@withContext
                    bm.torrentProgressBar.isVisible = false
                    bm.torrentStatusText.isVisible = true
                    bm.torrentStatusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun parseTorrentFolders(torrent: Torrent) {
        folderFilesMap.clear()
        val stats = torrent.file_stats ?: return

        val videoExtensions = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".m4v", ".ts")
        val videoFiles = stats.filter { stat ->
            val p = stat.path.lowercase(Locale.ROOT)
            videoExtensions.any { p.endsWith(it) }
        }.ifEmpty { stats }

        val relativePaths = ani.dantotsu.parsers.TorrentAnimeParser.stripCommonRootDirectory(videoFiles.map { it.path })

        videoFiles.forEachIndexed { index, stat ->
            val relPath = relativePaths.getOrElse(index) { stat.path }
            val norm = relPath.replace('\\', '/')
            val folder = if (norm.contains('/')) norm.substringBeforeLast('/') else ""
            folderFilesMap.getOrPut(folder) { mutableListOf() }.add(stat)
        }
    }

    private fun setupFolderDropdown() {
        val b = _binding ?: return
        val folderKeys = folderFilesMap.keys.toList()

        if (folderKeys.size > 1) {
            b.torrentFolderDropdownContainer.visibility = View.VISIBLE

            val hasSeasonKeyword = folderKeys.any {
                it.contains("season", ignoreCase = true) ||
                it.contains("series", ignoreCase = true) ||
                it.matches(Regex(".*[sS]\\d+.*"))
            }

            val dropdownHint = if (hasSeasonKeyword) getString(R.string.season) else getString(R.string.torrent_folder_hint)
            b.torrentFolderDropdownContainer.hint = dropdownHint

            val allText = if (hasSeasonKeyword) getString(R.string.all_seasons) else getString(R.string.torrent_folder_all)
            val dropdownLabels = mutableListOf<String>()
            val dropdownKeys = mutableListOf<String>()

            // Option 0: All Folders / All Seasons
            val totalCount = folderFilesMap.values.sumOf { it.size }
            dropdownLabels.add("$allText ($totalCount files)")
            dropdownKeys.add("__ALL__")

            folderKeys.sorted().forEach { folder ->
                val files = folderFilesMap[folder] ?: emptyList()
                val label = if (folder.isBlank()) "Root (${files.size} files)" else "$folder (${files.size} files)"
                dropdownLabels.add(label)
                dropdownKeys.add(folder)
            }

            b.torrentFolderDropdown.setText(dropdownLabels[0], false)
            val dropdownAdapter = ArrayAdapter(
                requireContext(),
                R.layout.item_dropdown,
                dropdownLabels
            )
            b.torrentFolderDropdown.setAdapter(dropdownAdapter)

            b.torrentFolderDropdown.setOnItemClickListener { _, _, position, _ ->
                val selectedKey = dropdownKeys.getOrNull(position) ?: "__ALL__"
                currentSelectedFolderKey = selectedKey
                displayFilesForFolder(selectedKey)
                updateLinkedMediaUi()
            }

            // Default display: first option (All files or First season)
            currentSelectedFolderKey = "__ALL__"
            displayFilesForFolder("__ALL__")
            updateLinkedMediaUi()
        } else {
            b.torrentFolderDropdownContainer.visibility = View.GONE
            val singleKey = folderKeys.firstOrNull() ?: ""
            currentSelectedFolderKey = singleKey
            displayFilesForFolder(singleKey)
            updateLinkedMediaUi()
        }
    }

    private fun displayFilesForFolder(folderKey: String) {
        currentFileList.clear()
        if (folderKey == "__ALL__") {
            folderFilesMap.keys.sorted().forEach { k ->
                folderFilesMap[k]?.let { currentFileList.addAll(it.sortedBy { f -> f.path }) }
            }
        } else {
            folderFilesMap[folderKey]?.let { currentFileList.addAll(it.sortedBy { f -> f.path }) }
        }
        torrentAdapter?.notifyDataSetChanged()
    }

    // ── File Clicked: build episode map and launch ExoPlayer ───────────────

    @SuppressLint("UnsafeOptInUsageError")
    private fun onFileClicked(clickedFileStat: FileStat) {
        val torrent = loadedTorrent ?: return
        val torrentManager = Injekt.get<TorrentServerManager>()
        val currentAct = activity ?: currActivity() ?: return

        val cleanTitle = clickedFileStat.path.replace('\\', '/').substringAfterLast('/')
        val b = _binding ?: return
        b.torrentProgressBar.isVisible = true
        b.torrentStatusText.isVisible = true
        b.torrentStatusText.text = "Pre-buffering $cleanTitle..."

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                torrentManager.activeTorrentHash = torrent.hash
                val clickedFileId = clickedFileStat.id ?: 0
                torrentManager.prebuffer(torrent.hash ?: "", clickedFileId)

                val stats = torrent.file_stats ?: emptyList()
                val videoExtensions = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".m4v", ".ts")
                val videoFilesList = stats.filter { stat ->
                    val p = stat.path.lowercase()
                    videoExtensions.any { p.endsWith(it) }
                }.ifEmpty { stats }

                val relativePaths = ani.dantotsu.parsers.TorrentAnimeParser.stripCommonRootDirectory(videoFilesList.map { it.path })
                val clickedIdx = videoFilesList.indexOf(clickedFileStat).coerceAtLeast(0)
                val clickedRelPath = relativePaths.getOrElse(clickedIdx) { clickedFileStat.path }
                val norm = clickedRelPath.replace('\\', '/')
                val clickedFolder = if (norm.contains('/')) norm.substringBeforeLast('/') else ""

                // Scoped files: if clicked inside a multi-season folder, scope the episode playlist to that season
                val scopedFiles = if (clickedFolder.isNotBlank() && folderFilesMap.containsKey(clickedFolder)) {
                    folderFilesMap[clickedFolder] ?: videoFilesList
                } else {
                    videoFilesList
                }

                val allEpisodesMap = mutableMapOf<String, Episode>()
                var clickedEpNumber = "1"

                scopedFiles.forEachIndexed { index, stat ->
                    val fCleanName = stat.path.replace('\\', '/').substringAfterLast('/')
                    val epNum = MediaNameAdapter.findEpisodeNumber(fCleanName)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    } ?: (index + 1).toString()

                    if (stat.id == clickedFileId) clickedEpNumber = epNum

                    val fId = stat.id ?: 0
                    val sUrl = torrentManager.getLink(torrent, fId)
                    val sEp = SEpisode.create().apply {
                        name = fCleanName
                        url = sUrl
                        episode_number = epNum.toFloatOrNull() ?: (index + 1).toFloat()
                        if (clickedFolder.isNotBlank()) {
                            scanlator = clickedFolder
                        } else if (stat.path.replace('\\', '/').contains('/')) {
                            scanlator = stat.path.replace('\\', '/').substringBeforeLast('/')
                        }
                    }
                    val vid = Video(
                        quality = 1080,
                        format = VideoType.CONTAINER,
                        file = FileUrl(sUrl),
                        size = (stat.length / (1024.0 * 1024.0))
                    )
                    val ext = object : VideoExtractor() {
                        override val server = VideoServer(name = "Torrent Stream", embed = FileUrl(sUrl))
                        override suspend fun extract(): VideoContainer = VideoContainer(videos = listOf(vid))
                        init { videos = listOf(vid) }
                    }
                    val ep = Episode(
                        number = epNum,
                        link = sUrl,
                        title = fCleanName,
                        sEpisode = sEp
                    ).apply {
                        extractors = mutableListOf(ext)
                        selectedExtractor = "Torrent Stream"
                        selectedVideo = 0
                    }
                    allEpisodesMap[epNum] = ep
                }

                // If the user linked a real AniList entry (specific season or global), use that Media so
                // updateProgress() in PlayerProgressManager updates progress for the specific season.
                val linked = (if (clickedFolder.isNotBlank()) folderLinkedMediaMap[clickedFolder] else null) ?: defaultLinkedMedia
                val media = if (linked != null) {
                    linked.copy(
                        anime = (linked.anime ?: Anime()).copy(
                            episodes = allEpisodesMap,
                            selectedEpisode = clickedEpNumber
                        )
                    ).also { it.selected = Selected(server = "Torrent") }
                } else {
                    // No link — synthetic ID. AniList/MAL tracking is a no-op.
                    val seasonTitle = if (clickedFolder.isNotBlank()) "${torrent.name ?: torrent.title ?: cleanTitle} - $clickedFolder" else (torrent.name ?: torrent.title ?: cleanTitle)
                    Media(
                        id = kotlin.math.abs((torrent.hash ?: cleanTitle).hashCode() + clickedFolder.hashCode()),
                        name = seasonTitle,
                        nameRomaji = seasonTitle,
                        userPreferredName = seasonTitle,
                        isAdult = false,
                        anime = Anime(
                            episodes = allEpisodesMap,
                            selectedEpisode = clickedEpNumber
                        )
                    ).also { it.selected = Selected(server = "Torrent") }
                }

                withContext(Dispatchers.Main) {
                    val bm = _binding ?: return@withContext
                    bm.torrentProgressBar.isVisible = false
                    bm.torrentStatusText.isVisible = false
                    ExoplayerView.media = media
                    ExoplayerView.initialized = true
                    startActivity(Intent(currentAct, ExoplayerView::class.java))
                    dismissAllowingStateLoss()
                }
            } catch (e: Exception) {
                Logger.log("Failed to start torrent stream: ${e.message}")
                withContext(Dispatchers.Main) {
                    val bm = _binding ?: return@withContext
                    bm.torrentProgressBar.isVisible = false
                    bm.torrentStatusText.isVisible = true
                    bm.torrentStatusText.text = "Error streaming file: ${e.message}"
                    toast("Error streaming file: ${e.message}")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        _binding?.torrentFilesRecyclerView?.adapter = null
        torrentAdapter = null
        _binding = null
    }

    companion object {
        private const val ARG_URL           = "arg_torrent_url"
        private const val ARG_MEDIA_ID      = "arg_media_id"
        private const val ARG_MEDIA_ID_MAL  = "arg_media_id_mal"
        private const val ARG_MEDIA_TITLE   = "arg_media_title"
        private const val ARG_MEDIA_COVER   = "arg_media_cover"
        private const val ARG_MEDIA_FORMAT  = "arg_media_format"
        private const val ARG_MEDIA_EPISODES= "arg_media_episodes"

        /** Open with just a URL (Settings / deep link). No pre-linked media. */
        fun newInstance(url: String? = null): DirectTorrentBottomSheet =
            DirectTorrentBottomSheet().apply {
                arguments = Bundle().apply {
                    if (url != null) putString(ARG_URL, url)
                }
            }

        /**
         * Open pre-linked to a real AniList anime.
         * Progress will sync to AniList + MAL automatically when an episode finishes.
         */
        fun newInstanceLinked(media: Media, url: String? = null): DirectTorrentBottomSheet =
            DirectTorrentBottomSheet().apply {
                arguments = Bundle().apply {
                    if (url != null) putString(ARG_URL, url)
                    putInt(ARG_MEDIA_ID, media.id)
                    media.idMAL?.let { putInt(ARG_MEDIA_ID_MAL, it) }
                    putString(ARG_MEDIA_TITLE, media.userPreferredName)
                    putString(ARG_MEDIA_COVER, media.cover ?: media.banner ?: "")
                    media.format?.let { putString(ARG_MEDIA_FORMAT, it) }
                    media.anime?.totalEpisodes?.let { putInt(ARG_MEDIA_EPISODES, it) }
                }
            }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
            val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0))
                .toInt().coerceIn(0, units.size - 1)
            return String.format(
                Locale.US, "%.1f %s",
                bytes / java.lang.Math.pow(1024.0, digitGroups.toDouble()),
                units[digitGroups]
            )
        }
    }

    // ── RecyclerView Adapter with Episode Cards ────────────────────────────

    class TorrentFileAdapter(
        private val files: List<FileStat>,
        private val onFileClick: (FileStat) -> Unit
    ) : RecyclerView.Adapter<TorrentFileAdapter.FileViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return FileViewHolder(ItemTorrentFileBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            holder.bind(files[position], position, onFileClick)
        }

        override fun getItemCount(): Int = files.size

        class FileViewHolder(private val binding: ItemTorrentFileBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(fileStat: FileStat, position: Int, onClick: (FileStat) -> Unit) {
                val rawName = fileStat.path.replace('\\', '/').substringAfterLast('/')
                val epNum = MediaNameAdapter.findEpisodeNumber(rawName)?.let {
                    if (it % 1 == 0f) String.format(Locale.US, "%02d", it.toInt()) else it.toString()
                } ?: String.format(Locale.US, "%02d", position + 1)

                binding.fileEpisodeBadge.text = epNum
                binding.fileNameText.text = rawName
                binding.fileSizeText.text = formatFileSize(fileStat.length)
                binding.torrentFileCard.setOnClickListener { onClick(fileStat) }
            }
        }
    }
}
