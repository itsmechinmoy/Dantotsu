@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var volume: String? = null

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var number: String?
        get() = if (chapter_number >= 0) {
            if (chapter_number % 1.0f == 0.0f) chapter_number.toInt().toString() else chapter_number.toString()
        } else null
        set(value) {
            val num = value?.toFloatOrNull()
            if (num != null) chapter_number = num
        }

    override var scanlator: String?
        get() = scanlators.joinToString(", ").ifBlank { null }
        set(value) {
            scanlators = value?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        }

    override var scanlators: List<String> = emptyList()

    override var language: String? = null

    override var locked: Boolean = false

    override var note: String? = null

    @Transient
    private var _memo: JsonObject? = JsonObject(emptyMap())

    override var memo: JsonObject
        get() = _memo ?: JsonObject(emptyMap())
        set(value) {
            _memo = value
        }
}
