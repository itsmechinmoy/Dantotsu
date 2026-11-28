package ani.dantotsu.media

import ani.dantotsu.client
import ani.dantotsu.isOnline
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.TmdbService
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.currContext
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object CarouselLogoResolver {
    private const val TAG = "CarouselLogoResolver"
    private const val HIT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
    private const val MISS_TTL_MS = 6L * 60 * 60 * 1000L

    private data class CacheEntry(
        val url: String?,
        val expiresAt: Long
    )

    private data class AniZipResult(
        val logoUrl: String? = null,
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val isMovie: Boolean? = null
    )

    private data class RelatedMediaHint(
        val anilistId: Int,
        val malId: Int?,
        val titles: List<String>,
        val year: Int?,
        val isMovie: Boolean,
        val isAdaptation: Boolean
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    @Volatile
    private var diskLoaded = false

    private fun ensureDiskCacheLoaded() {
        if (diskLoaded) return
        synchronized(this) {
            if (diskLoaded) return
            diskLoaded = true
            runCatching {
                val ctx = currContext() ?: return@runCatching
                val file = File(ctx.cacheDir, "clearlogo_cache_v2.json")
                if (!file.exists()) return@runCatching
                val root = json.parseToJsonElement(file.readText()).jsonObject
                val now = System.currentTimeMillis()
                root.forEach { (key, value) ->
                    val obj = value as? JsonObject ?: return@forEach
                    val exp = obj["exp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@forEach
                    if (exp > now) {
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull
                        cache[key] = CacheEntry(url, exp)
                    }
                }
            }
        }
    }

    private fun persistCacheAsync() {
        runCatching {
            val ctx = currContext() ?: return@runCatching
            val file = File(ctx.cacheDir, "clearlogo_cache_v2.json")
            val now = System.currentTimeMillis()
            val payload = kotlinx.serialization.json.buildJsonObject {
                cache.entries.filter { it.value.expiresAt > now }.take(500).forEach { (k, v) ->
                    put(k, kotlinx.serialization.json.buildJsonObject {
                        if (v.url != null) put("url", JsonPrimitive(v.url))
                        put("exp", JsonPrimitive(v.expiresAt))
                    })
                }
            }.toString()
            file.writeText(payload)
        }
    }

    suspend fun resolve(media: Media): String? = withContext(Dispatchers.IO) {
        ensureDiskCacheLoaded()
        val isManga = media.manga != null && media.anime == null
        val cacheKey = "${if (isManga) "M" else "A"}:${media.id}:${media.idMAL}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { entry ->
            if (entry.expiresAt > now) {
                return@withContext entry.url
            }
        }

        val ctx = currContext()
        if (ctx != null && !isOnline(ctx)) {
            return@withContext null
        }

        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        val isMalMode = rescueMode || (media.idMAL != null && media.idMAL == media.id)
        var networkHadError = false

        val logo = run {
            resolveDirect(media, isManga, isMalMode, rescueMode) { networkHadError = true }
                ?: resolveViaRelations(media, isManga) { networkHadError = true }
                ?: resolveViaTitleSearch(media, isManga)
        }

        if (logo != null) {
            cache[cacheKey] = CacheEntry(logo, now + HIT_TTL_MS)
            persistCacheAsync()
        } else if (!networkHadError) {
            cache[cacheKey] = CacheEntry(null, now + MISS_TTL_MS)
        }
        logo
    }

    suspend fun preload(items: List<Media>) = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { media ->
                async {
                    val logo = resolve(media)
                    if (!logo.isNullOrBlank()) {
                        media.clearLogo = logo
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun resolveDirect(
        media: Media,
        isManga: Boolean,
        isMalMode: Boolean,
        rescueMode: Boolean,
        onError: () -> Unit
    ): String? {
        val isMovieHint = !isManga && (
            media.anime?.totalEpisodes == 1 ||
                media.format?.equals("MOVIE", ignoreCase = true) == true ||
                media.typeMAL?.equals("Movie", ignoreCase = true) == true
            )

        // 1. Keep AniZip primary for anime entries
        var aniZip: AniZipResult? = null
        if (!isManga) {
            aniZip = if (isMalMode) {
                resolveAniZipMalFirst(media, rescueMode, onError)
            } else {
                resolveAniZipAnilistFirst(media, onError)
            }
            if (!aniZip?.logoUrl.isNullOrBlank()) {
                return aniZip!!.logoUrl
            }
        }

        // 2. Alternative: TMDB via explicit/mapped IDs (from Media, AniZip, or IdMappers)
        val effectiveIsMovie = aniZip?.isMovie ?: isMovieHint
        val tmdbId = media.idTMDB?.toIntOrNull()
            ?: aniZip?.tmdbId
            ?: if (!isManga && media.id > 0) runCatching { IdMappers.getIds(media.id)?.tmdbId }.getOrNull() else null

        if (tmdbId != null && tmdbId > 0) {
            TmdbService.getClearLogo(tmdbId, effectiveIsMovie)?.let { return it }
            // Fallback to alternate TMDB type if format/type mismatched
            TmdbService.getClearLogo(tmdbId, !effectiveIsMovie)?.let { return it }
        }

        // 3. Alternative: IMDb -> TMDB /find -> ClearLogo
        val imdbId = media.idIMDB ?: aniZip?.imdbId
        if (!imdbId.isNullOrBlank()) {
            val found = TmdbService.findTmdbByImdb(imdbId)
            if (found != null) {
                TmdbService.getClearLogo(found.first, found.second)?.let { return it }
            }
        }

        return null
    }

    private suspend fun resolveViaRelations(media: Media, isManga: Boolean, onError: () -> Unit): String? {
        if (media.id <= 0 || PrefManager.getVal<Boolean>(PrefName.RescueMode)) return null

        val hints = mutableListOf<RelatedMediaHint>()
        media.prequel?.let {
            hints.add(
                RelatedMediaHint(
                    anilistId = it.id,
                    malId = it.idMAL,
                    titles = collectTitles(it),
                    year = it.startDate?.year,
                    isMovie = it.format?.equals("MOVIE", ignoreCase = true) == true,
                    isAdaptation = false
                )
            )
        }
        media.relations?.forEach { rel ->
            val relType = rel.relation.orEmpty()
            val validForLookup = if (isManga) {
                relType.equals("ADAPTATION", ignoreCase = true) && rel.anime != null
            } else {
                relType.equals("PREQUEL", ignoreCase = true) || relType.equals("PARENT", ignoreCase = true)
            }
            if (validForLookup) {
                hints.add(
                    RelatedMediaHint(
                        anilistId = rel.id,
                        malId = rel.idMAL,
                        titles = collectTitles(rel),
                        year = rel.startDate?.year,
                        isMovie = rel.format?.equals("MOVIE", ignoreCase = true) == true,
                        isAdaptation = relType.equals("ADAPTATION", ignoreCase = true)
                    )
                )
            }
        }

        for (hint in hints.distinctBy { it.anilistId }.take(3)) {
            val aniZip = fetchAniZip("anilist_id", hint.anilistId.toString(), onError)
                ?: hint.malId?.takeIf { it > 0 }?.let { fetchAniZip("mal_id", it.toString(), onError) }
            if (!aniZip?.logoUrl.isNullOrBlank()) {
                return aniZip!!.logoUrl
            }
            val mappedTmdbId = aniZip?.tmdbId
                ?: runCatching { IdMappers.getIds(hint.anilistId)?.tmdbId }.getOrNull()
            if (mappedTmdbId != null && mappedTmdbId > 0) {
                val isMov = aniZip?.isMovie ?: hint.isMovie
                TmdbService.getClearLogo(mappedTmdbId, isMov)?.let { return it }
                TmdbService.getClearLogo(mappedTmdbId, !isMov)?.let { return it }
            }
            val imdb = aniZip?.imdbId
            if (!imdb.isNullOrBlank()) {
                TmdbService.findTmdbByImdb(imdb)?.let { (tid, isMov) ->
                    TmdbService.getClearLogo(tid, isMov)?.let { return it }
                }
            }
            if (hint.titles.isNotEmpty()) {
                TmdbService.searchClearLogo(
                    titles = hint.titles,
                    year = hint.year,
                    preferMovie = hint.isMovie
                )?.let { return it }
            }
        }
        return null
    }

    private suspend fun resolveViaTitleSearch(media: Media, isManga: Boolean): String? {
        val titles = collectTitles(media)
        if (titles.isEmpty()) return null
        val preferMovie = !isManga && (
            media.anime?.totalEpisodes == 1 ||
                media.format?.equals("MOVIE", ignoreCase = true) == true ||
                media.typeMAL?.equals("Movie", ignoreCase = true) == true
            )
        // Manga start years reflect serialization start (years before an anime aired), so omit strict year scoring for manga
        return TmdbService.searchClearLogo(
            titles = titles,
            year = if (isManga) null else media.startDate?.year,
            preferMovie = preferMovie
        )
    }

    private fun collectTitles(media: Media): List<String> {
        return listOfNotNull(
            media.name?.takeIf { it.isNotBlank() },
            media.nameRomaji.takeIf { it.isNotBlank() },
            media.nameMAL?.takeIf { it.isNotBlank() },
            media.userPreferredName.takeIf { it.isNotBlank() }
        ).distinct()
    }

    private suspend fun resolveAniZipMalFirst(
        media: Media,
        rescueMode: Boolean,
        onError: () -> Unit
    ): AniZipResult? {
        val malId = (media.idMAL ?: media.id).toString()
        var merged = AniZipResult()
        if (malId.isNotBlank() && malId != "0") {
            fetchAniZip("mal_id", malId, onError)?.let {
                merged = mergeAniZip(merged, it)
                if (!merged.logoUrl.isNullOrBlank()) return merged
            }
        }
        if (!rescueMode && media.id > 0) {
            fetchAniZip("anilist_id", media.id.toString(), onError)?.let {
                merged = mergeAniZip(merged, it)
            }
        }
        return merged.takeIf { it.logoUrl != null || it.tmdbId != null || it.imdbId != null }
    }

    private suspend fun resolveAniZipAnilistFirst(
        media: Media,
        onError: () -> Unit
    ): AniZipResult? {
        var merged = AniZipResult()
        if (media.id > 0) {
            fetchAniZip("anilist_id", media.id.toString(), onError)?.let {
                merged = mergeAniZip(merged, it)
                if (!merged.logoUrl.isNullOrBlank()) return merged
            }
        }
        if (media.idMAL != null && media.idMAL!! > 0) {
            fetchAniZip("mal_id", media.idMAL.toString(), onError)?.let {
                merged = mergeAniZip(merged, it)
                if (!merged.logoUrl.isNullOrBlank()) return merged
            }
        }
        if (media.id > 0 && merged.logoUrl == null && merged.tmdbId == null) {
            val ids = runCatching { IdMappers.getIds(media.id) }.getOrNull()
            if (ids?.malId != null && ids.malId != media.idMAL) {
                fetchAniZip("mal_id", ids.malId.toString(), onError)?.let {
                    merged = mergeAniZip(merged, it)
                }
            }
            if (ids?.tmdbId != null) {
                merged = merged.copy(tmdbId = merged.tmdbId ?: ids.tmdbId)
            }
        }
        return merged.takeIf { it.logoUrl != null || it.tmdbId != null || it.imdbId != null }
    }

    private fun mergeAniZip(base: AniZipResult, incoming: AniZipResult): AniZipResult {
        return AniZipResult(
            logoUrl = base.logoUrl ?: incoming.logoUrl,
            tmdbId = base.tmdbId ?: incoming.tmdbId,
            imdbId = base.imdbId ?: incoming.imdbId,
            isMovie = base.isMovie ?: incoming.isMovie
        )
    }

    private suspend fun fetchAniZip(key: String, id: String, onError: () -> Unit): AniZipResult? {
        if (id.isBlank() || id == "0") return null
        val url = "https://api.ani.zip/mappings?$key=$id"
        val element = getJson(url, onError) ?: return null
        val obj = element as? JsonObject ?: return null

        val rawLogo = extractLogoUrl(obj)
        val normalizedLogo = rawLogo?.let { normalizeLogoImageUrl(it) }

        val mappings = obj["mappings"] as? JsonObject
        val tmdbId = mappings?.get("themoviedb_id")?.jsonPrimitive?.intOrNull
            ?: mappings?.get("tmdb_id")?.jsonPrimitive?.intOrNull
        val imdbId = mappings?.get("imdb_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") }
        val typeStr = mappings?.get("type")?.jsonPrimitive?.contentOrNull
            ?: obj["type"]?.jsonPrimitive?.contentOrNull
        val isMovie = when {
            typeStr.equals("MOVIE", ignoreCase = true) -> true
            typeStr.equals("TV", ignoreCase = true) -> false
            else -> null
        }

        return AniZipResult(normalizedLogo, tmdbId, imdbId, isMovie)
    }

    private fun normalizeLogoImageUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.contains("image.tmdb.org", ignoreCase = true) && trimmed.endsWith(".svg", ignoreCase = true)) {
            return trimmed.dropLast(4) + ".png"
        }
        if (trimmed.endsWith(".svg", ignoreCase = true)) {
            return "https://wsrv.nl/?url=$trimmed&output=png"
        }
        return trimmed
    }

    private fun extractLogoUrl(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                val logoKeys = listOf("clearlogo", "clearLogo", "logo", "logoImage")
                val direct = logoKeys.firstNotNullOfOrNull { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf { isLogoUrl(it) }
                }
                if (direct != null) return direct

                val imagesArray = element["images"] as? JsonArray
                imagesArray?.filterIsInstance<JsonObject>()
                    ?.firstOrNull { img ->
                        (img["coverType"] as? JsonPrimitive)?.contentOrNull
                            ?.equals("Clearlogo", ignoreCase = true) == true
                    }
                    ?.let { (it["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { u -> isLogoUrl(u) } }
            }
            is JsonArray -> element.filterIsInstance<JsonObject>().firstNotNullOfOrNull { extractLogoUrl(it) }
            else -> null
        }
    }

    private fun isLogoUrl(value: String): Boolean =
        (value.startsWith("http://") || value.startsWith("https://")) &&
            (value.contains(".png", ignoreCase = true) || value.contains(".svg", ignoreCase = true) ||
                value.contains(".webp", ignoreCase = true))

    private suspend fun getJson(url: String, onError: () -> Unit): JsonElement? = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return@runCatching null
            json.parseToJsonElement(text)
        }.onFailure {
            onError()
            Logger.log("$TAG error fetching $url: ${it.message}")
        }.getOrNull()
    }
}
