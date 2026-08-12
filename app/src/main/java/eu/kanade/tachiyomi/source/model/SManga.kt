@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SManga : Serializable {

    var url: String

    var title: String

    /**
     * Alternative titles for the manga.
     *
     * @since tachiyomix 1.7
     */
    var altTitles: List<String>

    var artist: String?

    var author: String?

    var description: String?

    @Deprecated("Provide SManga.genres instead")
    var genre: String?

    var genres: List<String>

    var status: Int

    var thumbnail_url: String?

    /**
     * URL of the manga's banner image.
     *
     * @since tachiyomix 1.7
     */
    var banner: String?

    /**
     * Primary language of the manga.
     *
     * @since tachiyomix 1.7
     */
    var language: String?

    /**
     * Age or content rating for the manga.
     */
    var contentRating: ContentRating

    /**
     * Source-provided rating score for the manga.
     *
     * @since tachiyomix 1.7
     */
    var score: Int?

    /**
     * Preferred reading mode provided by the source.
     *
     * @since tachiyomix 1.7
     */
    var readingMode: ReadingMode?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    /**
     * Extra metadata associated with the manga.
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.altTitles = altTitles
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.genres = genres
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.banner = banner
        it.language = language
        it.contentRating = contentRating
        it.score = score
        it.readingMode = readingMode
        it.update_strategy = update_strategy
        it.initialized = initialized
        it.memo = memo
    }

    enum class ContentRating {
        SAFE,
        SUGGESTIVE,
        ADULT,
    }

    enum class ReadingMode {
        RIGHT_TO_LEFT,
        LEFT_TO_RIGHT,
        LONG_STRIP,
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga {
            return SMangaImpl()
        }
    }
}

fun SManga.copyFrom(other: SManga) {
    if (other.author != null) author = other.author
    if (other.artist != null) artist = other.artist
    if (other.description != null) description = other.description
    if (other.genre != null) genre = other.genre
    if (other.genres.isNotEmpty()) genres = other.genres
    if (other.altTitles.isNotEmpty()) altTitles = other.altTitles
    if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
    if (other.banner != null) banner = other.banner
    if (other.language != null) language = other.language
    if (other.score != null) score = other.score
    if (other.readingMode != null) readingMode = other.readingMode
    contentRating = other.contentRating
    status = other.status
    update_strategy = other.update_strategy
    initialized = other.initialized
}
