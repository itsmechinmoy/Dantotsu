package ani.dantotsu.media

import ani.dantotsu.client
import ani.dantotsu.others.IdMappers
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
import java.util.concurrent.ConcurrentHashMap

object CarouselLogoResolver {
    private const val NO_LOGO = ""
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(media: Media): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${media.id}:${media.idMAL}"
        val cached = cache[cacheKey]
        if (cached != null) {
            return@withContext if (cached == NO_LOGO) null else cached
        }

        var logo: String? = null

        // 1. Try AniZip mapping by Anilist ID
        if (media.id > 0) {
            logo = fromAniZip("anilist_id", media.id.toString())
                ?: fromAniZip("anilistId", media.id.toString())
        }

        // 2. Try AniZip mapping by MAL ID
        if (logo == null && media.idMAL != null && media.idMAL!! > 0) {
            logo = fromAniZip("mal_id", media.idMAL.toString())
                ?: fromAniZip("malId", media.idMAL.toString())
        }

        // 3. Fallback: try IdMappers to resolve MAL ID
        if (logo == null && media.id > 0) {
            val ids = runCatching { IdMappers.getIds(media.id) }.getOrNull()
            if (ids?.malId != null && ids.malId != media.idMAL) {
                logo = fromAniZip("mal_id", ids.malId.toString())
            }
        }

        cache[cacheKey] = logo ?: NO_LOGO
        logo
    }

    suspend fun preload(items: List<Media>) = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { media ->
                async {
                    val logo = resolve(media)
                    media.clearLogo = logo
                }
            }.awaitAll()
        }
    }

    private suspend fun fromAniZip(key: String, id: String): String? {
        if (id.isBlank() || id == "0") return null
        val endpoints = listOf(
            "https://api.ani.zip/mappings?$key=$id"
        )
        for (url in endpoints) {
            val jsonElement = getJson(url) ?: continue
            val logo = extractLogoUrl(jsonElement)
            if (!logo.isNullOrBlank()) {
                return if (logo.startsWith("http://") || logo.startsWith("https://")) {
                    "https://wsrv.nl/?url=$logo"
                } else {
                    logo
                }
            }
        }
        return null
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

    private suspend fun getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.get(url)
            val text = response.text
            if (text.isBlank()) return@runCatching null
            json.parseToJsonElement(text)
        }.onFailure {
            Logger.log("CarouselLogoResolver error fetching $url: ${it.message}")
        }.getOrNull()
    }
}
