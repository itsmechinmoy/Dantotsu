package ani.dantotsu.parsers

import ani.dantotsu.FileUrl
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.data.torrentServer.model.FileStat
import eu.kanade.tachiyomi.data.torrentServer.model.Torrent
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class TorrentAnimeParser : AnimeParser() {
    override val name = "Torrent"
    override val saveName = "Torrent"
    override val hostUrl = "Torrent"
    override val isNSFW = false

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null && saved.link.isNotBlank()) {
            return saved
        }

        val torrentUrl = PrefManager.getNullableCustomVal("${mediaObj.id}_torrent_url", null, String::class.java)
            ?.takeIf { it.isNotBlank() }
            ?: PrefManager.getCustomVal("${mediaObj.id}_torrent_url", "")
                .takeIf { it.isNotBlank() }
            ?: return null

        val title = mediaObj.userPreferredName.ifBlank { mediaObj.mainName() }
        val sAnime = SAnime.create().apply {
            this.title = title
            this.url = torrentUrl
        }
        val response = ShowResponse(
            name = title,
            link = torrentUrl,
            coverUrl = FileUrl(mediaObj.cover ?: mediaObj.banner ?: ""),
            sAnime = sAnime
        )
        saveShowResponse(mediaObj.id, response, true)
        return response
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val trimmed = query.trim()
        if (trimmed.startsWith("magnet:?xt=", ignoreCase = true) ||
            (trimmed.startsWith("http", ignoreCase = true) && trimmed.contains(".torrent", ignoreCase = true)) ||
            trimmed.startsWith("file://", ignoreCase = true)
        ) {
            val sAnime = SAnime.create().apply {
                this.title = "Torrent Stream"
                this.url = trimmed
            }
            return listOf(
                ShowResponse(
                    name = "Torrent Stream",
                    link = trimmed,
                    coverUrl = FileUrl(""),
                    sAnime = sAnime
                )
            )
        }
        return emptyList()
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        val torrentManager = Injekt.get<TorrentServerManager>()
        try {
            setUserText("Connecting to torrent peers...")
            ani.dantotsu.addons.torrent.TorrentServerService.start()
            torrentManager.start()
            setUserText("Fetching metadata from torrent...")
            val torrent = torrentManager.addTorrent(
                url = animeLink,
                title = sAnime.title.ifBlank { "Torrent Stream" },
                poster = "",
                data = "",
                save = false
            )
            setUserText("Torrent loaded: ${torrent.file_stats?.size ?: 0} files")

            val rawStats = torrent.file_stats ?: emptyList()
            val videoExtensions = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".m4v", ".ts")
            val videoFiles = rawStats.filter { stat ->
                val p = stat.path.lowercase(Locale.ROOT)
                videoExtensions.any { p.endsWith(it) }
            }.ifEmpty { rawStats }

            if (videoFiles.isEmpty()) return emptyList()

            // Strip common root folder prefix across all files if present
            val relativePaths = stripCommonRootDirectory(videoFiles.map { it.path })

            val episodesList = mutableListOf<Episode>()
            videoFiles.forEachIndexed { index, stat ->
                val relPath = relativePaths.getOrElse(index) { stat.path }
                val cleanFileName = relPath.replace('\\', '/').substringAfterLast('/')
                val folderName = if (relPath.replace('\\', '/').contains('/')) {
                    relPath.replace('\\', '/').substringBeforeLast('/')
                } else ""

                val epNum = MediaNameAdapter.findEpisodeNumber(cleanFileName)?.let {
                    if (it % 1 == 0f) it.toInt().toString() else it.toString()
                } ?: (index + 1).toString()

                val fileId = stat.id ?: index
                val streamUrl = torrentManager.getLink(torrent, fileId)

                val sEp = SEpisode.create().apply {
                    this.name = cleanFileName
                    this.url = streamUrl
                    this.episode_number = epNum.toFloatOrNull() ?: (index + 1).toFloat()
                    if (folderName.isNotBlank()) {
                        this.scanlator = folderName
                    }
                }

                val extraData = mutableMapOf<String, String>()
                extraData["torrentHash"] = torrent.hash ?: ""
                extraData["fileId"] = fileId.toString()
                extraData["streamUrl"] = streamUrl

                val sizeDesc = if (stat.length > 0) formatFileSize(stat.length) else null

                val ep = Episode(
                    number = epNum,
                    link = streamUrl,
                    title = cleanFileName,
                    thumbnail = null,
                    description = sizeDesc,
                    isFiller = false,
                    extra = extraData,
                    sEpisode = sEp
                )
                episodesList.add(ep)
            }

            return episodesList.sortedWith(
                compareBy<Episode> { it.sEpisode?.scanlator ?: "" }
                    .thenBy { it.sEpisode?.episode_number ?: 0f }
            )
        } catch (e: Exception) {
            Logger.log("TorrentAnimeParser error loading episodes: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        return listOf(
            VideoServer(
                name = "Torrent Stream",
                embed = FileUrl(episodeLink),
                extraData = extra
            )
        )
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        val vid = Video(
            quality = 1080,
            format = VideoType.CONTAINER,
            file = server.embed,
            size = null
        )
        return object : VideoExtractor() {
            override val server: VideoServer = server
            override suspend fun extract(): VideoContainer = VideoContainer(videos = listOf(vid))
            init {
                videos = listOf(vid)
            }
        }
    }

    companion object {
        /**
         * Strips common top-level root directory (e.g. "[Anime Time] Attack On Titan (Ultimate Collection)/")
         * when all files in the torrent share the same top-level root folder.
         */
        fun stripCommonRootDirectory(paths: List<String>): List<String> {
            val normalized = paths.map { it.replace('\\', '/').trim('/') }
            if (normalized.isEmpty()) return normalized

            val partsList = normalized.map { it.split('/') }
            val allHaveFolders = partsList.all { it.size > 1 }

            var commonRoot: String? = null
            if (allHaveFolders) {
                val firstSeg = partsList[0][0]
                if (partsList.all { it[0] == firstSeg }) {
                    commonRoot = firstSeg
                }
            }

            return if (commonRoot != null) {
                normalized.map { path ->
                    if (path.startsWith("$commonRoot/")) {
                        path.removePrefix("$commonRoot/")
                    } else path
                }
            } else {
                normalized
            }
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
            val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0))
                .toInt().coerceIn(0, units.size - 1)
            return String.format(
                java.util.Locale.US, "%.1f %s",
                bytes / java.lang.Math.pow(1024.0, digitGroups.toDouble()),
                units[digitGroups]
            )
        }
    }
}
