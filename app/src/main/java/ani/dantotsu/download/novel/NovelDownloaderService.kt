package ani.dantotsu.download.novel

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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.parsers.novel.LnReaderNovelParser
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.buffer
import okio.sink
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class NovelDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var isCurrentlyProcessing = false

    private val networkHelper = Injekt.get<NetworkHelper>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Novel Download Progress")
            setSmallIcon(R.drawable.ic_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(this, cancelReceiver, IntentFilter(ACTION_CANCEL_DOWNLOAD), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        NovelServiceDataSingleton.downloadQueue.clear()
        downloadJobs.clear()
        NovelServiceDataSingleton.isServiceRunning = false
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
            while (NovelServiceDataSingleton.downloadQueue.isNotEmpty()) {
                val task = NovelServiceDataSingleton.downloadQueue.poll()
                if (task != null) {
                    val job = launch { download(task) }
                    mutex.withLock { downloadJobs[task.chapter] = job }
                    job.join()
                    mutex.withLock { downloadJobs.remove(task.chapter) }
                    updateNotification()
                }
                if (NovelServiceDataSingleton.downloadQueue.isEmpty()) {
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        }
    }

    fun cancelDownload(chapter: String) {
        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                downloadJobs[chapter]?.cancel()
                downloadJobs.remove(chapter)
                NovelServiceDataSingleton.downloadQueue.removeAll { it.chapter == chapter }
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val text = if (NovelServiceDataSingleton.downloadQueue.size > 0)
            "Pending downloads: ${NovelServiceDataSingleton.downloadQueue.size}"
        else "All downloads completed"
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private suspend fun isEpubFile(urlString: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(urlString).head().build()
                networkHelper.client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    val contentDisposition = response.header("Content-Disposition")
                    contentType?.contains("application/epub+zip", ignoreCase = true) == true ||
                            contentDisposition?.contains(".epub") == true
                }
            } catch (e: Exception) {
                Logger.log("Error checking file type: ${e.message}")
                false
            }
        }
    }

    private fun isAlreadyDownloaded(urlString: String): Boolean =
        urlString.contains("file://")

    suspend fun download(task: DownloadTask) {
        if (task.lnReaderParser != null) {
            downloadHtmlChapter(task)
        } else {
            downloadEpub(task)
        }
    }

    private suspend fun downloadHtmlChapter(task: DownloadTask) {
        val parser = task.lnReaderParser ?: return
        try {
            withContext(Dispatchers.Main) {
                val notifi = hasNotificationPermission()
                broadcastDownloadStarted(task.originalLink)
                if (notifi) {
                    builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                }
                
                val html = withContext(Dispatchers.IO) {
                    parser.loadChapterHtml(task.downloadLink)
                }
                
                val directory = getSubDirectory(
                    this@NovelDownloaderService,
                    MediaType.NOVEL, false,
                    task.title, task.chapter
                ) ?: throw Exception("Directory not found")

                directory.findFile("0.epub")?.forceDelete(this@NovelDownloaderService)
                val file = directory.createFile("application/epub+zip", "0.epub")
                    ?: throw Exception("Could not create 0.epub")

                withContext(Dispatchers.IO) {
                    this@NovelDownloaderService.contentResolver.openOutputStream(file.uri)?.use { os ->
                        val epubBytes = HtmlToEpubUtils.createEpub(task.chapter, html)
                        os.write(epubBytes)
                    } ?: throw Exception("Could not open OutputStream")
                }

                task.coverUrl?.let {
                    getSubDirectory(this@NovelDownloaderService, MediaType.NOVEL, false, task.title)
                        ?.let { dir -> downloadImage(it, dir, "cover.jpg") }
                }

                val baseDirectory = getSubDirectory(
                    this@NovelDownloaderService, MediaType.NOVEL, false, task.title
                ) ?: throw Exception("Directory not found")
                saveMediaInfo(task, baseDirectory)

                downloadsManager.addDownload(DownloadedType(task.title, task.chapter, MediaType.NOVEL))

                builder.setContentText("${task.title} - ${task.chapter} Download complete")
                    .setProgress(0, 0, false)
                if (notifi) notificationManager.notify(NOTIFICATION_ID, builder.build())

                broadcastDownloadFinished(task.originalLink)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: Exception) {
            Logger.log("HTML chapter download failed: ${e.message}")
            snackString("Download failed: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.originalLink)
        }
    }

    private suspend fun downloadEpub(task: DownloadTask) {
        try {
            withContext(Dispatchers.Main) {
                val notifi = hasNotificationPermission()
                broadcastDownloadStarted(task.originalLink)
                if (notifi) {
                    builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                }

                if (!isEpubFile(task.downloadLink)) {
                    if (isAlreadyDownloaded(task.originalLink)) {
                        Logger.log("Already downloaded")
                        broadcastDownloadFinished(task.originalLink)
                        snackString("Already downloaded")
                        return@withContext
                    }
                    Logger.log("Download link is not an .epub file")
                    broadcastDownloadFailed(task.originalLink)
                    snackString("Download link is not an .epub file")
                    return@withContext
                }

                val baseDirectory = getSubDirectory(
                    this@NovelDownloaderService, MediaType.NOVEL, false, task.title
                ) ?: throw Exception("Directory not found")

                withContext(Dispatchers.IO) {
                    try {
                        val request = Request.Builder().url(task.downloadLink).build()
                        networkHelper.downloadClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful)
                                throw IOException("Failed to download file: ${response.message}")

                            val directory = getSubDirectory(
                                this@NovelDownloaderService, MediaType.NOVEL, false,
                                task.title, task.chapter
                            ) ?: throw Exception("Directory not found")

                            directory.findFile("0.epub")?.forceDelete(this@NovelDownloaderService)
                            val file = directory.createFile("application/epub+zip", "0.epub")
                                ?: throw Exception("File not created")

                            task.coverUrl?.let {
                                file.parentFile?.let { dir -> downloadImage(it, dir, "cover.jpg") }
                            }

                            val outputStream = this@NovelDownloaderService.contentResolver
                                .openOutputStream(file.uri)
                                ?: throw Exception("Could not open OutputStream")

                            val sink = outputStream.sink().buffer()
                            val body  = response.body
                            val total = body.contentLength()
                            var downloaded = 0L
                            var lastNotif = 0L; var lastBcast = 0L

                            body.source().use { source ->
                                while (true) {
                                    val read = source.read(sink.buffer, 8192)
                                    if (read == -1L) break
                                    downloaded += read
                                    sink.emit()

                                    if (downloaded - lastNotif >= 1024 * 1024) {
                                        withContext(Dispatchers.Main) {
                                            builder.setProgress(100, (downloaded * 100 / total).toInt(), false)
                                            if (notifi) notificationManager.notify(NOTIFICATION_ID, builder.build())
                                        }
                                        lastNotif = downloaded
                                    }
                                    if (downloaded - lastBcast >= 1024 * 256) {
                                        withContext(Dispatchers.Main) {
                                            broadcastDownloadProgress(task.originalLink, (downloaded * 100 / total).toInt())
                                        }
                                        lastBcast = downloaded
                                    }
                                }
                            }
                            sink.close()
                            if (file.length() < total * 0.95)
                                throw IOException("Failed to download file: ${response.message}")
                        }
                    } catch (e: Exception) {
                        Logger.log("Exception downloading epub: ${e.message}")
                        throw e
                    }
                }

                builder.setContentText("${task.title} - ${task.chapter} Download complete")
                    .setProgress(0, 0, false)
                if (notifi) notificationManager.notify(NOTIFICATION_ID, builder.build())

                saveMediaInfo(task, baseDirectory)
                downloadsManager.addDownload(DownloadedType(task.title, task.chapter, MediaType.NOVEL))
                broadcastDownloadFinished(task.originalLink)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: Exception) {
            Logger.log("Exception while downloading .epub: ${e.message}")
            snackString("Exception while downloading .epub: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.originalLink)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveMediaInfo(task: DownloadTask, directory: DocumentFile) {
        launchIO {
            directory.findFile("media.json")?.forceDelete(this@NovelDownloaderService)
            val file = directory.createFile("application/json", "media.json")
                ?: throw Exception("File not created")
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> { SChapterImpl() })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover  = media.cover?.let  { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }
                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    try {
                        file.openOutputStream(this@NovelDownloaderService, false).use { output ->
                            if (output == null) throw Exception("Output stream is null")
                            output.write(jsonString.toByteArray())
                        }
                    } catch (e: android.system.ErrnoException) {
                        e.printStackTrace()
                        Toast.makeText(this@NovelDownloaderService, "Error while saving: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK)
                    throw Exception("Server returned HTTP ${connection.responseCode}")
                directory.findFile(name)?.forceDelete(this@NovelDownloaderService)
                val file = directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@NovelDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { it.copyTo(output) }
                }
                file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NovelDownloaderService, "Exception saving $name: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(link: String) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_STARTED).apply { putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link) })

    private fun broadcastDownloadFinished(link: String) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_FINISHED).apply { putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link) })

    private fun broadcastDownloadFailed(link: String) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_FAILED).apply { putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link) })

    private fun broadcastDownloadProgress(link: String, progress: Int) =
        sendBroadcast(Intent(NovelReadFragment.ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(NovelReadFragment.EXTRA_NOVEL_LINK, link)
            putExtra("progress", progress)
        })

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD)
                intent.getStringExtra(EXTRA_CHAPTER)?.let { cancelDownload(it) }
        }
    }

    data class DownloadTask(
        val title: String,
        val chapter: String,
        val downloadLink: String,
        val originalLink: String,
        val sourceMedia: Media? = null,
        val coverUrl: String? = null,
        val retries: Int = 2,
        val lnReaderParser: ani.dantotsu.parsers.novel.LnReaderNovelParser? = null,
    )

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_CHAPTER = "extra_chapter"
    }
}

object NovelServiceDataSingleton {
    var downloadQueue: Queue<NovelDownloaderService.DownloadTask> = ConcurrentLinkedQueue()
    @Volatile var isServiceRunning: Boolean = false
}
