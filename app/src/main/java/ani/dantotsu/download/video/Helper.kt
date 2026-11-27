package ani.dantotsu.download.video

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.defaultHeaders
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.anime.AnimeDownloader
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.isWifiConnected
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
object Helper {
    fun startAnimeDownloadService(
        context: Context,
        title: String,
        episode: String,
        video: Video,
        subtitle: List<Pair<String, String>> = emptyList(),
        audio: List<Pair<String, String>> = emptyList(),
        sourceMedia: Media? = null,
        episodeImage: String? = null
    ) {
        if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !isWifiConnected(context)) {
            snackString(context.getString(R.string.download_wifi_only_warning))
            return
        }

        if (!isNotificationPermissionGranted(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is Activity) {
                ActivityCompat.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1
                )
            }
        }

        val animeDownloadTask = AnimeDownloaderService.AnimeDownloadTask(
            title,
            episode,
            video,
            subtitle,
            audio,
            sourceMedia,
            episodeImage
        )

        val downloadsManager = Injekt.get<DownloadsManager>()
        val downloadCheck = downloadsManager
            .queryDownload(title, episode, MediaType.ANIME)

        if (!downloadCheck && !AnimeDownloader.isDownloading(sourceMedia?.id ?: -1, episode)) {
            AnimeServiceDataSingleton.downloadQueue.offer(animeDownloadTask)
            val intent = Intent(context, AnimeDownloaderService::class.java)
            ContextCompat.startForegroundService(context, intent)
            AnimeServiceDataSingleton.isServiceRunning = true
        }
    }

    fun findBestVideoForDownload(
        videos: List<Video>,
        preferredResolutions: List<String>
    ): Video? {
        if (videos.isEmpty()) return null
        val preferredVideo = videos.firstOrNull { it.preferred }
        if (preferredVideo != null) return preferredVideo
        if (preferredResolutions.isEmpty()) return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()

        for (preferred in preferredResolutions) {
            val resNumber = Regex("""\d+""").find(preferred)?.value?.toIntOrNull()
            if (resNumber != null) {
                val match = videos.firstOrNull { it.quality == resNumber }
                if (match != null) return match
            }
            val cleanPreferred = preferred.lowercase().replace("p", "").trim()
            val matchFallback = videos.firstOrNull { video ->
                val note = video.extraNote?.lowercase() ?: ""
                val url = video.file.url.lowercase()
                note.contains(preferred.lowercase()) || note.contains(cleanPreferred) ||
                    url.contains("${cleanPreferred}p") || url.contains(cleanPreferred)
            }
            if (matchFallback != null) return matchFallback
        }

        return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()
    }

    private fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
}
