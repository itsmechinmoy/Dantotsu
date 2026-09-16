package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.view.View
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.settings.CurrentReaderSettings.Directions.LEFT_TO_RIGHT
import ani.dantotsu.settings.CurrentReaderSettings.Layouts.PAGED
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation

class DualPageAdapter(
    activity: MangaReaderActivity,
    chapter: MangaChapter,
    nextChapter: MangaChapter? = null,
    prevChapter: MangaChapter? = null
) : ImageAdapter(activity, chapter, nextChapter, prevChapter) {

    override fun buildInitialItems(chap: MangaChapter, nextChap: MangaChapter?, prevChap: MangaChapter?) {
        items.clear()
        if (hasTransition() && settings.layout != PAGED) {
            items.add(ReaderItem.Transition(chap, prevChap, isLoading = false, isPrevious = true))
        }
        val dualPages = chap.dualPages()
        val totalPages = dualPages.size
        dualPages.forEachIndexed { index, pair ->
            items.add(ReaderItem.DualPage(pair.first, pair.second, chap, index + 1, totalPages))
        }

        if (hasTransition()) {
            val isLoading = nextChap != null && nextChap.images().isEmpty()
            items.add(ReaderItem.Transition(chap, nextChap, isLoading = isLoading, isPrevious = false))
            if (nextChap != null && nextChap.images().isNotEmpty()) {
                val nextDual = nextChap.dualPages()
                nextDual.forEachIndexed { index, pair ->
                    items.add(ReaderItem.DualPage(pair.first, pair.second, nextChap, index + 1, nextDual.size))
                }
            }
        }
    }

    override fun appendChapter(nextChap: MangaChapter, afterNextChap: MangaChapter?) {
        val alreadyHas = items.any { it is ReaderItem.DualPage && it.chapter.uniqueNumber() == nextChap.uniqueNumber() }
        if (alreadyHas) return

        val newDual = nextChap.dualPages()
        if (newDual.isEmpty()) return

        val transitionIndex = items.indexOfLast {
            it is ReaderItem.Transition && it.toChapter?.uniqueNumber() == nextChap.uniqueNumber()
        }

        if (transitionIndex != -1) {
            val trans = items[transitionIndex] as ReaderItem.Transition
            trans.isLoading = false
            notifyItemChanged(transitionIndex)

            val insertPos = transitionIndex + 1
            val newItems = mutableListOf<ReaderItem>()
            newDual.forEachIndexed { index, pair ->
                newItems.add(ReaderItem.DualPage(pair.first, pair.second, nextChap, index + 1, newDual.size))
            }
            if (hasTransition()) {
                val nextLoading = afterNextChap != null && afterNextChap.images().isEmpty()
                newItems.add(ReaderItem.Transition(nextChap, afterNextChap, isLoading = nextLoading))
            }
            items.addAll(insertPos, newItems)
            notifyItemRangeInserted(insertPos, newItems.size)
        } else {
            val start = items.size
            val newItems = mutableListOf<ReaderItem>()
            if (hasTransition()) {
                val prevChap = (items.lastOrNull() as? ReaderItem.DualPage)?.chapter ?: initialChapter
                newItems.add(ReaderItem.Transition(prevChap, nextChap, isLoading = false))
            }
            newDual.forEachIndexed { index, pair ->
                newItems.add(ReaderItem.DualPage(pair.first, pair.second, nextChap, index + 1, newDual.size))
            }
            if (hasTransition()) {
                val nextLoading = afterNextChap != null && afterNextChap.images().isEmpty()
                newItems.add(ReaderItem.Transition(nextChap, afterNextChap, isLoading = nextLoading))
            }
            items.addAll(newItems)
            notifyItemRangeInserted(start, newItems.size)
        }
    }

    override fun prependChapter(prevChap: MangaChapter, beforePrevChap: MangaChapter?): Int {
        val alreadyHas = items.any { it is ReaderItem.DualPage && it.chapter.uniqueNumber() == prevChap.uniqueNumber() }
        if (alreadyHas) return 0

        val newDual = prevChap.dualPages()
        if (newDual.isEmpty()) return 0

        val newItems = mutableListOf<ReaderItem>()
        if (hasTransition()) {
            val prevLoading = beforePrevChap != null && beforePrevChap.images().isEmpty()
            newItems.add(ReaderItem.Transition(prevChap, beforePrevChap, isLoading = prevLoading, isPrevious = true))
        }
        val totalPages = newDual.size
        newDual.forEachIndexed { index, pair ->
            newItems.add(ReaderItem.DualPage(pair.first, pair.second, prevChap, index + 1, totalPages))
        }

        val transitionIndex = items.indexOfFirst {
            it is ReaderItem.Transition && it.isPrevious && it.toChapter?.uniqueNumber() == prevChap.uniqueNumber()
        }

        return if (transitionIndex != -1) {
            val oldTrans = items[transitionIndex] as ReaderItem.Transition
            items[transitionIndex] = ReaderItem.Transition(prevChap, oldTrans.fromChapter, isLoading = false, isPrevious = false)
            items.addAll(transitionIndex, newItems)
            notifyItemRangeInserted(transitionIndex, newItems.size)
            notifyItemChanged(transitionIndex + newItems.size)
            newItems.size
        } else {
            if (hasTransition()) {
                val nextChap = (items.firstOrNull() as? ReaderItem.DualPage)?.chapter ?: initialChapter
                newItems.add(ReaderItem.Transition(prevChap, nextChap, isLoading = false, isPrevious = false))
            }
            items.addAll(0, newItems)
            notifyItemRangeInserted(0, newItems.size)
            newItems.size
        }
    }

    override suspend fun loadBitmap(position: Int, parent: View): Bitmap? {
        val dualItem = items.getOrNull(position) as? ReaderItem.DualPage ?: return null
        val img1 = dualItem.first
        val link1 = img1.url
        if (link1.url.isEmpty()) return null

        val img2 = dualItem.second
        val link2 = img2?.url
        if (link2?.url?.isEmpty() == true) return null

        val transforms1 = mutableListOf<BitmapTransformation>()
        val parserTransformation1 = activity.getTransformation(img1)
        if (parserTransformation1 != null) transforms1.add(parserTransformation1)
        val transforms2 = mutableListOf<BitmapTransformation>()
        if (img2 != null) {
            val parserTransformation2 = activity.getTransformation(img2)
            if (parserTransformation2 != null) transforms2.add(parserTransformation2)
        }

        if (settings.cropBorders) {
            transforms1.add(RemoveBordersTransformation(true, settings.cropBorderThreshold))
            transforms1.add(RemoveBordersTransformation(false, settings.cropBorderThreshold))
            if (img2 != null) {
                transforms2.add(RemoveBordersTransformation(true, settings.cropBorderThreshold))
                transforms2.add(RemoveBordersTransformation(false, settings.cropBorderThreshold))
            }
        }

        val bitmap1 = activity.loadBitmap(link1, transforms1) ?: return null
        val bitmap2 = if (link2 != null) {
            val b2 = activity.loadBitmap(link2, transforms2)
            if (b2 == null) {
                if (!bitmap1.isRecycled) bitmap1.recycle()
                return null
            }
            b2
        } else null

        return if (bitmap2 != null) {
            val merged = if (settings.direction != LEFT_TO_RIGHT)
                mergeBitmap(bitmap2, bitmap1)
            else mergeBitmap(bitmap1, bitmap2)
            if (bitmap1 != merged && !bitmap1.isRecycled) bitmap1.recycle()
            if (bitmap2 != merged && !bitmap2.isRecycled) bitmap2.recycle()
            merged
        } else bitmap1
    }
}
