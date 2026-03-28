package ani.dantotsu.media.anime

import ani.dantotsu.toast
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.util.Logger
import ani.dantotsu.R
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.connections.subtitles.WyzieSubtitles
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.databinding.BottomSheetSubtitlesBinding
import ani.dantotsu.databinding.ItemSubtitleTextBinding
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.launch
import ani.dantotsu.others.IdMappers

class SubtitleDialogFragment : BottomSheetDialogFragment() {
    data class AddLocalSubtitle(val text: String)
    data class SearchOnlineSubtitles(var text: String)
    private var _binding: BottomSheetSubtitlesBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private lateinit var episode: Episode
    private var currentSeasonEpisode: EpisodeMapper.SeasonEpisode? = null

    private fun mapLanguageCode(isoCode: String): String = when (isoCode.lowercase()) {
        "eng" -> "English"
        "spa" -> "Spanish"
        "fra" -> "French"
        "deu" -> "German"
        "ita" -> "Italian"
        "por" -> "Portuguese"
        "rus" -> "Russian"
        "jpn" -> "Japanese"
        "zho", "chi" -> "Chinese"
        "ara" -> "Arabic"
        "hin" -> "Hindi"
        "kor" -> "Korean"
        "pol" -> "Polish"
        "tur" -> "Turkish"
        "hun" -> "Hungarian"
        "ron" -> "Romanian"
        "ell" -> "Greek"
        "cze" -> "Czech"
        "swe" -> "Swedish"
        "dan" -> "Danish"
        "fin" -> "Finnish"
        "nor" -> "Norwegian"
        "nld" -> "Dutch"
        "tha" -> "Thai"
        "vie" -> "Vietnamese"
        "ind" -> "Indonesian"
        "ukr" -> "Ukrainian"
        "heb" -> "Hebrew"
        "bul" -> "Bulgarian"
        "hrv" -> "Croatian"
        "slk" -> "Slovak"
        "slv" -> "Slovenian"
        "mon" -> "Mongolian"
        "srp" -> "Serbian"
        else -> isoCode
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSubtitlesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Logger.log("SubtitleDialogFragment: onViewCreated called")

        binding.subtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())

