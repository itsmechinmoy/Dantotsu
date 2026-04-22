package ani.dantotsu.media.anime

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_GAIN
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
import android.media.AudioManager.STREAM_MUSIC
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.util.AttributeSet
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_B
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.math.MathUtils.clamp
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.mediarouter.app.MediaRouteButton
import ani.dantotsu.GesturesListener
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.brightnessConverter
import ani.dantotsu.circularReveal
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.connections.discord.RPC
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.databinding.ActivityExoplayerBinding
import ani.dantotsu.defaultHeaders
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.video.Helper
import ani.dantotsu.dp
import ani.dantotsu.getCurrentBrightnessValue
import ani.dantotsu.getLanguageCode
import ani.dantotsu.hideSystemBars
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.isOnline
import ani.dantotsu.logError
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.okHttpClient
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.AniSkip.getType
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.ResettableTimer
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
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
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.anggrayudi.storage.file.extension
import java.io.File
import kotlinx.coroutines.withContext
import com.bumptech.glide.Glide
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.slider.Slider
import com.lagradost.nicehttp.ignoreAllSSLErrors
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.net.toUri
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.StremioSub
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class ExoplayerView :
    AppCompatActivity(),
    Player.Listener,
    SessionAvailabilityListener {
    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"
    private val playerFullscreen = "playerFullscreen"
    private val playerOnPlay = "playerOnPlay"
    private var disappeared: Boolean = false
    private var functionstarted: Boolean = false

    private lateinit var exoPlayer: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var isCastApiAvailable = false
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var cacheFactory: CacheDataSource.Factory
    private lateinit var playbackParameters: PlaybackParameters
    private lateinit var mediaItem: MediaItem
    private lateinit var mediaSource: MergingMediaSource
    private var mediaSession: MediaSession? = null

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
    private var assHandler: AssHandler? = null
    private var assSubtitleView: io.github.peerless2012.ass.media.widget.AssSubtitleView? = null
    private lateinit var assMediaSourceFactory: DefaultMediaSourceFactory

    private var orientationListener: OrientationEventListener? = null

    private var downloadId: String? = null
    private var hasExtSubtitles = false
    private var audioLanguages = mutableListOf<Pair<String, String>>()

    companion object {
        var initialized = false
        lateinit var media: Media

        private const val DEFAULT_MIN_BUFFER_MS = 30000
        private const val DEFAULT_MAX_BUFFER_MS = 60000
        private const val BUFFER_FOR_PLAYBACK_MS = 2000   // 2s: faster start, still safe on 4G
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5000
    }

    private lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String, Episode>
    private lateinit var episodeArr: List<String>
    private lateinit var episodeTitleArr: ArrayList<String>
    private var currentEpisodeIndex = 0
    private var epChanging = false

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null

    private var notchHeight: Int = 0
    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private var episodeLength: Float = 0f
    private var isFullscreen: Int = 0
    private var isInitialized = false
    private var isPlayerPlaying = true
    private var changingServer = false
    private var interacted = false

    private var pipEnabled = false
    private var aspectRatio = Rational(16, 9)

    private val handler = Handler(Looper.getMainLooper())
    val model: MediaDetailsViewModel by viewModels()
    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        uri?.let { applyLocalSubtitle(it) }
    }

    private var isTimeStampsLoaded = false
    private var isSeeking = false
    private var isFastForwarding = false

    // Subtitle label to select the next time onTracksChanged fires (after setMediaItem+prepare).
    // Volatile so it is safely read from the Player.Listener callback thread.
    @Volatile private var pendingSubtitleLabel: String? = null

    var rotation = 0

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    notchHeight =
                        min(
                            displayCutout.boundingRects[0].width(),
                            displayCutout.boundingRects[0].height(),
                        )
                    checkNotch()
                }
            }
        }
        super.onAttachedToWindow()
    }

    private fun checkNotch() {
        if (notchHeight != 0) {
            val orientation = resources.configuration.orientation
            playerView
                .findViewById<View>(R.id.exo_controller_margin)
                .updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        marginStart = notchHeight
                        marginEnd = notchHeight
                        topMargin = 0
                    } else {
                        topMargin = notchHeight
                        marginStart = 0
                        marginEnd = 0
                    }
                }
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).translationY =
                (if (orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else (notchHeight + 8.toPx)).dp
            exoBrightnessCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd =
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
            exoVolumeCont.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart =
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) notchHeight else 0
            }
        }
    }

    private fun setupSubFormatting(playerView: PlayerView) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)

        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)

        val outline =
            when (PrefManager.getVal<Int>(PrefName.Outline)) {
                0 -> EDGE_TYPE_OUTLINE // Normal
                1 -> EDGE_TYPE_DEPRESSED // Shine
                2 -> EDGE_TYPE_DROP_SHADOW // Drop shadow
                3 -> EDGE_TYPE_NONE // No outline
                else -> EDGE_TYPE_OUTLINE // Normal
            }

        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground)

        val subWindow = PrefManager.getVal<Int>(PrefName.SubWindow)

        val font =
            when (PrefManager.getVal<Int>(PrefName.Font)) {
                0 -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
                1 -> ResourcesCompat.getFont(this, R.font.poppins_bold)
                2 -> ResourcesCompat.getFont(this, R.font.poppins)
                3 -> ResourcesCompat.getFont(this, R.font.poppins_thin)
                4 -> ResourcesCompat.getFont(this, R.font.century_gothic_regular)
                5 -> ResourcesCompat.getFont(this, R.font.levenim_mt_bold)
                6 -> ResourcesCompat.getFont(this, R.font.blocky)
                else -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
            }
        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()

        playerView.subtitleView?.let { subtitles ->
            subtitles.setApplyEmbeddedStyles(false)
            subtitles.setApplyEmbeddedFontSizes(false)

            subtitles.setStyle(
                CaptionStyleCompat(
                    primaryColor,
                    subBackground,
                    subWindow,
                    outline,
                    secondaryColor,
                    font,
                ),
            )

            subtitles.alpha =
                when (PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
                    true -> PrefManager.getVal(PrefName.SubAlpha)
                    false -> 0f
                }

            subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        }
    }

    private fun applySubtitleStyles(textView: Xubtitle) {
        val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)

        val subBackground = PrefManager.getVal<Int>(PrefName.SubBackground)

        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)

        val subStroke = PrefManager.getVal<Float>(PrefName.SubStroke)

        val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()

        val font =
            when (PrefManager.getVal<Int>(PrefName.Font)) {
                0 -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
                1 -> ResourcesCompat.getFont(this, R.font.poppins_bold)
                2 -> ResourcesCompat.getFont(this, R.font.poppins)
                3 -> ResourcesCompat.getFont(this, R.font.poppins_thin)
                4 -> ResourcesCompat.getFont(this, R.font.century_gothic_regular)
                5 -> ResourcesCompat.getFont(this, R.font.levenim_mt_bold)
                6 -> ResourcesCompat.getFont(this, R.font.blocky)
                else -> ResourcesCompat.getFont(this, R.font.poppins_semi_bold)
            }

        textView.setBackgroundColor(subBackground)
        textView.setTextColor(primaryColor)
        textView.typeface = font
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)

        textView.apply {
            when (PrefManager.getVal<Int>(PrefName.Outline)) {
                0 -> applyOutline(secondaryColor, subStroke)
                1 -> applyShineEffect(secondaryColor)
                2 -> applyDropShadow(secondaryColor, subStroke)
                3 -> {}
                else -> applyOutline(secondaryColor, subStroke)
            }
        }

        textView.alpha =
            when (PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
                true -> PrefManager.getVal(PrefName.SubAlpha)
                false -> 0f
            }

        val textElevation =
            PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50 * resources.displayMetrics.heightPixels

        // Add offset to move subtitles slightly down for better alignment
        // This helps align online subtitles with local subtitles
        val positionOffset = 10f // Increased to 120f to push subtitles very close to bottom edge
        textView.translationY = -textElevation + positionOffset
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize
        isCastApiAvailable = GoogleApiAvailability
            .getInstance()
            .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        try {
            castContext =
                CastContext.getSharedInstance(this, Executors.newSingleThreadExecutor()).result
            castPlayer = CastPlayer(castContext!!)
            castPlayer!!.setSessionAvailabilityListener(this)
        } catch (e: Exception) {
            isCastApiAvailable = false
        }

        hideSystemBarsExtendView()

        onBackPressedDispatcher.addCallback(this) {
            finishAndRemoveTask()
        }

        playerView = findViewById(R.id.player_view)
        exoPlay = playerView.findViewById(androidx.media3.ui.R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoAudioTrack = playerView.findViewById(R.id.exo_audio)
        exoSubtitleView = playerView.findViewById(androidx.media3.ui.R.id.exo_subtitles)
        // Adjust bottom padding to absolute edge
        // 0.0f (0%) pushes subtitles to the very bottom
        exoSubtitleView?.setBottomPaddingFraction(0.0f)

        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoSpeed = playerView.findViewById(androidx.media3.ui.R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoPip = playerView.findViewById(R.id.exo_pip)
        exoSkipOpEd = playerView.findViewById(R.id.exo_skip_op_ed)
        exoSkip = playerView.findViewById(R.id.exo_skip)
        skipTimeButton = playerView.findViewById(R.id.exo_skip_timestamp)
        skipTimeText = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
        timeStampText = playerView.findViewById(R.id.exo_time_stamp_text)
        customSubtitleView = playerView.findViewById(R.id.customSubtitleView)

        animeTitle = playerView.findViewById(R.id.exo_anime_title)
        episodeTitle = playerView.findViewById(R.id.exo_ep_sel)

        playerView.controllerShowTimeoutMs = 5000

        val audioManager = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager

        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus({ focus ->
            when (focus) {
                AUDIOFOCUS_LOSS_TRANSIENT, AUDIOFOCUS_LOSS -> if (isInitialized) exoPlayer.pause()
                AUDIOFOCUS_GAIN -> if (isInitialized && isPlayerPlaying) exoPlayer.play()
            }
        }, AUDIO_CONTENT_TYPE_MOVIE, AUDIOFOCUS_GAIN)

        if (System.getInt(contentResolver, System.ACCELEROMETER_ROTATION, 0) != 1) {
            if (PrefManager.getVal(PrefName.RotationPlayer)) {
                orientationListener =
                    object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
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

        // BackButton
        playerView.findViewById<ImageButton>(R.id.exo_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // TimeStamps
        model.timeStamps.observe(this) { it ->
            isTimeStampsLoaded = true
            exoSkipOpEd.visibility =
                if (it != null) {
                    val adGroups =
                        it
                            .flatMap {
                                listOf(
                                    it.interval.startTime.toLong() * 1000,
                                    it.interval.endTime.toLong() * 1000,
                                )
                            }.toLongArray()
                    val playedAdGroups =
                        it
                            .flatMap {
                                listOf(false, false)
                            }.toBooleanArray()
                    playerView.setExtraAdGroupMarkers(adGroups, playedAdGroups)
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        exoSkipOpEd.setOnClickListener {
            if (PrefManager.getVal(PrefName.AutoSkipOPED)) {
                snackString(getString(R.string.disabled_auto_skip))
                PrefManager.setVal(PrefName.AutoSkipOPED, false)
            } else {
                snackString(getString(R.string.auto_skip))
                PrefManager.setVal(PrefName.AutoSkipOPED, true)
            }
            exoSkipOpEd.alpha = if (PrefManager.getVal(PrefName.AutoSkipOPED)) 1f else 0.3f
        }

        // Play Pause
        exoPlay.setOnClickListener {
            if (isInitialized) {
                isPlayerPlaying = exoPlayer.isPlaying
                (exoPlay.drawable as Animatable?)?.start()
                if (isPlayerPlaying || castPlayer?.isPlaying == true) {
                    Glide.with(this).load(R.drawable.anim_play_to_pause).into(exoPlay)
                    exoPlayer.pause()
                    castPlayer?.pause()
                } else {
                    if (castPlayer?.isPlaying == false && castPlayer?.currentMediaItem != null) {
                        Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                        castPlayer?.play()
                    } else if (!isPlayerPlaying) {
                        Glide.with(this).load(R.drawable.anim_pause_to_play).into(exoPlay)
                        exoPlayer.play()
                    }
                }
            }
        }

        // Picture-in-picture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pipEnabled =
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                        PrefManager.getVal(
                            PrefName.Pip,
                        )
            if (pipEnabled) {
                exoPip.visibility = View.VISIBLE
                exoPip.setOnClickListener {
                    enterPipMode()
                }
            } else {
                exoPip.visibility = View.GONE
            }
        }

        // Lock Button
        var locked = false
        val container = playerView.findViewById<View>(R.id.exo_controller_cont)
        val screen = playerView.findViewById<View>(R.id.exo_black_screen)
        val lockButton = playerView.findViewById<ImageButton>(R.id.exo_unlock)
        val timeline =
            playerView.findViewById<ExtendedTimeBar>(androidx.media3.ui.R.id.exo_progress)
        playerView.findViewById<ImageButton>(R.id.exo_lock).setOnClickListener {
            locked = true
            screen.visibility = View.GONE
            container.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            timeline.setForceDisabled(true)
        }
        lockButton.setOnClickListener {
            locked = false
            screen.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            it.visibility = View.GONE
            timeline.setForceDisabled(false)
        }

        // Skip Time Button
        var skipTime = PrefManager.getVal<Int>(PrefName.SkipTime)
        if (skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
            exoSkip.setOnClickListener {
                if (isInitialized) {
                    exoPlayer.seekTo(exoPlayer.currentPosition + skipTime * 1000)
                }
            }
            exoSkip.setOnLongClickListener {
                val dialog = Dialog(this, R.style.MyPopup)
                dialog.setContentView(R.layout.item_seekbar_dialog)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                if (skipTime <= 120) {
                    dialog.findViewById<Slider>(R.id.seekbar).value = skipTime.toFloat()
                } else {
                    dialog.findViewById<Slider>(R.id.seekbar).value = 120f
                }
                dialog.findViewById<Slider>(R.id.seekbar).addOnChangeListener { _, value, _ ->
                    skipTime = value.toInt()
                    // saveData(player, settings)
                    PrefManager.setVal(PrefName.SkipTime, skipTime)
                    playerView.findViewById<TextView>(R.id.exo_skip_time).text =
                        skipTime.toString()
                    dialog.findViewById<TextView>(R.id.seekbar_value).text =
                        skipTime.toString()
                }
                dialog
                    .findViewById<Slider>(R.id.seekbar)
                    .addOnSliderTouchListener(
                        object : Slider.OnSliderTouchListener {
                            override fun onStartTrackingTouch(slider: Slider) {}

                            override fun onStopTrackingTouch(slider: Slider) {
                                dialog.dismiss()
                            }
                        },
                    )
                dialog.findViewById<TextView>(R.id.seekbar_title).text =
                    getString(R.string.skip_time)
                dialog.findViewById<TextView>(R.id.seekbar_value).text =
                    skipTime.toString()
                @Suppress("DEPRECATION")
                dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                dialog.show()
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }

        val gestureSpeed = (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()
        // Player UI Visibility Handler
        val brightnessRunnable =
            Runnable {
                if (exoBrightnessCont.alpha == 1f) {
                    lifecycleScope.launch {
                        ObjectAnimator
                            .ofFloat(exoBrightnessCont, "alpha", 1f, 0f)
                            .setDuration(gestureSpeed)
                            .start()
                        delay(gestureSpeed)
                        exoBrightnessCont.visibility = View.GONE
                        checkNotch()
                    }
                }
            }
        val volumeRunnable =
            Runnable {
                if (exoVolumeCont.alpha == 1f) {
                    lifecycleScope.launch {
                        ObjectAnimator
                            .ofFloat(exoVolumeCont, "alpha", 1f, 0f)
                            .setDuration(gestureSpeed)
                            .start()
                        delay(gestureSpeed)
                        exoVolumeCont.visibility = View.GONE
                        checkNotch()
                    }
                }
            }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.GONE) {
                    hideSystemBars()
                    brightnessRunnable.run()
                    volumeRunnable.run()
                }
            },
        )
        val overshoot = AnimationUtils.loadInterpolator(this, R.anim.over_shoot)
        val controllerDuration = (300 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong()

        fun handleController() {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) !isInPictureInPictureMode else true) {
                if (playerView.isControllerFullyVisible) {
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_controller),
                            "alpha",
                            1f,
                            0f,
                        ).setDuration(controllerDuration)
                        .start()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_bottom_cont),
                            "translationY",
                            0f,
                            128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_timeline_cont),
                            "translationY",
                            0f,
                            128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_top_cont),
                            "translationY",
                            0f,
                            -128f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    playerView.postDelayed({ playerView.hideController() }, controllerDuration)
                } else {
                    checkNotch()
                    playerView.showController()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_controller),
                            "alpha",
                            0f,
                            1f,
                        ).setDuration(controllerDuration)
                        .start()
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_bottom_cont),
                            "translationY",
                            128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_timeline_cont),
                            "translationY",
                            128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                    ObjectAnimator
                        .ofFloat(
                            playerView.findViewById(R.id.exo_top_cont),
                            "translationY",
                            -128f,
                            0f,
                        ).apply {
                            interpolator = overshoot
                            duration = controllerDuration
                            start()
                        }
                }
            }
        }

        playerView.findViewById<View>(R.id.exo_full_area).setOnClickListener {
            handleController()
        }

        val rewindText = playerView.findViewById<TextView>(R.id.exo_fast_rewind_anim)
        val forwardText = playerView.findViewById<TextView>(R.id.exo_fast_forward_anim)
        val fastForwardCard = playerView.findViewById<View>(R.id.exo_fast_forward)
        val fastRewindCard = playerView.findViewById<View>(R.id.exo_fast_rewind)

        // Seeking
        val seekTimerF = ResettableTimer()
        val seekTimerR = ResettableTimer()
        var seekTimesF = 0
        var seekTimesR = 0

        fun seek(
            forward: Boolean,
            event: MotionEvent? = null,
        ) {
            val seekTime = PrefManager.getVal<Int>(PrefName.SeekTime)
            val (card, text) =
                if (forward) {
                    val text = "+${seekTime * ++seekTimesF}"
                    forwardText.text = text
                    handler.post { exoPlayer.seekTo(exoPlayer.currentPosition + seekTime * 1000) }
                    fastForwardCard to forwardText
                } else {
                    val text = "-${seekTime * ++seekTimesR}"
                    rewindText.text = text
                    handler.post { exoPlayer.seekTo(exoPlayer.currentPosition - seekTime * 1000) }
                    fastRewindCard to rewindText
                }

            //region Double Tap Animation
            val showCardAnim = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f).setDuration(300)
            val showTextAnim = ObjectAnimator.ofFloat(text, "alpha", 0f, 1f).setDuration(150)

            fun startAnim() {
                showTextAnim.start()

                (text.compoundDrawables[1] as Animatable).apply {
                    if (!isRunning) start()
                }

                if (!isSeeking && event != null) {
                    playerView.hideController()
                    card.circularReveal(event.x.toInt(), event.y.toInt(), !forward, 800)
                    showCardAnim.start()
                }
            }

            fun stopAnim() {
                handler.post {
                    showCardAnim.cancel()
                    showTextAnim.cancel()
                    ObjectAnimator.ofFloat(card, "alpha", card.alpha, 0f).setDuration(150).start()
                    ObjectAnimator.ofFloat(text, "alpha", 1f, 0f).setDuration(150).start()
                }
            }
            //endregion

            startAnim()

            isSeeking = true

            if (forward) {
                seekTimerR.reset(
                    object : TimerTask() {
                        override fun run() {
                            isSeeking = false
                            stopAnim()
                            seekTimesF = 0
                        }
                    },
                    850,
                )
            } else {
                seekTimerF.reset(
                    object : TimerTask() {
                        override fun run() {
                            isSeeking = false
                            stopAnim()
                            seekTimesR = 0
                        }
                    },
                    850,
                )
            }
        }

        if (!PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            playerView.findViewById<View>(R.id.exo_fast_forward_button_cont).visibility =
                View.VISIBLE
            playerView.findViewById<View>(R.id.exo_fast_rewind_button_cont).visibility =
                View.VISIBLE
            playerView.findViewById<ImageButton>(R.id.exo_fast_forward_button).setOnClickListener {
                if (isInitialized) {
                    seek(true)
                }
            }
            playerView.findViewById<ImageButton>(R.id.exo_fast_rewind_button).setOnClickListener {
                if (isInitialized) {
                    seek(false)
                }
            }
        }

        keyMap[KEYCODE_DPAD_RIGHT] = { seek(true) }
        keyMap[KEYCODE_DPAD_LEFT] = { seek(false) }

        // Screen Gestures
        if (PrefManager.getVal<Boolean>(PrefName.Gestures) || PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
            fun doubleTap(
                forward: Boolean,
                event: MotionEvent,
            ) {
                if (!locked && isInitialized && PrefManager.getVal<Boolean>(PrefName.DoubleTap)) {
                    seek(forward, event)
                }
            }

            // Brightness
            var brightnessTimer = Timer()
            exoBrightnessCont.visibility = View.GONE

            fun brightnessHide() {
                brightnessTimer.cancel()
                brightnessTimer.purge()
                val timerTask: TimerTask =
                    object : TimerTask() {
                        override fun run() {
                            handler.post(brightnessRunnable)
                        }
                    }
                brightnessTimer = Timer()
                brightnessTimer.schedule(timerTask, 3000)
            }
            exoBrightness.value = (getCurrentBrightnessValue(this) * 10f)

            exoBrightness.addOnChangeListener { _, value, _ ->
                val lp = window.attributes
                lp.screenBrightness =
                    brightnessConverter((value.takeIf { !it.isNaN() } ?: 0f) / 10, false)
                window.attributes = lp
                brightnessHide()
            }

            // Volume
            var volumeTimer = Timer()
            exoVolumeCont.visibility = View.GONE

            val volumeMax = audioManager.getStreamMaxVolume(STREAM_MUSIC)
            exoVolume.value = audioManager.getStreamVolume(STREAM_MUSIC).toFloat() / volumeMax * 10

            fun volumeHide() {
                volumeTimer.cancel()
                volumeTimer.purge()
                val timerTask: TimerTask =
                    object : TimerTask() {
                        override fun run() {
                            handler.post(volumeRunnable)
                        }
                    }
                volumeTimer = Timer()
                volumeTimer.schedule(timerTask, 3000)
            }
            exoVolume.addOnChangeListener { _, value, _ ->
                val volume = ((value.takeIf { !it.isNaN() } ?: 0f) / 10 * volumeMax).roundToInt()
                audioManager.setStreamVolume(STREAM_MUSIC, volume, 0)
                volumeHide()
            }
            val fastForward = playerView.findViewById<TextView>(R.id.exo_fast_forward_text)
            val minLongPressSpeed = 0.25f
            val maxLongPressSpeed = 4f
            val dragSpeedSensitivity = 4f
            val minSpeedUpdateDelta = 0.01f
            var fastForwardStartX = 0f
            var fastForwardInitialSpeed = 1f
            var fastForwardOriginalSpeed = 1f
            var lastFastForwardSpeed = 1f

            fun updateFastForwardText(speed: Float) {
                fastForward.text = String.format(Locale.US, "%.2fx", speed)
            }

            fun fastForward(event: MotionEvent) {
                isFastForwarding = true
                fastForwardStartX = event.rawX
                fastForwardOriginalSpeed = exoPlayer.playbackParameters.speed
                fastForwardInitialSpeed = clamp(fastForwardOriginalSpeed * 2f, minLongPressSpeed, maxLongPressSpeed)
                exoPlayer.setPlaybackSpeed(fastForwardInitialSpeed)
                lastFastForwardSpeed = fastForwardInitialSpeed
                fastForward.visibility = View.VISIBLE
                updateFastForwardText(exoPlayer.playbackParameters.speed)
            }

            fun updateFastForwardSpeed(event: MotionEvent) {
                if (!isFastForwarding) return
                val width = playerView.width.toFloat().takeIf { it > 0f } ?: return
                val deltaRatio = (event.rawX - fastForwardStartX) / width
                val targetSpeed =
                    clamp(
                        fastForwardInitialSpeed + (deltaRatio * dragSpeedSensitivity),
                        minLongPressSpeed,
                        maxLongPressSpeed,
                    )
                if (abs(targetSpeed - lastFastForwardSpeed) < minSpeedUpdateDelta) return
                exoPlayer.setPlaybackSpeed(targetSpeed)
                lastFastForwardSpeed = targetSpeed
                updateFastForwardText(exoPlayer.playbackParameters.speed)
            }

            fun stopFastForward() {
                if (isFastForwarding) {
                    isFastForwarding = false
                    exoPlayer.setPlaybackSpeed(fastForwardOriginalSpeed)
                    fastForward.visibility = View.GONE
                }
            }

            // FastRewind (Left Panel)
            val fastRewindDetector =
                GestureDetector(
                    this,
                    object : GesturesListener() {
                        override fun onLongClick(event: MotionEvent) {
                            if (PrefManager.getVal(PrefName.FastForward)) fastForward(event)
                        }

                        override fun onDoubleClick(event: MotionEvent) {
                            doubleTap(false, event)
                        }

                        override fun onScrollYClick(y: Float) {
                            if (!locked && PrefManager.getVal(PrefName.Gestures)) {
                                exoBrightness.value = clamp(exoBrightness.value + y / 100, 0f, 10f)
                                if (exoBrightnessCont.visibility != View.VISIBLE) {
                                    exoBrightnessCont.visibility = View.VISIBLE
                                }
                                exoBrightnessCont.alpha = 1f
                            }
                        }

                        override fun onSingleClick(event: MotionEvent) =
                            if (isSeeking) doubleTap(false, event) else handleController()
                    },
                )
            val rewindArea = playerView.findViewById<View>(R.id.exo_rewind_area)
            rewindArea.isClickable = true
            rewindArea.setOnTouchListener { v, event ->
                fastRewindDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
                }
                v.performClick()
                true
            }

            // FastForward (Right Panel)
            val fastForwardDetector =
                GestureDetector(
                    this,
                    object : GesturesListener() {
                        override fun onLongClick(event: MotionEvent) {
                            if (PrefManager.getVal(PrefName.FastForward)) fastForward(event)
                        }

                        override fun onDoubleClick(event: MotionEvent) {
                            doubleTap(true, event)
                        }

                        override fun onScrollYClick(y: Float) {
                            if (!locked && PrefManager.getVal(PrefName.Gestures)) {
                                exoVolume.value = clamp(exoVolume.value + y / 100, 0f, 10f)
                                if (exoVolumeCont.visibility != View.VISIBLE) {
                                    exoVolumeCont.visibility = View.VISIBLE
                                }
                                exoVolumeCont.alpha = 1f
                            }
                        }

                        override fun onSingleClick(event: MotionEvent) =
                            if (isSeeking) doubleTap(true, event) else handleController()
                    },
                )
            val forwardArea = playerView.findViewById<View>(R.id.exo_forward_area)
            forwardArea.isClickable = true
            forwardArea.setOnTouchListener { v, event ->
                fastForwardDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
                }
                v.performClick()
                true
            }
        }

        // Handle Media
        if (!initialized) return startMainActivity(this)
        model.setMedia(media)
        title = media.userPreferredName
        episodes = media.anime?.episodes ?: return startMainActivity(this)

        videoInfo = playerView.findViewById(R.id.exo_video_info)

        model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources

        model.epChanged.observe(this) {
            epChanging = !it
        }

        // Anime Title
        animeTitle.text = media.userPreferredName

        episodeArr = episodes.keys.toList()
        currentEpisodeIndex = episodeArr.indexOf(media.anime!!.selectedEpisode!!)

        episodeTitleArr = arrayListOf()
        episodes.forEach {
            val episode = it.value
            val cleanedTitle = MediaNameAdapter.removeEpisodeNumberCompletely(episode.title ?: "")
            episodeTitleArr.add(
                "Episode ${episode.number}${if (episode.filler) " [Filler]" else ""}${if (cleanedTitle.isNotBlank() && cleanedTitle != "null") ": $cleanedTitle" else ""}",
            )
        }

        // Episode Change
        fun change(index: Int) {
            if (isInitialized) {
                changingServer = false
                PrefManager.setCustomVal(
                    "${media.id}_${episodeArr[currentEpisodeIndex]}",
                    exoPlayer.currentPosition,
                )
                val prev = episodeArr[currentEpisodeIndex]
                // Clear transient subtitle caches for the episode we are leaving
                val leavingEpisodeId = "${media.id}-${episodeArr[currentEpisodeIndex]}"
                clearTransientSubtitleCache(leavingEpisodeId)
                isTimeStampsLoaded = false
                episodeLength = 0f
                media.anime!!.selectedEpisode = episodeArr[index]
                model.setMedia(media)
                model.epChanged.postValue(false)
                model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "change")
                model.onEpisodeClick(
                    media,
                    media.anime!!.selectedEpisode!!,
                    this.supportFragmentManager,
                    false,
                    prev,
                )
            }
        }

        // EpisodeSelector
        episodeTitle.adapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, episodeTitleArr)
        episodeTitle.setSelection(currentEpisodeIndex)
        episodeTitle.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long,
                ) {
                    if (position != currentEpisodeIndex) {
                        disappeared = false
                        functionstarted = false
                        change(position)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        // Next Episode
        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener {
            if (isInitialized) {
                nextEpisode { i ->
                    updateAniProgress()
                    disappeared = false
                    functionstarted = false
                    change(currentEpisodeIndex + i)
                }
            }
        }
        // Prev Episode
        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                disappeared = false
                change(currentEpisodeIndex - 1)
            } else {
                snackString(getString(R.string.first_episode))
            }
        }

        model.getEpisode().observe(this) { ep ->
            hideSystemBars()
            if (ep != null && !epChanging) {
                episode = ep
                media.selected = model.loadSelected(media)
                model.setMedia(media)
                currentEpisodeIndex = episodeArr.indexOf(ep.number)
                episodeTitle.setSelection(currentEpisodeIndex)
                if (isInitialized) releasePlayer()
                playbackPosition =
                    PrefManager.getCustomVal(
                        "${media.id}_${ep.number}",
                        0,
                    )
                initPlayer()
                preloading = false
                updateProgress()
            }
        }

        // FullScreen
        isFullscreen = PrefManager.getCustomVal("${media.id}_fullscreenInt", isFullscreen)
        playerView.resizeMode =
            when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        exoScreen.setOnClickListener {
            if (isFullscreen < 2) isFullscreen += 1 else isFullscreen = 0
            playerView.resizeMode =
                when (isFullscreen) {
                    0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            snackString(
                when (isFullscreen) {
                    0 -> "Original"
                    1 -> "Zoom"
                    2 -> "Stretch"
                    else -> "Original"
                },
            )
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        // Cast
        if (PrefManager.getVal(PrefName.Cast)) {
            playerView.findViewById<CustomCastButton>(R.id.exo_cast).apply {
                visibility = View.VISIBLE
                if (PrefManager.getVal(PrefName.UseInternalCast)) {
                    try {
                        CastButtonFactory.setUpMediaRouteButton(context, this)
                        dialogFactory = CustomCastThemeFactory()
                    } catch (e: Exception) {
                        isCastApiAvailable = false
                    }
                } else {
                    setCastCallback { cast() }
                }
            }
        }

        // Settings
        exoSettings.setOnClickListener {
            PrefManager.setCustomVal(
                "${media.id}_${media.anime!!.selectedEpisode}",
                exoPlayer.currentPosition,
            )
            val intent =
                Intent(this, PlayerSettingsActivity::class.java).apply {
                    putExtra("subtitle", subtitle)
                }
            exoPlayer.pause()
            onChangeSettings.launch(intent)
        }

        // Speed
        val speeds =
            if (PrefManager.getVal(PrefName.CursedSpeeds)) {
                arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
            } else {
                arrayOf(
                    0.25f,
                    0.33f,
                    0.5f,
                    0.66f,
                    0.75f,
                    1f,
                    1.15f,
                    1.25f,
                    1.33f,
                    1.5f,
                    1.66f,
                    1.75f,
                    2f,
                )
            }

        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        // var curSpeed = loadData("${media.id}_speed", this) ?: settings.defaultSpeed
        val speedsLength = speeds.size
        val savedIndex = PrefManager.getCustomVal(
            "${media.id}_speed",
            PrefManager.getVal<Int>(PrefName.DefaultSpeed),
        )
        var curSpeed = savedIndex.coerceIn(0, speedsLength - 1)


        playbackParameters = PlaybackParameters(speeds[curSpeed])
        var speed: Float
        exoSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.speed)
                singleChoiceItems(speedsName, curSpeed) { i ->
                    PrefManager.setCustomVal("${media.id}_speed", i)
                    speed = speeds.getOrNull(i) ?: 1f
                    curSpeed = i
                    playbackParameters = PlaybackParameters(speed)
                    exoPlayer.playbackParameters = playbackParameters
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        if (PrefManager.getVal(PrefName.AutoPlay)) {
            var touchTimer = Timer()

            fun touched() {
                interacted = true
                touchTimer.apply {
                    cancel()
                    purge()
                }
                touchTimer = Timer()
                touchTimer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            interacted = false
                        }
                    },
                    1000 * 60 * 60,
                )
            }
            playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
                touched()
                false
            }
        }

        isFullscreen = PrefManager.getVal(PrefName.Resize)
        playerView.resizeMode =
            when (isFullscreen) {
                0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        preloading = false
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        val showProgressDialog =
            if (PrefManager.getVal(PrefName.AskIndividualPlayer)) {
                PrefManager.getCustomVal(
                    "${media.id}_progressDialog",
                    true,
                )
            } else {
                false
            }
        if (!incognito &&
            showProgressDialog &&
            Anilist.userid != null &&
            if (media.isAdult) {
                PrefManager.getVal(
                    PrefName.UpdateForHPlayer,
                )
            } else {
                true
            }
        ) {
            customAlertDialog().apply {
                setTitle(getString(R.string.auto_update, media.userPreferredName))
                setCancelable(false)
                setPosButton(R.string.yes) {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        false,
                    )
                    PrefManager.setCustomVal(
                        "${media.id}_save_progress",
                        true,
                    )
                    model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                }
                setNegButton(R.string.no) {
                    PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        false,
                    )
                    PrefManager.setCustomVal(
                        "${media.id}_save_progress",
                        false,
                    )
                    toast(getString(R.string.reset_auto_update))
                    model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        } else {
            model.setEpisode(episodes[media.anime!!.selectedEpisode!!]!!, "invoke")
        }

        // Start the recursive Fun
        if (PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            updateTimeStamp()
        }
    }

    private fun discordRPC() {
        val context = this
        val ep = episode
        val offline: Boolean = PrefManager.getVal(PrefName.OfflineMode)
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        val rpcenabled: Boolean = PrefManager.getVal(PrefName.rpcEnabled)
        if (RPCManager.shouldSuppressForAdultMedia(media.isAdult)) {
            RPCManager.clearPresence(context)
            return
        }
        if ((isOnline(context) && !offline) && Discord.token != null && !incognito && rpcenabled) {
            lifecycleScope.launch {
                val buttons = mutableListOf<RPC.Link>()
                buttons.add(RPC.Link("View Anime", "https://anilist.co/anime/${media.id}/"))
                media.idMAL?.let {
                    buttons.add(RPC.Link("View on MyAnimeList", "https://myanimelist.net/anime/$it"))
                }

                val now = java.lang.System.currentTimeMillis()
                val currentPosMs = if (exoPlayer.currentPosition > 0) exoPlayer.currentPosition else 0L
                val safeDurationMs = if (exoPlayer.duration > 0 && exoPlayer.duration != C.TIME_UNSET) exoPlayer.duration else 1440000L // default 24 mins

                // If paused, we don't send timestamps so the timer stops
                val isPaused = !isPlayerPlaying
                val startTimestamp = if (isPaused) null else now - currentPosMs
                val endTimestamp = if (isPaused) null else (now - currentPosMs) + safeDurationMs

                val stateText = "Episode : ${ep.number}/${media.anime?.totalEpisodes ?: "??"}"
                val finalState = if (isPaused) "Paused - $stateText" else stateText

                val rpcData = RPC.Companion.RPCData(
                    applicationId = Discord.application_Id,
                    type = RPC.Type.WATCHING,
                    activityName = media.userPreferredName,
                    details = ep.title?.takeIf { it.isNotEmpty() } ?: getString(R.string.episode_num, ep.number),
                    startTimestamp = startTimestamp,
                    stopTimestamp = endTimestamp,
                    state = finalState,
                    largeImage = media.cover?.let { RPC.Link(media.userPreferredName, it) },
                    smallImage = RPC.Link("Dantotsu", Discord.small_Image),
                    buttons = buttons,
                )
                RPCManager.setPresence(context, rpcData)
            }
        }
    }

    private fun initPlayer() {
        checkNotch()

        PrefManager.setCustomVal(
            "${media.id}_current_ep",
            media.anime!!.selectedEpisode!!,
        )

        @Suppress("UNCHECKED_CAST")
        val list =
            (
                    PrefManager.getNullableCustomVal(
                        "continueAnimeList",
                        listOf<Int>(),
                        List::class.java,
                    ) as List<Int>
                    ).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)
        PrefManager.setCustomVal("continueAnimeList", list)

        lifecycleScope.launch(Dispatchers.IO) {
            extractor?.onVideoStopped(video)
        }

        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor } ?: return
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo) ?: return
        val subLanguages =
            arrayOf(
                "Albanian",
                "Arabic",
                "Bosnian",
                "Bulgarian",
                "Chinese",
                "Croatian",
                "Czech",
                "Danish",
                "Dutch",
                "English",
                "Estonian",
                "Finnish",
                "French",
                "Georgian",
                "German",
                "Greek",
                "Hebrew",
                "Hindi",
                "Indonesian",
                "Irish",
                "Italian",
                "Japanese",
                "Korean",
                "Lithuanian",
                "Luxembourgish",
                "Macedonian",
                "Mongolian",
                "Norwegian",
                "Polish",
                "Portuguese",
                "Punjabi",
                "Romanian",
                "Russian",
                "Serbian",
                "Slovak",
                "Slovenian",
                "Spanish",
                "Turkish",
                "Ukrainian",
                "Urdu",
                "Vietnamese",
            )
        val lang = subLanguages[PrefManager.getVal(PrefName.SubLanguage)]
        subtitle = intent.getSerialized("subtitle")
            ?: when (
                val subLang: String? =
                    PrefManager.getNullableCustomVal(
                        "subLang_${media.id}",
                        null,
                        String::class.java
                    )
            ) {
                null -> {
                    when (episode.selectedSubtitle) {
                        null -> null
                        -1 ->
                            ext.subtitles.find {
                                it.language.contains(lang, ignoreCase = true) ||
                                        it.language.contains(
                                            getLanguageCode(lang),
                                            ignoreCase = true
                                        )
                            }

                        else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                    }
                }

                "None" -> ext.subtitles.let { null }
                else -> ext.subtitles.find { it.language == subLang }
            }

        // Subtitles
        hasExtSubtitles = ext.subtitles.isNotEmpty()

        // Fix: Fetch IMDB ID and Episode Mapping asynchronously if missing (needed for online subtitles)
        if (isOnline(this)) {
             lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                 try {
                     if (media.idIMDB == null) {
                         media.idIMDB = IdMappers.getImdbId(media.id)
                     }
                     // Prefetch episode mapping so SubtitleDialogFragment doesn't have visual label pop
                     val selectedEpisodeStr = media.anime?.selectedEpisode ?: "1"
                     val episodeNum = selectedEpisodeStr.toIntOrNull() ?: 1
                     val currentEpisode = media.anime?.episodes?.get(selectedEpisodeStr)
                     EpisodeMapper.mapEpisode(media, episodeNum, currentEpisode)
                 } catch (e: Exception) {
                     e.printStackTrace()
                 }
             }
        }

        if (hasExtSubtitles || media.idIMDB != null) {
            exoSubtitle.isVisible = true
            exoSubtitle.setOnClickListener {
                subClick()
            }
        }
        val sub: MutableList<MediaItem.SubtitleConfiguration> =
            emptyList<MediaItem.SubtitleConfiguration>().toMutableList()
        ext.subtitles.forEach { subtitle ->
            val subtitleUrl = if (!hasExtSubtitles) video!!.file.url else subtitle.file.url
            if (subtitle.type == SubtitleType.UNKNOWN) {
                runBlocking {
                    val type = SubtitleDownloader.loadSubtitleType(subtitleUrl)
                    val fileUri = (subtitleUrl).toUri()
                    sub +=
                        MediaItem.SubtitleConfiguration
                            .Builder(fileUri)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setMimeType(
                                when (type) {
                                    SubtitleType.VTT -> MimeTypes.TEXT_VTT
                                    SubtitleType.ASS -> MimeTypes.TEXT_SSA
                                    SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                                    else -> MimeTypes.TEXT_UNKNOWN
                                },
                            ).setId("69")
                            .setLanguage(subtitle.language)
                            .setLabel(subtitle.language)
                            .build()
                }
            } else {
                val subUri = subtitleUrl.toUri()
                sub +=
                    MediaItem.SubtitleConfiguration
                        .Builder(subUri)
                        .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                        .setMimeType(
                            when (subtitle.type) {
                                SubtitleType.VTT -> MimeTypes.TEXT_VTT
                                SubtitleType.ASS -> MimeTypes.TEXT_SSA
                                SubtitleType.SRT -> MimeTypes.APPLICATION_SUBRIP
                                else -> MimeTypes.TEXT_UNKNOWN
                            },
                        ).setId("69")
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.language)
                        .build()
            }
        }

        // 2. Online Subtitles (Stremio/Wyzie)
        // Auto-fetch removed for Lazy Loading.
        // Subtitles are now fetched only when the user opens the Subtitle Dialog.
        // The "Online Subtitles" button availability is handled by SubtitleDialogFragment.


        lifecycleScope.launch(Dispatchers.IO) {
            ext.onVideoPlayed(video)
        }

        val httpClient =
            okHttpClient
                .newBuilder()
                .apply {
                    ignoreAllSSLErrors()
                    followRedirects(true)
                    followSslRedirects(true)
                    // Tune for HLS: more parallel connections, explicit timeouts
                    connectionPool(
                        okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES)
                    )
                    connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                }.build()
        val httpDataSourceFactory =
            OkHttpDataSource.Factory(httpClient).apply {
                setDefaultRequestProperties(defaultHeaders)
                video?.file?.headers?.let {
                    setDefaultRequestProperties(it)
                }
            }
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        cacheFactory =
            CacheDataSource.Factory().apply {
                setCache(VideoCache.getInstance(this@ExoplayerView))
                setUpstreamDataSourceFactory(defaultDataSourceFactory)
                // Fall back to network when a cached segment cannot be read (e.g. stale/incomplete
                // data left from a previous session), so seeks past already-cached positions don't
                // hang indefinitely.
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }

        // Set up libass for ASS/SSA subtitle rendering.
        // We use OVERLAY_OPEN_GL to render subtitles in a dedicated hardware-accelerated
        // TextureView via a background HandlerThread.
        if (assHandler == null) {
            Logger.log("Libass: Creating AssHandler with OVERLAY_OPEN_GL")
            assHandler = AssHandler(
                AssRenderType.OVERLAY_OPEN_GL,
            )
            // Inject the dedicated AssSubtitleTextureView into the video frame hierarchy.
            Logger.log("Libass: Injecting AssSubtitleView into exo_content_frame")
            val contentFrame = playerView.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val assView = io.github.peerless2012.ass.media.widget.AssSubtitleView(this, assHandler!!)
            assView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentFrame?.addView(assView)
            assSubtitleView = assView
        }
        val handler = assHandler!!
        val assSubtitleParserFactory = AssSubtitleParserFactory(handler)
        val extractorsFactory = DefaultExtractorsFactory()
            .withAssMkvSupport(assSubtitleParserFactory, handler)
        assMediaSourceFactory = DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
        assMediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

        val mimeType =
            when (video?.format) {
                VideoType.M3U8 -> MimeTypes.APPLICATION_M3U8
                VideoType.DASH -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.APPLICATION_MP4
            }

        val downloadedMediaItem =
            if (ext.server.offline) {
                val titleName =
                    ext.server.name
                        .split("/")
                        .first()
                val episodeName =
                    ext.server.name
                        .split("/")
                        .last()
                downloadId = PrefManager
                    .getAnimeDownloadPreferences()
                    .getString("$titleName - $episodeName", null)
                    ?: PrefManager
                        .getAnimeDownloadPreferences()
                        .getString(ext.server.name, null)
                val exoItem =
                    if (downloadId != null) {
                        Helper
                            .downloadManager(this)
                            .downloadIndex
                            .getDownload(downloadId!!)
                            ?.request
                            ?.toMediaItem()
                    } else {
                        null
                    }
                if (exoItem != null) {
                    exoItem
                } else {
                    val directory =
                        getSubDirectory(this, MediaType.ANIME, false, titleName, episodeName)
                    if (directory != null) {
                        val files = directory.listFiles()
                        println(files)
                        val docFile =
                            directory.listFiles().firstOrNull {
                                it.name?.endsWith(".mp4") == true ||
                                        it.name?.endsWith(".mkv") == true ||
                                        it.name?.endsWith(
                                            ".${
                                                Injekt
                                                    .get<DownloadAddonManager>()
                                                    .extension
                                                    ?.extension
                                                    ?.getFileExtension()
                                                    ?.first ?: "ts"
                                            }",
                                        ) ==
                                        true
                            }
                        if (docFile != null) {
                            val uri = docFile.uri
                            val downloadedMimeType =
                                when (docFile.extension) {
                                    "mp4" -> MimeTypes.APPLICATION_MP4
                                    "mkv" -> MimeTypes.APPLICATION_MATROSKA
                                    else -> MimeTypes.APPLICATION_MP4
                                }
                            MediaItem
                                .Builder()
                                .setUri(uri)
                                .setMimeType(downloadedMimeType)
                                .build()
                        } else {
                            snackString("File not found")
                            null
                        }
                    } else {
                        snackString("Directory not found")
                        null
                    }
                }
            } else {
                null
            }

        mediaItem =
            if (downloadedMediaItem == null) {
                val builder = MediaItem.Builder().setUri(video!!.file.url).setMimeType(mimeType)
                Logger.log("url: ${video!!.file.url}")
                Logger.log("mimeType: $mimeType")
                builder.setSubtitleConfigurations(sub)
                builder.build()
            } else {
                if (sub.isNotEmpty()) {
                    val addedSubsDownloadedMediaItem = downloadedMediaItem.buildUpon()
                    val addLanguage = sub[0].buildUpon().setLanguage("en").build()
                    addedSubsDownloadedMediaItem.setSubtitleConfigurations(listOf(addLanguage))
                    episode.selectedSubtitle = 0
                    addedSubsDownloadedMediaItem.build()
                } else {
                    downloadedMediaItem
                }
            }

        val audioMediaItem = mutableListOf<MediaItem>()
        audioLanguages.clear()
        ext.audioTracks.forEach {
            var code = LanguageMapper.getLanguageCode(it.lang)
            if (code == "all") code = "un"
            audioLanguages.add(Pair(it.lang, code))
            audioMediaItem.add(
                MediaItem
                    .Builder()
                    .setUri(it.url)
                    .setMimeType(MimeTypes.AUDIO_UNKNOWN)
                    .setTag(code)
                    .build(),
            )
        }

        val audioSources =
            audioMediaItem
                .map { mediaItem ->
                    if (mediaItem.localConfiguration
                            ?.uri
                            .toString()
                            .contains(".m3u8")
                    ) {
                        HlsMediaSource.Factory(cacheFactory).createMediaSource(mediaItem)
                    } else {
                        DefaultMediaSourceFactory(cacheFactory).createMediaSource(mediaItem)
                    }
                }.toTypedArray()
        val videoMediaSource =
            assMediaSourceFactory
                .createMediaSource(mediaItem)
        mediaSource = MergingMediaSource(videoMediaSource, *audioSources)

        // Source
        exoSource.setOnClickListener {
            sourceClick()
        }

        // Quality Track
        trackSelector = DefaultTrackSelector(this)
        val parameters =
            trackSelector
                .buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setSelectUndeterminedTextLanguage(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowMultipleAdaptiveSelections(true)
                .setPreferredTextLanguage(subtitle?.language ?: Locale.getDefault().language)
                .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .setRendererDisabled(TRACK_TYPE_VIDEO, false)
                .setRendererDisabled(TRACK_TYPE_AUDIO, false)
                .setRendererDisabled(TRACK_TYPE_TEXT, false)
                .setMaxVideoSize(1, 1)
        // .setOverrideForType(TrackSelectionOverride(trackSelector, TRACK_TYPE_VIDEO))
        if (PrefManager.getVal(PrefName.SettingsPreferDub)) {
            parameters.setPreferredAudioLanguage(Locale.getDefault().language)
        }
        trackSelector.setParameters(parameters)

        if (playbackPosition != 0L && !changingServer && !PrefManager.getVal<Boolean>(PrefName.AlwaysContinue)) {
            val time =
                String.format(
                    "%02d:%02d:%02d",
                    TimeUnit.MILLISECONDS.toHours(playbackPosition),
                    TimeUnit.MILLISECONDS.toMinutes(playbackPosition) -
                            TimeUnit.HOURS.toMinutes(
                                TimeUnit.MILLISECONDS.toHours(
                                    playbackPosition,
                                ),
                            ),
                    TimeUnit.MILLISECONDS.toSeconds(playbackPosition) -
                            TimeUnit.MINUTES.toSeconds(
                                TimeUnit.MILLISECONDS.toMinutes(
                                    playbackPosition,
                                ),
                            ),
                )
            customAlertDialog().apply {
                setTitle(getString(R.string.continue_from, time))
                setCancelable(false)
                setPosButton(getString(R.string.yes)) {
                    buildExoplayer()
                }
                setNegButton(getString(R.string.no)) {
                    playbackPosition = 0L
                    buildExoplayer()
                }
                show()
            }
        } else {
            buildExoplayer()
        }
    }

    private fun buildExoplayer() {
        // Clear any leftover subtitle text from the previous episode immediately
        customSubtitleView.text = ""
        customSubtitleView.visibility = View.GONE
        exoSubtitleView.visibility = View.GONE

        // Player
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBackBuffer(1000 * 60 * 2, true)
                .setBufferDurationsMs(
                    DEFAULT_MIN_BUFFER_MS,
                    DEFAULT_MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET) // auto-size by device RAM
                .setPrioritizeTimeOverSizeThresholds(true) // prefer filling time over quality cap
                .build()

        hideSystemBars()

        val useExtensionDecoder = PrefManager.getVal<Boolean>(PrefName.UseAdditionalCodec)
        val decoder =
            if (useExtensionDecoder) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            }
        val activityContext: android.content.Context = this
        // We use NextRenderersFactory to provide FFmpeg video/audio rendering when
        // Additional Codec Support is enabled. Since we now use OVERLAY_OPEN_GL,
        // it doesn't matter that FfmpegVideoRenderer bypasses the video effects pipeline.
        val baseRenderersFactory =
            object : NextRenderersFactory(activityContext) {
                override fun buildTextRenderers(
                    context: Context,
                    output: TextOutput,
                    outputLooper: Looper,
                    extensionRendererMode: Int,
                    out: ArrayList<Renderer>
                ) {
                    out.add(TextRenderer(output, outputLooper))
                    try {
                        val clazz = Class.forName("io.github.anilbeesetti.nextlib.media3ext.renderer.NextTextRenderer")
                        val ctor = clazz.getConstructor(
                            TextOutput::class.java,
                            Looper::class.java
                        )
                        out.add(ctor.newInstance(output, outputLooper) as Renderer)
                    } catch (e: Exception) {
                    }
                }
            }.apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(decoder)
            }

        val handler = assHandler!!
        Logger.log("Libass: Calling baseRenderersFactory.withAssSupport()")
        val renderersFactory = baseRenderersFactory.withAssSupport(handler)

        exoPlayer =
            ExoPlayer
                .Builder(this, renderersFactory)
                .setMediaSourceFactory(assMediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build()
        playerView.player = exoPlayer

        // init() must be called before prepare() so it receives onTracksChanged.
        Logger.log("Libass: Calling handler.init(exoPlayer)")
        handler.init(exoPlayer)

        exoPlayer.apply {
            playWhenReady = true
            this.playbackParameters = this@ExoplayerView.playbackParameters
            setMediaSource(mediaSource)
            prepare()
            PrefManager
                .getCustomVal(
                    "${media.id}_${media.anime!!.selectedEpisode}_max",
                    Long.MAX_VALUE,
                ).takeIf { it != Long.MAX_VALUE }
                ?.let { if (it <= playbackPosition) playbackPosition = max(0, it - 5) }
            seekTo(playbackPosition)
        }

        exoPlayer.addListener(
            object : Player.Listener {
                var activeSubtitles = ArrayDeque<String>(3)
                var lastSubtitle: String? = null
                var lastPosition: Long = 0

                override fun onCues(cueGroup: CueGroup) {
                    val libassActive = assHandler?.hasTracks() == true || subtitle?.type == SubtitleType.ASS
                    if (libassActive) {
                        exoSubtitleView.visibility = View.GONE
                        customSubtitleView.visibility = View.GONE
                        customSubtitleView.text = ""
                        return
                    }

                    if (PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)) {
                        exoSubtitleView.visibility = View.GONE
                        customSubtitleView.visibility = View.VISIBLE
                        val newCues = cueGroup.cues.map { it.text.toString() ?: "" }

                        if (newCues.isEmpty()) {
                            customSubtitleView.text = ""
                            activeSubtitles.clear()
                            lastSubtitle = null
                            lastPosition = 0
                            return
                        }

                        val currentPosition = exoPlayer.currentPosition

                        if ((lastSubtitle?.length
                                ?: 0) < 20 || (lastPosition != 0L && currentPosition - lastPosition > 1500)
                        ) {
                            activeSubtitles.clear()
                        }

                        for (newCue in newCues) {
                            if (newCue !in activeSubtitles) {
                                if (activeSubtitles.size >= 2) {
                                    activeSubtitles.removeLast()
                                }
                                activeSubtitles.addFirst(newCue)
                                lastSubtitle = newCue
                                lastPosition = currentPosition
                            }
                        }

                        customSubtitleView.text = activeSubtitles.joinToString("\n")
                    } else {
                        customSubtitleView.text = ""
                        customSubtitleView.visibility = View.GONE
                        exoSubtitleView.visibility = View.VISIBLE
                    }
                }
            },
        )

        applySubtitleStyles(customSubtitleView)
        setupSubFormatting(playerView)

        try {
            val rightNow = Calendar.getInstance()
            mediaSession =
                MediaSession
                    .Builder(this, exoPlayer)
                    .setId(rightNow.timeInMillis.toString())
                    .build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        exoPlayer.addListener(this)
        exoPlayer.addAnalyticsListener(EventLogger())
        isInitialized = true

        if (!hasExtSubtitles && !PrefManager.getVal<Boolean>(PrefName.Subtitles)) {
            onSetTrackGroupOverride(dummyTrack, TRACK_TYPE_TEXT)
        }

        val isDisabled = (subtitle == null && hasExtSubtitles)
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
                .build()
    }

    private fun releasePlayer() {
        isPlayerPlaying = exoPlayer.playWhenReady
        playbackPosition = exoPlayer.currentPosition
        disappeared = false
        functionstarted = false
        exoSubtitleView.setCues(emptyList())
        exoPlayer.release()
        VideoCache.release()
        mediaSession?.release()
        RPCManager.clearPresence(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isInitialized) {
            outState.putInt(resumeWindow, exoPlayer.currentMediaItemIndex)
            outState.putLong(resumePosition, exoPlayer.currentPosition)
        }
        outState.putInt(playerFullscreen, isFullscreen)
        outState.putBoolean(playerOnPlay, isPlayerPlaying)
        super.onSaveInstanceState(outState)
    }

    private fun sourceClick() {
        changingServer = true

        media.selected!!.server = null
        PrefManager.setCustomVal(
            "${media.id}_${media.anime!!.selectedEpisode}",
            exoPlayer.currentPosition,
        )
        model.saveSelected(media.id, media.selected!!)
        model.onEpisodeClick(
            media,
            episode.number,
            this.supportFragmentManager,
            launch = false,
        )
    }

    private fun subClick() {
        Logger.log("subClick: Opening subtitle dialog")
        PrefManager.setCustomVal(
            "${media.id}_${media.anime!!.selectedEpisode}",
            exoPlayer.currentPosition,
        )
        model.saveSelected(media.id, media.selected!!)
        val dialog = SubtitleDialogFragment()
        Logger.log("subClick: Showing dialog")
        dialog.show(supportFragmentManager, "dialog")
    }

    fun requestLocalSubtitle() {
        getContent.launch(
            arrayOf(
                "application/x-subrip",
                "text/vtt",
                "text/x-ssa",
                "application/x-ass",
                "text/plain",
                "application/octet-stream"
            )
        )
    }

    /**
     * Public entry point for re-applying a cached local subtitle from its stored URI string.
     * Called from SubtitleDialogFragment when a user re-selects a "[Local]" entry.
     * Always performs a full re-add: sets the pending label so onTracksChanged will select
     * the track as soon as ExoPlayer reports it as available.
     */
    fun reApplyLocalSubtitle(uriString: String) {
        android.util.Log.d("LocalSubDebug", "reApplyLocalSubtitle called with: $uriString")
        try {
            val uri = android.net.Uri.parse(uriString)
            android.util.Log.d("LocalSubDebug", "reApplyLocalSubtitle: parsed URI=$uri, calling applyLocalSubtitle")
            applyLocalSubtitle(uri)
        } catch (e: Exception) {
            android.util.Log.e("LocalSubDebug", "reApplyLocalSubtitle: EXCEPTION - ${e.message}", e)
            e.printStackTrace()
        }
    }


    private fun applyLocalSubtitle(uri: android.net.Uri) {
        try {
            val label = "Local Subtitle"
            val contentResolver = applicationContext.contentResolver

            // --- Step 1: Determine MIME type ---
            val rawMime = contentResolver.getType(uri)
            val uriStr = uri.toString().lowercase()
            val finalMimeType = when {
                rawMime == "application/octet-stream" || rawMime == null -> when {
                    uriStr.contains(".vtt") -> MimeTypes.TEXT_VTT
                    uriStr.contains(".ssa") || uriStr.contains(".ass") -> MimeTypes.TEXT_SSA
                    uriStr.contains(".ttml") || uriStr.contains(".xml") -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                else -> rawMime
            }
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: uri=$uri mime=$finalMimeType")

            // --- Step 2: Read subtitle bytes ---
            val subtitleBytes = try {
                if (uri.scheme == "file") {
                    java.io.File(uri.path!!).readBytes()
                } else {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: Exception) {
                android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: failed to read URI $uri", e)
                null
            }

            if (subtitleBytes == null) {
                android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: subtitleBytes null, aborting")
                snackString("Failed to read subtitle file")
                return
            }

            // --- Step 3: Write to a stable cache file (file:// is reliable for SingleSampleMediaSource) ---
            val ext = when (finalMimeType) {
                MimeTypes.TEXT_VTT -> "vtt"
                MimeTypes.TEXT_SSA -> "ass"
                MimeTypes.APPLICATION_TTML -> "ttml"
                else -> "srt"
            }
            val cacheFile = File(cacheDir, "local_sub_${uri.toString().hashCode()}.$ext")

            if (finalMimeType == MimeTypes.TEXT_SSA) {
                val cleaned = stripAssPositioning(subtitleBytes.toString(Charsets.UTF_8))
                cacheFile.writeText(cleaned)
            } else {
                cacheFile.writeBytes(subtitleBytes)
            }

            val finalSubUri = android.net.Uri.fromFile(cacheFile)
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: cacheFile=$cacheFile mime=$finalMimeType")

            val stableId = "local_sub_${uri.toString().hashCode()}"

            // --- Step 4: Get existing subtitle configs from current media item ---
            val currentMediaItem = exoPlayer.currentMediaItem
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: currentMediaItem=${currentMediaItem?.mediaId ?: "NULL"}, playerState=${exoPlayer.playbackState}")
            if (currentMediaItem == null) {
                android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: currentMediaItem NULL, aborting")
                return
            }
            val existingSubtitles = currentMediaItem.localConfiguration
                ?.subtitleConfigurations?.toMutableList() ?: mutableListOf()
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: existingSubtitles ids=${existingSubtitles.map { it.id }}")

            val alreadyAdded = existingSubtitles.any { it.id == stableId }
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: alreadyAdded=$alreadyAdded")
            if (alreadyAdded) {
                android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: already present, pendingLabel + selectNow")
                pendingSubtitleLabel = label
                selectSubtitleTrack("", label)
                return
            }

            // --- Step 5: Build SubtitleConfiguration ---
            // KEY FIX: Do NOT use SELECTION_FLAG_DEFAULT — it causes ExoPlayer to silently
            // merge/drop the track alongside HLS manifest subtitles.
            // DO add setLanguage("und") — matches the working online subtitle config.
            val subConfig = MediaItem.SubtitleConfiguration.Builder(finalSubUri)
                .setMimeType(finalMimeType)
                .setLanguage("und")
                .setLabel(label)
                .setId(stableId)
                .build()

            existingSubtitles.add(subConfig)

            // --- Step 6: Save to ViewModel cache ---
            val mediaId = media.id
            val episodeId = media.anime?.selectedEpisode ?: "1"
            val newLocalSub = Subtitle(
                language = "[Local] ${uri.lastPathSegment ?: "Custom"}",
                url = uri.toString()
            )
            model.saveLocalSubtitle("$mediaId-$episodeId", newLocalSub)
            PrefManager.setCustomVal("subLang_$mediaId", newLocalSub.language)

            // --- Step 7: Apply via setMediaItem — same path as the working online subtitle ---
            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(existingSubtitles)
                .build()

            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: pendingLabel='$label', setMediaItem+prepare, uri=$finalSubUri")
            pendingSubtitleLabel = label
            val currentPos = exoPlayer.currentPosition
            exoPlayer.setMediaItem(newMediaItem, currentPos)
            exoPlayer.prepare()
            android.util.Log.d("LocalSubDebug", "applyLocalSubtitle: prepare() called")

        } catch (e: Exception) {
            android.util.Log.e("LocalSubDebug", "applyLocalSubtitle: EXCEPTION ${e.message}", e)
            snackString("Failed to load subtitle: ${e.message}")
        }
    }

    private fun stripAssPositioning(assContent: String): String {
        android.util.Log.d("ExoplayerView", "stripAssPositioning: Stripping positioning from ASS subtitle")

        // Split into lines
        val lines = assContent.lines().toMutableList()
        var inEvents = false
        var inStyles = false
        val styleFormatMap = mutableMapOf<String, Int>()

        for (i in lines.indices) {
            val line = lines[i]
            val trimmedLine = line.trim()

            // Track sections
            if (trimmedLine.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                inStyles = false
                continue
            } else if (trimmedLine.equals("[V4+ Styles]", ignoreCase = true) ||
                       trimmedLine.equals("[V4 Styles]", ignoreCase = true)) {
                inStyles = true
                inEvents = false
                continue
            } else if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]")) {
                inEvents = false
                inStyles = false
                continue
            }

            // Process Style section
            if (inStyles) {
                if (trimmedLine.startsWith("Format:", ignoreCase = true)) {
                    // Parse format definition: Format: Name, Fontname, ...
                    val parts = trimmedLine.substringAfter(":").split(",")
                    styleFormatMap.clear()
                    parts.forEachIndexed { index, name ->
                        styleFormatMap[name.trim().lowercase()] = index
                    }
                } else if (trimmedLine.startsWith("Style:", ignoreCase = true) && styleFormatMap.isNotEmpty()) {
                    // Start after "Style:"
                    val styleContent = trimmedLine.substringAfter("Style:")
                    val parts = styleContent.split(",").toMutableList()

                    // Fix Alignment -> 2 (Bottom Center)
                    val alignIdx = styleFormatMap["alignment"]
                    if (alignIdx != null && alignIdx < parts.size) {
                        parts[alignIdx] = "2"
                    }

                    // Fix MarginV -> 0 (Vertical Margin)
                    val marginVIdx = styleFormatMap["marginv"]
                    if (marginVIdx != null && marginVIdx < parts.size) {
                        parts[marginVIdx] = "0" // 0 margin for absolute bottom positioning
                    }

                    lines[i] = "Style: ${parts.joinToString(",")}"
                }
            }

            // Process dialogue lines in [Events] section
            if (inEvents && (trimmedLine.startsWith("Dialogue:", ignoreCase = true) ||
                             trimmedLine.startsWith("Comment:", ignoreCase = true))) {
                var modifiedLine = line

                // Remove \pos(x,y) - positioning
                modifiedLine = modifiedLine.replace(Regex("\\\\pos\\([^)]*\\)"), "")

                // Remove \move(x1,y1,x2,y2) - movement
                modifiedLine = modifiedLine.replace(Regex("\\\\move\\([^)]*\\)"), "")

                // Remove \an alignment tags (don't replace, just remove to use Style alignment)
                modifiedLine = modifiedLine.replace(Regex("\\\\an[1-9]"), "")

                // Remove \a alignment tags (old style)
                modifiedLine = modifiedLine.replace(Regex("\\\\a[1-9]+"), "")

                // Remove \org (rotation origin)
                modifiedLine = modifiedLine.replace(Regex("\\\\org\\([^)]*\\)"), "")

                lines[i] = modifiedLine
            }
        }

        val result = lines.joinToString("\n")
        android.util.Log.d("ExoplayerView", "stripAssPositioning: Done")
        return result
    }

    /**
     * Clears the online and local subtitle caches for the given episodeId.
     * Removes ViewModel in-memory caches and deletes the physical subtitle files
     * from cacheDir. Source subtitles (from the extractor) are unaffected.
     * Called on episode change and player exit.
     */
    private fun clearTransientSubtitleCache(episodeId: String) {
        model.clearFetchedSubtitles(episodeId)
        model.clearLocalSubtitles(episodeId)
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ExoplayerView", "clearTransientSubtitleCache: error deleting files - ${e.message}")
        }
    }

    fun applyOnlineSubtitle(subtitle: ani.dantotsu.connections.subtitles.StremioSub) {
        android.util.Log.d("ExoplayerView", "=== applyOnlineSubtitle CALLED ===")
        android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: lang=${subtitle.lang}, url=${subtitle.url}")

        // Download subtitle content first, then apply
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Downloading subtitle from ${subtitle.url}")

                // Download subtitle content
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url(subtitle.url)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        android.util.Log.e("ExoplayerView", "applyOnlineSubtitle: Download failed with code ${response.code}")
                        snackString("Failed to download subtitle: HTTP ${response.code}")
                    }
                    return@launch
                }

                val subtitleContent = response.body?.string()
                if (subtitleContent.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        android.util.Log.e("ExoplayerView", "applyOnlineSubtitle: Subtitle content is empty")
                        snackString("Subtitle file is empty")
                    }
                    return@launch
                }

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Downloaded ${subtitleContent.length} bytes")

                // Detect format from content
                val detectedFormat = when {
                    subtitleContent.trimStart().startsWith("WEBVTT") -> "VTT"
                    subtitleContent.contains("[Script Info]") || subtitleContent.contains("\\[Events\\]") -> "ASS"
                    subtitleContent.contains("<tt ") || subtitleContent.contains("<tt>") -> "TTML"
                    else -> "SRT"
                }

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Detected format: $detectedFormat")

                // Strip positioning from ASS files
                val cleanedContent = if (detectedFormat == "ASS") {
                    stripAssPositioning(subtitleContent)
                } else {
                    subtitleContent
                }

                // Use appropriate MIME type
                val mimeType = when (detectedFormat) {
                    "VTT" -> MimeTypes.TEXT_VTT
                    "ASS" -> MimeTypes.TEXT_SSA
                    "TTML" -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }

                val extension = when (detectedFormat) {
                    "VTT" -> "vtt"
                    "ASS" -> "ass"
                    "TTML" -> "ttml"
                    else -> "srt"
                }

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Using MIME type: $mimeType, extension: $extension")

                val cacheDir = this@ExoplayerView.cacheDir
                val subtitleFile = File(cacheDir, "online_subtitle_${subtitle.id}.$extension")
                subtitleFile.writeText(cleanedContent)

                android.util.Log.d("ExoplayerView", "applyOnlineSubtitle: Saved to ${subtitleFile.absolutePath}")

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    applySubtitleFromFile(subtitleFile, subtitle.lang, mimeType)
                }

            } catch (e: Exception) {
                android.util.Log.e("ExoplayerView", "applyOnlineSubtitle: ERROR - ${e.message}", e)
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}")
                }
            }
        }
    }

    private fun applySubtitleFromFile(file: File, lang: String, mimeType: String) {
        try {
            val label = "Online: $lang"
            val subUri = android.net.Uri.fromFile(file)

            android.util.Log.d("ExoplayerView", "applySubtitleFromFile: URI=$subUri, MIME=$mimeType, label=$label")

            val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mimeType)
                .setLanguage(lang)
                .setLabel(label)
                .setId(file.name)
                .build()

            val currentMediaItem = exoPlayer.currentMediaItem ?: return
            val existingSubtitles = currentMediaItem.localConfiguration?.subtitleConfigurations?.toMutableList() ?: mutableListOf()

            val alreadyExists = existingSubtitles.any { it.id == file.name }
            if (alreadyExists) {
                android.util.Log.d("ExoplayerView", "applySubtitleFromFile: Subtitle already exists, selecting via pendingLabel")
                // Even though track already exists in the media item, we may need
                // to wait for onTracksChanged to fire to reliably select it.
                pendingSubtitleLabel = label
                // If tracks are already reported by ExoPlayer, try immediately too.
                selectSubtitleTrack(lang, label)
                return
            }

            existingSubtitles.add(subConfig)
            android.util.Log.d("ExoplayerView", "applySubtitleFromFile: Added subtitle, total: ${existingSubtitles.size}")

            val newMediaItem = currentMediaItem.buildUpon()
                .setSubtitleConfigurations(existingSubtitles)
                .build()

            // Register label to select once onTracksChanged fires after prepare()
            pendingSubtitleLabel = label

            val currentPos = exoPlayer.currentPosition
            exoPlayer.setMediaItem(newMediaItem, currentPos)
            exoPlayer.prepare()

        } catch (e: Exception) {
            android.util.Log.e("ExoplayerView", "applySubtitleFromFile: ERROR - ${e.message}", e)
            snackString("Failed to apply subtitle: ${e.message}")
        }
    }


    // Map ISO 639-2 codes (from Stremio API) to language names
    private fun mapLanguageCode(isoCode: String): String = when (isoCode.lowercase()) {
        "eng" -> "english"
        "spa" -> "spanish"
        "fra" -> "french"
        "deu" -> "german"
        "ita" -> "italian"
        "por" -> "portuguese"
        "rus" -> "russian"
        "jpn" -> "japanese"
        "zho", "chi" -> "chinese"
        "ara" -> "arabic"
        "hin" -> "hindi"
        "kor" -> "korean"
        "pol" -> "polish"
        "tur" -> "turkish"
        "hun" -> "hungarian"
        "ron" -> "romanian"
        "ell" -> "greek"
        "cze" -> "czech"
        "swe" -> "swedish"
        "dan" -> "danish"
        "fin" -> "finnish"
        "nor" -> "norwegian"
        "nld" -> "dutch"
        "tha" -> "thai"
        "vie" -> "vietnamese"
        "ind" -> "indonesian"
        "ukr" -> "ukrainian"
        "heb" -> "hebrew"
        "bul" -> "bulgarian"
        "hrv" -> "croatian"
        "slk" -> "slovak"
        "slv" -> "slovenian"
        else -> isoCode
    }

    private fun selectSubtitleTrack(langCode: String, targetLabel: String? = null) {
        android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Looking for lang=$langCode, targetLabel=$targetLabel")

        val mappedLang = mapLanguageCode(langCode)
        android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Mapped '$langCode' to '$mappedLang'")

        try {
            val tracks = exoPlayer.currentTracks
            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Total track groups: ${tracks.groups.size}")

            for (groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[groupIndex]

                if (group.type == TRACK_TYPE_TEXT) {
                    android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Found TEXT group at index $groupIndex with ${group.length} tracks")

                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val trackLang = format.language?.lowercase() ?: ""
                        val trackLabel = format.label ?: ""
                        android.util.Log.d("ExoplayerView", "selectSubtitleTrack: Track $trackIndex - lang=$trackLang, label=$trackLabel")

                        // PRIORITY 1: Match by specific Label (e.g., "Online: eng")
                        if (targetLabel != null && trackLabel == targetLabel) {
                            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: FOUND matching track by label! Selecting index $trackIndex")
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            snackString("Subtitle loaded: $trackLabel")
                            return
                        }

                        // PRIORITY 2: Fallback to matching language code if no label provided
                        if (targetLabel == null && (trackLang == mappedLang || trackLang == langCode || trackLang.startsWith(langCode) || trackLang.startsWith(mappedLang))) {
                            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: FOUND matching track by language! Selecting index $trackIndex")
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            snackString("Subtitle loaded: ${mappedLang.replaceFirstChar { it.uppercase() }}")
                            return
                        }
                    }
                }
            }
            android.util.Log.d("ExoplayerView", "selectSubtitleTrack: No matching track found for lang=$langCode, targetLabel=$targetLabel")
        } catch (e: Exception) {
            android.util.Log.e("ExoplayerView", "selectSubtitleTrack: ERROR - ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        if (isInitialized) {
            if (castPlayer?.isPlaying == false) {
                playerView.player?.pause()
            }
            if (exoPlayer.currentPosition > 5000) {
                PrefManager.setCustomVal(
                    "${media.id}_${media.anime!!.selectedEpisode}",
                    exoPlayer.currentPosition,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        hideSystemBars()
        if (isInitialized) {
            playerView.onResume()
            playerView.useController = true
        }
    }

    override fun onStop() {
        if (castPlayer?.isPlaying == false) {
            playerView.player?.pause()
        }
        super.onStop()
    }

    private var wasPlaying = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (PrefManager.getVal(PrefName.FocusPause) && !epChanging) {
            if (isInitialized && !hasFocus) wasPlaying = exoPlayer.playWhenReady
            if (hasFocus) {
                if (isInitialized && wasPlaying) exoPlayer.play()
            } else {
                if (isInitialized) exoPlayer.pause()
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            (exoPlay.drawable as Animatable?)?.start()
            if (!this.isDestroyed) {
                Glide
                    .with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            discordRPC()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            discordRPC()
            // Proactively ensure playback resumes after seek when the player was playing.
            // Seeking to an unbuffered position transitions to STATE_BUFFERING; setting
            // playWhenReady = true here (before buffering begins) ensures ExoPlayer will
            // auto-play as soon as STATE_READY is reached, preventing the player from
            // silently staying paused after the buffer fills.
            if (isPlayerPlaying) {
                exoPlayer.play()
            }
        }
    }

    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()

        PrefManager.setCustomVal(
            "${media.id}_${media.anime!!.selectedEpisode}_max",
            exoPlayer.duration,
        )

        val format = exoPlayer.videoFormat ?: return
        var height = format.height
        var width = format.width
        val rotation = format.rotationDegrees

        if (rotation == 90 || rotation == 270) {
            val temp = width
            width = height
            height = temp
        }

        aspectRatio = Rational(width, height)

        videoInfo.text = getString(R.string.video_quality, height)

        if (exoPlayer.duration < playbackPosition) {
            exoPlayer.seekTo(0)
        }

        // if playbackPosition is within 92% of the episode length, reset it to 0
        if (playbackPosition > exoPlayer.duration.toFloat() * 0.92) {
            playbackPosition = 0
            exoPlayer.seekTo(0)
        }

        if (!isTimeStampsLoaded && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            val dur = exoPlayer.duration
            lifecycleScope.launch(Dispatchers.IO) {
                model.loadTimeStamps(
                    media.idMAL,
                    media.anime
                        ?.selectedEpisode
                        ?.trim()
                        ?.toIntOrNull(),
                    dur / 1000,
                    PrefManager.getVal(PrefName.UseProxyForTimeStamps),
                )
            }
        }
    }

    // Link Preloading
    private var preloading = false

    private fun updateProgress() {
        if (isInitialized) {
            if (exoPlayer.currentPosition.toFloat() / exoPlayer.duration >
                PrefManager.getVal<Float>(
                    PrefName.WatchPercentage,
                )
            ) {
                preloading = true
                nextEpisode(false) { i ->
                    val ep = episodes[episodeArr[currentEpisodeIndex + i]] ?: return@nextEpisode
                    val selected = media.selected ?: return@nextEpisode
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (media.selected!!.server != null) {
                            model.loadEpisodeSingleVideo(ep, selected, false)
                        } else {
                            model.loadEpisodeVideos(ep, selected.sourceIndex, false)
                        }
                    }
                }
            }
        }
        if (!preloading) {
            handler.postDelayed({
                updateProgress()
            }, 2500)
        }
    }

    // TimeStamp Updating
    private var currentTimeStamp: AniSkip.Stamp? = null
    private var skippedTimeStamps: MutableList<AniSkip.Stamp> = mutableListOf()

    private fun updateTimeStamp() {
        if (isInitialized) {
            val playerCurrentTime = exoPlayer.currentPosition / 1000
            currentTimeStamp =
                model.timeStamps.value?.find { timestamp ->
                    timestamp.interval.startTime < playerCurrentTime &&
                            playerCurrentTime < (timestamp.interval.endTime - 1)
                }

            val new = currentTimeStamp
            timeStampText.text =
                if (new != null) {
                    fun disappearSkip() {
                        functionstarted = true
                        skipTimeButton.visibility = View.VISIBLE
                        exoSkip.visibility = View.GONE
                        skipTimeText.text = new.skipType.getType()
                        skipTimeButton.setOnClickListener {
                            exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                        }
                        var timer: CountDownTimer? = null

                        fun cancelTimer() {
                            timer?.cancel()
                            timer = null
                            return
                        }
                        timer =
                            object : CountDownTimer(5000, 1000) {
                                override fun onTick(millisUntilFinished: Long) {
                                    if (new == null) {
                                        skipTimeButton.visibility = View.GONE
                                        exoSkip.isVisible =
                                            PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                                        disappeared = false
                                        functionstarted = false
                                        cancelTimer()
                                    }
                                }

                                override fun onFinish() {
                                    skipTimeButton.visibility = View.GONE
                                    exoSkip.isVisible =
                                        PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                                    disappeared = true
                                    functionstarted = false
                                    cancelTimer()
                                }
                            }
                        timer?.start()
                    }
                    if (PrefManager.getVal(PrefName.ShowTimeStampButton)) {
                        if (!functionstarted && !disappeared && PrefManager.getVal(PrefName.AutoHideTimeStamps)) {
                            disappearSkip()
                        } else if (!PrefManager.getVal<Boolean>(PrefName.AutoHideTimeStamps)) {
                            skipTimeButton.visibility = View.VISIBLE
                            exoSkip.visibility = View.GONE
                            skipTimeText.text = new.skipType.getType()
                            skipTimeButton.setOnClickListener {
                                exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                            }
                        }
                    }
                    if (PrefManager.getVal(PrefName.AutoSkipOPED) &&
                        (new.skipType == "op" || new.skipType == "ed") &&
                        !skippedTimeStamps.contains(new)
                    ) {
                        exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                        skippedTimeStamps.add(new)
                    }
                    if (PrefManager.getVal(PrefName.AutoSkipRecap) &&
                        new.skipType == "recap" &&
                        !skippedTimeStamps.contains(
                            new,
                        )
                    ) {
                        exoPlayer.seekTo((new.interval.endTime * 1000).toLong())
                        skippedTimeStamps.add(new)
                    }
                    new.skipType.getType()
                } else {
                    disappeared = false
                    functionstarted = false
                    skipTimeButton.visibility = View.GONE
                    exoSkip.isVisible = PrefManager.getVal<Int>(PrefName.SkipTime) > 0
                    ""
                }
        }
        handler.postDelayed({
            updateTimeStamp()
        }, 500)
    }

    fun onSetTrackGroupOverride(
        trackGroup: Tracks.Group,
        type: @C.TrackType Int,
        index: Int = 0,
    ) {
        val isDisabled = trackGroup.getTrackFormat(0).language == "none"
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(TRACK_TYPE_TEXT, isDisabled)
                .setOverrideForType(
                    TrackSelectionOverride(trackGroup.mediaTrackGroup, index),
                ).build()
        if (type == TRACK_TYPE_TEXT) {
            setupSubFormatting(playerView)
            applySubtitleStyles(customSubtitleView)
        }
        playerView.subtitleView?.alpha =
            when (isDisabled) {
                false -> PrefManager.getVal(PrefName.SubAlpha)
                true -> 0f
            }
    }

    private val dummyTrack =
        Tracks.Group(
            TrackGroup("Dummy Track", Format.Builder().apply { setLanguage("none") }.build()),
            true,
            intArrayOf(1),
            booleanArrayOf(false),
        )

    override fun onTracksChanged(tracks: Tracks) {
        // Consume any pending subtitle label set by applyLocalSubtitle / applySubtitleFromFile.
        // This fires reliably once ExoPlayer has parsed all tracks after setMediaItem+prepare.
        val pendingLabel = pendingSubtitleLabel
        android.util.Log.d("LocalSubDebug", "onTracksChanged: pendingLabel=$pendingLabel, totalGroups=${tracks.groups.size}")
        if (pendingLabel != null) {
            var matched = false
            tracks.groups.forEachIndexed { groupIndex, group ->
                android.util.Log.d("LocalSubDebug", "onTracksChanged: group[$groupIndex] type=${group.type}, length=${group.length}")
                if (group.type == TRACK_TYPE_TEXT) {
                    for (trackIndex in 0 until group.length) {
                        val trackLabel = group.getTrackFormat(trackIndex).label
                        android.util.Log.d("LocalSubDebug", "onTracksChanged: TEXT track[$trackIndex] label='$trackLabel', isSupported=${group.isTrackSupported(trackIndex, true)}")
                        if (trackLabel == pendingLabel) {
                            android.util.Log.d("LocalSubDebug", "onTracksChanged: MATCH FOUND for '$pendingLabel' at group=$groupIndex track=$trackIndex, selecting")
                            pendingSubtitleLabel = null
                            matched = true
                            onSetTrackGroupOverride(group, TRACK_TYPE_TEXT, trackIndex)
                            snackString("Subtitle loaded: $pendingLabel")
                            break
                        }
                    }
                }
                if (matched) return@forEachIndexed
            }
            if (!matched) {
                android.util.Log.w("LocalSubDebug", "onTracksChanged: NO MATCH found for '$pendingLabel' — will retry on next onTracksChanged")
            }
        }

        val audioTracks: ArrayList<Tracks.Group> = arrayListOf()
        val subTracks: ArrayList<Tracks.Group> = arrayListOf(dummyTrack)
        tracks.groups.forEach {
            println(
                "Track__: $it\nTrack__: ${it.length}\nTrack__: ${it.isSelected}\n" +
                        "Track__: ${it.type}\nTrack__: ${it.mediaTrackGroup.id}",
            )
            when (it.type) {
                TRACK_TYPE_AUDIO -> {
                    if (it.isSupported(true)) audioTracks.add(it)
                }

                TRACK_TYPE_TEXT -> {
                    if (!hasExtSubtitles) {
                        if (it.isSupported(true)) subTracks.add(it)
                        return@forEach
                    }
                }
            }
        }
        exoAudioTrack.isVisible = audioTracks.size > 1
        exoAudioTrack.setOnClickListener {
            TrackGroupDialogFragment(this, audioTracks, TRACK_TYPE_AUDIO, audioLanguages)
                .show(supportFragmentManager, "dialog")
        }
        if (!hasExtSubtitles) {
            exoSubtitle.isVisible = subTracks.size > 1
            exoSubtitle.setOnClickListener {
                TrackGroupDialogFragment(this, subTracks, TRACK_TYPE_TEXT)
                    .show(supportFragmentManager, "dialog")
            }
        }
    }

    private val onChangeSettings =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _: ActivityResult ->
            if (!hasExtSubtitles) {
                exoPlayer.currentTracks.groups.forEach { trackGroup ->
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
            if (isInitialized) exoPlayer.play()
        }

    override fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                -> {
                toast("Source Exception : ${error.message}")
                isPlayerPlaying = true
                sourceClick()
            }

            else -> {
                toast("Player Error ${error.errorCode} (${error.errorCodeName}) : ${error.message}")
                Injekt.get<CrashlyticsInterface>().logException(error)
            }
        }
    }

    private var isBuffering = true

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == ExoPlayer.STATE_READY) {
            exoPlayer.play()
            if (episodeLength == 0f) {
                episodeLength = exoPlayer.duration.toFloat()
                discordRPC()
            }
        }
        isBuffering = playbackState == Player.STATE_BUFFERING
        if (playbackState == Player.STATE_ENDED && PrefManager.getVal(PrefName.AutoPlay)) {
            if (interacted) {
                exoNext.performClick()
            } else {
                toast(getString(R.string.autoplay_cancelled))
            }
        }
        super.onPlaybackStateChanged(playbackState)
    }

    private fun updateAniProgress() {
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        val episodeEnd =
            exoPlayer.currentPosition / episodeLength >
                    PrefManager.getVal<Float>(
                        PrefName.WatchPercentage,
                    )
        val episode0 = currentEpisodeIndex == 0 && PrefManager.getVal(PrefName.ChapterZeroPlayer)
        if (!incognito && (episodeEnd || episode0) && Anilist.userid != null
        ) {
            if (PrefManager.getCustomVal(
                    "${media.id}_save_progress",
                    true,
                ) &&
                (if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true)
            ) {
                if (episode0 && !episodeEnd) {
                    updateProgress(media, "0")
                } else {
                    media.anime!!.selectedEpisode?.apply {
                        updateProgress(media, this)
                    }
                }
            }
        }
        maybeHandleSubscriptionAfterEpisodeCompletion(episodeEnd, incognito)
    }

    private var lastSubscriptionPromptEpisode: String? = null

    private fun maybeHandleSubscriptionAfterEpisodeCompletion(episodeEnd: Boolean, incognito: Boolean) {
        if (!episodeEnd || incognito) return
        val currentEpisode = media.anime?.selectedEpisode ?: return
        if (lastSubscriptionPromptEpisode == currentEpisode) return
        lastSubscriptionPromptEpisode = currentEpisode

        val subscriptionsEnabled = PrefManager.getVal(PrefName.SubscriptionCheckingNotifications)
        if (!subscriptionsEnabled) return

        val isCompleted = isAnimeCompleted()
        val alreadySubscribed = SubscriptionHelper.getSubscriptions().containsKey(media.id)
        if (isCompleted) {
            if (alreadySubscribed) {
                SubscriptionHelper.saveSubscription(media, false)
                toast(getString(R.string.unsubscribed_notification))
            }
            return
        }
        if (alreadySubscribed) return

        customAlertDialog().apply {
            setTitle(getString(R.string.subscribe_prompt_title))
            setMessage(getString(R.string.subscribe_prompt_anime_message, media.userPreferredName))
            setPosButton(R.string.yes) {
                SubscriptionHelper.saveSubscription(media, true)
                toast(getString(R.string.subscribed_notification, getString(R.string.anime)))
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun isAnimeCompleted(): Boolean {
        if (media.userStatus == "COMPLETED") return true
        val totalEpisodes = media.anime?.totalEpisodes ?: return false
        val currentEpisodeNumber = media.anime?.selectedEpisode?.toFloatOrNull() ?: return false
        return currentEpisodeNumber >= totalEpisodes
    }

    private fun nextEpisode(
        toast: Boolean = true,
        runnable: ((Int) -> Unit),
    ) {
        var isFiller = true
        var i = 1
        while (isFiller) {
            if (episodeArr.size > currentEpisodeIndex + i) {
                isFiller =
                    if (PrefManager.getVal(PrefName.AutoSkipFiller)) {
                        episodes[episodeArr[currentEpisodeIndex + i]]?.filler
                            ?: false
                    } else {
                        false
                    }
                if (!isFiller) runnable.invoke(i)
                i++
            } else {
                if (toast) {
                    toast(getString(R.string.no_next_episode))
                }
                isFiller = false
            }
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

        CoroutineScope(Dispatchers.IO).launch {
            tryWithSuspend(true) {
                extractor?.onVideoStopped(video)
            }
        }

        if (isInitialized) {
            updateAniProgress()
            // Clear transient subtitle caches (online + local) on player exit
            val episodeId = "${media.id}-${media.anime?.selectedEpisode ?: ""}"
            clearTransientSubtitleCache(episodeId)
            
            disappeared = false
            functionstarted = false
            releasePlayer()
        }

        super.onDestroy()
        finishAndRemoveTask()
    }

    // Cast
    private fun cast() {
        val videoURL = video?.file?.url ?: return
        val subtitleUrl = if (!hasExtSubtitles || subtitle == null) video!!.file.url else subtitle!!.file.url
        val shareVideo = Intent(Intent.ACTION_VIEW)
        shareVideo.setDataAndType(videoURL.toUri(), "video/*")
        shareVideo.setPackage("com.instantbits.cast.webvideo")
        if (subtitle != null) shareVideo.putExtra("subtitle", subtitleUrl)
        shareVideo.putExtra(
            "title",
            media.userPreferredName + " : Ep " + episodeTitleArr[currentEpisodeIndex],
        )
        shareVideo.putExtra("poster", episode.thumb?.url ?: media.cover)
        val headers = Bundle()
        defaultHeaders.forEach {
            headers.putString(it.key, it.value)
        }
        video?.file?.headers?.forEach {
            headers.putString(it.key, it.value)
        }
        shareVideo.putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
        shareVideo.putExtra("secure_uri", true)
        try {
            startActivity(shareVideo)
        } catch (ex: ActivityNotFoundException) {
            val intent = Intent(Intent.ACTION_VIEW)
            val uriString = "market://details?id=com.instantbits.cast.webvideo"
            intent.data = uriString.toUri()
            startActivity(intent)
        }
    }

    // Enter PiP Mode
    @Suppress("DEPRECATION")
    private fun enterPipMode() {
        wasPlaying = isPlayerPlaying
        if (!pipEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(
                    PictureInPictureParams
                        .Builder()
                        .setAspectRatio(aspectRatio)
                        .build(),
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                enterPictureInPictureMode()
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun onPiPChanged(isInPictureInPictureMode: Boolean) {
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientationListener?.disable()
        } else {
            orientationListener?.enable()
        }
        if (isInitialized) {
            PrefManager.setCustomVal(
                "${media.id}_${episode.number}",
                exoPlayer.currentPosition,
            )
            if (wasPlaying) exoPlayer.play()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureUiStateChanged(pipState)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        onPiPChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private val keyMap: MutableMap<Int, (() -> Unit)?> =
        mutableMapOf(
            KEYCODE_DPAD_RIGHT to null,
            KEYCODE_DPAD_LEFT to null,
            KEYCODE_SPACE to { exoPlay.performClick() },
            KEYCODE_N to { exoNext.performClick() },
            KEYCODE_B to { exoPrev.performClick() },
        )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (keyMap.containsKey(event.keyCode)) {
            (event.action == ACTION_UP).also {
                if (isInitialized && it) keyMap[event.keyCode]?.invoke()
            }
        } else {
            super.dispatchKeyEvent(event)
        }

    private fun startCastPlayer() {
        if (!isCastApiAvailable) {
            snackString("Cast API not available")
            return
        }
        // make sure mediaItem is initialized and castPlayer is not null
        if (!this::mediaItem.isInitialized || castPlayer == null) return
        castPlayer?.setMediaItem(mediaItem)
        castPlayer?.prepare()
        playerView.player = castPlayer
        exoPlayer.stop()
        castPlayer?.addListener(
            object : Player.Listener {
                // if the player is paused changed, we want to update the UI
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    super.onPlayWhenReadyChanged(playWhenReady, reason)
                    if (playWhenReady) {
                        (exoPlay.drawable as Animatable?)?.start()
                        Glide
                            .with(this@ExoplayerView)
                            .load(R.drawable.anim_play_to_pause)
                            .into(exoPlay)
                    } else {
                        (exoPlay.drawable as Animatable?)?.start()
                        Glide
                            .with(this@ExoplayerView)
                            .load(R.drawable.anim_pause_to_play)
                            .into(exoPlay)
                    }
                }
            },
        )
    }

    private fun startExoPlayer() {
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        playerView.player = exoPlayer
        castPlayer?.stop()
    }

    override fun onCastSessionAvailable() {
        if (isCastApiAvailable && !this.isDestroyed) {
            startCastPlayer()
        }
    }

    override fun onCastSessionUnavailable() {
        startExoPlayer()
    }

    @SuppressLint("ViewConstructor")
    class ExtendedTimeBar(
        context: Context,
        attrs: AttributeSet?,
    ) : DefaultTimeBar(context, attrs) {
        private var enabled = false
        private var forceDisabled = false

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
            super.setEnabled(!forceDisabled && this.enabled)
        }

        fun setForceDisabled(forceDisabled: Boolean) {
            this.forceDisabled = forceDisabled
            isEnabled = enabled
        }
    }
}

class CustomCastButton : MediaRouteButton {
    private var castCallback: (() -> Unit)? = null

    fun setCastCallback(castCallback: () -> Unit) {
        this.castCallback = castCallback
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr,
    )

    override fun performClick(): Boolean =
        if (PrefManager.getVal(PrefName.UseInternalCast)) {
            super.performClick()
        } else {
            castCallback?.let { it() }
            true
        }
}
