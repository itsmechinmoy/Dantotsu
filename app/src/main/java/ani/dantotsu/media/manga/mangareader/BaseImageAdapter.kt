package ani.dantotsu.media.manga.mangareader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.FileUrl
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.px
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.tryWithSuspend
import com.alexvasilkov.gestures.views.GestureFrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.abs

abstract class BaseImageAdapter(
    val activity: MangaReaderActivity,
    chapter: MangaChapter
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val settings = activity.defaultSettings
    private val chapterImages = chapter.images()
    var images = chapterImages

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        images = if (settings.layout == CurrentReaderSettings.Layouts.PAGED
            && settings.direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP
        ) {
            chapterImages.reversed()
        } else {
            chapterImages
        }
        super.onAttachedToRecyclerView(recyclerView)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView as GestureFrameLayout
        view.controller.also {
            if (settings.layout == CurrentReaderSettings.Layouts.PAGED) {
                it.settings.enableGestures()
            }
            it.settings.isRotationEnabled = settings.rotation
        }
        if (settings.layout != CurrentReaderSettings.Layouts.PAGED) {
            if (settings.padding) {
                when (settings.direction) {
                    CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> view.setPadding(
                        0,
                        0,
                        0,
                        16f.px
                    )

                    CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> view.setPadding(
                        0,
                        0,
                        16f.px,
                        0
                    )

                    CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> view.setPadding(
                        0,
                        16f.px,
                        0,
                        0
                    )

                    CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> view.setPadding(
                        16f.px,
                        0,
                        0,
                        0
                    )
                }
            }
            view.updateLayoutParams {
                if (settings.direction != CurrentReaderSettings.Directions.LEFT_TO_RIGHT && settings.direction != CurrentReaderSettings.Directions.RIGHT_TO_LEFT) {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 480f.px
                } else {
                    width = 480f.px
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        } else {
            val detector = GestureDetectorCompat(view.context, object : GesturesListener() {
                override fun onSingleClick(event: MotionEvent) =
                    activity.handleController(event = event)
            })
            view.findViewById<View>(R.id.imgProgCover).apply {
                val imageView =
                    view.findViewById<com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView>(
                        R.id.imgProgImageNoGestures
                    )
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                var downTime = 0L
                var startZoom = 1f
                var oneHandZoomActive = false
                setOnTouchListener { _, event ->
                    if (settings.oneHandZoom && imageView != null) {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                downTime = event.eventTime
                                startZoom = imageView.scale
                                oneHandZoomActive = false
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (!oneHandZoomActive) {
                                    val started =
                                        event.eventTime - downTime >= ONE_HAND_ZOOM_HOLD_MILLIS && abs(
                                            event.y - downY
                                        ) > touchSlop && abs(
                                            event.x - downX
                                        ) <= touchSlop * ONE_HAND_ZOOM_HORIZONTAL_TOLERANCE_MULTIPLIER
                                    if (started) {
                                        oneHandZoomActive = true
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        parent.requestDisallowInterceptTouchEvent(true)
                                    }
                                }

                                if (oneHandZoomActive) {
                                    val minScale = imageView.minScale
                                    val maxScale = imageView.maxScale
                                    val height = imageView.height.coerceAtLeast(1)
                                    val delta = (downY - event.y) / height
                                    val target = (
                                        startZoom + delta * (maxScale - minScale) *
                                                ONE_HAND_ZOOM_SENSITIVITY_MULTIPLIER
                                        )
                                        .coerceIn(minScale, maxScale)
                                    imageView.setScaleAndCenter(target, imageView.center)
                                    return@setOnTouchListener true
                                }
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (oneHandZoomActive) {
                                    oneHandZoomActive = false
                                    parent.requestDisallowInterceptTouchEvent(false)
                                    return@setOnTouchListener true
                                }
                            }
                        }
                    }
                    detector.onTouchEvent(event)
                    false
                }
                setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    val image = images.getOrNull(pos) ?: return@setOnLongClickListener false
                    activity.onImageLongClicked(pos, image, null) { dialog ->
                        activity.lifecycleScope.launch {
                            loadImage(pos, view)
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        dialog.dismiss()
                    }
                }
            }
        }
        activity.lifecycleScope.launch { loadImage(holder.bindingAdapterPosition, view) }
    }

    abstract fun isZoomed(): Boolean
    abstract fun setZoom(zoom: Float)

    abstract suspend fun loadImage(position: Int, parent: View): Boolean

    companion object {
        private const val ONE_HAND_ZOOM_HOLD_MILLIS = 200L
        private const val ONE_HAND_ZOOM_HORIZONTAL_TOLERANCE_MULTIPLIER = 3
        private const val ONE_HAND_ZOOM_SENSITIVITY_MULTIPLIER = 2f

        suspend fun Context.loadBitmapOld(
            link: FileUrl,
            transforms: List<BitmapTransformation>
        ): Bitmap? { //still used in some places
            return tryWithSuspend {
                withContext(Dispatchers.IO) {
                    Glide.with(this@loadBitmapOld)
                        .asBitmap()
                        .let {
                            if (link.url.startsWith("file://")) {
                                it.load(link.url)
                                    .skipMemoryCache(true)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                            } else {
                                it.load(GlideUrl(link.url) { link.headers })
                            }
                        }
                        .let {
                            if (transforms.isNotEmpty()) {
                                it.transform(*transforms.toTypedArray())
                            } else {
                                it
                            }
                        }
                        .submit()
                        .get()
                }
            }
        }

        suspend fun Context.loadBitmap(
            link: FileUrl,
            transforms: List<BitmapTransformation>
        ): Bitmap? {
            return tryWithSuspend {
                val mangaCache = uy.kohesive.injekt.Injekt.get<MangaCache>()
                withContext(Dispatchers.IO) {
                    Glide.with(this@loadBitmap)
                        .asBitmap()
                        .let {
                            val localFile = File(link.url)
                            if (localFile.exists()) {
                                it.load(localFile.absoluteFile)
                                    .skipMemoryCache(true)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                            } else if (link.url.startsWith("content://")) {
                                it.load(Uri.parse(link.url))
                                    .skipMemoryCache(true)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                            } else {
                                val cachedBitmap = mangaCache.get(link.url)?.let { imageData ->
                                    imageData.fetchAndProcessImage(imageData.page, imageData.source)
                                }
                                if (cachedBitmap != null) {
                                    it.load(cachedBitmap)
                                        .skipMemoryCache(true)
                                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                                } else {
                                    it.load(GlideUrl(link.url) { link.headers })
                                }
                            }
                        }
                        ?.let {
                            if (transforms.isNotEmpty()) {
                                it.transform(*transforms.toTypedArray())
                            } else {
                                it
                            }
                        }
                        ?.submit()
                        ?.get()
                }
            }
        }

        fun mergeBitmap(bitmap1: Bitmap, bitmap2: Bitmap, scale: Boolean = false): Bitmap {
            val height = if (bitmap1.height > bitmap2.height) bitmap1.height else bitmap2.height
            val (bit1, bit2) = if (!scale) bitmap1 to bitmap2 else {
                val width1 = bitmap1.width * height * 1f / bitmap1.height
                val width2 = bitmap2.width * height * 1f / bitmap2.height
                (Bitmap.createScaledBitmap(bitmap1, width1.toInt(), height, false)
                        to
                        Bitmap.createScaledBitmap(bitmap2, width2.toInt(), height, false))
            }
            val width = bit1.width + bit2.width
            val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(newBitmap)
            canvas.drawBitmap(bit1, 0f, (height * 1f - bit1.height) / 2, null)
            canvas.drawBitmap(bit2, bit1.width.toFloat(), (height * 1f - bit2.height) / 2, null)
            return newBitmap
        }
    }
}
