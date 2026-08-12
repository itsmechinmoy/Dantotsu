package ani.dantotsu.settings.saving.internal

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger

class Compat {
    companion object {
        fun importOldPrefs(context: Context) {
            migrateReposToStores(context)
            if (PrefManager.getVal(PrefName.HasUpdatedPrefs)) return
            val oldPrefs = context.getSharedPreferences("downloads_pref", Context.MODE_PRIVATE)
            val jsonString = oldPrefs.getString("downloads_key", null)
            PrefManager.setVal(PrefName.DownloadsKeys, jsonString)
            oldPrefs.edit().clear().apply()
            PrefManager.setVal(PrefName.HasUpdatedPrefs, true)
        }

        private fun migrateReposToStores(context: Context) {
            val migratedKey = "has_migrated_repos_to_stores_v2"
            val migratedPrefs = context.getSharedPreferences("migration_prefs", Context.MODE_PRIVATE)
            if (migratedPrefs.getBoolean(migratedKey, false)) return

            fun migrateSet(prefName: PrefName) {
                try {
                    val current = PrefManager.getVal<Set<String>>(prefName)
                    val migrated = current.map { url ->
                        var newUrl = url
                        if (newUrl.contains("github.com") && newUrl.contains("blob")) {
                            newUrl = newUrl.replace("github.com", "raw.githubusercontent.com")
                                .replace("/blob/", "/")
                        }
                        if (newUrl.endsWith("/index.min.json")) {
                            newUrl = newUrl.removeSuffix("/index.min.json") + "/repo.json"
                        } else if (newUrl.endsWith("/index.json")) {
                            newUrl = newUrl.removeSuffix("/index.json") + "/repo.json"
                        }
                        newUrl
                    }.toSet()
                    PrefManager.setVal(prefName, migrated)
                } catch (e: Exception) {
                    Logger.log("Failed to migrate repos for $prefName: $e")
                }
            }

            migrateSet(PrefName.AnimeExtensionRepos)
            migrateSet(PrefName.MangaExtensionRepos)
            migrateSet(PrefName.NovelExtensionRepos)

            migratedPrefs.edit().putBoolean(migratedKey, true).apply()
        }
    }
}
