package ani.dantotsu.media.anime.player

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.media.MediaScannerConnection
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerScreenshotManager(
    private val activity: Activity,
    private val playerView: PlayerView
) {
    fun takeScreenshot(
        animeTitle: String? = null,
        episodeNum: String? = null,
        onComplete: ((Bitmap?, Uri?) -> Unit)? = null
    ) {
        val surfaceView = playerView.videoSurfaceView
        if (surfaceView == null) {
            toast(activity.getString(R.string.video_not_ready))
            onComplete?.invoke(null, null)
            return
        }

        captureBitmap(surfaceView) { bitmap ->
            if (bitmap != null) {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val cleanTitle = animeTitle?.replace(Regex("[^a-zA-Z0-9.-]"), "_")?.take(30) ?: "Dantotsu"
                val epSuffix = if (!episodeNum.isNullOrBlank()) "_EP$episodeNum" else ""
                val fileName = "${cleanTitle}${epSuffix}_${timeStamp}.png"

                val uri = saveBitmapToGallery(activity, bitmap, fileName)
                if (uri != null) {
                    toast(activity.getString(R.string.screenshot_saved))
                } else {
                    snackString("Failed to save screenshot", activity)
                }
                onComplete?.invoke(bitmap, uri)
            } else {
                snackString("Failed to capture screenshot", activity)
                onComplete?.invoke(null, null)
            }
        }
    }

    private fun captureBitmap(surface: View, callback: (Bitmap?) -> Unit) {
        try {
            if (surface is TextureView) {
                val bmp = surface.bitmap
                callback(bmp)
                return
            }

            if (surface is SurfaceView) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val width = surface.width
                    val height = surface.height
                    if (width <= 0 || height <= 0) {
                        callback(null)
                        return
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(
                        surface,
                        bitmap,
                        { copyResult ->
                            if (copyResult == PixelCopy.SUCCESS) {
                                activity.runOnUiThread { callback(bitmap) }
                            } else {
                                Logger.log("PixelCopy failed with code: $copyResult")
                                activity.runOnUiThread { callback(null) }
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                    return
                }
            }

            // Fallback: draw view hierarchy if supported
            if (surface.width > 0 && surface.height > 0) {
                val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                surface.draw(canvas)
                callback(bmp)
            } else {
                callback(null)
            }
        } catch (e: Exception) {
            Logger.log("Screenshot capture error: ${e.message}")
            callback(null)
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Dantotsu")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null

                resolver.openOutputStream(imageUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
                imageUri
            } else {
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Dantotsu"
                )
                if (!picturesDir.exists()) picturesDir.mkdirs()
                val imageFile = File(picturesDir, fileName)
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                MediaScannerConnection.scanFile(context, arrayOf(imageFile.absolutePath), arrayOf("image/png"), null)
                Uri.fromFile(imageFile)
            }
        } catch (e: Exception) {
            Logger.log("Save screenshot error: ${e.message}")
            null
        }
    }
}
