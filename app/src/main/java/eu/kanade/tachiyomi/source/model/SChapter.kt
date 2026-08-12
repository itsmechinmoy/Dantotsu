@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SChapter : Serializable {

    var url: String

    var name: String

    /**
     * Volume number in string format.
     *
     * @since tachiyomix 1.7
     */
    var volume: String?
        get() = null
        set(_) {}

    var date_upload: Long

    var chapter_number: Float

    /**
     * Chapter number in string format.
     *
     * @since tachiyomix 1.7
     */
    var number: String?
        get() = if (chapter_number >= 0) {
            if (chapter_number % 1.0f == 0.0f) chapter_number.toInt().toString() else chapter_number.toString()
        } else null
        set(value) {
            val num = value?.toFloatOrNull()
            if (num != null) chapter_number = num
        }

    var scanlator: String?
        get() = scanlators.joinToString(", ").ifBlank { null }
        set(value) {
            scanlators = value?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        }

    var scanlators: List<String>
        get() = emptyList()
        set(_) {}

    /**
     * Language of the chapter content.
     *
     * @since tachiyomix 1.7
     */
    var language: String?
        get() = null
        set(_) {}

    /**
     * Whether the chapter is currently locked or otherwise inaccessible.
     *
     * @since tachiyomix 1.7
     */
    var locked: Boolean
        get() = false
        set(_) {}

    /**
     * Optional note associated with the chapter.
     *
     * @since tachiyomix 1.7
     */
    var note: String?
        get() = null
        set(_) {}

    /**
     * Extra metadata associated with the chapter.
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        number = other.number
        volume = other.volume
        scanlator = other.scanlator
        scanlators = other.scanlators
        language = other.language
        locked = other.locked
        note = other.note
        memo = other.memo
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}
