package ani.dantotsu.download.video

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import ani.dantotsu.media.anime.player.PlayerSubtitleManager
import ani.dantotsu.util.Logger
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeKit
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

enum class DownloadType { Hls, Dash, Direct }

data class DownloadTrack(val name: String, val ffmpegInput: String)

sealed interface TrackResult {
    val name: String
    data class Url(val url: String, override val name: String) : TrackResult
    data class Playlist(
        val type: DownloadType,
        val content: String,
        val fragments: List<DownloadFragment>,
        override val name: String,
    ) : TrackResult
}

sealed interface PlaylistResult {
    val subtitleTracks: List<TrackResult>
    val audioTracks: List<TrackResult>

    data class Url(
        val url: String,
        override val subtitleTracks: List<TrackResult>,
        override val audioTracks: List<TrackResult>,
    ) : PlaylistResult

    data class Content(
        val type: DownloadType,
        val content: String,
        val fragments: List<DownloadFragment>,
        override val subtitleTracks: List<TrackResult>,
        override val audioTracks: List<TrackResult>,
    ) : PlaylistResult
}

/**
 * Animiru-derived Fast DL -> Fast Remux downloader:
 * Multi-threaded Direct Range / Parallel HLS / DASH segment downloading followed by
 * zero-reencode Matroska remux (`-f matroska -c:a copy -c:v copy -c:s copy`).
 */
