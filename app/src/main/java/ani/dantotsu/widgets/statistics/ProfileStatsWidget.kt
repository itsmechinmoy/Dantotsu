package ani.dantotsu.widgets.statistics

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.BitmapUtil.downloadImageAsBitmap
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO

class ProfileStatsWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            context.getSharedPreferences(getPrefsName(appWidgetId), Context.MODE_PRIVATE).edit().clear().apply()
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
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(getPrefsName(appWidgetId), Context.MODE_PRIVATE)
            val backgroundColor = prefs.getInt(PREF_BACKGROUND_COLOR, Color.parseColor("#80000000"))
            val backgroundFade = prefs.getInt(PREF_BACKGROUND_FADE, Color.parseColor("#00000000"))
            val titleTextColor = prefs.getInt(PREF_TITLE_TEXT_COLOR, Color.WHITE)
            val statsTextColor = prefs.getInt(PREF_STATS_TEXT_COLOR, Color.WHITE)

            val gradientDrawable = ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.linear_gradient_black,
                null
            ) as GradientDrawable
            gradientDrawable.colors = intArrayOf(backgroundColor, backgroundFade)
            gradientDrawable.cornerRadius = 0f
            val backgroundBitmap = gradientDrawable.toBitmap(720, 360)

            launchIO {
                val userPref = PrefManager.getVal(PrefName.AnilistUserId, "")
                if (userPref.isNotEmpty()) {
                    val lastUpdate = prefs.getLong("last_update", 0)
                    if (System.currentTimeMillis() - lastUpdate > 1000 * 60 * 60 * 4 || !prefs.contains("user_name")) {
                        val respond = Anilist.query.getUserProfile(userPref.toInt())
                        respond?.data?.user?.let { user ->
                            prefs.edit()
                                .putLong("last_update", System.currentTimeMillis())
                                .putString("user_name", user.name)
                                .putString("avatar_url", user.avatar?.medium ?: "")
                                .putInt("anime_count", user.statistics.anime.count)
                                .putInt("episodes_watched", user.statistics.anime.episodesWatched)
                                .putInt("manga_count", user.statistics.manga.count)
                                .putInt("chapters_read", user.statistics.manga.chaptersRead)
                                .apply()
                            
                            renderWidget(context, appWidgetManager, appWidgetId, backgroundBitmap, userPref, titleTextColor, statsTextColor,
                                user.name, user.avatar?.medium, user.statistics.anime.count, user.statistics.anime.episodesWatched,
                                user.statistics.manga.count, user.statistics.manga.chaptersRead)
                        } ?: showLoginCascade(context, appWidgetManager, appWidgetId, backgroundBitmap)
                    } else {
                        val userName = prefs.getString("user_name", "") ?: ""
                        val avatarUrl = prefs.getString("avatar_url", "")
                        val animeCount = prefs.getInt("anime_count", 0)
                        val episodesWatched = prefs.getInt("episodes_watched", 0)
                        val mangaCount = prefs.getInt("manga_count", 0)
                        val chaptersRead = prefs.getInt("chapters_read", 0)
                        
                        renderWidget(context, appWidgetManager, appWidgetId, backgroundBitmap, userPref, titleTextColor, statsTextColor,
                            userName, avatarUrl, animeCount, episodesWatched, mangaCount, chaptersRead)
                    }
                } else showLoginCascade(context, appWidgetManager, appWidgetId, backgroundBitmap)
            }
        }

        private suspend fun renderWidget(
            context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int,
            backgroundBitmap: android.graphics.Bitmap, userPref: String,
            titleTextColor: Int, statsTextColor: Int,
            userName: String, avatarUrl: String?,
            animeCount: Int, episodesWatched: Int,
            mangaCount: Int, chaptersRead: Int
        ) {
            withContext(Dispatchers.Main) {
                fun buildViews(): RemoteViews =
                    RemoteViews(context.packageName, R.layout.statistics_widget).apply {
                        setImageViewBitmap(R.id.backgroundView, backgroundBitmap)
                        
                        setOnClickPendingIntent(
                            R.id.userAvatar,
                            PendingIntent.getActivity(
                                context,
                                1,
                                Intent(context, ProfileStatsConfigure::class.java).apply {
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                        setTextColor(R.id.userLabel, titleTextColor)
                        setTextColor(R.id.topLeftItem, titleTextColor)
                        setTextColor(R.id.topLeftLabel, statsTextColor)
                        setTextColor(R.id.topRightItem, titleTextColor)
                        setTextColor(R.id.topRightLabel, statsTextColor)
                        setTextColor(R.id.bottomLeftItem, titleTextColor)
                        setTextColor(R.id.bottomLeftLabel, statsTextColor)
                        setTextColor(R.id.bottomRightItem, titleTextColor)
                        setTextColor(R.id.bottomRightLabel, statsTextColor)
                        
                        avatarUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                            val avatarBitmap = downloadImageAsBitmap(url)
                            setImageViewBitmap(R.id.userAvatar, avatarBitmap)
                        }
                        
                        setTextViewText(R.id.userLabel, context.getString(R.string.user_stats, userName))
                        setTextViewText(R.id.topLeftItem, animeCount.toString())
                        setTextViewText(R.id.topLeftLabel, context.getString(R.string.anime_watched))
                        setTextViewText(R.id.topRightItem, episodesWatched.toString())
                        setTextViewText(R.id.topRightLabel, context.getString(R.string.episodes_watched_n))
                        setTextViewText(R.id.bottomLeftItem, mangaCount.toString())
                        setTextViewText(R.id.bottomLeftLabel, context.getString(R.string.manga_read))
                        setTextViewText(R.id.bottomRightItem, chaptersRead.toString())
                        setTextViewText(R.id.bottomRightLabel, context.getString(R.string.chapters_read_n))
                        
                        val intent = Intent(context, ProfileActivity::class.java)
                            .putExtra("userId", userPref.toInt())
                        val pendingIntent = PendingIntent.getActivity(
                            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
                        )
                        setOnClickPendingIntent(R.id.widgetContainer, pendingIntent)
                    }

                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(
                        mapOf(
                            SizeF(0f, 0f) to buildViews(),
                            SizeF(200f, 100f) to buildViews()
                        )
                    )
                } else {
                    buildViews()
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private suspend fun showLoginCascade(
            context: Context, 
            appWidgetManager: AppWidgetManager, 
            appWidgetId: Int,
            backgroundBitmap: android.graphics.Bitmap
        ) {
            withContext(Dispatchers.Main) {
                val views = RemoteViews(context.packageName, R.layout.statistics_widget).apply {
                    setImageViewBitmap(R.id.backgroundView, backgroundBitmap)
                    setTextViewText(R.id.topLeftItem, "")
                    setTextViewText(R.id.topLeftLabel, context.getString(R.string.please))
                    setTextViewText(R.id.topRightItem, "")
                    setTextViewText(R.id.topRightLabel, context.getString(R.string.log_in))
                    setTextViewText(R.id.bottomLeftItem, context.getString(R.string.or_join))
                    setTextViewText(R.id.bottomLeftLabel, "")
                    setTextViewText(R.id.bottomRightItem, context.getString(R.string.anilist))
                    setTextViewText(R.id.bottomRightLabel, "")
                    val intent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 0, intent, PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widgetContainer, pendingIntent)
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        fun getPrefsName(appWidgetId: Int) = "ani.dantotsu.widgets.Statistics.${appWidgetId}"

        const val PREF_BACKGROUND_COLOR = "background_color"
        const val PREF_BACKGROUND_FADE = "background_fade"
        const val PREF_TITLE_TEXT_COLOR = "title_text_color"
        const val PREF_STATS_TEXT_COLOR = "stats_text_color"
    }
}