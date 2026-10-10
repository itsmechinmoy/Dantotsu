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

        // If bitmap is HARDWARE, copy to software config so getPixels is allowed
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

        // Find the non-white area by scanning from the edges
        var left = 0
        var top = 0
        var right = width - 1
        var bottom = height - 1

        // Scan from the left edge
        for (x in 0 until width) {
            var stop = false
            for (y in 0 until height) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    left = x
                    stop = true
                    break
                }
            }
            if (stop) break
        }

        // Scan from the right edge
        for (x in width - 1 downTo left) {
            var stop = false
            for (y in 0 until height) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    right = x
                    stop = true
                    break
                }
            }
            if (stop) break
        }

        // Scan from the top edge
        for (y in 0 until height) {
            var stop = false
            for (x in 0 until width) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    top = y
                    stop = true
                    break
                }
            }
            if (stop) break
        }

        // Scan from the bottom edge
        for (y in height - 1 downTo top) {
            var stop = false
            for (x in 0 until width) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    bottom = y
                    stop = true
                    break
                }
            }
            if (stop) break
        }

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        if (cropWidth <= 0 || cropHeight <= 0 || (cropWidth == width && cropHeight == height)) {
            return toTransform
        }

        // Crop the bitmap to the non-white area
        return Bitmap.createBitmap(
            safeBitmap,
            left,
            top,
            cropWidth,
            cropHeight
        )
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(
            "RemoveBordersTransformation(${white}_$threshHold)".toByteArray()
        )
    }

    private fun isPixelNotWhite(pixel: Int): Boolean {
        val brightness = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        return if (white) brightness < (255 - threshHold) else brightness > threshHold
    }
}
