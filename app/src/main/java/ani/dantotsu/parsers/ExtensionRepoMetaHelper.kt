package ani.dantotsu.parsers

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.api.NetworkExtensionStore
import eu.kanade.tachiyomi.extension.api.NetworkExtensionStoreMetaOnly
import eu.kanade.tachiyomi.extension.api.NetworkLegacyExtensionRepo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

@Serializable
data class ExtensionRepoMeta(
    val url: String = "",
    val name: String = "",
    val shortName: String? = null,
    val website: String = "",
    val discord: String? = null,
    val githubUrl: String? = null,
)

object ExtensionRepoMetaHelper {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val metaCache = ConcurrentHashMap<String, ExtensionRepoMeta>()
    private val installedRepoMap = ConcurrentHashMap<String, Pair<String, String?>>() // pkgName -> (repoUrl, repoName)
    @Volatile
    private var isLoaded = false

    private fun getClient(): OkHttpClient {
        return runCatching { Injekt.get<NetworkHelper>().client }.getOrElse { OkHttpClient() }
    }

    private fun ensureLoaded() {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                val rawMeta = PrefManager.getVal<String>(PrefName.ExtensionRepoMeta)
                if (!rawMeta.isNullOrBlank() && rawMeta != "{}") {
                    val map = json.decodeFromString<Map<String, ExtensionRepoMeta>>(rawMeta)
                    metaCache.putAll(map)
                }
            } catch (e: Throwable) {
                Logger.log("ExtensionRepoMetaHelper: Failed to load meta cache: $e")
            }

