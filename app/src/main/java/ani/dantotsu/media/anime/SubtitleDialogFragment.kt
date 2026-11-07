package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.subtitles.OpenSubRestItem
import ani.dantotsu.connections.subtitles.OpenSubtitlesRestApi
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.SubSourceSub
import ani.dantotsu.connections.subtitles.SubSourceSubtitles
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.connections.subtitles.WyzieSubtitles
import ani.dantotsu.databinding.BottomSheetSubtitlesBinding
import ani.dantotsu.databinding.ItemSubtitleCardBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.anime.player.PlayerSubtitleManager
import ani.dantotsu.others.IdMappers
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(UnstableApi::class)
class SubtitleDialogFragment : BottomSheetDialogFragment() {

    // Models for subtitle list items
    object NoneSubtitleOption
    data class ActiveOnlineSubtitle(val title: String, val provider: String, val idOrUrl: String, val format: String? = null)
    data class OtherServerSubtitle(val serverName: String, val subtitle: Subtitle, val suffix: String = "")
    data class EmbeddedSubtitleTrack(val group: Tracks.Group, val trackIndex: Int, val language: String?, val label: String?, val flatIndex: Int = 0)
    enum class TabType { SERVER, ONLINE, LOCAL }

    private var _binding: BottomSheetSubtitlesBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()

    private lateinit var episode: Episode
    private var currentSeasonEpisode: EpisodeMapper.SeasonEpisode? = null
    private var searchJob: Job? = null

    // Tab state: 0 = Subtitles, 1 = Online, 2 = Local, 3 = Sync
    private var currentTab = 0

    // Filter states for online tab
    private var selectedProviderFilter = "All"
    private var selectedLanguageFilter = "All"
    private var currentOnlineResults: List<Any> = emptyList()

    private val LANGUAGE_MAP: Map<String, String> = mapOf(
        "eng" to "English", "en" to "English", "en-us" to "English", "en-gb" to "English",
        "spa" to "Spanish", "es" to "Spanish", "es-es" to "Spanish", "es-419" to "Spanish", "es-la" to "Spanish",
        "fra" to "French", "fr" to "French", "fr-fr" to "French",
        "deu" to "German", "de" to "German", "de-de" to "German",
        "ita" to "Italian", "it" to "Italian", "it-it" to "Italian",
        "por" to "Portuguese", "pt" to "Portuguese", "pt-br" to "Portuguese", "pt-pt" to "Portuguese",
        "rus" to "Russian", "ru" to "Russian", "ru-ru" to "Russian",
        "jpn" to "Japanese", "ja" to "Japanese", "ja-jp" to "Japanese",
        "zho" to "Chinese", "chi" to "Chinese", "zh" to "Chinese", "zh-cn" to "Chinese", "zh-tw" to "Chinese",
        "ara" to "Arabic", "ar" to "Arabic", "ar-me" to "Arabic", "ar-sa" to "Arabic",
        "hin" to "Hindi", "hi" to "Hindi",
        "kor" to "Korean", "ko" to "Korean", "ko-kr" to "Korean",
        "pol" to "Polish", "pl" to "Polish", "pl-pl" to "Polish",
        "tur" to "Turkish", "tr" to "Turkish", "tr-tr" to "Turkish",
        "hun" to "Hungarian", "hu" to "Hungarian",
        "ron" to "Romanian", "ro" to "Romanian", "ro-ro" to "Romanian",
        "ell" to "Greek", "el" to "Greek", "el-gr" to "Greek",
        "cze" to "Czech", "cs" to "Czech",
        "swe" to "Swedish", "sv" to "Swedish", "sv-se" to "Swedish",
        "dan" to "Danish", "da" to "Danish",
        "fin" to "Finnish", "fi" to "Finnish",
        "nor" to "Norwegian", "no" to "Norwegian",
        "nld" to "Dutch", "nl" to "Dutch",
        "tha" to "Thai", "th" to "Thai",
        "vie" to "Vietnamese", "vi" to "Vietnamese",
        "ind" to "Indonesian", "id" to "Indonesian",
        "ukr" to "Ukrainian", "uk" to "Ukrainian", "uk-uk" to "Ukrainian",
        "heb" to "Hebrew", "he" to "Hebrew", "he-il" to "Hebrew",
        "bul" to "Bulgarian", "bg" to "Bulgarian",
        "hrv" to "Croatian", "hr" to "Croatian",
        "slk" to "Slovak", "sk" to "Slovak",
        "slv" to "Slovenian", "sl" to "Slovenian",
        "mon" to "Mongolian", "mn" to "Mongolian",
        "srp" to "Serbian", "sr" to "Serbian"
    )

    private val LANGUAGE_CODES_MAP: Map<String, List<String>> = mapOf(
        "english" to listOf("eng", "en", "en-us", "en-gb"),
        "spanish" to listOf("spa", "es", "es-es", "es-419", "es-la"),
        "french" to listOf("fra", "fre", "fr", "fr-fr"),
        "german" to listOf("deu", "ger", "de", "de-de"),
        "portuguese" to listOf("por", "pt", "pt-br", "pt-pt"),
        "arabic" to listOf("ara", "ar", "ar-me", "ar-sa"),
        "russian" to listOf("rus", "ru", "ru-ru"),
        "japanese" to listOf("jpn", "ja", "ja-jp"),
        "chinese" to listOf("zho", "chi", "zh", "zh-cn", "zh-tw"),
        "hindi" to listOf("hin", "hi"),
        "korean" to listOf("kor", "ko", "ko-kr"),
        "polish" to listOf("pol", "pl"),
        "turkish" to listOf("tur", "tr"),
        "indonesian" to listOf("ind", "id"),
        "vietnamese" to listOf("vie", "vi"),
        "thai" to listOf("tha", "th"),
        "italian" to listOf("ita", "it")
    )

    private fun mapLanguageCode(isoCode: String): String {
        return LANGUAGE_MAP[isoCode.lowercase(Locale.ROOT)] ?: isoCode
    }

