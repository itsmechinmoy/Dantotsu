package ani.dantotsu.parsers.novel

import android.content.Context
import android.graphics.drawable.Drawable
import ani.dantotsu.media.MediaType
import ani.dantotsu.snackString
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rx.Observable
import tachiyomi.core.util.lang.withUIContext

class NovelExtensionManager(private val context: Context) {

    var isInitialized = false
        private set
    private val api = ExtensionGithubApi()
    private val installer by lazy { ExtensionInstaller(context) }
    private val iconMap = mutableMapOf<String, Drawable>()
    private val _installedNovelExtensionsFlow =
        MutableStateFlow(emptyList<NovelExtension.Installed>())
    val installedExtensionsFlow = _installedNovelExtensionsFlow.asStateFlow()
    private val _availableNovelExtensionsFlow =
        MutableStateFlow(emptyList<NovelExtension.Available>())
    val availableExtensionsFlow = _availableNovelExtensionsFlow.asStateFlow()
    private var availableNovelExtensionsSourcesData: Map<Long, NovelSourceData> = emptyMap()
    val lnReaderManager = LnReaderExtensionManager(context)
    val allInstalledExtensionsFlow: kotlinx.coroutines.flow.Flow<List<NovelExtension>> =
        _installedNovelExtensionsFlow.combine(lnReaderManager.installedPluginsFlow) { apk, js ->
            val apkList: List<NovelExtension> = apk
            val jsList: List<NovelExtension>  = js.map { NovelExtension.JsPlugin(it) }
            apkList + jsList
        }

    init {
        initNovelExtensions()
        ExtensionInstallReceiver().setNovelListener(NovelInstallationListener()).register(context)
    }

    private fun initNovelExtensions() {
        val novelExtensions = ExtensionLoader.loadNovelExtensions(context)
        _installedNovelExtensionsFlow.value = novelExtensions
            .filterIsInstance<NovelLoadResult.Success>()
            .map { it.extension }
        isInitialized = true
    }

    fun getSourceData(id: Long) = availableNovelExtensionsSourcesData[id]

    suspend fun findAvailableExtensions() {
        val extensions: List<NovelExtension.Available> = try {
            api.findNovelExtensions()
        } catch (e: Exception) {
            Logger.log("Error finding extensions: ${e.message}")
            withUIContext { snackString("Failed to get Novel extensions list") }
            emptyList()
        }
        _availableNovelExtensionsFlow.value = extensions
        updatedInstalledNovelExtensionsStatuses(extensions)
        setupAvailableNovelExtensionsSourcesDataMap(extensions)

        try {
            val novelRepos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toList()
            lnReaderManager.findAvailablePlugins(novelRepos)
        } catch (e: Exception) {
            Logger.log("Error finding LnReader plugins: ${e.message}")
        }
    }

    private fun setupAvailableNovelExtensionsSourcesDataMap(novelExtensions: List<NovelExtension.Available>) {
        if (novelExtensions.isEmpty()) return
        availableNovelExtensionsSourcesData = novelExtensions
            .flatMap { ext -> ext.sources.map { it.toNovelSourceData() } }
            .associateBy { it.id }
    }

    private fun updatedInstalledNovelExtensionsStatuses(
        availableNovelExtensions: List<NovelExtension.Available>
    ) {
        if (availableNovelExtensions.isEmpty()) return
        val mut = _installedNovelExtensionsFlow.value.toMutableList()
        var changed = false
        for ((i, ext) in mut.withIndex()) {
            val avail = availableNovelExtensions.find { it.pkgName == ext.pkgName }
            if (avail == null && !ext.isObsolete) {
                mut[i] = ext.copy(isObsolete = true); changed = true
            } else if (avail != null) {
                val hasUpdate = ext.updateExists(avail)
                if (ext.hasUpdate != hasUpdate) { mut[i] = ext.copy(hasUpdate = hasUpdate); changed = true }
            }
        }
        if (changed) _installedNovelExtensionsFlow.value = mut
    }

    fun installExtension(extension: NovelExtension.Available): Observable<InstallStep> =
        installer.downloadAndInstall(
            api.getNovelApkUrl(extension), extension.pkgName,
            extension.name, MediaType.NOVEL
        )

    fun updateExtension(extension: NovelExtension.Installed): Observable<InstallStep> {
        val avail = _availableNovelExtensionsFlow.value.find { it.pkgName == extension.pkgName }
            ?: return Observable.empty()
        return installExtension(avail)
    }

    fun cancelInstallUpdateExtension(extension: NovelExtension) =
        installer.cancelInstall(extension.pkgName)
    fun setInstalling(downloadId: Long) =
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    fun updateInstallStep(downloadId: Long, step: InstallStep) =
        installer.updateInstallStep(downloadId, step)
    fun uninstallExtension(pkgName: String) = installer.uninstallApk(pkgName)
    suspend fun findAvailableLnReaderPlugins(extraRepos: List<String> = emptyList()) =
        lnReaderManager.findAvailablePlugins(extraRepos)
    suspend fun installLnReaderPlugin(item: LnReaderPluginItem): Boolean =
        lnReaderManager.installPlugin(item)
    fun uninstallLnReaderPlugin(pluginId: String) =
        lnReaderManager.uninstallPlugin(pluginId)
    suspend fun updateLnReaderPlugin(pluginId: String): Boolean =
        lnReaderManager.updatePlugin(pluginId)
    private fun registerNewExtension(ext: NovelExtension.Installed) {
        _installedNovelExtensionsFlow.value += ext
    }

    private fun registerUpdatedExtension(ext: NovelExtension.Installed) {
        val mut = _installedNovelExtensionsFlow.value.toMutableList()
        val old = mut.find { it.pkgName == ext.pkgName }
        if (old != null) mut -= old
        mut += ext
        _installedNovelExtensionsFlow.value = mut
    }

    private fun unregisterNovelExtension(pkgName: String) {
        val ext = _installedNovelExtensionsFlow.value.find { it.pkgName == pkgName }
        if (ext != null) _installedNovelExtensionsFlow.value -= ext
    }

    private inner class NovelInstallationListener : ExtensionInstallReceiver.NovelListener {
        override fun onExtensionInstalled(extension: NovelExtension.Installed) =
            registerNewExtension(extension.withUpdateCheck())
        override fun onExtensionUpdated(extension: NovelExtension.Installed) =
            registerUpdatedExtension(extension.withUpdateCheck())
        override fun onPackageUninstalled(pkgName: String) =
            unregisterNovelExtension(pkgName)
    }

    private fun NovelExtension.Installed.withUpdateCheck(): NovelExtension.Installed =
        if (updateExists()) copy(hasUpdate = true) else this

    private fun NovelExtension.Installed.updateExists(
        avail: NovelExtension.Available? = null
    ): Boolean {
        val a = avail ?: _availableNovelExtensionsFlow.value.find { it.pkgName == pkgName }
        return a != null && a.versionCode > versionCode
    }
}
