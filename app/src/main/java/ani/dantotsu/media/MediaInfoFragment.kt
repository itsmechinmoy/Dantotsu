package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.GenresViewModel
import ani.dantotsu.copyToClipboard
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.ActivityGenreBinding
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemQuelsBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.databinding.ItemTitleTrailerBinding
import ani.dantotsu.displayTimer
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.setBaseline
import ani.dantotsu.profile.User
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.media.mangaupdates.MangaAnimeUtil
import ani.dantotsu.util.Logger
import com.xwray.groupie.GroupieAdapter
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.net.URLEncoder


class MediaInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var timer: CountDownTimer? = null
    private var loaded = false
    private var type = "ANIME"
    private val genreModel: GenresViewModel by activityViewModels()
    private val model: MediaDetailsViewModel by activityViewModels()

    private val tripleTab = "\t\t\t"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        timer?.cancel()
        timer = null
        super.onDestroyView()
        _binding = null
    }

    // Method to display anime adaptation
    private fun displayAnimeAdaptation(adaptation: MangaAnimeUtil.AnimeAdaptation) {
        if (adaptation.hasAdaptation) {
            val adaptationText = buildString {
                append("Start: ${adaptation.animeStart ?: "Unknown"}\n")
                append("End: ${adaptation.animeEnd ?: "Ongoing"}")
            }

            binding.mediaAnimeAdaptation.text = adaptationText

            binding.mediaAnimeAdaptationText.fadeIn()
            binding.mediaAnimeAdaptation.fadeIn()
        } else {
            binding.mediaAnimeAdaptationText.fadeOut()
            binding.mediaAnimeAdaptation.fadeOut()
        }
    }
    private fun displayNextChapterPrediction(prediction: MangaAnimeUtil.NextRelease) {
        if (prediction.error == null && prediction.nextReleaseDate != null) {
            val dateFormat = java.text.SimpleDateFormat("d MMMM", java.util.Locale.US)

            val predictionText = buildString {
                append("Current: ${prediction.latestChapter ?: "Unknown"}\n")
                append("${prediction.nextChapter ?: "Next chapter"} releases on ${
                    dateFormat.format(prediction.nextReleaseDate)
                }")
            }

            binding.mediaNextChapterPrediction.text = predictionText

            binding.mediaNextChapterPredictionText.fadeIn()
            binding.mediaNextChapterPrediction.fadeIn()
        } else {
            binding.mediaNextChapterPredictionText.fadeOut()
            binding.mediaNextChapterPrediction.fadeOut()

            if (prediction.error != null) {
                Logger.log("Next chapter prediction error: ${prediction.error}")
            }
        }
    }
    fun View.fadeIn(duration: Long = 250) {
        if (isVisible) return
        alpha = 0f
        translationY = 20f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .start()
    }

    fun View.fadeOut(duration: Long = 200) {
        if (visibility != View.VISIBLE) return
        animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(duration)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                translationY = 0f
            }
            .start()
    }
    private inner class MediaWebChromeClient : WebChromeClient() {
        private var mCustomView: View? = null
        private var mCustomViewCallback: CustomViewCallback? = null
        private var mOriginalSystemUiVisibility = 0

        override fun onHideCustomView() {
            (requireActivity().window.decorView as FrameLayout).removeView(mCustomView)
            mCustomView = null
            requireActivity().window.decorView.systemUiVisibility = mOriginalSystemUiVisibility
            mCustomViewCallback?.onCustomViewHidden()
            mCustomViewCallback = null
        }

        override fun onShowCustomView(
            paramView: View,
            paramCustomViewCallback: CustomViewCallback
        ) {
            if (mCustomView != null) {
                onHideCustomView()
                return
            }
            mCustomView = paramView
            mOriginalSystemUiVisibility = requireActivity().window.decorView.systemUiVisibility
            mCustomViewCallback = paramCustomViewCallback
            (requireActivity().window.decorView as FrameLayout).addView(
                mCustomView,
                FrameLayout.LayoutParams(-1, -1)
            )
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility =
                3846 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun setupBaselineAnchor(activity: MediaDetailsActivity) {
        val baselineAnchor = activity.binding.mediaBottomBarContainer ?: activity.binding.commentMessageContainer
        baselineAnchor?.let {
            val includeSystemPaddings = it != activity.binding.mediaBottomBarContainer
            binding.mediaInfoScroll.setBaseline(it, includeSystemNavBar = includeSystemPaddings)
            binding.mediaInfoScroll.clipToPadding = false
        }
    }

    private fun setupScrollAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cornerTop = ObjectAnimator.ofFloat(binding.root, "radius", 0f, 32f).setDuration(200)
            val cornerNotTop = ObjectAnimator.ofFloat(binding.root, "radius", 32f, 0f).setDuration(200)
            var cornered = true
            cornerTop.start()
            binding.mediaInfoScroll.setOnScrollChangeListener { v, _, _, _, _ ->
                if (!v.canScrollVertically(-1)) {
                    if (!cornered) {
                        cornered = true
                        cornerTop.start()
                    }
                } else if (cornered) {
                    cornered = false
                    cornerNotTop.start()
                }
            }
        }
    }

    private fun formatEpisodeDuration(episodeDuration: Int?): String {
        if (episodeDuration == null) return "??"
        val hours = episodeDuration / 60
        val minutes = episodeDuration % 60
        return buildString {
            if (hours > 0) {
                append("$hours hour")
                if (hours > 1) append("s")
            }
            if (minutes > 0) {
                if (hours > 0) append(", ")
                append("$minutes min")
                if (minutes > 1) append("s")
            }
        }.ifEmpty { "??" }
    }

    private fun bindBasicInfo(media: Media, offline: Boolean) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE
        val infoName = tripleTab + (media.name ?: media.nameRomaji)
        binding.mediaInfoName.text = infoName
        binding.mediaInfoName.setOnLongClickListener {
            copyToClipboard(media.name ?: media.nameRomaji)
            true
        }
        if (media.name != null) binding.mediaInfoNameRomajiContainer.visibility = View.VISIBLE
        val infoNameRomaji = tripleTab + media.nameRomaji
        binding.mediaInfoNameRomaji.text = infoNameRomaji
        binding.mediaInfoNameRomaji.setOnLongClickListener {
            copyToClipboard(media.nameRomaji)
            true
        }
        binding.mediaInfoMeanScore.text = media.meanScore?.let { (it / 10.0).toString() } ?: "??"
        binding.mediaInfoStatus.text = media.status
        binding.mediaInfoFormat.text = media.format
        binding.mediaInfoSource.text = media.source
        binding.mediaInfoStart.text = media.startDate?.toString() ?: "??"
        binding.mediaInfoEnd.text = media.endDate?.toString() ?: "??"
        binding.mediaInfoPopularity.text = media.popularity.toString()
        binding.mediaInfoFavorites.text = media.favourites.toString()

        if (media.anime != null) {
            bindAnimeDetails(media)
        } else if (media.manga != null) {
            bindMangaDetails(media, offline)
        }

        bindDescription(media)
        displayTimer(media, binding.mediaInfoContainer)

        val parent = _binding?.mediaInfoContainer ?: return
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.tag == "dynamic_view") {
                parent.removeViewAt(i)
            }
        }
    }

    private fun bindAnimeDetails(media: Media) {
        val anime = media.anime ?: return
        binding.mediaInfoDuration.text = formatEpisodeDuration(anime.episodeDuration)
        binding.mediaInfoDurationContainer.visibility = View.VISIBLE
        binding.mediaInfoSeasonContainer.visibility = View.VISIBLE
        val seasonInfo = "${anime.season ?: "??"} ${anime.seasonYear ?: "??"}"
        binding.mediaInfoSeason.text = seasonInfo

        if (anime.mainStudio != null) {
            binding.mediaInfoStudioContainer.visibility = View.VISIBLE
            binding.mediaInfoStudio.text = anime.mainStudio!!.name
            binding.mediaInfoStudioContainer.setOnClickListener {
                ContextCompat.startActivity(
                    requireActivity(),
                    Intent(requireContext(), StudioActivity::class.java).putExtra(
                        "studio",
                        anime.mainStudio!! as Serializable
                    ),
                    null
                )
            }
        }
        if (anime.author != null) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = anime.author!!.name
            binding.mediaInfoAuthorContainer.setOnClickListener {
                ContextCompat.startActivity(
                    requireActivity(),
                    Intent(requireContext(), AuthorActivity::class.java).putExtra(
                        "author",
                        anime.author!! as Serializable
                    ),
                    null
                )
            }
        }
        binding.mediaInfoTotalTitle.setText(R.string.total_eps)
        val infoTotal = if (anime.nextAiringEpisode != null)
            "${anime.nextAiringEpisode} | ${anime.totalEpisodes ?: "~"}"
        else
            (anime.totalEpisodes ?: "~").toString()
        binding.mediaInfoTotal.text = infoTotal
    }

    private fun bindMangaDetails(media: Media, offline: Boolean) {
        val manga = media.manga ?: return
        type = "MANGA"
        binding.mediaInfoTotalTitle.setText(R.string.total_chaps)
        binding.mediaInfoTotal.text = (manga.totalChapters ?: "~").toString()
        if (manga.author != null) {
            binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
            binding.mediaInfoAuthor.text = manga.author!!.name
            if (!offline) {
                binding.mediaInfoAuthorContainer.setOnClickListener {
                    ContextCompat.startActivity(
                        requireActivity(),
                        Intent(activity, AuthorActivity::class.java).putExtra(
                            "author",
                            manga.author!! as Serializable
                        ),
                        null
                    )
                }
            }
        }
    }

    private fun bindDescription(media: Media) {
        val desc = HtmlCompat.fromHtml(
            (media.description ?: "null").replace("\\n", "<br>").replace("\\\"", "\""),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        val infoDesc = tripleTab + if (desc.toString() != "null") desc else getString(R.string.no_description_available)
        binding.mediaInfoDescription.text = infoDesc

        binding.mediaInfoDescription.setOnClickListener {
            if (binding.mediaInfoDescription.maxLines == 5) {
                ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 100).setDuration(950).start()
            } else {
                ObjectAnimator.ofInt(binding.mediaInfoDescription, "maxLines", 5).setDuration(400).start()
            }
        }
    }

    private fun bindProducers(media: Media, parent: ViewGroup) {
        val producers = media.anime?.producers?.filter { it.id != "null" } ?: return
        if (producers.isEmpty()) return

        val bind = ItemTitleChipgroupBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.itemTitle.text = getString(R.string.producers)
        bind.root.tag = "dynamic_view"
        parent.addView(bind.root, 1)

        producers.forEach { producer ->
            val chip = ItemChipBinding.inflate(
                LayoutInflater.from(context),
                bind.itemChipGroup,
                false
            ).root
            chip.text = producer.name ?: ""
            chip.setSafeOnClickListener {
                ContextCompat.startActivity(
                    requireActivity(),
                    Intent(activity, StudioActivity::class.java).putExtra(
                        "studio",
                        producer as Serializable
                    ),
                    null
                )
            }
            bind.itemChipGroup.addView(chip)
        }
    }

    private fun bindMangaExtrasAndSynonyms(media: Media, parent: ViewGroup, offline: Boolean) {
        if (media.manga != null && !offline) {
            model.loadMangaExtras(media)
            model.adaptation.observe(viewLifecycleOwner) {
                it?.let { displayAnimeAdaptation(it) }
            }
            model.nextRelease.observe(viewLifecycleOwner) {
                it?.let { displayNextChapterPrediction(it) }
            }
        }

        if (media.synonyms.isNotEmpty()) {
            val bind = ItemTitleChipgroupBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.root.tag = "dynamic_view"
            for (position in media.synonyms.indices) {
                val chip = ItemChipBinding.inflate(
                    LayoutInflater.from(context),
                    bind.itemChipGroup,
                    false
                ).root
                chip.text = media.synonyms[position]
                chip.setOnLongClickListener { copyToClipboard(media.synonyms[position]); true }
                bind.itemChipGroup.addView(chip)
            }
            parent.addView(bind.root)
        }
    }

    private fun bindSocialUsers(media: Media, parent: ViewGroup, offline: Boolean) {
        if (media.users.isNullOrEmpty() || offline) return

        val users: ArrayList<User> = media.users ?: arrayListOf()
        val currentUserId = Anilist.userid
        if (Anilist.token != null && currentUserId != null && media.userStatus != null) {
            users.add(
                0,
                User(
                    id = currentUserId,
                    name = getString(R.string.you),
                    pfp = Anilist.avatar,
                    banner = "",
                    status = media.userStatus,
                    score = media.userScore.toFloat(),
                    progress = media.userProgress,
                    totalEpisodes = media.anime?.totalEpisodes ?: media.manga?.totalChapters,
                    nextAiringEpisode = media.anime?.nextAiringEpisode
                )
            )
        }
        ItemTitleRecyclerBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        ).apply {
            itemTitle.visibility = View.GONE
            itemRecycler.adapter = MediaSocialAdapter(users, type, requireActivity())
            itemRecycler.layoutManager = LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
            )
            root.tag = "dynamic_view"
            parent.addView(root)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun bindTrailerView(media: Media, parent: ViewGroup, offline: Boolean) {
        val trailerId = media.trailer
        if (trailerId == null || offline) return

        val bind = ItemTitleTrailerBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.mediaInfoTrailer.apply {
            visibility = View.VISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            settings.userAgentString = null
            isSoundEffectsEnabled = true
            webChromeClient = MediaWebChromeClient()

            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun loadVideo() {
                    val trailerHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <style>
                                * {
                                    margin: 0;
                                    padding: 0;
                                    box-sizing: border-box;
                                    -webkit-tap-highlight-color: transparent;
                                }
                                html, body {
                                    width: 100%;
                                    height: 100%;
                                    background: #000;
                                    overflow: hidden;
                                }
                                iframe {
                                    width: 100%;
                                    height: 100%;
                                    border: none;
                                    display: block;
                                }
                            </style>
                        </head>
                        <body>
                            <iframe
                                src="https://www.youtube-nocookie.com/embed/$trailerId?autoplay=1&rel=0&modestbranding=1&controls=1&fs=0"
                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                                frameborder="0">
                            </iframe>
                        </body>
                        </html>
                    """.trimIndent()
                    context.let {
                        (it as? android.app.Activity)?.runOnUiThread {
                            loadDataWithBaseURL("https://www.youtube-nocookie.com", trailerHtml, "text/html", "utf-8", null)
                        }
                    }
                }
            }, "Android")

            val placeholderHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        * {
                            margin: 0;
                            padding: 0;
                            box-sizing: border-box;
                            -webkit-tap-highlight-color: transparent;
                            -webkit-touch-callout: none;
                            -webkit-user-select: none;
                            user-select: none;
                        }
                        body, html {
                            width: 100%;
                            height: 100%;
                            background: #000;
                            overflow: hidden;
                        }
                        .container {
                            position: relative;
                            width: 100%;
                            height: 100%;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            cursor: pointer;
                        }
                        .thumbnail {
                            position: absolute;
                            top: 0;
                            left: 0;
                            width: 100%;
                            height: 100%;
                            object-fit: cover;
                        }
                        .play-button {
                            position: relative;
                            width: 68px;
                            height: 48px;
                            background: rgba(33, 33, 33, 0.8);
                            border-radius: 12px;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            z-index: 1;
                            transition: background 0.2s;
                        }
                        .play-button:hover {
                            background: #f00;
                        }
                        .play-icon {
                            width: 0;
                            height: 0;
                            border-style: solid;
                            border-width: 10px 0 10px 18px;
                            border-color: transparent transparent transparent #fff;
                        }
                    </style>
                </head>
                <body>
                    <div class="container" onclick="Android.loadVideo()">
                        <img class="thumbnail" src="https://img.youtube.com/vi/$trailerId/hqdefault.jpg" alt="Trailer">
                        <div class="play-button">
                            <div class="play-icon"></div>
                        </div>
                    </div>
                </body>
                </html>
            """.trimIndent()
            loadDataWithBaseURL("https://www.youtube-nocookie.com", placeholderHtml, "text/html", "utf-8", null)
        }
        bind.root.tag = "dynamic_view"
        parent.addView(bind.root)
    }

    private fun makeYoutubeThemeLink(a: String): String {
        val first = a.indexOf('"').let { if (it != -1) it else return a } + 1
        val end = a.indexOf('"', first).let { if (it != -1) it else return a }
        val name = a.subSequence(first, end).toString()
        return "${a.subSequence(0, first)}" +
                "[$name](https://www.youtube.com/results?search_query=${
                    URLEncoder.encode(
                        name,
                        "utf-8"
                    )
                })" +
                "${a.subSequence(end, a.length)}"
    }

    private fun setupThemeTextView(textView: TextView, arr: ArrayList<String>, markWon: Markwon) {
        var op = ""
        arr.forEach {
            op += "\n"
            op += makeYoutubeThemeLink(it)
        }
        op = op.removePrefix("\n")
        textView.setOnClickListener {
            if (textView.maxLines == 4) {
                ObjectAnimator.ofInt(textView, "maxLines", 100).setDuration(950).start()
            } else {
                ObjectAnimator.ofInt(textView, "maxLines", 4).setDuration(400).start()
            }
        }
        markWon.setMarkdown(textView, op)
    }

    private fun bindThemes(media: Media, parent: ViewGroup, offline: Boolean) {
        val anime = media.anime ?: return
        if ((anime.op.isEmpty() && anime.ed.isEmpty()) || offline) return

        val markWon = Markwon.builder(requireContext())
            .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()

        if (anime.op.isNotEmpty()) {
            val bind = ItemTitleTextBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.setText(R.string.opening)
            setupThemeTextView(bind.itemText, anime.op, markWon)
            bind.root.tag = "dynamic_view"
            parent.addView(bind.root)
        }

        if (anime.ed.isNotEmpty()) {
            val bind = ItemTitleTextBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            bind.itemTitle.setText(R.string.ending)
            setupThemeTextView(bind.itemText, anime.ed, markWon)
            bind.root.tag = "dynamic_view"
            parent.addView(bind.root)
        }
    }

    private fun bindGenres(media: Media, parent: ViewGroup, screenWidth: Float, offline: Boolean) {
        if (media.genres.isEmpty()) return

        val bind = ActivityGenreBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.root.tag = "dynamic_view"
        val adapter = GenreAdapter(type)

        bind.mediaInfoGenresRecyclerView.adapter = adapter
        bind.mediaInfoGenresRecyclerView.layoutManager =
            GridLayoutManager(requireActivity(), (screenWidth / 156f).toInt())

        if (!offline) {
            genreModel.doneListener = {
                MainScope().launch {
                    bind.mediaInfoGenresProgressBar.visibility = View.GONE
                }
            }
            if (genreModel.genres != null) {
                adapter.genres = genreModel.genres!!
                adapter.pos = ArrayList(genreModel.genres!!.keys)
                if (genreModel.done) genreModel.doneListener?.invoke()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                genreModel.loadGenres(media.genres) {
                    MainScope().launch {
                        adapter.addGenre(it)
                    }
                }
            }
        } else {
            bind.mediaInfoGenresProgressBar.visibility = View.GONE
            media.genres.forEach { genre ->
                adapter.addGenre(Pair(genre, ""))
            }
        }
        parent.addView(bind.root)
    }

    private fun bindTags(media: Media, parent: ViewGroup, offline: Boolean) {
        if (media.tags.isEmpty() || offline) return

        val bind = ItemTitleChipgroupBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.root.tag = "dynamic_view"
        val hideSpoilerTags = PrefManager.getVal<Boolean>(PrefName.HideSpoilerTags)
        val hasSpoilers = media.tagsIsSpoiler.any { it }
        var allSpoilersRevealed = false
        val spoilerChipUpdaters = mutableListOf<(Boolean) -> Unit>()

        bind.itemTitle.setText(R.string.tags)
        if (hasSpoilers && hideSpoilerTags) {
            bind.itemTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.format_spoiler_24, 0)
            bind.itemTitle.compoundDrawablePadding = 8
            bind.itemTitle.setSafeOnClickListener {
                allSpoilersRevealed = !allSpoilersRevealed
                spoilerChipUpdaters.forEach { it(allSpoilersRevealed) }
                val msg = if (allSpoilersRevealed) R.string.show_spoilers else R.string.hide_spoilers
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        for (position in media.tags.indices) {
            val chip = ItemChipBinding.inflate(
                LayoutInflater.from(context),
                bind.itemChipGroup,
                false
            ).root
            val isSpoiler = media.tagsIsSpoiler.getOrNull(position) == true
            var isRevealed = !hideSpoilerTags || !isSpoiler

            fun updateChipAppearance() {
                if (isSpoiler && !isRevealed) {
                    chip.text = chip.context.getString(R.string.spoiler_tag_placeholder)
                    chip.setChipIconResource(R.drawable.format_spoiler_24)
                    chip.isChipIconVisible = true
                    chip.alpha = 0.75f
                } else {
                    chip.text = media.tags[position]
                    chip.isChipIconVisible = false
                    chip.alpha = 1.0f
                }
            }

            updateChipAppearance()

            if (isSpoiler && hideSpoilerTags) {
                spoilerChipUpdaters.add { reveal ->
                    isRevealed = reveal
                    updateChipAppearance()
                }
            }

            chip.setSafeOnClickListener {
                if (isSpoiler && !isRevealed) {
                    isRevealed = true
                    updateChipAppearance()
                } else {
                    ContextCompat.startActivity(
                        chip.context,
                        Intent(chip.context, SearchActivity::class.java)
                            .putExtra("type", type)
                            .putExtra("sortBy", Anilist.sortBy[2])
                            .putExtra("tag", media.tags[position].substringBefore(" :"))
                            .putExtra("search", true)
                            .also {
                                if (media.isAdult) {
                                    if (!Anilist.adult) Toast.makeText(
                                        chip.context,
                                        currActivity()?.getString(R.string.content_18),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    it.putExtra("hentai", true)
                                }
                            },
                        null
                    )
                }
            }
            chip.setOnLongClickListener {
                if (isSpoiler && isRevealed && hideSpoilerTags) {
                    isRevealed = false
                    updateChipAppearance()
                    true
                } else {
                    copyToClipboard(media.tags[position])
                    true
                }
            }
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun resolveRankingTimePeriod(rank: ani.dantotsu.connections.anilist.api.MediaRank, formattedContext: String): String {
        val isAllTime = rank.allTime == true || formattedContext.contains("All Time", ignoreCase = true)
        val seasonName = rank.season?.name?.lowercase()?.replaceFirstChar(Char::titlecase)

        return when {
            isAllTime -> if (formattedContext.contains("All Time", ignoreCase = true)) "" else "All Time"
            seasonName != null && rank.year != null -> "$seasonName ${rank.year}"
            rank.year != null -> "${rank.year}"
            seasonName != null -> seasonName
            else -> ""
        }
    }

    private fun bindRankings(media: Media, parent: ViewGroup, offline: Boolean) {
        val rankings = media.rankings
        if (rankings.isNullOrEmpty() || offline) return

        val bind = ItemTitleChipgroupBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.root.tag = "dynamic_view"
        bind.itemTitle.text = "Rankings"
        rankings.forEach { rank ->
            val chip = ItemChipBinding.inflate(
                LayoutInflater.from(context),
                bind.itemChipGroup,
                false
            ).root
            val rawContext = rank.context?.takeIf { it.isNotBlank() }
                ?: when (rank.type) {
                    "POPULAR" -> "most popular"
                    "RATED" -> "highest rated"
                    else -> ""
                }
            val formattedContext = rawContext.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }

            val timePeriod = resolveRankingTimePeriod(rank, formattedContext)
            val rankingText = if (timePeriod.isNotEmpty()) {
                "$formattedContext $timePeriod"
            } else {
                formattedContext
            }

            chip.text = "#${rank.rank} $rankingText"
            bind.itemChipGroup.addView(chip)
        }
        parent.addView(bind.root)
    }

    private fun bindExternalLinks(media: Media, parent: ViewGroup, offline: Boolean) {
        val links = media.externalLinks
        if (links.isNullOrEmpty() || offline) return

        val bind = ItemTitleChipgroupBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.itemTitle.setText(R.string.external_links)
        for (link in links) {
            val url = link.url ?: continue
            val chip = ItemChipBinding.inflate(
                LayoutInflater.from(context),
                bind.itemChipGroup,
                false
            ).root
            chip.text = link.site
            chip.setSafeOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            chip.setOnLongClickListener { copyToClipboard(url); true }
            bind.itemChipGroup.addView(chip)
        }
        bind.root.tag = "dynamic_view"
        parent.addView(bind.root)
    }

    private fun bindPrequelSequel(media: Media, parent: ViewGroup, offline: Boolean) {
        if ((media.relations.isNullOrEmpty() && media.sequel == null && media.prequel == null) || offline) return
        if (media.sequel == null && media.prequel == null) return

        ItemQuelsBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        ).apply {
            if (media.sequel != null) {
                mediaInfoSequel.visibility = View.VISIBLE
                mediaInfoSequelImage.loadImage(media.sequel!!.banner ?: media.sequel!!.cover)
                mediaInfoSequel.setSafeOnClickListener {
                    ContextCompat.startActivity(
                        requireContext(),
                        Intent(requireContext(), MediaDetailsActivity::class.java).putExtra(
                            "media",
                            media.sequel as Serializable
                        ),
                        null
                    )
                }
            }
            if (media.prequel != null) {
                mediaInfoPrequel.visibility = View.VISIBLE
                mediaInfoPrequelImage.loadImage(media.prequel!!.banner ?: media.prequel!!.cover)
                mediaInfoPrequel.setSafeOnClickListener {
                    ContextCompat.startActivity(
                        requireContext(),
                        Intent(requireContext(), MediaDetailsActivity::class.java).putExtra(
                            "media",
                            media.prequel as Serializable
                        ),
                        null
                    )
                }
            }
            root.tag = "dynamic_view"
            parent.addView(root)
        }
    }

    private fun resolveNewsImage(media: Media, watchOrderImage: String?): String? {
        return media.relations?.firstOrNull { it.cover != null && it.cover != media.cover }?.cover
            ?: media.characters?.firstOrNull()?.image
            ?: media.relations?.firstOrNull { it.banner != null && it.banner != media.banner }?.banner
            ?: media.cover?.takeIf { it != watchOrderImage }
            ?: media.banner
            ?: media.cover
    }

    private fun bindWatchOrderAndNews(media: Media, parent: ViewGroup, offline: Boolean) {
        if (offline) return

        ItemQuelsBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        ).apply {
            if (media.anime != null) {
                val watchOrderImage = media.banner ?: media.cover
                val newsImage = resolveNewsImage(media, watchOrderImage)

                mediaInfoPrequel.visibility = View.VISIBLE
                mediaInfoPrequelTitle.text = getString(R.string.watch_order)
                mediaInfoPrequelImage.loadImage(watchOrderImage)
                mediaInfoPrequel.setSafeOnClickListener {
                    if (!isAdded || context == null) return@setSafeOnClickListener
                    if (childFragmentManager.findFragmentByTag("watch_order") == null) {
                        MediaContentBottomSheet.newWatchOrderInstance(media)
                            .show(childFragmentManager, "watch_order")
                    }
                }

                mediaInfoSequel.visibility = View.VISIBLE
                mediaInfoSequelTitle.text = getString(R.string.news)
                mediaInfoSequelImage.loadImage(newsImage)
                mediaInfoSequel.setSafeOnClickListener {
                    if (!isAdded || context == null) return@setSafeOnClickListener
                    if (childFragmentManager.findFragmentByTag("news") == null) {
                        MediaContentBottomSheet.newNewsInstance(media)
                            .show(childFragmentManager, "news")
                    }
                }
            } else {
                val newsImage = media.banner ?: media.cover
                mediaInfoPrequel.visibility = View.VISIBLE
                mediaInfoPrequelTitle.text = getString(R.string.news)
                mediaInfoPrequelImage.loadImage(newsImage)
                mediaInfoPrequel.setSafeOnClickListener {
                    if (!isAdded || context == null) return@setSafeOnClickListener
                    if (childFragmentManager.findFragmentByTag("news") == null) {
                        MediaContentBottomSheet.newNewsInstance(media)
                            .show(childFragmentManager, "news")
                    }
                }
            }
            root.tag = "dynamic_view"
            parent.addView(root)
        }
    }

    private fun bindReviews(media: Media, parent: ViewGroup) {
        if (media.review.isNullOrEmpty()) return

        ItemTitleRecyclerBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        ).apply {
            val adapter = GroupieAdapter()
            media.review!!.forEach { adapter.add(ReviewAdapter(it)) }
            itemTitle.setText(R.string.reviews)
            itemRecycler.adapter = adapter
            itemRecycler.layoutManager = LinearLayoutManager(requireContext())
            itemMore.visibility = View.VISIBLE
            itemMore.setSafeOnClickListener {
                startActivity(
                    Intent(requireContext(), ReviewActivity::class.java)
                        .putExtra("mediaId", media.id)
                )
            }
            root.tag = "dynamic_view"
            parent.addView(root)
        }
    }

    private fun bindHorizontalRecyclers(media: Media, parent: ViewGroup, offline: Boolean) {
        if (offline) return

        if (!media.relations.isNullOrEmpty()) {
            ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                itemRecycler.adapter = MediaAdaptor(0, media.relations!!, requireActivity())
                itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                root.tag = "dynamic_view"
                parent.addView(root)
            }
        }
        if (!media.characters.isNullOrEmpty()) {
            ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                itemTitle.setText(R.string.characters)
                itemRecycler.adapter = CharacterAdapter(media.characters!!)
                itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                root.tag = "dynamic_view"
                parent.addView(root)
            }
        }
        if (!media.staff.isNullOrEmpty()) {
            ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                itemTitle.setText(R.string.staff)
                itemRecycler.adapter = AuthorAdapter(media.staff!!)
                itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                root.tag = "dynamic_view"
                parent.addView(root)
            }
        }
        if (!media.recommendations.isNullOrEmpty()) {
            ItemTitleRecyclerBinding.inflate(LayoutInflater.from(context), parent, false).apply {
                itemTitle.setText(R.string.recommended)
                itemRecycler.adapter = MediaAdaptor(0, media.recommendations!!, requireActivity())
                itemRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                root.tag = "dynamic_view"
                parent.addView(root)
            }
        }
    }

    private fun bindMediaContent(media: Media, offline: Boolean) {
        bindBasicInfo(media, offline)
        val parent = _binding?.mediaInfoContainer ?: return
        bindProducers(media, parent)
        bindMangaExtrasAndSynonyms(media, parent, offline)
        bindSocialUsers(media, parent, offline)
        bindTrailerView(media, parent, offline)
        bindThemes(media, parent, offline)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        bindGenres(media, parent, screenWidth, offline)
        bindTags(media, parent, offline)
        bindRankings(media, parent, offline)
        bindExternalLinks(media, parent, offline)
        bindPrequelSequel(media, parent, offline)
        bindWatchOrderAndNews(media, parent, offline)
        bindReviews(media, parent)
        bindHorizontalRecyclers(media, parent, offline)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()
        val offline: Boolean = PrefManager.getVal(PrefName.OfflineMode) || !isOnline(requireContext())
        binding.mediaInfoProgressBar.isGone = loaded
        binding.mediaInfoContainer.isVisible = loaded
        val activity = requireActivity() as MediaDetailsActivity
        if (!activity.bindingReady) return
        setupBaselineAnchor(activity)

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (media != null) {
                loaded = true
                bindMediaContent(media, offline)
            }
        }

        setupScrollAnimation()
        super.onViewCreated(view, null)
    }

    override fun onResume() {
        binding.mediaInfoProgressBar.isGone = loaded
        super.onResume()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
