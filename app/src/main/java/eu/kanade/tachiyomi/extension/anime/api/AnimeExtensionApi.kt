package eu.kanade.tachiyomi.extension.anime.api

import ani.dantotsu.asyncMap
import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.api.IntOrStringSerializer
import eu.kanade.tachiyomi.extension.api.LongOrStringSerializer
import eu.kanade.tachiyomi.extension.api.NetworkAnimeExtensionStore
import eu.kanade.tachiyomi.extension.api.NetworkLegacyExtensionRepo
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream

class AnimeExtensionApi {
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

    private fun buildCandidateUrls(repoUrl: String, cleanBase: String): List<String> {
        val candidateUrls = mutableListOf<String>()
        val trimmed = repoUrl.trim()
        if (trimmed.endsWith(".json") || trimmed.endsWith(".pb")) {
            candidateUrls.add(trimmed)
        }
        val defaultEndpoints = listOf(
            "$cleanBase/index.pb",
            "$cleanBase/repo.json",
            "$cleanBase/index.min.json",
            "$cleanBase/index.json"
        )
        for (endpoint in defaultEndpoints) {
            if (!candidateUrls.contains(endpoint)) {
                candidateUrls.add(endpoint)
            }
        }
        return candidateUrls
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

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchAnimeExtensionList(
        store: NetworkAnimeExtensionStore,
        cleanBase: String
    ): NetworkAnimeExtensionStore.ExtensionList? {
        if (store.extensionListUrl == null) return store.extensionList
        val listUrl = if (store.extensionListUrl.startsWith("http")) {
            store.extensionListUrl
        } else {
            "$cleanBase/${store.extensionListUrl.removePrefix("/")}"
        }
        val bytes = downloadBytes(listUrl) ?: return null
        return if (bytes.isNotEmpty() && bytes[0] == 0x7B.toByte()) {
            runCatching {
                json.decodeFromString<NetworkAnimeExtensionStore.ExtensionList>(bytes.toString(Charsets.UTF_8))
            }.getOrNull()
        } else if (bytes.isNotEmpty()) {
            runCatching {
                ProtoBuf.decodeFromByteArray<NetworkAnimeExtensionStore.ExtensionList>(bytes)
            }.getOrNull()
        } else {
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun parseAnimeStorePayload(
        bytes: ByteArray,
        cleanBase: String,
        originalUrl: String,
        repository: String
    ): List<AnimeExtension.Available>? {
        val firstByte = bytes.firstOrNull() ?: return null
        val store = if (firstByte == 0x7B.toByte()) {
            val body = bytes.toString(Charsets.UTF_8)
            runCatching { json.decodeFromString<NetworkAnimeExtensionStore>(body) }.getOrNull()
        } else {
            runCatching { ProtoBuf.decodeFromByteArray<NetworkAnimeExtensionStore>(bytes) }.getOrNull()
        } ?: return null

        ExtensionRepoMetaHelper.saveMeta(
            originalUrl,
            name = store.name,
            shortName = store.badgeLabel,
            website = store.contact.website,
            discord = store.contact.discord
        )

        val badge = ExtensionRepoMetaHelper.getRepoBadgeName(repository)
        val resolvedList = fetchAnimeExtensionList(store, cleanBase) ?: return null

        return resolvedList.extensions.filter { ext ->
            val majorLib = ext.extractMajorLibVersion()
            majorLib in ExtensionLoader.ANIME_LIB_VERSION_MIN..ExtensionLoader.ANIME_LIB_VERSION_MAX
        }.map { ext ->
            val sourcesMapped = ext.sources.map { src ->
                AvailableAnimeSources(
                    id = src.id,
                    lang = src.language,
                    name = src.name,
                    baseUrl = src.homeUrl
                )
            }
            val primaryLang = ext.sources.firstOrNull()?.language ?: "all"
            val cleanName = ext.name.removePrefix("Aniyomi: ").trim()
            val iconUrl = if (ext.resources.iconUrl.isNotBlank()) {
                ext.resources.iconUrl
            } else {
                "$cleanBase/icon/${ext.packageName}.png"
            }
            AnimeExtension.Available(
                name = cleanName,
                pkgName = ext.packageName,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                libVersion = ext.extractMajorLibVersion().toDouble(),
                lang = primaryLang,
                isNsfw = ext.contentWarning == NetworkAnimeExtensionStore.ContentWarning.NSFW ||
                        ext.contentWarning == NetworkAnimeExtensionStore.ContentWarning.MIXED,
                hasReadme = false,
                hasChangelog = false,
                sources = sourcesMapped,
                apkName = ext.resources.apkUrl,
                iconUrl = iconUrl,
                repository = repository,
                repoName = badge
            )
        }
    }

    private fun parseLegacyJsonArray(
        bytes: ByteArray,
        cleanBase: String,
        repository: String
    ): List<AnimeExtension.Available>? {
        val bodyString = bytes.toString(Charsets.UTF_8)
        val list = runCatching {
            json.decodeFromString<List<LegacyAnimeExtensionJson>>(bodyString)
        }.getOrNull() ?: return null

        val badge = ExtensionRepoMetaHelper.getRepoBadgeName(repository)
        return list.filter { ext ->
            val majorLib = ext.extractMajorLibVersion()
            majorLib in ExtensionLoader.ANIME_LIB_VERSION_MIN..ExtensionLoader.ANIME_LIB_VERSION_MAX
        }.map { ext ->
            val sourcesMapped = ext.sources?.map { src ->
                AvailableAnimeSources(
                    id = src.id,
                    lang = src.lang,
                    name = src.name,
                    baseUrl = src.baseUrl
                )
            }.orEmpty()
            val cleanName = ext.name.removePrefix("Aniyomi: ").trim()
            val icon = ext.iconUrl ?: "$cleanBase/icon/${ext.pkg}.png"
            AnimeExtension.Available(
                name = cleanName,
                pkgName = ext.pkg,
                versionName = ext.version,
                versionCode = ext.code,
                libVersion = ext.extractMajorLibVersion().toDouble(),
                lang = ext.lang,
                isNsfw = ext.nsfw == 1,
                hasReadme = ext.hasReadme == 1,
                hasChangelog = ext.hasChangelog == 1,
                sources = sourcesMapped,
                apkName = ext.apk,
                iconUrl = icon,
                repository = repository,
                repoName = badge
            )
        }
    }

    private suspend fun fetchExtensionsForRepo(
        repoUrl: String,
        originalUrl: String = repoUrl
    ): List<AnimeExtension.Available> {
        val cleanBase = cleanRepoUrl(repoUrl)
        val candidates = buildCandidateUrls(repoUrl, cleanBase)

        for (candidate in candidates) {
            try {
                val bytes = downloadBytes(candidate) ?: continue
                val firstByte = bytes.firstOrNull() ?: continue

                if (firstByte == 0x5B.toByte()) { // '[' - Legacy JSON array
                    val result = parseLegacyJsonArray(bytes, cleanBase, originalUrl)
                    if (result != null) return result
                } else {
                    if (firstByte == 0x7B.toByte()) {
                        val bodyString = bytes.toString(Charsets.UTF_8)
                        if (bodyString.contains("\"index_v2\"") || bodyString.contains("\"indexV2\"")) {
                            val legacyRepo = runCatching {
                                json.decodeFromString<NetworkLegacyExtensionRepo>(bodyString)
                            }.getOrNull()
                            if (legacyRepo?.meta != null) {
                                ExtensionRepoMetaHelper.saveMeta(
                                    originalUrl,
                                    name = legacyRepo.meta.name,
                                    shortName = legacyRepo.meta.shortName,
                                    website = legacyRepo.meta.website,
                                    discord = null
                                )
                            }
                            val nextUrl = legacyRepo?.indexV2
                            if (!nextUrl.isNullOrBlank()) {
                                updateRepoUrl(originalUrl, nextUrl)
                                return fetchExtensionsForRepo(nextUrl, originalUrl)
                            }
                        }
                    }

                    val storeResult = parseAnimeStorePayload(bytes, cleanBase, originalUrl, originalUrl)
                    if (storeResult != null) return storeResult
                }
            } catch (e: Throwable) {
                Logger.log("Failed candidate $candidate for $repoUrl: $e")
            }
        }
        return emptyList()
    }

    private fun updateRepoUrl(oldUrl: String, newUrl: String) {
        val current = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos)
        if (current.contains(oldUrl)) {
            val updated = current.minus(oldUrl).plus(newUrl)
            PrefManager.setVal(PrefName.AnimeExtensionRepos, updated)
        }
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> = withContext(Dispatchers.IO) {
        val extensions = mutableListOf<AnimeExtension.Available>()
        val repos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toList()

        repos.asyncMap { repo ->
            try {
                var repoExts = fetchExtensionsForRepo(repo)
                if (repoExts.isEmpty()) {
                    val fallback = fallbackRepoUrl(repo)
                    if (fallback != null) {
                        repoExts = fetchExtensionsForRepo(fallback, repo)
                    }
                }
                synchronized(extensions) {
                    extensions.addAll(repoExts)
                }
            } catch (e: Throwable) {
                Logger.log("Failed to get anime extensions for $repo: $e")
            }
        }
        extensions
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.apkName.removePrefix("/")}"
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

private fun NetworkAnimeExtensionStore.Extension.extractMajorLibVersion(): Int {
    if (extensionLib.isNotBlank()) {
        val clean = extensionLib.substringBefore(':')
        clean.toDoubleOrNull()?.let { return it.toInt() }
    }
    val parts = versionName.split('.')
    return parts.firstOrNull()?.toIntOrNull() ?: 0
}

@Serializable
private data class LegacyAnimeExtensionJson(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "all",
    @Serializable(with = LongOrStringSerializer::class)
    val code: Long = 0,
    val version: String = "1.0",
    @Serializable(with = IntOrStringSerializer::class)
    val nsfw: Int = 0,
    @Serializable(with = IntOrStringSerializer::class)
    val hasReadme: Int = 0,
    @Serializable(with = IntOrStringSerializer::class)
    val hasChangelog: Int = 0,
    val sources: List<LegacyAnimeSourceJson>? = null,
    @JsonNames("icon", "iconUrl")
    val iconUrl: String? = null,
    val extensionLib: String? = null,
) {
    fun extractMajorLibVersion(): Int {
        if (!extensionLib.isNullOrBlank()) {
            val clean = extensionLib.substringBefore(':')
            clean.toDoubleOrNull()?.let { return it.toInt() }
        }
        val parts = version.split('.')
        return parts.firstOrNull()?.toIntOrNull() ?: 0
    }
}

@Serializable
private data class LegacyAnimeSourceJson(
    @Serializable(with = LongOrStringSerializer::class)
    val id: Long = 0L,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)