    private fun matchesLanguage(langText: String, filterLanguage: String): Boolean {
        if (filterLanguage.equals("All", ignoreCase = true)) return true
        val mapped = mapLanguageCode(langText)
        if (mapped.contains(filterLanguage, ignoreCase = true)) return true
        if (langText.contains(filterLanguage, ignoreCase = true)) return true

        val matchCodes = LANGUAGE_CODES_MAP[filterLanguage.lowercase(Locale.ROOT)]
            ?: listOf(filterLanguage.lowercase(Locale.ROOT))
        val lower = langText.lowercase(Locale.ROOT)
        return matchCodes.any { lower.contains(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSubtitlesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { dlg ->
            val bottomSheet = dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupTabNavigation()
        setupOnlineSearchControls()
        setupLocalControls()
        setupSyncControls()

        binding.closeSubtitlesSheet.setOnClickListener {
            dismiss()
        }

        binding.quickSearchOnlineBtn.setOnClickListener {
            switchTab(1)
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            observeMedia(media)
        }
    }

    private fun observeMedia(media: Media?) {
        val anime = media?.anime ?: return
        val eps = anime.episodes ?: return
        val selectedEpisode = anime.selectedEpisode ?: "1"
        val ep = eps.getEpisode(selectedEpisode) ?: return
        episode = ep

        val actualEpisodeNum = MediaNameAdapter.findEpisodeNumber(ep.number)?.toInt()
            ?: ep.number.filter { it.isDigit() }.toIntOrNull()
            ?: selectedEpisode.toIntOrNull()
            ?: 1
        val episodeId = "${media.id}-${episode.number}"

        val animeTitleText = media.userPreferredName
        if (binding.onlineSearchEditText.text.isNullOrBlank()) {
            binding.onlineSearchEditText.setText("$animeTitleText Episode $actualEpisodeNum")
        }

        updateActiveSubtitleBadge()
        loadSubtitlesTab(episodeId)
        loadLocalTab(episodeId)

        val cachedOnline = model.getFetchedSubtitles(episodeId)
        if (cachedOnline != null && cachedOnline.isNotEmpty()) {
            currentOnlineResults = cachedOnline
            filterAndDisplayOnlineResults()
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (media.idIMDB == null) {
                runCatching {
                    val imdb = IdMappers.getImdbId(media.id)
                    if (imdb != null) media.idIMDB = imdb
                }
            }
            runCatching {
                currentSeasonEpisode = EpisodeMapper.mapEpisode(media, actualEpisodeNum, ep)
            }
        }
    }

    private fun setupRecyclerViews() {
        binding.serverSubtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.onlineSubtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.localSubtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupTabNavigation() {
        binding.tabSubtitlesBtn.setOnClickListener { switchTab(0) }
        binding.tabOnlineBtn.setOnClickListener { switchTab(1) }
        binding.tabLocalBtn.setOnClickListener { switchTab(2) }
        binding.tabSyncBtn.setOnClickListener { switchTab(3) }
        updateTabStyles()
    }

    private fun switchTab(tabIndex: Int) {
        currentTab = tabIndex
        updateTabStyles()

        binding.tabSubtitlesLayout.isVisible = currentTab == 0
        binding.tabOnlineLayout.isVisible = currentTab == 1
        binding.tabLocalLayout.isVisible = currentTab == 2
        binding.tabSyncLayout.isVisible = currentTab == 3

        if (currentTab == 1 && currentOnlineResults.isEmpty()) {
            performOnlineSearch()
        }
    }

    private fun updateTabStyles() {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
        val selectedBgColor = ColorUtils.setAlphaComponent(primaryColor, 40)
        val unselectedTextColor = try {
            val themeColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            if (themeColor != 0) themeColor else ContextCompat.getColor(requireContext(), R.color.grey_60)
        } catch (_: Exception) {
            ContextCompat.getColor(requireContext(), R.color.grey_60)
        }

        // Subtitles Tab
        if (currentTab == 0) {
            binding.tabSubtitlesBtn.setBackgroundResource(R.drawable.badge_bg_rounded)
            binding.tabSubtitlesBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabSubtitlesBtn.setTextColor(primaryColor)
        } else {
            binding.tabSubtitlesBtn.setBackgroundResource(android.R.color.transparent)
            binding.tabSubtitlesBtn.backgroundTintList = null
            binding.tabSubtitlesBtn.setTextColor(unselectedTextColor)
        }

        // Online Tab
        if (currentTab == 1) {
            binding.tabOnlineBtn.setBackgroundResource(R.drawable.badge_bg_rounded)
            binding.tabOnlineBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabOnlineBtn.setTextColor(primaryColor)
        } else {
            binding.tabOnlineBtn.setBackgroundResource(android.R.color.transparent)
            binding.tabOnlineBtn.backgroundTintList = null
            binding.tabOnlineBtn.setTextColor(unselectedTextColor)
        }

        // Local Tab
        if (currentTab == 2) {
            binding.tabLocalBtn.setBackgroundResource(R.drawable.badge_bg_rounded)
            binding.tabLocalBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabLocalBtn.setTextColor(primaryColor)
        } else {
            binding.tabLocalBtn.setBackgroundResource(android.R.color.transparent)
            binding.tabLocalBtn.backgroundTintList = null
            binding.tabLocalBtn.setTextColor(unselectedTextColor)
        }

        // Sync Tab
        if (currentTab == 3) {
            binding.tabSyncBtn.setBackgroundResource(R.drawable.badge_bg_rounded)
            binding.tabSyncBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabSyncBtn.setTextColor(primaryColor)
        } else {
            binding.tabSyncBtn.setBackgroundResource(android.R.color.transparent)
            binding.tabSyncBtn.backgroundTintList = null
            binding.tabSyncBtn.setTextColor(unselectedTextColor)
        }
    }

    private fun setupOnlineSearchControls() {
        binding.onlineSearchActionBtn.setOnClickListener {
            performOnlineSearch()
        }

        binding.onlineRefreshBtn.setOnClickListener {
            val media = model.getMedia().value
            if (media != null && ::episode.isInitialized) {
                val episodeId = "${media.id}-${episode.number}"
                model.clearFetchedSubtitles(episodeId)
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = selectedEpisode.toIntOrNull() ?: 1
                model.clearFetchedSubtitles("${media.id}-$episodeNum")
                currentOnlineResults = emptyList()
                performOnlineSearch()
            }
        }

        binding.onlineSearchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performOnlineSearch()
                true
            } else false
        }

        // Provider chips
        binding.chipProviderAll.setOnClickListener {
            selectedProviderFilter = "All"
            uncheckOtherProviderChips(binding.chipProviderAll.id)
            filterAndDisplayOnlineResults()
        }
        binding.chipProviderWyzie.setOnClickListener {
            selectedProviderFilter = "Wyzie"
            uncheckOtherProviderChips(binding.chipProviderWyzie.id)
            filterAndDisplayOnlineResults()
        }
        binding.chipProviderStremio.setOnClickListener {
            selectedProviderFilter = "OpenSubtitles"
            uncheckOtherProviderChips(binding.chipProviderStremio.id)
            filterAndDisplayOnlineResults()
        }
        binding.chipProviderSubSource.setOnClickListener {
            selectedProviderFilter = "SubSource"
            uncheckOtherProviderChips(binding.chipProviderSubSource.id)
            filterAndDisplayOnlineResults()
        }

        // Language filter chips
        setupLanguageChips()
    }

    private fun uncheckOtherProviderChips(selectedId: Int) {
        binding.chipProviderAll.isChecked = selectedId == binding.chipProviderAll.id
        binding.chipProviderWyzie.isChecked = selectedId == binding.chipProviderWyzie.id
        binding.chipProviderStremio.isChecked = selectedId == binding.chipProviderStremio.id
        binding.chipProviderSubSource.isChecked = selectedId == binding.chipProviderSubSource.id
    }

    private fun setupLanguageChips() {
        val langChips = listOf(
            Pair(binding.chipLangEng, "English"),
            Pair(binding.chipLangSpa, "Spanish"),
            Pair(binding.chipLangFre, "French"),
            Pair(binding.chipLangGer, "German"),
            Pair(binding.chipLangPor, "Portuguese"),
            Pair(binding.chipLangAra, "Arabic")
        )

        for ((chip, lang) in langChips) {
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    langChips.filter { it.first.id != chip.id }.forEach { it.first.isChecked = false }
                    selectedLanguageFilter = lang
                } else {
                    selectedLanguageFilter = "All"
                }
                filterAndDisplayOnlineResults()
            }
        }
    }

