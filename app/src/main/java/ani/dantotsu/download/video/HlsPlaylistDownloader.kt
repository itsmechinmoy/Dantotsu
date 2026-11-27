package ani.dantotsu.download.video

import android.content.Context
import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File

class HlsPlaylistDownloader(
    private val context: Context,
    private val downloader: HttpSegmentDownloader,
) {
    private class Variant(
        val url: String,
        val bandwidth: Long?,
        val resolution: Long?,
        val audioGroup: String?,
        val subtitleGroup: String?,
    )

    private class Media(
        val type: String,
        val group: String,
        val name: String?,
        val lang: String?,
        val url: String?,
    )

    suspend fun parsePlaylist(
        url: String,
        headers: Headers,
        client: OkHttpClient,
        name: String,
    ): PlaylistResult {
        val playlistContent = when {
            url.startsWith("http", ignoreCase = true) -> client.newCall(GET(url, headers)).awaitSuccess().body.string()
            url.startsWith("file", ignoreCase = true) || url.startsWith("content", ignoreCase = true) -> {
                context.contentResolver.openInputStream(url.toUri())?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Failed to read from file $url")
            }
            else -> File(url).takeIf { it.exists() }?.readText()
                ?: throw IllegalArgumentException("Unexpected input: $url")
        }

        return if (playlistContent.contains("#EXT-X-STREAM-INF")) {
            val variants = mutableListOf<Variant>()
            val media = mutableListOf<Media>()
            var pending: Map<String, String>? = null

            playlistContent.lineSequence().map { it.trim() }.forEach { line ->
                if (line.isBlank()) return@forEach
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        pending = parseAttrs(line.substringAfter(':'))
                    }
                    line.startsWith("#EXT-X-MEDIA:") -> {
                        val a = parseAttrs(line.substringAfter(':'))
                        media += Media(
                            type = a["TYPE"].orEmpty(),
                            group = a["GROUP-ID"].orEmpty(),
                            name = a["NAME"],
                            lang = a["LANGUAGE"],
                            url = a["URI"]?.let { resolveUrl(url, it) },
                        )
                    }
                    line.startsWith("#") -> {}
                    else -> pending?.let { a ->
                        variants += Variant(
                            url = resolveUrl(url, line),
                            bandwidth = a["BANDWIDTH"]?.toLongOrNull(),
                            resolution = a["RESOLUTION"]?.substringAfter('x')?.toLongOrNull(),
                            audioGroup = a["AUDIO"],
                            subtitleGroup = a["SUBTITLES"],
                        )
                        pending = null
                    }
                }
            }

            if (variants.isEmpty()) throw IllegalStateException("Master playlist does not contain any video streams")
            val variant = variants.maxByOrNull { it.bandwidth ?: it.resolution ?: 0L } ?: variants.last()

            fun tracks(type: String, group: String?) = media
                .filter { it.type == type && it.group == group && it.url != null }
                .map {
                    val trackName = buildString {
                        it.name?.let { s ->
                            append(s)
                            append(" ")
                        }
                        it.lang?.let { s -> append("($s)") }
                    }.trim().ifBlank { "Track" }
                    TrackResult.Url(url = it.url!!, name = trackName)
                }

            PlaylistResult.Url(
                url = variant.url,
                subtitleTracks = tracks("SUBTITLES", variant.subtitleGroup),
                audioTracks = tracks("AUDIO", variant.audioGroup),
            )
        } else {
            val (playlist, fragments) = getSegments(url, playlistContent, name)
            PlaylistResult.Content(
                type = DownloadType.Hls,
                content = playlist,
                fragments = fragments,
                subtitleTracks = emptyList(),
                audioTracks = emptyList(),
            )
        }
    }

    suspend fun download(
        url: String,
        headers: Headers,
        client: OkHttpClient,
        progress: ItemProgress,
        threadCount: Int,
        destDir: File,
        name: String,
    ): String {
        val (playlist, fragments) = getSegments(url, headers, client, name)
        return download(headers, client, playlist, fragments, progress, threadCount, destDir, name)
    }

    suspend fun download(
        headers: Headers,
        client: OkHttpClient,
        playlist: String,
        fragments: List<DownloadFragment>,
        progress: ItemProgress,
        threadCount: Int,
        destDir: File,
        name: String,
    ): String {
        return downloader.downloadPlaylist(
            headers = headers,
            client = client,
            playlist = playlist,
            fragments = fragments,
            progress = progress,
            threadCount = threadCount,
            destDir = destDir,
            name = name,
            ffmpegName = "index",
            ffmpegType = "hls",
        )
    }

    private suspend fun getSegments(
        playlistUrl: String,
        headers: Headers,
        client: OkHttpClient,
        name: String,
    ): Pair<String, List<DownloadFragment>> {
        val playlistContent = client.newCall(GET(playlistUrl, headers)).awaitSuccess().body.string()
        return getSegments(playlistUrl, playlistContent, name)
    }

    private fun getSegments(
        playlistUrl: String,
        playlistContent: String,
        name: String,
    ): Pair<String, List<DownloadFragment>> {
        val fragments = mutableListOf<DownloadFragment>()
        val rewrittenPlaylist = StringBuilder()
        val keyMap = mutableMapOf<String, String>()

        var urlIndex = 0
        var keyIndex = 0
        var initIndex = 0
        var byteLen: Long? = null
        var byteOffset: Long? = null
        var hasByteOffset = false

        fun parseRange(range: String): Pair<Long, Long> {
            val len = range.substringBefore('@').toLong()
            val offset = range.substringAfter('@', "").takeIf { it.isNotEmpty() }?.toLong()
            byteOffset = offset ?: (byteOffset!! + byteLen!!)
            byteLen = len
            return byteOffset to len
        }

        playlistContent.lineSequence().map { it.trim() }.forEach { line ->
            if (line.isBlank()) {
                rewrittenPlaylist.appendLine()
                return@forEach
            }

            if (line.startsWith('#')) {
                when {
                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        parseRange(line.substringAfter(':'))
                        hasByteOffset = true
                    }
                    line.startsWith("#EXT-X-KEY:") -> {
                        val rewritten = uriRegex.replace(line) { m ->
                            val uri = m.groupValues[1]
                            val newUri = if (uri.startsWith("data:", true)) {
                                uri
                            } else {
                                val fullUrl = resolveUrl(playlistUrl, uri)
                                keyMap[fullUrl] ?: run {
                                    val keyName = "$name-key${keyIndex++}"
                                    fragments.add(DownloadFragment(url = fullUrl, name = keyName))
                                    keyMap[fullUrl] = keyName
                                    keyName
                                }
                            }
                            """URI="$newUri""""
                        }
                        rewrittenPlaylist.appendLine(rewritten)
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val rewritten = uriRegex.replace(line) { m ->
                            val uri = m.groupValues[1]
                            val newUri = if (uri.startsWith("data:", true)) {
                                uri
                            } else {
                                val fullUrl = resolveUrl(playlistUrl, uri)
                                val range = byteRangeRegex.find(line)?.groupValues?.get(1)
                                val (start, end) = range?.let(::parseRange) ?: Pair(null, null)
                                val initName = "$name-init${initIndex++}"
                                fragments.add(
                                    DownloadFragment(
                                        url = fullUrl,
                                        name = initName,
                                        start = start,
                                        end = end?.let { start!! + it - 1 },
                                    ),
                                )
                                initName
                            }
                            """URI="$newUri""""
                        }
                        rewrittenPlaylist.appendLine(
                            rewritten.replace(byteRangeRegex, "").trimEnd(',').replace(",,", ","),
                        )
                    }
                    line.startsWith("#EXT-X-PART:") ||
                        line.startsWith("#EXT-X-PRELOAD-HINT:") ||
                        line.startsWith("#EXT-X-RENDITION-REPORT:") -> {}
                    else -> rewrittenPlaylist.appendLine(line)
                }
                return@forEach
            }

            val fullUrl = resolveUrl(playlistUrl, line)
            val segName = "$name-seg${urlIndex++}.ts"
            if (hasByteOffset) {
                fragments.add(
                    DownloadFragment(
                        url = fullUrl,
                        name = segName,
                        start = byteOffset!!,
                        end = byteOffset + byteLen!! - 1,
                    ),
                )
            } else {
                fragments.add(DownloadFragment(url = fullUrl, name = segName))
            }
            rewrittenPlaylist.appendLine(segName)
            hasByteOffset = false
        }

        return Pair(rewrittenPlaylist.toString(), fragments)
    }

    private fun parseAttrs(s: String): Map<String, String> =
        attrRegex.findAll(s).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    companion object {
        private val uriRegex = Regex("""URI="([^"]+)"""")
        private val byteRangeRegex = Regex("""BYTERANGE="([^"]+)"""")
        private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    }
}
