package eu.kanade.tachiyomi.extension.anime.api

import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.api.IntOrStringSerializer
import eu.kanade.tachiyomi.extension.api.LongOrStringSerializer
import eu.kanade.tachiyomi.extension.api.NetworkAnimeExtensionStore
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

class AnimeExtensionApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    companion object {
        fun cleanRepoUrl(url: String): String = ExtensionRepoFetchHelper.cleanRepoUrl(url)
    }

    private suspend fun fetchAnimeExtensionList(
        store: NetworkAnimeExtensionStore,
        cleanBase: String
    ): NetworkAnimeExtensionStore.ExtensionList? {
        if (store.extensionListUrl == null) return store.extensionList
        return ExtensionRepoFetchHelper.fetchExtensionListByUrl(
            networkService = networkService,
            json = json,
            extensionListUrl = store.extensionListUrl,
            cleanBase = cleanBase,
            serializer = NetworkAnimeExtensionStore.ExtensionList.serializer()
        )
    }

    private suspend fun parseAnimeStorePayload(
        bytes: ByteArray,
        cleanBase: String,
        originalUrl: String,
        repository: String
    ): List<AnimeExtension.Available>? {
        val store = ExtensionRepoFetchHelper.decodeJsonOrProto(
            bytes = bytes,
            json = json,
            serializer = NetworkAnimeExtensionStore.serializer()
        ) ?: return null

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
            val warning = when (ext.contentWarning) {
                NetworkAnimeExtensionStore.ContentWarning.NSFW -> ExtensionContentWarning.NSFW
                NetworkAnimeExtensionStore.ContentWarning.MIXED -> ExtensionContentWarning.MIXED
                else -> ExtensionContentWarning.SAFE
            }
            AnimeExtension.Available(
                name = cleanName,
                pkgName = ext.packageName,
                versionName = ext.versionName,
                versionCode = ext.versionCode,
                libVersion = ext.extractMajorLibVersion().toDouble(),
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
    ): List<AnimeExtension.Available> = ExtensionRepoFetchHelper.fetchExtensionsForRepo(
        networkService = networkService,
        json = json,
        repoUrl = repoUrl,
        repoPref = PrefName.AnimeExtensionRepos,
        originalUrl = originalUrl,
        parseLegacyJsonArray = ::parseLegacyJsonArray,
        parseStorePayload = ::parseAnimeStorePayload
    )

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> = withContext(Dispatchers.IO) {
        ExtensionRepoFetchHelper.findExtensionsAcrossRepos(
            repoPref = PrefName.AnimeExtensionRepos,
            kindLabel = "anime"
        ) { repoUrl, originalUrl -> fetchExtensionsForRepo(repoUrl, originalUrl) }
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String =
        ExtensionRepoFetchHelper.resolveApkUrl(extension.apkName, extension.repository)
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
