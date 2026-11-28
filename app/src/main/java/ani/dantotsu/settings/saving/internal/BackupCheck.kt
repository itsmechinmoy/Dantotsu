package ani.dantotsu.settings.saving.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json

/**
 * Checks a settings backup before restore writes anything.
 *
 * Restore clears each preference file before importing it, so a backup that breaks halfway
 * (bad json, a value of the wrong type) used to leave settings half wiped. Everything is
 * checked first; restore only runs on a backup that passed.
 *
 * Locations this version doesn't know (removed in an update) are skipped, as before.
 */
sealed interface BackupCheck {
    data object Valid : BackupCheck
    data class Invalid(val reason: String) : BackupCheck

    companion object {
        private val VALUE_TYPES = setOf(
            "kotlin.Int",
            "kotlin.String",
            "kotlin.Boolean",
            "kotlin.Float",
            "kotlin.Long",
            "java.util.HashSet"
        )

        fun of(json: String): BackupCheck {
            if (json.isBlank()) return Invalid("empty backup")
            val root = try {
                Json.parseToJsonElement(json)
            } catch (_: Exception) {
                return Invalid("malformed json")
            }
            if (root !is JsonObject) return Invalid("top level must be an object")
            if (root.isEmpty()) return Invalid("no preference locations")
            val known = Location.entries.map { it.name }.toSet()
            for ((location, entries) in root) {
                if (location !in known && !location.startsWith("source_")) continue
                if (entries !is JsonObject) return Invalid("location is not an object: $location")
                for ((key, typeValue) in entries) {
                    if (typeValue !is JsonObject) return Invalid("entry is not an object: $location/$key")
                    val typeName = (typeValue["type"] as? JsonPrimitive)?.contentOrNull
                    if (typeName !in VALUE_TYPES) {
                        return Invalid("unsupported value type: $location/$key ($typeName)")
                    }
                    if (!typeValue.containsKey("value")) return Invalid("missing value: $location/$key")
                    shapeError(typeName!!, typeValue["value"])?.let {
                        return Invalid("$it: $location/$key ($typeName)")
                    }
                }
            }
            return Valid
        }

        /** The json value must match the declared type, or unpack would write null after clearing. */
        private fun shapeError(typeName: String, value: JsonElement?): String? {
            if (value == null || value is JsonNull) return "null value"
            if (typeName == "java.util.HashSet") {
                return if (value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString }) "not a string set" else null
            }
            return primitiveShapeError(typeName, value as? JsonPrimitive)
        }

        /** Strings must be quoted; booleans and numbers must not be. */
        private fun primitiveShapeError(typeName: String, primitive: JsonPrimitive?): String? {
            if (typeName == "kotlin.String") {
                return if (primitive == null || !primitive.isString) "not a string" else null
            }
            val bare = primitive?.takeIf { !it.isString }
            return when (typeName) {
                "kotlin.Boolean" -> if (bare?.booleanOrNull == null) "not a boolean" else null
                "kotlin.Int" -> if (bare?.intOrNull == null) "not an int" else null
                "kotlin.Long" -> if (bare?.longOrNull == null) "not a long" else null
                "kotlin.Float" -> floatShapeError(bare?.floatOrNull)
                else -> "unsupported type"
            }
        }

        private fun floatShapeError(parsed: Float?): String? = when {
            parsed == null -> "not a float"
            !parsed.isFinite() -> "non-finite float"
            else -> null
        }
    }
}
