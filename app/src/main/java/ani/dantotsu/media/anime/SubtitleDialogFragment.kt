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
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.connections.subtitles.WyzieSubtitles
import ani.dantotsu.databinding.BottomSheetSubtitlesBinding
import ani.dantotsu.databinding.ItemSubtitleCardBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.others.IdMappers
import ani.dantotsu.parsers.Subtitle
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
    data class OtherServerSubtitle(val serverName: String, val subtitle: Subtitle)
    data class EmbeddedSubtitleTrack(val group: Tracks.Group, val trackIndex: Int, val language: String?, val label: String?)
    enum class TabType { SERVER, ONLINE, LOCAL }

    private var _binding: BottomSheetSubtitlesBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()

    private lateinit var episode: Episode
    private var currentSeasonEpisode: EpisodeMapper.SeasonEpisode? = null
    private var searchJob: Job? = null

    // Tab state: 0 = Subtitles, 1 = Online, 2 = Local
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

            val episodeNum = selectedEpisode.toIntOrNull() ?: 1
            val episodeId = "${media.id}-${episode.number}"

            // Pre-fill search input
            val animeTitleText = media.userPreferredName
            if (binding.onlineSearchEditText.text.isNullOrBlank()) {
                binding.onlineSearchEditText.setText("$animeTitleText Episode $episodeNum")
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
                if (currentSeasonEpisode == null) {
                    try {
                        val currentEp = eps[selectedEpisode]
                        currentSeasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, currentEp)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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
        updateTabStyles()
    }

    private fun switchTab(tabIndex: Int) {
        currentTab = tabIndex
        updateTabStyles()

        binding.tabSubtitlesLayout.isVisible = currentTab == 0
        binding.tabOnlineLayout.isVisible = currentTab == 1
        binding.tabLocalLayout.isVisible = currentTab == 2

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
            binding.tabSubtitlesBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabSubtitlesBtn.setTextColor(primaryColor)
        } else {
            binding.tabSubtitlesBtn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.tabSubtitlesBtn.setTextColor(unselectedTextColor)
        }

        // Online Tab
        if (currentTab == 1) {
            binding.tabOnlineBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabOnlineBtn.setTextColor(primaryColor)
        } else {
            binding.tabOnlineBtn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.tabOnlineBtn.setTextColor(unselectedTextColor)
        }

        // Local Tab
        if (currentTab == 2) {
            binding.tabLocalBtn.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
            binding.tabLocalBtn.setTextColor(primaryColor)
        } else {
            binding.tabLocalBtn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.tabLocalBtn.setTextColor(unselectedTextColor)
        }
    }

    private fun setupOnlineSearchControls() {
        binding.onlineSearchActionBtn.setOnClickListener {
            performOnlineSearch()
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
            selectedProviderFilter = "Stremio"
            uncheckOtherProviderChips(binding.chipProviderStremio.id)
            filterAndDisplayOnlineResults()
        }

        // Language filter chips
        setupLanguageChips()
    }

    private fun uncheckOtherProviderChips(selectedId: Int) {
        binding.chipProviderAll.isChecked = selectedId == binding.chipProviderAll.id
        binding.chipProviderWyzie.isChecked = selectedId == binding.chipProviderWyzie.id
        binding.chipProviderStremio.isChecked = selectedId == binding.chipProviderStremio.id
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
                val label = savedLang.removePrefix("Online:")
                val display = if (label.startsWith("http")) "Online Subtitle" else label
                getString(R.string.active_sub_prefix, display)
            }
            savedLang.startsWith("[Local]") -> {
                val clean = savedLang.removePrefix("[Local]").trim()
                getString(R.string.active_sub_prefix, "Local: $clean")
            }
            else -> getString(R.string.active_sub_prefix, mapLanguageCode(savedLang))
        }

        binding.activeSubBadge.text = badgeText
    }

    private fun loadSubtitlesTab(episodeId: String) {
        val media = model.getMedia().value ?: return
        val items = mutableListOf<Any>()

        // 1. None Option
        items.add(NoneSubtitleOption)

        // 2. Current Server's Extractor Subtitles
        val currentExtractor = episode.extractors?.find { it.server.name == episode.selectedExtractor }
        if (currentExtractor != null && currentExtractor.subtitles.isNotEmpty()) {
            items.addAll(currentExtractor.subtitles)
        }

        // 3. Embedded Player Tracks
        val exoActivity = activity as? ExoplayerView
        val trackGroups = exoActivity?.currentSubTrackGroups
        if (trackGroups != null && trackGroups.isNotEmpty()) {
            trackGroups.forEachIndexed { _, group ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val lang = format.language
                    val label = format.label
                    if (lang != "none") {
                        items.add(EmbeddedSubtitleTrack(group, trackIndex, lang, label))
                    }
                }
            }
        }

        // 4. Other Servers' Subtitles for this Episode (Cross-server extraction)
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

        searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imdbId = media.idIMDB ?: IdMappers.getImdbId(media.id)
                if (imdbId != null) {
                    media.idIMDB = imdbId
                }

                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = selectedEpisode.toIntOrNull() ?: 1

                val currentEp = media.anime?.episodes?.get(selectedEpisode)
                val seasonEpisode = currentSeasonEpisode ?: EpisodeMapper.mapEpisode(media, episodeNum, currentEp)
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

                    // 2. Fetch Stremio / OpenSubtitles
                    try {
                        val fetchedStremio = StremioSubtitles.getSubtitles(media, seasonEpisode.season, seasonEpisode.episode)
                        val existingUrls = onlineSubs.mapNotNull { (it as? WyzieSub)?.url }.toSet()
                        val uniqueStremio = fetchedStremio.filter { it.url !in existingUrls }
                        onlineSubs.addAll(uniqueStremio)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.onlineLoadingLayout.isVisible = false
                    currentOnlineResults = onlineSubs
                    model.saveFetchedSubtitles("${media.id}-${episodeNum}", onlineSubs)
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
        } else if (selectedProviderFilter == "Stremio") {
            filtered = filtered.filterIsInstance<StremioSub>()
        }

        // Language Filter
        if (selectedLanguageFilter != "All") {
            filtered = filtered.filter { item ->
                when (item) {
                    is WyzieSub -> matchesLanguage(item.language, selectedLanguageFilter) ||
                            matchesLanguage(item.displayLabel, selectedLanguageFilter)
                    is StremioSub -> matchesLanguage(item.lang, selectedLanguageFilter)
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
                    else -> true
                }
            }
        }

        binding.noOnlineSubsBanner.isVisible = filtered.isEmpty()
        binding.onlineSubtitlesRecycler.isVisible = filtered.isNotEmpty()
        binding.onlineSubtitlesRecycler.adapter = SubtitleAdapter(filtered, TabType.ONLINE)
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

                        val isSelected = savedLang == item.language
                        if (isSelected) {
                            itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                            itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                            itemBinding.selectedCheckmark.isVisible = true
                        }

                        // Delete button
                        itemBinding.deleteButton.isVisible = true
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
                            (requireActivity() as? ExoplayerView)?.reApplyLocalSubtitle(item.file.url)
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
                            ani.dantotsu.parsers.SubtitleType.ASS -> "ASS"
                            ani.dantotsu.parsers.SubtitleType.VTT -> "VTT"
                            ani.dantotsu.parsers.SubtitleType.SRT -> "SRT"
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
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
                    itemBinding.subtitleTitle.text = item.displayLabel.ifBlank { langName }

                    val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""
                    itemBinding.subtitleDetails.text = "Wyzie • ${if (seInfo.isNotEmpty()) "$seInfo • " else ""}$langName"

                    itemBinding.formatBadge.text = item.format.uppercase(Locale.ROOT)
                    itemBinding.formatBadge.isVisible = true

                    val isSelected = savedLang == "Online:${item.url}"
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        val exoActivity = requireActivity() as? ExoplayerView
                        if (exoActivity != null) {
                            episode.selectedSubtitle = -1
                            model.setEpisode(episode, "Subtitle")
                            PrefManager.setCustomVal("subLang_${mediaId}", "Online:${item.url}")

                            val stremioSub = StremioSub(
                                id = item.url,
                                url = item.url,
                                lang = item.language
                            )
                            exoActivity.applyOnlineSubtitle(stremioSub)
                        }
                        updateActiveSubtitleBadge()
                        dismiss()
                    }
                }

                // --- 6. ONLINE SUBTITLE (STREMIO / OPEN SUBTITLES) ---
                is StremioSub -> {
                    val langName = mapLanguageCode(item.lang)
                    itemBinding.subtitleIcon.setImageResource(R.drawable.ic_globe_24)
                    itemBinding.subtitleTitle.text = langName

                    val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""
                    itemBinding.subtitleDetails.text = "OpenSubtitles • ${if (seInfo.isNotEmpty()) "$seInfo • " else ""}${item.lang}"

                    val isSelected = savedLang == "Online:${item.id}"
                    if (isSelected) {
                        itemBinding.subtitleCardRoot.setCardBackgroundColor(highlightColor)
                        itemBinding.subtitleCardRoot.strokeColor = borderSelectedColor
                        itemBinding.selectedCheckmark.isVisible = true
                    }

                    itemBinding.subtitleCardRoot.setOnClickListener {
                        val exoActivity = requireActivity() as? ExoplayerView
                        if (exoActivity != null) {
                            episode.selectedSubtitle = -1
                            model.setEpisode(episode, "Subtitle")
                            PrefManager.setCustomVal("subLang_${mediaId}", "Online:${item.id}")
                            exoActivity.applyOnlineSubtitle(item)
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
