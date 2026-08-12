@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SMangaImpl : SManga {

    override lateinit var url: String

    override lateinit var title: String

    override var altTitles: List<String> = emptyList()

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null
        set(value) {
            field = value
            _genres = null
        }

    @Transient
    private var _genres: List<String>? = null

    override var genres: List<String>
        get() {
            return _genres ?: genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct().orEmpty()
        }
        set(value) {
            _genres = value
            genre = value.joinToString(", ")
        }

    override var status: Int = 0

    override var thumbnail_url: String? = null

    override var banner: String? = null

    override var language: String? = null

    override var contentRating: SManga.ContentRating = SManga.ContentRating.SAFE

    override var score: Int? = null

    override var readingMode: SManga.ReadingMode? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false

    @Transient
    private var _memo: JsonObject? = JsonObject(emptyMap())

    override var memo: JsonObject
        get() = _memo ?: JsonObject(emptyMap())
        set(value) {
            _memo = value
        }
}
