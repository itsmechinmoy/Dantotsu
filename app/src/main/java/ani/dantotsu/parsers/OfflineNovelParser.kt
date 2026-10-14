package ani.dantotsu.parsers

import android.app.Application
import ani.dantotsu.FileUrl
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.MediaType
import ani.dantotsu.util.Logger
import me.xdrop.fuzzywuzzy.FuzzySearch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OfflineNovelParser : NovelParser() {
    private val downloadManager = Injekt.get<DownloadsManager>()
    private val context = Injekt.get<Application>()

    override val hostUrl: String = "Offline"
    override val name: String = "Offline"
    override val saveName: String = "Offline"

    override val volumeRegex =
        Regex("vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)

    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book {
        val title = extra?.get("title") ?: link
        val directory = getSubDirectory(context, MediaType.NOVEL, false, title)
        if (directory?.exists() == true) {
            val chapters = mutableListOf<FileUrl>()
            val chapterDirs = directory.listFiles().filter { it.isDirectory }
                .sortedBy { MediaNameAdapter.findChapterNumber(it.name) }

            chapterDirs.forEach { chDir ->
                val epub = chDir.findFile("0.epub")
                    ?: chDir.listFiles().firstOrNull { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true }
                    ?: chDir.listFiles().firstOrNull { it.isFile && it.length() > 0 && it.name != "cover.jpg" && it.name != "media.json" && !it.name.orEmpty().startsWith(".") }
                val uriStr = epub?.uri?.toString() ?: chDir.uri.toString()
                chapters.add(FileUrl(uriStr, mapOf("X-Chapter-Name" to (chDir.name ?: "Unknown"))))
            }
            val coverUri = directory.findFile("cover.jpg")?.uri?.toString() ?: ""
            return Book(
                name = directory.name ?: title,
                img = FileUrl(coverUri),
                description = null,
                links = chapters
            )
        }
        return Book(
            "error",
            "",
            null,
            emptyList()
        )
    }

    override suspend fun autoSearch(mediaObj: ani.dantotsu.media.Media): ShowResponse? {
        val titles = downloadManager.novelDownloadedTypes.map { it.titleName }.distinct()
        if (titles.isEmpty()) return null
        return super.autoSearch(mediaObj)
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val titles = downloadManager.novelDownloadedTypes.map { it.titleName }.distinct()
        if (titles.isEmpty()) return emptyList()
        val returnTitlesPair: MutableList<Pair<String, Int>> = mutableListOf()
        for (title in titles) {
            Logger.log("Comparing $title to $query")
            val score = FuzzySearch.ratio(title.lowercase(), query.lowercase())
            if (score > 80) {
                returnTitlesPair.add(Pair(title, score))
            }
        }
        val returnTitles = returnTitlesPair.sortedByDescending { it.second }.map { it.first }
        val returnList: MutableList<ShowResponse> = mutableListOf()
        for (title in returnTitles) {
            val directory = getSubDirectory(context, MediaType.NOVEL, false, title)
            if (directory?.exists() == true) {
                val cover = directory.findFile("cover.jpg")?.uri?.toString() ?: ""
                val chapterDirs = directory.listFiles().filter { it.isDirectory }
                    .sortedBy { MediaNameAdapter.findChapterNumber(it.name) }
                for (chDir in chapterDirs) {
                    val epub = chDir.findFile("0.epub")
                        ?: chDir.listFiles().firstOrNull { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true }
                        ?: chDir.listFiles().firstOrNull { it.isFile && it.length() > 0 && it.name != "cover.jpg" && it.name != "media.json" && !it.name.orEmpty().startsWith(".") }
                    val linkUri = epub?.uri?.toString() ?: chDir.uri.toString()
                    returnList.add(
                        ShowResponse(
                            name = chDir.name ?: "Unknown",
                            link = linkUri,
                            coverUrl = FileUrl(cover),
                            extra = mutableMapOf("title" to title, "chapter" to (chDir.name ?: "Unknown"))
                        )
                    )
                }
            }
        }
        return returnList
    }

}