package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

class RemoveBordersTransformation(private val white: Boolean, private val threshHold: Int) :
    BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        if (width <= 0 || height <= 0) return toTransform

        val safeBitmap = if (toTransform.config == Bitmap.Config.HARDWARE) {
            toTransform.copy(Bitmap.Config.ARGB_8888, false) ?: return toTransform
        } else {
            toTransform
        }

        val pixels = IntArray(width * height)
        try {
            safeBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } catch (_: Exception) {
            return toTransform
        }

        val left = findLeftBorder(pixels, width, height)
        val right = findRightBorder(pixels, width, height, left)
        val top = findTopBorder(pixels, width, height)
        val bottom = findBottomBorder(pixels, width, height, top)

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        if (cropWidth <= 0 || cropHeight <= 0 || (cropWidth == width && cropHeight == height)) {
            return toTransform
        }

        return Bitmap.createBitmap(
            safeBitmap,
            left,
            top,
            cropWidth,
            cropHeight
        )
    }

    private fun findLeftBorder(pixels: IntArray, width: Int, height: Int): Int {
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (isPixelNotWhite(pixels[y * width + x])) return x
            }
        }
        return 0
    }

    private fun findRightBorder(pixels: IntArray, width: Int, height: Int, minLeft: Int): Int {
        for (x in width - 1 downTo minLeft) {
            for (y in 0 until height) {
                if (isPixelNotWhite(pixels[y * width + x])) return x
            }
        }
        return width - 1
    }

    private fun findTopBorder(pixels: IntArray, width: Int, height: Int): Int {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (isPixelNotWhite(pixels[y * width + x])) return y
            }
        }
        return 0
    }

    private fun findBottomBorder(pixels: IntArray, width: Int, height: Int, minTop: Int): Int {
        for (y in height - 1 downTo minTop) {
            for (x in 0 until width) {
                if (isPixelNotWhite(pixels[y * width + x])) return y
            }
        }
        return height - 1
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(
            "RemoveBordersTransformation(${white}_$threshHold)".toByteArray()
        )
    }

    private fun isPixelNotWhite(pixel: Int): Boolean {
        val brightness = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        return if (white) brightness < 255 - threshHold else brightness > threshHold
    }
}
