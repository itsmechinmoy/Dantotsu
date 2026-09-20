package ani.dantotsu.media.novel.novelreader

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.TextView
import ani.dantotsu.getThemeColor
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ActivityNovelReaderBinding
import ani.dantotsu.hideSystemBars
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.CurrentNovelReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.tryWith
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.epub.css.FontStyle as ReadiumFontStyle
import org.readium.r2.navigator.epub.css.FontWeight as ReadiumFontWeight
import android.speech.tts.TextToSpeech
import java.util.Locale
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.properties.Delegates

data class NovelReaderTheme(
    val name: String,
    val lightFg: Int,
    val lightBg: Int,
    val darkFg: Int,
    val darkBg: Int
)

@OptIn(ExperimentalReadiumApi::class)
class NovelReaderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNovelReaderBinding
    private val scope = lifecycleScope

    private var notchHeight: Int? = null

    var loaded = false
    val autoScroll = NovelReaderAutoScroll()
    lateinit var readerOverlay: NovelReaderOverlayManager

    private var currentPublication: Publication? = null
    private var sanitizedBookId: String = "unknown_book"
    private var visualNavigator: VisualNavigator? = null
    private var epubNavigator: EpubNavigatorFragment? = null
    private var pdfNavigator: PdfiumNavigatorFragment? = null

    // TTS
    private var ttsNavigator: AndroidTtsNavigator? = null
    private var ttsLocationJob: Job? = null
    private var ttsPlaybackJob: Job? = null
    private var ttsSpeedIndex = 1
    private val ttsSpeeds = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

    private var snippetTts: TextToSpeech? = null
    private var locatorJob: Job? = null
    private var isSliderDragging = false
    private var isNavigatingFromSlider = false
    private var wasAutoScrolling = false
    private var totalPositionsCount: Int = 0
    private var latestLocator: Locator? = null
    private var endOfChapterFrames = 0
    private var chapterTransitionCooldown = 0
    private var isTransitioningChapter = false
    private var chapterRanges: List<Pair<Double, Double>> = emptyList()
    private var scrollFrameCount = 0
    private var lastObservedWebView: WebView? = null
    private var applySettingsJob: Job? = null

    val themes = arrayListOf(
        NovelReaderTheme(
            name = "Default",
            lightFg = AndroidColor.parseColor("#000000"),
            lightBg = AndroidColor.parseColor("#FFFFFF"),
            darkFg = AndroidColor.parseColor("#FFFFFF"),
            darkBg = AndroidColor.parseColor("#121212")
        ),
        NovelReaderTheme(
            name = "Forest",
            lightFg = AndroidColor.parseColor("#000000"),
            lightBg = AndroidColor.parseColor("#E7F6E7"),
            darkFg = AndroidColor.parseColor("#FFFFFF"),
            darkBg = AndroidColor.parseColor("#084D08")
        ),
        NovelReaderTheme(
            name = "Ocean",
            lightFg = AndroidColor.parseColor("#000000"),
            lightBg = AndroidColor.parseColor("#E4F0F9"),
            darkFg = AndroidColor.parseColor("#FFFFFF"),
            darkBg = AndroidColor.parseColor("#0A2E3E")
        ),
        NovelReaderTheme(
            name = "Sunset",
            lightFg = AndroidColor.parseColor("#000000"),
            lightBg = AndroidColor.parseColor("#FDEDE6"),
            darkFg = AndroidColor.parseColor("#FFFFFF"),
            darkBg = AndroidColor.parseColor("#441517")
        ),
        NovelReaderTheme(
            name = "Desert",
            lightFg = AndroidColor.parseColor("#000000"),
            lightBg = AndroidColor.parseColor("#FDF5E6"),
            darkFg = AndroidColor.parseColor("#FFFFFF"),
            darkBg = AndroidColor.parseColor("#523B19")
        ),
        NovelReaderTheme(
            name = "Galaxy",
            lightFg = AndroidColor.parseColor("#000000"),
            lightBg = AndroidColor.parseColor("#F2F2F2"),
            darkFg = AndroidColor.parseColor("#FFFFFF"),
            darkBg = AndroidColor.parseColor("#000000")
        )
    )

    var defaultSettings = CurrentNovelReaderSettings()

    override fun onAttachedToWindow() {
        checkNotch()
        super.onAttachedToWindow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityNovelReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rootFrame = binding.root as FrameLayout
        readerOverlay = NovelReaderOverlayManager(rootFrame)
        readerOverlay.attach()

        controllerDuration = (PrefManager.getVal<Float>(PrefName.AnimationSpeed) * 200).toLong()

        setupViews()
        setupBackPressedHandler()

        if (intent.data != null) {
            openPublication(intent.data!!)
        } else if (ani.dantotsu.media.novel.NovelReaderSession.isActive()) {
            loadStreamingChapter(0)
        }
    }

    private fun setupViews() {
        binding.novelReaderBack.setOnClickListener { finish() }
        binding.novelReaderSettings.setSafeOnClickListener {
            NovelReaderSettingsDialogFragment.newInstance()
                .show(supportFragmentManager, NovelReaderSettingsDialogFragment.TAG)
        }

        binding.novelReaderTts.setOnClickListener {
            if (ttsNavigator != null && binding.novelReaderTtsCard.visibility == View.VISIBLE) {
                stopTts()
            } else {
                startTts()
            }
        }

        // Floating TTS Controls
        binding.novelReaderTtsPlayPause.setOnClickListener {
            toggleTts()
        }
        binding.novelReaderTtsPrev.setOnClickListener {
            ttsNavigator?.skipToPreviousUtterance()
        }
        binding.novelReaderTtsNext.setOnClickListener {
            ttsNavigator?.skipToNextUtterance()
        }
        binding.novelReaderTtsStop.setOnClickListener {
            stopTts()
        }
        binding.novelReaderTtsSpeed.setOnClickListener {
            ttsSpeedIndex = (ttsSpeedIndex + 1) % ttsSpeeds.size
            val speed = ttsSpeeds[ttsSpeedIndex]
            binding.novelReaderTtsSpeed.text = "${speed}x"
            ttsNavigator?.submitPreferences(AndroidTtsPreferences(speed = speed))
        }

        binding.novelReaderNextChap.setOnClickListener { binding.novelReaderNextChapter.performClick() }
        binding.novelReaderNextChapter.setOnClickListener {
            navigateToNextChapter()
        }

        binding.novelReaderPrevChap.setOnClickListener { binding.novelReaderPreviousChapter.performClick() }
        binding.novelReaderPreviousChapter.setOnClickListener {
            navigateToPreviousChapter()
        }

        binding.novelReaderAutoScroll.setOnClickListener {
            toggleAutoScroll()
        }

        binding.autoScrollPlayPause.setOnClickListener {
            if (autoScroll.isRunning) {
                autoScroll.stop()
                binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
            } else {
                startAutoScroll()
            }
        }

        binding.autoScrollSpeedUp.setOnClickListener {
            val newSpeed = (autoScroll.speed + 0.5f).coerceAtMost(10f)
            autoScroll.speed = newSpeed
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL_SPEED, newSpeed)
            snackString("${newSpeed}x")
        }

        binding.autoScrollSpeedDown.setOnClickListener {
            val newSpeed = (autoScroll.speed - 0.5f).coerceAtLeast(0.5f)
            autoScroll.speed = newSpeed
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL_SPEED, newSpeed)
            snackString("${newSpeed}x")
        }

        binding.novelReaderSlider.setLabelFormatter { value ->
            val total = totalPositionsCount
            if (total > 0) {
                val pos = (value * total).toInt().coerceIn(1, total)
                "$pos / $total (${(value * 100).toInt()}%)"
            } else {
                "${(value * 100).toInt()}%"
            }
        }

        binding.novelReaderSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val total = totalPositionsCount
                val text = if (total > 0) {
                    val pos = (value * total).toInt().coerceIn(1, total)
                    "$pos / $total  (${(value * 100).toInt()}%)"
                } else {
                    "${(value * 100).toInt()}%"
                }
                binding.novelReaderPageNumber.text = text
            }
        }

        binding.novelReaderSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isSliderDragging = true
                if (autoScroll.isRunning) {
                    wasAutoScrolling = true
                    autoScroll.stop()
                    binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
                }
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val targetProgression = slider.value.toDouble()
                isNavigatingFromSlider = true
                binding.progress.visibility = View.VISIBLE
                val themeBg = getThemeColor(com.google.android.material.R.attr.colorSurface)
                binding.novelReaderFragmentContainer.setBackgroundColor(themeBg)

                scope.launch {
                    try {
                        val session = ani.dantotsu.media.novel.NovelReaderSession
                        if (session.isActive() && session.chapters.isNotEmpty()) {
                            val targetChapterIndex = (targetProgression * (session.chapters.size - 1))
                                .roundToInt()
                                .coerceIn(0, session.chapters.size - 1)
                            if (targetChapterIndex != session.currentIndex) {
                                session.currentIndex = targetChapterIndex
                                loadStreamingChapter(direction = 0)
                            }
                        } else {
                            val pub = currentPublication ?: return@launch
                            val targetLocator = pub.locateProgression(targetProgression)
                            if (targetLocator != null) {
                                visualNavigator?.go(targetLocator)
                            } else {
                                val readingOrder = pub.readingOrder
                                if (readingOrder.isNotEmpty()) {
                                    val contentLinks = readingOrder.filter { link ->
                                        val type = link.mediaType.toString()
                                        type.contains("html") || type.contains("xml")
                                    }.ifEmpty { readingOrder }
                                    val targetIndex = (targetProgression * (contentLinks.size - 1)).toInt().coerceIn(0, contentLinks.size - 1)
                                    visualNavigator?.go(contentLinks[targetIndex])
                                }
                            }
                        }
                    } finally {
                        isSliderDragging = false
                        // Safety timeout: if locator observer has not reset navigating flag within 1200ms, reset it
                        scope.launch {
                            kotlinx.coroutines.delay(1200)
                            if (isNavigatingFromSlider) {
                                isNavigatingFromSlider = false
                                binding.progress.visibility = View.GONE
                                if (wasAutoScrolling) {
                                    wasAutoScrolling = false
                                    val currentWv = findActiveWebView(binding.novelReaderFragmentContainer)
                                    if (currentWv != null) {
                                        attachAutoScroll(currentWv)
                                        autoScroll.start()
                                        binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_pause_24)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })

        onVolumeUp = { binding.novelReaderNextChapter.performClick() }
        onVolumeDown = { binding.novelReaderPreviousChapter.performClick() }
    }

    private fun loadStreamingChapter(direction: Int) {
        val session = ani.dantotsu.media.novel.NovelReaderSession
        val chapter = when {
            direction > 0 -> session.nextChapter()
            direction < 0 -> session.prevChapter()
            else -> session.currentChapter()
        }
        if (chapter == null || session.parser == null) {
            snackString("No more chapters")
            return
        }
        loaded = false
        isTransitioningChapter = true
        chapterTransitionCooldown = 300
        endOfChapterFrames = 0
        binding.progress.visibility = View.VISIBLE
        val chapterName = chapter.headers?.get("X-Chapter-Name") ?: "Chapter"
        binding.novelReaderTitle.text = chapterName

        scope.launch(Dispatchers.IO) {
            try {
                val html = session.parser!!.loadChapterHtml(chapter.url)
                val intent = ani.dantotsu.download.novel.HtmlToEpubUtils.streamToReader(
                    this@NovelReaderActivity, chapterName, html
                )
                val uri = intent.data!!
                openPublication(uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to load chapter: ${e.message}")
                    binding.progress.visibility = View.GONE
                    loaded = true
                }
            }
        }
    }

    private fun openPublication(uri: Uri) {
        loaded = false
        binding.progress.visibility = View.VISIBLE

        scope.launch(Dispatchers.IO) {
            try {
                val httpClient = DefaultHttpClient()
                val assetRetriever = AssetRetriever(contentResolver, httpClient)
                val readiumUrl = uri.toAbsoluteUrl()
                    ?: File(uri.path ?: "").toUrl(isDirectory = false)

                val asset = assetRetriever.retrieve(readiumUrl).getOrElse {
                    throw Exception(it.message)
                }

                val pdfDocumentFactory = PdfiumDocumentFactory(this@NovelReaderActivity)
                val publicationParser = DefaultPublicationParser(
                    context = this@NovelReaderActivity,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = pdfDocumentFactory
                )
                val publicationOpener = PublicationOpener(publicationParser)
                val pub = publicationOpener.open(asset, allowUserInteraction = false).getOrElse {
                    throw Exception(it.message)
                }

                withContext(Dispatchers.Main) {
                    onPublicationLoaded(pub)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to open publication: ${e.message}")
                    binding.progress.visibility = View.GONE
                    loaded = true
                }
            }
        }
    }

    private fun onPublicationLoaded(pub: Publication) {
        currentPublication?.close()
        currentPublication = pub

        val session = ani.dantotsu.media.novel.NovelReaderSession
        val bookId = pub.metadata.identifier
            ?: (if (session.isActive()) session.currentChapter()?.url else null)
            ?: pub.metadata.title
            ?: "stream_${System.currentTimeMillis()}"

        val illegalCharsRegex = Regex("[^a-zA-Z0-9._-]")
        sanitizedBookId = bookId.replace(illegalCharsRegex, "_")

        binding.novelReaderTitle.text = pub.metadata.title
        binding.novelReaderSource.text = pub.metadata.authors.joinToString(", ") { it.name }

        isTransitioningChapter = false
        chapterTransitionCooldown = 180
        endOfChapterFrames = 0

        scope.launch(Dispatchers.Default) {
            runCatching {
                val positions = pub.positions()
                totalPositionsCount = positions.size
                val ranges = mutableListOf<Pair<Double, Double>>()
                val readingOrder = pub.readingOrder
                if (readingOrder.isNotEmpty()) {
                    for (i in readingOrder.indices) {
                        val href = readingOrder[i].href.toString()
                        val matching = positions.filter { it.href.toString() == href }
                        if (matching.isNotEmpty()) {
                            val start = matching.first().locations.totalProgression ?: (i.toDouble() / readingOrder.size)
                            val end = matching.last().locations.totalProgression ?: ((i + 1).toDouble() / readingOrder.size)
                            ranges.add(start to end)
                        } else {
                            val start = i.toDouble() / readingOrder.size
                            val end = (i + 1).toDouble() / readingOrder.size
                            ranges.add(start to end)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    chapterRanges = ranges
                    val progression = binding.novelReaderSlider.value.toDouble()
                    val total = totalPositionsCount
                    if (total > 0) {
                        val pos = (progression * total).toInt().coerceIn(1, total)
                        binding.novelReaderPageNumber.text = "$pos / $total  (${(progression * 100).toInt()}%)"
                    } else {
                        binding.novelReaderPageNumber.text = "${(progression * 100).toInt()}%"
                    }
                }
            }
        }

        val savedLocatorJson = PrefManager.getNullableCustomVal("${sanitizedBookId}_locator", null, String::class.java)
        val savedLocator = savedLocatorJson?.let {
            runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull()
        }

        defaultSettings = loadReaderSettings("${sanitizedBookId}_current_settings") ?: defaultSettings

        val isPdf = pub.conformsTo(Publication.Profile.PDF)
        if (isPdf) {
            val pdfFactory = PdfNavigatorFactory(
                publication = pub,
                pdfEngineProvider = PdfiumEngineProvider()
            )
            val fragmentFactory = pdfFactory.createFragmentFactory(
                initialLocator = savedLocator,
                initialPreferences = PdfiumPreferences(
                    scroll = defaultSettings.layout == CurrentNovelReaderSettings.Layouts.SCROLLED
                )
            )
            supportFragmentManager.fragmentFactory = fragmentFactory
            val fragment = fragmentFactory.instantiate(classLoader, PdfNavigatorFragment::class.java.name) as PdfiumNavigatorFragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.novelReaderFragmentContainer, fragment, "pdf_navigator")
                .commitNow()
            visualNavigator = fragment
            pdfNavigator = fragment
            epubNavigator = null
        } else {
            val epubFactory = EpubNavigatorFactory(publication = pub)
            val fragmentFactory = epubFactory.createFragmentFactory(
                initialLocator = savedLocator,
                initialPreferences = buildEpubPreferences(),
                configuration = EpubNavigatorFragment.Configuration {
                    selectionActionModeCallback = this@NovelReaderActivity.selectionActionModeCallback
                    val hasPoppinsAssets = runCatching {
                        assets.open("fonts/poppins.ttf").use { }
                        assets.open("fonts/poppins_bold.ttf").use { }
                        true
                    }.getOrDefault(false)

                    if (hasPoppinsAssets) {
                        servedAssets = listOf("fonts/.*")
                        addFontFamilyDeclaration(FontFamily("Poppins")) {
                            addFontFace {
                                addSource("fonts/poppins.ttf")
                                setFontStyle(ReadiumFontStyle.NORMAL)
                                setFontWeight(ReadiumFontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/poppins_bold.ttf")
                                setFontStyle(ReadiumFontStyle.NORMAL)
                                setFontWeight(ReadiumFontWeight.BOLD)
                            }
                        }
                    }
                }
            )
            supportFragmentManager.fragmentFactory = fragmentFactory
            val fragment = fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.novelReaderFragmentContainer, fragment, "epub_navigator")
                .commitNow()
            visualNavigator = fragment
            epubNavigator = fragment
            pdfNavigator = null
        }

        setupChapterSelect(pub)
        setupNavigatorObservers()
        applySettings()

        binding.progress.visibility = View.GONE
        loaded = true
    }

    private var chapterSelectAdapter: ChapterSelectAdapter? = null
    private var suppressChapterSpinnerEvent = false

    private fun updateSelectedChapterInSpinner(index: Int) {
        val adapter = chapterSelectAdapter ?: return
        if (index in 0 until adapter.count && adapter.selectedIndex != index) {
            adapter.selectedIndex = index
            adapter.notifyDataSetChanged()
            suppressChapterSpinnerEvent = true
            binding.novelReaderChapterSelect.setSelection(index, false)
        }
    }

    class ChapterSelectAdapter(
        context: Context,
        private val items: List<String>,
        var selectedIndex: Int = 0
    ) : NoPaddingArrayAdapter<String>(context, R.layout.item_dropdown, items) {

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = android.view.LayoutInflater.from(parent.context)
            val textView = (convertView as? TextView)
                ?: inflater.inflate(R.layout.item_dropdown, parent, false) as TextView

            val isSelected = (position == selectedIndex)
            val original = items.getOrElse(position) { "" }

            val primaryColor = parent.context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary)).let { a ->
                try { a.getColor(0, AndroidColor.parseColor("#7C4DFF")) } finally { a.recycle() }
            }
            val textColor = parent.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).let { a ->
                try {
                    val c = a.getColor(0, AndroidColor.WHITE)
                    if (c == 0) AndroidColor.WHITE else c
                } finally {
                    a.recycle()
                }
            }

            if (isSelected) {
                textView.setTextColor(primaryColor)
                textView.setTypeface(textView.typeface, android.graphics.Typeface.BOLD)
                val alphaBg = android.graphics.Color.argb(
                    40,
                    android.graphics.Color.red(primaryColor),
                    android.graphics.Color.green(primaryColor),
                    android.graphics.Color.blue(primaryColor)
                )
                textView.setBackgroundColor(alphaBg)
                textView.text = "✓  $original"
            } else {
                textView.setTextColor(textColor)
                textView.setTypeface(android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.NORMAL))
                textView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                textView.text = original
            }
            return textView
        }
    }

    private fun setupChapterSelect(pub: Publication) {
        val session = ani.dantotsu.media.novel.NovelReaderSession
        if (session.isActive()) {
            val chapterLabels = session.chapters.mapIndexed { index, fileUrl ->
                fileUrl.headers?.get("X-Chapter-Name") ?: "Chapter ${index + 1}"
            }
            chapterSelectAdapter = ChapterSelectAdapter(this, chapterLabels, session.currentIndex)
            binding.novelReaderChapterSelect.adapter = chapterSelectAdapter
            suppressChapterSpinnerEvent = true
            binding.novelReaderChapterSelect.setSelection(session.currentIndex, false)
            binding.novelReaderChapterSelect.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (suppressChapterSpinnerEvent) {
                            suppressChapterSpinnerEvent = false
                            return
                        }
                        if (position != session.currentIndex) {
                            chapterSelectAdapter?.selectedIndex = position
                            chapterSelectAdapter?.notifyDataSetChanged()
                            session.currentIndex = position
                            loadStreamingChapter(direction = 0)
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        } else {
            val toc = pub.tableOfContents
            val tocLabels = if (toc.isNotEmpty()) {
                toc.map { it.title ?: "Chapter" }
            } else {
                pub.readingOrder.mapIndexed { index, link -> link.title ?: "Section ${index + 1}" }
            }
            val initialIndex = chapterSelectAdapter?.selectedIndex ?: 0
            chapterSelectAdapter = ChapterSelectAdapter(this, tocLabels, initialIndex)
            binding.novelReaderChapterSelect.adapter = chapterSelectAdapter
            suppressChapterSpinnerEvent = true
            binding.novelReaderChapterSelect.setSelection(initialIndex, false)
            binding.novelReaderChapterSelect.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (suppressChapterSpinnerEvent) {
                            suppressChapterSpinnerEvent = false
                            return
                        }
                        val link = toc.getOrNull(position) ?: pub.readingOrder.getOrNull(position)
                        if (link != null) {
                            chapterSelectAdapter?.selectedIndex = position
                            chapterSelectAdapter?.notifyDataSetChanged()
                            visualNavigator?.go(link)
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }
    }

    private fun setupNavigatorObservers() {
        locatorJob?.cancel()
        locatorJob = visualNavigator?.currentLocator?.onEach { locator ->
            latestLocator = locator
            val session = ani.dantotsu.media.novel.NovelReaderSession
            val pub = currentPublication
            if (session.isActive()) {
                updateSelectedChapterInSpinner(session.currentIndex)
            } else if (pub != null) {
                val toc = pub.tableOfContents
                val currentHref = locator.href.toString()
                val idx = if (toc.isNotEmpty()) {
                    toc.indexOfFirst {
                        val h = it.href.toString()
                        h == currentHref || currentHref.endsWith(h) || currentHref.contains(h)
                    }
                } else {
                    pub.readingOrder.indexOfFirst {
                        val h = it.href.toString()
                        h == currentHref || currentHref.endsWith(h) || currentHref.contains(h)
                    }
                }
                if (idx >= 0) {
                    updateSelectedChapterInSpinner(idx)
                }
            }
            isTransitioningChapter = false
            chapterTransitionCooldown = 45
            endOfChapterFrames = 0

            if (isNavigatingFromSlider) {
                isNavigatingFromSlider = false
                binding.progress.visibility = View.GONE
                if (wasAutoScrolling) {
                    wasAutoScrolling = false
                    val currentWv = findActiveWebView(binding.novelReaderFragmentContainer)
                    if (currentWv != null) {
                        attachAutoScroll(currentWv)
                        autoScroll.start()
                        binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_pause_24)
                    }
                }
            }

            val currentFont = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, "Default")
            applyFontDirectlyToWebView(fontName = currentFont)

            val rawProgression = locator.locations.totalProgression
            val progression = if (rawProgression != null) {
                rawProgression
            } else if (session.isActive() && session.chapters.isNotEmpty()) {
                val intra = locator.locations.progression ?: 0.0
                ((session.currentIndex + intra) / session.chapters.size).coerceIn(0.0, 1.0)
            } else if (pub != null && pub.readingOrder.isNotEmpty()) {
                val idx = pub.readingOrder.indexOfFirst {
                    it.href.toString() == locator.href.toString() || it.href == locator.href
                }.coerceAtLeast(0)
                val intra = locator.locations.progression ?: 0.0
                val range = chapterRanges.getOrNull(idx)
                if (range != null) {
                    (range.first + intra * (range.second - range.first)).coerceIn(0.0, 1.0)
                } else {
                    ((idx + intra) / pub.readingOrder.size).coerceIn(0.0, 1.0)
                }
            } else {
                locator.locations.progression ?: return@onEach
            }

            if (!isSliderDragging && !isNavigatingFromSlider) {
                binding.novelReaderSlider.value = progression.toFloat().coerceIn(0f, 1f)
            }
            val total = totalPositionsCount
            val text = if (total > 0) {
                val pos = (progression * total).toInt().coerceIn(1, total)
                "$pos / $total  (${(progression * 100).toInt()}%)"
            } else if (session.isActive() && session.chapters.isNotEmpty()) {
                val currentChap = session.currentIndex + 1
                val totalChap = session.chapters.size
                "Ch. $currentChap / $totalChap  (${(progression * 100).toInt()}%)"
            } else {
                "${(progression * 100).toInt()}%"
            }
            binding.novelReaderPageNumber.text = text
            readerOverlay.progressFraction = progression.toFloat()
            PrefManager.setCustomVal("${sanitizedBookId}_locator", locator.toJSON().toString())
        }?.launchIn(scope)

        visualNavigator?.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (autoScroll.isRunning) {
                    autoScroll.stop()
                    binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
                    return true
                }
                handleController()
                return true
            }
        })
    }

    // region Text Selection & Actions
    private val selectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, 1001, Menu.NONE, "Dictionary")
                .setIcon(R.drawable.ic_round_search_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, 1002, Menu.NONE, "Translate")
                .setIcon(R.drawable.ic_round_translate_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, 1003, Menu.NONE, "Read Aloud")
                .setIcon(R.drawable.ic_round_volume_up_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = true

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                1001 -> {
                    scope.launch {
                        val selection = (visualNavigator as? SelectableNavigator)?.currentSelection()
                        val text = selection?.locator?.text?.highlight?.trim()
                        (visualNavigator as? SelectableNavigator)?.clearSelection()
                        mode.finish()
                        if (!text.isNullOrBlank()) {
                            NovelDictionaryDialog.newInstance(text)
                                .show(supportFragmentManager, NovelDictionaryDialog.TAG)
                        }
                    }
                    return true
                }
                1002 -> {
                    scope.launch {
                        val selection = (visualNavigator as? SelectableNavigator)?.currentSelection()
                        val text = selection?.locator?.text?.highlight?.trim()
                        (visualNavigator as? SelectableNavigator)?.clearSelection()
                        mode.finish()
                        if (!text.isNullOrBlank()) {
                            NovelTranslateDialog.newInstance(text)
                                .show(supportFragmentManager, NovelTranslateDialog.TAG)
                        }
                    }
                    return true
                }
                1003 -> {
                    scope.launch {
                        val selection = (visualNavigator as? SelectableNavigator)?.currentSelection()
                        val selectedSnippet = selection?.locator?.text?.highlight?.trim()
                        val locator = selection?.locator
                        (visualNavigator as? SelectableNavigator)?.clearSelection()
                        mode.finish()
                        if (!selectedSnippet.isNullOrBlank()) {
                            speakSelectedText(selectedSnippet)
                        } else if (locator != null) {
                            startTts(fromLocator = locator)
                        }
                    }
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }
    // endregion

    // region Text-To-Speech (TTS)
    private fun speakSelectedText(text: String) {
        stopTts()
        if (snippetTts == null) {
            snippetTts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    snippetTts?.language = Locale.getDefault()
                    snippetTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "snippet_tts")
                } else {
                    snackString("TTS is not available")
                }
            }
        } else {
            snippetTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "snippet_tts")
        }
    }
    private fun toggleTts() {
        if (ttsNavigator != null) {
            if (ttsNavigator?.playback?.value?.playWhenReady == true) {
                ttsNavigator?.pause()
            } else {
                ttsNavigator?.play()
            }
        } else {
            startTts()
        }
    }

    private fun startTts(fromLocator: Locator? = null) {
        val pub = currentPublication ?: return
        ttsNavigator?.close()
        val ttsFactory = AndroidTtsNavigatorFactory(application, pub)
        if (ttsFactory == null) {
            snackString("TTS is not supported for this publication")
            return
        }

        if (autoScroll.isRunning) autoScroll.stop()

        scope.launch {
            val initialLoc = fromLocator ?: visualNavigator?.firstVisibleElementLocator()
            val navigatorTry = ttsFactory.createNavigator(
                listener = object : TtsNavigator.Listener {
                    override fun onStopRequested() {
                        stopTts()
                    }
                },
                initialLocator = initialLoc
            )
            val tts = navigatorTry.getOrNull()
            if (tts == null) {
                snackString("Failed to start TTS")
                return@launch
            }

            ttsNavigator = tts

            binding.novelReaderTtsCard.visibility = View.VISIBLE
            binding.novelReaderTtsPlayPause.setImageResource(R.drawable.ic_round_pause_24)

            ttsPlaybackJob?.cancel()
            ttsPlaybackJob = tts.playback.onEach { pb ->
                val isPlaying = pb.playWhenReady && pb.state is TtsNavigator.State.Ready
                binding.novelReaderTtsPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
                )
                if (pb.state is TtsNavigator.State.Ended) {
                    stopTts()
                }
            }.launchIn(scope)

            ttsLocationJob?.cancel()
            ttsLocationJob = tts.location.onEach { loc ->
                val utteranceLocator = loc.utteranceLocator
                val decoration = Decoration(
                    id = "tts_utterance",
                    locator = utteranceLocator,
                    style = Decoration.Style.Highlight(tint = AndroidColor.argb(80, 255, 235, 59))
                )
                (visualNavigator as? DecorableNavigator)?.applyDecorations(listOf(decoration), "tts")
            }.launchIn(scope)

            // Throttle auto-advance so it doesn't jump aggressively
            tts.location
                .map { it.tokenLocator ?: it.utteranceLocator }
                .distinctUntilChanged()
                .onEach { targetLoc ->
                    visualNavigator?.go(targetLoc, animated = false)
                }
                .launchIn(scope)

            tts.play()
        }
    }

    private fun stopTts() {
        ttsLocationJob?.cancel()
        ttsPlaybackJob?.cancel()
        ttsNavigator?.close()
        ttsNavigator = null
        scope.launch {
            (visualNavigator as? DecorableNavigator)?.applyDecorations(emptyList(), "tts")
        }
        binding.novelReaderTtsCard.visibility = View.GONE
        binding.novelReaderTtsPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
    }
    // endregion

    // region Settings & Appearance
    fun applySettings() {
        applySettingsJob?.cancel()
        applySettingsJob = scope.launch(Dispatchers.Main) {
            saveReaderSettings("${sanitizedBookId}_current_settings", defaultSettings)
            hideBars()

            if (defaultSettings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            requestedOrientation = when (defaultSettings.dualPageMode) {
                CurrentReaderSettings.DualPageModes.Force -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_USER
            }

            val fontName = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, "Default")
            epubNavigator?.submitPreferences(buildEpubPreferences())
            applyFontDirectlyToWebView(fontName = fontName)
            pdfNavigator?.submitPreferences(
                PdfiumPreferences(
                    scroll = defaultSettings.layout == CurrentNovelReaderSettings.Layouts.SCROLLED
                )
            )

            applyExtraSettings()
        }
    }

    private fun applyFontDirectlyToWebView(targetWv: WebView? = null, fontName: String? = null) {
        val effectiveFont = fontName ?: PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, "Default")
        val isBold = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_BOLD_FONT, false)
        val webViews = if (targetWv != null) listOf(targetWv) else findAllWebViews(binding.novelReaderFragmentContainer)
        if (webViews.isEmpty()) return

        val cssFont = when (effectiveFont) {
            "Default" -> null
            "Sans-Serif" -> "sans-serif"
            "Serif" -> "serif"
            "Monospace" -> "monospace"
            "Cursive" -> "cursive"
            "OpenDyslexic" -> "'OpenDyslexic', sans-serif"
            "AccessibleDfA" -> "'AccessibleDfA', sans-serif"
            "IA Writer Duospace" -> "'IA Writer Duospace', monospace"
            "Poppins" -> "'Poppins', sans-serif"
            else -> "'$effectiveFont', sans-serif"
        }

        val hasPoppinsAssets = runCatching {
            assets.open("fonts/poppins.ttf").use { }
            assets.open("fonts/poppins_bold.ttf").use { }
            true
        }.getOrDefault(false)

        val fontFaceCss = if (effectiveFont == "Poppins" && hasPoppinsAssets) {
            "@font-face { font-family: 'Poppins'; src: url('https://readium/assets/fonts/poppins.ttf') format('truetype'); font-weight: normal; font-style: normal; } " +
            "@font-face { font-family: 'Poppins'; src: url('https://readium/assets/fonts/poppins_bold.ttf') format('truetype'); font-weight: bold; font-style: normal; }"
        } else ""

        val fontRule = if (cssFont != null) {
            "body, p, span, div, h1, h2, h3, h4, h5, h6, li, a, blockquote { font-family: $cssFont !important; }"
        } else ""

        val fontCombined = (fontFaceCss + " " + fontRule).trim().replace("\n", " ").replace("'", "\\'")

        val boldRule = if (isBold) {
            "body, p, span, div, h1, h2, h3, h4, h5, h6, li, a, em, b, strong, blockquote { font-weight: 700 !important; }"
        } else ""

        val boldCombined = boldRule.trim().replace("\n", " ").replace("'", "\\'")

        val js = """
            (function() {
                try {
                    var targetParent = document.head || document.documentElement;
                    var fontStyle = document.getElementById('dantotsu-font-override');
                    if ('$fontCombined'.length > 0) {
                        if (!fontStyle) {
                            fontStyle = document.createElement('style');
                            fontStyle.id = 'dantotsu-font-override';
                            targetParent.appendChild(fontStyle);
                        }
                        fontStyle.textContent = '$fontCombined';
                    } else if (fontStyle) {
                        fontStyle.remove();
                    }

                    var boldStyle = document.getElementById('dantotsu-bold-override');
                    if ('$boldCombined'.length > 0) {
                        if (!boldStyle) {
                            boldStyle = document.createElement('style');
                            boldStyle.id = 'dantotsu-bold-override';
                            targetParent.appendChild(boldStyle);
                        }
                        boldStyle.textContent = '$boldCombined';
                    } else if (boldStyle) {
                        boldStyle.remove();
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        for (wv in webViews) {
            wv.evaluateJavascript(js, null)
        }
    }

    private fun buildEpubPreferences(): EpubPreferences {
        val theme = themes.firstOrNull { it.name.equals(defaultSettings.currentThemeName, ignoreCase = true) }
            ?: themes.first()

        val useDark = defaultSettings.useDarkTheme
        val isOled = defaultSettings.useOledTheme

        val bgInt = when {
            isOled -> AndroidColor.BLACK
            useDark -> theme.darkBg
            else -> theme.lightBg
        }

        val fgInt = when {
            isOled -> AndroidColor.WHITE
            useDark -> theme.darkFg
            else -> theme.lightFg
        }

        val readiumTheme = if (useDark || isOled) ReadiumTheme.DARK else ReadiumTheme.LIGHT

        val fontSizePx = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_SIZE_PX, 100)
        val fontSizeMultiplier = (fontSizePx / 100.0).coerceIn(0.5, 3.0)

        val textAlignInt = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_TEXT_ALIGN, 0)
        val readiumTextAlign = when (textAlignInt) {
            1 -> ReadiumTextAlign.START
            2 -> ReadiumTextAlign.CENTER
            3 -> ReadiumTextAlign.JUSTIFY
            else -> if (defaultSettings.justify) ReadiumTextAlign.JUSTIFY else null
        }

        val isScrolled = defaultSettings.layout == CurrentNovelReaderSettings.Layouts.SCROLLED

        val colCount = when (defaultSettings.dualPageMode) {
            CurrentReaderSettings.DualPageModes.No -> ColumnCount.ONE
            CurrentReaderSettings.DualPageModes.Automatic -> ColumnCount.AUTO
            CurrentReaderSettings.DualPageModes.Force -> ColumnCount.TWO
        }

        val letterSpacingEm = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_LETTER_SPACING, 0f).toDouble()
        val wordSpacingPx = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_WORD_SPACING_PX, 0).toDouble()
        val paraSpacingPx = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_PARAGRAPH_SPACING_PX, 0).toDouble()

        val fontName = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_FONT_FAMILY, "Default")
        val hasPoppinsAssets = runCatching {
            assets.open("fonts/poppins.ttf").use { }
            assets.open("fonts/poppins_bold.ttf").use { }
            true
        }.getOrDefault(false)

        val readiumFontFamily = when (fontName) {
            "Default" -> null
            "Sans-Serif" -> FontFamily.SANS_SERIF
            "Serif" -> FontFamily.SERIF
            "Monospace" -> FontFamily.MONOSPACE
            "Cursive" -> FontFamily.CURSIVE
            "OpenDyslexic" -> FontFamily.OPEN_DYSLEXIC
            "AccessibleDfA" -> FontFamily.ACCESSIBLE_DFA
            "IA Writer Duospace" -> FontFamily.IA_WRITER_DUOSPACE
            "Poppins" -> if (hasPoppinsAssets) FontFamily("Poppins") else FontFamily.SANS_SERIF
            else -> FontFamily(fontName)
        }

        val isBold = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_BOLD_FONT, false)

        val marginFactor = when {
            defaultSettings.margin in 0.001f..0.4f -> (defaultSettings.margin / 0.06).toDouble().coerceIn(0.2, 4.0)
            defaultSettings.margin > 0.4f -> defaultSettings.margin.toDouble().coerceIn(0.2, 4.0)
            else -> 1.0
        }

        return EpubPreferences(
            backgroundColor = ReadiumColor(bgInt),
            textColor = ReadiumColor(fgInt),
            theme = readiumTheme,
            fontFamily = readiumFontFamily,
            fontWeight = if (isBold) 2.0 else null,
            fontSize = fontSizeMultiplier,
            lineHeight = defaultSettings.lineHeight.toDouble().takeIf { it > 0 },
            pageMargins = marginFactor,
            letterSpacing = letterSpacingEm.takeIf { it > 0 },
            wordSpacing = wordSpacingPx.takeIf { it > 0 },
            paragraphSpacing = paraSpacingPx.takeIf { it > 0 },
            scroll = isScrolled,
            columnCount = colCount,
            textAlign = readiumTextAlign,
            hyphens = defaultSettings.hyphenation,
            publisherStyles = false
        )
    }

    private fun toggleAutoScroll() {
        if (binding.novelReaderAutoScrollPlayBar.visibility == View.VISIBLE) {
            if (autoScroll.isRunning) {
                autoScroll.stop()
                binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
            } else {
                binding.novelReaderAutoScrollPlayBar.visibility = View.GONE
                PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL, false)
            }
        } else {
            startAutoScroll()
        }
    }

    private fun attachAutoScroll(wv: WebView) {
        endOfChapterFrames = 0
        chapterTransitionCooldown = 45
        autoScroll.attach(wv) { delta ->
            if (isSliderDragging || isNavigatingFromSlider) return@attach
            if (isTransitioningChapter || chapterTransitionCooldown > 0) {
                if (chapterTransitionCooldown > 0) chapterTransitionCooldown--
                endOfChapterFrames = 0
                return@attach
            }
            val activeWv = findActiveWebView(binding.novelReaderFragmentContainer)
            if (activeWv != null) {
                activeWv.scrollBy(0, delta)
                scrollFrameCount++
                if (scrollFrameCount % 4 == 0) {
                    updateProgressFromScroll(activeWv)
                }
                val canScrollDown = activeWv.canScrollVertically(1)
                val isActuallyScrollable = (activeWv.contentHeight * activeWv.scale) > (activeWv.height + 100)
                if (isActuallyScrollable && !canScrollDown) {
                    endOfChapterFrames++
                    if (endOfChapterFrames > 45) {
                        endOfChapterFrames = 0
                        isTransitioningChapter = true
                        chapterTransitionCooldown = 60
                        navigateToNextChapter()
                    }
                } else {
                    endOfChapterFrames = 0
                }
            }
        }
    }

    private fun navigateToNextChapter() {
        if (ani.dantotsu.media.novel.NovelReaderSession.isActive() && ani.dantotsu.media.novel.NovelReaderSession.hasNext()) {
            loadStreamingChapter(direction = 1)
        } else {
            val pub = currentPublication
            if (pub != null && pub.readingOrder.isNotEmpty()) {
                val currentHref = latestLocator?.href
                val currentIndex = if (currentHref != null) {
                    pub.readingOrder.indexOfFirst {
                        it.href.toString() == currentHref.toString() || it.href == currentHref
                    }
                } else -1
                val targetIndex = if (currentIndex >= 0) currentIndex + 1 else 1
                if (targetIndex < pub.readingOrder.size) {
                    val nextLink = pub.readingOrder[targetIndex]
                    val nextLocator = pub.locatorFromLink(nextLink)
                    if (nextLocator != null) {
                        visualNavigator?.go(nextLocator, animated = false)
                    } else {
                        visualNavigator?.go(nextLink, animated = false)
                    }
                } else {
                    isTransitioningChapter = false
                    snackString("Reached end of book")
                }
            } else {
                (visualNavigator as? OverflowableNavigator)?.goForward(animated = false)
            }
        }
        scope.launch {
            kotlinx.coroutines.delay(3500)
            if (isTransitioningChapter) {
                isTransitioningChapter = false
            }
        }
    }

    private fun navigateToPreviousChapter() {
        if (ani.dantotsu.media.novel.NovelReaderSession.isActive() && ani.dantotsu.media.novel.NovelReaderSession.hasPrev()) {
            loadStreamingChapter(direction = -1)
        } else {
            val pub = currentPublication
            if (pub != null && pub.readingOrder.isNotEmpty()) {
                val currentHref = latestLocator?.href
                val currentIndex = if (currentHref != null) {
                    pub.readingOrder.indexOfFirst {
                        it.href.toString() == currentHref.toString() || it.href == currentHref
                    }
                } else -1
                val targetIndex = if (currentIndex > 0) currentIndex - 1 else 0
                if (targetIndex >= 0 && targetIndex < pub.readingOrder.size && targetIndex != currentIndex) {
                    val prevLink = pub.readingOrder[targetIndex]
                    val prevLocator = pub.locatorFromLink(prevLink)
                    if (prevLocator != null) {
                        visualNavigator?.go(prevLocator, animated = false)
                    } else {
                        visualNavigator?.go(prevLink, animated = false)
                    }
                }
            } else {
                (visualNavigator as? OverflowableNavigator)?.goBackward(animated = false)
            }
        }
    }

    private fun startAutoScroll() {
        if (defaultSettings.layout == CurrentNovelReaderSettings.Layouts.PAGED) {
            defaultSettings.layout = CurrentNovelReaderSettings.Layouts.SCROLLED
            applySettings()
            snackString("Switched to Continuous mode for auto-scroll")
        }
        if (ttsNavigator != null) stopTts()
        val wv = findActiveWebView(binding.novelReaderFragmentContainer)
        if (wv != null) {
            attachAutoScroll(wv)
            autoScroll.start()
            binding.novelReaderAutoScrollPlayBar.visibility = View.VISIBLE
            binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_pause_24)
            PrefManager.setCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL, true)
        } else {
            snackString("Cannot start auto-scroll")
        }
    }

    fun applyExtraSettings() {
        autoScroll.speed = PrefManager.getCustomVal(
            ExtraNovelReaderPrefs.PREF_AUTO_SCROLL_SPEED, 3f
        ).toFloat()
        val autoScrollEnabled = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_AUTO_SCROLL, false)
        if (autoScrollEnabled) {
            val wv = findActiveWebView(binding.novelReaderFragmentContainer)
            if (wv != null) {
                attachAutoScroll(wv)
            }
            // Always start paused when reader is opened (matches MangaReader behavior)
            autoScroll.stop()
            binding.novelReaderAutoScrollPlayBar.visibility = View.VISIBLE
            binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
        } else {
            autoScroll.stop()
            binding.novelReaderAutoScrollPlayBar.visibility = View.GONE
            binding.autoScrollPlayPause.setImageResource(R.drawable.ic_round_play_arrow_24)
        }

        readerOverlay.showStatusBar = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_SHOW_STATUS_BAR, false)
        readerOverlay.showReadingProgress = PrefManager.getCustomVal(ExtraNovelReaderPrefs.PREF_SHOW_PROGRESS, false)
    }

    private fun findAllWebViews(root: View?): List<WebView> {
        if (root == null) return emptyList()
        val webViews = mutableListOf<WebView>()
        fun collect(v: View) {
            if (v is WebView) {
                webViews.add(v)
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    collect(v.getChildAt(i))
                }
            }
        }
        collect(root)
        return webViews
    }

    private fun findActiveWebView(root: View?): WebView? {
        val webViews = findAllWebViews(root)
        if (webViews.isEmpty()) return null
        if (webViews.size == 1) {
            val single = webViews[0]
            attachWebViewScrollListener(single)
            return single
        }

        val screenRect = android.graphics.Rect()
        root?.getGlobalVisibleRect(screenRect)
        val centerX = screenRect.centerX()
        val centerY = screenRect.centerY()

        var bestWv: WebView? = null
        var maxVisibleArea = 0

        for (w in webViews) {
            if (!w.isShown) continue
            val r = android.graphics.Rect()
            if (w.getGlobalVisibleRect(r)) {
                val area = r.width() * r.height()
                if (r.contains(centerX, centerY) && area > maxVisibleArea) {
                    maxVisibleArea = area
                    bestWv = w
                }
            }
        }
        val target = bestWv ?: webViews.firstOrNull { it.isShown } ?: webViews[0]
        attachWebViewScrollListener(target)
        return target
    }

    private fun attachWebViewScrollListener(wv: WebView) {
        if (lastObservedWebView === wv) return
        lastObservedWebView = wv
        applyFontDirectlyToWebView(targetWv = wv)
        wv.setOnScrollChangeListener { _, _, _, _, _ ->
            updateProgressFromScroll(wv)
        }
    }

    private fun updateProgressFromScroll(wv: WebView) {
        if (isSliderDragging || isNavigatingFromSlider) return
        val session = ani.dantotsu.media.novel.NovelReaderSession
        val pub = currentPublication ?: return

        val scrollY = wv.scrollY.toFloat()
        val maxScroll = (wv.contentHeight * wv.scale - wv.height).coerceAtLeast(1f)
        val chapterProgress = (scrollY / maxScroll).coerceIn(0f, 1f)

        val totalChapters = if (session.isActive()) {
            session.chapters.size
        } else {
            pub.readingOrder.size
        }

        if (totalChapters <= 0) return

        val chapterIndex = if (session.isActive()) {
            session.currentIndex.coerceIn(0, totalChapters - 1)
        } else {
            val currentHref = latestLocator?.href
            val idx = if (currentHref != null) {
                pub.readingOrder.indexOfFirst {
                    it.href.toString() == currentHref.toString() || it.href == currentHref
                }
            } else -1
            if (idx >= 0) idx else 0
        }

        val overallProgression = if (session.isActive() && session.chapters.isNotEmpty()) {
            ((session.currentIndex + chapterProgress) / session.chapters.size).toDouble().coerceIn(0.0, 1.0)
        } else if (chapterRanges.isNotEmpty()) {
            val range = chapterRanges.getOrNull(chapterIndex)
            if (range != null) {
                (range.first + chapterProgress * (range.second - range.first)).coerceIn(0.0, 1.0)
            } else {
                ((chapterIndex + chapterProgress) / totalChapters).toDouble().coerceIn(0.0, 1.0)
            }
        } else {
            ((chapterIndex + chapterProgress) / totalChapters).toDouble().coerceIn(0.0, 1.0)
        }

        readerOverlay.progressFraction = overallProgression.toFloat()

        // Also update the slider and page number text during auto-scroll
        if (!isSliderDragging && !isNavigatingFromSlider) {
            binding.novelReaderSlider.value = overallProgression.toFloat().coerceIn(0f, 1f)
            val total = totalPositionsCount
            val text = if (total > 0) {
                val pos = ((overallProgression * total).toInt()).coerceIn(1, total)
                "$pos / $total  (${(overallProgression * 100).toInt()}%)"
            } else if (session.isActive() && session.chapters.isNotEmpty()) {
                val currentChap = session.currentIndex + 1
                val totalChap = session.chapters.size
                "Ch. $currentChap / $totalChap  (${(overallProgression * 100).toInt()}%)"
            } else {
                "${(overallProgression * 100).toInt()}%"
            }
            binding.novelReaderPageNumber.text = text
        }
    }
    // endregion

    // region Handle Controls & Overlay
    private var isContVisible = false
    private var isAnimating = false
    private val goneHandler = Handler(Looper.getMainLooper())
    private val goneRunnable = Runnable {
        if (!isContVisible && ::binding.isInitialized) {
            binding.novelReaderCont.visibility = View.GONE
            isAnimating = false
        }
    }
    private var controllerDuration by Delegates.notNull<Long>()
    private val overshoot = OvershootInterpolator(1.4f)

    fun gone() {
        goneHandler.removeCallbacks(goneRunnable)
        goneHandler.postDelayed(goneRunnable, controllerDuration)
    }

    fun handleController(shouldShow: Boolean? = null) {
        if (!loaded) return

        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            hideBars()
            applyNotchMargin()
        }

        shouldShow?.apply { isContVisible = !this }
        if (isContVisible) {
            isContVisible = false
            if (!isAnimating) {
                isAnimating = true
                ObjectAnimator.ofFloat(binding.novelReaderCont, "alpha", 1f, 0f)
                    .setDuration(controllerDuration).start()
                ObjectAnimator.ofFloat(binding.novelReaderBottomCont, "translationY", 0f, 128f)
                    .apply { interpolator = overshoot; duration = controllerDuration; start() }
                ObjectAnimator.ofFloat(binding.novelReaderTopLayout, "translationY", 0f, -128f)
                    .apply { interpolator = overshoot; duration = controllerDuration; start() }
            }
            gone()
        } else {
            isContVisible = true
            binding.novelReaderCont.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(binding.novelReaderCont, "alpha", 0f, 1f)
                .setDuration(controllerDuration).start()
            ObjectAnimator.ofFloat(binding.novelReaderTopLayout, "translationY", -128f, 0f)
                .apply { interpolator = overshoot; duration = controllerDuration; start() }
            ObjectAnimator.ofFloat(binding.novelReaderBottomCont, "translationY", 128f, 0f)
                .apply { interpolator = overshoot; duration = controllerDuration; start() }
        }
    }

    private fun setupBackPressedHandler() {
        var lastBackPressedTime: Long = 0
        val doublePressInterval: Long = 2000

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.novelReaderTtsCard.visibility == View.VISIBLE) {
                    stopTts()
                    return
                }
                if (lastBackPressedTime + doublePressInterval > System.currentTimeMillis()) {
                    finish()
                } else {
                    snackString("Press back again to exit")
                    lastBackPressedTime = System.currentTimeMillis()
                }
            }
        })
    }

    private var onVolumeUp: (() -> Unit)? = null
    private var onVolumeDown: (() -> Unit)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    if (!defaultSettings.volumeButtons)
                        return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onVolumeUp?.invoke()
                    true
                } else false
            }

            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                    if (!defaultSettings.volumeButtons)
                        return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onVolumeDown?.invoke()
                    true
                } else false
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun checkNotch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            val displayCutout = window.decorView.rootWindowInsets?.displayCutout
            if (displayCutout != null && displayCutout.boundingRects.isNotEmpty()) {
                notchHeight = min(
                    displayCutout.boundingRects[0].width(),
                    displayCutout.boundingRects[0].height()
                )
                applyNotchMargin()
            }
        }
    }

    private fun applyNotchMargin() {
        binding.novelReaderTopLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = notchHeight ?: return
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadReaderSettings(
        fileName: String,
        context: Context? = null,
        toast: Boolean = true
    ): T? {
        val a = context ?: currContext()
        try {
            if (a?.fileList() != null && fileName in a.fileList()) {
                val fileIS: FileInputStream = a.openFileInput(fileName)
                val objIS = ObjectInputStream(fileIS)
                val data = objIS.readObject() as T
                objIS.close()
                fileIS.close()
                return data
            }
        } catch (e: Exception) {
            if (toast) snackString(a?.getString(R.string.error_loading_data, fileName))
            try {
                a?.deleteFile(fileName)
            } catch (e2: Exception) {
                Injekt.get<CrashlyticsInterface>().log("Failed to delete file $fileName")
                Injekt.get<CrashlyticsInterface>().logException(e2)
            }
            e.printStackTrace()
        }
        return null
    }

    private fun saveReaderSettings(fileName: String, data: Any?, context: Context? = null) {
        tryWith {
            val a = context ?: currContext()
            if (a != null) {
                val fos: FileOutputStream = a.openFileOutput(fileName, Context.MODE_PRIVATE)
                val os = ObjectOutputStream(fos)
                os.writeObject(data)
                os.close()
                fos.close()
            }
        }
    }

    private fun hideBars() {
        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBars)) {
            hideSystemBars()
        }
    }

    override fun onDestroy() {
        snippetTts?.stop()
        snippetTts?.shutdown()
        snippetTts = null
        stopTts()
        autoScroll.destroy()
        readerOverlay.destroy()
        locatorJob?.cancel()
        currentPublication?.close()
        currentPublication = null
        ani.dantotsu.media.novel.NovelReaderSession.clear()
        goneHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
