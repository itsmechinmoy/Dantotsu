package eu.kanade.tachiyomi.extension.manga.api

import ani.dantotsu.asyncMap
import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.api.IntOrStringSerializer
import eu.kanade.tachiyomi.extension.api.LongOrStringSerializer
import eu.kanade.tachiyomi.extension.api.NetworkExtensionStore
import eu.kanade.tachiyomi.extension.api.NetworkLegacyExtensionRepo
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
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

class MangaExtensionApi {
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
    private suspend fun fetchMangaExtensionList(
        store: NetworkExtensionStore,
        cleanBase: String
    ): NetworkExtensionStore.ExtensionList? {
        if (store.extensionListUrl == null) return store.extensionList
        val listUrl = if (store.extensionListUrl.startsWith("http")) {
            store.extensionListUrl
        } else {
            "$cleanBase/${store.extensionListUrl.removePrefix("/")}"
        }
        val bytes = downloadBytes(listUrl) ?: return null
        return if (bytes.isNotEmpty() && bytes[0] == 0x7B.toByte()) {
            runCatching {
                json.decodeFromString<NetworkExtensionStore.ExtensionList>(bytes.toString(Charsets.UTF_8))
            }.getOrNull()
        } else if (bytes.isNotEmpty()) {
            runCatching {
                ProtoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes)
            }.getOrNull()
        } else {
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun parseMangaStorePayload(
        bytes: ByteArray,
        cleanBase: String,
        originalUrl: String,
        repository: String
    ): List<MangaExtension.Available>? {
        val firstByte = bytes.firstOrNull() ?: return null
        val store = if (firstByte == 0x7B.toByte()) {
            val body = bytes.toString(Charsets.UTF_8)
            runCatching { json.decodeFromString<NetworkExtensionStore>(body) }.getOrNull()
        } else {
            runCatching { ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) }.getOrNull()
        } ?: return null

        ExtensionRepoMetaHelper.saveMeta(
            originalUrl,
            name = store.name,
            shortName = store.badgeLabel,
            website = store.contact.website,
            discord = store.contact.discord
        )

        val badge = ExtensionRepoMetaHelper.getRepoBadgeName(repository)
        val resolvedList = fetchMangaExtensionList(store, cleanBase) ?: return null

        return resolvedList.extensions.filter { ext ->
            val libVer = ext.extractLibVersion()
            libVer in ExtensionLoader.MANGA_LIB_VERSION_MIN..(ExtensionLoader.MANGA_LIB_VERSION_MAX + 0.09)
        }.map { ext ->
            val sourcesMapped = ext.sources.map { src ->
                AvailableMangaSources(
                    id = src.id,
                    lang = src.language,
                    name = src.name,
                    baseUrl = src.homeUrl
                )
            }
            val primaryLang = ext.sources.firstOrNull()?.language ?: "all"
            val cleanName = ext.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: ").trim()
            val iconUrl = if (ext.resources.iconUrl.isNotBlank()) {
                ext.resources.iconUrl
            } else {
                "$cleanBase/icon/${ext.packageName}.png"
            }
            MangaExtension.Available(
                name = cleanName,
                pkgName = ext.packageName,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                libVersion = ext.extractLibVersion(),
                lang = primaryLang,
                isNsfw = ext.contentWarning == NetworkExtensionStore.ContentWarning.NSFW ||
                        ext.contentWarning == NetworkExtensionStore.ContentWarning.MIXED,
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
    ): List<MangaExtension.Available>? {
        val bodyString = bytes.toString(Charsets.UTF_8)
        val list = runCatching {
            json.decodeFromString<List<LegacyMangaExtensionJson>>(bodyString)
        }.getOrNull() ?: return null

        val badge = ExtensionRepoMetaHelper.getRepoBadgeName(repository)
        return list.filter { ext ->
            val libVer = ext.extractLibVersion()
            libVer in ExtensionLoader.MANGA_LIB_VERSION_MIN..(ExtensionLoader.MANGA_LIB_VERSION_MAX + 0.09)
        }.map { ext ->
            val sourcesMapped = ext.sources?.map { src ->
                AvailableMangaSources(
                    id = src.id,
                    lang = src.lang,
                    name = src.name,
                    baseUrl = src.baseUrl
                )
            }.orEmpty()
            val cleanName = ext.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: ").trim()
            val icon = ext.iconUrl ?: "$cleanBase/icon/${ext.pkg}.png"
            MangaExtension.Available(
                name = cleanName,
                pkgName = ext.pkg,
                versionName = ext.version,
                versionCode = ext.code,
                libVersion = ext.extractLibVersion(),
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
    ): List<MangaExtension.Available> {
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

                    val storeResult = parseMangaStorePayload(bytes, cleanBase, originalUrl, originalUrl)
                    if (storeResult != null) return storeResult
                }
            } catch (e: Throwable) {
                Logger.log("Failed candidate $candidate for $repoUrl: $e")
            }
        }
        return emptyList()
    }

    private fun updateRepoUrl(oldUrl: String, newUrl: String) {
        val current = PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos)
        if (current.contains(oldUrl)) {
            val updated = current.minus(oldUrl).plus(newUrl)
            PrefManager.setVal(PrefName.MangaExtensionRepos, updated)
        }
    }

    suspend fun findMangaExtensions(): List<MangaExtension.Available> = withContext(Dispatchers.IO) {
        val extensions = mutableListOf<MangaExtension.Available>()
        val repos = PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos).toList()

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
                Logger.log("Failed to get manga extensions for $repo: $e")
            }
        }
        extensions
    }

    fun getMangaApkUrl(extension: MangaExtension.Available): String {
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

private fun NetworkExtensionStore.Extension.extractLibVersion(): Double {
    extensionLib.toDoubleOrNull()?.let { return it }
    val parts = versionName.split('.')
    return if (parts.size >= 2) {
        val majorMinor = "${parts[0]}.${parts[1]}"
        majorMinor.toDoubleOrNull() ?: 1.0
    } else {
        versionName.toDoubleOrNull() ?: 1.0
    }
}

@Serializable
private data class LegacyMangaExtensionJson(
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
    val sources: List<LegacyMangaSourceJson>? = null,
    @JsonNames("icon", "iconUrl")
    val iconUrl: String? = null,
    val extensionLib: String? = null,
) {
    fun extractLibVersion(): Double {
        extensionLib?.toDoubleOrNull()?.let { return it }
        val parts = version.split('.')
        return if (parts.size >= 2) {
            val majorMinor = "${parts[0]}.${parts[1]}"
            majorMinor.toDoubleOrNull() ?: 1.0
        } else {
            version.toDoubleOrNull() ?: 1.0
        }
    }
}

@Serializable
private data class LegacyMangaSourceJson(
    @Serializable(with = LongOrStringSerializer::class)
    val id: Long = 0L,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)
