package ani.dantotsu.media.anime

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.util.AttributeSet
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.connections.subtitles.OpenSubRestItem
import ani.dantotsu.connections.subtitles.OpenSubtitlesRestApi
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.SubSourceSub
import ani.dantotsu.connections.subtitles.SubSourceSubtitles
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.connections.subtitles.WyzieSubtitles
import ani.dantotsu.databinding.ActivityExoplayerBinding
import ani.dantotsu.dp
import ani.dantotsu.hideSystemBars
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.isOnline
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.anime.player.CastScreenView
import ani.dantotsu.media.anime.player.DantotsuPlayerManager
import ani.dantotsu.media.anime.player.PlayerAniSkipManager
import ani.dantotsu.media.anime.player.PlayerCastManager
import ani.dantotsu.media.anime.player.PlayerDiscordManager
import ani.dantotsu.media.anime.player.PlayerGestureManager
import ani.dantotsu.media.anime.player.PlayerProgressManager
import ani.dantotsu.media.anime.player.PlayerScreenshotManager
import ani.dantotsu.media.anime.player.PlayerSubtitleManager
import ani.dantotsu.shareImage
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toPx
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.bumptech.glide.Glide
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Timer
import java.util.TimerTask
import kotlin.math.max
import kotlin.math.min

@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class ExoplayerView : AppCompatActivity(), Player.Listener {

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

    private lateinit var binding: ActivityExoplayerBinding
    private lateinit var playerView: PlayerView
    private lateinit var exoPlay: ImageButton
    private lateinit var exoSource: ImageButton
    private lateinit var exoSettings: ImageButton
    private lateinit var exoSubtitle: ImageButton
    private lateinit var exoSubtitleView: SubtitleView
    private lateinit var exoAudioTrack: ImageButton
    private lateinit var exoRotate: ImageButton
    private lateinit var exoSpeed: ImageButton
    private lateinit var exoScreen: ImageButton
    private lateinit var exoNext: ImageButton
    private lateinit var exoPrev: ImageButton
    private lateinit var exoSkipOpEd: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoScreenshot: ImageButton
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
    private var downloadId: String? = null
    private var hasExtSubtitles = false
    private var audioLanguages = mutableListOf<Pair<String, String>>()
    var currentSubTrackGroups: ArrayList<Tracks.Group> = arrayListOf()
        private set
    var currentSubTracks: MutableList<Pair<String, String>> = mutableListOf()
        private set

    private lateinit var episode: Episode
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
    private var isBuffering = true
    private var playerErrorRetryCount = 0
    private var rotation = 0
    private var wasPlaying = false

    private val handler = Handler(Looper.getMainLooper())
    val model: MediaDetailsViewModel by viewModels()
    private val client = OkHttpClient()

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { applyLocalSubtitle(it) }
    }

    private val dummyTrack = Tracks.Group(
        androidx.media3.common.TrackGroup(
            Format.Builder().setLanguage("none").build()
        ),
        false,
        intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES),
        booleanArrayOf(true)
    )

    private val onChangeSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        if (!hasExtSubtitles) {
            playerManager.exoPlayer?.currentTracks?.groups?.forEach { trackGroup ->
                when (trackGroup.type) {
                    TRACK_TYPE_TEXT -> {
                        if (PrefManager.getVal(PrefName.Subtitles)) {
                            onSetTrackGroupOverride(trackGroup, TRACK_TYPE_TEXT)
                        } else {
                            onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT)
                        }
                    }
                    else -> {}
                }
            }
        }
        subtitleManager.setupSubFormatting(playerView)
        subtitleManager.applySubtitleStyles(customSubtitleView)
        if (playerManager.isInitialized) playerManager.exoPlayer?.play()
    }

    class ExtendedTimeBar @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : DefaultTimeBar(context, attrs) {
        private var isForceDisabled: Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return if (isForceDisabled) {
                false
            } else {
                super.onTouchEvent(event)
            }
        }

        fun setForceDisabled(forceDisabled: Boolean) {
            this.isForceDisabled = forceDisabled
        }
    }

    companion object {
        var initialized = false
        lateinit var media: Media
        var targetStartPosition: Long? = null
        private const val MAX_PLAYER_ERROR_RETRIES = 1
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

        if (!initialized) {
            startMainActivity(this)
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        ThemeManager(this).applyTheme()
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerView = binding.playerView
        hideSystemBarsExtendView()

        // Bind Views
        exoPlay = playerView.findViewById(androidx.media3.ui.R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoAudioTrack = playerView.findViewById(R.id.exo_audio)
        exoSubtitleView = playerView.findViewById(androidx.media3.ui.R.id.exo_subtitles)
        exoSubtitleView.setBottomPaddingFraction(0.0f)

        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoSpeed = playerView.findViewById(androidx.media3.ui.R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoPip = playerView.findViewById(R.id.exo_pip)
        exoScreenshot = playerView.findViewById(R.id.exo_screenshot)
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

        // Initialize Managers
        subtitleManager = PlayerSubtitleManager(this, playerView, customSubtitleView, model) {
            playerManager.exoPlayer
        }
        playerManager = DantotsuPlayerManager(this, playerView, subtitleManager, client) { error ->
            onPlayerError(error)
        }
        gestureManager = PlayerGestureManager(
            this, playerView, exoBrightnessCont, exoVolumeCont, exoBrightness, exoVolume,
            { playerManager.exoPlayer }, { playerManager.isInitialized }
        )
        aniSkipManager = PlayerAniSkipManager(
            this, playerView, model, exoSkipOpEd, exoSkip, skipTimeButton, skipTimeText, timeStampText,
            { playerManager.exoPlayer }
        )
        discordManager = PlayerDiscordManager(this)
        progressManager = PlayerProgressManager(
            this, model, { playerManager.exoPlayer }, { playerManager.isInitialized }
        )
        screenshotManager = PlayerScreenshotManager(this, playerView)
        exoScreenshot.setOnClickListener {
            val title = media.userPreferredName.ifBlank { media.mainName() }
            val epNum = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
            screenshotManager.takeScreenshot(title, epNum)
        }
        exoScreenshot.setOnLongClickListener {
            val title = media.userPreferredName.ifBlank { media.mainName() }
            val epNum = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
            screenshotManager.takeScreenshot(title, epNum) { bitmap, _ ->
                if (bitmap != null) {
                    shareImage("$title - Episode $epNum", bitmap, this)
                }
            }
            true
        }

        castManager = PlayerCastManager(this, playerView) { isPlaying ->
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (initialized && this::episode.isInitialized) {
                discordManager.updatePresence(media, episode, playerManager.exoPlayer, isPlaying)
            }
        }

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
            playerManager.exoPlayer?.pause()
            if (initialized) {
                castScreenView.updateMediaInfo(media, if (this::episode.isInitialized) episode else null)
            }
            castScreenView.updateDeviceName(deviceName)
            castScreenView.show(true)
            playerView.hideController()
        }

        castManager.onSessionEndedListener = { resumePositionMs ->
            castScreenView.hide(true)
            playerView.player = playerManager.exoPlayer
            playerManager.exoPlayer?.let { p ->
                p.seekTo(resumePositionMs)
                p.play()
            }
        }

        // Sensor & Orientation
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

        // UI Controls
        playerView.findViewById<ImageButton>(R.id.exo_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        aniSkipManager.init()

        // Play/Pause
        exoPlay.setOnClickListener {
            if (playerManager.isInitialized) {
                val player = playerManager.exoPlayer
                isPlayerPlaying = player?.isPlaying == true
                (exoPlay.drawable as? Animatable)?.start()
                if (isPlayerPlaying || castManager.isCasting()) {
                    Glide.with(this).load(R.drawable.anim_play_to_pause).into(exoPlay)
                    player?.pause()
                    castManager.pause()
                } else {
                    if (castManager.castPlayer?.isPlaying == false && castManager.castPlayer?.currentMediaItem != null) {
                        Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                        castManager.play()
                    } else if (!isPlayerPlaying) {
                        Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                        player?.play()
                    }
                }
            }
        }

        // PiP
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

        // Lock button
        val container = playerView.findViewById<View>(R.id.exo_controller_cont)
        val screen = playerView.findViewById<View>(R.id.exo_black_screen)
        val lockButton = playerView.findViewById<ImageButton>(R.id.exo_unlock)
        val timeline = playerView.findViewById<ExtendedTimeBar>(androidx.media3.ui.R.id.exo_progress)
        playerView.findViewById<ImageButton>(R.id.exo_lock).setOnClickListener {
            gestureManager.isLocked = true
            screen.visibility = View.GONE
            container.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            timeline.setForceDisabled(true)
        }
        lockButton.setOnClickListener {
            gestureManager.isLocked = false
            screen.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            it.visibility = View.GONE
            timeline.setForceDisabled(false)
        }

        // Skip Time
        var skipTime = PrefManager.getVal<Int>(PrefName.SkipTime)
        if (skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
            exoSkip.setOnClickListener {
                playerManager.exoPlayer?.let { p ->
                    p.seekTo(p.currentPosition + skipTime * 1000)
                }
            }
            exoSkip.setOnLongClickListener {
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
                val clamped = skipTime.toFloat().coerceIn(from, to)
                val snapped = from + Math.round((clamped - from) / step) * step
                slider.value = snapped.coerceIn(from, to)
                slider.addOnChangeListener { _, value, _ ->
                    skipTime = value.toInt()
                    PrefManager.setVal(PrefName.SkipTime, skipTime)
                    playerView.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
                    dialog.findViewById<TextView>(R.id.seekbar_value).text = skipTime.toString()
                }
                slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(s: Slider) {}
                    override fun onStopTrackingTouch(s: Slider) { dialog.dismiss() }
                })
                dialog.findViewById<TextView>(R.id.seekbar_title).text = getString(R.string.skip_time)
                dialog.findViewById<TextView>(R.id.seekbar_value).text = skipTime.toString()
                dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                dialog.show()
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }

        // Initialize Gestures
        gestureManager.initGestures()

        // Handle Media
        if (!initialized) return startMainActivity(this)
        model.setMedia(media)
        title = media.userPreferredName
        val eps = media.anime?.episodes
        if (eps.isNullOrEmpty()) {
            startMainActivity(this)
            finish()
            return
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
                return
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

        // Episode Spinner
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

        // Prev / Next Episode Buttons
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

        // Episode Observer
        model.getEpisode().observe(this) { ep ->
            hideSystemBarsExtendView()
            if (ep != null && !epChanging) {
                val currentPos = playerManager.exoPlayer?.currentPosition
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
                if (playerManager.isInitialized) {
                    releasePlayer()
                    playbackPosition = if (changingServer) {
                        currentPos ?: PrefManager.getCustomVal("${media.id}_${epKey ?: ep.number}", 0L)
                    } else {
                        val cleanEp = (epKey ?: ep.number).let { MediaNameAdapter.findEpisodeNumber(it) }?.let {
                            if (it % 1 == 0f) it.toInt().toString() else it.toString()
                        }
                        val savedEpPos = PrefManager.getCustomVal("${media.id}_${epKey ?: ep.number}", 0L)
                        if (savedEpPos > 0L) savedEpPos else cleanEp?.let { PrefManager.getCustomVal("${media.id}_${it}", 0L) } ?: 0L
                    }
                } else {
                    val cleanEp = (epKey ?: ep.number).let { MediaNameAdapter.findEpisodeNumber(it) }?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    val savedEpPos = PrefManager.getCustomVal("${media.id}_${epKey ?: ep.number}", 0L)
                    val targetPos = targetStartPosition
                    playbackPosition = if (targetPos != null && targetPos > 0L) {
                        targetStartPosition = null
                        targetPos
                    } else if (savedEpPos > 0L) savedEpPos else cleanEp?.let { PrefManager.getCustomVal("${media.id}_${it}", 0L) } ?: 0L
                }
                aniSkipManager.resetForNewEpisode()
                initPlayer()
                changingServer = false
                progressManager.startTracking()
                aniSkipManager.startTracking()
            }
        }

        // FullScreen / Aspect Ratio
        val defaultResize = PrefManager.getVal<Int>(PrefName.Resize)
        isFullscreen = PrefManager.getCustomVal("${media.id}_fullscreenInt", defaultResize)
        playerView.resizeMode = when (isFullscreen) {
            0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        exoScreen.setOnClickListener {
            isFullscreen = if (isFullscreen < 2) isFullscreen + 1 else 0
            playerView.resizeMode = when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            snackString(when (isFullscreen) {
                0 -> "Original"
                1 -> "Zoom"
                2 -> "Stretch"
                else -> "Original"
            }, this)
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        // Settings Button
        exoSettings.setOnClickListener {
            playerManager.exoPlayer?.let { p ->
                val selEp = media.anime?.selectedEpisode
                if (selEp != null) {
                    PrefManager.setCustomVal("${media.id}_${selEp}", p.currentPosition)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != selEp) {
                        PrefManager.setCustomVal("${media.id}_${cleanEp}", p.currentPosition)
                    }
                }
                p.pause()
            }
            val intent = Intent(this, PlayerSettingsActivity::class.java).apply {
                putExtra("subtitle", subtitle)
            }
            onChangeSettings.launch(intent)
        }

        // Speed Dialog
        val speeds = if (PrefManager.getVal(PrefName.CursedSpeeds)) {
            arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
        } else {
            arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
        }
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
                    playerManager.exoPlayer?.playbackParameters = PlaybackParameters(speed)
                    hideSystemBarsExtendView()
                }
                setOnCancelListener { hideSystemBarsExtendView() }
                show()
            }
        }

        // AutoPlay Interacted Tracking
        if (PrefManager.getVal(PrefName.AutoPlay)) {
            var touchTimer = Timer()
            fun touched() {
                interacted = true
                touchTimer.cancel()
                touchTimer.purge()
                touchTimer = Timer()
                touchTimer.schedule(object : TimerTask() {
                    override fun run() { interacted = false }
                }, 1000 * 60 * 60)
            }
            playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
                touched()
                false
            }
        }

        // Progress Dialog & Initial Episode
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
                playerManager.exoPlayer?.let { p ->
                    PrefManager.setCustomVal("${media.id}_$prevEpKey", p.currentPosition)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(prevEpKey)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != prevEpKey) {
                        PrefManager.setCustomVal("${media.id}_${cleanEp}", p.currentPosition)
                    }
                }
                subtitleManager.clearTransientSubtitleCache("${media.id}-$prevEpKey")
            }
            playerManager.exoPlayer?.pause()
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

    private fun initPlayer() {
        gestureManager.checkNotch()
        aniSkipManager.resetForNewEpisode()
        val selEp = media.anime?.selectedEpisode ?: episodeArr.firstOrNull() ?: return
        media.anime!!.selectedEpisode = selEp
        PrefManager.setCustomVal("${media.id}_current_ep", selEp)

        val list = (PrefManager.getNullableCustomVal("continueAnimeList", listOf<Int>(), List::class.java) as List<Int>).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)
        PrefManager.setCustomVal("continueAnimeList", list)

        lifecycleScope.launch(Dispatchers.IO) { extractor?.onVideoStopped(video) }

        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor }
            ?: episode.extractors?.firstOrNull() ?: return
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo) ?: ext.videos.firstOrNull() ?: return

        val subLanguages = arrayOf(
            "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
            "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
            "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
            "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
            "Turkish", "Ukrainian", "Urdu", "Vietnamese"
        )
        val lang = subLanguages.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
        val savedSubLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
        subtitle = intent.getSerialized("subtitle")
            ?: when {
                savedSubLang == null -> when (episode.selectedSubtitle) {
                    null, -1 -> ext.subtitles.find {
                        it.language.contains(lang, true) ||
                        it.language.contains("English", true) ||
                        it.language.contains("en", true)
                    } ?: ext.subtitles.firstOrNull()
                    else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                }
                savedSubLang == "None" -> null
                savedSubLang.startsWith("Online:") -> null
                savedSubLang.startsWith("[Local]") -> null
                savedSubLang.startsWith("Embedded:") -> null
                else -> ext.subtitles.find { it.language == savedSubLang }
            }

        hasExtSubtitles = ext.subtitles.isNotEmpty()
        if (subtitle == null && hasExtSubtitles && savedSubLang != "None" &&
            savedSubLang?.startsWith("Online:") != true &&
            savedSubLang?.startsWith("[Local]") != true &&
            savedSubLang?.startsWith("Embedded:") != true
        ) {
            subtitle = ext.subtitles.find {
                it.language.contains(lang, true) ||
                it.language.contains("English", true) ||
                it.language.contains("en", true)
            } ?: ext.subtitles.firstOrNull()
        }
        subtitleManager.initialSubtitleLabel = subtitle?.language ?: lang
        if (subtitle != null) {
            PrefManager.setCustomVal("subLang_${media.id}", subtitle!!.language)
            subtitleManager.setActiveServerSubtitle(subtitle)
        }

        exoSource.setOnClickListener { sourceClick() }

        if (isOnline(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (media.idIMDB == null) media.idIMDB = IdMappers.getImdbId(media.id)
                    val selectedEpisodeStr = media.anime?.selectedEpisode ?: "1"
                    val epObj = if (this@ExoplayerView::episode.isInitialized) episode else media.anime?.episodes?.getEpisode(selectedEpisodeStr)
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

        exoSubtitle.isVisible = true
        exoSubtitle.setOnClickListener { subClick() }

        val subConfigs = subtitleManager.buildSubtitleConfigurations(
            ext.subtitles, ext.server.embed.url, video!!.file.url, hasExtSubtitles, subtitle?.language ?: lang
        )

        lifecycleScope.launch(Dispatchers.IO) { ext.onVideoPlayed(video) }

        val videoUrl = video?.file?.url ?: ""
        val mimeType = when {
            video?.format == VideoType.M3U8 ||
                    videoUrl.contains(".m3u8", ignoreCase = true) ||
                    videoUrl.contains("/m3u8", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            video?.format == VideoType.DASH ||
                    videoUrl.contains(".mpd", ignoreCase = true) ||
                    videoUrl.contains("/mpd", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
            video?.format == VideoType.CONTAINER -> {
                if (videoUrl.startsWith("content://")) {
                    val decoded = runCatching { java.net.URLDecoder.decode(videoUrl, "UTF-8").lowercase() }.getOrDefault("")
                    when {
                        decoded.endsWith(".mkv") -> androidx.media3.common.MimeTypes.APPLICATION_MATROSKA
                        decoded.endsWith(".webm") -> androidx.media3.common.MimeTypes.APPLICATION_WEBM
                        else -> androidx.media3.common.MimeTypes.APPLICATION_MP4
                    }
                } else {
                    null // ExoPlayer auto-detect for non-local containers
                }
            }
            else -> null
        }

        val downloadedMediaItem = if (ext.server.offline) {
            val titleName = ext.server.name.split("/").first()
            val episodeName = ext.server.name.split("/").last()
            val directory = ani.dantotsu.download.DownloadsManager.getSubDirectory(this, ani.dantotsu.media.MediaType.ANIME, false, titleName, episodeName)
            if (directory != null) {
                val file = directory.listFiles()?.firstOrNull {
                    it.isFile && !it.name.orEmpty().contains("subtitle", ignoreCase = true) && !it.name.orEmpty().startsWith(".") &&
                    (it.name?.endsWith(".mp4", ignoreCase = true) == true ||
                     it.name?.endsWith(".mkv", ignoreCase = true) == true ||
                     it.name?.endsWith(".webm", ignoreCase = true) == true ||
                     it.name?.endsWith(".ts", ignoreCase = true) == true ||
                     it.type?.startsWith("video/") == true)
                } ?: directory.listFiles()?.firstOrNull {
                    it.isFile && !it.name.orEmpty().contains("subtitle", ignoreCase = true) && !it.name.orEmpty().startsWith(".")
                }
                if (file != null) {
                    val downloadedMimeType = when {
                        file.name?.endsWith(".mkv", ignoreCase = true) == true -> androidx.media3.common.MimeTypes.APPLICATION_MATROSKA
                        file.name?.endsWith(".webm", ignoreCase = true) == true -> androidx.media3.common.MimeTypes.APPLICATION_WEBM
                        file.name?.endsWith(".ts", ignoreCase = true) == true -> androidx.media3.common.MimeTypes.VIDEO_MP2T
                        else -> androidx.media3.common.MimeTypes.APPLICATION_MP4
                    }
                    MediaItem.Builder()
                        .setUri(file.uri)
                        .setMimeType(downloadedMimeType)
                        .build()
                } else null
            } else null
        } else null

        val episodeDisplayName = episodeTitleArr.getOrNull(currentEpisodeIndex) ?: episode.number
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(episodeDisplayName)
            .setArtist(media.userPreferredName)
            .setDisplayTitle(episodeDisplayName)
            .setAlbumArtist(media.userPreferredName)
            .apply {
                val thumbUrl = episode.thumb?.url ?: media.cover
                if (!thumbUrl.isNullOrEmpty()) {
                    try {
                        setArtworkUri(Uri.parse(thumbUrl))
                    } catch (_: Exception) {}
                }
            }
            .build()

        playerManager.buildMediaSource(
            video!!, subConfigs, mimeType, downloadedMediaItem, mediaMetadata, ext.audioTracks
        )

        castManager.setupCastButton(
            customCastButton, media, video, subtitle, hasExtSubtitles,
            episodeTitleArr.getOrNull(currentEpisodeIndex) ?: episode.number
        )

        subtitleManager.applySubtitleStyles(customSubtitleView)
        subtitleManager.setupSubFormatting(playerView)

        buildExoplayer()
    }

    private fun buildExoplayer() {
        customSubtitleView.text = ""
        customSubtitleView.visibility = View.GONE
        exoSubtitleView.visibility = View.GONE
        playerErrorRetryCount = 0
        hideSystemBarsExtendView()

        val selEp = media.anime?.selectedEpisode
        val cleanEp = selEp?.let { MediaNameAdapter.findEpisodeNumber(it) }?.let {
            if (it % 1 == 0f) it.toInt().toString() else it.toString()
        }
        val savedMax = selEp?.let { PrefManager.getCustomVal("${media.id}_${it}_max", Long.MAX_VALUE) }
            ?.takeIf { it != Long.MAX_VALUE }
            ?: (cleanEp?.let { PrefManager.getCustomVal("${media.id}_${it}_max", Long.MAX_VALUE) }?.takeIf { it != Long.MAX_VALUE })

        val savedPosition = if (savedMax != null && savedMax > 0L) {
            if (playbackPosition >= savedMax || playbackPosition > savedMax.toFloat() * 0.92f) {
                playbackPosition = 0L
                0L
            } else {
                playbackPosition
            }
        } else {
            playbackPosition
        }

        val speeds = if (PrefManager.getVal(PrefName.CursedSpeeds)) {
            arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
        } else {
            arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
        }
        val savedSpeedIndex = PrefManager.getCustomVal("${media.id}_speed", PrefManager.getVal<Int>(PrefName.DefaultSpeed))
            .coerceIn(0, speeds.size - 1)
        val currentSpeed = speeds[savedSpeedIndex]

        val exo = playerManager.buildExoplayer(
            savedPosition,
            PlaybackParameters(currentSpeed),
            this
        )
        playerView.player = exo

        castManager.updateCurrentMedia(playerManager.currentMediaItem, exo, video)

        subtitleManager.applySubtitleStyles(customSubtitleView)
        subtitleManager.setupSubFormatting(playerView)

        progressManager.updateWidgetState(isExiting = false)

        if (!hasExtSubtitles && !PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
            onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT)
        }

        val savedLang = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
        val isDisabled = if (hasExtSubtitles) {
            savedLang == "None"
        } else {
            savedLang == "None" || (subtitle == null && !PrefManager.getVal<Boolean>(PrefName.Subtitles))
        }

        val subLanguages = arrayOf(
            "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
            "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
            "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
            "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
            "Turkish", "Ukrainian", "Urdu", "Vietnamese"
        )
        val lang = subLanguages.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"

        val preferredLanguage = subtitle?.let { LanguageMapper.getLanguageCode(it.language) }
            ?: (if (lang.isNotBlank()) LanguageMapper.getLanguageCode(lang) else "en")
            ?: "en"

        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage(preferredLanguage)
            .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
            .build()
    }

    private fun releasePlayer() {
        playerManager.exoPlayer?.let { p ->
            isPlayerPlaying = p.playWhenReady
            playbackPosition = p.currentPosition
        }
        playerView.player = null
        customSubtitleView.text = ""
        exoSubtitleView.setCues(emptyList())
        progressManager.stopTracking()
        progressManager.updateWidgetState(isExiting = true)
        discordManager.clear()
        playerManager.release()
    }

    private fun sourceClick() {
        changingServer = true

        media.selected?.server = null
        playerManager.exoPlayer?.let { p ->
            PrefManager.setCustomVal(
                "${media.id}_${media.anime?.selectedEpisode}",
                p.currentPosition,
            )
            p.pause()
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        val epNum = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
        model.onEpisodeClick(
            media,
            epNum,
            this.supportFragmentManager,
            launch = false,
        )
    }

    private fun subClick() {
        Logger.log("subClick: Opening subtitle dialog")
        playerManager.exoPlayer?.let { p ->
            PrefManager.setCustomVal(
                "${media.id}_${media.anime?.selectedEpisode}",
                p.currentPosition,
            )
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        val dialog = SubtitleDialogFragment()
        Logger.log("subClick: Showing dialog")
        dialog.show(supportFragmentManager, "dialog")
    }

    // Public contract methods
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

    fun onSetTrackGroupOverride(
        trackGroup: Tracks.Group,
        type: @C.TrackType Int,
        index: Int = 0
    ) {
        subtitleManager.onSetTrackGroupOverride(trackGroup, type, index)
    }

    // Player.Listener implementation
    override fun onCues(cueGroup: CueGroup) {
        subtitleManager.handleCues(cueGroup, subtitle)
    }

    override fun onTracksChanged(tracks: Tracks) {
        subtitleManager.checkTracksForPendingSubtitles(tracks)

        val audioTrackGroups = ArrayList<Tracks.Group>()
        val subTrackGroups = ArrayList<Tracks.Group>()
        val audioTracks = mutableListOf<Pair<String, String>>()
        val subTracks = mutableListOf<Pair<String, String>>()

        tracks.groups.forEach { trackGroup ->
            when (trackGroup.type) {
                TRACK_TYPE_AUDIO -> {
                    audioTrackGroups.add(trackGroup)
                    for (i in 0 until trackGroup.length) {
                        val format = trackGroup.getTrackFormat(i)
                        audioTracks.add(Pair(format.language ?: "Default", format.label ?: "Default"))
                    }
                }
                TRACK_TYPE_TEXT -> {
                    subTrackGroups.add(trackGroup)
                    for (i in 0 until trackGroup.length) {
                        val format = trackGroup.getTrackFormat(i)
                        subTracks.add(Pair(format.language ?: "Default", format.label ?: "Default"))
                    }
                }
                else -> {}
            }
        }

        audioLanguages = audioTracks
        exoAudioTrack.isVisible = audioTracks.size > 1
        exoAudioTrack.setOnClickListener {
            TrackGroupDialogFragment(this, audioTrackGroups, TRACK_TYPE_AUDIO, audioTracks)
                .show(supportFragmentManager, "dialog")
        }

        currentSubTrackGroups.clear()
        currentSubTrackGroups.addAll(subTrackGroups)
        currentSubTracks.clear()
        currentSubTracks.addAll(subTracks)

        exoSubtitle.isVisible = true
        exoSubtitle.setOnClickListener { subClick() }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val player = playerManager.exoPlayer ?: return
        if (playbackState == ExoPlayer.STATE_READY) {
            player.play()
            if (progressManager.episodeLength <= 0f && player.duration > 0) {
                progressManager.episodeLength = player.duration.toFloat()
            }
            isBuffering = false
            checkAndLoadTimestamps()
        } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
            isBuffering = true
        } else if (playbackState == ExoPlayer.STATE_ENDED) {
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
    }

    private fun checkAndLoadTimestamps() {
        val player = playerManager.exoPlayer ?: return
        if (!aniSkipManager.isTimeStampsLoaded && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            val dur = player.duration
            val extTimestamps = extractor?.server?.video?.timestamps ?: emptyList()
            if (extTimestamps.isNotEmpty() || dur > 0) {
                val epString = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
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

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            (exoPlay.drawable as? Animatable)?.start()
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            discordManager.updatePresence(media, episode, playerManager.exoPlayer, isPlaying)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            discordManager.updatePresence(media, episode, playerManager.exoPlayer, isPlayerPlaying)
            if (isPlayerPlaying) playerManager.exoPlayer?.play()
            // Re-apply subtitle track selection after seek. ExoPlayer may invalidate track
            // group overrides when seeking into unbuffered regions of HLS/DASH streams, causing
            // subtitles to vanish until the user manually re-selects them from the menu.
            val player = playerManager.exoPlayer ?: return
            val activeId = subtitleManager.activeSubtitleId
            val activeName = subtitleManager.activeSubtitleDisplayName
            if (activeId != null || activeName != null) {
                val tracks = player.currentTracks
                // Set only pendingSubtitleLabel (not pendingTrackId) so checkTracksForPendingSubtitles
                // silently re-applies the selection without showing a "Subtitle loaded" snack.
                subtitleManager.pendingTrackId = null
                subtitleManager.pendingSubtitleLabel = activeName ?: activeId
                subtitleManager.checkTracksForPendingSubtitles(tracks)
            }
        }
    }

    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()
        val player = playerManager.exoPlayer ?: return

        val selEp = media.anime?.selectedEpisode
        if (selEp != null) {
            PrefManager.setCustomVal("${media.id}_${selEp}_max", player.duration)
            val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                if (it % 1 == 0f) it.toInt().toString() else it.toString()
            }
            if (cleanEp != null && cleanEp != selEp) {
                PrefManager.setCustomVal("${media.id}_${cleanEp}_max", player.duration)
            }
        }

        val format = player.videoFormat ?: return
        var height = format.height
        var width = format.width
        val rot = format.rotationDegrees
        if (rot == 90 || rot == 270) {
            val temp = width
            width = height
            height = temp
        }

        aspectRatio = Rational(width, height)
        videoInfo.text = getString(R.string.video_quality, height)

        if (player.duration < playbackPosition || playbackPosition > player.duration.toFloat() * 0.92) {
            playbackPosition = 0
            player.seekTo(0)
        }

        checkAndLoadTimestamps()
    }

    override fun onPlayerError(error: PlaybackException) {
        val cause = error.cause ?: error
        Logger.log("ExoPlayer error: ${error.errorCodeName} (${error.errorCode}): ${error.message}, cause: ${cause.message}")
        val player = playerManager.exoPlayer
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> {
                if (playerErrorRetryCount < MAX_PLAYER_ERROR_RETRIES) {
                    playerErrorRetryCount++
                    val savedPos = if (playerManager.isInitialized) player?.currentPosition?.takeIf { it > 0 } ?: playbackPosition else playbackPosition
                    if (playerManager.isInitialized && player != null) {
                        player.seekTo(savedPos)
                        player.prepare()
                        player.play()
                    }
                } else {
                    playerErrorRetryCount = 0
                    toast("Source Network Error: ${error.message}")
                    isPlayerPlaying = true
                    sourceClick()
                }
            }
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> {
                if (playerErrorRetryCount < MAX_PLAYER_ERROR_RETRIES) {
                    playerErrorRetryCount++
                    val savedPos = if (playerManager.isInitialized) player?.currentPosition?.takeIf { it > 0 } ?: playbackPosition else playbackPosition
                    val currentParams = playerManager.exoPlayer?.playbackParameters ?: PlaybackParameters(1f)
                    val fallbackExo = playerManager.buildExoplayer(savedPos, currentParams, this, forceDefaultRenderers = true)
                    playerView.player = fallbackExo
                } else {
                    playerErrorRetryCount = 0
                    toast("Player Error 1004: ${error.message}")
                    runCatching { Injekt.get<CrashlyticsInterface>().logException(error) }
                    sourceClick()
                }
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                if (playerErrorRetryCount < MAX_PLAYER_ERROR_RETRIES) {
                    playerErrorRetryCount++
                    val savedPos = if (playerManager.isInitialized) player?.currentPosition?.takeIf { it > 0 } ?: playbackPosition else playbackPosition
                    val currentParams = playerManager.exoPlayer?.playbackParameters ?: PlaybackParameters(1f)
                    val fallbackExo = playerManager.buildExoplayer(savedPos, currentParams, this, forceDefaultRenderers = false)
                    playerView.player = fallbackExo
                } else {
                    playerErrorRetryCount = 0
                    toast("Source Format Error (${error.errorCodeName}) : ${error.message}")
                    runCatching { Injekt.get<CrashlyticsInterface>().logException(error) }
                    sourceClick()
                }
            }
            else -> {
                toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
                runCatching { Injekt.get<CrashlyticsInterface>().logException(error) }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (PrefManager.getVal(PrefName.FocusPause) && !epChanging) {
            val player = playerManager.exoPlayer
            if (playerManager.isInitialized && !hasFocus && player != null) wasPlaying = player.playWhenReady
            if (hasFocus) {
                if (playerManager.isInitialized && wasPlaying) player?.play()
            } else {
                if (playerManager.isInitialized) player?.pause()
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.hideController()
            val ratio = aspectRatio.toFloat()
            val baseSubSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
            val pipSubSize = when {
                ratio > 1.5f -> baseSubSize * 0.45f
                ratio < 1.0f -> baseSubSize * 0.65f
                else -> baseSubSize * 0.55f
            }
            exoSubtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, pipSubSize)
            customSubtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, pipSubSize)
            customSubtitleView.translationY = 0f
        } else {
            val baseSubSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
            exoSubtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseSubSize)
            customSubtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseSubSize)
            val textElevation = PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50 * resources.displayMetrics.heightPixels
            customSubtitleView.translationY = -textElevation + 10f
        }
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
        hideSystemBarsExtendView()
        if (playerManager.isInitialized) {
            playerManager.exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        if (playerManager.isInitialized) {
            playerManager.exoPlayer?.pause()
        }
        aniSkipManager.stopTracking()
        progressManager.stopTracking()
    }

    override fun onStop() {
        super.onStop()
        if (playerManager.isInitialized) {
            playerManager.exoPlayer?.let { p ->
                val selEp = media.anime?.selectedEpisode
                if (selEp != null) {
                    PrefManager.setCustomVal("${media.id}_${selEp}", p.currentPosition)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != selEp) {
                        PrefManager.setCustomVal("${media.id}_${cleanEp}", p.currentPosition)
                    }
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        orientationListener?.disable()
        orientationListener = null
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                extractor?.onVideoStopped(video)
            } catch (_: Exception) {}
            try {
                val torrentManager = uy.kohesive.injekt.Injekt.get<ani.dantotsu.torrent.TorrentServerManager>()
                if (torrentManager.isRunning()) {
                    torrentManager.pauseActiveTorrent()
                    torrentManager.pruneCache()
                }
            } catch (_: Exception) {}
        }
        aniSkipManager.stopTracking()
        progressManager.stopTracking()
        try {
            if (playerManager.isInitialized) {
                progressManager.updateAniProgress()
                val episodeId = "${media.id}-${media.anime?.selectedEpisode ?: ""}"
                subtitleManager.clearTransientSubtitleCache(episodeId)
                releasePlayer()
            } else {
                playerView.player = null
            }
        } catch (_: Exception) {}
        castManager.release()
        super.onDestroy()
    }
}