class FastVideoDownloader(
    private val context: Context,
    private val downloadedBytesCounter: AtomicLong,
    private val totalBytesEstimate: AtomicLong,
) {
    private val network: NetworkHelper by lazy { Injekt.get() }
    private val segmentDownloader = HttpSegmentDownloader(downloadedBytesCounter)
    private val directDownloader = DirectRangeDownloader(segmentDownloader, totalBytesEstimate)
    private val hlsDownloader = HlsPlaylistDownloader(context, segmentDownloader)
    private val dashDownloader = DashManifestDownloader(context, directDownloader, segmentDownloader)

    suspend fun downloadAndRemux(
        sessionId: Long,
        videoUrl: String,
        outputPath: String,
        headersMap: Map<String, String>,
        externalSubtitles: List<Pair<String, String>>,
        externalAudios: List<Pair<String, String>>,
        threadCount: Int = 4,
        onProgress: (percent: Int, durationMs: Double) -> Unit,
    ) {
        val tempDir = File(context.cacheDir, "fast_dl_$sessionId").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        try {
            val client = network.downloadClient
            val headers = headersMap.toHeaders()
            val isTorrent = videoUrl.contains("127.0.0.1") || videoUrl.contains("localhost")

            val type = getType(client, videoUrl, headers)
            val playlistResult = when (type) {
                DownloadType.Hls -> hlsDownloader.parsePlaylist(videoUrl, headers, client, "vid")
                DownloadType.Dash -> dashDownloader.parsePlaylist(videoUrl, headers, client)
                DownloadType.Direct -> PlaylistResult.Url(videoUrl, emptyList(), emptyList())
            }

            val allSubtitles = buildList {
                playlistResult.subtitleTracks.forEach { add(it) }
                externalSubtitles.forEachIndexed { idx, (subUrl, lang) ->
                    val resolved = if (subUrl.startsWith("http", true)) {
                        subUrl
                    } else {
                        PlayerSubtitleManager.resolveSubtitleUrl(subUrl, videoUrl)
                    }
                    add(TrackResult.Url(resolved, lang.ifBlank { "Subtitle ${idx + 1}" }))
                }
            }

            val allAudios = buildList {
                playlistResult.audioTracks.forEach { add(it) }
                externalAudios.forEachIndexed { idx, (audUrl, lang) ->
                    add(TrackResult.Url(audUrl, lang.ifBlank { "Audio ${idx + 1}" }))
                }
            }

            val aggregator = ProgressAggregator { pct ->
                // Leave headroom for remux phase (0..92% download, 92..99% remux)
                onProgress((pct * 0.92f).toInt().coerceIn(0, 92), 0.0)
            }
            val videoProgress = aggregator.register(VIDEO_WEIGHT)
            val audioProgress = allAudios.map { aggregator.register(AUDIO_WEIGHT) }
            val subProgress = allSubtitles.map { aggregator.register(SUBTITLE_WEIGHT) }

            val subDownloadTracks = allSubtitles.mapIndexedNotNull { i, track ->
                try {
                    val input = downloadItem(track, headers, client, subProgress[i], threadCount, tempDir, "sub$i", forceSingle = true)
                    DownloadTrack(track.name, input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("FastVideoDownloader: Skipping failed subtitle track ${track.name}: ${e.message}")
                    null
                }
            }

            val audDownloadTracks = allAudios.mapIndexedNotNull { i, track ->
                try {
                    val input = downloadItem(track, headers, client, audioProgress[i], threadCount, tempDir, "aud$i")
                    DownloadTrack(track.name, input)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.log("FastVideoDownloader: Skipping failed audio track ${track.name}: ${e.message}")
                    null
                }
            }

            val videoInput = when (playlistResult) {
                is PlaylistResult.Url -> when (type) {
                    DownloadType.Hls -> hlsDownloader.download(playlistResult.url, headers, client, videoProgress, threadCount, tempDir, "vid")
                    DownloadType.Dash -> dashDownloader.downloadTrack(headers, client, TrackResult.Url(playlistResult.url, "vid"), videoProgress, threadCount, tempDir, "vid")
                    DownloadType.Direct -> directDownloader.download(playlistResult.url, headers, client, videoProgress, threadCount, tempDir, "vid", forceSingle = isTorrent)
                }
                is PlaylistResult.Content -> when (playlistResult.type) {
                    DownloadType.Hls -> hlsDownloader.download(headers, client, playlistResult.content, playlistResult.fragments, videoProgress, threadCount, tempDir, "vid")
                    DownloadType.Dash -> dashDownloader.downloadTrack(headers, client, TrackResult.Playlist(DownloadType.Dash, playlistResult.content, playlistResult.fragments, "vid"), videoProgress, threadCount, tempDir, "vid")
                    DownloadType.Direct -> throw IllegalStateException("Content not supported for direct download")
                }
            }

            onProgress(93, 0.0)
            remuxToMatroska(
                videoInput = videoInput,
                subtitleTracks = subDownloadTracks,
                audioTracks = audDownloadTracks,
                outputPath = outputPath,
                onRemuxProgress = { pct, durMs ->
                    val scaled = (93 + pct * 0.06f).toInt().coerceIn(93, 99)
                    onProgress(scaled, durMs)
                },
            )
            onProgress(99, 0.0)
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    private suspend fun downloadItem(
        trackResult: TrackResult,
        headers: Headers,
        client: OkHttpClient,
        progress: ItemProgress,
        threadCount: Int,
        destDir: File,
        name: String,
        forceSingle: Boolean = false,
    ): String {
        return when (trackResult) {
            is TrackResult.Url -> {
                val url = trackResult.url
                if (url.startsWith("file", true) || url.startsWith("content", true)) {
                    progress.report(1f)
                    return "-allowed_extensions ALL -i \"$url\""
                }
                when (getType(client, url, headers)) {
                    DownloadType.Hls -> hlsDownloader.download(url, headers, client, progress, threadCount, destDir, name)
                    DownloadType.Dash -> dashDownloader.downloadTrack(headers, client, trackResult, progress, threadCount, destDir, name)
                    DownloadType.Direct -> directDownloader.download(url, headers, client, progress, threadCount, destDir, name, forceSingle)
                }
            }
            is TrackResult.Playlist -> {
                when (trackResult.type) {
                    DownloadType.Hls -> hlsDownloader.download(headers, client, trackResult.content, trackResult.fragments, progress, threadCount, destDir, name)
                    DownloadType.Dash -> dashDownloader.downloadTrack(headers, client, trackResult, progress, threadCount, destDir, name)
                    DownloadType.Direct -> throw IllegalStateException("Content not supported for direct download")
                }
            }
        }
    }

    private suspend fun getType(client: OkHttpClient, url: String, headers: Headers): DownloadType {
        if (!url.startsWith("http", ignoreCase = true)) {
            if (url.endsWith(".m3u8", true)) return DownloadType.Hls
            if (url.endsWith(".mpd", true)) return DownloadType.Dash
            return DownloadType.Direct
        }

        val path = url.toHttpUrl().encodedPath
        if (path.endsWith(".m3u8", true)) return DownloadType.Hls
        if (path.endsWith(".mpd", true)) return DownloadType.Dash
        if (url.endsWith(".vtt", true) || url.endsWith(".ass", true) || url.endsWith(".mkv", true)) return DownloadType.Direct

        val contentType = runCatching {
            val headHeaders = headers.newBuilder().set("Connection", "close").build()
            val headReq = Request.Builder().url(url).head().headers(headHeaders).build()
            client.newCall(headReq).awaitSuccess().use { it.header("Content-Type")?.lowercase() }
        }.getOrNull()

        if (contentType != null) {
            if (contentType.contains("mpegurl")) return DownloadType.Hls
            if (contentType.contains("dash+xml")) return DownloadType.Dash
            if (contentType.contains("video/x-matroska") || contentType.contains("video/mp4") || contentType.contains("video/webm")) {
                return DownloadType.Direct
            }
        }

        val probeHeaders = headers.newBuilder().set("Range", "bytes=0-1023").build()
        return flow {
            client.newCall(GET(url, probeHeaders)).awaitSuccess().use { resp ->
                val source = resp.body.source()
                source.request(1024)
                val len = minOf(source.buffer.size, 1024L)
                val text = source.buffer.peek().readUtf8(len).trim()
                when {
                    text.startsWith("#EXTM3U") -> emit(DownloadType.Hls)
                    text.startsWith("<") && text.contains("<MPD") -> emit(DownloadType.Dash)
                    else -> emit(DownloadType.Direct)
                }
            }
        }
            .retryWhen { _, attempt ->
                if (attempt < 2) {
                    delay((2L shl attempt.toInt()).seconds)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    private suspend fun remuxToMatroska(
        videoInput: String,
        subtitleTracks: List<DownloadTrack>,
        audioTracks: List<DownloadTrack>,
        outputPath: String,
        onRemuxProgress: (percent: Int, durationMs: Double) -> Unit,
    ) {
        val durationSec = probeDurationSeconds(videoInput)
        val subInputs = subtitleTracks.joinToString(" ") { it.ffmpegInput }
        val audInputs = audioTracks.joinToString(" ") { it.ffmpegInput }
        val subMaps = subtitleTracks.indices.joinToString(" ") { "-map ${it + 1}:s" }
        val audMaps = audioTracks.indices.joinToString(" ") { "-map ${it + 1 + subtitleTracks.size}:a" }
        val subMeta = subtitleTracks.mapIndexed { i, t -> "-metadata:s:s:$i \"title=${t.name.replace("\"", "'")}\"" }.joinToString(" ")
        val audMeta = audioTracks.mapIndexed { i, t -> "-metadata:s:a:$i \"title=${t.name.replace("\"", "'")}\"" }.joinToString(" ")

        val command = listOf(
            "-hide_banner",
            videoInput,
            subInputs,
            audInputs,
            "-map 0:v",
            audMaps,
            "-map 0:a?",
            subMaps,
            "-map 0:s? -map 0:t?",
            "-f matroska -c:a copy -c:v copy -c:s copy",
            subMeta,
            audMeta,
            "-y \"$outputPath\"",
        ).filter { it.isNotBlank() }.joinToString(" ")

        val args = FFmpegKitConfig.parseArguments(command)
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { completed ->
                    if (completed.returnCode?.isValueSuccess == true) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(
                            IllegalStateException("FFmpeg remux failed rc=${completed.returnCode}: ${completed.failStackTrace ?: completed.allLogsAsString}"),
                        )
                    }
                },
                { _ -> },
                { stats ->
                    val timeMs = stats.time.toDouble()
                    val pct = if (durationSec != null && durationSec > 0.0) {
                        ((timeMs / (durationSec * 1000.0)) * 100.0).toInt().coerceIn(0, 100)
                    } else 50
                    onRemuxProgress(pct, timeMs)
                },
            )
            cont.invokeOnCancellation {
                session.cancel()
            }
        }
    }

    private fun probeDurationSeconds(videoInput: String): Double? {
        return runCatching {
            val args = FFmpegKitConfig.parseArguments("-v error -hide_banner -show_entries format=duration -of csv=\"p=0\" $videoInput")
            val session = FFprobeKit.executeWithArguments(args)
            session.output?.lines()?.map { it.trim() }?.firstOrNull { it.toDoubleOrNull() != null }?.toDoubleOrNull()
        }.getOrNull()
    }

    companion object {
        private const val VIDEO_WEIGHT = 100.0
        private const val AUDIO_WEIGHT = 8.0
        private const val SUBTITLE_WEIGHT = 0.5
    }
}
