package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.GesturesListener
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.anime.MpvPlayerActivity
import ani.dantotsu.media.anime.getEpisode
import ani.dantotsu.media.anime.mpv.DantotsuPlayerView
import ani.dantotsu.media.anime.player.DantotsuPlayerManager
import ani.dantotsu.media.anime.player.PlayerSubtitleManager
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.media.comments.CommentsFragment
import ani.dantotsu.snackString
import ani.dantotsu.parsers.StreamTypeDetector
import ani.dantotsu.torrent.TorrentRelayLink
import okhttp3.OkHttpClient
import ani.dantotsu.notifications.comment.MediaNameFetch
import ani.dantotsu.media.manga.MangaReadFragment
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.toPx
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleOwner
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds


class MediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {
    private var isFavSyncRunning = false
    lateinit var launcher: LauncherWrapper
    lateinit var binding: ActivityMediaBinding

    /** False while the activity is bailing out of [onCreate] without any media. */
    val bindingReady get() = ::binding.isInitialized
    val safeBinding get() = if (::binding.isInitialized) binding else null
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    var selected = 0
    lateinit var navBar: AnimatedBottomBar
    var anime = true
    private var adult = false
    lateinit var media: Media
    private var compactPlayerActive = false
    private var compactPlayerManager: DantotsuPlayerManager? = null
    private var compactSubtitleManager: PlayerSubtitleManager? = null
    private val compactOkHttpClient by lazy { OkHttpClient() }
    private var compactCurrentEpisodeKey: String? = null
    private var compactSubtitlesVisible = true
    private var trailerBannerHelper: TrailerBannerHelper? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::media.isInitialized && media.name != "No media found") {
            outState.putInt("saved_media_id", media.id)
            mediaCache.put(media.id, media)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialMedia = resolveInitialMedia(savedInstanceState)
        if (initialMedia == null) {
            snackString("No media found")
            finish()
            return
        }
        this.media = initialMedia
        intent.putExtra("media", media as java.io.Serializable)
        intent.putExtra("mediaId", media.id)
        launcher = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        mediaSingleton = null
        ThemeManager(this).applyTheme(MediaSingleton.bitmap)
        initActivity(this)
        MediaSingleton.bitmap = null

        setupWindowAndViews()

        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        val isDownload = intent.getBooleanExtra("download", false)
        media.selected = model.loadSelected(media, isDownload)

        setupMediaHeader(bannerAnimations)
        setupAddToListButton(rescueMode)
        setupMediaObservers(rescueMode, bannerAnimations, isDownload)
        setupViewPagerAndTabs(rescueMode, isDownload)
        setupRefreshObserver()
    }

    private fun resolveInitialMedia(savedInstanceState: Bundle?): Media? {
        val savedId = savedInstanceState?.getInt("saved_media_id", -1)?.takeIf { it != -1 }
        var resolvedMedia: Media = intent.getSerialized("media")
            ?: savedId?.let { mediaCache.get(it) }
            ?: savedInstanceState?.getSerialized("saved_media")
            ?: mediaSingleton
            ?: emptyMedia()
        val id = intent.getIntExtra("mediaId", -1).takeIf { it != -1 }
            ?: savedId
            ?: -1
        if (id != -1 && resolvedMedia.name == "No media found") {
            resolvedMedia = fetchRescueMedia(id)
        }
        return if (resolvedMedia.name == "No media found") null else resolvedMedia
    }

    private fun fetchRescueMedia(id: Int): Media {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        return runBlocking(Dispatchers.IO) {
            if (rescueMode) {
                val animeNode = MAL.query.getAnimeDetails(id)
                if (animeNode != null) {
                    Media(animeNode, true)
                } else {
                    val mangaNode = MAL.query.getMangaDetails(id)
                    if (mangaNode != null) Media(mangaNode, false) else emptyMedia()
                }
            } else {
                Anilist.query.getMedia(id, false) ?: emptyMedia()
            }
        }
    }

    private fun setupWindowAndViews() {
        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenWidth = resources.displayMetrics.widthPixels.toFloat()
        navBar = binding.mediaBottomBar

        supportFragmentManager.addOnBackStackChangedListener {
            syncExtensionPrefsUi()
        }

        binding.mediaBottomBarContainer?.setPadding(0, 0, 0, navBarHeight)

        AndroidBug5497Workaround.assistActivity(this) { keyboardVisible ->
            navBar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        }
        binding.mediaBanner.updateLayoutParams { height += statusBarHeight }
        binding.mediaBannerNoKen.updateLayoutParams { height += statusBarHeight }
        binding.mediaTrailerWebView.updateLayoutParams { height += statusBarHeight }
        binding.mediaTrailerControls.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaHome.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.incognito.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        binding.mediaTitle.isSelected = true
        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        binding.mediaHome.setOnClickListener {
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("goToHome", true)
            }
            startActivity(homeIntent)
            finish()
        }

        binding.mediaClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupMediaHeader(bannerAnimations: Boolean) {
        if (bannerAnimations) {
            val adi = AccelerateDecelerateInterpolator()
            val animSpeed: Float = PrefManager.getVal(PrefName.AnimationSpeed)
            val generator = RandomTransitionGenerator(
                (10000 + 15000 * animSpeed).toLong(),
                adi
            )
            binding.mediaBanner.setTransitionGenerator(generator)
        }
        val banner = if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen

        binding.mediaCoverImage.loadImage(media.cover)
        binding.mediaCoverImage.setOnLongClickListener {
            val coverTitle = getString(R.string.cover, media.userPreferredName)
            ImageViewDialog.newInstance(this, coverTitle, media.cover)
        }

        blurImage(banner, media.banner ?: media.cover)
        maybeStartTrailerBanner(bannerAnimations)
        val gestureDetector = GestureDetector(this, object : GesturesListener() {
            override fun onDoubleClick(event: MotionEvent) {
                if (!(PrefManager.getVal(PrefName.BannerAnimations) as Boolean)) {
                    snackString(getString(R.string.enable_banner_animations))
                } else {
                    binding.mediaBanner.restart()
                    binding.mediaBanner.performClick()
                }
            }

            override fun onLongClick(event: MotionEvent) {
                val bannerTitle = getString(R.string.banner, media.userPreferredName)
                ImageViewDialog.newInstance(
                    this@MediaDetailsActivity,
                    bannerTitle,
                    media.banner ?: media.cover
                )
                banner.performClick()
            }
        })
        banner.setOnTouchListener { _, motionEvent -> gestureDetector.onTouchEvent(motionEvent); true }

        if (PrefManager.getVal(PrefName.Incognito)) {
            val mediaTitle = "    ${media.userPreferredName}"
            binding.mediaTitle.text = mediaTitle
            binding.incognito.visibility = View.VISIBLE
        } else {
            binding.mediaTitle.text = media.userPreferredName
        }
        binding.mediaTitle.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaTitleCollapse.text = media.userPreferredName
        binding.mediaTitleCollapse.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        MediaNameFetch.cacheMedia(
            media.id,
            media.userPreferredName,
            media.cover ?: "",
            "#222222"
        )
        binding.mediaStatus.text = media.status ?: ""
    }

    private fun createFavButton(targetMedia: Media, rescueMode: Boolean): PopImageButton? {
        return if (Anilist.userid != null && !rescueMode) {
            if (targetMedia.isFav) {
                binding.mediaFav.setImageDrawable(
                    AppCompatResources.getDrawable(this, R.drawable.ic_round_favorite_24)
                )
            }
            PopImageButton(
                scope,
                binding.mediaFav,
                R.drawable.ic_round_favorite_24,
                R.drawable.ic_round_favorite_border_24,
                R.color.bg_opp,
                R.color.violet_400,
                targetMedia.isFav
            ) {
                targetMedia.isFav = it
                Anilist.mutation.toggleFav(targetMedia.anime != null, targetMedia.id)
                Refresh.all()
            }
        } else {
            binding.mediaFav.visibility = View.GONE
            null
        }
    }

    private fun syncMediaFavStateIfNeeded(favButton: PopImageButton?, rescueMode: Boolean) {
        if (rescueMode || Anilist.userid == null || favButton == null || media.isFav || isFavSyncRunning) return
        isFavSyncRunning = true
        scope.launch {
            try {
                val favType = if (media.anime != null) {
                    ani.dantotsu.connections.anilist.AnilistMutations.FavType.ANIME
                } else {
                    ani.dantotsu.connections.anilist.AnilistMutations.FavType.MANGA
                }
                val isUserFav = withContext(Dispatchers.IO) {
                    Anilist.query.isUserFav(favType, media.id)
                }
                if (isUserFav) {
                    media.isFav = true
                    if (!favButton.clicked) favButton.clicked()
                }
            } finally {
                isFavSyncRunning = false
            }
        }
    }

    @SuppressLint("ResourceType")
    private fun updateMediaTotalText() {
        val text = SpannableStringBuilder().apply {
            val white = this@MediaDetailsActivity.getThemeColor(com.google.android.material.R.attr.colorOnBackground)
            if (media.userStatus != null) {
                append(if (media.anime != null) getString(R.string.watched_num) else getString(R.string.read_num))
                val colorSecondary = getThemeColor(com.google.android.material.R.attr.colorSecondary)
                bold { color(colorSecondary) { append("${media.userProgress}") } }
                append(
                    if (media.anime != null) getString(R.string.episodes_out_of) else getString(
                        R.string.chapters_out_of
                    )
                )
            } else {
                append(
                    if (media.anime != null) getString(R.string.episodes_total_of) else getString(
                        R.string.chapters_total_of
                    )
                )
            }
            if (media.anime != null) {
                if (media.anime!!.nextAiringEpisode != null) {
                    bold { color(white) { append("${media.anime!!.nextAiringEpisode}") } }
                    append(" / ")
                }
                bold { color(white) { append("${media.anime!!.totalEpisodes ?: "??"}") } }
            } else {
                bold { color(white) { append("${media.manga!!.totalChapters ?: "??"}") } }
            }
        }
        binding.mediaTotal.text = text
    }

    private fun updateMediaProgressUi(rescueMode: Boolean) {
        val statuses: Array<String> = resources.getStringArray(R.array.status)
        val statusStrings = if (media.manga == null) {
            resources.getStringArray(R.array.status_anime)
        } else {
            resources.getStringArray(R.array.status_manga)
        }
        val userStatus = if (media.userStatus != null) {
            statusStrings[statuses.indexOf(media.userStatus).coerceAtLeast(0)]
        } else {
            statusStrings[0]
        }

        if (media.userStatus != null) {
            binding.mediaTotal.visibility = View.VISIBLE
            binding.mediaAddToList.text = userStatus
        } else {
            binding.mediaAddToList.setText(R.string.add_list)
        }
        updateMediaTotalText()
    }

    private fun setupAddToListButton(rescueMode: Boolean) {
        updateMediaProgressUi(rescueMode)
        binding.mediaAddToList.setOnClickListener {
            if (rescueMode) {
                if (MAL.token != null) {
                    if (supportFragmentManager.findFragmentByTag("dialog") == null) {
                        MediaListDialogFragment().show(supportFragmentManager, "dialog")
                    }
                } else snackString("Please login to MAL")
            } else if (Anilist.userid != null) {
                if (supportFragmentManager.findFragmentByTag("dialog") == null) {
                    MediaListDialogFragment().show(supportFragmentManager, "dialog")
                }
            } else snackString(getString(R.string.please_login_anilist))
        }
        binding.mediaAddToList.setOnLongClickListener {
            PrefManager.setCustomVal("${media.id}_progressDialog", true)
            snackString(getString(R.string.auto_update_reset))
            true
        }
    }

    private fun setupMediaObservers(rescueMode: Boolean, bannerAnimations: Boolean, isDownload: Boolean) {
        model.getMedia().observe(this) { updatedMedia ->
            if (updatedMedia != null) {
                val oldId = media.id
                media = updatedMedia

                if (media.format?.startsWith("LOCAL") == true) {
                    binding.mediaMapping?.visibility = View.VISIBLE
                    binding.mediaMapping?.setOnClickListener {
                        val isAnime = media.anime != null
                        val isNovel = media.format == "LOCAL_NOVEL"
                        val folderName = media.folderName ?: media.name ?: media.nameRomaji
                        val dialog = LocalMappingSearchDialog.newInstance(
                            folderName = folderName,
                            isAnime = isAnime,
                            isNovel = isNovel
                        ) { _ ->
                            val updated = media.copy(id = 0)
                            model.loading = false
                            model.loadMedia(updated)
                        }
                        dialog.show(supportFragmentManager, "localMapping")
                    }
                }

                scope.launch {
                    val favIcon = createFavButton(updatedMedia, rescueMode)
                    syncMediaFavStateIfNeeded(favIcon, rescueMode)
                    if (media.isFav != favIcon?.clicked) favIcon?.clicked()
                }

                binding.mediaNotify.setOnClickListener {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, media.shareLink)
                    }
                    startActivity(Intent.createChooser(shareIntent, media.userPreferredName))
                }
                binding.mediaNotify.setOnLongClickListener {
                    openLinkInBrowser(media.shareLink)
                    true
                }
                binding.mediaCover.setOnClickListener {
                    openLinkInBrowser(media.shareLink)
                }

                if (oldId == 0 && media.id != 0) {
                    if (media.format?.startsWith("LOCAL") == true) {
                        binding.mediaCoverImage.loadImage(media.cover)
                        val bannerView = if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen
                        blurImage(bannerView, media.banner ?: media.cover)
                    }
                }
                updateMediaProgressUi(rescueMode)
                maybeStartTrailerBanner(bannerAnimations)
            }
        }
    }

    private fun maybeStartTrailerBanner(bannerAnimations: Boolean) {
        if (!::binding.isInitialized || compactPlayerActive) return
        val enabled = PrefManager.getVal<Boolean>(PrefName.TrailerBanner) &&
            !PrefManager.getVal<Boolean>(PrefName.OfflineMode) &&
            ani.dantotsu.isOnline(this)
        val trailerId = media.trailer
        if (!enabled || trailerId.isNullOrBlank()) {
            trailerBannerHelper?.hideAndFallback()
            return
        }
        val helper = trailerBannerHelper ?: TrailerBannerHelper(
            webView = binding.mediaTrailerWebView,
            controlsContainer = binding.mediaTrailerControls,
            playPauseButton = binding.mediaTrailerPlayPause,
            muteButton = binding.mediaTrailerMute,
            onFallbackToImageBanner = {
                if (!compactPlayerActive && ::binding.isInitialized) {
                    binding.mediaBanner.isVisible = bannerAnimations
                    binding.mediaBannerNoKen.isVisible = !bannerAnimations
                    if (bannerAnimations) binding.mediaBanner.resume()
                }
            },
            onTrailerReadyUi = {
                if (::binding.isInitialized && !compactPlayerActive) {
                    if (bannerAnimations) binding.mediaBanner.pause()
                    binding.mediaBanner.isVisible = false
                    binding.mediaBannerNoKen.isVisible = false
                }
            }
        ).also { trailerBannerHelper = it }
        helper.loadTrailer(trailerId)
    }

    private fun setupViewPagerAndTabs(rescueMode: Boolean, isDownload: Boolean) {
        val viewPager = binding.mediaViewPager
        viewPager.offscreenPageLimit = 2
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer())

        adult = media.isAdult
        setupViewPagerAdapter(viewPager)

        val commentsEnabled = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 && !rescueMode
        selected = if (!commentsEnabled && media.selected!!.window == 2) 1 else media.selected!!.window
        binding.mediaTitle.translationX = -screenWidth

        setupBottomNavTabs(commentsEnabled, rescueMode, isDownload)
    }

    private fun setupViewPagerAdapter(viewPager: androidx.viewpager2.widget.ViewPager2) {
        if (media.anime != null) {
            viewPager.adapter = ViewPagerAdapter(
                supportFragmentManager,
                lifecycle,
                SupportedMedia.ANIME,
                media,
                intent.getIntExtra("commentId", -1)
            )
        } else if (media.manga != null) {
            val supportedMedia = if (media.format == "NOVEL" || media.format == "LOCAL_NOVEL" || media.format == "LIGHT_NOVEL") {
                SupportedMedia.NOVEL
            } else {
                SupportedMedia.MANGA
            }
            viewPager.adapter = ViewPagerAdapter(
                supportFragmentManager,
                lifecycle,
                supportedMedia,
                media,
                intent.getIntExtra("commentId", -1)
            )
            anime = false
        }
    }

    private fun setupBottomNavTabs(commentsEnabled: Boolean, rescueMode: Boolean, isDownload: Boolean) {
        val viewPager = binding.mediaViewPager
        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val watchTab = if (anime) {
            navBar.createTab(R.drawable.ic_round_movie_filter_24, R.string.watch, R.id.watch)
        } else if (media.format == "NOVEL" || media.format == "LOCAL_NOVEL" || media.format == "LIGHT_NOVEL") {
            navBar.createTab(R.drawable.ic_round_book_24, R.string.read, R.id.read)
        } else {
            navBar.createTab(R.drawable.ic_round_import_contacts_24, R.string.read, R.id.read)
        }
        val commentTab = navBar.createTab(R.drawable.ic_round_comment_24, R.string.comments, R.id.comment)
        navBar.addTab(infoTab)
        navBar.addTab(watchTab)
        if (commentsEnabled) {
            navBar.addTab(commentTab)
        }
        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = PrefManager.getVal(PrefName.ContinueMedia)
            selected = 1
        }
        if (intent.getStringExtra("FRAGMENT_TO_LOAD") != null && commentsEnabled) {
            selected = 2
        }
        if (viewPager.currentItem != selected) {
            viewPager.post { viewPager.setCurrentItem(selected, false) }
        }
        binding.commentInputLayout.isVisible = selected == 2

        if (selected == 2 && !commentsEnabled) {
            selected = 1
        }
        navBar.selectTabAt(selected)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                selected = newIndex
                binding.commentInputLayout.isVisible = selected == 2
                viewPager.setCurrentItem(selected, true)
                val sel = model.loadSelected(media, isDownload)
                sel.window = selected
                model.saveSelected(media.id, sel)
            }
        })
    }

    private fun setupRefreshObserver() {
        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::navBar.isInitialized) return
        val rightMargin = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) navBarHeight else 0
        val bottomMargin = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) 0 else navBarHeight
        val params: ViewGroup.MarginLayoutParams =
            navBar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(right = rightMargin, bottom = bottomMargin)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        syncExtensionPrefsUi()
        binding.root.requestLayout()
    }

    override fun onPause() {
        super.onPause()
        trailerBannerHelper?.pause()
        if (compactPlayerActive) {
            saveCompactPosition()
            compactPlayerManager?.pause()
        }
    }

    fun startCompactPlayback(targetMedia: Media): Boolean {
        if (!::binding.isInitialized) return false
        if (targetMedia.anime == null) return false
        if (!PrefManager.getVal<Boolean>(PrefName.CompactPlayerViewEnabled)) return false
        if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return false

        val epKey = targetMedia.anime?.selectedEpisode ?: return false
        val ep = targetMedia.anime?.episodes?.getEpisode(epKey) ?: model.getEpisode().value ?: return false
        val ext = ep.extractors?.find { it.server.name == ep.selectedExtractor }
            ?: ep.extractors?.firstOrNull { it.isPreferred }
            ?: ep.extractors?.firstOrNull()
            ?: return false
        val video = ext.videos.getOrNull(ep.selectedVideo) ?: ext.videos.firstOrNull() ?: return false

        val rawUrl = video.file.url
        val isTorrent = TorrentRelayLink.parse(rawUrl) != null ||
            StreamTypeDetector.isTorrentStreamUrl(rawUrl) ||
            rawUrl.startsWith("magnet:", ignoreCase = true) ||
            rawUrl.endsWith(".torrent", ignoreCase = true)
        if (isTorrent || video.drm != null || ext.server.offline) {
            return false
        }

        trailerBannerHelper?.hideAndFallback()
        this.media = targetMedia
        compactCurrentEpisodeKey = epKey

        val container = binding.compactPlayerContainer ?: return false
        val playerView = binding.compactPlayerView ?: return false
        val screenW = resources.displayMetrics.widthPixels
        playerView.updateLayoutParams { height = screenW * 9 / 16 }
        container.setPadding(0, statusBarHeight, 0, 0)

        binding.mediaBanner.isVisible = false
        binding.mediaBannerNoKen.isVisible = false
        binding.mediaCollapseContainer.isVisible = false
        binding.mediaCover.isVisible = false
        binding.mediaClose.isVisible = false
        binding.mediaHome.isVisible = false
        binding.incognito.isVisible = false
        container.isVisible = true

        (binding.mediaCollapsing.layoutParams as? AppBarLayout.LayoutParams)?.let { lp ->
            lp.scrollFlags = 0
            binding.mediaCollapsing.layoutParams = lp
        }
        binding.mediaAppBar.setExpanded(true, false)

        if (!compactPlayerActive) {
            compactPlayerActive = true
            initCompactPlayerControls(playerView)
            model.getEpisode().observe(this) { updatedEp ->
                if (!compactPlayerActive || updatedEp == null) return@observe
                val updatedKey = updatedEp.number
                val uExt = updatedEp.extractors?.find { it.server.name == updatedEp.selectedExtractor }
                    ?: updatedEp.extractors?.firstOrNull { it.isPreferred }
                    ?: updatedEp.extractors?.firstOrNull()
                    ?: return@observe
                val uVideo = uExt.videos.getOrNull(updatedEp.selectedVideo) ?: uExt.videos.firstOrNull() ?: return@observe
                if (updatedKey != compactCurrentEpisodeKey || uVideo.file.url != compactPlayerManager?.currentVideo?.file?.url) {
                    compactCurrentEpisodeKey = updatedKey
                    playVideoInCompact(uExt, uVideo, updatedEp)
                }
            }
        }

        playVideoInCompact(ext, video, ep)
        return true
    }

    private fun initCompactPlayerControls(playerView: DantotsuPlayerView) {
        playerView.setCompactMode(true)
        playerView.controllerShowTimeoutMs = 4000
        val customSub = playerView.findViewById<Xubtitle>(R.id.customSubtitleView)
        val subMgr = PlayerSubtitleManager(this, playerView, customSub, model) {
            compactPlayerManager?.mpvView
        }
        compactSubtitleManager = subMgr
        compactPlayerManager = DantotsuPlayerManager(this, playerView, subMgr, compactOkHttpClient) { msg ->
            snackString("Player Error: $msg")
        }

        playerView.findViewById<View>(R.id.exo_back)?.setOnClickListener {
            stopCompactPlayback()
        }
        playerView.findViewById<View>(R.id.exo_screen)?.setOnClickListener {
            expandCompactToFullscreen()
        }
        playerView.findViewById<View>(R.id.exo_source)?.setOnClickListener {
            val epKey = compactCurrentEpisodeKey ?: media.anime?.selectedEpisode ?: return@setOnClickListener
            media.selected?.server = null
            media.selected?.let { model.saveSelected(media.id, it) }
            model.onEpisodeClick(media, epKey, supportFragmentManager, launch = false)
        }
        val compactSpeeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        var speedIdx = 0
        playerView.findViewById<View>(R.id.exo_playback_speed)?.setOnClickListener {
            speedIdx = (speedIdx + 1) % compactSpeeds.size
            val nextSpeed = compactSpeeds[speedIdx]
            compactPlayerManager?.setPlaybackSpeed(nextSpeed)
            snackString("${nextSpeed}x")
        }
        playerView.findViewById<View>(R.id.exo_sub)?.setOnClickListener {
            compactSubtitlesVisible = !compactSubtitlesVisible
            compactPlayerManager?.mpvView?.setSubVisibility(compactSubtitlesVisible)
            snackString(if (compactSubtitlesVisible) "Subtitles ON" else "Subtitles OFF")
        }
        val seekStepMs = 10_000L
        playerView.findViewById<View>(R.id.exo_fast_rewind_button)?.setOnClickListener {
            val mpv = compactPlayerManager?.mpvView ?: return@setOnClickListener
            mpv.seekTo((mpv.currentPosition.value - seekStepMs).coerceAtLeast(0L))
        }
        playerView.findViewById<View>(R.id.exo_fast_forward_button)?.setOnClickListener {
            val mpv = compactPlayerManager?.mpvView ?: return@setOnClickListener
            mpv.seekTo(mpv.currentPosition.value + seekStepMs)
        }
        playerView.findViewById<View>(R.id.exo_skip)?.setOnClickListener {
            val skipSec = PrefManager.getVal<Int>(PrefName.SkipTime).coerceAtLeast(30)
            val mpv = compactPlayerManager?.mpvView ?: return@setOnClickListener
            mpv.seekTo(mpv.currentPosition.value + skipSec * 1000L)
        }
    }

    private fun playVideoInCompact(
        ext: ani.dantotsu.parsers.VideoExtractor,
        video: ani.dantotsu.parsers.Video,
        ep: ani.dantotsu.media.anime.Episode
    ) {
        val subMgr = compactSubtitleManager ?: return
        val playerMgr = compactPlayerManager ?: return
        val extSubs = subMgr.buildExternalSubtitles(
            subtitles = ext.subtitles,
            embedUrl = ext.server.embed.url,
            currentVideoUrl = video.file.url,
            hasExtSubtitles = ext.subtitles.isNotEmpty(),
            defaultSubLanguage = "English"
        )
        val epKey = compactCurrentEpisodeKey ?: ep.number
        val savedPos = PrefManager.getCustomVal("${media.id}_$epKey", 0L)
        binding.compactPlayerView?.findViewById<android.widget.TextView>(R.id.exo_anime_title)?.text =
            "${media.userPreferredName} - Ep ${ep.number}"

        playerMgr.loadVideo(
            video = video,
            startPositionMs = savedPos,
            externalSubtitles = extSubs,
            audioTracks = ext.audioTracks,
            speed = 1.0f
        )
        subMgr.setupSubFormatting()
    }

    private fun saveCompactPosition() {
        val epKey = compactCurrentEpisodeKey ?: media.anime?.selectedEpisode ?: return
        val pos = compactPlayerManager?.mpvView?.currentPosition?.value ?: 0L
        if (pos > 0L) {
            PrefManager.setCustomVal("${media.id}_$epKey", pos)
        }
    }

    private fun expandCompactToFullscreen() {
        saveCompactPosition()
        val resumePos = compactPlayerManager?.mpvView?.currentPosition?.value ?: 0L
        stopCompactPlayback()
        MpvPlayerActivity.media = media
        MpvPlayerActivity.initialized = true
        MpvPlayerActivity.targetStartPosition = resumePos
        startActivity(Intent(this, MpvPlayerActivity::class.java))
    }

    fun stopCompactPlayback() {
        if (!::binding.isInitialized) return
        if (compactPlayerActive) {
            saveCompactPosition()
        }
        compactPlayerActive = false
        compactPlayerManager?.release()
        compactSubtitleManager?.release()
        compactPlayerManager = null
        compactSubtitleManager = null

        binding.compactPlayerContainer?.isVisible = false
        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        binding.mediaBanner.isVisible = bannerAnimations
        binding.mediaBannerNoKen.isVisible = !bannerAnimations
        binding.mediaCollapseContainer.isVisible = true
        binding.mediaCover.isVisible = true
        binding.mediaClose.isVisible = true
        binding.mediaHome.isVisible = true
        binding.incognito.isVisible = PrefManager.getVal(PrefName.Incognito)

        (binding.mediaCollapsing.layoutParams as? AppBarLayout.LayoutParams)?.let { lp ->
            lp.scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED or
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
            binding.mediaCollapsing.layoutParams = lp
        }
    }

    private fun syncExtensionPrefsUi() {
        if (!::binding.isInitialized) return
        val hasExtFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentExtensionsContainer) != null

        binding.fragmentExtensionsContainer.isVisible = hasExtFragment
        binding.mediaAppBar.isVisible = !hasExtFragment
        binding.mediaViewPager.isVisible = !hasExtFragment
        binding.mediaCover.isVisible = !hasExtFragment
        binding.mediaClose.isVisible = !hasExtFragment
        binding.commentInputLayout.isVisible = !hasExtFragment && selected == 2
        if (::navBar.isInitialized) {
            navBar.isVisible = !hasExtFragment
            if (!hasExtFragment) navBar.selectTabAt(selected)
        }
    }

    private enum class SupportedMedia {
        ANIME, MANGA, NOVEL
    }

    // ViewPager
    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val mediaType: SupportedMedia,
        private val media: Media,
        private val commentId: Int
    ) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 && !PrefManager.getVal<Boolean>(PrefName.RescueMode)) 3 else 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MediaInfoFragment()
            1 -> when (mediaType) {
                SupportedMedia.ANIME -> AnimeWatchFragment()
                SupportedMedia.MANGA -> MangaReadFragment()
                SupportedMedia.NOVEL -> NovelReadFragment()
            }

            2 -> { // Index 2
                if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 && !PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                    val fragment = CommentsFragment()
                    val bundle = Bundle()
                    bundle.putInt("mediaId", media.id)
                    bundle.putString("mediaName", media.mainName())
                    bundle.putString("mediaFormat", media.format)
                    if (commentId != -1) bundle.putInt("commentId", commentId)
                    fragment.arguments = bundle
                    fragment
                } else {
                    MediaInfoFragment() // Fallback to Info tab if comments are disabled
                }
            }

            else -> MediaInfoFragment()
        }
    }

    //Collapsing UI Stuff
    private var isCollapsed = false
    private val percent = 45
    private var mMaxScrollSize = 0
    private var screenWidth: Float = 0f

    override fun onOffsetChanged(appBar: AppBarLayout, i: Int) {
        if (!::binding.isInitialized) return
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        val percentage = abs(i) * 100 / mMaxScrollSize

        binding.mediaCover.visibility =
            if (binding.mediaCover.scaleX == 0f) View.GONE else View.VISIBLE
        val animSpeed: Float = PrefManager.getVal(PrefName.AnimationSpeed)
        val duration = (200 * animSpeed).toLong()
        if (percentage >= percent && !isCollapsed) {
            isCollapsed = true
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            binding.mediaBanner.pause()
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", 0f)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f)
                .setDuration(duration).start()
            if (PrefManager.getVal(PrefName.BannerAnimations)) binding.mediaBanner.resume()
        }
        if (percentage == 1 && model.scrolledToTop.value != false) model.scrolledToTop.postValue(
            false
        )
        if (percentage == 0 && model.scrolledToTop.value != true) model.scrolledToTop.postValue(true)
    }

    class PopImageButton(
        private val scope: CoroutineScope,
        private val image: ImageView,
        private val d1: Int,
        private val d2: Int,
        private val c1: Int,
        private val c2: Int,
        var clicked: Boolean,
        needsInitialClick: Boolean = false,
        callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        fun setState(state: Boolean) {
            if (clicked != state) {
                clicked = state
                scope.launch {
                    clicked()
                }
            }
        }

        init {
            enabled(true)
            if (needsInitialClick) {
                scope.launch {
                    clicked()
                }
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) {
                            callback.invoke(clicked)
                        }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
            delay(100.milliseconds)

            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c1),
                    ContextCompat.getColor(context, c2)
                ).setDuration(120).start()
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.5f).setDuration(120).start()
            ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.5f).setDuration(100).start()
            delay(120.milliseconds)
            ObjectAnimator.ofFloat(image, "scaleX", 1.5f, 1f).setDuration(100).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1.5f, 1f).setDuration(100).start()
            delay(200.milliseconds)
            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c2),
                    ContextCompat.getColor(context, c1)
                ).setDuration(200).start()
            }
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if (disabled) 0.33f else 1f
        }
    }

    override fun onProvideAssistContent(outContent: android.app.assist.AssistContent?) {
        super.onProvideAssistContent(outContent)
        outContent?.intent = null
    }

    override fun onDestroy() {
        trailerBannerHelper?.release()
        trailerBannerHelper = null
        stopCompactPlayback()
        super.onDestroy()
        Refresh.activity.remove(this.hashCode())
        mediaSingleton = null
        MediaSingleton.bitmap = null
        MediaSingleton.media = null
        if (::binding.isInitialized) {
            binding.mediaViewPager.adapter = null
        }
    }

    companion object {
        var mediaSingleton: Media? = null
        private val mediaCache = android.util.LruCache<Int, Media>(10)
    }
}

fun setupMediaScrollTop(
    recyclerView: RecyclerView,
    scrollTopView: View,
    gridLayoutManager: GridLayoutManager,
    navBarHeight: Int,
    scrolledToTopLiveData: LiveData<Boolean>?,
    lifecycleOwner: LifecycleOwner
) {
    scrollTopView.setOnClickListener {
        recyclerView.scrollToPosition(10)
        recyclerView.smoothScrollToPosition(0)
    }
    recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            val position = gridLayoutManager.findFirstVisibleItemPosition()
            if (position > 2) {
                scrollTopView.translationY = -(navBarHeight + 12.toPx).toFloat()
                scrollTopView.visibility = View.VISIBLE
            } else {
                scrollTopView.visibility = View.GONE
            }
        }
    })
    scrolledToTopLiveData?.observe(lifecycleOwner) {
        if (it) recyclerView.scrollToPosition(0)
    }
}

