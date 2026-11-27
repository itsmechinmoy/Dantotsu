package eu.kanade.tachiyomi.extension.util

import ani.dantotsu.asyncMap
import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.api.NetworkLegacyExtensionRepo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import java.io.ByteArrayInputStream

/**
 * Shared HTTP fetch, payload decompression, repo URL normalization, and legacy `index_v2`
 * migration helpers for anime and manga extension APIs.
 */
object ExtensionRepoFetchHelper {

    fun cleanRepoUrl(url: String): String {
        return url.trim()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    fun ByteArray.decompressIfGzipped(): ByteArray {
        if (size < 2) return this
        val isGzip = this[0].toInt() and 0xFF == 0x1F && this[1].toInt() and 0xFF == 0x8B
        if (!isGzip) return this
        return try {
            ByteArrayInputStream(this).source().gzip().buffer().readByteArray()
        } catch (_: Throwable) {
            this
        }
    }

    fun buildCandidateUrls(repoUrl: String, cleanBase: String): List<String> {
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

    suspend fun downloadBytes(networkService: NetworkHelper, url: String): ByteArray? {
        return try {
            val response = networkService.client.newCall(GET(url)).awaitSuccess()
            val raw = response.body.bytes()
            if (raw.isEmpty()) null else raw.decompressIfGzipped()
        } catch (_: Throwable) {
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun <T> decodeJsonOrProto(bytes: ByteArray, json: Json, serializer: KSerializer<T>): T? {
        if (bytes.isEmpty()) return null
        return if (bytes[0] == 0x7B.toByte()) {
            runCatching { json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)) }.getOrNull()
        } else {
            runCatching { ProtoBuf.decodeFromByteArray(serializer, bytes) }.getOrNull()
        }
    }

    suspend fun <T> fetchExtensionListByUrl(
        networkService: NetworkHelper,
        json: Json,
        extensionListUrl: String,
        cleanBase: String,
        serializer: KSerializer<T>
    ): T? {
        val listUrl = if (extensionListUrl.startsWith("http")) {
            extensionListUrl
        } else {
            "$cleanBase/${extensionListUrl.removePrefix("/")}"
        }
        val bytes = downloadBytes(networkService, listUrl) ?: return null
        return decodeJsonOrProto(bytes, json, serializer)
    }

    fun updateRepoUrl(repoPref: PrefName, oldUrl: String, newUrl: String) {
        val current = PrefManager.getVal<Set<String>>(repoPref)
        if (current.contains(oldUrl)) {
            PrefManager.setVal(repoPref, current.minus(oldUrl).plus(newUrl))
        }
    }

    fun fallbackRepoUrl(repoUrl: String): String? {
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

    fun resolveApkUrl(apkName: String, repository: String): String {
        return if (apkName.startsWith("http")) {
            apkName
        } else {
            "${cleanRepoUrl(repository)}/apk/${apkName.removePrefix("/")}"
        }
    }

    suspend fun <T> fetchExtensionsForRepo(
        networkService: NetworkHelper,
        json: Json,
        repoUrl: String,
        repoPref: PrefName,
        originalUrl: String = repoUrl,
        parseLegacyJsonArray: (bytes: ByteArray, cleanBase: String, repository: String) -> List<T>?,
        parseStorePayload: suspend (bytes: ByteArray, cleanBase: String, originalUrl: String, repository: String) -> List<T>?
    ): List<T> {
        val cleanBase = cleanRepoUrl(repoUrl)
        val candidates = buildCandidateUrls(repoUrl, cleanBase)

        for (candidate in candidates) {
            try {
                val bytes = downloadBytes(networkService, candidate) ?: continue
                val firstByte = bytes.firstOrNull() ?: continue

                if (firstByte == 0x5B.toByte()) { // '[' - Legacy JSON array
                    val result = parseLegacyJsonArray(bytes, cleanBase, originalUrl)
                    if (result != null) return result
                    continue
                }

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
                            updateRepoUrl(repoPref, originalUrl, nextUrl)
                            return fetchExtensionsForRepo(
                                networkService = networkService,
                                json = json,
                                repoUrl = nextUrl,
                                repoPref = repoPref,
                                originalUrl = originalUrl,
                                parseLegacyJsonArray = parseLegacyJsonArray,
                                parseStorePayload = parseStorePayload
                            )
                        }
                    }
                }

                val storeResult = parseStorePayload(bytes, cleanBase, originalUrl, originalUrl)
                if (storeResult != null) return storeResult
            } catch (e: Throwable) {
                Logger.log("Failed candidate $candidate for $repoUrl: $e")
            }
        }
        return emptyList()
    }

    suspend fun <T> findExtensionsAcrossRepos(
        repoPref: PrefName,
        kindLabel: String,
        fetchForRepo: suspend (repoUrl: String, originalUrl: String) -> List<T>
    ): List<T> {
        val extensions = mutableListOf<T>()
        val repos = PrefManager.getVal<Set<String>>(repoPref).toList()

        repos.asyncMap { repo ->
            try {
                var repoExts = fetchForRepo(repo, repo)
                if (repoExts.isEmpty()) {
                    val fallback = fallbackRepoUrl(repo)
                    if (fallback != null) {
                        repoExts = fetchForRepo(fallback, repo)
                    }
                }
                synchronized(extensions) {
                    extensions.addAll(repoExts)
                }
            } catch (e: Throwable) {
                Logger.log("Failed to get $kindLabel extensions for $repo: $e")
            }
        }
        return extensions
    }
}
