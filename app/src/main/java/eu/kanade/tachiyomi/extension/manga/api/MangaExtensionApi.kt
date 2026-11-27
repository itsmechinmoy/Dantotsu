package eu.kanade.tachiyomi.extension.manga.api

import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.extension.api.IntOrStringSerializer
import eu.kanade.tachiyomi.extension.api.LongOrStringSerializer
import eu.kanade.tachiyomi.extension.api.NetworkExtensionStore
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.model.ExtensionContentWarning
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.extension.util.ExtensionRepoFetchHelper
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import uy.kohesive.injekt.injectLazy

class MangaExtensionApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    companion object {
        fun cleanRepoUrl(url: String): String = ExtensionRepoFetchHelper.cleanRepoUrl(url)
    }

    private suspend fun fetchMangaExtensionList(
        store: NetworkExtensionStore,
        cleanBase: String
    ): NetworkExtensionStore.ExtensionList? {
        if (store.extensionListUrl == null) return store.extensionList
        return ExtensionRepoFetchHelper.fetchExtensionListByUrl(
            networkService = networkService,
            json = json,
            extensionListUrl = store.extensionListUrl,
            cleanBase = cleanBase,
            serializer = NetworkExtensionStore.ExtensionList.serializer()
        )
    }

    private suspend fun parseMangaStorePayload(
        bytes: ByteArray,
        cleanBase: String,
        originalUrl: String,
        repository: String
    ): List<MangaExtension.Available>? {
        val store = ExtensionRepoFetchHelper.decodeJsonOrProto(
            bytes = bytes,
            json = json,
            serializer = NetworkExtensionStore.serializer()
        ) ?: return null

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
            val warning = when (ext.contentWarning) {
                NetworkExtensionStore.ContentWarning.NSFW -> ExtensionContentWarning.NSFW
                NetworkExtensionStore.ContentWarning.MIXED -> ExtensionContentWarning.MIXED
                else -> ExtensionContentWarning.SAFE
            }
            MangaExtension.Available(
                name = cleanName,
                pkgName = ext.packageName,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                libVersion = ext.extractLibVersion(),
                lang = primaryLang,
                isNsfw = warning.isRestricted,
                contentWarning = warning,
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
    ): List<MangaExtension.Available> = ExtensionRepoFetchHelper.fetchExtensionsForRepo(
        networkService = networkService,
        json = json,
        repoUrl = repoUrl,
        repoPref = PrefName.MangaExtensionRepos,
        originalUrl = originalUrl,
        parseLegacyJsonArray = ::parseLegacyJsonArray,
        parseStorePayload = ::parseMangaStorePayload
    )

    suspend fun findMangaExtensions(): List<MangaExtension.Available> = withContext(Dispatchers.IO) {
        ExtensionRepoFetchHelper.findExtensionsAcrossRepos(
            repoPref = PrefName.MangaExtensionRepos,
            kindLabel = "manga"
        ) { repoUrl, originalUrl -> fetchExtensionsForRepo(repoUrl, originalUrl) }
    }

    fun getMangaApkUrl(extension: MangaExtension.Available): String =
        ExtensionRepoFetchHelper.resolveApkUrl(extension.apkName, extension.repository)
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
