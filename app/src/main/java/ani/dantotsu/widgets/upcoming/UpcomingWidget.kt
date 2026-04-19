package ani.dantotsu.widgets.upcoming

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import ani.dantotsu.MainActivity
import ani.dantotsu.R

class UpcomingWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putLong(LAST_UPDATE, 0)
                    .putString(PREF_SERIALIZED_MEDIA, "")
                    .apply()
                
                val appWidgetManager = AppWidgetManager.getInstance(context)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetListView)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val rv = updateAppWidget(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, rv)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context?,
        appWidgetManager: AppWidgetManager?,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        if (context != null && appWidgetManager != null) {
            val views = updateAppWidget(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetId: Int): RemoteViews {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val backgroundColor = prefs.getInt(PREF_BACKGROUND_COLOR, Color.parseColor("#80000000"))
            val backgroundFade = prefs.getInt(PREF_BACKGROUND_FADE, Color.parseColor("#00000000"))
            val titleTextColor = prefs.getInt(PREF_TITLE_TEXT_COLOR, Color.WHITE)
            val countdownTextColor = prefs.getInt(PREF_COUNTDOWN_TEXT_COLOR, Color.WHITE)

            val intent = Intent(context, UpcomingRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

            val intentTemplate = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("fromWidget", true)
            }

            val refreshIntent = Intent(context, UpcomingWidget::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val gradientDrawable = ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.linear_gradient_black,
                null
            ) as GradientDrawable
            gradientDrawable.colors = intArrayOf(backgroundColor, backgroundFade)
            gradientDrawable.cornerRadius = 0f
            val backgroundBitmap = gradientDrawable.toBitmap(720, 360)

            fun buildViews(): RemoteViews = RemoteViews(context.packageName, R.layout.upcoming_widget).apply {
                setImageViewBitmap(R.id.backgroundView, backgroundBitmap)
                setTextColor(R.id.text_show_title, titleTextColor)
                setTextColor(R.id.text_show_countdown, countdownTextColor)
                setTextColor(R.id.widgetTitle, titleTextColor)
                setInt(R.id.refreshButton, "setColorFilter", titleTextColor)
                setOnClickPendingIntent(R.id.refreshButton, refreshPendingIntent)
                setRemoteAdapter(R.id.widgetListView, intent)
                setEmptyView(R.id.widgetListView, R.id.empty_view)
                setPendingIntentTemplate(
                    R.id.widgetListView,
                    PendingIntent.getActivity(
                        context,
                        0,
                        intentTemplate,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                )
                setOnClickPendingIntent(
                    R.id.logoView,
                    PendingIntent.getActivity(
                        context,
                        1,
                        Intent(context, UpcomingWidgetConfigure::class.java).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    mapOf(
                        SizeF(0f, 0f) to buildViews(),
                        SizeF(200f, 100f) to buildViews()
                    )
                )
            } else {
                buildViews()
            }
        }

        const val PREFS_NAME = "ani.dantotsu.widgets.UpcomingWidget"
        const val PREF_BACKGROUND_COLOR = "background_color"
        const val PREF_BACKGROUND_FADE = "background_fade"
        const val PREF_TITLE_TEXT_COLOR = "title_text_color"
        const val PREF_COUNTDOWN_TEXT_COLOR = "countdown_text_color"
        const val PREF_SERIALIZED_MEDIA = "serialized_media"
        const val LAST_UPDATE = "last_update"
        const val ACTION_REFRESH = "ani.dantotsu.widgets.upcoming.ACTION_REFRESH"
    }
}

internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    val views = UpcomingWidget.updateAppWidget(context, appWidgetId)
    appWidgetManager.updateAppWidget(appWidgetId, views)
    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetListView)
}
