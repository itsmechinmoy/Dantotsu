@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SEpisodeImpl : SEpisode {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var episode_number: Float = -1f

    override var fillermark: Boolean = false

    override var scanlator: String? = null

    override var summary: String? = null

    override var preview_url: String? = null

    @kotlin.jvm.Transient
    override var memo: JsonObject = JsonObject(emptyMap())

    private fun writeObject(out: ObjectOutputStream) {
        out.defaultWriteObject()
        runCatching { out.writeObject(memo.toString()) }
    }

    private fun readObject(`in`: ObjectInputStream) {
        `in`.defaultReadObject()
        memo = runCatching {
            val raw = `in`.readObject() as? String ?: "{}"
            Json.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
        }.getOrDefault(JsonObject(emptyMap()))
    }
}
