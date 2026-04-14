package ani.dantotsu.parsers.novel
import android.content.Context
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class LnReaderExtensionManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pluginDir: File
        get() = File(context.filesDir, "lnreader_plugins").also { it.mkdirs() }

    private fun safePluginDir(pluginId: String): File {
        val sanitized = pluginId.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val dir = File(pluginDir, sanitized)
        require(dir.canonicalPath.startsWith(pluginDir.canonicalPath)) {
            "Invalid plugin ID: path traversal detected in '$pluginId'"
        }
        return dir
    }

    private val _installedPluginsFlow = MutableStateFlow(emptyList<LnReaderInstalledPlugin>())
    val installedPluginsFlow = _installedPluginsFlow.asStateFlow()

    private val _availablePluginsFlow = MutableStateFlow(emptyList<LnReaderPluginItem>())
    val availablePluginsFlow = _availablePluginsFlow.asStateFlow()
    private val defaultRepoUrls = emptyList<String>()

    init {
        loadInstalledFromDisk()
    }

    private fun loadInstalledFromDisk() {
        val plugins = mutableListOf<LnReaderInstalledPlugin>()
        val dirs = pluginDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val meta = File(dir, "meta.json")
            if (!meta.exists()) continue
            try {
                val plugin = json.decodeFromString<LnReaderInstalledPlugin>(meta.readText())
                plugins.add(plugin)
            } catch (e: Exception) {
                Logger.log("LnReaderExtensionManager: failed to load meta for ${dir.name}: ${e.message}")
            }
        }
        _installedPluginsFlow.value = plugins
    }

    private fun saveMetaToDisk(plugin: LnReaderInstalledPlugin) {
        val dir = safePluginDir(plugin.id).also { it.mkdirs() }
        File(dir, "meta.json").writeText(json.encodeToString(plugin))
    }

    fun getJsFile(pluginId: String): File =
        File(safePluginDir(pluginId), "index.js")

    suspend fun findAvailablePlugins(
        extraRepoUrls: List<String> = emptyList()
    ): List<LnReaderPluginItem> = withContext(Dispatchers.IO) {
        val all = mutableListOf<LnReaderPluginItem>()
        val seen = mutableSetOf<String>()

        (defaultRepoUrls + extraRepoUrls).forEach { repoUrl ->
            try {
                val response = http.newCall(Request.Builder().url(repoUrl).build()).execute()
                val body = response.body?.string() ?: return@forEach
                val items = json.decodeFromString<List<LnReaderPluginItem>>(body)
                items.forEach { item ->
                    if (seen.add(item.id)) all.add(item)
                }
            } catch (e: Exception) {
                Logger.log("LnReaderExtensionManager: failed to fetch repo $repoUrl: ${e.message}")
            }
        }
        
        val installedMap = _installedPluginsFlow.value.associateBy { it.id }
        val withUpdate = all.map { item ->
            val installed = installedMap[item.id]
            if (installed != null && isNewerVersion(item.version, installed.version))
                item.copy(hasUpdate = true)
            else item
        }

        _availablePluginsFlow.value = withUpdate
        withUpdate
    }

    suspend fun installPlugin(item: LnReaderPluginItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = http.newCall(
                Request.Builder()
                    .url(item.url)
                    .header("pragma", "no-cache")
                    .header("cache-control", "no-cache")
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                Logger.log("LnReaderExtensionManager: HTTP ${response.code} for ${item.url}")
                return@withContext false
            }

            val jsCode = response.body?.string()
                ?: return@withContext false

            val dir = safePluginDir(item.id).also { it.mkdirs() }
            File(dir, "index.js").writeText(jsCode)

            val plugin = LnReaderInstalledPlugin(
                id        = item.id,
                name      = item.name,
                site      = item.site,
                lang      = item.lang,
                version   = item.version,
                iconUrl   = item.iconUrl,
                jsFilePath = File(dir, "index.js").absolutePath,
                hasUpdate = false,
            )
            saveMetaToDisk(plugin)

            val current = _installedPluginsFlow.value.toMutableList()
            val idx = current.indexOfFirst { it.id == plugin.id }
            if (idx >= 0) current[idx] = plugin else current.add(plugin)
            _installedPluginsFlow.value = current
            _availablePluginsFlow.value = _availablePluginsFlow.value.map {
                if (it.id == item.id) it.copy(hasUpdate = false) else it
            }

            Logger.log("LnReaderExtensionManager: installed ${plugin.name} v${plugin.version}")
            true
        } catch (e: Exception) {
            Logger.log("LnReaderExtensionManager: install failed for ${item.id}: ${e.message}")
            false
        }
    }

    fun uninstallPlugin(pluginId: String) {
        safePluginDir(pluginId).deleteRecursively()
        _installedPluginsFlow.value = _installedPluginsFlow.value.filter { it.id != pluginId }
    }

    suspend fun updatePlugin(pluginId: String): Boolean {
        val item = _availablePluginsFlow.value.find { it.id == pluginId }
            ?: return false
        return installPlugin(item)
    }

    private fun isNewerVersion(remote: String, installed: String): Boolean {
        if (remote == installed) return false
        val rParts = remote.split("-", limit = 2)[0].split(".").mapNotNull { it.toIntOrNull() }
        val iParts = installed.split("-", limit = 2)[0].split(".").mapNotNull { it.toIntOrNull() }
        if (rParts.isEmpty() || iParts.isEmpty()) return remote != installed
        val len = maxOf(rParts.size, iParts.size)
        for (i in 0 until len) {
            val r = rParts.getOrElse(i) { 0 }
            val l = iParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
