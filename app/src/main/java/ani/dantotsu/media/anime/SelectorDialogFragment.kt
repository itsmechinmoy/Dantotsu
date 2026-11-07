package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.ui.test.performClick
//import androidx.compose.ui.geometry.isEmpty
//import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
//import androidx.glance.visibility
//import androidx.glance.visibility
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withCreated
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.copyToClipboard
import ani.dantotsu.currActivity
import ani.dantotsu.currContext
import ani.dantotsu.databinding.BottomSheetSelectorBinding
import ani.dantotsu.databinding.ItemStreamBinding
import ani.dantotsu.databinding.ItemUrlBinding
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.video.Helper
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Download.download
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SettingsAddonActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.tryWith
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat


class SelectorDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var scope: CoroutineScope = lifecycleScope
    private var media: Media? = null
    private var episode: Episode? = null
    private var prevEpisode: String? = null
    private var makeDefault = false
    private var selected: String? = null
    private var launch: Boolean? = null
    private var isDownloadMenu: Boolean? = null
    private var episodes: ArrayList<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            selected = it.getString("server")
            launch = it.getBoolean("launch", true)
            prevEpisode = it.getString("prev")
            isDownloadMenu = it.getBoolean("isDownload")
            episodes = it.getStringArrayList("episodes")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        return binding.root
    }

    interface EpisodeDownloadListener {
        fun onFinishingUserSelection(selectedServerName: String,
                                     selectedSubtitles: MutableList<String>,
                                     selectedAudioTracks: MutableList<String>)
    }
    class EpisodeDownloadHandler(private val _onFinishingUserSelection: (String, MutableList<String>, MutableList<String>) -> Unit)
        : EpisodeDownloadListener{
        override fun onFinishingUserSelection(selectedServerName: String,
                                              selectedSubtitles: MutableList<String>,
                                              selectedAudioTracks: MutableList<String>) {
            _onFinishingUserSelection(selectedServerName, selectedSubtitles, selectedAudioTracks)
        }
    }

    fun findBestVideoForDownload(
        videos: List<Video>,
        preferredResolutions: List<String>
    ): Video? {
        if (videos.isEmpty()) return null
        val preferredVideo = videos.firstOrNull { it.preferred }
        if (preferredVideo != null) return preferredVideo
        if (preferredResolutions.isEmpty()) return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()

        for (preferred in preferredResolutions) {
            val resNumber = Regex("""\d+""").find(preferred)?.value?.toIntOrNull()
            if (resNumber != null) {
                val match = videos.firstOrNull { it.quality == resNumber }
                if (match != null) return match
            }
            val cleanPreferred = preferred.lowercase().replace("p", "").trim()
            val matchFallback = videos.firstOrNull { video ->
                val note = video.extraNote?.lowercase() ?: ""
                val url = video.file.url.lowercase()
                note.contains(preferred.lowercase()) || note.contains(cleanPreferred) ||
                        url.contains("${cleanPreferred}p") || url.contains(cleanPreferred)
            }
            if (matchFallback != null) return matchFallback
        }

        return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        var loaded = false
        model.getMedia().observe(viewLifecycleOwner) { m ->
            media = m
            if (media != null && !loaded) {
                loaded = true
                onMediaLoaded()
            }
        }
        super.onViewCreated(view, savedInstanceState)
    }

    private fun onMediaLoaded() {
        val currentEpisodes = episodes
        if (currentEpisodes.isNullOrEmpty()) {
            fail(R.string.empty_episodes_list)
            return
        }

        if (isDownloadMenu == false) {
            handleWatchMenu(currentEpisodes[0])
        } else {
            handleDownloadMenu(currentEpisodes)
        }
    }

    private fun fail(resId: Int) {
        val ctx = context ?: currContext() ?: return
        ContextCompat.getMainExecutor(ctx).execute {
            snackString(getString(resId))
            tryWith {
                dismissAllowingStateLoss()
            }
        }
    }

    private fun initializeVideoServerSelector(
        ep: Episode,
        onEpisodeDownloadHandler: EpisodeDownloadHandler? = null
    ) {
        binding.selectorRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.selectorRecyclerView.adapter = null
        binding.selectorProgressBar.visibility = View.VISIBLE
        makeDefault = PrefManager.getVal(PrefName.MakeDefault)
        binding.selectorMakeDefault.isChecked = makeDefault
        binding.selectorMakeDefault.setOnClickListener {
            makeDefault = binding.selectorMakeDefault.isChecked
            PrefManager.setVal(PrefName.MakeDefault, makeDefault)
        }
        binding.selectorRecyclerView.layoutManager =
            LinearLayoutManager(
                requireActivity(),
                LinearLayoutManager.VERTICAL,
                false
            )
        val adapter = ExtractorAdapter(onEpisodeDownloadHandler)
        binding.selectorRecyclerView.adapter = adapter
        if (!ep.allStreams) {
            loadEpisodeStreamsDynamically(ep, adapter)
        } else {
            loadEpisodeStreamsStatically(ep, adapter)
        }
    }

    private fun loadEpisodeStreamsDynamically(ep: Episode, adapter: ExtractorAdapter) {
        ep.extractorCallback = { extractor ->
            scope.launch(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@launch
                adapter.add(extractor)
                binding.selectorProgressBar.visibility = View.GONE
            }
        }
        scope.launch(Dispatchers.IO) {
            val sourceIndex = media?.selected?.sourceIndex ?: 0
            model.loadEpisodeVideos(ep, sourceIndex)
            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                binding.selectorProgressBar.visibility = View.GONE
                if (adapter.itemCount == 0) {
                    fail(R.string.stream_selection_empty)
                }
                if (model.watchSources?.isDownloadedSource(sourceIndex) == true) {
                    adapter.performClick(0)
                }
            }
        }
    }

    private fun loadEpisodeStreamsStatically(ep: Episode, adapter: ExtractorAdapter) {
        val epKey = media?.anime?.episodes?.getEpisodeKey(ep.number) ?: media?.anime?.selectedEpisode
        if (epKey != null) {
            media?.anime?.episodes?.set(epKey, ep)
        }
        adapter.addAll(ep.extractors)
        if (ep.extractors?.isEmpty() == true) {
            fail(R.string.stream_selection_empty)
        }
        val sourceIndex = media?.selected?.sourceIndex ?: 0
        if (model.watchSources?.isDownloadedSource(sourceIndex) == true) {
            adapter.performClick(0)
        }
        binding.selectorProgressBar.visibility = View.GONE
    }

    private fun autoSelectServerAndPlay(ep: Episode) {
        binding.selectorListContainer.visibility = View.GONE
        binding.selectorAutoListContainer.visibility = View.VISIBLE
        binding.selectorAutoText.text = getString(R.string.auto_select_server)
        var isCancelled = false
        binding.selectorCancel.setOnClickListener {
            isCancelled = true
            binding.selectorAutoListContainer.visibility = View.GONE
            binding.selectorListContainer.visibility = View.VISIBLE
            initializeVideoServerSelector(ep)
        }

        val sourceName = model.watchSources?.get(media?.selected?.sourceIndex ?: 0)?.name
        val preferredResolutions = PrefManager.getPreferredDownloadResolutions(sourceName)

        if (ep.allStreams) {
            handleAutoSelectStatic(ep, preferredResolutions)
        } else {
            handleAutoSelectDynamic(ep, preferredResolutions) { isCancelled }
        }
    }

    private fun selectAndStart(
        ep: Episode,
        chosenExtractor: VideoExtractor,
        preferredResolutions: List<String>
    ): Boolean {
        val bestVideo = findBestVideoForDownload(chosenExtractor.videos, preferredResolutions) ?: return false
        ep.selectedExtractor = chosenExtractor.server.name
        ep.selectedVideo = chosenExtractor.videos.indexOf(bestVideo).takeIf { it >= 0 } ?: 0
        val currentKey = media?.anime?.selectedEpisode ?: ep.number
        media?.anime?.episodes?.getEpisode(currentKey)?.selectedExtractor = ep.selectedExtractor
        media?.anime?.episodes?.getEpisode(currentKey)?.selectedVideo = ep.selectedVideo
        media?.let { startExoplayer(it) }
        return true
    }

    private fun handleAutoSelectStatic(ep: Episode, preferredResolutions: List<String>) {
        val extractors = ep.extractors ?: emptyList()
        val validExtractor = extractors.firstOrNull { it.isPreferred && it.videos.isNotEmpty() }
            ?: extractors.firstOrNull { it.videos.isNotEmpty() }
        if (validExtractor != null && selectAndStart(ep, validExtractor, preferredResolutions)) {
            return
        }
        binding.selectorAutoListContainer.visibility = View.GONE
        binding.selectorListContainer.visibility = View.VISIBLE
        initializeVideoServerSelector(ep)
    }

    private fun handleAutoSelectDynamic(
        ep: Episode,
        preferredResolutions: List<String>,
        isCancelled: () -> Boolean
    ) {
        var hasStarted = false
        ep.extractorCallback = { extractor ->
            scope.launch(Dispatchers.Main) {
                if (_binding == null || !isAdded || isCancelled() || hasStarted) return@launch
                if (extractor.videos.isNotEmpty() && extractor.isPreferred) {
                    hasStarted = true
                    selectAndStart(ep, extractor, preferredResolutions)
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val sourceIndex = media?.selected?.sourceIndex ?: 0
            model.loadEpisodeVideos(ep, sourceIndex)
            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded || isCancelled()) return@withContext
                if (!hasStarted) {
                    val valid = ep.extractors?.firstOrNull { it.isPreferred && it.videos.isNotEmpty() }
                        ?: ep.extractors?.firstOrNull { it.videos.isNotEmpty() }
                    if (valid != null) {
                        hasStarted = true
                        selectAndStart(ep, valid, preferredResolutions)
                    } else {
                        binding.selectorAutoListContainer.visibility = View.GONE
                        binding.selectorListContainer.visibility = View.VISIBLE
                        initializeVideoServerSelector(ep)
                    }
                }
            }
        }
    }

    private suspend fun loadEpisodeSingleServer(episodeName: String, selectedServerName: String): Boolean {
        val ep = media?.anime?.episodes?.getEpisode(episodeName)
            ?: media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode)
            ?: return false
        episode = ep

        val selectedMedia = media?.selected ?: return false
        var success = false
        scope.launch(Dispatchers.IO) {
            success = model.loadEpisodeSingleVideo(
                ep,
                selectedMedia,
                selectedServerName = selectedServerName
            )
        }.join()
        Log.d("AnimeDownloader", "Loading Episode Server State: $success")
        return success
    }

    private fun startEpisodeDownload(
        episodeName: String,
        selectedServerName: String,
        selectedSubtitles: MutableList<String>,
        selectedAudioTracks: MutableList<String>
    ) {
        val ep = media?.anime?.episodes?.getEpisode(episodeName)
            ?: media?.anime?.episodes?.get(episodeName)
        if (ep == null) {
            fail(R.string.auto_select_server_error)
            return
        }
        val epKey = media?.anime?.episodes?.getEpisodeKey(episodeName) ?: episodeName
        media?.anime?.selectedEpisode = epKey
        episode = ep

        Log.d("AnimeDownloader", "Downloading Episode: ${ep.number}, server: $selectedServerName")

        val selectedExtractor = ep.extractors?.find { it.server.name == selectedServerName }
        if (selectedExtractor == null) {
            fail(R.string.auto_select_server_error)
        } else {
            media?.anime?.episodes?.set(epKey, ep)
            if (!downloadUsingSingleServer(selectedExtractor, ep, selectedSubtitles, selectedAudioTracks)) {
                fail(R.string.auto_select_server_error)
            }
        }
    }

    private fun downloadUsingSingleServer(
        extractor: VideoExtractor,
        currentEp: Episode,
        selectedSubtitles: MutableList<String>,
        selectedAudioTracks: MutableList<String>
    ): Boolean {
        updateSelectedVideoForExtractor(extractor, currentEp)

        if (PrefManager.getVal<Int>(PrefName.DownloadManager) != 0) {
            val act = activity ?: currActivity()
            if (act != null) {
                download(
                    act,
                    currentEp,
                    media!!.userPreferredName
                )
            }
            return true
        }

        return executeAddonDownload(extractor, currentEp, selectedSubtitles, selectedAudioTracks)
    }

    private fun updateSelectedVideoForExtractor(extractor: VideoExtractor, currentEp: Episode) {
        val sourceName = model.watchSources?.get(media?.selected?.sourceIndex ?: 0)?.name
        val preferredResolutions = PrefManager.getPreferredDownloadResolutions(sourceName)
        val autoPriority = PrefManager.getVal<Boolean>(PrefName.AutoSelectResolutionPriority)

        currentEp.selectedExtractor = extractor.server.name
        val bestVideo = if (autoPriority && extractor.videos.isNotEmpty()) {
            findBestVideoForDownload(extractor.videos, preferredResolutions)
        } else null

        val chosenVideoIndex = if (bestVideo != null) {
            extractor.videos.indexOf(bestVideo).takeIf { it >= 0 } ?: 0
        } else {
            if (currentEp.selectedVideo >= extractor.videos.size) 0 else currentEp.selectedVideo
        }
        currentEp.selectedVideo = chosenVideoIndex

        val epKey = media?.anime?.episodes?.getEpisodeKey(currentEp.number) ?: currentEp.number
        media?.anime?.episodes?.get(epKey)?.let { mapEp ->
            mapEp.selectedExtractor = extractor.server.name
            mapEp.selectedVideo = currentEp.selectedVideo
        }
    }

    private fun executeAddonDownload(
        extractor: VideoExtractor,
        currentEp: Episode,
        selectedSubtitles: MutableList<String>,
        selectedAudioTracks: MutableList<String>
    ): Boolean {
        val downloadAddonManager: DownloadAddonManager = Injekt.get()
        if (!downloadAddonManager.isAvailable()) {
            showDownloadAddonNotInstalledDialog()
            return false
        }
        val subtitlesToDownload = collectSubtitlesToDownload(extractor, currentEp, selectedSubtitles)
        val audioTracksToDownload = collectAudioTracksToDownload(extractor, selectedAudioTracks)

        val selectedVideo = if (extractor.videos.isNotEmpty()) {
            if (currentEp.selectedVideo in extractor.videos.indices) extractor.videos[currentEp.selectedVideo] else extractor.videos[0]
        } else null

        val act = activity ?: currActivity()
        selectedVideo?.file?.url?.let { url ->
            if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                if (!prepareTorrentForDownload(url, selectedVideo)) {
                    return false
                }
            }
        }
        if (selectedVideo != null && act != null) {
            Helper.startAnimeDownloadService(
                act,
                media!!.mainName(),
                currentEp.number,
                selectedVideo,
                subtitlesToDownload,
                audioTracksToDownload,
                media,
                currentEp.thumb?.url ?: media!!.banner ?: media!!.cover
            )
            val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
                putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, currentEp.number)
                putExtra("mediaId", media?.id)
            }
            act.sendBroadcast(intent)
        } else if (selectedVideo == null) {
            snackString(R.string.no_video_selected)
        }
        return true
    }

    private fun showDownloadAddonNotInstalledDialog() {
        val ctx = context ?: currContext()
        ctx?.customAlertDialog()?.apply {
            setTitle(R.string.download_addon_not_installed)
            setMessage(R.string.would_you_like_to_install)
            setPosButton(R.string.yes) {
                ctx.startActivity(Intent(ctx, SettingsAddonActivity::class.java))
            }
            setNegButton(R.string.no) { }
            show()
        }
    }

    private fun collectSubtitlesToDownload(
        extractor: VideoExtractor,
        currentEp: Episode,
        selectedSubtitles: List<String>
    ): MutableList<Pair<String, String>> {
        val subtitlesToDownload: MutableList<Pair<String, String>> = mutableListOf()
        val embedUrl = extractor.server.embed.url
        val selectedVideoUrl = if (extractor.videos.isNotEmpty()) {
            if (currentEp.selectedVideo in extractor.videos.indices) extractor.videos[currentEp.selectedVideo].file.url else extractor.videos[0].file.url
        } else ""
        extractor.subtitles.forEach {
            if (it.language in selectedSubtitles) {
                val resolvedUrl = ani.dantotsu.media.anime.player.PlayerSubtitleManager.resolveSubtitleUrl(
                    it.file.url, embedUrl, selectedVideoUrl
                )
                subtitlesToDownload.add(Pair(resolvedUrl, it.language))
            }
        }
        return subtitlesToDownload
    }

    private fun collectAudioTracksToDownload(
        extractor: VideoExtractor,
        selectedAudioTracks: List<String>
    ): MutableList<Pair<String, String>> {
        val audioTracksToDownload: MutableList<Pair<String, String>> = mutableListOf()
        extractor.audioTracks.forEach {
            if (it.lang in selectedAudioTracks) {
                audioTracksToDownload.add(Pair(it.url, it.lang))
            }
        }
        return audioTracksToDownload
    }

    private fun prepareTorrentForDownload(url: String, selectedVideo: Video): Boolean {
        val torrentManager = Injekt.get<TorrentServerManager>()
        if (!torrentManager.isAvailable()) {
            toast(R.string.torrent_addon_not_available)
            return false
        }
        runBlocking {
            try {
                withContext(Dispatchers.IO) {
                    torrentManager.activeTorrentHash?.let {
                        torrentManager.removeTorrent(it)
                    }
                    val index = if (url.contains("index=")) {
                        url.substringAfter("index=").toIntOrNull() ?: 0
                    } else 0
                    Logger.log("Sending: $url, ${selectedVideo.quality}, $index")
                    val currentTorrent = torrentManager.addTorrent(
                        url,
                        selectedVideo.quality.toString(),
                        "",
                        "",
                        false
                    )
                    torrentManager.activeTorrentHash = currentTorrent.hash
                    torrentManager.prebuffer(currentTorrent.hash!!, index)
                    selectedVideo.file.url = torrentManager.getLink(currentTorrent, index)
                    Logger.log("Received: ${selectedVideo.file.url}")
                }
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().logException(e)
                Logger.log(e)
                toast("Error starting video: ${e.message}")
            }
        }
        return true
    }

    private fun handleWatchMenu(rawKey: String) {
        val ep = media?.anime?.episodes?.getEpisode(rawKey)
        val actualKey = media?.anime?.episodes?.getEpisodeKey(rawKey) ?: rawKey
        media?.anime?.selectedEpisode = actualKey
        episode = ep
        if (ep == null) return

        if (selected != null && media?.format != "LOCAL") {
            handleSingleServerWatch(ep, actualKey)
        } else if (PrefManager.getVal<Boolean>(PrefName.AutoSelectServer) && media?.format != "LOCAL") {
            autoSelectServerAndPlay(ep)
        } else {
            initializeVideoServerSelector(ep)
        }
    }

    private fun handleSingleServerWatch(ep: Episode, actualKey: String) {
        val serverName = selected ?: return
        binding.selectorListContainer.visibility = View.GONE
        binding.selectorAutoListContainer.visibility = View.VISIBLE
        binding.selectorAutoText.text = serverName
        binding.selectorCancel.setOnClickListener {
            media?.selected?.server = null
            media?.let { m -> m.selected?.let { sel -> model.saveSelected(m.id, sel) } }
            tryWith {
                dismissAllowingStateLoss()
            }
        }

        val hasMatchingExtractor = ep.extractors?.any {
            it.server.name == serverName || it.isPreferred
        } == true

        if (!hasMatchingExtractor) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    loadEpisodeSingleServer(ep.number, serverName)
                }
                withContext(Dispatchers.Main) {
                    if (_binding == null || !isAdded) return@withContext
                    if (!success) {
                        failToSelectorList(ep)
                    } else {
                        loadMatchedExtractorAndPlay(ep, actualKey, serverName)
                    }
                }
            }
        } else {
            loadMatchedExtractorAndPlay(ep, actualKey, serverName)
        }
    }

    private fun failToSelectorList(ep: Episode) {
        snackString(getString(R.string.auto_select_server_error))
        media?.selected?.server = null
        media?.let { m -> m.selected?.let { sel -> model.saveSelected(m.id, sel) } }
        binding.selectorAutoListContainer.visibility = View.GONE
        binding.selectorListContainer.visibility = View.VISIBLE
        initializeVideoServerSelector(ep)
    }

    private fun findMatchedExtractor(ep: Episode, sourceIndex: Int, serverName: String): VideoExtractor? =
        if (model.watchSources?.isDownloadedSource(sourceIndex) == true) {
            ep.extractors?.firstOrNull()
        } else {
            ep.extractors?.find { it.server.name == serverName }
                ?: ep.extractors?.find { it.isPreferred }
                ?: ep.extractors?.firstOrNull()
        }

    private fun loadMatchedExtractorAndPlay(ep: Episode, actualKey: String, serverName: String) {
        val m = media ?: return
        val sel = m.selected ?: return
        val matchedExtractor = findMatchedExtractor(ep, sel.sourceIndex, serverName)

        val size = matchedExtractor?.videos?.size
        if (matchedExtractor != null && size != null && size > 0) {
            val currentKey = m.anime?.selectedEpisode ?: actualKey
            m.anime?.episodes?.getEpisode(currentKey)?.selectedExtractor = matchedExtractor.server.name
            val chosenVideo = if (sel.video in 0 until size) sel.video else 0
            m.anime?.episodes?.getEpisode(currentKey)?.selectedVideo = chosenVideo
            sel.server = matchedExtractor.server.name
            model.saveSelected(m.id, sel)
            startExoplayer(m)
        } else {
            failToSelectorList(ep)
        }
    }

    private fun handleDownloadMenu(currentEpisodes: ArrayList<String>) {
        binding.selectorMakeDefault.visibility = View.GONE
        val rawKey = currentEpisodes[0]
        val ep = media?.anime?.episodes?.getEpisode(rawKey)
        val actualKey = media?.anime?.episodes?.getEpisodeKey(rawKey) ?: rawKey
        media?.anime?.selectedEpisode = actualKey
        episode = ep

        if (ep != null) {
            val downloadHandler =
                EpisodeDownloadHandler { selectedServerName, selectedSubtitles, selectedAudioTracks ->
                    startMultiEpisodeDownload(currentEpisodes, selectedServerName, selectedSubtitles, selectedAudioTracks)
                }
            initializeVideoServerSelector(ep, downloadHandler)
        }
    }

    private fun startMultiEpisodeDownload(
        currentEpisodes: ArrayList<String>,
        selectedServerName: String,
        selectedSubtitles: MutableList<String>,
        selectedAudioTracks: MutableList<String>
    ) {
        binding.selectorListContainer.visibility = View.GONE
        binding.selectorAutoListContainer.visibility = View.VISIBLE
        binding.selectorTitle.text = "Starting Download"
        binding.selectorAutoText.text =
            "Starting download using server:
$selectedServerName"
        binding.selectorCancel.visibility = View.GONE

        scope.launch(Dispatchers.IO) {
            val serverSelectionScope = CoroutineScope(Dispatchers.IO)
            val serverSelectionTasks = mutableListOf<Deferred<Unit>>()
            for (episodeName in currentEpisodes.drop(1)) {
                serverSelectionTasks.add(serverSelectionScope.async {
                    if (!loadEpisodeSingleServer(episodeName, selectedServerName)) {
                        Log.d("AnimeDownloader", "Error loading server $selectedServerName for episode $episodeName")
                        fail(R.string.auto_select_server_error)
                    }
                })
            }
            serverSelectionTasks.awaitAll()

            for (episodeName in currentEpisodes) {
                startEpisodeDownload(episodeName, selectedServerName, selectedSubtitles, selectedAudioTracks)
            }
            withContext(Dispatchers.Main) {
                tryWith {
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    private val externalPlayerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        Logger.log(result.data.toString())
    }

    private fun exportMagnetIntent(episode: Episode, video: Video): Intent {
        val amnis = "com.amnis"
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(amnis, "$amnis.gui.player.PlayerActivity")
            data = Uri.parse(video.file.url)
            putExtra("title", "${media?.name} - ${episode.title}")
            putExtra("position", 0)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("secure_uri", true)
            val headersArray = arrayOf<String>()
            video.file.headers.forEach {
                headersArray.plus(arrayOf(it.key, it.value))
            }
            putExtra("headers", headersArray)
        }
    }

    private fun isTorrentUrl(url: String, ep: Episode): Boolean =
        url.startsWith("magnet:") ||
                url.endsWith(".torrent") ||
                url.contains("/stream?hash=") ||
                url.contains("127.0.0.1") ||
                ep.extra?.containsKey("torrentHash") == true

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("UnsafeOptInUsageError")
    fun startExoplayer(media: Media) {
        if (!isAdded || _binding == null) return
        prevEpisode = null

        val ep = episode
        val video = ep?.extractors?.find {
            it.server.name == ep.selectedExtractor
        }?.videos?.getOrNull(ep.selectedVideo)
        val url = video?.file?.url

        if (ep != null && video != null && url != null) {
            if (isTorrentUrl(url, ep)) {
                if (handleTorrentPlayback(ep, video, url, media)) {
                    return
                }
            } else if (url.startsWith("magnet:")) {
                launchExternalMagnetPlayer(ep, video)
                return
            }
        }

        launchDefaultExoplayer(media)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun handleTorrentPlayback(ep: Episode, video: Video, url: String, media: Media): Boolean {
        val torrentManager = Injekt.get<TorrentServerManager>()
        if (!torrentManager.isAvailable()) return false

        val activity = activity ?: currActivity()
        launchIO {
            try {
                ani.dantotsu.addons.torrent.TorrentServerService.start()
                torrentManager.start()
                val torrentHash = ep.extra?.get("torrentHash")
                    ?: if (url.contains("hash=")) url.substringAfter("hash=").substringBefore("&") else null
                val index = ep.extra?.get("fileId")?.toIntOrNull()
                    ?: if (url.contains("index=")) url.substringAfter("index=").substringBefore("&").toIntOrNull() else null
                    ?: 0

                prepareTorrentBuffer(torrentManager, torrentHash, url, video, index)

                if (launch == true) {
                    Intent(activity, ExoplayerView::class.java).apply {
                        ExoplayerView.media = media
                        ExoplayerView.initialized = true
                        startActivity(this)
                    }
                } else {
                    val epKey = media.anime?.selectedEpisode
                    val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: ep
                    model.setEpisode(targetEp, "startExo no launch")
                }
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().logException(e)
                Logger.log(e)
                toast("Error starting video: ${e.message}")
                dismissAllowingStateLoss()
            }
        }
        return true
    }

    private suspend fun prepareTorrentBuffer(
        torrentManager: TorrentServerManager,
        torrentHash: String?,
        url: String,
        video: Video,
        index: Int
    ) {
        if (torrentHash != null) {
            torrentManager.activeTorrentHash = torrentHash
            torrentManager.prebuffer(torrentHash, index)
        } else if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
            torrentManager.activeTorrentHash?.let {
                torrentManager.removeTorrent(it)
            }
            val currentTorrent = torrentManager.addTorrent(
                url, video.quality.toString(), "", "", false
            )
            torrentManager.activeTorrentHash = currentTorrent.hash
            torrentManager.prebuffer(currentTorrent.hash!!, index)
            video.file.url = torrentManager.getLink(currentTorrent, index)
        }
    }

    private fun launchExternalMagnetPlayer(ep: Episode, video: Video) {
        try {
            externalPlayerResult.launch(exportMagnetIntent(ep, video))
        } catch (e: ActivityNotFoundException) {
            val amnis = "com.amnis"
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$amnis")
                    )
                )
                dismissAllowingStateLoss()
            } catch (e: ActivityNotFoundException) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$amnis")
                    )
                )
            }
        }
    }

    private fun launchDefaultExoplayer(media: Media) {
        dismissAllowingStateLoss()
        if (launch == true) {
            stopAddingToList()
            val intent = Intent(activity, ExoplayerView::class.java)
            ExoplayerView.media = media
            ExoplayerView.initialized = true
            startActivity(intent)
        } else {
            val epKey = media.anime?.selectedEpisode
            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: episode
            if (targetEp != null) {
                model.setEpisode(targetEp, "startExo no launch")
            }
        }
    }

    private fun stopAddingToList() {
        episode?.extractorCallback = null
        episode?.also {
            it.extractors = it.extractors?.toMutableList()
        }
    }

    private inner class ExtractorAdapter(private val onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) :
        RecyclerView.Adapter<ExtractorAdapter.StreamViewHolder>() {
        val links = mutableListOf<VideoExtractor>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemStreamBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val extractor = links.getOrNull(position) ?: return
            holder.binding.streamName.text = ""//extractor.server.name
            holder.binding.streamName.visibility = View.GONE

            holder.binding.streamRecyclerView.layoutManager = LinearLayoutManager(requireContext())
            holder.binding.streamRecyclerView.adapter = VideoAdapter(extractor, onEpisodeDownloadHandler)
        }

        override fun getItemCount(): Int = links.size

        fun add(videoExtractor: VideoExtractor) {
            if (videoExtractor.videos.isNotEmpty()) {
                val existingIndex = links.indexOfFirst {
                    it.server.name == videoExtractor.server.name
                }
                if (existingIndex >= 0) {
                    links[existingIndex] = videoExtractor
                    notifyItemChanged(existingIndex)
                } else {
                    if (videoExtractor.isPreferred) {
                        links.add(0, videoExtractor)
                        notifyItemInserted(0)
                    } else {
                        links.add(videoExtractor)
                        notifyItemInserted(links.size - 1)
                    }
                }
            }
        }

        fun addAll(extractors: List<VideoExtractor>?) {
            if (extractors == null) return
            val sorted = extractors.sortedByDescending { it.isPreferred }
            links.addAll(sorted)
            notifyItemRangeInserted(0, sorted.size)
        }

        fun performClick(position: Int) {
            try {
                val extractor = links[position]
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
                    currentEp.selectedExtractor = extractor.server.name
                    currentEp.selectedVideo = 0
                }
                if (epKey != null) {
                    media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                    media?.anime?.episodes?.get(epKey)?.selectedVideo = 0
                }
                startExoplayer(media!!)
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().logException(e)
            }
        }

        private inner class StreamViewHolder(val binding: ItemStreamBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private inner class VideoAdapter(private val extractor: VideoExtractor,private val onEpisodeDownloadHandler: EpisodeDownloadHandler?) :
        RecyclerView.Adapter<VideoAdapter.UrlViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UrlViewHolder {
            return UrlViewHolder(
                ItemUrlBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: UrlViewHolder, position: Int) {
            val binding = holder.binding
            val video = extractor.videos[position]
            binding.urlDownload.visibility = if (isDownloadMenu == true) View.VISIBLE else View.GONE
            binding.urlSub.visibility = if (extractor.subtitles.isNotEmpty()) View.VISIBLE else View.GONE

            setupSubClickListener(binding)
            setupDownloadClickListener(binding, position)
            bindVideoDetails(binding, video)
        }

        private fun setupSubClickListener(binding: ItemUrlBinding) {
            val subtitles = extractor.subtitles
            binding.urlSub.setOnClickListener {
                if (subtitles.isNotEmpty()) {
                    showSubtitleDownloadDialog(subtitles)
                } else {
                    snackString(R.string.no_subtitles_available)
                }
            }
        }

        private fun showSubtitleDownloadDialog(subtitles: List<Subtitle>) {
            val subtitleNames = subtitles.map { it.language }
            var subtitleToDownload: Subtitle? = null
            val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
            val epNumber = currentEp?.number ?: media?.anime?.selectedEpisode ?: "1"
            (activity ?: currActivity())?.customAlertDialog()?.apply {
                setTitle(R.string.download_subtitle)
                singleChoiceItems(subtitleNames.toTypedArray(), dismissOnSelect = false) { which ->
                    subtitleToDownload = subtitles[which]
                }
                setPosButton(R.string.download) {
                    scope.launch(Dispatchers.IO) {
                        val sub = subtitleToDownload ?: return@launch
                        val ctx = context ?: currContext() ?: return@launch
                        val m = media ?: return@launch
                        SubtitleDownloader.downloadSubtitle(
                            ctx,
                            sub.file.url,
                            DownloadedType(
                                m.mainName(),
                                epNumber,
                                MediaType.ANIME
                            )
                        )
                    }
                }
                setNegButton(R.string.cancel) {}
            }?.show()
        }

        private fun selectVideoForEpisode(position: Int): Episode? {
            val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
            val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
            if (currentEp != null) {
                currentEp.selectedExtractor = extractor.server.name
                currentEp.selectedVideo = position
            }
            if (epKey != null) {
                media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                media?.anime?.episodes?.get(epKey)?.selectedVideo = position
            }
            return currentEp
        }

        private fun setupDownloadClickListener(binding: ItemUrlBinding, position: Int) {
            binding.urlDownload.setSafeOnClickListener {
                val currentEp = selectVideoForEpisode(position)
                if (PrefManager.getVal<Int>(PrefName.DownloadManager) != 0) {
                    val act = activity ?: currActivity()
                    if (act != null && currentEp != null && media != null) {
                        download(
                            act,
                            currentEp,
                            media!!.userPreferredName
                        )
                    }
                } else {
                    handleDownloadAddonSelection(currentEp)
                }
            }
        }

        private fun handleDownloadAddonSelection(currentEp: Episode?) {
            val ep = currentEp ?: return
            val selectedVideo = if (extractor.videos.size > ep.selectedVideo) {
                extractor.videos[ep.selectedVideo]
            } else {
                extractor.videos.getOrNull(0)
            }

            val downloadAddonManager: DownloadAddonManager = Injekt.get()
            if (!downloadAddonManager.isAvailable()) {
                val ctx = context ?: currContext()
                ctx?.customAlertDialog()?.apply {
                    setTitle(R.string.download_addon_not_installed)
                    setMessage(R.string.would_you_like_to_install)
                    setPosButton(R.string.yes) {
                        ContextCompat.startActivity(
                            ctx,
                            Intent(ctx, SettingsAddonActivity::class.java),
                            null
                        )
                    }
                    setNegButton(R.string.no) {}
                    show()
                }
                dismissAllowingStateLoss()
                return
            }

            selectedVideo?.file?.url?.let { url ->
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    val torrentManager = Injekt.get<TorrentServerManager>()
                    if (!torrentManager.isAvailable()) {
                        toast(R.string.torrent_addon_not_available)
                        return
                    }
                }
            }

            promptSubtitlesAndAudioTracks()
        }

        private fun promptSubtitlesAndAudioTracks() {
            val selectedSubtitles = mutableListOf<String>()
            val selectedAudioTracks = mutableListOf<String>()

            val onComplete = {
                onEpisodeDownloadHandler?.onFinishingUserSelection(
                    extractor.server.name,
                    selectedSubtitles,
                    selectedAudioTracks
                )
            }

            val onAudioPrompt = {
                promptAudioTracks(selectedAudioTracks, onComplete)
            }

            if (extractor.subtitles.isNotEmpty()) {
                promptSubtitles(selectedSubtitles, onAudioPrompt)
            } else {
                onAudioPrompt()
            }
        }

        private fun promptSubtitles(
            selectedSubtitles: MutableList<String>,
            onComplete: () -> Unit
        ) {
            val subtitles = extractor.subtitles
            val subtitleNamesArray = subtitles.map { it.language }.toTypedArray()
            val subLanguages = arrayOf(
                "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
                "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
                "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
                "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
                "Turkish", "Ukrainian", "Urdu", "Vietnamese"
            )
            val prefLang = subLanguages.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
            val isPrefEnglish = prefLang.equals("English", ignoreCase = true)
            val hasPrefMatch = if (!isPrefEnglish) subtitleNamesArray.any { it.contains(prefLang, ignoreCase = true) } else false
            val englishRegex = Regex("""(?i)(?:^|[^a-zA-Z])(en|eng|english)(?:[^a-zA-Z]|$)""")

            val checkedItems = BooleanArray(subtitleNamesArray.size) { index ->
                val name = subtitleNamesArray[index]
                val isDefaultMatch = if (hasPrefMatch) {
                    name.contains(prefLang, ignoreCase = true)
                } else {
                    name.contains("English", true) || englishRegex.containsMatchIn(name) || subtitles.size == 1
                }
                if (isDefaultMatch) {
                    selectedSubtitles.add(subtitles[index].language)
                }
                isDefaultMatch
            }

            val currContext = currContext() ?: requireContext()
            currContext.customAlertDialog().apply {
                setTitle(R.string.download_subtitle)
                multiChoiceItems(subtitleNamesArray, checkedItems) {
                    it.forEachIndexed { index, isChecked ->
                        val subtitleName = subtitles[index].language
                        if (isChecked) {
                            if (!selectedSubtitles.contains(subtitleName)) selectedSubtitles.add(subtitleName)
                        } else {
                            selectedSubtitles.remove(subtitleName)
                        }
                    }
                }
                setPosButton(R.string.download) {
                    onComplete()
                }
                setNegButton(R.string.skip) {
                    selectedSubtitles.clear()
                    onComplete()
                }
                setNeutralButton(R.string.cancel) {
                    selectedSubtitles.clear()
                }
                show()
            }
        }

        private fun promptAudioTracks(
            selectedAudioTracks: MutableList<String>,
            onComplete: () -> Unit
        ) {
            val audioTracks = extractor.audioTracks.map { it.lang }
            if (audioTracks.isEmpty()) {
                onComplete()
                return
            }
            val audioNamesArray = audioTracks.toTypedArray()
            val checkedItems = BooleanArray(audioNamesArray.size) { false }
            val currContext = currContext() ?: requireContext()

            currContext.customAlertDialog().apply {
                setTitle(R.string.download_audio_tracks)
                multiChoiceItems(audioNamesArray, checkedItems) {
                    it.forEachIndexed { index, isChecked ->
                        val audioName = extractor.audioTracks[index].lang
                        if (isChecked) {
                            selectedAudioTracks.add(audioName)
                        } else {
                            selectedAudioTracks.remove(audioName)
                        }
                    }
                }
                setPosButton(R.string.download) {
                    onComplete()
                }
                setNegButton(R.string.skip) {
                    selectedAudioTracks.clear()
                    onComplete()
                }
                setNeutralButton(R.string.cancel) {
                    selectedAudioTracks.clear()
                }
                show()
            }
        }

        private fun bindVideoDetails(binding: ItemUrlBinding, video: Video) {
            if (video.format == VideoType.CONTAINER) {
                binding.urlSize.isVisible = video.size != null
                val formattedSize = if (video.size == 0.0) {
                    getString(R.string.size_unknown)
                } else {
                    DecimalFormat("#.##").format(video.size ?: 0)
                }
                val prefix = if (video.extraNote != null) " : " else ""
                binding.urlSize.text = getString(R.string.mb_size, "$prefix$formattedSize")
            }
            val isPref = video.preferred || extractor.isPreferred
            binding.urlNote.visibility = View.VISIBLE
            binding.urlNote.text = if (isPref) "${video.format.name} • Preferred" else video.format.name
            binding.urlQuality.text = extractor.server.name
        }

        override fun getItemCount(): Int = extractor.videos.size

        private inner class UrlViewHolder(val binding: ItemUrlBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                itemView.setSafeOnClickListener {
                    if (isDownloadMenu == true) {
                        binding.urlDownload.performClick()
                        return@setSafeOnClickListener
                    }
                    tryWith(true) {
                        val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                        val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                        if (currentEp != null) {
                            currentEp.selectedExtractor = extractor.server.name
                            currentEp.selectedVideo = bindingAdapterPosition
                        }
                        if (epKey != null) {
                            media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                            media?.anime?.episodes?.get(epKey)?.selectedVideo = bindingAdapterPosition
                        }
                        if (makeDefault) {
                            media!!.selected!!.server = extractor.server.name
                            media!!.selected!!.video = bindingAdapterPosition
                            model.saveSelected(media!!.id, media!!.selected!!)
                        }
                        Log.d("AnimeDownloader", "Should start the player")
                        startExoplayer(media!!)
                    }
                }
                itemView.setOnLongClickListener {
                    val video = extractor.videos[bindingAdapterPosition]
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(video.file.url), "video/*")
                    }
                    copyToClipboard(video.file.url, true)
                    dismissAllowingStateLoss()
                    startActivity(Intent.createChooser(intent, "Open Video in :"))
                    true
                }
            }
        }
    }

    companion object {
        fun newInstance(
            server: String? = null,
            la: Boolean = true,
            prev: String? = null,
            isDownload: Boolean,
            episodes: ArrayList<String>
        ): SelectorDialogFragment =
            SelectorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("server", server)
                    putBoolean("launch", la)
                    putString("prev", prev)
                    putBoolean("isDownload", isDownload)
                    putStringArrayList("episodes", episodes)
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {}

    override fun onDismiss(dialog: DialogInterface) {
        if (launch == false) {
            activity?.hideSystemBarsExtendView()
            model.epChanged.postValue(true)
            if (prevEpisode != null) {
                media?.anime?.selectedEpisode = prevEpisode
                model.setEpisode(media?.anime?.episodes?.get(prevEpisode) ?: return, "prevEp")
            }
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