    private fun setupLocalControls() {
        binding.importLocalSubCard.setOnClickListener {
            (requireActivity() as? ExoplayerView)?.requestLocalSubtitle()
            dismiss()
        }
    }

    private fun updateActiveSubtitleBadge() {
        val exoActivity = activity as? ExoplayerView
        val subManager = exoActivity?.subtitleManager
        val activeOnlineName = if (subManager?.activeSubtitleId != null &&
            subManager.activeSubtitleDisplayName?.contains("[Server]") != true &&
            subManager.activeSubtitleDisplayName?.startsWith("[Local]") != true
        ) {
            subManager.activeSubtitleDisplayName
        } else null

        val media = model.getMedia().value ?: return
        val savedEpSub = EpisodeSubtitleStore.getSavedSubtitle(media.id, episode.number)
        val onlineName = activeOnlineName ?: savedEpSub?.let { "${it.displayName} (${it.provider})" }
        val savedLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

        val badgeText = when {
            onlineName != null -> {
                getString(R.string.active_sub_prefix, onlineName)
            }
            savedLang == null || savedLang == "None" -> getString(R.string.status_sub_off)
            savedLang.startsWith("[Local]") -> {
                val clean = savedLang.removePrefix("[Local]").trim()
                getString(R.string.active_sub_prefix, "Local: $clean")
            }
            savedLang.startsWith("Embedded:") -> {
                val displayName = savedLang.removePrefix("Embedded:").trim()
                getString(R.string.active_sub_prefix, "Stream: $displayName")
            }
            savedLang.startsWith("Online:") -> {
                PrefManager.setCustomVal("subLang_${media.id}", null)
                getString(R.string.status_sub_off)
            }
            else -> getString(R.string.active_sub_prefix, "${mapLanguageCode(savedLang)} [Server]")
        }

        binding.activeSubBadge.text = badgeText
    }

    private fun loadSubtitlesTab(episodeId: String) {
        val media = model.getMedia().value ?: return
        val items = mutableListOf<Any>()
        val savedLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

        items.add(NoneSubtitleOption)

        val exoActivity = activity as? ExoplayerView
        val subManager = exoActivity?.subtitleManager
        val savedEpSub = EpisodeSubtitleStore.getSavedSubtitle(media.id, episode.number)
        resolveActiveExternalSub(subManager, savedEpSub, savedLang)?.let { items.add(it) }

        val currentExtractor = episode.extractors?.find { it.server.name == episode.selectedExtractor }
        if (currentExtractor != null && currentExtractor.subtitles.isNotEmpty()) {
            items.addAll(currentExtractor.subtitles)
        }

        items.addAll(collectEmbeddedSubtitles(exoActivity?.currentSubTrackGroups))
        items.addAll(collectOtherServersSubtitles(episode.extractors, episode.selectedExtractor))

        val hasAnySubtitles = items.size > 1
        binding.noServerSubsBanner.isVisible = !hasAnySubtitles
        binding.serverSubtitlesRecycler.adapter = SubtitleAdapter(items, TabType.SERVER)
    }

    private fun resolveActiveExternalSub(
        subManager: ani.dantotsu.media.anime.player.PlayerSubtitleManager?,
        savedEpSub: EpisodeSubtitle?,
        savedLang: String?
    ): Any? {
        val isOnlineActive = subManager?.activeSubtitleId != null &&
            subManager.activeSubtitleDisplayName?.contains("[Server]") != true &&
            subManager.activeSubtitleDisplayName?.startsWith("[Local]") != true

        return when {
            isOnlineActive -> {
                val displayName = subManager!!.activeSubtitleDisplayName ?: "Online Subtitle"
                val title = displayName.substringBefore(" (")
                val provider = displayName.substringAfter(" (", "Online").removeSuffix(")")
                val id = subManager.activeSubtitleId ?: ""
                ActiveOnlineSubtitle(title, provider, id)
            }
            savedEpSub != null -> {
                ActiveOnlineSubtitle(savedEpSub.displayName, savedEpSub.provider, savedEpSub.id)
            }
            savedLang?.startsWith("[Local]") == true -> {
                Subtitle(language = savedLang, url = "")
            }
            else -> null
        }
    }