        model.getMedia().observe(viewLifecycleOwner) { media ->
            // Logger.log("SubtitleDialogFragment: Media observed")
            episode = media?.anime?.episodes?.get(media.anime.selectedEpisode) ?: return@observe
            val currentExtractor =
                episode.extractors?.find { it.server.name == episode.selectedExtractor }
                    ?: return@observe

            // Logger.log("SubtitleDialogFragment: Got extractor with ${currentExtractor.subtitles.size} local subtitles")

            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val episodeId = "${media.id}-${episode.number}"
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = selectedEpisode.toIntOrNull() ?: 1

                val allSubtitles = mutableListOf<Any>()
                allSubtitles.add(AddLocalSubtitle("+ Add Local Subtitle"))

                // Add any locally-added subtitles (stored separately from online cache)
                val localSubs = model.getLocalSubtitles(episodeId)
                allSubtitles.addAll(localSubs)

                // Online subtitle section: populated instantly
                val cached = model.getFetchedSubtitles(episodeId)
                if (cached != null) {
                    if (cached.isNotEmpty()) {
                        allSubtitles.addAll(cached)
                    }
                } else {
                    val onlineSubsEnabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
                    if (onlineSubsEnabled) {
                        allSubtitles.add(SearchOnlineSubtitles("+ Online Subtitle"))
                    }
                }

                allSubtitles.addAll(currentExtractor.subtitles)

                if (_binding != null) {
                     binding.subtitlesRecycler.adapter = SubtitleAdapter(allSubtitles)
                }

                // Fetch IMDB and Mapping in background so it's ready when users click "Online"
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (media.idIMDB == null) {
                        try {
                            media.idIMDB = IdMappers.getImdbId(media.id)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    if (currentSeasonEpisode == null) {
                        try {
                            currentSeasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, episode)
                            // Update labels visually once loaded
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (_binding != null) binding.subtitlesRecycler.adapter?.notifyDataSetChanged()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun fetchOnlineSubtitles(adapter: SubtitleAdapter, item: SearchOnlineSubtitles, position: Int) {
        item.text = "Searching..."
        adapter.notifyItemChanged(position)

        val media = model.getMedia().value ?: return

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val imdbId = media.idIMDB ?: IdMappers.getImdbId(media.id) ?: return@launch
                val isMovie = media.format == "MOVIE"
                val selectedEpisode = media.anime?.selectedEpisode ?: "1"
                val episodeNum = selectedEpisode.toIntOrNull() ?: 1

                // Phase 2: Calculator (Translation) — reads AniZip data from episode.extra (no extra API call)
                val currentEpisode = media.anime?.episodes?.get(selectedEpisode)
                val seasonEpisode = EpisodeMapper.mapEpisode(media, episodeNum, currentEpisode)
                currentSeasonEpisode = seasonEpisode

                // Phase 3 & 4: Fetcher & Backup Plan
                val onlineSubs = mutableListOf<Any>()

                // Unified Fetcher (handles Wyzie & Stremio based on settings)
                // Use the new signature: getSubtitles(media, season, episode)
                // Ensure we pass the Media object correctly.
                val fetchedSubs = StremioSubtitles.getSubtitles(media, seasonEpisode.season, seasonEpisode.episode)
                onlineSubs.addAll(fetchedSubs)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        if (_binding == null) return@withContext
                        val validAdapter = binding.subtitlesRecycler.adapter as? SubtitleAdapter ?: return@withContext
                        // Verify item is still at position (basic check, improved by verifying content)
                        if (position < validAdapter.subtitles.size && validAdapter.subtitles[position] == item) {
                            val list = validAdapter.subtitles as MutableList<Any>
                            list.removeAt(position) // Remove "Search..." button

                            if (onlineSubs.isNotEmpty()) {
                                model.saveFetchedSubtitles("${media.id}-${episodeNum}", onlineSubs)
                                list.addAll(onlineSubs)
                            } else {
                                     toast("No subtitles found")
                                }
                            validAdapter.notifyDataSetChanged()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                         toast("Error fetching subtitles")
                         if (_binding != null) adapter.notifyDataSetChanged()
                }
            }
        }
    }


    inner class SubtitleAdapter(val subtitles: List<Any>) :
        RecyclerView.Adapter<SubtitleAdapter.StreamViewHolder>() {
        inner class StreamViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemSubtitleTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        @OptIn(UnstableApi::class)
        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val binding = holder.binding

            // Create alpha background color for highlighted item
            val highlightColor = ColorUtils.setAlphaComponent(
                PrefManager.getVal<Int>(PrefName.PrimaryColor),
                60
            )

            // Handle "None" option at position 0
            if (position == 0) {
                binding.subtitleTitle.setText(R.string.none)
                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val mediaID: Int = media.id
                    val selSubs = PrefManager.getNullableCustomVal(
                        "subLang_${mediaID}",
                        null,
                        String::class.java
                    )
                    if (episode.selectedSubtitle != null && selSubs != "None") {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                        binding.root.setCardBackgroundColor(highlightColor)
                    }
                }
                binding.root.setOnClickListener {
                    episode.selectedSubtitle = null
                    model.setEpisode(episode, "Subtitle")
                    model.getMedia().observe(viewLifecycleOwner) { media ->
                        val mediaID: Int = media.id
                        PrefManager.setCustomVal("subLang_${mediaID}", "None")
                    }
                    dismiss()
                }
                return
            }

            val adjustedPosition = position - 1
            val item = subtitles[adjustedPosition]

            // Handle separator
            if (item is String) {
                binding.subtitleTitle.text = item
                binding.root.isClickable = false
                binding.root.setCardBackgroundColor(TRANSPARENT)
                return
            }

            // --- ADD LOCAL SUBTITLE ---
            if (item is AddLocalSubtitle) {
                binding.subtitleTitle.text = item.text
                binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {
                    (requireActivity() as? ExoplayerView)?.requestLocalSubtitle()
                    dismiss()
                }
                return
            }

            // --- SEARCH ONLINE SUBTITLES ---
            if (item is SearchOnlineSubtitles) {
                binding.subtitleTitle.text = item.text
                binding.root.setCardBackgroundColor(TRANSPARENT)
                binding.root.setOnClickListener {
                    // Prevent double clicks
                    if (item.text == "Searching...") return@setOnClickListener
                    fetchOnlineSubtitles(this@SubtitleAdapter, item, adjustedPosition)
                }
                return
            }

            // --- LOCAL SUBTITLES ---
            if (item is Subtitle) {
                binding.subtitleTitle.text = when (item.language) {
                    "ja-JP" -> "[ja-JP] Japanese"
                    "en-US" -> "[en-US] English"
                    "de-DE" -> "[de-DE] German"
                    "es-ES" -> "[es-ES] Spanish"
                    "es-419" -> "[es-419] Spanish"
                    "fr-FR" -> "[fr-FR] French"
                    "it-IT" -> "[it-IT] Italian"
                    "pt-BR" -> "[pt-BR] Portuguese (Brazil)"
                    "pt-PT" -> "[pt-PT] Portuguese (Portugal)"
                    "ru-RU" -> "[ru-RU] Russian"
                    "zh-CN" -> "[zh-CN] Chinese (Simplified)"
                    "tr-TR" -> "[tr-TR] Turkish"
                    "ar-ME" -> "[ar-ME] Arabic"
                    "ar-SA" -> "[ar-SA] Arabic (Saudi Arabia)"
                    "uk-UK" -> "[uk-UK] Ukrainian"
                    "he-IL" -> "[he-IL] Hebrew"
                    "pl-PL" -> "[pl-PL] Polish"
                    "ro-RO" -> "[ro-RO] Romanian"
                    "sv-SE" -> "[sv-SE] Swedish"
                    else -> if (item.language matches Regex("([a-z]{2})-([A-Z]{2}|\\d{3})")) "[${item.language}]" else item.language
                }

                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val mediaID: Int = media.id
                    val selSubs: String? =
                        PrefManager.getNullableCustomVal(
                            "subLang_${mediaID}",
                            null,
                            String::class.java
                        )
                    // Highlight ONLY if the saved preference matches this local subtitle's language
                    if (selSubs != item.language) {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                         binding.root.setCardBackgroundColor(highlightColor)
                    }
                }

                binding.root.setOnClickListener {
                    // Check if this is a custom local subtitle we cached
                    if (item.language.startsWith("[Local]")) {
                        // DO NOT call model.setEpisode() here — that triggers a full player
                        // rebuild (releasePlayer + initPlayer) which wipes the local sub config
                        // and makes reApplyLocalSubtitle fail. Just update prefs and re-apply.
                        model.getMedia().value?.let { media ->
                            PrefManager.setCustomVal("subLang_${media.id}", item.language)
                        }

                        val activity = requireActivity()
                        if (activity is ExoplayerView) {
                            try {
                                activity.reApplyLocalSubtitle(item.file.url)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else {
                        // Standard built-in local subtitle
                        episode.selectedSubtitle = adjustedPosition
                        model.setEpisode(episode, "Subtitle")
                        model.getMedia().observe(viewLifecycleOwner) { media ->
                            val mediaID: Int = media.id
                            PrefManager.setCustomVal(
                                "subLang_${mediaID}",
                                item.language
                            )
                        }
                    }
                    dismiss()
                }
                return
            }

            val seInfo = currentSeasonEpisode?.let { "S${it.season}.E${it.episode}" } ?: ""

            // --- ONLINE SUBTITLES (WYZIE) ---
            if (item is WyzieSub) {
                // Formatting: [ASS] S2.E5.English
                val format = item.format.uppercase()
                val label = "[$format] ${if(seInfo.isNotEmpty()) "$seInfo." else ""}${item.displayLabel}"
                binding.subtitleTitle.text = label

                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val selSubs: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
                    // Use URL as unique ID for Wyzie
                    if (selSubs != "Online:${item.url}") {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                        binding.root.setCardBackgroundColor(highlightColor)
                    }
                }

                binding.root.setOnClickListener {
                    try {
                        val activity = requireActivity()
                        if (activity is ExoplayerView) {
                            episode.selectedSubtitle = -1
                            model.setEpisode(episode, "Subtitle")

                            model.getMedia().observe(viewLifecycleOwner) { media ->
                                PrefManager.setCustomVal("subLang_${media.id}", "Online:${item.url}")
                            }

                            // Convert WyzieSub to StremioSub for compatibility
                            val stremioSub = StremioSub(
                                id = item.url, // Use URL as ID
                                url = item.url,
                                lang = item.language
                            )
                            activity.applyOnlineSubtitle(stremioSub)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    dismiss()
                }
                return
            }

            // --- ONLINE SUBTITLES (STREMIO) ---
            if (item is StremioSub) {
                val langName = mapLanguageCode(item.lang)
                val label = "[ONLINE] ${if(seInfo.isNotEmpty()) "$seInfo." else ""}$langName"
                binding.subtitleTitle.text = label

                // Check if this online subtitle is currently selected using UNIQUE ID
                model.getMedia().observe(viewLifecycleOwner) { media ->
                    val selSubs: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)

                    // Use subtitle ID to uniquely identify selection
                    if (selSubs != "Online:${item.id}") {
                        binding.root.setCardBackgroundColor(TRANSPARENT)
                    } else {
                        binding.root.setCardBackgroundColor(highlightColor)
                    }
                }

                binding.root.setOnClickListener {
                    // Logger.log("SubtitleDialogFragment: Online subtitle clicked - ${item.lang}")
                    try {
                        val activity = requireActivity()
                        // Logger.log("SubtitleDialogFragment: Activity = ${activity::class.simpleName}")

                        if (activity is ExoplayerView) {
                            // Logger.log("SubtitleDialogFragment: Activity IS ExoplayerView, calling applyOnlineSubtitle")

                            // 1. Reset the Local Subtitle Selection Index
                            episode.selectedSubtitle = -1
                            model.setEpisode(episode, "Subtitle")

                            // 2. Save the "Online" label WITH unique ID to Preferences
                            model.getMedia().observe(viewLifecycleOwner) { media ->
                                PrefManager.setCustomVal("subLang_${media.id}", "Online:${item.id}")
                            }

                            // 3. Apply the subtitle
                            activity.applyOnlineSubtitle(item)
                            // Logger.log("SubtitleDialogFragment: applyOnlineSubtitle called successfully")
                        } else {
                            // Logger.log("SubtitleDialogFragment: Activity is NOT ExoplayerView! Type = ${activity::class.qualifiedName}")
                        }
                    } catch (e: Exception) {
                        // Logger.log("SubtitleDialogFragment: Exception in online subtitle click: ${e.message}")
                        e.printStackTrace()
                    }
                    dismiss()
                }
            }
        }

        override fun getItemCount(): Int = subtitles.size + 1
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}