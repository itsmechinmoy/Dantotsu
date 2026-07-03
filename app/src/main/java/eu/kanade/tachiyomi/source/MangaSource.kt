package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.awaitSingle
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface MangaSource {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean
        get() = false

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     * @param page the page number to retrieve.
     */
    suspend fun getPopularManga(page: Int): MangasPage

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since tachiyomix 1.6
     * @param page the page number to retrieve.
     */
    suspend fun getLatestUpdates(page: Int): MangasPage

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    /**
     * Fetches updated information for a manga.
     *
     * @since tachiyomix 1.6
     * @param manga The manga to fetch updates for.
     * @param chapters Existing chapters of the manga
     * @param fetchDetails Whether to fetch updated manga details.
     * @param fetchChapters Whether to fetch available chapters.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    suspend fun getPageList(chapter: SChapter): List<Page>

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
    suspend fun getMangaDetails(manga: SManga): SManga {
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaUpdate"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaUpdate"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}
