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
        var size = -1L
        var supportsRanges = false

        if (!forceSingle) {
            val probeHeaders = headers.newBuilder()
                .set("Range", "bytes=0-0")
                .build()
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
        }

        if (size > 0L) {
            totalBytesEstimate.addAndGet(size)
        }

        return if (!forceSingle && size > 0 && supportsRanges && threadCount > 1) {
            val partSize = size / threadCount
            fun partLength(i: Int): Long {
                return if (i == threadCount - 1) {
                    size - i * partSize
                } else {
                    partSize
                }
            }

            val downloadedParts = AtomicLongArray(threadCount)
            (0 until threadCount).forEach { i ->
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
                val downloaded = (0 until threadCount).sumOf { downloadedParts.get(it) }
                progress.report(downloaded.toFloat() / size)
            }
            report()

            coroutineScope {
                List(threadCount) { i ->
                    async {
                        val partFile = File(destDir, "$name-part$i")
                        if (partFile.exists() && partFile.length() == partLength(i)) {
                            return@async
                        }

                        val job = currentCoroutineContext().job
                        val listener = object : ProgressListener {
                            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                                job.ensureActive()
                                downloadedParts.accumulateAndGet(
                                    i,
                                    bytesRead.coerceAtMost(partLength(i)),
                                ) { a, b -> maxOf(a, b) }
                                report()
                            }
                        }

                        downloader.downloadFile(
                            url = url,
                            headersBuilder = { tmp ->
                                val existing = if (tmp.exists()) tmp.length() else 0L
                                val start = i * partSize + existing
                                val end = if (i == threadCount - 1) size - 1 else (i + 1) * partSize - 1
                                headers.newBuilder()
                                    .set("Range", "bytes=$start-$end")
                                    .build()
                            },
                            client = client,
                            listener = listener,
                            destDir = destDir,
                            fileName = "$name-part$i",
                        )
                    }
                }.joinAll()
            }

            progress.report(1f)
            val parts = (0 until threadCount).joinToString("|") { File(destDir, "$name-part$it").absolutePath }
            "-i \"concat:$parts\""
        } else {
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
            "-i \"${file.absolutePath}\""
        }
    }
}
