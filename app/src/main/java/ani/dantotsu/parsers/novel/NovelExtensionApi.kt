package ani.dantotsu.parsers.novel

import ani.dantotsu.asyncMap
import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.api.IntOrStringSerializer
import eu.kanade.tachiyomi.extension.api.LongOrStringSerializer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import okio.buffer
import okio.gzip
import okio.source
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream

class NovelExtensionApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    companion object {
        fun cleanRepoUrl(url: String): String {
            return url.trim()
                .removeSuffix("/")
                .removeSuffix("/index.min.json")
                .removeSuffix("/index.json")
                .removeSuffix("/repo.json")
                .removeSuffix("/index.pb")
                .removeSuffix("/")
        }
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (this.size < 2) return this
        val isGzip = this[0].toInt() and 0xFF == 0x1F && this[1].toInt() and 0xFF == 0x8B
        if (!isGzip) return this
        return try {
            ByteArrayInputStream(this).source().gzip().buffer().readByteArray()
        } catch (_: Throwable) {
            this
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return try {
            val response = networkService.client.newCall(GET(url)).awaitSuccess()
            val raw = response.body.bytes()
            if (raw.isEmpty()) null else raw.decompressIfGzipped()
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildCandidateUrls(repoUrl: String, cleanBase: String): List<String> {
        val candidateUrls = mutableListOf<String>()
        val trimmed = repoUrl.trim()
        if (trimmed.endsWith(".json") || trimmed.endsWith(".pb")) {
            candidateUrls.add(trimmed)
        }
        val defaultEndpoints = listOf(
            "$cleanBase/index.json",
            "$cleanBase/index.min.json",
            "$cleanBase/repo.json",
            "$cleanBase/index.pb"
        )
        for (endpoint in defaultEndpoints) {
            if (!candidateUrls.contains(endpoint)) {
                candidateUrls.add(endpoint)
            }
        }
        return candidateUrls
    }

    private fun parseNovelJsonArray(
        bytes: ByteArray,
        cleanBase: String,
        repository: String
    ): List<NovelExtension.Available>? {
        val bodyString = bytes.toString(Charsets.UTF_8)
        val list = runCatching {
            json.decodeFromString<List<NovelExtensionJsonObject>>(bodyString)
        }.getOrNull() ?: return null

        val badge = ExtensionRepoMetaHelper.getRepoBadgeName(repository)
        return list.filter {
            !it.apk.isNullOrBlank() && !it.pkg.isNullOrBlank() && it.apk.endsWith(".apk", ignoreCase = true)
        }.map { ext ->
            val sources = ext.sources?.map { s ->
                AvailableNovelSources(
                    id = s.id,
                    lang = s.lang,
                    name = s.name,
                    baseUrl = s.baseUrl
                )
            }.orEmpty()
            val iconUrl = ext.iconUrl ?: "$cleanBase/icon/${ext.pkg}.png"
            NovelExtension.Available(
                name = ext.name,
                pkgName = ext.pkg,
                versionName = ext.version,
                versionCode = ext.code,
                repository = repository,
                sources = sources,
                iconUrl = iconUrl,
                repoName = badge
            )
        }
    }

    private suspend fun fetchExtensionsForRepo(repoUrl: String): List<NovelExtension.Available> {
        val cleanBase = cleanRepoUrl(repoUrl)
        val candidates = buildCandidateUrls(repoUrl, cleanBase)

        for (candidate in candidates) {
            try {
                val bytes = downloadBytes(candidate) ?: continue
                val result = parseNovelJsonArray(bytes, cleanBase, repoUrl)
                if (!result.isNullOrEmpty()) return result
            } catch (e: Throwable) {
                Logger.log("Failed novel candidate $candidate for $repoUrl: $e")
            }
        }
        return emptyList()
    }

    suspend fun findNovelExtensions(): List<NovelExtension.Available> = withContext(Dispatchers.IO) {
        val extensions = mutableListOf<NovelExtension.Available>()
        val repos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toList()

        repos.asyncMap { repo ->
            try {
                var repoExts = fetchExtensionsForRepo(repo)
                if (repoExts.isEmpty()) {
                    val fallback = fallbackRepoUrl(repo)
                    if (fallback != null) {
                        repoExts = fetchExtensionsForRepo(fallback)
                    }
                }
                synchronized(extensions) {
                    extensions.addAll(repoExts)
                }
            } catch (e: Throwable) {
                Logger.log("Failed to get novel extensions for $repo: $e")
            }
        }
        extensions
    }

    fun getNovelApkUrl(extension: NovelExtension.Available): String {
        return if (extension.versionName.startsWith("http")) {
            extension.versionName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.pkgName.removePrefix("/")}.apk"
        }
    }

    private fun fallbackRepoUrl(repoUrl: String): String? {
        val stripped = cleanRepoUrl(repoUrl)
            .removePrefix("https://")
            .removePrefix("http://")
        val parts = stripped.split("/")
        if (parts.size < 3) return null
        val repoOwner = parts[1]
        val repoName = parts[2]
        val repoBranch = if (parts.size > 3) parts[3] else "main"
        return "https://gcore.jsdelivr.net/gh/$repoOwner/$repoName@$repoBranch"
    }
}

@Serializable
private data class NovelExtensionJsonObject(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "all",
    @Serializable(with = LongOrStringSerializer::class)
    val code: Long = 0,
    val version: String = "1.0",
    @Serializable(with = IntOrStringSerializer::class)
    val nsfw: Int = 0,
    val sources: List<NovelSourceJsonObject>? = null,
    @JsonNames("icon", "iconUrl")
    val iconUrl: String? = null,
)

@Serializable
private data class NovelSourceJsonObject(
    @Serializable(with = LongOrStringSerializer::class)
    val id: Long = 0L,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)
