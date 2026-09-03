package ani.dantotsu.download.anime

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.defaultHeaders
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.anime.AnimeDownloaderService.AnimeDownloadTask.Companion.getTaskName
import ani.dantotsu.download.findValidName
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.anime.getEpisode
import ani.dantotsu.parsers.Video
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.SizeFormatter
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.edit


class AnimeDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var isCurrentlyProcessing = false
    private val currentTasks get() = AnimeServiceDataSingleton.currentTasks
    private val ffExtension = Injekt.get<DownloadAddonManager>().extension?.extension

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        if (ffExtension == null) {
            toast(getString(R.string.download_addon_not_found))
            stopSelf()
            return
        }
        notificationManager = NotificationManagerCompat.from(this)
        builder =
            NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADER_PROGRESS).apply {
                setContentTitle("Anime Download Progress")
                setSmallIcon(R.drawable.ic_download_24)
                priority = NotificationCompat.PRIORITY_DEFAULT
                setOnlyAlertOnce(true)
                setProgress(100, 0, false)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_DOWNLOAD),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        AnimeServiceDataSingleton.downloadQueue.clear()
        downloadJobs.clear()
        AnimeServiceDataSingleton.isServiceRunning = false
        unregisterReceiver(cancelReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        snackString("Download started")
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope.launch {
            mutex.withLock {
                if (!isCurrentlyProcessing) {
                    isCurrentlyProcessing = true
                    processQueue()
                    isCurrentlyProcessing = false
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        CoroutineScope(Dispatchers.Default).launch {
            val maxParallel = PrefManager.getVal<Int>(PrefName.MaxParallelDownloads).coerceIn(0, 10)
            val concurrency = if (maxParallel > 0) maxParallel else 1
            val semaphore = Semaphore(concurrency)
            val activeJobs = mutableListOf<Job>()

            while (AnimeServiceDataSingleton.downloadQueue.isNotEmpty()) {
                val task = AnimeServiceDataSingleton.downloadQueue.poll() ?: continue
                if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@AnimeDownloaderService)) {
                    broadcastDownloadFailed(task.episode, task.sourceMedia?.id)
                    withContext(Dispatchers.Main) {
                        snackString(getString(R.string.download_wifi_only_warning))
                    }
                    continue
                }
                val job = launch {
                    semaphore.withPermit {
                        currentTasks.add(task)
                        try {
                            download(task)
                        } finally {
                            mutex.withLock {
                                downloadJobs.remove(task.getTaskName())
                            }
                            currentTasks.remove(task)
                            updateNotification()
                        }
                    }
                }
                mutex.withLock {
                    downloadJobs[task.getTaskName()] = job
                }
                activeJobs.add(job)
            }
            activeJobs.joinAll()
            if (AnimeServiceDataSingleton.downloadQueue.isEmpty()) {
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            }
        }
    }

    @UnstableApi
    fun cancelDownload(taskName: String) {
        val tasks = mutableListOf<AnimeDownloadTask>()
        tasks.addAll(AnimeServiceDataSingleton.downloadQueue.filter { it.getTaskName() == taskName })
        tasks.addAll(currentTasks.filter { it.getTaskName() == taskName })

        // Mark canceled first so any in-flight publishProgress is dropped
        tasks.forEach { it.cancelled = true }

        tasks.forEach { task ->
            task.sourceMedia?.id?.let { mediaId ->
                AnimeDownloader.stopDownload(mediaId, task.episode)
                broadcastDownloadFailed(task.episode, mediaId)
            }
            // Cancel engine session (may still be -1 if cancel raced before executeFFMpeg)
            if (task.sessionId != -1L) {
                runCatching { ffExtension?.cancelDownload(task.sessionId) }
            }
        }

        currentTasks.removeAll { it.getTaskName() == taskName }
        AnimeServiceDataSingleton.downloadQueue.removeAll { it.getTaskName() == taskName }
        AnimeServiceDataSingleton.progress.remove(taskName)

        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                downloadJobs[taskName]?.cancel()
                downloadJobs.remove(taskName)
            }
            // Dismiss notification when nothing is left
            val stillBusy = currentTasks.isNotEmpty() ||
                AnimeServiceDataSingleton.downloadQueue.isNotEmpty() ||
                downloadJobs.isNotEmpty()
            if (!stillBusy) {
                notificationManager.cancel(NOTIFICATION_ID)
                withContext(Dispatchers.Main) {
                    stopSelf()
                }
            } else {
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val pendingDownloads = AnimeServiceDataSingleton.downloadQueue.size + currentTasks.size
        if (pendingDownloads <= 0 && downloadJobs.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun download(task: AnimeDownloadTask) {
        withContext(Dispatchers.IO) {
            if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@AnimeDownloaderService)) {
                task.sourceMedia?.id?.let { mediaId ->
                    broadcastDownloadFailed(task.episode, mediaId)
                }
                withContext(Dispatchers.Main) {
                    snackString(getString(R.string.download_wifi_only_warning))
                }
                return@withContext
            }
            try {
                val notifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        this@AnimeDownloaderService,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                builder.setContentText("Downloading ${getTaskName(task.title, task.episode)}")
                if (notifi) {
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }

                val baseOutputDir = getSubDirectory(
                    this@AnimeDownloaderService,
                    MediaType.ANIME,
                    false,
                    task.title
                ) ?: throw Exception("Failed to create output directory")
                val outputDir = getSubDirectory(
                    this@AnimeDownloaderService,
                    MediaType.ANIME,
                    false,
                    task.title,
                    task.episode
                ) ?: throw Exception("Failed to create output directory")

                val extension = ffExtension!!.getFileExtension()
                outputDir.findFile("${task.getTaskName().findValidName()}.${extension.first}")
                    ?.delete()

                val outputFile =
                    outputDir.createFile(
                        extension.second,
                        "${task.getTaskName()}.${extension.first}"
                    )
                        ?: throw Exception("Failed to create output file")

                val percent = java.util.concurrent.atomic.AtomicInteger(0)
                val lastPublishedPercent = java.util.concurrent.atomic.AtomicInteger(-1)
                val lastUiMs = java.util.concurrent.atomic.AtomicLong(0L)
                var ffTask = -1L
                val path = ffExtension.setDownloadPath(
                    this@AnimeDownloaderService,
                    outputFile.uri
                )
                if (!task.video.file.headers.containsKey("User-Agent")
                    && !task.video.file.headers.containsKey("user-agent")
                ) {
                    val newHeaders = task.video.file.headers.toMutableMap()
                    newHeaders["User-Agent"] = defaultHeaders["User-Agent"]!!
                    task.video.file.headers = newHeaders
                }

                fun publishProgress(force: Boolean = false) {
                    // Drop all UI/notification updates after user cancel
                    if (task.cancelled) return
                    val p = percent.get().coerceIn(0, 99)
                    // Broadcast to UI only when percent changes (or forced on complete)
                    if (!force && p == lastPublishedPercent.get()) return
                    lastPublishedPercent.set(p)
                    lastUiMs.set(SystemClock.elapsedRealtime())
                    AnimeServiceDataSingleton.progress[task.getTaskName()] = p
                    builder.setProgress(100, p, false)
                    builder.setContentText(
                        "${getTaskName(task.title, task.episode)} · $p%"
                    )
                    val sessionId = ffTask
                    val addonDownloaded =
                        if (sessionId != -1L) ffExtension.getDownloadedBytes(sessionId) else -1L
                    val addonEstimated =
                        if (sessionId != -1L) ffExtension.getEstimatedTotalBytes(sessionId) else -1L
                    val downloadedBytes =
                        if (addonDownloaded > 0L) addonDownloaded else outputFile.length()
                    val estimatedTotalBytes =
                        if (addonEstimated > 0L) addonEstimated
                        else SizeFormatter.estimateTotalBytesByPercent(downloadedBytes, p)
                    broadcastDownloadProgress(
                        task.episode,
                        p,
                        task.sourceMedia?.id,
                        downloadedBytes,
                        estimatedTotalBytes
                    )
                    if (notifi) {
                        try {
                            notificationManager.notify(NOTIFICATION_ID, builder.build())
                        } catch (_: SecurityException) {
                            // POST_NOTIFICATIONS may be missing on some devices mid-download
                        }
                    }
                }

                if (task.cancelled) return@withContext

                // Downloader sends percent * 1000 via statCallback
                ffTask = ffExtension.executeFFMpeg(
                    task.video.file.url,
                    path,
                    task.video.file.headers,
                    task.subtitle,
                    task.audio,
                ) {
                    if (task.cancelled) return@executeFFMpeg
                    if (it > 0) {
                        percent.set((it / 1000.0).toInt().coerceIn(0, 99))
                        publishProgress(force = false)
                    }
                }
                task.sessionId = ffTask
                // If canceled while executeFFMpeg was starting, stop engine immediately
                if (task.cancelled) {
                    ffExtension.cancelDownload(ffTask)
                    return@withContext
                }
                currentTasks.find { it.getTaskName() == task.getTaskName() }?.sessionId =
                    ffTask

                saveMediaInfo(task, baseOutputDir)

                // Wait for completion; UI progress only on percent change via statCallback
                while (!task.cancelled && ffExtension.getState(ffTask) != "COMPLETED") {
                    if (ffExtension.getState(ffTask) == "FAILED") {
                        Logger.log("Download failed")
                        builder.setContentText(
                            "${
                                getTaskName(
                                    task.title,
                                    task.episode
                                )
                            } Download failed"
                        )
                        if (notifi) {
                            withContext(Dispatchers.Main) {
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }
                        }
                        toast("${getTaskName(task.title, task.episode)} Download failed")
                        Logger.log("Download failed: ${ffExtension.getStackTrace(ffTask)}")
                        downloadsManager.removeDownload(
                            DownloadedType(
                                task.title,
                                task.episode,
                                MediaType.ANIME,
                            ),
                            false
                        ) {}
                        Injekt.get<CrashlyticsInterface>().logException(
                            Exception(
                                "Anime Download failed:" +
                                        " ${getTaskName(task.title, task.episode)}" +
                                        " url: ${task.video.file.url}" +
                                        " title: ${task.title}" +
                                        " episode: ${task.episode}"
                            )
                        )
                        currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                        broadcastDownloadFailed(task.episode, task.sourceMedia?.id)
                        break
                    }
                    kotlinx.coroutines.delay(300.milliseconds)
                }
                if (task.cancelled) {
                    ffExtension.cancelDownload(ffTask)
                    return@withContext
                }
                publishProgress(force = true)
                if (ffExtension.getState(ffTask) == "COMPLETED") {
                    if (task.cancelled) return@withContext
                    if (ffExtension.hadError(ffTask)) {
                        Logger.log("Download failed")
                        builder.setContentText(
                            "${
                                getTaskName(
                                    task.title,
                                    task.episode
                                )
                            } Download failed"
                        )
                        if (notifi) {
                            withContext(Dispatchers.Main) {
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }
                        }
                        snackString("${getTaskName(task.title, task.episode)} Download failed")
                        downloadsManager.removeDownload(
                            DownloadedType(
                                task.title,
                                task.episode,
                                MediaType.ANIME
                            ),
                            false
                        ) {}
                        Injekt.get<CrashlyticsInterface>().logException(
                            Exception(
                                "Anime Download failed:" +
                                        " ${getTaskName(task.title, task.episode)}" +
                                        " url: ${task.video.file.url}" +
                                        " title: ${task.title}" +
                                        " episode: ${task.episode}"
                            )
                        )
                        currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                        broadcastDownloadFailed(task.episode, task.sourceMedia?.id)
                        return@withContext
                    }
                    Logger.log("Download completed")
                    builder.setContentText(
                        "${
                            getTaskName(
                                task.title,
                                task.episode
                            )
                        } Download completed"
                    )
                    if (notifi) {
                        withContext(Dispatchers.Main) {
                            notificationManager.notify(NOTIFICATION_ID, builder.build())
                        }
                    }
                    snackString("${getTaskName(task.title, task.episode)} Download completed")
                    PrefManager.getAnimeDownloadPreferences().edit {
                        putString(
                            task.getTaskName(),
                            task.video.file.url
                        )
                    }
                    val downloadType = DownloadedType(
                        task.title,
                        task.episode,
                        MediaType.ANIME,
                    )
                    if (task.cancelled) return@withContext
                    downloadsManager.addDownload(downloadType)
                    val size = downloadsManager.getSize(downloadType)
                    currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                    broadcastDownloadFinished(task.episode, task.sourceMedia?.id, size)
                } else throw Exception("Download failed")

            } catch (e: kotlinx.coroutines.CancellationException) {
                task.cancelled = true
                Logger.log("Download cancelled: ${task.getTaskName()}")
                throw e
            } catch (e: Exception) {
                if (task.cancelled || e.message?.contains("Coroutine was cancelled") == true) {
                    task.cancelled = true
                    Logger.log("Download cancelled: ${task.getTaskName()}")
                } else {
                    Logger.log("Exception while downloading file: ${e.message}")
                    snackString("Exception while downloading file: ${e.message}")
                    e.printStackTrace()
                    Injekt.get<CrashlyticsInterface>().logException(e)
                    if (!task.cancelled) {
                        broadcastDownloadFailed(task.episode, task.sourceMedia?.id)
                    }
                }
            } finally {
                // Stop orphan engine work after user cancel
                if (task.cancelled && task.sessionId != -1L) {
                    runCatching { ffExtension?.cancelDownload(task.sessionId) }
                }
                task.sourceMedia?.id?.let { mediaId ->
                    AnimeDownloader.stopDownload(mediaId, task.episode)
                }
                currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                AnimeServiceDataSingleton.progress.remove(task.getTaskName())
            }
        }
    }

    private fun CoroutineScope.saveMediaInfo(task: AnimeDownloadTask, directory: DocumentFile) {
        launch(Dispatchers.IO) {
            try {
                directory.findFile("media.json")?.forceDelete(this@AnimeDownloaderService)
                val file = directory.createFile("application/json", "media.json")
                    ?: return@launch
                val episodeDirectory =
                    getSubDirectory(
                        this@AnimeDownloaderService,
                        MediaType.ANIME,
                        false,
                        task.title,
                        task.episode
                    )
                        ?: return@launch

                val gson = GsonBuilder()
                    .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                        SChapterImpl() // Provide an instance of SChapterImpl
                    })
                    .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> {
                        SAnimeImpl() // Provide an instance of SAnimeImpl
                    })
                    .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> {
                        SEpisodeImpl() // Provide an instance of SEpisodeImpl
                    })
                    .create()
                val mediaJson = gson.toJson(task.sourceMedia)
                val media = gson.fromJson(mediaJson, Media::class.java)
                if (media != null) {
                    media.cover = media.cover?.let {
                        ensureImage(it, directory, "cover.jpg")
                    }
                    media.banner = media.banner?.let {
                        ensureImage(it, directory, "banner.jpg")
                    }
                    if (task.episodeImage != null) {
                        media.anime?.episodes?.getEpisode(task.episode)?.let { episode ->
                            episode.thumb = ensureImage(
                                task.episodeImage,
                                episodeDirectory,
                                "episodeImage.jpg"
                            )?.let { FileUrl(it) }
                        }
                    }

                    val jsonString = gson.toJson(media)
                    withContext(Dispatchers.Main) {
                        try {
                            file.openOutputStream(this@AnimeDownloaderService, false).use { output ->
                                if (output == null) throw Exception("Output stream is null")
                                output.write(jsonString.toByteArray())
                            }
                        } catch (e: android.system.ErrnoException) {
                            e.printStackTrace()
                            Toast.makeText(
                                this@AnimeDownloaderService,
                                "Error while saving: ${e.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log("Failed to save media info: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Reuse an existing image on disk when present; only hit the network if missing.
     * Avoids re-downloading cover/banner for every episode of the same title.
     */
    private suspend fun ensureImage(
        url: String,
        directory: DocumentFile,
        name: String
    ): String? = withContext(Dispatchers.IO) {
        val existing = directory.findFile(name)
        if (existing != null && existing.isFile && existing.length() > 0L) {
            return@withContext existing.uri.toString()
        }
        downloadImage(url, directory, name)
    }

    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                directory.findFile(name)?.forceDelete(this@AnimeDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@AnimeDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return@withContext file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AnimeDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadFinished(episodeNumber: String, mediaId: Int?, size: Double?) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_FINISHED).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
            putExtra("mediaId", mediaId)
            putExtra("size", size)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(episodeNumber: String, mediaId: Int?) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_FAILED).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
            putExtra("mediaId", mediaId)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadProgress(
        episodeNumber: String,
        progress: Int,
        mediaId: Int?,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
            putExtra("progress", progress)
            putExtra("mediaId", mediaId)
            putExtra(AnimeWatchFragment.EXTRA_DOWNLOADED_BYTES, downloadedBytes)
            putExtra(AnimeWatchFragment.EXTRA_ESTIMATED_TOTAL_BYTES, estimatedTotalBytes)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        @androidx.annotation.OptIn(UnstableApi::class)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val taskName = intent.getStringExtra(EXTRA_TASK_NAME)
                taskName?.let {
                    cancelDownload(it)
                }
            }
        }
    }


    data class AnimeDownloadTask(
        val title: String,
        val episode: String,
        val video: Video,
        val subtitle: List<Pair<String, String>> = emptyList(),
        val audio: List<Pair<String, String>> = emptyList(),
        val sourceMedia: Media? = null,
        val episodeImage: String? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
        var sessionId: Long = -1,
        @Volatile var cancelled: Boolean = false
    ) {
        fun getTaskName(): String {
            return "${title.replace("/", "")}/${episode.replace("/", "")}"
        }

        companion object {
            fun getTaskName(title: String, episode: String): String {
                return "${title.replace("/", "")}/${episode.replace("/", "")}"
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_TASK_NAME = "extra_task_name"
    }
}

object AnimeServiceDataSingleton {
    var video: Video? = null
    var downloadQueue: Queue<AnimeDownloaderService.AnimeDownloadTask> = ConcurrentLinkedQueue()
    val currentTasks = java.util.Collections.synchronizedList(mutableListOf<AnimeDownloaderService.AnimeDownloadTask>())
    val progress = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    var isServiceRunning: Boolean = false
}

object AnimeDownloader{
    private val activeDownloads = mutableMapOf<Int, MutableSet<String>>()

    fun startDownload(mediaId: Int, episodeNumber: String) {
        activeDownloads.getOrPut(mediaId) { mutableSetOf() }.add(episodeNumber)
    }
    fun stopDownload(mediaId: Int, episodeNumber: String) {
        activeDownloads[mediaId]?.remove(episodeNumber)
        if (activeDownloads[mediaId]?.isEmpty() == true) {
            activeDownloads.remove(mediaId)
        }
    }
    fun isDownloading(mediaId: Int, episodeNumber: String): Boolean {
        return activeDownloads[mediaId]?.contains(episodeNumber) == true
    }
}
