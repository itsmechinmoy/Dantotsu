package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import rx.Observable
import tachiyomi.core.util.lang.awaitSingle
import tachiyomi.core.util.lang.withIOContext

interface AnimeCatalogueSource : AnimeSource {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Get the related anime list for an anime.
     *
     * @since extensions-lib 17
     * @param anime the anime to fetch related anime for.
     * @return the related anime list for the anime.
     */
    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    /**
     * Get a page with a list of anime.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    suspend fun getPopularAnime(page: Int): AnimesPage = withIOContext {
        fetchPopularAnime(page).awaitSingle()
    }

    /**
     * Get a page with a list of anime.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    @Suppress("DEPRECATION")
    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = withIOContext {
        fetchSearchAnime(page, query, filters).awaitSingle()
    }

    /**
     * Get a page with a list of latest anime updates.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    suspend fun getLatestUpdates(page: Int): AnimesPage = withIOContext {
        fetchLatestUpdates(page).awaitSingle()
    }

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): AnimeFilterList

    // Should be replaced as soon as Anime Extension reach 1.5
    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularAnime"),
    )
    fun fetchPopularAnime(page: Int): Observable<AnimesPage> = throw IllegalStateException("Not used")

    // Should be replaced as soon as Anime Extension reach 1.5
    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchAnime"),
    )
    fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> = throw IllegalStateException("Not used")

    // Should be replaced as soon as Anime Extension reach 1.5
    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = throw IllegalStateException("Not used")
}