    private fun collectEmbeddedSubtitles(trackGroups: List<androidx.media3.common.Tracks.Group>?): List<EmbeddedSubtitleTrack> {
        if (trackGroups.isNullOrEmpty()) return emptyList()
        val result = mutableListOf<EmbeddedSubtitleTrack>()
        val periodPrefix = Regex("^\\d+:")
        var flatIndex = 0
        trackGroups.forEach { group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val lang = format.language
                val label = format.label
                val cleanTrackId = format.id.orEmpty().replace(periodPrefix, "")
                val isExternal = cleanTrackId.startsWith("ext_sub_") ||
                    cleanTrackId.startsWith("shifted_sub_") ||
                    cleanTrackId.startsWith("local_sub_")
                if (lang != "none" && !isExternal) {
                    result.add(EmbeddedSubtitleTrack(group, trackIndex, lang, label, flatIndex++))
                }
            }
        }
        return result
    }

    private fun collectOtherServersSubtitles(extractors: List<Extractor>?, selectedExtractor: String?): List<OtherServerSubtitle> {
        if (extractors.isNullOrEmpty()) return emptyList()
        val result = mutableListOf<OtherServerSubtitle>()
        extractors.forEach { extractor ->
            if (extractor.server.name != selectedExtractor && extractor.subtitles.isNotEmpty()) {
                val langCounts = mutableMapOf<String, Int>()
                extractor.subtitles.forEach { sub ->
                    val count = (langCounts[sub.language] ?: 0) + 1
                    langCounts[sub.language] = count
                    val suffix = if (count > 1) " #$count" else ""
                    result.add(OtherServerSubtitle(extractor.server.name, sub, suffix))
                }
            }
        }
        return result
    }

    private fun loadLocalTab(episodeId: String) {
        val localSubs = model.getLocalSubtitles(episodeId)
        binding.noLocalSubsBanner.isVisible = localSubs.isEmpty()
        binding.localSubtitlesRecycler.adapter = SubtitleAdapter(localSubs, TabType.LOCAL)
    }

    private fun performOnlineSearch() {
        searchJob?.cancel()
        val media = model.getMedia().value ?: return

        binding.onlineLoadingLayout.isVisible = true
        binding.noOnlineSubsBanner.isVisible = false
        binding.onlineSubtitlesRecycler.isVisible = false

        val queryText = binding.onlineSearchEditText.text?.toString()?.trim() ?: ""

        searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imdbId = media.idIMDB ?: IdMappers.getImdbId(media.id)
                if (imdbId != null) {
                    media.idIMDB = imdbId
                }

                val actualEpNum = if (this@SubtitleDialogFragment::episode.isInitialized) {
                    MediaNameAdapter.findEpisodeNumber(episode.number)?.toInt()
                        ?: episode.number.filter { it.isDigit() }.toIntOrNull()
                        ?: 1
                } else 1

                val parsedEpFromQuery = if (queryText.isNotBlank()) {
                    MediaNameAdapter.findEpisodeNumber(queryText)?.toInt()
                } else null
                val targetEpisodeNum = parsedEpFromQuery ?: actualEpNum

                val seasonEpisode = EpisodeMapper.mapEpisode(
                    media,
                    targetEpisodeNum,
                    if (this@SubtitleDialogFragment::episode.isInitialized) episode else null
                )
                currentSeasonEpisode = seasonEpisode

                val onlineSubs = fetchOnlineSubtitles(imdbId, seasonEpisode, targetEpisodeNum, queryText, media)

                withContext(Dispatchers.Main) {
                    binding.onlineLoadingLayout.isVisible = false
                    currentOnlineResults = onlineSubs
                    model.saveFetchedSubtitles("${media.id}-${targetEpisodeNum}", onlineSubs)
                    filterAndDisplayOnlineResults()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.onlineLoadingLayout.isVisible = false
                    filterAndDisplayOnlineResults()
                }
            }
        }
    }

    private fun fetchOnlineSubtitles(
        imdbId: String?,
        seasonEpisode: EpisodeMapper.SeasonEpisode,
        targetEpisodeNum: Int,
        queryText: String,
        media: Media
    ): List<Any> {
        if (imdbId == null) return emptyList()
        val onlineSubs = mutableListOf<Any>()
        runCatching {
            onlineSubs.addAll(WyzieSubtitles.getWyzieSubtitles(imdbId, seasonEpisode.season, seasonEpisode.episode))
        }
        runCatching {
            onlineSubs.addAll(SubSourceSubtitles.getSubtitles(imdbId, targetEpisodeNum, seasonEpisode.season))
        }
        runCatching {
            onlineSubs.addAll(OpenSubtitlesRestApi.search(imdbId, targetEpisodeNum, seasonEpisode.season, queryText))
        }
        runCatching {
            val fetchedStremio = StremioSubtitles.getSubtitles(media, seasonEpisode.season, seasonEpisode.episode)
            val existingUrls = onlineSubs.mapNotNull {
                when (it) {
                    is WyzieSub -> it.url
                    is StremioSub -> it.url
                    else -> null
                }
            }.toSet()
            val uniqueStremio = fetchedStremio.filter { sub ->
                sub.url !in existingUrls && (!sub.id.contains(":1:1") || targetEpisodeNum == 1)
            }
            onlineSubs.addAll(uniqueStremio)
        }
        return onlineSubs
    }

    private fun filterAndDisplayOnlineResults() {
        var filtered = currentOnlineResults
        filtered = filterByProvider(filtered)
        filtered = filterByLanguage(filtered)
        val queryText = binding.onlineSearchEditText.text?.toString()?.trim() ?: ""
        if (queryText.isNotEmpty() && !queryText.startsWith(model.getMedia().value?.userPreferredName ?: "", ignoreCase = true)) {
            filtered = filterByQueryText(filtered, queryText)
        }

        binding.noOnlineSubsBanner.isVisible = filtered.isEmpty()
        binding.onlineSubtitlesRecycler.isVisible = filtered.isNotEmpty()
        binding.onlineSubtitlesRecycler.adapter = SubtitleAdapter(filtered, TabType.ONLINE)
    }

    private fun filterByProvider(items: List<Any>): List<Any> {
        return when (selectedProviderFilter) {
            "Wyzie" -> items.filterIsInstance<WyzieSub>()
            "OpenSubtitles", "Stremio" -> items.filter { it is StremioSub || it is OpenSubRestItem }
            "SubSource" -> items.filterIsInstance<SubSourceSub>()
            else -> items
        }
    }

    private fun filterByLanguage(items: List<Any>): List<Any> {
        if (selectedLanguageFilter == "All") return items
        return items.filter { item ->
            when (item) {
                is WyzieSub -> matchesLanguage(item.language, selectedLanguageFilter) ||
                        matchesLanguage(item.displayLabel, selectedLanguageFilter)
                is StremioSub -> matchesLanguage(item.lang, selectedLanguageFilter)
                is SubSourceSub -> matchesLanguage(item.lang, selectedLanguageFilter)
                is OpenSubRestItem -> matchesLanguage(item.language, selectedLanguageFilter)
                else -> true
            }
        }
    }

    private fun filterByQueryText(items: List<Any>, queryText: String): List<Any> {
        return items.filter { item ->
            when (item) {
                is WyzieSub -> item.displayLabel.contains(queryText, ignoreCase = true) ||
                        item.language.contains(queryText, ignoreCase = true) ||
                        item.format.contains(queryText, ignoreCase = true)
                is StremioSub -> item.lang.contains(queryText, ignoreCase = true) ||
                        mapLanguageCode(item.lang).contains(queryText, ignoreCase = true)
                is SubSourceSub -> item.releaseName.contains(queryText, ignoreCase = true) ||
                        item.lang.contains(queryText, ignoreCase = true)
                is OpenSubRestItem -> item.fileName.contains(queryText, ignoreCase = true) ||
                        item.language.contains(queryText, ignoreCase = true)
                else -> true
            }
        }
    }

    // ==========================================
    // TAB 4: SYNC CONTROLS
    // ==========================================
    private var audioCapturedTime: Long? = null
    private var subCapturedTime: Long? = null

    private fun setupSyncControls() {
        val subtitleManager = (activity as? ExoplayerView)?.subtitleManager
        setupSubDelayControls(subtitleManager)
        setupAudioDelayControls(subtitleManager)
        setupLiveSyncHelper(subtitleManager)
        updateSubDelayDisplay(subtitleManager)
        updateAudioDelayDisplay(subtitleManager)
    }

    private fun updateSubDelayDisplay(subtitleManager: ani.dantotsu.media.anime.player.PlayerSubtitleManager?) {
        val delay = subtitleManager?.subtitleDelayMs ?: 0L
        val seconds = delay / 1000.0
        val formatted = if (delay >= 0) String.format(Locale.ROOT, "+%.1fs", seconds) else String.format(Locale.ROOT, "%.1fs", seconds)
        binding.subDelayValueText.text = formatted
        when {
            delay == 0L -> {
                binding.subDelayStatusBadge.text = getString(R.string.sync_in_sync)
                binding.subDelayStatusBadge.backgroundTintList = ColorStateList.valueOf(requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            }
            delay > 0 -> {
                binding.subDelayStatusBadge.text = getString(R.string.sync_delayed)
                binding.subDelayStatusBadge.backgroundTintList = ColorStateList.valueOf(requireContext().getThemeColor(com.google.android.material.R.attr.colorTertiary))
            }
            else -> {
                binding.subDelayStatusBadge.text = getString(R.string.sync_earlier)
                binding.subDelayStatusBadge.backgroundTintList = ColorStateList.valueOf(requireContext().getThemeColor(com.google.android.material.R.attr.colorSecondary))
            }
        }
    }

    private fun updateAudioDelayDisplay(subtitleManager: ani.dantotsu.media.anime.player.PlayerSubtitleManager?) {
        val delay = subtitleManager?.audioDelayMs ?: 0L
        val seconds = delay / 1000.0
        val formatted = if (delay >= 0) String.format(Locale.ROOT, "+%.1fs", seconds) else String.format(Locale.ROOT, "%.1fs", seconds)
        binding.audioDelayValueText.text = formatted
        when {
            delay == 0L -> {
                binding.audioDelayStatusBadge.text = getString(R.string.sync_in_sync)
                binding.audioDelayStatusBadge.backgroundTintList = ColorStateList.valueOf(requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            }
            delay > 0 -> {
                binding.audioDelayStatusBadge.text = getString(R.string.sync_delayed)
                binding.audioDelayStatusBadge.backgroundTintList = ColorStateList.valueOf(requireContext().getThemeColor(com.google.android.material.R.attr.colorTertiary))
            }
            else -> {
                binding.audioDelayStatusBadge.text = getString(R.string.sync_earlier)
                binding.audioDelayStatusBadge.backgroundTintList = ColorStateList.valueOf(requireContext().getThemeColor(com.google.android.material.R.attr.colorSecondary))
            }
        }
    }

    private fun setupSubDelayControls(subtitleManager: ani.dantotsu.media.anime.player.PlayerSubtitleManager?) {
        binding.subDelayMinus500.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current - 500L)
            updateSubDelayDisplay(subtitleManager)
        }
        binding.subDelayMinus100.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current - 100L)
            updateSubDelayDisplay(subtitleManager)
        }
        binding.subDelayReset.setOnClickListener {
            subtitleManager?.setSubtitleDelay(0L)
            updateSubDelayDisplay(subtitleManager)
        }
        binding.subDelayPlus100.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current + 100L)
            updateSubDelayDisplay(subtitleManager)
        }
        binding.subDelayPlus500.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current + 500L)
            updateSubDelayDisplay(subtitleManager)
        }
    }

    private fun setupAudioDelayControls(subtitleManager: ani.dantotsu.media.anime.player.PlayerSubtitleManager?) {
        binding.audioDelayMinus500.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current - 500L)
            updateAudioDelayDisplay(subtitleManager)
        }
        binding.audioDelayMinus100.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current - 100L)
            updateAudioDelayDisplay(subtitleManager)
        }
        binding.audioDelayReset.setOnClickListener {
            subtitleManager?.setAudioDelay(0L)
            updateAudioDelayDisplay(subtitleManager)
        }
        binding.audioDelayPlus100.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current + 100L)
            updateAudioDelayDisplay(subtitleManager)
        }
        binding.audioDelayPlus500.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current + 500L)
            updateAudioDelayDisplay(subtitleManager)
        }
    }

    private fun formatLiveSyncTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val millis = (ms % 1000) / 100
        return String.format(Locale.ROOT, "%02d:%02d.%d", m, s, millis)
    }

    private fun setupLiveSyncHelper(subtitleManager: ani.dantotsu.media.anime.player.PlayerSubtitleManager?) {
        fun checkAndCalculateSync() {
            val a = audioCapturedTime
            val s = subCapturedTime
            if (a != null && s != null) {
                val offset = a - s
                subtitleManager?.setSubtitleDelay(offset)
                updateSubDelayDisplay(subtitleManager)
                val offsetStr = if (offset >= 0) "+${offset}ms" else "${offset}ms"
                binding.syncHelperStatusText.text = "Audio: ${formatLiveSyncTime(a)} | Sub: ${formatLiveSyncTime(s)}\nCalculated offset: $offsetStr (Applied!)"
                toast(getString(R.string.sync_applied_toast, offsetStr))
                audioCapturedTime = null
                subCapturedTime = null
            }
        }

        binding.syncHearingAudioBtn.setOnClickListener {
            val currentPos = (activity as? ExoplayerView)?.playerManager?.exoPlayer?.currentPosition ?: 0L
            audioCapturedTime = currentPos
            binding.syncHelperStatusText.text = getString(R.string.sync_audio_captured, formatLiveSyncTime(currentPos))
            checkAndCalculateSync()
        }

        binding.syncSeenSubtitleBtn.setOnClickListener {
            val currentPos = (activity as? ExoplayerView)?.playerManager?.exoPlayer?.currentPosition ?: 0L
            subCapturedTime = currentPos
            binding.syncHelperStatusText.text = getString(R.string.sync_sub_captured, formatLiveSyncTime(currentPos))
            checkAndCalculateSync()
        }
    }

    // ==========================================
    // RECYCLER VIEW ADAPTER
    // ==========================================
    inner class SubtitleAdapter(
        private val items: List<Any>,
        private val tabType: TabType
    ) : RecyclerView.Adapter<SubtitleAdapter.SubtitleViewHolder>() {

        inner class SubtitleViewHolder(val binding: ItemSubtitleCardBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtitleViewHolder {
            val binding = ItemSubtitleCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SubtitleViewHolder(binding)
        }

        private fun resolveNormalBorderColor(): Int {
            return try {
                val themeColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
                if (themeColor != 0) themeColor else ContextCompat.getColor(requireContext(), R.color.grey_20)
            } catch (_: Exception) {
                ContextCompat.getColor(requireContext(), R.color.grey_20)
            }
        }

        private fun resolveEffectiveOnlineId(exoActivity: ExoplayerView?, media: Media?): String? {
            val subManager = exoActivity?.subtitleManager
            val isOnlineActive = subManager?.activeSubtitleId != null &&
                subManager.activeSubtitleDisplayName?.contains("[Server]") != true &&
                subManager.activeSubtitleDisplayName?.startsWith("[Local]") != true
            val activeOnlineId = if (isOnlineActive) subManager?.activeSubtitleId else null
            val savedEpSub = media?.let { EpisodeSubtitleStore.getSavedSubtitle(it.id, episode.number) }
            return activeOnlineId ?: savedEpSub?.id
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
            val itemBinding = holder.binding
            val item = items[position]

            val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            val highlightColor = ColorUtils.setAlphaComponent(primaryColor, 35)
            val borderSelectedColor = ColorUtils.setAlphaComponent(primaryColor, 180)
            val normalBorderColor = resolveNormalBorderColor()
            val exoActivity = activity as? ExoplayerView

            val media = model.getMedia().value
            val mediaId = media?.id ?: 0
            val effectiveOnlineId = resolveEffectiveOnlineId(exoActivity, media)
            val savedLang: String? = PrefManager.getNullableCustomVal("subLang_${mediaId}", null, String::class.java)

            itemBinding.formatBadge.isVisible = false
            itemBinding.hiBadge.isVisible = false
            itemBinding.deleteButton.isVisible = false
            itemBinding.selectedCheckmark.isVisible = false
            itemBinding.subtitleCardRoot.setCardBackgroundColor(Color.TRANSPARENT)
            itemBinding.subtitleCardRoot.strokeColor = normalBorderColor

            when (item) {
                is NoneSubtitleOption -> bindNoneOption(itemBinding, effectiveOnlineId, savedLang, mediaId, exoActivity, highlightColor, borderSelectedColor)
                is ActiveOnlineSubtitle -> bindActiveOnlineSub(itemBinding, item, effectiveOnlineId, highlightColor, borderSelectedColor)
                is Subtitle -> bindSubtitleItem(itemBinding, item, effectiveOnlineId, savedLang, mediaId, exoActivity, highlightColor, borderSelectedColor)
                is OtherServerSubtitle -> bindOtherServerSub(itemBinding, item, exoActivity, highlightColor, borderSelectedColor)
                is EmbeddedSubtitleTrack -> bindEmbeddedTrack(itemBinding, item, exoActivity, highlightColor, borderSelectedColor)
                is WyzieSub -> bindWyzieSub(itemBinding, item, effectiveOnlineId, mediaId, exoActivity, highlightColor, borderSelectedColor)
                is SubSourceSub -> bindSubSourceSub(itemBinding, item, effectiveOnlineId, mediaId, exoActivity, highlightColor, borderSelectedColor)
                is OpenSubRestItem -> bindOpenSubRest(itemBinding, item, effectiveOnlineId, mediaId, exoActivity, highlightColor, borderSelectedColor)
                is StremioSub -> bindStremioSub(itemBinding, item, effectiveOnlineId, mediaId, exoActivity, highlightColor, borderSelectedColor)
            }
        }

        private fun bindNoneOption(
            itemBinding: ItemSubtitleCardBinding,
            effectiveOnlineId: String?,
            savedLang: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_off_24)
itemBinding.subtitleTitle.text = getString(R.string.subtitles_off)
itemBinding.subtitleDetails.text = getString(R.string.subtitles_off_desc)

val isSelected = effectiveOnlineId == null && (savedLang == "None" || (savedLang == null && (episode.selectedSubtitle == null || episode.selectedSubtitle == -1)))
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    episode.selectedSubtitle = null
    model.setEpisode(episode, "Subtitle")
    PrefManager.setCustomVal("subLang_${mediaId}", "None")
    exoActivity?.subtitleManager?.clearOnlineSubtitle(mediaId, episode.number)
    exoActivity?.playerManager?.exoPlayer?.let { p ->
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }
    updateActiveSubtitleBadge()
    dismiss()
}
                }
        }

        private fun bindActiveOnlineSub(
            itemBinding: ItemSubtitleCardBinding,
            item: ActiveOnlineSubtitle,
            effectiveOnlineId: String?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
itemBinding.subtitleTitle.text = item.title
itemBinding.subtitleDetails.text = "${item.provider} • Active Online Subtitle"

val ext = item.format ?: "SRT"
itemBinding.formatBadge.text = ext
itemBinding.formatBadge.isVisible = true

val isSelected = effectiveOnlineId != null
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    updateActiveSubtitleBadge()
    dismiss()
}
        }

        private fun bindSubtitleItem(
            itemBinding: ItemSubtitleCardBinding,
            item: Subtitle,
            effectiveOnlineId: String?,
            savedLang: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
            if (item.language.startsWith("[Local]")) {
                bindLocalStoredSubtitle(itemBinding, item, effectiveOnlineId, savedLang, mediaId, exoActivity, highlightColor, borderSelectedColor)
            } else {
                bindServerTrackSubtitle(itemBinding, item, effectiveOnlineId, savedLang, mediaId, exoActivity, highlightColor, borderSelectedColor)
            }
        }

        private fun bindLocalStoredSubtitle(
            itemBinding: ItemSubtitleCardBinding,
            item: Subtitle,
            effectiveOnlineId: String?,
            savedLang: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
            val fileName = item.language.removePrefix("[Local]").trim()
            itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_folder_24)
            itemBinding.subtitleTitle.text = fileName
            itemBinding.subtitleDetails.text = "Local Storage Subtitle"

            val ext = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
            if (ext.isNotBlank()) {
                itemBinding.formatBadge.text = ext
                itemBinding.formatBadge.isVisible = true
            }

            val isSelected = effectiveOnlineId == null && (savedLang == item.language || (savedLang?.startsWith("[Local]") == true && savedLang.contains(fileName)))
            if (isSelected) {
                itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                itemBinding.selectedCheckmark.isVisible = true
            }

            itemBinding.deleteButton.isVisible = tabType == TabType.LOCAL
            itemBinding.deleteButton.setOnClickListener {
                val epId = "${mediaId}-${episode.number}"
                model.removeLocalSubtitle(epId, item)
                loadLocalTab(epId)
                if (savedLang == item.language) {
                    PrefManager.setCustomVal("subLang_${mediaId}", "None")
                    updateActiveSubtitleBadge()
                }
            }

            itemBinding.subtitleCardRoot.setOnClickListener {
                episode.selectedSubtitle = null
                PrefManager.setCustomVal("subLang_${mediaId}", item.language)
                exoActivity?.subtitleManager?.clearOnlineSubtitle(mediaId, episode.number)
                if (item.file.url.isNotBlank()) {
                    exoActivity?.reApplyLocalSubtitle(item.file.url)
                }
                updateActiveSubtitleBadge()
                dismiss()
            }
        }

        private fun bindServerTrackSubtitle(
            itemBinding: ItemSubtitleCardBinding,
            item: Subtitle,
            effectiveOnlineId: String?,
            savedLang: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
            val langName = mapLanguageCode(item.language)
            itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_24)
            itemBinding.subtitleTitle.text = langName
            val currentServerName = episode.selectedExtractor ?: getString(R.string.current_server_subs)
            itemBinding.subtitleDetails.text = "$currentServerName • ${item.language}"

            val formatTag = when (item.type) {
                SubtitleType.ASS -> "ASS"
                SubtitleType.VTT -> "VTT"
                SubtitleType.SRT -> "SRT"
                else -> null
            }
            if (formatTag != null) {
                itemBinding.formatBadge.text = formatTag
                itemBinding.formatBadge.isVisible = true
            }

            val isSelected = effectiveOnlineId == null && (savedLang == item.language || (savedLang == null && episode.selectedSubtitle != null && episode.selectedSubtitle == episode.subtitles?.indexOf(item)))
            if (isSelected) {
                itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                itemBinding.selectedCheckmark.isVisible = true
            }

            itemBinding.subtitleCardRoot.setOnClickListener {
                handleServerTrackSelection(item, mediaId, exoActivity)
            }
        }

        private fun handleServerTrackSelection(
            item: Subtitle,
            mediaId: Int,
            exoActivity: ExoplayerView?
        ) {
            val subIndex = episode.subtitles?.indexOf(item) ?: -1
            if (subIndex != -1) {
                episode.selectedSubtitle = subIndex
                model.setEpisode(episode, "Subtitle")
                PrefManager.setCustomVal("subLang_${mediaId}", item.language)
                exoActivity?.subtitleManager?.clearOnlineSubtitle(mediaId, episode.number)

                exoActivity?.playerManager?.exoPlayer?.let { p ->
                    val tracks = p.currentTracks
                    val textTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    val matchedTrack = textTracks.firstOrNull { group ->
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            if (f.language.equals(item.language, ignoreCase = true) || f.label?.contains(item.language, ignoreCase = true) == true) return@firstOrNull true
                        }
                        false
                    }
                    if (matchedTrack != null) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setPreferredTextLanguage(item.language)
                            .build()
                    } else if (item.file.url.isNotBlank()) {
                        exoActivity?.reApplySubtitle(item.file.url, item.type.toMimeType())
                    }
                }
            } else if (item.file.url.isNotBlank()) {
                PrefManager.setCustomVal("subLang_${mediaId}", item.language)
                exoActivity?.subtitleManager?.clearOnlineSubtitle(mediaId, episode.number)
                exoActivity?.reApplySubtitle(item.file.url, item.type.toMimeType())
            }
            updateActiveSubtitleBadge()
            dismiss()
        }
        private fun bindOtherServerSub(
            itemBinding: ItemSubtitleCardBinding,
            item: OtherServerSubtitle,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
val langName = mapLanguageCode(item.subtitle.language)
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_24)
itemBinding.subtitleTitle.text = "$langName${item.suffix} [${item.serverName}]"
itemBinding.subtitleDetails.text = "${item.serverName} • ${item.subtitle.language}${item.suffix}"

