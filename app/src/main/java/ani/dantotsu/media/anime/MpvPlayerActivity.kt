package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.graphics.drawable.Icon
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.util.Rational
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.FileUrl
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.subtitles.OpenSubRestItem
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.SubSourceSub
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.databinding.ActivityMpvPlayerBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.anime.AnimeDownloader
import ani.dantotsu.download.video.Helper
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.isOnline
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.anime.mpv.AudioTrack
import ani.dantotsu.media.anime.mpv.DantotsuPlayerView
import ani.dantotsu.media.anime.mpv.DantotsuTimeBar
import ani.dantotsu.media.anime.mpv.ExternalSubtitle
import ani.dantotsu.media.anime.mpv.PlaybackState
import ani.dantotsu.media.anime.mpv.SubtitleTrack
import ani.dantotsu.media.anime.mpv.VideoScaleMode
import ani.dantotsu.media.anime.player.CastScreenView
import ani.dantotsu.media.anime.player.DantotsuPlayerManager
import ani.dantotsu.media.anime.player.PlayerAniSkipManager
import ani.dantotsu.media.anime.player.PlayerCastManager
import ani.dantotsu.media.anime.player.PlayerDiscordManager
import ani.dantotsu.media.anime.player.PlayerGestureManager
import ani.dantotsu.media.anime.player.PlayerProgressManager
import ani.dantotsu.media.anime.player.PlayerScreenshotManager
import ani.dantotsu.media.anime.player.PlayerSubtitleManager
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.StreamTypeDetector
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.together.TogetherStream
import ani.dantotsu.together.WatchRoomBottomSheet
import ani.dantotsu.together.WatchRoomManager
import ani.dantotsu.together.WatchRoomPlayerBridge
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.shareImage
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.torrent.TorrentLinks
import ani.dantotsu.torrent.TorrentRelayLink
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.bumptech.glide.Glide
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ClickableViewAccessibility")
class MpvPlayerActivity : AppCompatActivity() {

    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"
    private val playerFullscreen = "playerFullscreen"
    private val playerOnPlay = "playerOnPlay"

    lateinit var playerManager: DantotsuPlayerManager
        private set
    lateinit var subtitleManager: PlayerSubtitleManager
        private set
    lateinit var gestureManager: PlayerGestureManager
        private set
    lateinit var aniSkipManager: PlayerAniSkipManager
        private set
    lateinit var discordManager: PlayerDiscordManager
        private set
    lateinit var castManager: PlayerCastManager
        private set
    lateinit var progressManager: PlayerProgressManager
        private set

    private lateinit var binding: ActivityMpvPlayerBinding
    lateinit var playerView: DantotsuPlayerView
        private set
    private lateinit var exoPlay: ImageButton
    private lateinit var exoSource: ImageButton
    private lateinit var exoSettings: ImageButton
    private lateinit var exoSubtitle: ImageButton
    private lateinit var exoAudioTrack: ImageButton
    private lateinit var exoRotate: ImageButton
    private lateinit var exoSpeed: ImageButton
    private lateinit var exoScreen: ImageButton
    private lateinit var exoNext: ImageButton
    private lateinit var exoPrev: ImageButton
    private lateinit var exoSkipOpEd: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoScreenshot: ImageButton
    private lateinit var exoTogether: ImageButton
    private lateinit var screenshotManager: PlayerScreenshotManager
    private lateinit var exoBrightness: Slider
    private lateinit var exoVolume: Slider
    private lateinit var exoBrightnessCont: View
    private lateinit var exoVolumeCont: View
    private lateinit var exoSkip: View
    private lateinit var skipTimeButton: View
    private lateinit var skipTimeText: TextView
    private lateinit var timeStampText: TextView
    private lateinit var animeTitle: TextView
    private lateinit var videoInfo: TextView
    private lateinit var episodeTitle: Spinner
    private lateinit var customSubtitleView: Xubtitle
    private lateinit var customCastButton: CustomCastButton
    private lateinit var castScreenView: CastScreenView

    private var orientationListener: OrientationEventListener? = null
    private var hasExtSubtitles = false
    var currentEmbeddedSubtitleTracks: List<SubtitleTrack> = emptyList()
        private set
    private var currentAudioTracks: List<AudioTrack> = emptyList()

    lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String, Episode>
    private lateinit var episodeArr: List<String>
    private lateinit var episodeTitleArr: ArrayList<String>
    private var currentEpisodeIndex = 0
    private var epChanging = false

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null

    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private var isFullscreen: Int = 0
    private var isPlayerPlaying = true
    private var changingServer = false
    private var interacted = true
    private var pipEnabled = false
    private var aspectRatio = Rational(16, 9)
    private var pipReceiver: BroadcastReceiver? = null
    private var isBuffering = true
    private var playerErrorRetryCount = 0
    private var rotation = 0
    private var wasPlaying = false
    private var wasPlayingOnPause = false
    private var isStandaloneMode = false
    private var isTogetherStreamMode = false

