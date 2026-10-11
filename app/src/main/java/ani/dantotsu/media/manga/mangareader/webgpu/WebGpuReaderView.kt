package ani.dantotsu.media.manga.mangareader.webgpu

import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.webgpu.GPUTexture
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.media.manga.mangareader.MangaReaderActivity
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.util.Logger
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

class WebGpuReaderView(
    private val activity: MangaReaderActivity,
    val isContinuous: Boolean = false,
    val isVertical: Boolean = false,
    val isReversed: Boolean = false
) {
    val viewer: View = if (isContinuous) {
        ImageViewContinuous(activity)
    } else {
        ImageView(activity, isVertical = isVertical, isReversed = isReversed)
    }

    val pagedViewer: ImageView? get() = viewer as? ImageView
    val continuousViewer: ImageViewContinuous? get() = viewer as? ImageViewContinuous

    private var currentChapter: MangaChapter? = null
    private var images: List<MangaImage> = emptyList()

    private val pageCache = ConcurrentHashMap<Int, ImagePage>()
    private val loadingJobs = ConcurrentHashMap<Int, Job>()
    private val maxCacheSize = 8

    var currentPosition: Int = 0
        private set

    inner class ProgressPage(val pageIndex: Int) : ImagePage.Render(0, 0) {
        override val width: Int
            get() = (pagedViewer?.state?.width ?: continuousViewer?.state?.width)?.takeIf { it > 0 } ?: 1080

        override val height: Int
            get() = (pagedViewer?.state?.height ?: continuousViewer?.state?.height)?.takeIf { it > 0 } ?: 1920

        override val backgroundColor: Int
            get() = when (activity.defaultSettings.backgroundColor) {
                1 -> Color.BLACK
                2 -> Color.DKGRAY
                3 -> Color.WHITE
                else -> Color.BLACK
            }

        init {
            minScale = 1f
            maxScale = 1f
            homeScale = 1f
        }

        override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
            fillPage(dst, x, y, scale, backgroundColor)
            val cx = dst.width * (0.5f + scale * x)
            val cy = dst.height * (0.5f + scale * y)
            val full = min(width, height) * 0.25f * scale
            circle(cx, cy, full / 2f, 0x55888888.toInt())
        }
    }

    init {
        val bgColor = when (activity.defaultSettings.backgroundColor) {
            1 -> Color.BLACK
            2 -> Color.DKGRAY
            3 -> Color.WHITE
            else -> Color.BLACK
        }

        viewer.setBackgroundColor(bgColor)

        if (isContinuous) {
            continuousViewer?.state?.apply {
                backgroundColor = bgColor
                onTap = { _ ->
                    activity.handleController()
                }
                fetchPage = { delta ->
                    getPageForDelta(delta)
                }
                onViewport = { readThrough ->
                    val pageIndex = pageCache.entries.firstOrNull { it.value === readThrough }?.key
                    if (pageIndex != null && pageIndex != currentPosition) {
                        currentPosition = pageIndex
                        val chap = currentChapter
                        if (chap != null) {
                            activity.onChapterScrolledTo(chap, pageIndex + 1, images.size)
                            checkPreloadBoundaries(pageIndex)
                            preloadAround(pageIndex)
                        }
                    }
                }
            }
        } else {
            pagedViewer?.state?.apply {
                backgroundColor = bgColor
                transition = if (isVertical) TransitionBasic.Vertical else TransitionBasic
                onTap = { _ ->
                    activity.handleController()
                }
                fetchPage = { delta ->
                    getPageForDelta(delta)
                }
                onPageChange = { delta ->
                    val target = currentPosition + delta
                    if (target in images.indices) {
                        currentPosition = target
                        val chap = currentChapter
                        if (chap != null) {
                            activity.onChapterScrolledTo(chap, currentPosition + 1, images.size)
                            checkPreloadBoundaries(currentPosition)
                            preloadAround(currentPosition)
                        }
                    }
                }
            }
        }
    }

    fun updateBackgroundColor(color: Int) {
        viewer.setBackgroundColor(color)
        if (isContinuous) {
            continuousViewer?.state?.backgroundColor = color
        }
        invalidate()
    }

    fun bind(chapter: MangaChapter, initialPage: Int) {
        currentChapter = chapter
        images = chapter.images()
        currentPosition = (initialPage - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        clearCache()
        if (currentPosition in images.indices) {
            pageCache[currentPosition] = ProgressPage(currentPosition)
            loadPage(currentPosition)
        }
        preloadAround(currentPosition)
        invalidate()
    }

    fun scrollToPage(pageNumber: Int) {
        val target = (pageNumber - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        val delta = target - currentPosition
        currentPosition = target
        preloadAround(currentPosition)
        if (!isContinuous) {
            pagedViewer?.state?.animatePageTurn(if (isReversed) -delta else delta)
        } else {
            continuousViewer?.state?.resetScroll()
        }
        invalidate()
    }

    fun scrollUp() {
        if (isContinuous) {
            continuousViewer?.state?.let { s ->
                s.animateScroll(-s.height / 2f)
            }
        } else {
            pagedViewer?.state?.animatePageTurn(if (isReversed) 1 else -1)
        }
    }

    fun scrollDown() {
        if (isContinuous) {
            continuousViewer?.state?.let { s ->
                s.animateScroll(s.height / 2f)
            }
        } else {
            pagedViewer?.state?.animatePageTurn(if (isReversed) -1 else 1)
        }
    }

    private fun getPageForDelta(delta: Int): ImagePage? {
        val targetIdx = currentPosition + delta
        if (targetIdx !in images.indices) return null

        val cached = pageCache[targetIdx]
        if (cached != null) return cached

        val placeholder = ProgressPage(targetIdx)
        pageCache[targetIdx] = placeholder
        loadPage(targetIdx)
        return placeholder
    }

    private fun preloadAround(center: Int) {
        val range = (center - 2)..(center + 3)
        for (i in range) {
            if (i in images.indices && !pageCache.containsKey(i)) {
                loadPage(i)
            }
        }
        trimCache(center)
    }

    private fun checkPreloadBoundaries(pos: Int) {
        if (images.isEmpty()) return
        if (images.size - (pos + 1) <= 5) {
            val nextIdx = activity.currentChapterIndex + 1
            activity.chaptersArr.getOrNull(nextIdx)?.let { key ->
                activity.chapters[key]?.let { nextChap ->
                    activity.preloadChapterAndAppend(nextChap)
                }
            }
        }
        if (pos <= 5) {
            val prevIdx = activity.currentChapterIndex - 1
            activity.chaptersArr.getOrNull(prevIdx)?.let { key ->
                activity.chapters[key]?.let { prevChap ->
                    activity.preloadChapterAndPrepend(prevChap)
                }
            }
        }
    }

    private fun loadPage(index: Int) {
        if (loadingJobs.containsKey(index)) return
        val currentEntry = pageCache[index]
        if (currentEntry != null && currentEntry !is ProgressPage) return
        val img = images.getOrNull(index) ?: return

        val job = activity.lifecycleScope.launch(Dispatchers.IO) {
            val stream = openStream(img)
            if (stream == null) {
                Logger.log("WebGpuReaderView: openStream returned null for page $index")
                return@launch
            }
            try {
                val imagePage = decodeToGpuImage(stream)
                if (imagePage != null) {
                    val old = pageCache[index]
                    pageCache[index] = imagePage
                    old?.cleanup()
                    if (!isContinuous) {
                        imagePage.fadeIn()
                    }
                    withContext(Dispatchers.Main) {
                        invalidate()
                    }
                } else {
                    Logger.log("WebGpuReaderView: decodeToGpuImage returned null for page $index")
                }
            } catch (e: Throwable) {
                Logger.log("WebGpuReaderView: Exception loading page $index: ${e.message}")
            } finally {
                try { stream.close() } catch (_: Throwable) {}
                loadingJobs.remove(index)
            }
        }
        loadingJobs[index] = job
    }

    private suspend fun decodeToGpuImage(inputStream: InputStream): ImagePage? {
        val decoder = try {
            ImageDecoder.open(inputStream)
        } catch (e: Throwable) {
            Logger.log("WebGpuReaderView: ImageDecoder.open failed: ${e.message}")
            null
        } ?: return null

        return try {
            decoder.use { dec ->
                if (dec.hasNext) {
                    val frame = dec.decodeNext()
                    val trimColors = if (activity.defaultSettings.cropBorders) {
                        listOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f))
                    } else null

                    frame.image.rewind()
                    val gpuImage = Image(
                        frame.image,
                        frame.width,
                        frame.height,
                        createMipMaps = true,
                        trimColors = trimColors,
                        trimThreshold = 0.15f
                    )
                    frame.close()
                    ImagePage.ImageSingle(gpuImage)
                } else null
            }
        } catch (e: Throwable) {
            Logger.log("WebGpuReaderView: decodeNext failed: ${e.message}")
            null
        }
    }

    private suspend fun openStream(image: MangaImage): InputStream? = withContext(Dispatchers.IO) {
        val url = image.url.url
        if (url.isEmpty()) return@withContext null

        if (url.startsWith("content://")) {
            return@withContext try {
                val bytes = activity.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
                bytes?.let { ByteArrayInputStream(it) }
            } catch (e: Exception) {
                Logger.log("WebGpuReaderView: contentResolver error: ${e.message}")
                null
            }
        }

        val localFile = File(url)
        if (localFile.exists()) {
            return@withContext try {
                val bytes = localFile.inputStream().use { it.readBytes() }
                ByteArrayInputStream(bytes)
            } catch (e: Exception) {
                Logger.log("WebGpuReaderView: localFile error: ${e.message}")
                null
            }
        }

        // 1. Check MangaCache for ImageData with source (handles cookies, Cloudflare, headers)
        try {
            val mangaCache = Injekt.get<MangaCache>()
            val imageData = mangaCache.get(url)
            if (imageData != null) {
                val response = imageData.source.getImage(imageData.page)
                if (response.isSuccessful) {
                    val bytes = response.body.byteStream().use { it.readBytes() }
                    return@withContext ByteArrayInputStream(bytes)
                } else {
                    Logger.log("WebGpuReaderView: source.getImage response code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Logger.log("WebGpuReaderView: source.getImage error: ${e.message}")
        }

        // 2. Network fallback with OkHttpClient
        try {
            val okHttpClient = Injekt.get<OkHttpClient>()
            val requestBuilder = Request.Builder().url(url)
            image.url.headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.byteStream()?.use { it.readBytes() } ?: return@withContext null
                ByteArrayInputStream(bytes)
            } else {
                Logger.log("WebGpuReaderView: network fallback response code: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Logger.log("WebGpuReaderView: network fallback error: ${e.message}")
            null
        }
    }

    private fun trimCache(center: Int) {
        if (pageCache.size <= maxCacheSize) return
        val iterator = pageCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (abs(entry.key - center) > 4) {
                entry.value.cleanup()
                iterator.remove()
            }
        }
    }

    fun invalidate() {
        if (isContinuous) {
            continuousViewer?.state?.invalidate()
        } else {
            pagedViewer?.state?.invalidate()
        }
    }

    fun clearCache() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        pageCache.values.forEach { it.cleanup() }
        pageCache.clear()
    }

    fun destroy() {
        clearCache()
        if (isContinuous) {
            continuousViewer?.state?.onViewport = null
            continuousViewer?.state?.onTap = null
            continuousViewer?.state?.fetchPage = null
        } else {
            pagedViewer?.state?.onTap = null
            pagedViewer?.state?.fetchPage = null
            pagedViewer?.state?.onPageChange = null
        }
    }
}