val uniqueKey = "${item.subtitle.language}${item.suffix} [${item.serverName}]"
val isSelected = effectiveOnlineId == null && savedLang == uniqueKey
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    episode.selectedSubtitle = null
    PrefManager.setCustomVal("subLang_${mediaId}", uniqueKey)
    exoActivity?.subtitleManager?.clearOnlineSubtitle(mediaId, episode.number)
    exoActivity?.playerManager?.exoPlayer?.let { p ->
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }
    exoActivity?.reApplyLocalSubtitle(item.subtitle.file.url)
    updateActiveSubtitleBadge()
    dismiss()
}
                }

        }

        private fun bindEmbeddedTrack(
            itemBinding: ItemSubtitleCardBinding,
            item: EmbeddedSubtitleTrack,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
val langName = item.language?.let { mapLanguageCode(it) } ?: "Stream Track ${item.flatIndex + 1}"
val accurateName = when {
    item.label.isNullOrBlank() -> "$langName (Track ${item.flatIndex + 1})"
    item.label.contains(langName, ignoreCase = true) -> item.label.replaceFirstChar { it.uppercase() }
    else -> "$langName (${item.label.replaceFirstChar { it.uppercase() }})"
}

itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_24)
itemBinding.subtitleTitle.text = accurateName
itemBinding.subtitleDetails.text = "${getString(R.string.embedded_stream_subs)} • ${item.label ?: "Track ${item.flatIndex + 1}"}"