    private val watchRoomBridge = object : WatchRoomPlayerBridge {
        override fun isPlayerReady(): Boolean = this@MpvPlayerActivity::playerManager.isInitialized && playerManager.isInitialized
        override fun getCurrentPositionMs(): Long = if (isPlayerReady()) playerManager.mpvView.currentPosition.value else 0L
        override fun isPlayerPaused(): Boolean = if (isPlayerReady()) !playerManager.mpvView.playWhenReady else true
        override fun syncPlay() {
            if (isPlayerReady()) playerManager.play()
        }
        override fun syncPause() {
            if (isPlayerReady()) playerManager.pause()
        }
        override fun syncSeekTo(positionMs: Long) {
            if (isPlayerReady()) playerManager.seekTo(positionMs)
        }
        override fun syncPlaybackSpeed(speed: Float) {
            if (isPlayerReady()) playerManager.setPlaybackSpeed(speed)
        }
        override fun getDefaultPlaybackSpeed(): Float = resolvePlaybackSpeed()
        override fun getCurrentShareableStream(): TogetherStream? {
            if (isStandaloneMode && !isTogetherStreamMode) return null
            val currentVideo = video ?: return null
            val rawUrl = currentVideo.file.url
            val shareUrl = if (TogetherStream.isValidShareUrl(rawUrl)) {
                rawUrl
            } else {
                val magnetCandidate = episode.extra?.get("torrentUrl")
                    ?: extractor?.videos?.getOrNull(episode.selectedVideo)?.file?.url
                magnetCandidate?.takeIf { TogetherStream.isValidShareUrl(it) } ?: return null
            }
            val headers = currentVideo.file.headers.filterKeys {
                it.equals("Referer", ignoreCase = true) ||
                    it.equals("Origin", ignoreCase = true) ||
                    it.equals("User-Agent", ignoreCase = true)
            }
            val subs = extractor?.subtitles.orEmpty().mapNotNull { sub ->
                sub.file.url.takeIf { TogetherStream.isValidShareUrl(it) }?.let { url ->
                    ani.dantotsu.together.TogetherSubtitle(url = url, language = sub.language)
                }
            }
            return TogetherStream(
                mediaTitle = media.userPreferredName,
                episodeLabel = "Episode $currentEpisodeNumber",
                videoUrl = shareUrl,
                headers = headers,
                subtitles = subs
            )
        }
    }

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    val model: MediaDetailsViewModel by viewModels()
    private val client = OkHttpClient()

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        playerView.useController = true
        uri?.let { applyLocalSubtitle(it) }
    }

    private val currentEpisodeNumber: String
        get() = if (this::episode.isInitialized) episode.number else media.anime?.selectedEpisode ?: "1"

    private fun getAvailablePlaybackSpeeds(): Array<Float> {
        return if (PrefManager.getVal(PrefName.CursedSpeeds)) {
            arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
        } else {
            arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
        }
    }

    private val onChangeSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        playerView.useController = true
        playerManager.mpvView.applyRuntimePreferences()
        subtitleManager.setupSubFormatting()
        subtitleManager.applySubtitleStyles(customSubtitleView)
        if (playerManager.isInitialized) playerManager.play()
    }

    companion object {
        var initialized = false
        lateinit var media: Media
        var targetStartPosition: Long? = null
        private const val MAX_PLAYER_ERROR_RETRIES = 1
        private const val MAX_TORRENT_ERROR_RETRIES = 4
        private val livePlayers = java.util.concurrent.atomic.AtomicInteger(0)
        private val SUBTITLE_LANGUAGES = arrayOf(
            "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
            "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
            "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
            "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
            "Turkish", "Ukrainian", "Urdu", "Vietnamese"
        )
        const val ACTION_PIP_PREV_EP = "ani.dantotsu.PIP_PREV_EP"
        const val ACTION_PIP_REWIND = "ani.dantotsu.PIP_REWIND"
        const val ACTION_PIP_PLAY_PAUSE = "ani.dantotsu.PIP_PLAY_PAUSE"
        const val ACTION_PIP_SKIP = "ani.dantotsu.PIP_SKIP"
        const val ACTION_PIP_NEXT_EP = "ani.dantotsu.PIP_NEXT_EP"
        const val EXTRA_STANDALONE_MODE = "standalone_mode"
        const val EXTRA_TOGETHER_MODE = "together_mode"

        fun launchStandaloneStream(
            context: Context,
            title: String,
            subtitle: String,
            streamUrl: String,
            headers: Map<String, String> = emptyMap(),
            coverUrl: String? = null
        ) {
            launchDirectPlayback(
                context = context,
                title = title,
                episodeLabel = "1",
                subtitle = subtitle,
                streamUrl = streamUrl,
                headers = headers,
                subtitles = emptyList(),
                coverUrl = coverUrl,
                isTogether = false
            )
        }

        fun launchTogetherStream(context: Context, stream: TogetherStream) {
            val mappedSubs = stream.subtitles.map { sub ->
                val subType = when {
                    sub.url.contains(".ass", ignoreCase = true) || sub.url.contains(".ssa", ignoreCase = true) -> SubtitleType.ASS
                    sub.url.contains(".srt", ignoreCase = true) -> SubtitleType.SRT
                    else -> SubtitleType.VTT
                }
                Subtitle(sub.language, FileUrl(sub.url), subType)
            }
            launchDirectPlayback(
                context = context,
                title = stream.mediaTitle.ifBlank { "WatchRoom" },
                episodeLabel = stream.episodeLabel.ifBlank { "1" },
                subtitle = stream.episodeLabel,
                streamUrl = stream.videoUrl,
                headers = stream.headers,
                subtitles = mappedSubs,
                coverUrl = null,
                isTogether = true
            )
        }

        private fun launchDirectPlayback(
            context: Context,
            title: String,
            episodeLabel: String,
            subtitle: String,
            streamUrl: String,
            headers: Map<String, String>,
            subtitles: List<Subtitle>,
            coverUrl: String?,
            isTogether: Boolean
        ) {
            val streamFormat = when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
                streamUrl.contains(".mpd", ignoreCase = true) -> VideoType.DASH
                else -> VideoType.CONTAINER
            }
            val video = Video(
                quality = null,
                format = streamFormat,
                file = FileUrl(streamUrl, headers),
                preferred = true
            )
            val extractor = object : VideoExtractor() {
                override val server = VideoServer(
                    name = if (isTogether) "WatchRoom" else "Theme",
                    embed = FileUrl(streamUrl, headers)
                )
                override suspend fun extract(): VideoContainer = VideoContainer(listOf(video), subtitles)
            }.apply {
                videos = listOf(video)
                this.subtitles = subtitles
            }
            val ep = Episode(
                number = episodeLabel,
                title = subtitle,
                selectedExtractor = extractor.server.name,
                selectedVideo = 0,
                allStreams = true,
                extractors = mutableListOf(extractor)
            )
            media = Media(
                id = -1,
                name = title,
                nameRomaji = title,
                userPreferredName = title,
                cover = coverUrl,
                isAdult = false,
                anime = Anime(
                    totalEpisodes = 1,
                    selectedEpisode = episodeLabel,
                    episodes = mutableMapOf(episodeLabel to ep)
                )
            )
            initialized = true
            targetStartPosition = 0L
            context.startActivity(
                Intent(context, MpvPlayerActivity::class.java).apply {
                    putExtra(EXTRA_STANDALONE_MODE, true)
                    putExtra(EXTRA_TOGETHER_MODE, isTogether)
                }
            )
        }
    }

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = window.decorView.rootWindowInsets?.displayCutout
            if (displayCutout != null && displayCutout.boundingRects.size > 0) {
                val notch = min(
                    displayCutout.boundingRects[0].width(),
                    displayCutout.boundingRects[0].height()
                )
                if (this::gestureManager.isInitialized) {
                    gestureManager.updateNotchHeight(notch)
                }
            }
        }
        super.onAttachedToWindow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        livePlayers.incrementAndGet()

        if (!initialized) {
            startMainActivity(this)
            finish()
            return
        }

        isStandaloneMode = intent.getBooleanExtra(EXTRA_STANDALONE_MODE, false)
        isTogetherStreamMode = intent.getBooleanExtra(EXTRA_TOGETHER_MODE, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        ThemeManager(this).applyTheme()
        binding = ActivityMpvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerView = binding.playerView
        hideSystemBarsExtendView()

        bindControlViews()
        initPlayerManagers()
        observeMpvCallbacks()
        setupScreenshotActions()
        setupCastIntegration()
        setupOrientationTracking(savedInstanceState)
        setupPlayerControlListeners()
        if (!setupEpisodeListAndNavigation()) return
        setupDisplayAndSettings()
        initEpisodeProgressAndLaunch()
    }

    private fun bindControlViews() {
        exoPlay = playerView.findViewById(R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoAudioTrack = playerView.findViewById(R.id.exo_audio)

        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoSpeed = playerView.findViewById(R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoPip = playerView.findViewById(R.id.exo_pip)
        exoScreenshot = playerView.findViewById(R.id.exo_screenshot)
        exoTogether = playerView.findViewById(R.id.exo_together)
        exoSkipOpEd = playerView.findViewById(R.id.exo_skip_op_ed)
        exoSkip = playerView.findViewById(R.id.exo_skip)
        skipTimeButton = playerView.findViewById(R.id.exo_skip_timestamp)
        skipTimeText = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
        timeStampText = playerView.findViewById(R.id.exo_time_stamp_text)
        customSubtitleView = playerView.findViewById(R.id.customSubtitleView)
        animeTitle = playerView.findViewById(R.id.exo_anime_title)
        episodeTitle = playerView.findViewById(R.id.exo_ep_sel)
        customCastButton = playerView.findViewById(R.id.exo_cast)
        playerView.controllerShowTimeoutMs = 5000
        exoSource.setOnClickListener { sourceClick() }
    }

    private fun initPlayerManagers() {
        subtitleManager = PlayerSubtitleManager(this, playerView, customSubtitleView, model) {
            playerManager.mpvView
        }
        playerManager = DantotsuPlayerManager(this, playerView, subtitleManager, client) { errorMsg ->
            onPlayerError(errorMsg)
        }
        gestureManager = PlayerGestureManager(
            this, playerView, exoBrightnessCont, exoVolumeCont, exoBrightness, exoVolume,
            { playerManager.mpvView }, { playerManager.isInitialized }
        )
        aniSkipManager = PlayerAniSkipManager(
            this, playerView, model, exoSkipOpEd, exoSkip, skipTimeButton, skipTimeText, timeStampText,
            { playerManager.mpvView }
        )
        discordManager = PlayerDiscordManager(this)
        progressManager = PlayerProgressManager(
            this, model, { playerManager.mpvView }, { playerManager.isInitialized }
        )
        screenshotManager = PlayerScreenshotManager(this, playerView)
        castManager = PlayerCastManager(this, playerView) { isPlaying ->
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (initialized && this::episode.isInitialized) {
                discordManager.updatePresence(media, episode, playerManager.mpvView, isPlaying)
            }
        }
    }

    private fun updatePlayingUi(isPlaying: Boolean) {
        if (isPlayerPlaying == isPlaying) return
        isPlayerPlaying = isPlaying
        playerView.keepScreenOn = isPlaying
        (exoPlay.drawable as? Animatable)?.start()
        if (!isDestroyed) {
            Glide.with(this)
                .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                .into(exoPlay)
        }
        val mpv = playerManager.mpvView
        if (initialized && !isStandaloneMode && this::episode.isInitialized) {
            discordManager.updatePresence(media, episode, mpv, isPlaying)
        }
        updatePipActions(isPlaying)
    }

    private fun observeMpvCallbacks() {
        val mpv = playerManager.mpvView
        mpv.onFileLoaded = { handleFileLoaded() }
        mpv.onEnded = { handlePlaybackEnded() }
        mpv.onError = { msg -> onPlayerError(msg) }

        lifecycleScope.launch {
            mpv.playbackState.collectLatest { state ->
                when (state) {
                    PlaybackState.PLAYING -> {
                        isBuffering = false
                        updatePlayingUi(true)
                        val dur = mpv.duration.value
                        if (progressManager.episodeLength <= 0f && dur > 0) {
                            progressManager.episodeLength = dur.toFloat()
                        }
                        checkAndLoadTimestamps()
                    }
                    PlaybackState.BUFFERING -> {
                        isBuffering = true
                    }
                    PlaybackState.PAUSED -> {
                        isBuffering = false
                        updatePlayingUi(false)
                    }
                    PlaybackState.ENDED -> {
                        isBuffering = false
                        updatePlayingUi(false)
                    }
                    PlaybackState.IDLE -> {}
                }
            }
        }

        lifecycleScope.launch {
            mpv.isPlaying.collectLatest { isPlaying ->
                if (!isBuffering) {
                    updatePlayingUi(isPlaying)
                }
            }
        }

        lifecycleScope.launch {
            mpv.subtitleTracks.collectLatest { tracks ->
                currentEmbeddedSubtitleTracks = tracks
                subtitleManager.checkTracksForPendingSubtitles(tracks)
                exoSubtitle.isVisible = true
                exoSubtitle.setOnClickListener { subClick() }
            }
        }

        lifecycleScope.launch {
            mpv.audioTracks.collectLatest { tracks ->
                currentAudioTracks = tracks
                exoAudioTrack.isVisible = tracks.size > 1
                exoAudioTrack.setOnClickListener {
                    TrackGroupDialogFragment(this@MpvPlayerActivity, tracks)
                        .show(supportFragmentManager, "dialog")
                }
            }
        }
    }

    private fun handlePlaybackEnded() {
        if (isStandaloneMode) return
        progressManager.updateAniProgress(forceComplete = true)
        if (PrefManager.getVal<Boolean>(PrefName.AutoPlay)) {
            if (interacted) {
                progressManager.nextEpisode { i ->
                    changeEpisode(currentEpisodeIndex + i)
                }
            } else {
                toast(getString(R.string.autoplay_cancelled))
            }
        }
    }

    private fun handleFileLoaded() {
        val mpv = playerManager.mpvView
        val duration = mpv.duration.value
        val selEp = media.anime?.selectedEpisode
        if (!isStandaloneMode && selEp != null && duration > 0L) {
            PrefManager.setCustomVal("${media.id}_${selEp}_max", duration)
            val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                if (it % 1 == 0f) it.toInt().toString() else it.toString()
            }
            if (cleanEp != null && cleanEp != selEp) {
                PrefManager.setCustomVal("${media.id}_${cleanEp}_max", duration)
            }
        }

        val width = mpv.getVideoWidth()
        val height = mpv.getVideoHeight()
        if (width > 0 && height > 0) {
            aspectRatio = Rational(width, height)
            videoInfo.text = getString(R.string.video_quality, height)
        }

        if (duration > 0L && (duration < playbackPosition || playbackPosition > duration.toFloat() * 0.92f)) {
            playbackPosition = 0L
            mpv.seekTo(0L)
        }

        checkAndLoadTimestamps()
    }

    fun selectAudioTrack(trackId: Int) {
        playerManager.selectAudioTrack(trackId)
    }

    private fun setupScreenshotActions() {
        exoScreenshot.setOnClickListener {
            val title = media.userPreferredName.ifBlank { media.mainName() }
            val epNum = currentEpisodeNumber
            screenshotManager.takeScreenshot(title, epNum)
        }
        exoScreenshot.setOnLongClickListener {
            val title = media.userPreferredName.ifBlank { media.mainName() }
            val epNum = currentEpisodeNumber
            screenshotManager.takeScreenshot(title, epNum) { bitmap, _ ->
                if (bitmap != null) {
                    shareImage("$title - Episode $epNum", bitmap, this)
                }
            }
            true
        }
    }

    private fun setupCastIntegration() {
        castScreenView = CastScreenView(
            activity = this,
            binding = binding.castScreenView,
            castManager = castManager,
            onPlayPauseClick = {
                castManager.togglePlayPause()
            },
            onPreviousClick = {
                if (currentEpisodeIndex > 0) {
                    changeEpisode(currentEpisodeIndex - 1)
                } else {
                    snackString(getString(R.string.first_episode), this)
                }
            },
            onNextClick = {
                progressManager.nextEpisode { i ->
                    progressManager.updateAniProgress()
                    changeEpisode(currentEpisodeIndex + i)
                }
            },
            onSeekTo = { posMs: Long ->
                castManager.seekTo(posMs)
            },
            onPlaylistClick = {
                episodeTitle.performClick()
            },
            onSpeedClick = {
                exoSpeed.performClick()
            },
            onSubtitlesClick = {
                exoSubtitle.performClick()
            },
            onAudioClick = {
                exoAudioTrack.performClick()
            },
            onQualityClick = {
                exoSource.performClick()
            },
            onCustomSkipClick = {
                exoSkip.performLongClick()
            },
            onSkipIntroClick = {
                aniSkipManager.skipCurrentInterval()
            }
        )
        castManager.castScreenView = castScreenView

        castManager.onSessionStartedListener = { deviceName ->
            playerManager.pause()
            if (initialized) {
                castScreenView.updateMediaInfo(media, if (this::episode.isInitialized) episode else null)
            }
            castScreenView.updateDeviceName(deviceName)
            castScreenView.show(true)
            playerView.hideController()
        }

        castManager.onSessionEndedListener = { resumePositionMs ->
            castScreenView.hide(true)
            playerManager.seekTo(resumePositionMs)
            playerManager.play()
        }
    }

    private fun setupOrientationTracking(savedInstanceState: Bundle?) {
        if (System.getInt(contentResolver, System.ACCELEROMETER_ROTATION, 0) != 1) {
            if (PrefManager.getVal(PrefName.RotationPlayer)) {
                orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
                    override fun onOrientationChanged(orientation: Int) {
                        when (orientation) {
                            in 45..135 -> {
                                if (rotation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                                    exoRotate.visibility = View.VISIBLE
                                }
                                rotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            }
                            in 225..315 -> {
                                if (rotation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                    exoRotate.visibility = View.VISIBLE
                                }
                                rotation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                            in 315..360, in 0..45 -> {
                                if (rotation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                                    exoRotate.visibility = View.VISIBLE
                                }
                                rotation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        }
                    }
                }
                orientationListener?.enable()
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            exoRotate.setOnClickListener {
                requestedOrientation = rotation
                it.visibility = View.GONE
            }
        }

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(resumeWindow)
            playbackPosition = savedInstanceState.getLong(resumePosition)
            isFullscreen = savedInstanceState.getInt(playerFullscreen)
            isPlayerPlaying = savedInstanceState.getBoolean(playerOnPlay)
        }
    }

    private fun setupPlayerControlListeners() {
        playerView.findViewById<ImageButton>(R.id.exo_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        aniSkipManager.init()

        exoPlay.setOnClickListener {
            if (playerManager.isInitialized) {
                val mpv = playerManager.mpvView
                isPlayerPlaying = mpv.isPlaying.value
                (exoPlay.drawable as? Animatable)?.start()
                if (isPlayerPlaying || castManager.isCasting()) {
                    Glide.with(this).load(R.drawable.anim_play_to_pause).into(exoPlay)
                    mpv.pause()
                    castManager.pause()
                    WatchRoomManager.notifyLocalPlayPause(true, mpv.currentPosition.value)
                } else {
                    Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                    mpv.play()
                    WatchRoomManager.notifyLocalPlayPause(false, mpv.currentPosition.value)
                }
            }
        }

        if (PrefManager.getVal(PrefName.Cast)) {
            castManager.setupCastButton(
                customCastButton,
                media,
                null,
                subtitle,
                hasExtSubtitles,
                null
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pipEnabled = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                PrefManager.getVal(PrefName.Pip)
            if (pipEnabled) {
                exoPip.visibility = View.VISIBLE
                exoPip.setOnClickListener { enterPipMode() }
            } else {
                exoPip.visibility = View.GONE
            }
        }

        val container = playerView.findViewById<View>(R.id.exo_controller_cont)
        val screen = playerView.findViewById<View>(R.id.exo_black_screen)
        val lockButton = playerView.findViewById<ImageButton>(R.id.exo_unlock)
        val timeline = playerView.findViewById<DantotsuTimeBar>(R.id.exo_progress)
        playerView.findViewById<ImageButton>(R.id.exo_lock).setOnClickListener {
            gestureManager.isLocked = true
            screen.visibility = View.GONE
            container.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            timeline?.setForceDisabled(true)
        }
        lockButton.setOnClickListener {
            gestureManager.isLocked = false
            screen.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            it.visibility = View.GONE
            timeline?.setForceDisabled(false)
        }

        var skipTime = PrefManager.getVal<Int>(PrefName.SkipTime)
        if (skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
            exoSkip.setOnClickListener {
                val mpv = playerManager.mpvView
                mpv.seekTo(mpv.currentPosition.value + skipTime * 1000L)
            }
            exoSkip.setOnLongClickListener {
                showSkipTimeDialog(skipTime) { newTime ->
                    skipTime = newTime
                }
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }

        exoTogether.isVisible = !isStandaloneMode || isTogetherStreamMode
        exoTogether.setOnClickListener {
            WatchRoomBottomSheet
                .newInstance(media.userPreferredName, inPlayer = true)
                .show(supportFragmentManager, "WatchRoomBottomSheet")
        }

        gestureManager.initGestures()
    }

    private fun showSkipTimeDialog(initialTime: Int, onTimeChanged: (Int) -> Unit) {
        var currentTime = initialTime
        val dialog = Dialog(this, R.style.MyPopup)
        dialog.setContentView(R.layout.item_seekbar_dialog)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val slider = dialog.findViewById<Slider>(R.id.seekbar)
        slider.stepSize = 1f
        val from = slider.valueFrom
        val to = slider.valueTo
        val step = if (slider.stepSize > 0f) slider.stepSize else 1f
        val clamped = currentTime.toFloat().coerceIn(from, to)
        val snapped = from + Math.round((clamped - from) / step) * step
        slider.value = snapped.coerceIn(from, to)
        slider.addOnChangeListener { _, value, _ ->
            currentTime = value.toInt()
            PrefManager.setVal(PrefName.SkipTime, currentTime)
            playerView.findViewById<TextView>(R.id.exo_skip_time).text = currentTime.toString()
            dialog.findViewById<TextView>(R.id.seekbar_value).text = currentTime.toString()
            onTimeChanged(currentTime)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) {}
            override fun onStopTrackingTouch(s: Slider) { dialog.dismiss() }
        })
        dialog.findViewById<TextView>(R.id.seekbar_title).text = getString(R.string.skip_time)
        dialog.findViewById<TextView>(R.id.seekbar_value).text = currentTime.toString()
        dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        dialog.show()
    }

    private fun setupEpisodeListAndNavigation(): Boolean {
        if (!initialized) {
            startMainActivity(this)
            return false
        }
        model.setMedia(media)
        title = media.userPreferredName
        val eps = media.anime?.episodes
        if (eps.isNullOrEmpty()) {
            startMainActivity(this)
            finish()
            return false
        }
        episodes = eps.toMutableMap()
        videoInfo = playerView.findViewById(R.id.exo_video_info)
        model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources

        model.epChanged.observe(this) { epChanging = !it }
        animeTitle.text = media.userPreferredName

        episodeArr = episodes.keys.toList()
        val currentSelected = episodes.getEpisodeKey(media.anime?.selectedEpisode)
            ?: episodeArr.firstOrNull() ?: run {
                startMainActivity(this)
                finish()
                return false
            }
        media.anime!!.selectedEpisode = currentSelected
        currentEpisodeIndex = max(0, episodeArr.indexOf(currentSelected))

        episodeTitleArr = arrayListOf()
        episodes.forEach {
            val ep = it.value
            val cleanedTitle = MediaNameAdapter.removeEpisodeNumberCompletely(ep.title ?: "")
            episodeTitleArr.add(
                "Episode ${ep.number}${if (ep.filler) " [Filler]" else ""}${if (cleanedTitle.isNotBlank() && cleanedTitle != "null") ": $cleanedTitle" else ""}"
            )
        }

        progressManager.media = media
        progressManager.episodes = episodes
        progressManager.episodeArr = episodeArr
        progressManager.episodeTitleArr = episodeTitleArr
        progressManager.currentEpisodeIndex = currentEpisodeIndex

        episodeTitle.adapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, episodeTitleArr)
        episodeTitle.setSelection(currentEpisodeIndex)
        episodeTitle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position != currentEpisodeIndex) {
                    changeEpisode(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener {
            if (playerManager.isInitialized) {
                progressManager.nextEpisode { i ->
                    progressManager.updateAniProgress()
                    changeEpisode(currentEpisodeIndex + i)
                }
            }
        }

        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                changeEpisode(currentEpisodeIndex - 1)
            } else {
                snackString(getString(R.string.first_episode), this)
            }
        }

        model.getEpisode().observe(this) { ep ->
            onObservedEpisodeChanged(ep)
        }
        return true
    }

    private fun resolveEpisodeResumePosition(epKey: String?, epNumber: String, currentPos: Long?): Long {
        if (isStandaloneMode) {
            if (playerManager.isInitialized) releasePlayer()
            return 0L
        }
        val key = epKey ?: epNumber
        if (playerManager.isInitialized) {
            releasePlayer()
            if (changingServer) {
                return currentPos ?: PrefManager.getCustomVal("${media.id}_$key", 0L)
            }
        } else {
            val targetPos = targetStartPosition
            if (targetPos != null && targetPos > 0L) {
                targetStartPosition = null
                return targetPos
            }
        }

        val savedEpPos = PrefManager.getCustomVal("${media.id}_$key", 0L)
        if (savedEpPos > 0L) return savedEpPos

        val cleanEp = MediaNameAdapter.findEpisodeNumber(key)?.let {
            if (it % 1 == 0f) it.toInt().toString() else it.toString()
        }
        return cleanEp?.let { PrefManager.getCustomVal("${media.id}_$it", 0L) } ?: 0L
    }

    private fun onObservedEpisodeChanged(ep: Episode?) {
        hideSystemBarsExtendView()
        if (ep != null && (!epChanging || ep.extractors?.isNotEmpty() == true)) {
            epChanging = false
            val currentPos = playerManager.mpvView.currentPosition.value.takeIf { it > 0L }
            episode = ep
            media.selected = model.loadSelected(media)
            model.setMedia(media)
            val epKey = episodes.getEpisodeKey(ep.number)
                ?: episodeArr.find { episodes[it] == ep || episodes[it]?.number == ep.number }
                ?: episodeArr.firstOrNull()
            currentEpisodeIndex = if (epKey != null) max(0, episodeArr.indexOf(epKey)) else 0
            progressManager.currentEpisodeIndex = currentEpisodeIndex
            if (currentEpisodeIndex in 0 until episodeTitleArr.size) {
                episodeTitle.setSelection(currentEpisodeIndex)
            }
            playbackPosition = resolveEpisodeResumePosition(epKey, ep.number, currentPos)
            aniSkipManager.resetForNewEpisode()
            initPlayer()
            changingServer = false
            if (!isStandaloneMode) {
                progressManager.startTracking()
                aniSkipManager.startTracking()
            }
            if (!ep.allStreams) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val sourceIndex = media.selected?.sourceIndex ?: 0
                    model.loadEpisodeVideos(ep, sourceIndex, post = false)
                }
            }
        }
    }

    private fun setupDisplayAndSettings() {
        val defaultResize = PrefManager.getVal<Int>(PrefName.Resize)
        isFullscreen = PrefManager.getCustomVal("${media.id}_fullscreenInt", defaultResize)
        applyResizeMode(isFullscreen)

        exoScreen.setOnClickListener {
            isFullscreen = if (isFullscreen < 2) isFullscreen + 1 else 0
            applyResizeMode(isFullscreen)
            snackString(
                when (isFullscreen) {
                    0 -> "Original"
                    1 -> "Zoom"
                    2 -> "Stretch"
                    else -> "Original"
                },
                this
            )
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        exoSettings.setOnClickListener {
            val mpv = playerManager.mpvView
            if (playerManager.isInitialized) {
                val selEp = media.anime?.selectedEpisode
                val pos = mpv.currentPosition.value
                if (selEp != null) {
                    PrefManager.setCustomVal("${media.id}_$selEp", pos)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != selEp) {
                        PrefManager.setCustomVal("${media.id}_$cleanEp", pos)
                    }
                }
                mpv.pause()
            }
            val intent = Intent(this, PlayerSettingsActivity::class.java).apply {
                putExtra("subtitle", subtitle)
            }
            onChangeSettings.launch(intent)
        }

        val speeds = getAvailablePlaybackSpeeds()
        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        val savedIndex = PrefManager.getCustomVal("${media.id}_speed", PrefManager.getVal<Int>(PrefName.DefaultSpeed))
        var curSpeed = savedIndex.coerceIn(0, speeds.size - 1)

        exoSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.speed)
                singleChoiceItems(speedsName, curSpeed) { i ->
                    PrefManager.setCustomVal("${media.id}_speed", i)
                    val speed = speeds.getOrNull(i) ?: 1f
                    curSpeed = i
                    playerManager.setPlaybackSpeed(speed)
                    hideSystemBarsExtendView()
                }
                setOnCancelListener { hideSystemBarsExtendView() }
                show()
            }
        }

        if (PrefManager.getVal(PrefName.AutoPlay)) {
            val touchHandler = Handler(Looper.getMainLooper())
            val touchResetRunnable = Runnable { interacted = false }
            fun touched() {
                interacted = true
                touchHandler.removeCallbacks(touchResetRunnable)
                touchHandler.postDelayed(touchResetRunnable, 1000L * 60 * 60)
            }
            playerView.findViewById<View>(R.id.exo_touch_view)?.setOnTouchListener { _, _ ->
                touched()
                false
            }
        }
    }

    private fun applyResizeMode(resizeInt: Int) {
        playerView.resizeMode = when (resizeInt) {
            0 -> 0
            1 -> 4
            2 -> 3
            else -> 0
        }
        val mode = when (resizeInt) {
            1 -> VideoScaleMode.ZOOM
            2 -> VideoScaleMode.STRETCH
            else -> VideoScaleMode.FIT
        }
        playerManager.setVideoScaleMode(mode)
    }

    private fun initEpisodeProgressAndLaunch() {
        if (isStandaloneMode) {
            val selectedEpKey = media.anime?.selectedEpisode ?: episodeArr.firstOrNull()
            val initialEp = selectedEpKey?.let { episodes[it] } ?: episodes.values.firstOrNull() ?: return
            exoNext.visibility = View.GONE
            exoPrev.visibility = View.GONE
            exoSkipOpEd.visibility = View.GONE
            model.setEpisode(initialEp, "invoke")
            return
        }
        val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
        val showProgressDialog = if (PrefManager.getVal(PrefName.AskIndividualPlayer)) {
            PrefManager.getCustomVal("${media.id}_progressDialog", true)
        } else {
            false
        }
        val selectedEpKey = media.anime?.selectedEpisode ?: episodeArr.firstOrNull()
        val initialEp = selectedEpKey?.let { episodes[it] } ?: episodes.values.firstOrNull()
        if (initialEp == null) {
            startMainActivity(this)
            finish()
            return
        }

        if (!incognito && showProgressDialog && Anilist.userid != null &&
            (if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true)
        ) {
            customAlertDialog().apply {
                setTitle(getString(R.string.auto_update, media.userPreferredName))
                setCancelable(false)
                setPosButton(R.string.yes) {
                    PrefManager.setCustomVal("${media.id}_progressDialog", false)
                    PrefManager.setCustomVal("${media.id}_save_progress", true)
                    model.setEpisode(initialEp, "invoke")
                }
                setNegButton(R.string.no) {
                    PrefManager.setCustomVal("${media.id}_progressDialog", false)
                    PrefManager.setCustomVal("${media.id}_save_progress", false)
                    toast(getString(R.string.reset_auto_update))
                    model.setEpisode(initialEp, "invoke")
                }
                setOnCancelListener { hideSystemBarsExtendView() }
                show()
            }
        } else {
            model.setEpisode(initialEp, "invoke")
        }
    }

    private fun changeEpisode(index: Int) {
        if (playerManager.isInitialized && index in episodeArr.indices) {
            changingServer = false
            progressManager.stopTracking()
            aniSkipManager.stopTracking()
            val prevEpKey = episodeArr.getOrNull(currentEpisodeIndex)
            if (prevEpKey != null) {
                val pos = playerManager.mpvView.currentPosition.value
                PrefManager.setCustomVal("${media.id}_$prevEpKey", pos)
                val cleanEp = MediaNameAdapter.findEpisodeNumber(prevEpKey)?.let {
                    if (it % 1 == 0f) it.toInt().toString() else it.toString()
                }
                if (cleanEp != null && cleanEp != prevEpKey) {
                    PrefManager.setCustomVal("${media.id}_$cleanEp", pos)
                }
                subtitleManager.clearTransientSubtitleCache("${media.id}-$prevEpKey", media.id)
            }
            playerManager.pause()
            aniSkipManager.resetForNewEpisode()
            progressManager.episodeLength = 0f
            val newEpKey = episodeArr[index]
            media.anime?.selectedEpisode = newEpKey
            val targetEpisode = episodes[newEpKey] ?: return
            model.setMedia(media)
            model.epChanged.postValue(false)
            model.setEpisode(targetEpisode, "change")
            model.onEpisodeClick(media, newEpKey, supportFragmentManager, false, prevEpKey ?: "", false)
        }
    }

    private fun recordAnimeContinuation(selEp: String) {
        if (isStandaloneMode) return
        media.anime!!.selectedEpisode = selEp
        PrefManager.setCustomVal("${media.id}_current_ep", selEp)

        @Suppress("UNCHECKED_CAST")
        val list = (PrefManager.getNullableCustomVal("continueAnimeList", listOf<Int>(), List::class.java) as List<Int>).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)
        PrefManager.setCustomVal("continueAnimeList", list)
    }

    private fun isMatchingSubtitleLanguage(language: String, lang: String): Boolean {
        return language.contains(lang, true) ||
            language.contains("English", true) ||
            Regex("""(?i)(?:^|[^a-zA-Z])(en|eng)(?:[^a-zA-Z]|$)""").containsMatchIn(language)
    }

    private fun findDefaultSubtitle(subtitles: List<Subtitle>, lang: String): Subtitle? {
        return subtitles.find { isMatchingSubtitleLanguage(it.language, lang) } ?: subtitles.firstOrNull()
    }

    private fun resolveInitialSubtitle(ext: VideoExtractor, lang: String): Subtitle? {
        var savedSubLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
        if (savedSubLang?.startsWith("Online:") == true) {
            PrefManager.setCustomVal("subLang_${media.id}", null)
            savedSubLang = null
        }
        val hasSavedOnlineSub = subtitleManager.restoreSavedOnlineSubtitle(media.id, episode.number)
        if (hasSavedOnlineSub) {
            subtitle = null
            hasExtSubtitles = ext.subtitles.isNotEmpty()
            subtitleManager.initialSubtitleLabel = null
            return null
        }

        var resolvedSub = intent.getSerialized<Subtitle>("subtitle")
            ?: when {
                savedSubLang == null -> when (episode.selectedSubtitle) {
                    null, -1 -> findDefaultSubtitle(ext.subtitles, lang)
                    else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                }
                savedSubLang == "None" -> null
                savedSubLang.startsWith("Online:") -> null
                savedSubLang.startsWith("[Local]") -> null
                savedSubLang.startsWith("Embedded:") -> null
                else -> ext.subtitles.find { it.language == savedSubLang }
            }

        hasExtSubtitles = ext.subtitles.isNotEmpty()
        if (resolvedSub == null && hasExtSubtitles && savedSubLang != "None" &&
            savedSubLang?.startsWith("Online:") != true &&
            savedSubLang?.startsWith("[Local]") != true &&
            savedSubLang?.startsWith("Embedded:") != true
        ) {
            resolvedSub = findDefaultSubtitle(ext.subtitles, lang)
        }

        subtitleManager.initialSubtitleLabel =
            if (savedSubLang == "None") null else resolvedSub?.language ?: lang

        if (resolvedSub != null) {
            PrefManager.setCustomVal("subLang_${media.id}", resolvedSub.language)
            subtitleManager.setActiveServerSubtitle(resolvedSub)
        }
        return resolvedSub
    }

    private fun triggerOnlineEpisodeMapping() {
        if (isStandaloneMode || !isOnline(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (media.idIMDB == null) media.idIMDB = IdMappers.getImdbId(media.id)
                val selectedEpisodeStr = currentEpisodeNumber
                val epObj = if (this@MpvPlayerActivity::episode.isInitialized) episode else media.anime?.episodes?.getEpisode(selectedEpisodeStr)
                val episodeNum = MediaNameAdapter.findEpisodeNumber(epObj?.number ?: selectedEpisodeStr)?.toInt()
                    ?: epObj?.number?.filter { it.isDigit() }?.toIntOrNull()
                    ?: selectedEpisodeStr.toIntOrNull()
                    ?: 1
                EpisodeMapper.mapEpisode(media, episodeNum, epObj)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun resolveOfflineVideo(ext: VideoExtractor, fallbackVideo: Video): Video {
        if (!ext.server.offline) return fallbackVideo
        val titleName = ext.server.name.split("/").first()
        val episodeName = ext.server.name.split("/").last()
        val directory = DownloadsManager.getSubDirectory(
            this, MediaType.ANIME, false, titleName, episodeName
        ) ?: return fallbackVideo

        val supportedVideoExts = listOf(".mp4", ".mkv", ".webm", ".ts")
        val file = directory.listFiles().firstOrNull {
            it.isFile && !it.name.orEmpty().contains("subtitle", ignoreCase = true) && !it.name.orEmpty().startsWith(".") &&
                (supportedVideoExts.any { extName -> it.name?.endsWith(extName, ignoreCase = true) == true } ||
                    it.type?.startsWith("video/") == true)
        } ?: directory.listFiles().firstOrNull {
            it.isFile && !it.name.orEmpty().contains("subtitle", ignoreCase = true) && !it.name.orEmpty().startsWith(".")
        }

        return if (file != null) {
            fallbackVideo.copy(
                format = VideoType.CONTAINER,
                file = FileUrl(file.uri.toString())
            )
        } else {
            fallbackVideo
        }
    }

    private var torrentStartJob: Job? = null

    private fun resolveTorrentStreamUrl(
        torrentManager: TorrentServerManager,
        rawVideo: Video,
        relay: TorrentRelayLink?
    ): String {
        ani.dantotsu.addons.torrent.TorrentServerService.start()
        val rawUrl = rawVideo.file.url
        if (relay != null) {
            torrentManager.start()
            if (!torrentManager.hasTorrent(relay.hash)) {
                val source = episode.extra?.get("torrentUrl") ?: torrentManager.sourceFor(relay.hash)
                if (source.startsWith("magnet:", ignoreCase = true)) {
                    torrentManager.startMagnetForStream(source, relay.index)
                } else {
                    val previous = torrentManager.activeTorrentHash
                    val added = torrentManager.addTorrent(source, rawVideo.quality.toString(), "", "", false)
                    if (previous != null && !previous.equals(added.hash, ignoreCase = true)) {
                        torrentManager.removeTorrent(previous)
                    }
                    torrentManager.activeTorrentHash = added.hash
                    torrentManager.prebufferAsync(added.hash!!, relay.index)
                }
            } else {
                torrentManager.activeTorrentHash = relay.hash
                torrentManager.prebufferAsync(relay.hash, relay.index)
            }
            return torrentManager.getLink(relay.hash, relay.index)
        }

        val index = episode.extra?.get("fileId")?.toIntOrNull()
            ?: TorrentLinks.fileIndex(rawUrl)
        return if (rawUrl.startsWith("magnet:", ignoreCase = true)) {
            val hash = torrentManager.startMagnetForStream(rawUrl, index)
            torrentManager.getLink(hash, index)
        } else {
            val previous = torrentManager.activeTorrentHash
            val added = torrentManager.addTorrent(rawUrl, rawVideo.quality.toString(), "", "", false)
            if (previous != null && !previous.equals(added.hash, ignoreCase = true)) {
                torrentManager.removeTorrent(previous)
            }
            torrentManager.activeTorrentHash = added.hash
            torrentManager.prebufferAsync(added.hash!!, index)
            torrentManager.getLink(added, index)
        }
    }

    private fun startPlaybackWithVideo(ext: VideoExtractor, playbackVideo: Video, lang: String) {
        val resolvedPlaybackVideo = resolveOfflineVideo(ext, playbackVideo)
        val extSubtitles: List<ExternalSubtitle> = subtitleManager.buildExternalSubtitles(
            ext.subtitles,
            ext.server.embed.url,
            resolvedPlaybackVideo.file.url,
            hasExtSubtitles,
            subtitle?.language ?: lang
        )

        lifecycleScope.launch(Dispatchers.IO) { ext.onVideoPlayed(playbackVideo) }

        val episodeDisplayName = episodeTitleArr.getOrNull(currentEpisodeIndex) ?: episode.number
        val savedPosition = resolveSavedPlaybackPosition()
        val currentSpeed = resolvePlaybackSpeed()

        customSubtitleView.text = ""
        customSubtitleView.visibility = View.GONE
        playerErrorRetryCount = 0
        hideSystemBarsExtendView()

        playerManager.loadVideo(
            video = resolvedPlaybackVideo,
            startPositionMs = savedPosition,
            externalSubtitles = extSubtitles,
            audioTracks = ext.audioTracks,
            speed = currentSpeed
        )

        castManager.setupCastButton(
            customCastButton, media, resolvedPlaybackVideo, subtitle, hasExtSubtitles, episodeDisplayName
        )
        castManager.updateCurrentMedia(
            mpvView = playerManager.mpvView,
            video = resolvedPlaybackVideo,
            title = media.userPreferredName,
            episodeTitle = episodeDisplayName,
            posterUrl = episode.thumb?.url ?: media.cover,
            externalSubtitles = extSubtitles
        )

        subtitleManager.applySubtitleStyles(customSubtitleView)
        subtitleManager.setupSubFormatting()
        progressManager.updateWidgetState(isExiting = false)
        if (!isStandaloneMode || isTogetherStreamMode) {
            WatchRoomManager.attachPlayer(
                watchRoomBridge,
                "${media.userPreferredName} - Episode $currentEpisodeNumber"
            )
        }
        checkSmartDownloadAnime(ext)
    }

    private fun initPlayer() {
        torrentStartJob?.cancel()
        torrentStartJob = null
        gestureManager.checkNotch()
        aniSkipManager.resetForNewEpisode()
        val selEp = media.anime?.selectedEpisode ?: episodeArr.firstOrNull() ?: return
        recordAnimeContinuation(selEp)

        lifecycleScope.launch(Dispatchers.IO) { extractor?.onVideoStopped(video) }

        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor }
            ?: episode.extractors?.firstOrNull { it.isPreferred }
            ?: episode.extractors?.firstOrNull()
        if (ext == null) {
            toast("No stream source available, select a server")
            sourceClick()
            return
        }
        extractor = ext
        val rawVideo = ext.videos.getOrNull(episode.selectedVideo) ?: ext.videos.firstOrNull()
        if (rawVideo == null) {
            toast("Selected server returned no streams, choose another")
            sourceClick()
            return
        }
        video = rawVideo

        val lang = SUBTITLE_LANGUAGES.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
        subtitle = resolveInitialSubtitle(ext, lang)

        exoSource.setOnClickListener { sourceClick() }
        triggerOnlineEpisodeMapping()

        exoSubtitle.isVisible = true
        exoSubtitle.setOnClickListener { subClick() }

        val rawUrl = rawVideo.file.url
        val relay = TorrentRelayLink.parse(rawUrl)
        val isTorrentSource = relay != null ||
            rawUrl.startsWith("magnet:", ignoreCase = true) ||
            rawUrl.endsWith(".torrent", ignoreCase = true)

        if (isTorrentSource) {
            val torrentManager = Injekt.get<TorrentServerManager>()
            if (torrentManager.isAvailable()) {
                setResolvingSpinner(true)
                torrentStartJob = lifecycleScope.launch {
                    val resolvedUrl = try {
                        withContext(Dispatchers.IO) {
                            resolveTorrentStreamUrl(torrentManager, rawVideo, relay)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Injekt.get<CrashlyticsInterface>().logException(e)
                        Logger.log(e)
                        toast("Error starting torrent: ${e.message}")
                        setResolvingSpinner(false)
                        sourceClick()
                        null
                    } ?: return@launch
                    setResolvingSpinner(false)
                    val playbackVideo = rawVideo.copy(file = rawVideo.file.copy(url = resolvedUrl))
                    video = playbackVideo
                    startPlaybackWithVideo(ext, playbackVideo, lang)
                }
                return
            }
        }

        startPlaybackWithVideo(ext, rawVideo, lang)
    }

    private fun setResolvingSpinner(show: Boolean) {
        playerView.findViewById<View>(R.id.exo_buffering)?.isVisible = show
    }

    private fun getNextEpisodeKeyForSmartDownload(currentExt: VideoExtractor): String? {
        if (!PrefManager.getVal<Boolean>(PrefName.SmartDownloadAnime)) return null
        val isOffline = currentExt.server.offline || Injekt.get<DownloadsManager>().queryDownload(media.mainName(), episode.number, MediaType.ANIME)
        if (!isOffline) return null

        val currentIdx = episodeArr.indexOf(episode.number)
        if (currentIdx == -1 || currentIdx + 1 >= episodeArr.size) return null
        val nextEpKey = episodeArr[currentIdx + 1]
        val downloadsManager = Injekt.get<DownloadsManager>()
        if (downloadsManager.queryDownload(media.mainName(), nextEpKey, MediaType.ANIME)) return null
        if (AnimeDownloader.isDownloading(media.id, nextEpKey)) return null
        return nextEpKey
    }

    private suspend fun resolveSmartDownloadExtractor(
        nextEpisode: Episode,
        sourceIndex: Int,
        serverName: String
    ): VideoExtractor? {
        val success = if (serverName.isNotBlank()) {
            model.loadEpisodeSingleVideo(nextEpisode, media.selected!!, selectedServerName = serverName)
        } else false

        return if (success) {
            nextEpisode.extractors?.find { it.server.name == serverName }
        } else {
            model.loadEpisodeVideos(nextEpisode, sourceIndex)
            nextEpisode.extractors?.firstOrNull { it.isPreferred && it.videos.isNotEmpty() }
                ?: nextEpisode.extractors?.firstOrNull { it.videos.isNotEmpty() }
        }
    }

    private fun checkSmartDownloadAnime(currentExt: VideoExtractor) {
        val nextEpKey = getNextEpisodeKeyForSmartDownload(currentExt) ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nextEpisode = episodes[nextEpKey] ?: media.anime?.episodes?.getEpisode(nextEpKey) ?: return@launch
                val sourceIndex = media.selected?.sourceIndex ?: return@launch
                val serverName = if (!currentExt.server.offline) currentExt.server.name else media.selected?.server ?: ""

                val extractorToUse = resolveSmartDownloadExtractor(nextEpisode, sourceIndex, serverName) ?: return@launch

                val sourceName = model.watchSources?.get(sourceIndex)?.name
                val preferredResolutions = PrefManager.getPreferredDownloadResolutions(sourceName)
                val bestVideo = Helper.findBestVideoForDownload(extractorToUse.videos, preferredResolutions) ?: return@launch

                withContext(Dispatchers.Main) {
                    Helper.startAnimeDownloadService(
                        this@MpvPlayerActivity,
                        media.mainName(),
                        nextEpisode.number,
                        bestVideo,
                        sourceMedia = media,
                        episodeImage = nextEpisode.thumb?.url ?: media.banner ?: media.cover
                    )
                }
            } catch (e: Exception) {
                Logger.log("SmartDownloadAnime error: ${e.message}")
            }
        }
    }

    private fun resolveSavedPlaybackPosition(): Long {
        val selEp = media.anime?.selectedEpisode
        val cleanEp = selEp?.let { MediaNameAdapter.findEpisodeNumber(it) }?.let {
            if (it % 1 == 0f) it.toInt().toString() else it.toString()
        }
        val savedMax = selEp?.let { PrefManager.getCustomVal("${media.id}_${it}_max", Long.MAX_VALUE) }
            ?.takeIf { it != Long.MAX_VALUE }
            ?: cleanEp?.let { PrefManager.getCustomVal("${media.id}_${it}_max", Long.MAX_VALUE) }?.takeIf { it != Long.MAX_VALUE }

        return if (savedMax != null && savedMax > 0L) {
            if (playbackPosition >= savedMax || playbackPosition > savedMax.toFloat() * 0.92f) {
                playbackPosition = 0L
                0L
            } else {
                playbackPosition
            }
        } else {
            playbackPosition
        }
    }

    private fun resolvePlaybackSpeed(): Float {
        val speeds = getAvailablePlaybackSpeeds()
        val savedSpeedIndex = PrefManager.getCustomVal("${media.id}_speed", PrefManager.getVal<Int>(PrefName.DefaultSpeed))
            .coerceIn(0, speeds.size - 1)
        return speeds[savedSpeedIndex]
    }

    private fun releasePlayer() {
        val mpv = playerManager.mpvView
        isPlayerPlaying = mpv.isPlaying.value
        playbackPosition = mpv.currentPosition.value
        customSubtitleView.text = ""
        progressManager.stopTracking()
        progressManager.updateWidgetState(isExiting = true)
        discordManager.clear()
        playerManager.release()
    }

    private fun sourceClick() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        changingServer = true

        media.selected?.server = null
        if (playerManager.isInitialized) {
            val pos = playerManager.mpvView.currentPosition.value
            PrefManager.setCustomVal("${media.id}_${media.anime?.selectedEpisode}", pos)
            playerManager.pause()
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        val epNum = currentEpisodeNumber
        model.onEpisodeClick(
            media,
            epNum,
            this.supportFragmentManager,
            launch = false
        )
    }

    private fun subClick() {
        Logger.log("subClick: Opening subtitle dialog")
        if (playerManager.isInitialized) {
            PrefManager.setCustomVal(
                "${media.id}_${media.anime?.selectedEpisode}",
                playerManager.mpvView.currentPosition.value
            )
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        SubtitleDialogFragment().show(supportFragmentManager, "dialog")
    }

    fun requestLocalSubtitle() {
        getContent.launch(arrayOf("*/*"))
    }

    fun applyLocalSubtitle(uri: Uri) {
        subtitleManager.applyLocalSubtitle(uri, media)
    }

    fun reApplyLocalSubtitle(uriString: String) {
        applyLocalSubtitle(Uri.parse(uriString))
    }

    fun applyOnlineSubtitle(subtitle: StremioSub, displayName: String = subtitle.lang, provider: String = "OpenSubtitles") {
        subtitleManager.applyOnlineSubtitle(subtitle, displayName, provider)
    }

    fun applyWyzieSubtitle(subtitle: WyzieSub) {
        subtitleManager.applyWyzieSubtitle(subtitle)
    }

    fun applySubSourceSubtitle(sub: SubSourceSub) {
        subtitleManager.applySubSourceSubtitle(sub)
    }

    fun applyOpenSubRestSubtitle(item: OpenSubRestItem) {
        subtitleManager.applyOpenSubRestSubtitle(item)
    }

    private fun checkAndLoadTimestamps() {
        if (isStandaloneMode) return
        val mpv = playerManager.mpvView
        if (!aniSkipManager.isTimeStampsLoaded && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            val dur = mpv.duration.value
            val extTimestamps = extractor?.server?.video?.timestamps ?: emptyList()
            if (extTimestamps.isNotEmpty() || dur > 0) {
                val epString = currentEpisodeNumber
                val epNum = Regex("""\d+""").find(epString)?.value?.toIntOrNull()
                    ?: epString.trim().toIntOrNull()
                    ?: 1
                lifecycleScope.launch(Dispatchers.IO) {
                    model.loadTimeStamps(
                        media.idMAL,
                        epNum,
                        if (dur > 0) dur / 1000 else 0L,
                        PrefManager.getVal(PrefName.UseProxyForTimeStamps),
                        extTimestamps
                    )
                }
            }
        }
    }

    private fun getErrorRecoveryPosition(): Long =
        if (playerManager.isInitialized) {
            playerManager.mpvView.currentPosition.value.takeIf { it > 0 } ?: playbackPosition
        } else {
            playbackPosition
        }

    private fun isLocalTorrentStream(): Boolean =
        StreamTypeDetector.isTorrentStreamUrl(video?.file?.url)

    private fun onPlayerError(message: String) {
        Logger.log("MPV playback error: $message")
        val maxRetries = if (isLocalTorrentStream()) MAX_TORRENT_ERROR_RETRIES else MAX_PLAYER_ERROR_RETRIES
        val v = video
        val ext = extractor
        if (playerErrorRetryCount < maxRetries && v != null && ext != null && !message.contains("DRM", ignoreCase = true)) {
            playerErrorRetryCount++
            val savedPos = getErrorRecoveryPosition()
            playbackPosition = savedPos
            val lang = SUBTITLE_LANGUAGES.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
            val extSubtitles = subtitleManager.buildExternalSubtitles(
                ext.subtitles, ext.server.embed.url, v.file.url, hasExtSubtitles, subtitle?.language ?: lang
            )
            playerManager.loadVideo(
                video = v,
                startPositionMs = savedPos,
                externalSubtitles = extSubtitles,
                audioTracks = ext.audioTracks,
                speed = resolvePlaybackSpeed()
            )
        } else {
            playerErrorRetryCount = 0
            toast("Player Error: $message")
            isPlayerPlaying = true
            sourceClick()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (PrefManager.getVal(PrefName.FocusPause) && !epChanging && !isInPipMode()) {
            val mpv = playerManager.mpvView
            if (playerManager.isInitialized && !hasFocus) {
                wasPlaying = mpv.playWhenReady
            }
            if (hasFocus) {
                if (playerManager.isInitialized && wasPlaying) mpv.play()
            } else {
                if (playerManager.isInitialized) mpv.pause()
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    private fun buildPipActions(isPlaying: Boolean): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val prevEpIntent = PendingIntent.getBroadcast(
            this,
            10,
            Intent(ACTION_PIP_PREV_EP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevEpAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_round_skip_previous_24),
            "Previous Episode",
            "Previous Episode",
            prevEpIntent
        )

        val rewindIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_PIP_REWIND).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rewindAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_pip_rewind_10),
            "Rewind 10s",
            "Rewind 10s",
            rewindIntent
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = RemoteAction(
            Icon.createWithResource(this, playPauseIcon),
            playPauseTitle,
            playPauseTitle,
            playPauseIntent
        )

        val skipIntent = PendingIntent.getBroadcast(
            this,
            3,
            Intent(ACTION_PIP_SKIP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_pip_skip_10),
            "Skip 10s",
            "Skip 10s",
            skipIntent
        )

        val nextEpIntent = PendingIntent.getBroadcast(
            this,
            20,
            Intent(ACTION_PIP_NEXT_EP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextEpAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_round_skip_next_24),
            "Next Episode",
            "Next Episode",
            nextEpIntent
        )

        val maxActions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            maxNumPictureInPictureActions
        } else {
            3
        }

        return if (maxActions >= 5) {
            listOf(prevEpAction, rewindAction, playPauseAction, skipAction, nextEpAction)
        } else {
            listOf(rewindAction, playPauseAction, skipAction)
        }
    }

    private fun updatePipActions(isPlaying: Boolean = isPlayerPlaying) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .setActions(buildPipActions(isPlaying))
                    .build()
                setPictureInPictureParams(params)
            }
        }
    }

    private fun enterPipMode() {
        playerView.useController = false
        playerView.hideController()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setActions(buildPipActions(isPlayerPlaying))
                .build()
            enterPictureInPictureMode(params)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    private fun onPipModeChangedInternal(isInPictureInPictureMode: Boolean) {
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerView.hideController()
            if (pipReceiver == null) {
                pipReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val mpv = playerManager.mpvView
                        when (intent?.action) {
                            ACTION_PIP_PREV_EP -> {
                                if (currentEpisodeIndex > 0) {
                                    changeEpisode(currentEpisodeIndex - 1)
                                }
                            }
                            ACTION_PIP_REWIND -> {
                                val seekMs = (PrefManager.getVal<Int>(PrefName.SeekTime) * 1000).toLong().takeIf { it > 0 } ?: 10000L
                                mpv.seekTo(maxOf(0L, mpv.currentPosition.value - seekMs))
                            }
                            ACTION_PIP_PLAY_PAUSE -> {
                                mpv.togglePlayPause()
                            }
                            ACTION_PIP_SKIP -> {
                                val seekMs = (PrefManager.getVal<Int>(PrefName.SeekTime) * 1000).toLong().takeIf { it > 0 } ?: 10000L
                                val current = mpv.currentPosition.value
                                val duration = mpv.duration.value
                                val target = if (duration > 0) minOf(duration, current + seekMs) else current + seekMs
                                mpv.seekTo(target)
                            }
                            ACTION_PIP_NEXT_EP -> {
                                if (playerManager.isInitialized) {
                                    progressManager.nextEpisode { i ->
                                        progressManager.updateAniProgress()
                                        changeEpisode(currentEpisodeIndex + i)
                                    }
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(ACTION_PIP_PREV_EP)
                    addAction(ACTION_PIP_REWIND)
                    addAction(ACTION_PIP_PLAY_PAUSE)
                    addAction(ACTION_PIP_SKIP)
                    addAction(ACTION_PIP_NEXT_EP)
                }
                ContextCompat.registerReceiver(
                    this,
                    pipReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
            updatePipActions(isPlayerPlaying)
        } else {
            pipReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (_: Exception) {}
                pipReceiver = null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        onPipModeChangedInternal(isInPictureInPictureMode)
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        onPipModeChangedInternal(isInPictureInPictureMode)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        onPipModeChangedInternal(isInPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEnabled && PrefManager.getVal(PrefName.Pip)) {
            enterPipMode()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        aniSkipManager.startTracking()
        progressManager.startTracking()
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        hideSystemBarsExtendView()
        playerView.useController = true
        if (playerManager.isInitialized && wasPlayingOnPause) {
            wasPlayingOnPause = false
            playerManager.play()
        }
        if (initialized && this::episode.isInitialized && playerManager.isInitialized) {
            discordManager.updatePresence(
                media,
                episode,
                playerManager.mpvView,
                playerManager.mpvView.isPlaying.value
            )
        }
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        if (playerManager.isInitialized && !isInPipMode()) {
            wasPlayingOnPause = playerManager.mpvView.playWhenReady
            playerManager.pause()
        }
        aniSkipManager.stopTracking()
        progressManager.stopTracking()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            playerManager.abandonAudioFocus()
        }
        if (playerManager.isInitialized && !isStandaloneMode) {
            val pos = playerManager.mpvView.currentPosition.value
            val selEp = media.anime?.selectedEpisode
            if (selEp != null) {
                PrefManager.setCustomVal("${media.id}_$selEp", pos)
                val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                    if (it % 1 == 0f) it.toInt().toString() else it.toString()
                }
                if (cleanEp != null && cleanEp != selEp) {
                    PrefManager.setCustomVal("${media.id}_$cleanEp", pos)
                }
            }
            progressManager.updateAniProgress()
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishAndRemoveTask()
        startActivity(intent)
    }

    override fun onDestroy() {
        WatchRoomManager.detachPlayer(watchRoomBridge)
        torrentStartJob?.cancel()
        torrentStartJob = null
        livePlayers.decrementAndGet()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        orientationListener?.disable()
        orientationListener = null
        val closedAt = android.os.SystemClock.elapsedRealtime()
        tachiyomi.core.util.lang.launchIO {
            try {
                extractor?.onVideoStopped(video)
            } catch (_: Exception) {}
            if (livePlayers.get() > 0) return@launchIO
            try {
                val torrentManager = Injekt.get<TorrentServerManager>()
                if (torrentManager.isRunning() && !torrentManager.usedSince(closedAt)) {
                    torrentManager.pauseActiveTorrent()
                    torrentManager.pruneCache()
                    torrentManager.stop()
                }
            } catch (_: Exception) {}
            try {
                ani.dantotsu.addons.torrent.TorrentServerService.stop()
            } catch (_: Exception) {}
        }
        aniSkipManager.stopTracking()
        progressManager.stopTracking()
        try {
            if (playerManager.isInitialized) {
                if (!isStandaloneMode) {
                    progressManager.updateAniProgress()
                }
                val episodeId = "${media.id}-${media.anime?.selectedEpisode ?: ""}"
                subtitleManager.clearTransientSubtitleCache(episodeId, media.id)
                releasePlayer()
            }
        } catch (_: Exception) {}
        castManager.release()
        pipReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            pipReceiver = null
        }
        super.onDestroy()
    }
}
