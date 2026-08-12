package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable
import tachiyomi.core.util.lang.awaitSingle

interface CatalogueSource : Source {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    override val supportsLatest: Boolean

    @Suppress("DEPRECATION")
    override suspend fun getPopularManga(page: Int): MangasPage = try {
        fetchPopularManga(page).awaitSingle()
    } catch (e: Throwable) {
        throw UnsupportedOperationException()
    }

    @Suppress("DEPRECATION")
    override suspend fun getLatestUpdates(page: Int): MangasPage = try {
        fetchLatestUpdates(page).awaitSingle()
    } catch (e: Throwable) {
        throw UnsupportedOperationException()
    }

    @Suppress("DEPRECATION")
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = try {
        fetchSearchManga(page, query, filters).awaitSingle()
    } catch (e: Throwable) {
        throw UnsupportedOperationException()
    }

    @Suppress("DEPRECATION")
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async {
            try {
                fetchMangaDetails(manga).awaitSingle()
            } catch (e: Throwable) {
                getMangaDetails(manga)
            }
        } else null
        val asyncChapters = if (fetchChapters) async {
            try {
                fetchChapterList(manga).awaitSingle()
            } catch (e: Throwable) {
                getChapterList(manga)
            }
        } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    @Suppress("DEPRECATION")
    override suspend fun getPageList(chapter: SChapter): List<Page> = try {
        fetchPageList(chapter).awaitSingle()
    } catch (e: Throwable) {
        throw UnsupportedOperationException()
    }

    /**
     * Returns the list of filters for the source.
     */
    override fun getFilterList(): FilterList = FilterList()

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularManga"),
    )
    fun fetchPopularManga(page: Int): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchManga"),
    )
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        throw IllegalStateException("Not used")
}
