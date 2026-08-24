package ani.dantotsu.widgets.continue_widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.util.BitmapUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContinueWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { appWidgetId ->
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "ContinueWidgetPrefs"
        private const val KEY_ACTIVE_TITLE = "active_title"
        private const val KEY_ACTIVE_COVER = "active_cover"
        private const val KEY_ACTIVE_DETAIL = "active_detail"
        private const val KEY_ACTIVE_HEADER = "active_header"
        private const val KEY_ACTIVE_ICON_RES = "active_icon_res"
        private const val KEY_IS_ACTIVE = "is_active"

        private const val KEY_LAST_WATCHED_TITLE = "last_watched_title"
        private const val KEY_LAST_WATCHED_COVER = "last_watched_cover"
        private const val KEY_LAST_WATCHED_DETAIL = "last_watched_detail"

        private const val KEY_LAST_READ_TITLE = "last_read_title"
        private const val KEY_LAST_READ_COVER = "last_read_cover"
        private const val KEY_LAST_READ_DETAIL = "last_read_detail"

        fun updatePlaybackState(
            context: Context,
            title: String?,
            coverUrl: String?,
            detail: String?,
            isExiting: Boolean
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (!isExiting && !title.isNullOrEmpty()) {
                editor.putBoolean(KEY_IS_ACTIVE, true)
                editor.putString(KEY_ACTIVE_TITLE, title)
                editor.putString(KEY_ACTIVE_COVER, coverUrl)
                editor.putString(KEY_ACTIVE_DETAIL, detail ?: "Playing")
                editor.putString(KEY_ACTIVE_HEADER, "CURRENTLY WATCHING")
                editor.putInt(KEY_ACTIVE_ICON_RES, R.drawable.ic_round_play_circle_24)

                // Update Last Watched
                editor.putString(KEY_LAST_WATCHED_TITLE, title)
                editor.putString(KEY_LAST_WATCHED_COVER, coverUrl)
                editor.putString(KEY_LAST_WATCHED_DETAIL, detail ?: "Resume Episode")
            } else {
                editor.putBoolean(KEY_IS_ACTIVE, false)
            }
            editor.apply()
            notifyWidgets(context)
        }

        fun updateReadingState(
            context: Context,
            title: String?,
            coverUrl: String?,
            detail: String?,
            isExiting: Boolean
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (!isExiting && !title.isNullOrEmpty()) {
                editor.putBoolean(KEY_IS_ACTIVE, true)
                editor.putString(KEY_ACTIVE_TITLE, title)
                editor.putString(KEY_ACTIVE_COVER, coverUrl)
                editor.putString(KEY_ACTIVE_DETAIL, detail ?: "Reading")
                editor.putString(KEY_ACTIVE_HEADER, "CURRENTLY READING")
                editor.putInt(KEY_ACTIVE_ICON_RES, R.drawable.ic_round_menu_book_24)

                // Update Last Read
                editor.putString(KEY_LAST_READ_TITLE, title)
                editor.putString(KEY_LAST_READ_COVER, coverUrl)
                editor.putString(KEY_LAST_READ_DETAIL, detail ?: "Resume Chapter")
            } else {
                editor.putBoolean(KEY_IS_ACTIVE, false)
            }
            editor.apply()
            notifyWidgets(context)
        }

        fun notifyWidgets(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(
                        ComponentName(context, ContinueWidget::class.java)
                    )
                    appWidgetIds.forEach { appWidgetId ->
                        updateWidget(context, appWidgetManager, appWidgetId)
                    }
                } catch (_: Exception) {}
            }
        }

        private fun getRoundedCroppedBitmap(
            bitmap: Bitmap,
            targetWidth: Int,
            targetHeight: Int,
            cornerRadiusDp: Float,
            density: Float
        ): Bitmap {
            val cornerRadiusPx = cornerRadiusDp * density
            val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val scale: Float
            var dx = 0f
            var dy = 0f

            if (bitmap.width * targetHeight > targetWidth * bitmap.height) {
                scale = targetHeight.toFloat() / bitmap.height.toFloat()
                dx = (targetWidth - bitmap.width * scale) * 0.5f
            } else {
                scale = targetWidth.toFloat() / bitmap.width.toFloat()
                dy = (targetHeight - bitmap.height * scale) * 0.5f
            }

            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)

            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(matrix)
            paint.shader = shader

            val rect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

            return output
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)

            val headerText: String
            val titleText: String
            val detailText: String
            val coverUrl: String?
            val logoIconRes: Int

            if (isActive) {
                headerText = prefs.getString(KEY_ACTIVE_HEADER, "CURRENTLY ACTIVE") ?: "CURRENTLY ACTIVE"
                titleText = prefs.getString(KEY_ACTIVE_TITLE, "Dantotsu") ?: "Dantotsu"
                detailText = prefs.getString(KEY_ACTIVE_DETAIL, "In Progress") ?: "In Progress"
                coverUrl = prefs.getString(KEY_ACTIVE_COVER, null)
                logoIconRes = prefs.getInt(KEY_ACTIVE_ICON_RES, R.drawable.ic_round_play_circle_24)
            } else {
                val lastWatched = prefs.getString(KEY_LAST_WATCHED_TITLE, null)
                val lastRead = prefs.getString(KEY_LAST_READ_TITLE, null)

                if (!lastWatched.isNullOrEmpty()) {
                    headerText = "LAST WATCHED"
                    titleText = lastWatched
                    detailText = prefs.getString(KEY_LAST_WATCHED_DETAIL, "Tap to resume") ?: "Tap to resume"
                    coverUrl = prefs.getString(KEY_LAST_WATCHED_COVER, null)
                    logoIconRes = R.drawable.ic_round_play_circle_24
                } else if (!lastRead.isNullOrEmpty()) {
                    headerText = "LAST READ"
                    titleText = lastRead
                    detailText = prefs.getString(KEY_LAST_READ_DETAIL, "Tap to resume") ?: "Tap to resume"
                    coverUrl = prefs.getString(KEY_LAST_READ_COVER, null)
                    logoIconRes = R.drawable.ic_round_menu_book_24
                } else {
                    headerText = "DANTOTSU"
                    titleText = "Nothing active"
                    detailText = "Open Dantotsu to start"
                    coverUrl = null
                    logoIconRes = R.drawable.ic_dantotsu_round
                }
            }

            val widgetPrefs = context.getSharedPreferences("ani.dantotsu.widgets.UpcomingWidget", Context.MODE_PRIVATE)
            val backgroundColor = widgetPrefs.getInt("background_color", Color.parseColor("#80000000"))
            val backgroundFade = widgetPrefs.getInt("background_fade", Color.parseColor("#00000000"))
            val titleTextColor = widgetPrefs.getInt("title_text_color", Color.WHITE)
            val subtitleTextColor = widgetPrefs.getInt("countdown_text_color", Color.WHITE)

            val gradientDrawable = ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.linear_gradient_black,
                null
            ) as GradientDrawable
            gradientDrawable.colors = intArrayOf(backgroundColor, backgroundFade)
            gradientDrawable.cornerRadius = 0f
            val backgroundBitmap = gradientDrawable.toBitmap(720, 360)

            val views = RemoteViews(context.packageName, R.layout.widget_continue).apply {
                setImageViewBitmap(R.id.widget_background, backgroundBitmap)
                setTextViewText(R.id.widget_status_header, headerText)
                setTextViewText(R.id.widget_title, titleText)
                setTextViewText(R.id.widget_subtitle, detailText)

                setTextColor(R.id.widget_title, titleTextColor)
                setTextColor(R.id.widget_status_header, subtitleTextColor)
                setTextColor(R.id.widget_subtitle, subtitleTextColor)
                setImageViewResource(R.id.widget_logo, logoIconRes)

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setOnClickPendingIntent(R.id.widgetRootContainer, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)

            if (!coverUrl.isNullOrEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val rawBitmap = BitmapUtil.downloadImageAsBitmap(coverUrl)
                    if (rawBitmap != null) {
                        val density = context.resources.displayMetrics.density
                        val targetW = (56 * density).toInt().coerceAtLeast(1)
                        val targetH = (76 * density).toInt().coerceAtLeast(1)
                        val roundedBitmap = getRoundedCroppedBitmap(rawBitmap, targetW, targetH, 12f, density)
                        withContext(Dispatchers.Main) {
                            views.setImageViewBitmap(R.id.widget_cover, roundedBitmap)
                            appWidgetManager.updateAppWidget(appWidgetId, views)
                        }
                    }
                }
            }
        }
    }
}
