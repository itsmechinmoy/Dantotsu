package ani.dantotsu.download.video

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

internal fun resolveUrl(base: String, relative: String): String =
    URI(base).resolve(relative).toString()

data class DownloadFragment(
    val name: String,
    val url: String,
    val start: Long? = null,
    val end: Long? = null,
)

class HttpSegmentDownloader(
    private val downloadedBytesCounter: AtomicLong,
) {
    suspend fun downloadFragment(
        headers: Headers?,
        client: OkHttpClient,
        fragment: DownloadFragment,
        listener: ProgressListener,
        destDir: File,
    ): File {
        return downloadFile(
            url = fragment.url,
            headersBuilder = { file ->
                val headersBuilder = (headers ?: Headers.EMPTY).newBuilder()
                if (fragment.start != null) {
                    val range = buildString {
                        append(fragment.start + file.length())
                        append('-')
                        fragment.end?.let { append(it) }
                    }
                    headersBuilder.set("Range", "bytes=$range")
                }
                headersBuilder.build()
            },
            client = client,
            listener = listener,
            destDir = destDir,
            fileName = fragment.name,
        )
    }

    suspend fun downloadFile(
        url: String,
        headersBuilder: (File) -> Headers,
        client: OkHttpClient,
        listener: ProgressListener,
        destDir: File,
        fileName: String,
    ): File {
        val existingFile = File(destDir, fileName)
        if (existingFile.exists() && existingFile.length() > 0L) {
            val fileSize = existingFile.length()
            downloadedBytesCounter.addAndGet(fileSize)
            listener.update(fileSize, fileSize, true)
            return existingFile
        }

        return flow {
            val tmpFile = File(destDir, "$fileName.tmp")
            val headers = headersBuilder(tmpFile)

            try {
                val existingSize = if (tmpFile.exists()) tmpFile.length() else 0L
                val lastReported = AtomicLong(existingSize)
                val trackedListener = object : ProgressListener {
                    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                        val prev = lastReported.getAndSet(bytesRead)
                        val delta = (bytesRead - prev).coerceAtLeast(0L)
                        if (delta > 0L) downloadedBytesCounter.addAndGet(delta)
                        listener.update(bytesRead, contentLength, done)
                    }
                }

                client.newCachelessCallWithProgress(
                    request = GET(url, headers),
                    listener = trackedListener,
                    existingSize = existingSize,
                )
                    .awaitSuccess()
                    .use { response ->
                        val append = response.code == 206
                        FileOutputStream(tmpFile, append).use { out ->
                            response.body.byteStream().copyTo(out, bufferSize = 65536)
                        }
                        if (existingFile.exists()) existingFile.delete()
                        if (!tmpFile.renameTo(existingFile)) {
                            tmpFile.copyTo(existingFile, overwrite = true)
                            tmpFile.delete()
                        }
                    }
            } catch (e: HttpException) {
                if (e.code == 416) {
                    tmpFile.delete()
                }
                throw e
            }
            emit(existingFile)
        }
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()).seconds)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    suspend fun downloadPlaylist(
        headers: Headers,
        client: OkHttpClient,
        playlist: String,
        fragments: List<DownloadFragment>,
        progress: ItemProgress,
        threadCount: Int,
        destDir: File,
        name: String,
        ffmpegName: String,
        ffmpegType: String,
    ): String {
        val existing = destDir.listFiles().orEmpty().map { it.name }.toHashSet()
        val fragmentQueue = ConcurrentLinkedQueue(fragments.filter { it.name !in existing })

        val totalItems = fragments.size
        val workerCount = threadCount.coerceIn(1, fragmentQueue.size.coerceAtLeast(1))

        val finished = AtomicInteger(totalItems - fragmentQueue.size)
        val downloading = ConcurrentHashMap<String, Float>()

        fun report() {
            if (totalItems == 0) {
                progress.report(1f)
                return
            }
            val prog = (finished.get() + downloading.values.sum()) / totalItems
            progress.report(prog)
        }

        report()

        coroutineScope {
            List(workerCount) {
                launch {
                    val job = currentCoroutineContext().job
                    while (isActive) {
                        val fragment = fragmentQueue.poll() ?: break
                        downloadFragment(
                            headers = headers,
                            client = client,
                            fragment = fragment,
                            listener = object : ProgressListener {
                                override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                                    job.ensureActive()
                                    if (contentLength > 0) {
                                        val frac = (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                                        downloading[fragment.name] = frac
                                        report()
                                    }
                                }
                            },
                            destDir = destDir,
                        )
                        downloading.remove(fragment.name)
                        finished.incrementAndGet()
                        report()
                    }
                }
            }.joinAll()
        }

        val indexFile = File(destDir, "$name-$ffmpegName")
        indexFile.writeText(playlist)
        progress.report(1f)

        return listOf(
            "-extension_picky 0",
            "-allowed_extensions ALL",
            "-f",
            ffmpegType,
            "-i",
            "\"${indexFile.absolutePath}\"",
        ).joinToString(" ")
    }
}
