package ani.dantotsu.parsers.novel

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import rx.Observable

/**
 * Coordinator manager for both Dan Novel (APK-based) extensions and LNReader (JS-based) plugins.
 * Maintains a clean separation of concerns by delegating to [DanNovelExtensionManager]
 * and [LnReaderExtensionManager].
 */
@Inject
@SingleIn(AppScope::class)
class NovelExtensionManager(private val context: Context) {

    val danNovelManager = DanNovelExtensionManager(context)
    val lnReaderManager = LnReaderExtensionManager(context)

    val isInitialized: Boolean
        get() = danNovelManager.isInitialized

    val installedExtensionsFlow: StateFlow<List<NovelExtension.Installed>>
        get() = danNovelManager.installedExtensionsFlow

    val availableExtensionsFlow: StateFlow<List<NovelExtension.Available>>
        get() = danNovelManager.availableExtensionsFlow

    val allInstalledExtensionsFlow: Flow<List<NovelExtension>> =
        danNovelManager.installedExtensionsFlow.combine(lnReaderManager.installedPluginsFlow) { apk, js ->
            val apkList: List<NovelExtension> = apk
            val jsList: List<NovelExtension>  = js.map { NovelExtension.JsPlugin(it) }
            apkList + jsList
        }

    fun getSourceData(id: Long) = danNovelManager.getSourceData(id)

    suspend fun findAvailableExtensions() {
        danNovelManager.findAvailableExtensions()

        try {
            val novelRepos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toList()
            lnReaderManager.findAvailablePlugins(novelRepos)
        } catch (e: Exception) {
            Logger.log("NovelExtensionManager: Error finding LnReader plugins: ${e.message}")
        }
    }

    // --- APK Extension Delegation ---
    fun installExtension(extension: NovelExtension.Available): Observable<InstallStep> =
        danNovelManager.installExtension(extension)

    fun updateExtension(extension: NovelExtension.Installed): Observable<InstallStep> =
        danNovelManager.updateExtension(extension)

    fun cancelInstallUpdateExtension(extension: NovelExtension) =
        danNovelManager.cancelInstallUpdateExtension(extension)

    fun setInstalling(downloadId: Long) =
        danNovelManager.setInstalling(downloadId)

    fun updateInstallStep(downloadId: Long, step: InstallStep) =
        danNovelManager.updateInstallStep(downloadId, step)

    fun uninstallExtension(pkgName: String) =
        danNovelManager.uninstallExtension(pkgName)

    // --- LNReader JS Plugin Delegation ---
    suspend fun findAvailableLnReaderPlugins(extraRepos: List<String> = emptyList()) =
        lnReaderManager.findAvailablePlugins(extraRepos)

    suspend fun installLnReaderPlugin(item: LnReaderPluginItem): Boolean =
        lnReaderManager.installPlugin(item)

    fun uninstallLnReaderPlugin(pluginId: String) =
        lnReaderManager.uninstallPlugin(pluginId)

    suspend fun updateLnReaderPlugin(pluginId: String): Boolean =
        lnReaderManager.updatePlugin(pluginId)
}
