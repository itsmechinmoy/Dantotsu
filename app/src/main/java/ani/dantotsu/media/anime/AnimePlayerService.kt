package ani.dantotsu.media.anime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ani.dantotsu.R

@OptIn(UnstableApi::class)
class AnimePlayerService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "anime_player_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.video),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(channelId)
            .setChannelName(R.string.video)
            .build()
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeSession?.let { session ->
            if (!sessions.contains(session)) {
                addSession(session)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return activeSession
    }

    override fun onDestroy() {
        activeSession = null
        super.onDestroy()
    }

    companion object {
        var activeSession: MediaSession? = null

        fun start(context: Context, session: MediaSession) {
            activeSession = session
            val intent = Intent(context, AnimePlayerService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            activeSession = null
            val intent = Intent(context, AnimePlayerService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
