package ani.dantotsu.parsers

import android.app.Application
import android.net.Uri
import android.media.MediaMetadataRetriever
import ani.dantotsu.FileUrl
import ani.dantotsu.currContext
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.tryWithSuspend
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalAnimeParser : AnimeParser() {
    private val context = Injekt.get<Application>()
    private val localSource = LocalAnimeSource(context)

    override val name = "Local"
    override val saveName = "Local"
    override val hostUrl = "Local"
    override val isNSFW = false

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        sAnime.url = animeLink
        val sEpisodes = localSource.getEpisodeList(sAnime)
        return sEpisodes.map { sEpisode ->
            val extraData = mutableMapOf<String, String>()
            extraData["animeUrl"] = animeLink
            extraData["episodeUrl"] = sEpisode.url

           // fallback : thumbnail/poster
            val videoUri = localSource.cachedVideoUris[sEpisode.url] ?: localSource.getVideoUri(sEpisode)
            val thumbFileUrl = videoUri?.let { FileUrl(it.toString()) }

            val episodeNumberStr = sEpisode.episode_number.let {
                if (it == -1f) sEpisode.name else {
                    if (it % 1f == 0f) it.toInt().toString() else it.toString()
                }
            }

            Episode(
                number = episodeNumberStr,
                link = sEpisode.url,
                title = sEpisode.name,
                thumbnail = thumbFileUrl,
                extra = extraData,
                sEpisode = sEpisode
            )
        }.sortedBy { MediaNameAdapter.findEpisodeNumber(it.number) }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        return listOf(
            VideoServer(
                name = "Local",
                offline = false,
                extraData = extra
            )
        )
    }

    override suspend fun autoSearch(mediaObj: ani.dantotsu.media.Media): ShowResponse? {

        val folderName = mediaObj.folderName ?: mediaObj.name ?: mediaObj.mainName()
        val sAnime = SAnime.create().apply {
            title = folderName
            url = folderName
        }
        return ShowResponse(
            name = folderName,
            link = folderName,
            coverUrl = FileUrl(""),
            sAnime = sAnime
        )
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val searchResults = localSource.getSearchAnime(1, query, localSource.getFilterList())
        return searchResults.animes.map { sAnime ->
            ShowResponse(
                name = sAnime.title,
                link = sAnime.url,
                coverUrl = FileUrl(sAnime.thumbnail_url ?: ""),
                sAnime = sAnime
            )
        }
    }

    override suspend fun loadByVideoServers(
        episodeUrl: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
        callback: (VideoExtractor) -> Unit
    ) {
        val server = loadVideoServers(episodeUrl, extra, sEpisode).first()
        LocalVideoExtractor(server, localSource).apply {
            tryWithSuspend {
                load()
            }
            callback.invoke(this)
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        return LocalVideoExtractor(server, localSource)
    }
}

class LocalVideoExtractor(
    private var videoServer: VideoServer,
    private val localSource: LocalAnimeSource
) : VideoExtractor() {
    override val server: VideoServer
        get() = videoServer

    override suspend fun extract(): VideoContainer {
        val episodeUrl = videoServer.extraData?.get("episodeUrl") ?: ""

        val sEpisode = SEpisodeImpl().apply {
            url = episodeUrl
            name = episodeUrl.substringAfterLast("/").substringBeforeLast(".")
        }

        val videoUri = localSource.getVideoUri(sEpisode)
        
        var quality: Int? = null
        if (videoUri != null) {
            currContext()?.let { ctx ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, videoUri)
                    val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    quality = heightStr?.toIntOrNull()
                } catch (e: Exception) {
                    // Ignore metadata ext errors
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {}
                }
            }
        }

        // show resolution
        if (quality != null) {
            videoServer = videoServer.copy(name = "Local - ${quality}p")
        }

        val video = Video(
            quality,
            VideoType.CONTAINER,
            FileUrl(videoUri?.toString() ?: ""),
        )
        return VideoContainer(listOf(video))
    }
}
