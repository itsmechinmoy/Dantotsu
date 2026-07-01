package ani.dantotsu.parsers

import android.app.Application
import ani.dantotsu.FileUrl
import ani.dantotsu.media.MediaNameAdapter
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalMangaParser : MangaParser() {
    private val context = Injekt.get<Application>()
    private val localSource = LocalMangaSource(context)

    override val name = "Local"
    override val saveName = "Local"
    override val hostUrl = "Local"
    override val isNSFW = false

    override suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter> {
        sManga.url = mangaLink
        val sChapters = localSource.getChapterList(sManga)
        return sChapters.map { sChapter ->
            MangaChapter(
                number = sChapter.name,
                link = sChapter.url,
                title = "",
                scanlator = sChapter.scanlator?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown",
                sChapter = sChapter,
                date = sChapter.date_upload
            )
        }.sortedBy { MediaNameAdapter.findChapterNumber(it.number) }
    }

    override suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage> {
        sChapter.url = chapterLink
        val pages = localSource.getPageList(sChapter)
        return pages.map { page ->
            MangaImage(
                url = FileUrl(page.imageUrl ?: ""),
                useTransformation = false,
                page = page
            )
        }
    }

    override suspend fun autoSearch(mediaObj: ani.dantotsu.media.Media): ShowResponse? {
    
        val folderName = mediaObj.folderName ?: mediaObj.name ?: mediaObj.mainName()
        val sManga = SManga.create().apply {
            title = folderName
            url = folderName
        }
        return ShowResponse(
            name = folderName,
            link = folderName,
            coverUrl = FileUrl(""),
            sManga = sManga
        )
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val searchResults = localSource.getSearchManga(1, query, localSource.getFilterList())
        return searchResults.mangas.map { sManga ->
            ShowResponse(
                name = sManga.title,
                link = sManga.url,
                coverUrl = FileUrl(sManga.thumbnail_url ?: ""),
                sManga = sManga
            )
        }
    }
}
