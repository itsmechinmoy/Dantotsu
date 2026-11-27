package ani.dantotsu.download.video

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

class DirectRangeDownloader(
    private val downloader: HttpSegmentDownloader,
    private val totalBytesEstimate: AtomicLong,
) {
    private data class RangeProbe(val size: Long, val supportsRanges: Boolean)

    suspend fun download(
        url: String,
        headers: Headers,
        client: OkHttpClient,
        progress: ItemProgress,
        threadCount: Int,
        destDir: File,
        name: String,
        forceSingle: Boolean = false,
    ): String {
        val probe = if (forceSingle) RangeProbe(-1L, false) else probeRange(url, headers, client)
        if (probe.size > 0L) {
            totalBytesEstimate.addAndGet(probe.size)
        }

        return if (!forceSingle && probe.size > 0L && probe.supportsRanges && threadCount > 1) {
            downloadMultiPart(url, headers, client, progress, threadCount, destDir, name, probe.size)
        } else {
            downloadSinglePart(url, headers, client, progress, destDir, name)
        }
    }

    private suspend fun probeRange(url: String, headers: Headers, client: OkHttpClient): RangeProbe {
        var size = -1L
        var supportsRanges = false
        val probeHeaders = headers.newBuilder().set("Range", "bytes=0-0").build()
        runCatching {
            client.newCall(GET(url, probeHeaders)).awaitSuccess().use { resp ->
                supportsRanges = resp.code == 206
                val contentRange = resp.header("Content-Range")
                size = if (contentRange != null) {
                    contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                } else {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
            }
        }
        return RangeProbe(size, supportsRanges)
    }

    private suspend fun downloadMultiPart(
        url: String,
        headers: Headers,
        client: OkHttpClient,
        progress: ItemProgress,
        threadCount: Int,
        destDir: File,
        name: String,
        size: Long,
    ): String {
        val partSize = size / threadCount
        fun partLength(i: Int): Long = if (i == threadCount - 1) size - i * partSize else partSize

        val downloadedParts = AtomicLongArray(threadCount)
        for (i in 0 until threadCount) {
            val partLen = partLength(i)
            val finished = File(destDir, "$name-part$i").exists()
            val downloadedSize = if (finished) {
                partLen
            } else {
                File(destDir, "$name-part$i.tmp").takeIf { it.exists() }?.length() ?: 0L
            }
            downloadedParts.set(i, downloadedSize.coerceIn(0L, partLen))
        }

        fun report() {
            var downloaded = 0L
            for (i in 0 until threadCount) {
                downloaded += downloadedParts.get(i)
            }
            progress.report(downloaded.toFloat() / size)
        }
        report()

        coroutineScope {
            List(threadCount) { i ->
                async {
                    val partLen = partLength(i)
                    val partFile = File(destDir, "$name-part$i")
                    if (partFile.exists() && partFile.length() == partLen) {
                        return@async
                    }
                    downloadPartWorker(
                        url = url,
                        headers = headers,
                        client = client,
                        destDir = destDir,
                        fileName = "$name-part$i",
                        partStart = i * partSize,
                        partEnd = if (i == threadCount - 1) size - 1 else (i + 1) * partSize - 1,
                        partLength = partLen,
                    ) { bytesRead ->
                        downloadedParts.accumulateAndGet(i, bytesRead) { a, b -> maxOf(a, b) }
                        report()
                    }
                }
            }.joinAll()
        }

        progress.report(1f)
        val parts = (0 until threadCount).joinToString("|") { File(destDir, "$name-part$it").absolutePath }
        return "-i \"concat:$parts\""
    }

    private suspend fun downloadPartWorker(
        url: String,
        headers: Headers,
        client: OkHttpClient,
        destDir: File,
        fileName: String,
        partStart: Long,
        partEnd: Long,
        partLength: Long,
        onBytes: (Long) -> Unit,
    ) {
        val job = currentCoroutineContext().job
        val listener = object : ProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                job.ensureActive()
                onBytes(bytesRead.coerceAtMost(partLength))
            }
        }
        downloader.downloadFile(
            url = url,
            headersBuilder = { tmp ->
                val existing = if (tmp.exists()) tmp.length() else 0L
                headers.newBuilder()
                    .set("Range", "bytes=${partStart + existing}-$partEnd")
                    .build()
            },
            client = client,
            listener = listener,
            destDir = destDir,
            fileName = fileName,
        )
    }

    private suspend fun downloadSinglePart(
        url: String,
        headers: Headers,
        client: OkHttpClient,
        progress: ItemProgress,
        destDir: File,
        name: String,
    ): String {
        val job = currentCoroutineContext().job
        val file = downloader.downloadFile(
            url = url,
            headersBuilder = { headers },
            client = client,
            listener = object : ProgressListener {
                override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                    job.ensureActive()
                    if (contentLength > 0) {
                        totalBytesEstimate.compareAndSet(0L, contentLength)
                        progress.report((bytesRead.toFloat() / contentLength).coerceIn(0f, 1f))
                    }
                }
            },
            destDir = destDir,
            fileName = name,
        )

        progress.report(1f)
        return "-i \"${file.absolutePath}\""
    }
}
