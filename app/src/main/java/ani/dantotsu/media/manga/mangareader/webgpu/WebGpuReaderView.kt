package ani.dantotsu.media.manga.mangareader.webgpu

import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.media.manga.mangareader.MangaReaderActivity
import ani.dantotsu.parsers.MangaImage
import ca.mpreg.imagedecoder.ImageDecoder
import ca.mpreg.webgpuviewer.ImageView
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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

    init {
        val bgColor = when (activity.defaultSettings.backgroundColor) {
            1 -> Color.BLACK
            2 -> Color.DKGRAY
            3 -> Color.WHITE
            else -> Color.TRANSPARENT
        }

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
                        val chap = currentChapter ?: return@apply
                        activity.onChapterScrolledTo(chap, pageIndex + 1, images.size)
                        checkPreloadBoundaries(pageIndex)
                    }
                }
            }
        } else {
            pagedViewer?.state?.apply {
                backgroundColor = bgColor
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
                        val chap = currentChapter ?: return@onPageChange
                        activity.onChapterScrolledTo(chap, currentPosition + 1, images.size)
                        checkPreloadBoundaries(currentPosition)
                        preloadAround(currentPosition)
                    }
                }
            }
        }
    }

    fun updateBackgroundColor(color: Int) {
        if (isContinuous) {
            continuousViewer?.state?.backgroundColor = color
        } else {
            pagedViewer?.state?.backgroundColor = color
        }
        invalidate()
    }

    fun bind(chapter: MangaChapter, initialPage: Int) {
        currentChapter = chapter
        images = chapter.images()
        currentPosition = (initialPage - 1).coerceIn(0, (images.size - 1).coerceAtLeast(0))
        clearCache()
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

        loadPage(targetIdx)
        return null
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
        if (loadingJobs.containsKey(index) || pageCache.containsKey(index)) return
        val img = images.getOrNull(index) ?: return

        val job = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = openStream(img) ?: return@launch
                val imagePage = stream.use { decodeToGpuImage(it) }
                if (imagePage != null) {
                    pageCache[index] = imagePage
                    withContext(Dispatchers.Main) {
                        invalidate()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                loadingJobs.remove(index)
            }
        }
        loadingJobs[index] = job
    }

    private fun decodeToGpuImage(inputStream: InputStream): ImagePage? {
        return try {
            val decoder = try {
                ImageDecoder.open(inputStream)
            } catch (_: Throwable) {
                ImageDecoder.new(inputStream)
            } ?: return null

            decoder.use { dec ->
                if (dec.pages > 0) {
                    val frame = dec.decodeNext()
                    val trimColors = if (activity.defaultSettings.cropBorders) {
                        listOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f))
                    } else null

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
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun openStream(image: MangaImage): InputStream? = withContext(Dispatchers.IO) {
        val url = image.url.url
        if (url.isEmpty()) return@withContext null

        if (url.startsWith("content://")) {
            return@withContext try {
                activity.contentResolver.openInputStream(Uri.parse(url))
            } catch (_: Exception) { null }
        }

        val localFile = File(url)
        if (localFile.exists()) {
            return@withContext try { localFile.inputStream() } catch (_: Exception) { null }
        }

        try {
            val okHttpClient = Injekt.get<OkHttpClient>()
            val requestBuilder = Request.Builder().url(url)
            image.url.headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()
            } else null
        } catch (_: Exception) {
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
