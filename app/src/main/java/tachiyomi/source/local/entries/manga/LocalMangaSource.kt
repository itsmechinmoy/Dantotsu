package tachiyomi.source.local.entries.manga

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import rx.Observable
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.core.metadata.comicinfo.ChapterDetails
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.source.local.filter.manga.MangaOrderBy
import tachiyomi.source.local.archive.archiveReader
import tachiyomi.source.local.archive.epubReader
import tachiyomi.source.local.io.ArchiveManga
import tachiyomi.source.local.io.Format
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.source.local.metadata.fillMetadata
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.io.InputStream
import kotlin.math.abs

class LocalMangaSource(
    private val context: Context,
) : CatalogueSource, UnmeteredSource {

    private val json = Json { ignoreUnknownKeys = true }
    private val xml = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
    }

    private val POPULAR_FILTERS = FilterList(MangaOrderBy.Popular())
    private val LATEST_FILTERS = FilterList(MangaOrderBy.Latest())

    override val name: String = "Local manga source"

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

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

    // local manga dir
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
        val localMangaDir = baseDir.findFile("localmanga") ?: baseDir

        var mangaDirs = localMangaDir.listFiles()
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
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        )
                    } else {
                        mangaDirs.sortedWith(
                            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        )
                    }
                }
                is MangaOrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy { it.lastModified() }
                    } else {
                        mangaDirs.sortedByDescending { it.lastModified() }
                    }
                }
                else -> { /* Do nothing */ }
            }
        }

     
        val mangas = mangaDirs.map { mangaDir ->
            SManga.create().apply {
                title = mangaDir.name.orEmpty()
                url = mangaDir.name.orEmpty()

                val coverFile = findCoverFile(mangaDir)
                if (coverFile != null) {
                    thumbnail_url = coverFile.uri.toString()
                } else {
                    val fallback = extractCoverFallback(mangaDir)
                    if (fallback != null) {
                        thumbnail_url = fallback.uri.toString()
                    }
                }
            }
        }

        return MangasPage(mangas, false)
    }

    // Suspend wrappers
    suspend fun getPopularManga(page: Int): MangasPage = withIOContext {
        getSearchMangaSync(page, "", POPULAR_FILTERS)
    }

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        getSearchMangaSync(page, query, filters)
    }

    // Manga details related
    override suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        val mangaDir = getMangaDir(manga.url, context) ?: return@withIOContext manga

        val coverFile = findCoverFile(mangaDir) ?: extractCoverFallback(mangaDir)
        if (coverFile != null) {
            manga.thumbnail_url = coverFile.uri.toString()
        }

        val comicInfoFile = mangaDir.findFile(COMIC_INFO_FILE)
        if (comicInfoFile != null) {
            try {
                context.contentResolver.openInputStream(comicInfoFile.uri)?.use { stream ->
                    val comicInfo = AndroidXmlReader(stream, "UTF-8").use { xml.decodeFromReader<ComicInfo>(it) }
                    manga.copyFromComicInfo(comicInfo)
                }
            } catch (e: Exception) {
                Logger.log("Error parsing ComicInfo.xml for ${manga.url}: ${e.message}")
            }
        } else {
            // details.json get convert to ComicInfo
            mangaDir.findFile("details.json")?.let { detailsFile ->
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

                    // Convert to ComicInfo 
                    val comicInfo = manga.getComicInfo()
                    mangaDir.createFile("application/xml", COMIC_INFO_FILE)?.let { newComicInfoFile ->
                        context.contentResolver.openOutputStream(newComicInfoFile.uri)?.use { stream ->
                            val xmlString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            stream.write(xmlString.toByteArray())
                        }
                    }
                    detailsFile.delete()
                } catch (e: Exception) {
                    Logger.log("Error parsing details.json for ${manga.url}: ${e.message}")
                }
            }
        }

        return@withIOContext manga
    }

    // Chapters
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val mangaDir = getMangaDir(manga.url, context) ?: return@withIOContext emptyList()

        val chaptersData = try {
            mangaDir.findFile("chapters.json")?.let { chaptersFile ->
                val inputStream = context.contentResolver.openInputStream(chaptersFile.uri)
                val text = inputStream?.bufferedReader()?.readText() ?: return@let null
                inputStream.close()
                json.decodeFromString<List<ChapterDetails>>(text)
            }
        } catch (e: Exception) {
            Logger.log("Error parsing chapters.json for ${manga.url}: ${e.message}")
            null
        }

        // archives (zip/cbz)
        val chapterEntries = mangaDir.listFiles()
            .filter { !it.name.orEmpty().startsWith('.') && Format.valueOf(it) != null }

        val chapters = chapterEntries.map { chapterEntry ->
            SChapter.create().apply {
                url = "${manga.url}/${chapterEntry.name.orEmpty()}"
                name = chapterEntry.name.orEmpty().let { n ->
                    if (chapterEntry.isFile) n.substringBeforeLast(".") else n
                }
                date_upload = chapterEntry.lastModified()

                val format = Format.valueOf(chapterEntry)
                if (format is Format.Epub) {
                    try {
                        chapterEntry.epubReader(context).use { epub ->
                            epub.fillMetadata(manga, this)
                        }
                    } catch (e: Exception) {
                        Logger.log("Error extracting EPUB metadata for ${chapterEntry.name}: ${e.message}")
                    }
                }

                // Parse chapter number from name
                val parsedNumber = MediaNameAdapter.findChapterNumber(this.name)
                    ?: Regex("""(\d+(?:\.\d+)?)""").find(this.name)?.value?.toFloatOrNull()
                chapter_number = parsedNumber ?: -1f

                chaptersData?.let { dataList ->
                    dataList.firstOrNull {
                        it.chapter_number.equalsTo(chapter_number)
                    }?.also { data ->
                        data.name?.also { name = it }
                        data.date_upload?.also { date_upload = parseDate(it) }
                        scanlator = data.scanlator
                    }
                }
            }
        }
            .sortedWith { c1, c2 ->
                val c = c2.chapter_number.compareTo(c1.chapter_number)
                if (c == 0) c2.name.compareTo(c1.name, ignoreCase = true) else c
            }

        chapters
    }

    // Filters
    override fun getFilterList() = FilterList(MangaOrderBy.Popular())

    // Page list
    override suspend fun getPageList(chapter: SChapter): List<Page> = withIOContext {
        val parts = chapter.url.split("/", limit = 2)
        if (parts.size < 2) return@withIOContext emptyList()
        val mangaDir = getMangaDir(parts[0], context) ?: return@withIOContext emptyList()
        val chapterName = parts[1]

        val chapterEntry = mangaDir.findFile(chapterName)
            ?: return@withIOContext emptyList()

        val format = Format.valueOf(chapterEntry) ?: return@withIOContext emptyList()

        try {
            when (format) {
                is Format.Directory -> getPagesFromDirectory(chapterEntry)
                is Format.Archive -> getPagesFromArchive(chapterEntry)
                is Format.Epub -> getPagesFromEpub(chapterEntry)
            }
        } catch (e: Exception) {
            Logger.log("Error extracting pages for ${chapterEntry.name}: ${e.message}")
            emptyList()
        }
    }

    private fun getPagesFromEpub(chapterEntry: DocumentFile): List<Page> {
        val pages = mutableListOf<Page>()
        val cacheDir = java.io.File(context.cacheDir, "local_manga/${chapterEntry.name.orEmpty()}")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        try {
            chapterEntry.epubReader(context).use { epub ->
                val imagePaths = epub.getImagesFromPages()
                imagePaths.forEachIndexed { i, path ->
                    val entryName = path.substringAfterLast("/")
                    val outFile = java.io.File(cacheDir, entryName)
                    if (!outFile.exists() || outFile.length() == 0L) {
                        epub.getInputStream(path)?.use { stream ->
                            val bytes = stream.readBytes()
                            outFile.writeBytes(bytes)
                        }
                    }
                    pages.add(Page(i, imageUrl = outFile.absolutePath))
                }
            }
        } catch (e: Exception) {
            Logger.log("Error extracting EPUB ${chapterEntry.name}: ${e.message}")
        }
        return pages
    }

    // Lists img files in a dir chap and returns them as Pages
    private fun getPagesFromDirectory(dir: DocumentFile): List<Page> {
        return dir.listFiles()
            .filter { it.isFile && isSupportedImage(it) }
            .sortedWith(compareBy { naturalSortKey(it.name.orEmpty()) })
            .mapIndexed { index, file ->
                Page(index, imageUrl = file.uri.toString())
            }
    }

    // archives
    private fun getPagesFromArchive(archiveFile: DocumentFile): List<Page> {
        val pages = mutableListOf<Page>()
        val cacheDir = java.io.File(context.cacheDir, "local_manga/${archiveFile.name.orEmpty()}")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        try {
            archiveFile.archiveReader(context).use { reader ->
                val extractedFiles = mutableListOf<java.io.File>()
                reader.consume { stream ->
                    while (true) {
                        val entry = stream.getNextEntry() ?: break
                        val entryName = entry.name.substringAfterLast("/")
                        if (entry.isFile && isSupportedImageName(entryName)) {
                            val outFile = java.io.File(cacheDir, entryName)
                            if (!outFile.exists() || outFile.length() == 0L) {
                                val bytes = stream.readBytes()
                                outFile.writeBytes(bytes)
                            }
                            extractedFiles.add(outFile)
                        }
                    }
                }
               
                extractedFiles
                    .sortedWith(compareBy { naturalSortKey(it.name) })
                    .forEachIndexed { idx, file ->
                        pages.add(Page(idx, imageUrl = file.absolutePath))
                    }
            }
        } catch (e: Exception) {
            Logger.log("Error extracting archive ${archiveFile.name}: ${e.message}")
        }

        return pages
    }

    private fun findCoverFile(dir: DocumentFile): DocumentFile? {
        COVER_NAMES.forEach { name ->
            dir.findFile(name)?.let { return it }
        }
        return null
    }

    private fun extractCoverFallback(mangaDir: DocumentFile): DocumentFile? {
        val firstArchive = mangaDir.listFiles()
            .filter { !it.name.orEmpty().startsWith('.') && Format.valueOf(it) != null }
            .sortedBy { it.name }
            .firstOrNull() ?: return null

        try {
            when (Format.valueOf(firstArchive)) {
                is Format.Archive -> {
                    firstArchive.archiveReader(context).use { reader ->
                        val firstImage = reader.useEntries { entries ->
                            entries.filter { it.isFile && it.name.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp") }
                                .sortedWith(compareBy { naturalSortKey(it.name) })
                                .firstOrNull()
                        }
                        if (firstImage != null) {
                            reader.getInputStream(firstImage.name)?.use { inputStream ->
                                return saveCover(mangaDir, inputStream)
                            }
                        }
                    }
                }
                is Format.Epub -> {
                    firstArchive.epubReader(context).use { epub ->
                        val firstImage = epub.getImagesFromPages().firstOrNull()
                        if (firstImage != null) {
                            epub.getInputStream(firstImage)?.use { inputStream ->
                                return saveCover(mangaDir, inputStream)
                            }
                        }
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            Logger.log("Error extracting cover from ${firstArchive.name}: ${e.message}")
        }
        return null
    }

    private fun saveCover(mangaDir: DocumentFile, inputStream: InputStream): DocumentFile? {
        val coverFile = mangaDir.createFile("image/jpeg", "cover.jpg") ?: return null
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

    private fun isSupportedImage(file: DocumentFile): Boolean {
        return isSupportedImageName(file.name.orEmpty())
    }

    private fun isSupportedImageName(name: String): Boolean {
        val lower = name.lowercase()
        return SUPPORTED_IMAGE_EXTENSIONS.any { lower.endsWith(".$it") }
    }

    // sorting
    private fun naturalSortKey(name: String): String {
        return Regex("""(\d+)|(\D+)""").findAll(name).joinToString("") { match ->
            val num = match.groupValues[1]
            if (num.isNotEmpty()) {
                num.padStart(20, '0')
            } else {
                match.groupValues[2].lowercase()
            }
        }
    }

    private fun parseDate(isoDate: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                .parse(isoDate)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < 0.0001
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-manga/"

        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.jpeg", "cover.png", "cover.webp"
        )
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)
        private val SUPPORTED_IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        fun getBaseDirectory(context: Context): DocumentFile? {
            val uriString = ani.dantotsu.settings.saving.PrefManager
                .getVal<String>(ani.dantotsu.settings.saving.PrefName.LocalMangaDir)
            if (uriString.isBlank()) return null
            val uri = Uri.parse(uriString)
            return DocumentFile.fromTreeUri(context, uri)
        }

        fun createBaseDirectories(context: Context, rootUri: Uri): DocumentFile? {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
            // Create localmanga subfolder if it doesn't exist
            val dir = root.findFile("localmanga") ?: root.createDirectory("localmanga")
            if (dir?.findFile(".nomedia") == null) {
                dir?.createFile("", ".nomedia")
            }
            return dir
        }

        private fun getMangaDir(mangaUrl: String, context: Context): DocumentFile? {
            val baseDir = getBaseDirectory(context) ?: return null
            return baseDir.findFile(mangaUrl)?.takeIf { it.isDirectory }
                ?: baseDir.findFile("localmanga")?.findFile(mangaUrl)?.takeIf { it.isDirectory }
        }
    }
}

fun Manga.isLocal(): Boolean = source == LocalMangaSource.ID

fun MangaSource.isLocal(): Boolean = id == LocalMangaSource.ID