            try {
                val rawInstalled = PrefManager.getVal<String>(PrefName.InstalledExtensionRepos)
                if (!rawInstalled.isNullOrBlank() && rawInstalled != "{}") {
                    val map = json.decodeFromString<Map<String, Pair<String, String?>>>(rawInstalled)
                    installedRepoMap.putAll(map)
                }
            } catch (e: Throwable) {
                Logger.log("ExtensionRepoMetaHelper: Failed to load installed repo map: $e")
            }
            isLoaded = true
        }
    }

    private fun persistMetaCache() {
        try {
            val serialized = json.encodeToString(metaCache.toMap())
            PrefManager.setVal(PrefName.ExtensionRepoMeta, serialized)
        } catch (e: Throwable) {
            Logger.log("ExtensionRepoMetaHelper: Failed to persist meta cache: $e")
        }
    }

    private fun persistInstalledRepos() {
        try {
            val serialized = json.encodeToString(installedRepoMap.toMap())
            PrefManager.setVal(PrefName.InstalledExtensionRepos, serialized)
        } catch (e: Throwable) {
            Logger.log("ExtensionRepoMetaHelper: Failed to persist installed repos: $e")
        }
    }

    fun cleanRepoKey(url: String): String {
        return url.trim()
            .removeSuffix("/")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    fun cleanShownUrl(url: String): String {
        return url.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("raw.githubusercontent.com/")
            .removeSuffix("/")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    fun extractGitRepoPage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.trim()

        // Match GitHub raw URLs (e.g. raw.githubusercontent.com/user/repo/...)
        val rawGithubRegex = Regex("""(?:https?://)?raw\.githubusercontent\.com/([^/]+)/([^/]+)""")
        val rawMatch = rawGithubRegex.find(clean)
        if (rawMatch != null) {
            val (owner, repo) = rawMatch.destructured
            return "https://github.com/$owner/$repo"
        }

        // Match GitHub repository URLs
        val githubRegex = Regex("""(?:https?://)?github\.com/([^/]+)/([^/]+)""")
        val ghMatch = githubRegex.find(clean)
        if (ghMatch != null) {
            val (owner, repo) = ghMatch.destructured
            val cleanRepo = repo.removeSuffix(".git").substringBefore("/")
            return "https://github.com/$owner/$cleanRepo"
        }

        // Match GitLab URLs
        val gitlabRegex = Regex("""(?:https?://)?gitlab\.com/([^/]+)/([^/]+)""")
        val glMatch = gitlabRegex.find(clean)
        if (glMatch != null) {
            val (owner, repo) = glMatch.destructured
            val cleanRepo = repo.removeSuffix(".git").substringBefore("/-").substringBefore("/")
            return "https://gitlab.com/$owner/$cleanRepo"
        }

        // Match Codeberg URLs
        val codebergRegex = Regex("""(?:https?://)?codeberg\.org/([^/]+)/([^/]+)""")
        val cbMatch = codebergRegex.find(clean)
        if (cbMatch != null) {
            val (owner, repo) = cbMatch.destructured
            val cleanRepo = repo.removeSuffix(".git").substringBefore("/raw").substringBefore("/")
            return "https://codeberg.org/$owner/$cleanRepo"
        }

        return null
    }

    private fun extractBadgeNameFromUrl(url: String): String {
        val clean = url.trim()
        val rawGithubRegex = Regex("""(?:https?://)?raw\.githubusercontent\.com/([^/]+)/([^/]+)""")
        val rawMatch = rawGithubRegex.find(clean)
        if (rawMatch != null) {
            val (owner, repo) = rawMatch.destructured
            val genericRepos = setOf(
                "extensions", "anime-extensions", "manga-extensions",
                "novel-extensions", "plugins", "tachiyomi-extensions", "aniyomi-extensions"
            )
            if (genericRepos.contains(repo.lowercase())) {
                return when (owner.lowercase()) {
                    "keiyoushi" -> "Keiyoushi"
                    "aniyomiorg" -> "Aniyomi"
                    else -> owner
                }
            }
            return repo
        }
        val cleanShown = cleanShownUrl(url)
        return cleanShown.substringAfterLast("/").ifBlank { cleanShown }
    }

    fun getRepoPageUrl(repoUrl: String, meta: ExtensionRepoMeta? = null): String? {
        val m = meta ?: getCachedMeta(repoUrl)
        val gitPage = m?.githubUrl ?: extractGitRepoPage(repoUrl)
        if (gitPage != null) return gitPage

        val web = m?.website?.trim()
        if (!web.isNullOrBlank() && !web.endsWith(".json", ignoreCase = true) && !web.endsWith(".pb", ignoreCase = true)) {
            return web
        }
        return null
    }

    fun getRepoBadgeName(repoUrl: String, meta: ExtensionRepoMeta? = null): String {
        val m = meta ?: getCachedMeta(repoUrl)
        val badge = m?.shortName?.takeIf { it.isNotBlank() }
            ?: m?.name?.takeIf { it.isNotBlank() }
            ?: extractBadgeNameFromUrl(repoUrl)
        return badge.trim()
    }

    fun saveMeta(
        repoUrl: String,
        name: String = "",
        shortName: String? = null,
        website: String = "",
        discord: String? = null
    ): ExtensionRepoMeta {
        ensureLoaded()
        val key = cleanRepoKey(repoUrl)
        val githubUrl = extractGitRepoPage(repoUrl) ?: extractGitRepoPage(website)
        val existing = metaCache[key]
        val meta = ExtensionRepoMeta(
            url = repoUrl,
            name = name.ifBlank { existing?.name.orEmpty() },
            shortName = shortName?.takeIf { it.isNotBlank() } ?: existing?.shortName,
            website = website.ifBlank { existing?.website.orEmpty() },
            discord = discord ?: existing?.discord,
            githubUrl = githubUrl ?: existing?.githubUrl
        )
        metaCache[key] = meta
        persistMetaCache()
        return meta
    }

    fun saveInstalledExtensionRepo(pkgName: String, repoUrl: String, repoName: String? = null) {
        ensureLoaded()
        val name = repoName?.takeIf { it.isNotBlank() } ?: getRepoBadgeName(repoUrl)
        installedRepoMap[pkgName] = repoUrl to name
        persistInstalledRepos()
    }

    fun getCachedMeta(repoUrl: String): ExtensionRepoMeta? {
        ensureLoaded()
        val key = cleanRepoKey(repoUrl)
        return metaCache[key]
    }

    fun getInstalledExtensionRepo(pkgName: String): Pair<String, String?>? {
        ensureLoaded()
        return installedRepoMap[pkgName]
    }

    fun getInstalledRepo(pkgName: String): String? {
        ensureLoaded()
        return installedRepoMap[pkgName]?.first
    }

    fun getInstalledRepoName(pkgName: String): String? {
        ensureLoaded()
        return installedRepoMap[pkgName]?.second
    }

    suspend fun getMeta(repoUrl: String): ExtensionRepoMeta = withContext(Dispatchers.IO) {
        ensureLoaded()
        val key = cleanRepoKey(repoUrl)
        val cached = metaCache[key]
        if (cached != null && (cached.name.isNotBlank() || cached.shortName != null)) {
            return@withContext cached
        }

        val client = getClient()
        val candidates = mutableListOf<String>()

        if (repoUrl.endsWith("/repo.json")) {
            candidates.add(repoUrl)
        } else {
            val base = repoUrl.substringBeforeLast("/")
            candidates.add("$base/repo.json")
            if (!candidates.contains(repoUrl)) {
                candidates.add(repoUrl)
            }
        }

        var fetchedMeta: ExtensionRepoMeta? = null

        for (targetUrl in candidates) {
            try {
                val response = client.newCall(GET(targetUrl)).awaitSuccess()
                val rawBytes = response.body.bytes()
                if (rawBytes.isEmpty()) continue
                val responseBytes = rawBytes.decompressIfGzipped()
                if (responseBytes.isEmpty()) continue
                val firstByte = responseBytes[0]

                if (firstByte == 0x7B.toByte()) { // '{'
                    val bodyString = responseBytes.toString(Charsets.UTF_8)
                    val store = runCatching {
                        json.decodeFromString<NetworkExtensionStore>(bodyString)
                    }.getOrNull()

                    val legacyRepo = runCatching {
                        json.decodeFromString<NetworkLegacyExtensionRepo>(bodyString)
                    }.getOrNull()

                    val name = store?.name?.takeIf { it.isNotBlank() }
                        ?: legacyRepo?.meta?.name?.takeIf { it.isNotBlank() }
                        ?: ""
                    val shortName = store?.badgeLabel?.takeIf { it.isNotBlank() }
                        ?: legacyRepo?.meta?.shortName?.takeIf { it.isNotBlank() }
                    val website = store?.contact?.website?.takeIf { it.isNotBlank() }
                        ?: legacyRepo?.meta?.website?.takeIf { it.isNotBlank() }
                        ?: ""
                    val discord = store?.contact?.discord?.takeIf { it.isNotBlank() }

                    if (name.isNotBlank() || !shortName.isNullOrBlank() || website.isNotBlank() || discord != null) {
                        fetchedMeta = saveMeta(
                            repoUrl = repoUrl,
                            name = name,
                            shortName = shortName,
                            website = website,
                            discord = discord
                        )
                        break
                    }
                } else if (firstByte != 0x5B.toByte()) { // Protobuf
                    val metaOnly = runCatching {
                        ProtoBuf.decodeFromByteArray<NetworkExtensionStoreMetaOnly>(responseBytes)
                    }.getOrNull()

                    if (metaOnly != null && (metaOnly.name.isNotBlank() || metaOnly.badgeLabel.isNotBlank())) {
                        fetchedMeta = saveMeta(
                            repoUrl = repoUrl,
                            name = metaOnly.name,
                            shortName = metaOnly.badgeLabel,
                            website = metaOnly.contact.website,
                            discord = metaOnly.contact.discord
                        )
                        break
                    }
                }
            } catch (_: Throwable) {
                // Continue to next candidate
            }
        }

        if (fetchedMeta != null) {
            return@withContext fetchedMeta
        }

        // Fallback to URL-derived metadata
        val fallback = saveMeta(
            repoUrl = repoUrl,
            name = cached?.name?.takeIf { it.isNotBlank() } ?: cleanShownUrl(repoUrl),
            shortName = cached?.shortName ?: extractBadgeNameFromUrl(repoUrl),
            website = cached?.website.orEmpty(),
            discord = cached?.discord
        )
        fallback
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (size < 2) return this
        val isGzip = (this[0] == 0x1f.toByte()) && (this[1] == 0x8b.toByte())
        if (!isGzip) return this
        return try {
            GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
        } catch (_: Exception) {
            this
        }
    }
}