val uniqueKey = "Embedded:$accurateName"
val isSelected = effectiveOnlineId == null && (if (savedLang != null) savedLang == uniqueKey else item.group.isTrackSelected(item.trackIndex))
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    episode.selectedSubtitle = null
    PrefManager.setCustomVal("subLang_${mediaId}", uniqueKey)
    exoActivity?.subtitleManager?.clearOnlineSubtitle(mediaId, episode.number)
    exoActivity?.playerManager?.exoPlayer?.let { p ->
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }
    exoActivity?.onSetTrackGroupOverride(
        item.group,
        C.TRACK_TYPE_TEXT,
        item.trackIndex
    )
    updateActiveSubtitleBadge()
    dismiss()
}
                }

        }

        private fun bindWyzieSub(
            itemBinding: ItemSubtitleCardBinding,
            item: WyzieSub,
            effectiveOnlineId: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
val langName = mapLanguageCode(item.language)
val displayTitle = item.displayLabel.ifBlank { langName }
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
itemBinding.subtitleTitle.text = displayTitle

val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""
itemBinding.subtitleDetails.text = "Wyzie • ${if (seInfo.isNotEmpty()) "$seInfo • " else ""}$langName"

itemBinding.formatBadge.text = item.format.uppercase(Locale.ROOT)
itemBinding.formatBadge.isVisible = true

val isSelected = effectiveOnlineId != null && effectiveOnlineId == item.url
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    val exoActivity = requireActivity() as? ExoplayerView
    if (exoActivity != null) {
        episode.selectedSubtitle = -1
        exoActivity.applyWyzieSubtitle(item)
    }
    updateActiveSubtitleBadge()
    dismiss()
}
                }

        }

        private fun bindSubSourceSub(
            itemBinding: ItemSubtitleCardBinding,
            item: SubSourceSub,
            effectiveOnlineId: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
val langName = mapLanguageCode(item.lang)
val releaseTitle = item.releaseName.ifBlank { langName }
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
itemBinding.subtitleTitle.text = releaseTitle
itemBinding.subtitleDetails.text = "SubSource • $langName"
itemBinding.formatBadge.text = "SRT"
itemBinding.formatBadge.isVisible = true

val isSelected = effectiveOnlineId != null && effectiveOnlineId == item.id
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    val exoActivity = requireActivity() as? ExoplayerView
    if (exoActivity != null) {
        episode.selectedSubtitle = -1
        exoActivity.applySubSourceSubtitle(item)
    }
    updateActiveSubtitleBadge()
    dismiss()
}
                }

        }

        private fun bindOpenSubRest(
            itemBinding: ItemSubtitleCardBinding,
            item: OpenSubRestItem,
            effectiveOnlineId: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
val langName = mapLanguageCode(item.language)
val displayTitle = item.fileName.ifBlank { langName }
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
itemBinding.subtitleTitle.text = displayTitle
itemBinding.subtitleDetails.text = "OpenSubtitles • $langName"

val ext = item.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
if (ext.isNotBlank()) {
    itemBinding.formatBadge.text = ext
    itemBinding.formatBadge.isVisible = true
}

val isSelected = effectiveOnlineId != null && effectiveOnlineId == item.fileId.toString()
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    val exoActivity = requireActivity() as? ExoplayerView
    if (exoActivity != null) {
        episode.selectedSubtitle = -1
        exoActivity.applyOpenSubRestSubtitle(item)
    }
    updateActiveSubtitleBadge()
    dismiss()
}
                }

        }

        private fun bindStremioSub(
            itemBinding: ItemSubtitleCardBinding,
            item: StremioSub,
            effectiveOnlineId: String?,
            mediaId: Int,
            exoActivity: ExoplayerView?,
            highlightColor: Int,
            borderSelectedColor: Int
        ) {
val langName = mapLanguageCode(item.lang)
itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
itemBinding.subtitleTitle.text = langName

val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""
itemBinding.subtitleDetails.text = "OpenSubtitles • ${if (seInfo.isNotEmpty()) "$seInfo • " else ""}${item.lang}"

val isSelected = effectiveOnlineId != null && (effectiveOnlineId == item.id || effectiveOnlineId == item.url)
if (isSelected) {
    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
    itemBinding.selectedCheckmark.isVisible = true
}

itemBinding.subtitleCardRoot.setOnClickListener {
    val exoActivity = requireActivity() as? ExoplayerView
    if (exoActivity != null) {
        episode.selectedSubtitle = -1
        exoActivity.applyOnlineSubtitle(item, langName, "OpenSubtitles")
    }
    updateActiveSubtitleBadge()
    dismiss()
}
                }
            }
        }
        }


        override fun getItemCount(): Int = items.size
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        _binding = null
        super.onDestroyView()
    }
}
