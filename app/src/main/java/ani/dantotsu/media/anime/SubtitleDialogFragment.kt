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
    data class OtherServerSubtitle(val serverName: String, val subtitle: Subtitle)
    data class EmbeddedSubtitleTrack(val group: Tracks.Group, val trackIndex: Int, val language: String?, val label: String?)
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

    private fun mapLanguageCode(isoCode: String): String = when (isoCode.lowercase(Locale.ROOT)) {
        "eng", "en", "en-us", "en-gb" -> "English"
        "spa", "es", "es-es", "es-419", "es-la" -> "Spanish"
        "fra", "fr", "fr-fr" -> "French"
        "deu", "de", "de-de" -> "German"
        "ita", "it", "it-it" -> "Italian"
        "por", "pt", "pt-br", "pt-pt" -> "Portuguese"
        "rus", "ru", "ru-ru" -> "Russian"
        "jpn", "ja", "ja-jp" -> "Japanese"
        "zho", "chi", "zh", "zh-cn", "zh-tw" -> "Chinese"
        "ara", "ar", "ar-me", "ar-sa" -> "Arabic"
        "hin", "hi" -> "Hindi"
        "kor", "ko", "ko-kr" -> "Korean"
        "pol", "pl", "pl-pl" -> "Polish"
        "tur", "tr", "tr-tr" -> "Turkish"
        "hun", "hu" -> "Hungarian"
        "ron", "ro", "ro-ro" -> "Romanian"
        "ell", "el", "el-gr" -> "Greek"
        "cze", "cs" -> "Czech"
        "swe", "sv", "sv-se" -> "Swedish"
        "dan", "da" -> "Danish"
        "fin", "fi" -> "Finnish"
        "nor", "no" -> "Norwegian"
        "nld", "nl" -> "Dutch"
        "tha", "th" -> "Thai"
        "vie", "vi" -> "Vietnamese"
        "ind", "id" -> "Indonesian"
        "ukr", "uk", "uk-uk" -> "Ukrainian"
        "heb", "he", "he-il" -> "Hebrew"
        "bul", "bg" -> "Bulgarian"
        "hrv", "hr" -> "Croatian"
        "slk", "sk" -> "Slovak"
        "slv", "sl" -> "Slovenian"
        "mon", "mn" -> "Mongolian"
        "srp", "sr" -> "Serbian"
        else -> isoCode
    }

    private fun matchesLanguage(langText: String, filterLanguage: String): Boolean {
        if (filterLanguage.equals("All", ignoreCase = true)) return true
        val mapped = mapLanguageCode(langText)
        if (mapped.contains(filterLanguage, ignoreCase = true)) return true
        if (langText.contains(filterLanguage, ignoreCase = true)) return true

        val matchCodes = when (filterLanguage.lowercase(Locale.ROOT)) {
            "english" -> listOf("eng", "en", "en-us", "en-gb")
            "spanish" -> listOf("spa", "es", "es-es", "es-419", "es-la")
            "french" -> listOf("fra", "fre", "fr", "fr-fr")
            "german" -> listOf("deu", "ger", "de", "de-de")
            "portuguese" -> listOf("por", "pt", "pt-br", "pt-pt")
            "arabic" -> listOf("ara", "ar", "ar-me", "ar-sa")
            "russian" -> listOf("rus", "ru", "ru-ru")
            "japanese" -> listOf("jpn", "ja", "ja-jp")
            "chinese" -> listOf("zho", "chi", "zh", "zh-cn", "zh-tw")
            "hindi" -> listOf("hin", "hi")
            "korean" -> listOf("kor", "ko", "ko-kr")
            "polish" -> listOf("pol", "pl")
            "turkish" -> listOf("tur", "tr")
            "indonesian" -> listOf("ind", "id")
            "vietnamese" -> listOf("vie", "vi")
            "thai" -> listOf("tha", "th")
            "italian" -> listOf("ita", "it")
            else -> listOf(filterLanguage.lowercase(Locale.ROOT))
        }
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
            val anime = media?.anime ?: return@observe
            val eps = anime.episodes ?: return@observe
            val selectedEpisode = anime.selectedEpisode ?: "1"
            val ep = eps.getEpisode(selectedEpisode) ?: return@observe
            episode = ep

            val actualEpisodeNum = MediaNameAdapter.findEpisodeNumber(ep.number)?.toInt()
                ?: ep.number.filter { it.isDigit() }.toIntOrNull()
                ?: selectedEpisode.toIntOrNull()
                ?: 1
            val episodeId = "${media.id}-${episode.number}"

            // Pre-fill search input
            val animeTitleText = media.userPreferredName
            if (binding.onlineSearchEditText.text.isNullOrBlank()) {
                binding.onlineSearchEditText.setText("$animeTitleText Episode $actualEpisodeNum")
            }

            updateActiveSubtitleBadge()
            loadSubtitlesTab(episodeId)
            loadLocalTab(episodeId)

            // Online cached results check
            val cachedOnline = model.getFetchedSubtitles(episodeId)
            if (cachedOnline != null && cachedOnline.isNotEmpty()) {
                currentOnlineResults = cachedOnline
                filterAndDisplayOnlineResults()
            }

            // Background metadata & IMDB mapping
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (media.idIMDB == null) {
                    try {
                        val imdb = IdMappers.getImdbId(media.id)
                        if (imdb != null) {
                            media.idIMDB = imdb
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                try {
                    currentSeasonEpisode = EpisodeMapper.mapEpisode(media, actualEpisodeNum, ep)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
        val media = model.getMedia().value ?: return
        val savedLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

        val badgeText = when {
            savedLang == null || savedLang == "None" -> getString(R.string.status_sub_off)
            savedLang.startsWith("Online:") -> {
                val raw = savedLang.removePrefix("Online:").trim()
                val displayTitle = if (raw.contains("•")) {
                    raw.substringBefore("•").trim()
                } else if (raw.startsWith("http")) {
                    "Online Subtitle"
                } else {
                    raw
                }
                val provider = if (raw.contains("•")) {
                    val parts = raw.split("•").map { it.trim() }
                    if (parts.size >= 2) parts[1] else "Online"
                } else "Online"
                getString(R.string.active_sub_prefix, "$displayTitle ($provider)")
            }
            savedLang.startsWith("[Local]") -> {
                val clean = savedLang.removePrefix("[Local]").trim()
                getString(R.string.active_sub_prefix, "Local: $clean")
            }
            savedLang.startsWith("Embedded:") -> {
                val trackName = savedLang.removePrefix("Embedded:").trim()
                getString(R.string.active_sub_prefix, "Stream: $trackName")
            }
            else -> getString(R.string.active_sub_prefix, "${mapLanguageCode(savedLang)} [Server]")
        }

        binding.activeSubBadge.text = badgeText
    }

    private fun loadSubtitlesTab(episodeId: String) {
        val media = model.getMedia().value ?: return
        val items = mutableListOf<Any>()
        val savedLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

        // 1. None Option
        items.add(NoneSubtitleOption)

        // 2. Active External Subtitle (Online or Local) in Tab 0
        if (savedLang?.startsWith("Online:") == true) {
            val raw = savedLang.removePrefix("Online:").trim()
            val displayTitle = if (raw.contains("•")) raw.substringBefore("•").trim() else raw
            val provider = if (raw.contains("•")) {
                val parts = raw.split("•").map { it.trim() }
                if (parts.size >= 2) parts[1] else "Online"
            } else "Online"
            val idOrUrl = if (raw.contains("•")) raw.substringAfterLast("•").trim() else raw
            items.add(ActiveOnlineSubtitle(displayTitle, provider, idOrUrl))
        } else if (savedLang?.startsWith("[Local]") == true) {
            items.add(Subtitle(language = savedLang, url = ""))
        }

        // 3. Current Server's Extractor Subtitles
        val currentExtractor = episode.extractors?.find { it.server.name == episode.selectedExtractor }
        if (currentExtractor != null && currentExtractor.subtitles.isNotEmpty()) {
            items.addAll(currentExtractor.subtitles)
        }

        // 4. Embedded Player Tracks
        val exoActivity = activity as? ExoplayerView
        val trackGroups = exoActivity?.currentSubTrackGroups
        if (trackGroups != null && trackGroups.isNotEmpty()) {
            trackGroups.forEachIndexed { _, group ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val lang = format.language
                    val label = format.label
                    val trackId = format.id.orEmpty()
                    if (lang != "none" && !trackId.startsWith("shifted_sub_") && !trackId.startsWith("local_sub_")) {
                        items.add(EmbeddedSubtitleTrack(group, trackIndex, lang, label))
                    }
                }
            }
        }

        // 5. Other Servers' Subtitles for this Episode (Cross-server extraction)
        episode.extractors?.forEach { extractor ->
            if (extractor.server.name != episode.selectedExtractor && extractor.subtitles.isNotEmpty()) {
                extractor.subtitles.forEach { sub ->
                    items.add(OtherServerSubtitle(extractor.server.name, sub))
                }
            }
        }

        val hasAnySubtitles = items.size > 1
        binding.noServerSubsBanner.isVisible = !hasAnySubtitles
        binding.serverSubtitlesRecycler.adapter = SubtitleAdapter(items, TabType.SERVER)
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

                val onlineSubs = mutableListOf<Any>()
                if (imdbId != null) {
                    // 1. Fetch Wyzie Subtitles
                    try {
                        val wyzieSubs = WyzieSubtitles.getWyzieSubtitles(imdbId, seasonEpisode.season, seasonEpisode.episode)
                        onlineSubs.addAll(wyzieSubs)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 2. Fetch SubSource Subtitles (from AnymeX / CloudStream)
                    try {
                        val subSourceSubs = SubSourceSubtitles.getSubtitles(imdbId, targetEpisodeNum, seasonEpisode.season)
                        onlineSubs.addAll(subSourceSubs)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 3. Fetch OpenSubtitles REST API (from CloudStream)
                    try {
                        val openSubRest = OpenSubtitlesRestApi.search(imdbId, targetEpisodeNum, seasonEpisode.season, queryText)
                        onlineSubs.addAll(openSubRest)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 4. Fetch Stremio / OpenSubtitles (filter out mismatched S1E1 files)
                    try {
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
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

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

    private fun filterAndDisplayOnlineResults() {
        var filtered = currentOnlineResults

        // Provider Filter
        if (selectedProviderFilter == "Wyzie") {
            filtered = filtered.filterIsInstance<WyzieSub>()
        } else if (selectedProviderFilter == "OpenSubtitles" || selectedProviderFilter == "Stremio") {
            filtered = filtered.filter { it is StremioSub || it is OpenSubRestItem }
        } else if (selectedProviderFilter == "SubSource") {
            filtered = filtered.filterIsInstance<SubSourceSub>()
        }

        // Language Filter
        if (selectedLanguageFilter != "All") {
            filtered = filtered.filter { item ->
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

        // Text Search Filter if user entered custom text
        val queryText = binding.onlineSearchEditText.text?.toString()?.trim() ?: ""
        if (queryText.isNotEmpty() && !queryText.startsWith(model.getMedia().value?.userPreferredName ?: "", ignoreCase = true)) {
            filtered = filtered.filter { item ->
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

        binding.noOnlineSubsBanner.isVisible = filtered.isEmpty()
        binding.onlineSubtitlesRecycler.isVisible = filtered.isNotEmpty()
        binding.onlineSubtitlesRecycler.adapter = SubtitleAdapter(filtered, TabType.ONLINE)
    }

    // ==========================================
    // TAB 4: SYNC CONTROLS
    // ==========================================
    private var audioCapturedTime: Long? = null
    private var subCapturedTime: Long? = null

    private fun setupSyncControls() {
        val subtitleManager = (activity as? ExoplayerView)?.subtitleManager

        fun updateSubDelayDisplay() {
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

        fun updateAudioDelayDisplay() {
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

        // Subtitle Delay chips
        binding.subDelayMinus500.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current - 500L)
            updateSubDelayDisplay()
        }
        binding.subDelayMinus100.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current - 100L)
            updateSubDelayDisplay()
        }
        binding.subDelayReset.setOnClickListener {
            subtitleManager?.setSubtitleDelay(0L)
            updateSubDelayDisplay()
        }
        binding.subDelayPlus100.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current + 100L)
            updateSubDelayDisplay()
        }
        binding.subDelayPlus500.setOnClickListener {
            val current = subtitleManager?.subtitleDelayMs ?: 0L
            subtitleManager?.setSubtitleDelay(current + 500L)
            updateSubDelayDisplay()
        }

        // Audio Delay chips
        binding.audioDelayMinus500.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current - 500L)
            updateAudioDelayDisplay()
        }
        binding.audioDelayMinus100.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current - 100L)
            updateAudioDelayDisplay()
        }
        binding.audioDelayReset.setOnClickListener {
            subtitleManager?.setAudioDelay(0L)
            updateAudioDelayDisplay()
        }
        binding.audioDelayPlus100.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current + 100L)
            updateAudioDelayDisplay()
        }
        binding.audioDelayPlus500.setOnClickListener {
            val current = subtitleManager?.audioDelayMs ?: 0L
            subtitleManager?.setAudioDelay(current + 500L)
            updateAudioDelayDisplay()
        }

        // Live Sync Helper
        fun formatTime(ms: Long): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            val millis = (ms % 1000) / 100
            return String.format(Locale.ROOT, "%02d:%02d.%d", m, s, millis)
        }

        fun checkAndCalculateSync() {
            val a = audioCapturedTime
            val s = subCapturedTime
            if (a != null && s != null) {
                val offset = a - s
                subtitleManager?.setSubtitleDelay(offset)
                updateSubDelayDisplay()
                val offsetStr = if (offset >= 0) "+${offset}ms" else "${offset}ms"
                binding.syncHelperStatusText.text = "Audio: ${formatTime(a)} | Sub: ${formatTime(s)}\nCalculated offset: $offsetStr (Applied!)"
                toast(getString(R.string.sync_applied_toast, offsetStr))
                audioCapturedTime = null
                subCapturedTime = null
            }
        }

        binding.syncHearingAudioBtn.setOnClickListener {
            val currentPos = (activity as? ExoplayerView)?.playerManager?.exoPlayer?.currentPosition ?: 0L
            audioCapturedTime = currentPos
            binding.syncHelperStatusText.text = getString(R.string.sync_audio_captured, formatTime(currentPos))
            checkAndCalculateSync()
        }

        binding.syncSeenSubtitleBtn.setOnClickListener {
            val currentPos = (activity as? ExoplayerView)?.playerManager?.exoPlayer?.currentPosition ?: 0L
            subCapturedTime = currentPos
            binding.syncHelperStatusText.text = getString(R.string.sync_sub_captured, formatTime(currentPos))
            checkAndCalculateSync()
        }

        updateSubDelayDisplay()
        updateAudioDelayDisplay()
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

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
            val itemBinding = holder.binding
            val item = items[position]

            val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            val highlightColor = ColorUtils.setAlphaComponent(primaryColor, 35)
            val borderSelectedColor = ColorUtils.setAlphaComponent(primaryColor, 180)
            val normalBorderColor = try {
                val themeColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
                if (themeColor != 0) themeColor else ContextCompat.getColor(requireContext(), R.color.grey_20)
            } catch (_: Exception) {
                ContextCompat.getColor(requireContext(), R.color.grey_20)
            }
            val media = model.getMedia().value
            val mediaId = media?.id ?: 0
            val savedLang: String? = PrefManager.getNullableCustomVal("subLang_${mediaId}", null, String::class.java)

            // Reset visibility
            itemBinding.formatBadge.isVisible = false
            itemBinding.hiBadge.isVisible = false
            itemBinding.deleteButton.isVisible = false
            itemBinding.selectedCheckmark.isVisible = false
            itemBinding.subtitleCardRoot.setCardBackgroundColor(Color.TRANSPARENT)
            itemBinding.subtitleCardRoot.strokeColor = normalBorderColor

            when (item) {
                // --- 1. NONE / OFF OPTION ---
                is NoneSubtitleOption -> {
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_off_24)
                    itemBinding.subtitleTitle.text = getString(R.string.subtitles_off)
                    itemBinding.subtitleDetails.text = getString(R.string.subtitles_off_desc)

                    val isSelected = savedLang == "None" || (savedLang == null && episode.selectedSubtitle == null)
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        episode.selectedSubtitle = null
                        model.setEpisode(episode, "Subtitle")
                        PrefManager.setCustomVal("subLang_${mediaId}", "None")
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                 // --- 2. CURRENT SERVER SUBTITLES ---
                is ActiveOnlineSubtitle -> {
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
                    itemBinding.subtitleTitle.text = item.title
                    itemBinding.subtitleDetails.text = "${item.provider} • Active Online Subtitle"

                    val ext = item.format ?: "SRT"
                    itemBinding.formatBadge.text = ext
                    itemBinding.formatBadge.isVisible = true

                    itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                    itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                    itemBinding.selectedCheckmark.isVisible = true

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                is Subtitle -> {
                    if (item.language.startsWith("[Local]")) {
                        // Local Subtitle from storage
                        val fileName = item.language.removePrefix("[Local]").trim()
                        itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_folder_24)
                        itemBinding.subtitleTitle.text = fileName
                        itemBinding.subtitleDetails.text = "Local Storage Subtitle"
                        
                        // Format Badge
                        val ext = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
                        if (ext.isNotBlank()) {
                            itemBinding.formatBadge.text = ext
                            itemBinding.formatBadge.isVisible = true
                        }

                        val isSelected = savedLang == item.language || (savedLang?.startsWith("[Local]") == true && savedLang.contains(fileName))
                        if (isSelected) {
                            itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                            itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                            itemBinding.selectedCheckmark.isVisible = true
                        }

                        // Delete button (only visible in Local tab)
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
                            PrefManager.setCustomVal("subLang_${mediaId}", item.language)
                            if (item.file.url.isNotBlank()) {
                                (requireActivity() as? ExoplayerView)?.reApplyLocalSubtitle(item.file.url)
                            }
                            updateActiveSubtitleBadge()
                            dismiss()
                        }
                    } else {
                        // Regular Server Subtitle
                        val langName = mapLanguageCode(item.language)
                        itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_24)
                        itemBinding.subtitleTitle.text = langName
                        itemBinding.subtitleDetails.text = "${getString(R.string.current_server_subs)} • ${item.language}"

                       // Format Tag
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

                        val isSelected = savedLang == item.language
                        if (isSelected) {
                            itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                            itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                            itemBinding.selectedCheckmark.isVisible = true
                        }

                        itemBinding.subtitleCardRoot.setOnClickListener {
                            val currentExtractor = episode.extractors?.find { it.server.name == episode.selectedExtractor }
                            val subIndex = currentExtractor?.subtitles?.indexOf(item) ?: -1
                            episode.selectedSubtitle = subIndex
                            model.setEpisode(episode, "Subtitle")
                            PrefManager.setCustomVal("subLang_${mediaId}", item.language)
                            updateActiveSubtitleBadge()
                            dismiss()
                        }
                    }
                }

                // --- 3. OTHER SERVER SUBTITLES (Cross-server) ---
                is OtherServerSubtitle -> {
                    val langName = mapLanguageCode(item.subtitle.language)
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_24)
                    itemBinding.subtitleTitle.text = langName
                    itemBinding.subtitleDetails.text = "${item.serverName} • ${item.subtitle.language}"

                    val uniqueKey = "${item.subtitle.language} [${item.serverName}]"
                    val isSelected = savedLang == uniqueKey
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        PrefManager.setCustomVal("subLang_${mediaId}", uniqueKey)
                        (requireActivity() as? ExoplayerView)?.reApplyLocalSubtitle(item.subtitle.file.url)
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                // --- 4. EMBEDDED STREAM TRACKS ---
                is EmbeddedSubtitleTrack -> {
                    val langName = item.language?.let { mapLanguageCode(it) } ?: "Stream Track ${item.trackIndex + 1}"
                    val label = item.label ?: "Embedded Track"

                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_round_subtitles_24)
                    itemBinding.subtitleTitle.text = langName
                    itemBinding.subtitleDetails.text = "${getString(R.string.embedded_stream_subs)} • $label"

                    val uniqueKey = "Embedded:${item.language ?: item.trackIndex}"
                    val isSelected = savedLang == uniqueKey
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        PrefManager.setCustomVal("subLang_${mediaId}", uniqueKey)
                        (requireActivity() as? ExoplayerView)?.onSetTrackGroupOverride(
                            item.group,
                            C.TRACK_TYPE_TEXT,
                            item.trackIndex
                        )
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                // --- 5. ONLINE SUBTITLE (WYZIE) ---
                is WyzieSub -> {
                    val langName = mapLanguageCode(item.language)
                    val displayTitle = item.displayLabel.ifBlank { langName }
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
                    itemBinding.subtitleTitle.text = displayTitle

                    val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""
                    itemBinding.subtitleDetails.text = "Wyzie • ${if (seInfo.isNotEmpty()) "$seInfo • " else ""}$langName"

                    itemBinding.formatBadge.text = item.format.uppercase(Locale.ROOT)
                    itemBinding.formatBadge.isVisible = true

                    val isSelected = savedLang?.contains(item.url) == true || savedLang == "Online:${item.url}"
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        val exoActivity = requireActivity() as? ExoplayerView
                        if (exoActivity != null) {
                            episode.selectedSubtitle = -1
                            val saveKey = "Online:$displayTitle • Wyzie • ${item.url}"
                            PrefManager.setCustomVal("subLang_${mediaId}", saveKey)
                            exoActivity.applyWyzieSubtitle(item)
                        }
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                // --- 6. ONLINE SUBTITLE (SUBSOURCE) ---
                is SubSourceSub -> {
                    val langName = mapLanguageCode(item.lang)
                    val releaseTitle = item.releaseName.ifBlank { langName }
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
                    itemBinding.subtitleTitle.text = releaseTitle
                    itemBinding.subtitleDetails.text = "SubSource • $langName"
                    itemBinding.formatBadge.text = "SRT"
                    itemBinding.formatBadge.isVisible = true

                    val isSelected = savedLang?.contains(item.id) == true || savedLang == "Online:${item.id}"
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        val exoActivity = requireActivity() as? ExoplayerView
                        if (exoActivity != null) {
                            episode.selectedSubtitle = -1
                            val saveKey = "Online:$releaseTitle • SubSource • ${item.id}"
                            PrefManager.setCustomVal("subLang_${mediaId}", saveKey)
                            exoActivity.applySubSourceSubtitle(item)
                        }
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                // --- 7. ONLINE SUBTITLE (OPEN SUBTITLES REST) ---
                is OpenSubRestItem -> {
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

                    val isSelected = savedLang?.contains(item.fileId.toString()) == true || savedLang == "Online:${item.fileId}"
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        val exoActivity = requireActivity() as? ExoplayerView
                        if (exoActivity != null) {
                            episode.selectedSubtitle = -1
                            val saveKey = "Online:$displayTitle • OpenSubtitles • ${item.fileId}"
                            PrefManager.setCustomVal("subLang_${mediaId}", saveKey)
                            exoActivity.applyOpenSubRestSubtitle(item)
                        }
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                // --- 8. ONLINE SUBTITLE (STREMIO / OPEN SUBTITLES) ---
                is StremioSub -> {
                    val langName = mapLanguageCode(item.lang)
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
                    itemBinding.subtitleTitle.text = langName

                    val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""
                    itemBinding.subtitleDetails.text = "OpenSubtitles • ${if (seInfo.isNotEmpty()) "$seInfo • " else ""}${item.lang}"

                    val isSelected = savedLang?.contains(item.url) == true || savedLang?.contains(item.id) == true || savedLang == "Online:${item.id}"
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        val exoActivity = requireActivity() as? ExoplayerView
                        if (exoActivity != null) {
                            episode.selectedSubtitle = -1
                            val saveKey = "Online:$langName • OpenSubtitles • ${item.url}"
                            PrefManager.setCustomVal("subLang_${mediaId}", saveKey)
                            exoActivity.applyOnlineSubtitle(item, langName, "OpenSubtitles")
                        }
                        updateActiveSubtitleBadge()
                        dismiss()
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
