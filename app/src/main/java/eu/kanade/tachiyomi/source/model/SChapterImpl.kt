@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    @Transient
    private var _memo: JsonObject? = JsonObject(emptyMap())

    override var memo: JsonObject
        get() = _memo ?: JsonObject(emptyMap())
        set(value) {
            _memo = value
        }
}