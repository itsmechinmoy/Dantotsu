package ani.dantotsu.settings.saving.internal

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import ani.dantotsu.settings.saving.PrefManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferencePackager {
    //map one or more preference maps for import/export

    companion object {

        /**
         * @return a json string of the packed preferences
         */
        fun pack(map: Map<Location, SharedPreferences>, includeExtensionSettings: Boolean = false): String {
            val prefsMap = packagePreferences(map).toMutableMap()
            if (includeExtensionSettings) {
                try {
                    val context = uy.kohesive.injekt.Injekt.get<android.app.Application>()
                    val sharedPrefsDir = java.io.File(context.filesDir.parent, "shared_prefs")
                    if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                        val files = sharedPrefsDir.listFiles { _, name -> name.startsWith("source_") && name.endsWith(".xml") }
                        files?.forEach { file ->
                            val prefName = file.name.removeSuffix(".xml")
                            val pref = context.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                            val prefMap = mutableMapOf<String, Any>()
                            pref.all.forEach { (key, value) ->
                                if (value != null) {
                                    val typeValueMap = mapOf(
                                        "type" to value.javaClass.kotlin.qualifiedName,
                                        "value" to value
                                    )
                                    prefMap[key] = typeValueMap
                                }
                            }
                            prefsMap[prefName] = prefMap
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors packing sources
                }
            }
            val gson = Gson()
            return gson.toJson(prefsMap)
        }

        /**
         * @return true if successful, false if error
         */
        fun unpack(decryptedJson: String): Boolean {
            val gson = Gson()
            val type = object :
                TypeToken<Map<String, Map<String, Map<String, Any>>>>() {}.type  //oh god...
            val rawPrefsMap: Map<String, Map<String, Map<String, Any>>> =
                gson.fromJson(decryptedJson, type)


            val deserializedMap = mutableMapOf<String, Map<String, Any?>>()

            rawPrefsMap.forEach { (prefName, prefValueMap) ->
                val innerMap = mutableMapOf<String, Any?>()

                prefValueMap.forEach { (key, typeValueMap) ->

                    val typeName = typeValueMap["type"] as? String
                    val value = typeValueMap["value"]

                    innerMap[key] =
                        when (typeName) {  //weirdly null sometimes so cast to string
                            "kotlin.Int" -> (value as? Double)?.toInt()
                            "kotlin.String" -> value.toString()
                            "kotlin.Boolean" -> value as? Boolean
                            "kotlin.Float" -> value.toString().toFloatOrNull()
                            "kotlin.Long" -> (value as? Double)?.toLong()
                            "java.util.HashSet" -> value as? ArrayList<*>
                            else -> null
                        }
                }
                deserializedMap[prefName] = innerMap
            }
            return unpackagePreferences(deserializedMap)
        }

        /**
         * @return a map of location names to a map of preference names to their values
         */
        private fun packagePreferences(map: Map<Location, SharedPreferences>): Map<String, Map<String, *>> {
            val result = mutableMapOf<String, Map<String, *>>()
            for ((location, preferences) in map) {
                val prefMap = mutableMapOf<String, Any>()
                preferences.all.forEach { (key, value) ->
                    val typeValueMap = mapOf(
                        "type" to value?.javaClass?.kotlin?.qualifiedName,
                        "value" to value
                    )
                    prefMap[key] = typeValueMap
                }
                result[location.name] = prefMap
            }
            return result
        }

        /**
         * @return true if successful, false if error
         */
        private fun unpackagePreferences(map: Map<String, Map<String, *>>): Boolean {
            var success = true
            map.forEach { (location, prefMap) ->
                val locationEnum = Location.entries.find { it.name == location }
                if (locationEnum != null) {
                    if (locationEnum != Location.ExtensionSettings) {
                        if (!PrefManager.importAllPrefs(prefMap, locationEnum)) {
                            success = false
                        }
                    }
                } else if (location.startsWith("source_")) {
                    if (!importSourcePrefs(prefMap, location)) {
                        success = false
                    }
                }
            }
            return success
        }

        private fun importSourcePrefs(prefs: Map<String, *>, prefName: String): Boolean {
            try {
                val context = uy.kohesive.injekt.Injekt.get<android.app.Application>()
                val pref = context.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                var hadError = false
                pref.edit().clear().apply()
                with(pref.edit()) {
                    prefs.forEach { (key, value) ->
                        if (value != null) {
                            try {
                                when (value) {
                                    is Boolean -> putBoolean(key, value)
                                    is Int -> putInt(key, value)
                                    is Float -> putFloat(key, value)
                                    is Long -> putLong(key, value)
                                    is String -> putString(key, value)
                                    is ArrayList<*> -> putStringSet(key, value.map { it.toString() }.toSet())
                                    is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
                                    else -> hadError = true
                                }
                            } catch (e: Exception) {
                                hadError = true
                            }
                        }
                    }
                    apply()
                }
                return !hadError
            } catch (e: Exception) {
                return false
            }
        }
    }
}
