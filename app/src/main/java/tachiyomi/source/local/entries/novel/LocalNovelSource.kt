package tachiyomi.source.local.entries.novel

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.source.local.filter.manga.MangaOrderBy
import rx.Observable
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import tachiyomi.source.local.archive.epubReader
import tachiyomi.source.local.metadata.fillMetadata

class LocalNovelSource(
    private val context: Context,
) : CatalogueSource, UnmeteredSource {

    private val json = Json { ignoreUnknownKeys = true }

    private val POPULAR_FILTERS = FilterList(MangaOrderBy.Popular())
    private val LATEST_FILTERS = FilterList(MangaOrderBy.Latest())

    override val name = "Local novel source"

    override val id: Long = ID

    override val lang = "other"

    override fun toString() = name

    override val supportsLatest = true

    // Browse related
    override fun fetchPopularManga(page: Int) = fetchSearchManga(page, "", POPULAR_FILTERS)

    override fun fetchLatestUpdates(page: Int) = fetchSearchManga(page, "", LATEST_FILTERS)

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ): Observable<MangasPage> {
        return Observable.fromCallable {
            getSearchMangaSync(page, query, filters)
        }
    }

    private fun getSearchMangaSync(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val baseDir = getBaseDirectory(context)
            ?: return MangasPage(emptyList(), false)

        val lastModifiedLimit = if (filters === LATEST_FILTERS) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        val localDir = baseDir.findFile("local") ?: baseDir
        val localNovelDir = localDir.findFile("novel") ?: localDir

        var novelDirs = localNovelDir.listFiles()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is MangaOrderBy.Popular -> {
                    novelDirs = if (filter.state!!.ascending) {
                        novelDirs.sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        )
                    } else {
                        novelDirs.sortedWith(
                            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        )
                    }
                }
                is MangaOrderBy.Latest -> {
                    novelDirs = if (filter.state!!.ascending) {
                        novelDirs.sortedBy { it.lastModified() }
                    } else {
                        novelDirs.sortedByDescending { it.lastModified() }
                    }
                }
                else -> { /* Do nothing */ }
            }
        }

        // Transform novelDirs to list of SManga
        val novels = novelDirs.map { novelDir ->
            SManga.create().apply {
                title = novelDir.name.orEmpty()
                url = novelDir.name.orEmpty()

                val coverFile = findCoverFile(novelDir) ?: extractCoverFallback(novelDir)
                if (coverFile != null) {
                    thumbnail_url = coverFile.uri.toString()
                }
            }
        }

        return MangasPage(novels, false)
    }

   
    override suspend fun getPopularManga(page: Int): MangasPage = withIOContext {
        getSearchMangaSync(page, "", POPULAR_FILTERS)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        getSearchMangaSync(page, query, filters)
    }

    // Novel details related
    override suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        val novelDir = getNovelDir(manga.url, context) ?: return@withIOContext manga

        val coverFile = findCoverFile(novelDir) ?: extractCoverFallback(novelDir)
        if (coverFile != null) {
            manga.thumbnail_url = coverFile.uri.toString()
        }

        // Try to parse details.json
        novelDir.findFile("details.json")?.let { detailsFile ->
            try {
                val inputStream = context.contentResolver.openInputStream(detailsFile.uri)
                val text = inputStream?.bufferedReader()?.readText() ?: return@let
                inputStream.close()
                val details = json.decodeFromString<MangaDetails>(text)
                details.title?.let { manga.title = it }
                details.author?.let { manga.author = it }
                details.artist?.let { manga.artist = it }
                details.description?.let { manga.description = it }
                details.genre?.let { manga.genre = it.joinToString() }
                details.status?.let { manga.status = it }
            } catch (e: Exception) {
                Logger.log("Error parsing details.json for ${manga.url}: ${e.message}")
            }
        }

        return@withIOContext manga
    }

    // Chapters
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val novelDir = getNovelDir(manga.url, context) ?: return@withIOContext emptyList()

        val chapters = novelDir.listFiles()
            .filter { !it.name.orEmpty().startsWith('.') && isSupportedBook(it) }
            .map { bookFile ->
                SChapter.create().apply {
                    url = bookFile.uri.toString()
                    name = bookFile.name?.substringBeforeLast('.') ?: "Unknown"
                    date_upload = bookFile.lastModified()
                    chapter_number = -2f

                    try {
                        bookFile.epubReader(context).use { epub ->
                            epub.fillMetadata(manga, this)
                        }
                    } catch (e: Exception) {
                        Logger.log("Error extracting EPUB metadata for ${bookFile.name}: ${e.message}")
                    }
                }
            }
            .sortedBy { it.name }

        return@withIOContext chapters
    }

    override suspend fun getPageList(chapter: SChapter) = emptyList<eu.kanade.tachiyomi.source.model.Page>()
    // Helper methods
    override fun getFilterList() = FilterList(MangaOrderBy.Popular(), MangaOrderBy.Latest())

    private fun isSupportedBook(file: DocumentFile): Boolean {
        if (file.isDirectory) return false
        val extension = file.name?.substringAfterLast('.')?.lowercase()
        val mimeType = file.type?.lowercase()
        return extension == "epub" || mimeType == "application/epub+zip"
    }

    private fun findCoverFile(dir: DocumentFile): DocumentFile? {
        return dir.listFiles().firstOrNull {
            it.isFile && it.name.orEmpty().startsWith("cover", ignoreCase = true) &&
                    it.name.orEmpty().substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp")
        }
    }

    private fun extractCoverFallback(novelDir: DocumentFile): DocumentFile? {
        val firstBook = novelDir.listFiles()
            .filter { !it.name.orEmpty().startsWith('.') && isSupportedBook(it) }
            .sortedBy { it.name }
            .firstOrNull() ?: return null

        try {
            firstBook.epubReader(context).use { epub ->
                val firstImage = epub.getImagesFromPages().firstOrNull()
                if (firstImage != null) {
                    epub.getInputStream(firstImage)?.use { inputStream ->
                        return saveCover(novelDir, inputStream)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log("Error extracting cover from ${firstBook.name}: ${e.message}")
        }
        return null
    }

    private fun saveCover(novelDir: DocumentFile, inputStream: InputStream): DocumentFile? {
        val coverFile = novelDir.createFile("image/jpeg", "cover.jpg") ?: return null
        try {
            context.contentResolver.openOutputStream(coverFile.uri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            return coverFile
        } catch (e: Exception) {
            coverFile.delete()
            return null
        }
    }

    private fun getNovelDir(novelUrl: String, context: Context): DocumentFile? {
        val baseDir = getBaseDirectory(context) ?: return null
        val localDir = baseDir.findFile("local") ?: baseDir
        val localNovelDir = localDir.findFile("novel") ?: localDir
        return localNovelDir.findFile(novelUrl)
    }

    companion object {
        const val ID = 2L
        const val HELP_URL = "https://dantotsu.app"
        private val LATEST_THRESHOLD = TimeUnit.DAYS.toMillis(7)

        fun getBaseDirectory(context: Context): DocumentFile? {
            val uriStr = PrefManager.getVal<String>(PrefName.LocalDir)
            if (uriStr.isEmpty()) return null
            return try {
                DocumentFile.fromTreeUri(context, android.net.Uri.parse(uriStr))
            } catch (e: Exception) {
                null
            }
        }
    }
}
