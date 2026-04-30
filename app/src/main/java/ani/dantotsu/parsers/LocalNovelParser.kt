package ani.dantotsu.parsers

import android.app.Application
import ani.dantotsu.FileUrl
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import eu.kanade.tachiyomi.source.model.SManga
import me.xdrop.fuzzywuzzy.FuzzySearch
import tachiyomi.source.local.entries.novel.LocalNovelSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalNovelParser : NovelParser() {
    private val context = Injekt.get<Application>()
    private val localSource = LocalNovelSource(context)

    override val name = "Local"
    override val saveName = "Local"
    override val hostUrl = "Local"
    override val isNSFW = false

    override val volumeRegex = Regex("(?i)volume\\s+\\d+|vol\\.?\\s*\\d+|v\\d+")

    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book {
        return Book(
            name = "Local Book",
            img = "",
            description = null,
            links = listOf(link)
        )
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        return null
    }

    override suspend fun sortedSearch(media: Media): List<ShowResponse> {
        val query = media.folderName ?: media.mainName()
        return search(query)
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val searchResults = localSource.getSearchManga(1, "", localSource.getFilterList())
        val returnList: MutableList<ShowResponse> = mutableListOf()
        for (novel in searchResults.mangas) {
            val score = FuzzySearch.ratio(novel.title.lowercase(), query.lowercase())
            if (score > 80 || novel.title.contains(query, ignoreCase = true)) {
                val chapters = localSource.getChapterList(novel)
                for (chapter in chapters) {
                    returnList.add(
                        ShowResponse(
                            name = chapter.name,
                            link = chapter.url,
                            coverUrl = FileUrl(novel.thumbnail_url ?: "")
                        )
                    )
                }
                
                if (score > 90 || novel.title.equals(query, ignoreCase = true)) {
                    break
                }
            }
        }
        return returnList
    }
}
