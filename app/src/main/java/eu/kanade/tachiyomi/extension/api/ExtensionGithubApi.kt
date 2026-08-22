package eu.kanade.tachiyomi.extension.api

import ani.dantotsu.asyncMap
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.novel.AvailableNovelSources
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import java.io.ByteArrayInputStream
import tachiyomi.core.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private fun List<ExtensionSourceJsonObject>.toAnimeExtensionSources(): List<AvailableAnimeSources> {
        return this.map {
            AvailableAnimeSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun cleanRepoUrl(url: String): String {
        return url.trim()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    private fun List<ExtensionJsonObject>.toAnimeExtensions(repository: String): List<AnimeExtension.Available> {
        val cleanRepo = cleanRepoUrl(repository)
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                val majorLibVersion = libVersion.toInt()
                majorLibVersion >= ExtensionLoader.ANIME_LIB_VERSION_MIN && majorLibVersion <= ExtensionLoader.ANIME_LIB_VERSION_MAX
            }
            .map {
                AnimeExtension.Available(
                    name = it.name.removePrefix("Aniyomi: ").removePrefix("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toAnimeExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = it.iconUrl ?: "$cleanRepo/icon/${it.pkg}.png",
                )
            }
    }

    private fun updateStoreUrl(oldUrl: String, newUrl: String, mediaType: MediaType) {
        val prefName = when (mediaType) {
            MediaType.ANIME -> PrefName.AnimeExtensionRepos
            MediaType.MANGA -> PrefName.MangaExtensionRepos
            MediaType.NOVEL -> PrefName.NovelExtensionRepos
        }
        val current = PrefManager.getVal<Set<String>>(prefName)
        if (current.contains(oldUrl)) {
            val updated = current.minus(oldUrl).plus(newUrl)
            PrefManager.setVal(prefName, updated)
        }
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (this.size < 2) return this
        val isGzip = (this[0].toInt() and 0xFF == 0x1F) && (this[1].toInt() and 0xFF == 0x8B)
        if (!isGzip) return this
        return try {
            ByteArrayInputStream(this).source().gzip().buffer().readByteArray()
        } catch (e: Throwable) {
            this
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun fetchExtensions(
        repoUrl: String,
        mediaType: MediaType,
        originalUrl: String = repoUrl
    ): List<ExtensionJsonObject> {
        var targetUrl = repoUrl.trim()
        if (!targetUrl.endsWith(".json") && !targetUrl.endsWith(".pb")) {
            targetUrl = "${cleanRepoUrl(repoUrl)}/index.pb"
        }

        try {
            val response = try {
                networkService.client
                    .newCall(GET(targetUrl))
                    .awaitSuccess()
            } catch (e: Throwable) {
                if (targetUrl.endsWith("index.pb")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/repo.json"
                    try {
                        networkService.client.newCall(GET(fallback)).awaitSuccess()
                    } catch (_: Throwable) {
                        val minFallback = "${cleanRepoUrl(targetUrl)}/index.min.json"
                        networkService.client.newCall(GET(minFallback)).awaitSuccess()
                    }
                } else if (targetUrl.endsWith("repo.json")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/index.min.json"
                    try {
                        networkService.client.newCall(GET(fallback)).awaitSuccess()
                    } catch (_: Throwable) {
                        val pbFallback = "${cleanRepoUrl(targetUrl)}/index.pb"
                        networkService.client.newCall(GET(pbFallback)).awaitSuccess()
                    }
                } else if (targetUrl.endsWith("index.min.json")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/index.pb"
                    networkService.client.newCall(GET(fallback)).awaitSuccess()
                } else {
                    throw e
                }
            }

            val rawBytes = response.body.bytes()
            if (rawBytes.isEmpty()) return emptyList()

            val responseBytes = rawBytes.decompressIfGzipped()
            if (responseBytes.isEmpty()) return emptyList()
            val firstByte = responseBytes[0]

            if (firstByte == 0x5B.toByte()) { // '['
                val bodyString = responseBytes.toString(Charsets.UTF_8)
                val list = json.decodeFromString<List<ExtensionJsonObject>>(bodyString)
                val hasDeprecation = mediaType == MediaType.MANGA && list.any { 
                    it.pkg.contains("keiyoushi") || it.name.contains("Outdated App", ignoreCase = true) || it.name.contains("Update to Mihon", ignoreCase = true)
                }
                if (hasDeprecation && !targetUrl.endsWith("index.pb")) {
                    val pbUrl = "${cleanRepoUrl(targetUrl)}/index.pb"
                    return runCatching { fetchExtensions(pbUrl, mediaType, originalUrl) }.getOrElse { list }
                }
                return list
            } else {
                val store = if (firstByte == 0x7B.toByte()) { // '{'
                    val bodyString = responseBytes.toString(Charsets.UTF_8)
                    if (bodyString.contains("\"index_v2\"") || bodyString.contains("\"indexV2\"")) {
                        val legacyRepo = json.decodeFromString<NetworkLegacyExtensionRepo>(bodyString)
                        val nextUrl = legacyRepo.indexV2
                        if (nextUrl != null) {
                            updateStoreUrl(originalUrl, nextUrl, mediaType)
                            return fetchExtensions(nextUrl, mediaType, originalUrl)
                        }
                    }
                    json.decodeFromString<NetworkExtensionStore>(bodyString)
                } else { // Protobuf
                    ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(responseBytes)
                }

                val resolvedList: NetworkExtensionStore.ExtensionList? = if (store.extensionListUrl != null) {
                    val listUrl = if (store.extensionListUrl.startsWith("http")) {
                        store.extensionListUrl
                    } else {
                        "${cleanRepoUrl(targetUrl)}/${store.extensionListUrl.removePrefix("/")}"
                    }
                    val listResponse = networkService.client.newCall(GET(listUrl)).awaitSuccess()
                    val listBytes = listResponse.body.bytes().decompressIfGzipped()
                    if (listBytes.isNotEmpty() && listBytes[0] == 0x7B.toByte()) { // '{'
                        json.decodeFromString<NetworkExtensionStore.ExtensionList>(listBytes.toString(Charsets.UTF_8))
                    } else if (listBytes.isNotEmpty()) {
                        ProtoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(listBytes)
                    } else {
                        null
                    }
                } else {
                    store.extensionList
                }

                if (resolvedList != null) {
                    val prefix = when (mediaType) {
                        MediaType.ANIME -> "Aniyomi: "
                        MediaType.MANGA -> "Tachiyomi: "
                        else -> ""
                    }
                    return resolvedList.extensions.map { ext ->
                        val sourcesMapped = ext.sources.map { src ->
                            ExtensionSourceJsonObject(
                                id = src.id,
                                lang = src.language,
                                name = src.name,
                                baseUrl = src.homeUrl
                            )
                        }
                        val primaryLang = ext.sources.firstOrNull()?.language ?: "all"
                        val prefixName = if (ext.name.startsWith(prefix)) ext.name else "$prefix${ext.name}"
                        ExtensionJsonObject(
                            name = prefixName,
                            pkg = ext.packageName,
                            apk = ext.resources.apkUrl,
                            lang = primaryLang,
                            code = ext.versionCode,
                            version = ext.versionName,
                            nsfw = if (ext.contentWarning == NetworkExtensionStore.ContentWarning.NSFW || ext.contentWarning == NetworkExtensionStore.ContentWarning.MIXED) 1 else 0,
                            hasReadme = 0,
                            hasChangelog = 0,
                            sources = sourcesMapped,
                            iconUrl = ext.resources.iconUrl,
                            extensionLib = ext.extensionLib,
                        )
                    }
                } else if (targetUrl.endsWith("repo.json")) {
                    val fallback = "${cleanRepoUrl(targetUrl)}/index.min.json"
                    return fetchExtensions(fallback, mediaType, originalUrl)
                }
            }
        } catch (e: Throwable) {
            Logger.log("Failed to fetch extensions from $repoUrl: $e")
        }
        return emptyList()
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> {
        return withIOContext {
            val extensions: ArrayList<AnimeExtension.Available> = arrayListOf()
            val repos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toMutableList()

            repos.asyncMap {
                try {
                    var repoExtensions = fetchExtensions(it, MediaType.ANIME)
                    if (repoExtensions.isEmpty()) {
                        val fallback = fallbackRepoUrl(it)
                        if (fallback != null) {
                            repoExtensions = fetchExtensions(fallback, MediaType.ANIME)
                        }
                    }
                    extensions.addAll(repoExtensions.toAnimeExtensions(it))
                } catch (e: Throwable) {
                    Logger.log("Failed to get anime extensions")
                    Logger.log(e)
                }
            }
            extensions
        }
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.apkName.removePrefix("/")}"
        }
    }

    private fun List<ExtensionSourceJsonObject>.toMangaExtensionSources(): List<AvailableMangaSources> {
        return this.map {
            AvailableMangaSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toMangaExtensions(repository: String): List<MangaExtension.Available> {
        val cleanRepo = cleanRepoUrl(repository)
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.MANGA_LIB_VERSION_MIN && libVersion <= ExtensionLoader.MANGA_LIB_VERSION_MAX
            }
            .map {
                MangaExtension.Available(
                    name = it.name.removePrefix("Tachiyomi: ").removePrefix("Mihon: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toMangaExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = it.iconUrl ?: "$cleanRepo/icon/${it.pkg}.png",
                )
            }
    }

    suspend fun findMangaExtensions(): List<MangaExtension.Available> {
        return withIOContext {
            val extensions: ArrayList<MangaExtension.Available> = arrayListOf()
            val repos = PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos).toMutableList()

            repos.asyncMap {
                try {
                    var repoExtensions = fetchExtensions(it, MediaType.MANGA)
                    if (repoExtensions.isEmpty()) {
                        val fallback = fallbackRepoUrl(it)
                        if (fallback != null) {
                            repoExtensions = fetchExtensions(fallback, MediaType.MANGA)
                        }
                    }
                    extensions.addAll(repoExtensions.toMangaExtensions(it))
                } catch (e: Throwable) {
                    Logger.log("Failed to get manga extensions")
                    Logger.log(e)
                }
            }
            extensions
        }
    }

    fun getMangaApkUrl(extension: MangaExtension.Available): String {
        return if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.apkName.removePrefix("/")}"
        }
    }

    suspend fun findNovelExtensions(): List<NovelExtension.Available> {
        return withIOContext {
            val extensions: ArrayList<NovelExtension.Available> = arrayListOf()
            val repos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toMutableList()

            repos.asyncMap {
                try {
                    var repoExtensions = fetchExtensions(it, MediaType.NOVEL)
                    if (repoExtensions.isEmpty()) {
                        val fallback = fallbackRepoUrl(it)
                        if (fallback != null) {
                            repoExtensions = fetchExtensions(fallback, MediaType.NOVEL)
                        }
                    }
                    extensions.addAll(repoExtensions.toNovelExtensions(it))
                } catch (e: Throwable) {
                    Logger.log("Failed to get novel extensions")
                    Logger.log(e)
                }
            }
            extensions
        }
    }

    private fun List<ExtensionJsonObject>.toNovelExtensions(repository: String): List<NovelExtension.Available> {
        return filter { !it.apk.isNullOrBlank() && !it.pkg.isNullOrBlank() && it.apk.endsWith(".apk", ignoreCase = true) }
            .mapNotNull { extension ->
                val sources = extension.sources?.map { source ->
                    ExtensionSourceJsonObject(
                        source.id,
                        source.lang,
                        source.name,
                        source.baseUrl,
                    )
                }
                val iconUrl = extension.iconUrl ?: "${cleanRepoUrl(repository)}/icon/${extension.pkg}.png"
                NovelExtension.Available(
                    extension.name,
                    extension.pkg,
                    extension.apk,
                    extension.code,
                    repository,
                    sources?.toNovelSources() ?: emptyList(),
                    iconUrl,
                )
            }
    }

    private fun List<ExtensionSourceJsonObject>.toNovelSources(): List<AvailableNovelSources> {
        return map { source ->
            AvailableNovelSources(
                source.id,
                source.lang,
                source.name,
                source.baseUrl,
            )
        }
    }

    fun getNovelApkUrl(extension: NovelExtension.Available): String {
        return if (extension.versionName.startsWith("http")) {
            extension.versionName
        } else {
            "${cleanRepoUrl(extension.repository)}/apk/${extension.pkgName.removePrefix("/")}.apk"
        }
    }

    private fun fallbackRepoUrl(repoUrl: String): String? {
        var fallbackRepoUrl = "https://gcore.jsdelivr.net/gh/"
        val strippedRepoUrl = cleanRepoUrl(repoUrl)
            .removePrefix("https://")
            .removePrefix("http://")
        val repoUrlParts = strippedRepoUrl.split("/")
        if (repoUrlParts.size < 3) {
            return null
        }
        val repoOwner = repoUrlParts[1]
        val repoName = repoUrlParts[2]
        fallbackRepoUrl += "$repoOwner/$repoName"
        val repoBranch = if (repoUrlParts.size > 3) {
            repoUrlParts[3]
        } else {
            "main"
        }
        fallbackRepoUrl += "@$repoBranch"
        return fallbackRepoUrl
    }
}

object LongOrStringSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongOrString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return try {
            decoder.decodeLong()
        } catch (e: Throwable) {
            decoder.decodeString().toLongOrNull() ?: 0L
        }
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive) {
            element.longOrNull ?: element.content.toLongOrNull() ?: 0L
        } else {
            0L
        }
    }
}

object IntOrStringSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntOrString", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return try {
            decoder.decodeInt()
        } catch (e: Throwable) {
            decoder.decodeString().toIntOrNull() ?: 0
        }
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive) {
            element.intOrNull ?: element.content.toIntOrNull() ?: 0
        } else {
            0
        }
    }
}

@Serializable
private data class ExtensionJsonObject(
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
    val sources: List<ExtensionSourceJsonObject>? = null,
    val iconUrl: String? = null,
    val extensionLib: String? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    @Serializable(with = LongOrStringSerializer::class)
    val id: Long = 0L,
    val lang: String = "",
    val name: String = "",
    val baseUrl: String = "",
)

private fun ExtensionJsonObject.extractLibVersion(): Double {
    extensionLib?.toDoubleOrNull()?.let { return it }
    val parts = version.split('.')
    return if (parts.size >= 2) {
        val major = parts[0].toDoubleOrNull() ?: 1.0
        if (major >= 10.0) {
            major
        } else {
            val majorMinor = "${parts[0]}.${parts[1]}"
            majorMinor.toDoubleOrNull() ?: major
        }
    } else {
        version.toDoubleOrNull() ?: 1.0
    }
}
