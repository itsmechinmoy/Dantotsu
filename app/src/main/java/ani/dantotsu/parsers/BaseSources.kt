package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.EpisodeStorage
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga

abstract class WatchSources : BaseSources() {

    override operator fun get(i: Int): AnimeParser {
        return (list.getOrNull(i) ?: list.firstOrNull())?.get?.value as? AnimeParser
            ?: EmptyAnimeParser()
    }

    fun isDownloadedSource(i: Int): Boolean {
        return get(i) is OfflineAnimeParser
    }

    suspend fun loadEpisodesFromMedia(i: Int, media: Media, invalidate: Boolean = false): MutableMap<String, Episode> {
        return tryWithSuspend(true) {
            val parser = get(i)
            val sourceKey = parser.saveName.ifBlank { parser.name }

            if (!invalidate && parser !is OfflineAnimeParser) {
                val savedResponse = parser.loadSavedShowResponse(media.id)
                if (savedResponse != null && savedResponse.link.isNotBlank()) {
                    val cached = EpisodeStorage.loadEpisodes(sourceKey, savedResponse.link)
                    if (!cached.isNullOrEmpty()) {
                        return@tryWithSuspend cached
                    }
                }
            }

            val res = parser.autoSearch(media) ?: return@tryWithSuspend mutableMapOf()
            if (!invalidate && parser !is OfflineAnimeParser) {
                val cached = EpisodeStorage.loadEpisodes(sourceKey, res.link)
                if (!cached.isNullOrEmpty()) {
                    return@tryWithSuspend cached
                }
            }

            val loaded = loadEpisodes(i, res.link, res.extra, res.sAnime)
            if (loaded.isNotEmpty() && parser !is OfflineAnimeParser) {
                EpisodeStorage.saveEpisodes(sourceKey, res.link, loaded)
            }
            loaded
        } ?: mutableMapOf()
    }

    suspend fun loadEpisodes(
        i: Int,
        showLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime?
    ): MutableMap<String, Episode> {
        val map = mutableMapOf<String, Episode>()
        val parser = get(i)
        val actualAnime = sAnime ?: SAnime.create().apply {
            url = showLink
            title = ""
        }
        tryWithSuspend(true) {
            parser.loadEpisodes(showLink, extra, actualAnime).forEach {
                val key = if (it.sEpisode?.scanlator.isNullOrBlank()) it.number else "${it.number}-${it.sEpisode?.scanlator}"
                map[key] = Episode(
                    it.number,
                    it.link,
                    it.title,
                    it.description,
                    it.thumbnail,
                    it.isFiller,
                    extra = it.extra,
                    sEpisode = it.sEpisode
                )
            }
        }
        if (map.isNotEmpty() && parser !is OfflineAnimeParser) {
            val sourceKey = parser.saveName.ifBlank { parser.name }
            EpisodeStorage.saveEpisodes(sourceKey, showLink, map)
        }
        return map
    }
}

abstract class MangaReadSources : BaseSources() {

    override operator fun get(i: Int): MangaParser {
        return (list.getOrNull(i) ?: list.firstOrNull())?.get?.value as? MangaParser
            ?: EmptyMangaParser()
    }

    suspend fun loadChaptersFromMedia(i: Int, media: Media): MutableMap<String, MangaChapter> {
        return tryWithSuspend(true) {
            val res = get(i).autoSearch(media) ?: return@tryWithSuspend mutableMapOf()
            loadChapters(i, res)
        } ?: mutableMapOf()
    }

    suspend fun loadChapters(i: Int, show: ShowResponse): MutableMap<String, MangaChapter> {
        val map = mutableMapOf<String, MangaChapter>()
        val parser = get(i)
        val sManga = show.sManga ?: SManga.create().apply {
            url = show.link
            title = show.name
            thumbnail_url = show.coverUrl.url
        }

        tryWithSuspend(true) {
            parser.loadChapters(show.link, show.extra, sManga).forEach {
                map["${it.number}-${it.scanlator}"] = MangaChapter(it)
            }
        }
        return map
    }
}

abstract class NovelReadSources : BaseSources() {
    override operator fun get(i: Int): NovelParser? {
        return if (list.isNotEmpty()) {
            (list.getOrNull(i) ?: list[0]).get.value as NovelParser
        } else {
            return EmptyNovelParser()
        }
    }

}

class EmptyNovelParser : NovelParser() {

    override val volumeRegex: Regex = Regex("")

    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book {
        return Book("", "", null, emptyList())  // Return an empty Book object or some default value
    }

    override suspend fun search(query: String): List<ShowResponse> {
        return listOf() // Return an empty list or some default value
    }
}

abstract class BaseSources {
    abstract val list: List<Lazier<BaseParser>>

    val names: List<String> get() = list.map { it.name }

    fun flushText() {
        list.forEach {
            if (it.get.isInitialized())
                it.get.value?.showUserText = ""
        }
    }

    open operator fun get(i: Int): BaseParser? {
        return list[i].get.value
    }

    fun saveResponse(i: Int, mediaId: Int, response: ShowResponse) {
        get(i)?.saveShowResponse(mediaId, response, true)
    }
}
