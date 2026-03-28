package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.Mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

object IdMappers {

    // Your Working Vercel URL
    private const val MAPPER_API_URL = "https://Isagi2025-idmapper.hf.space/api/mapper"

    // Simple RAM cache to prevent fetching the same ID multiple times
    private val cache = HashMap<Int, AnimeId>()

    /**
     * Fetches IDs from your Vercel API.
     * NOTE: I removed "private" so MediaDetailsActivity can call this directly.
     */
    suspend fun getIds(anilistId: Int): AnimeId? {
        // 1. Check if we already have this ID in memory
        if (cache.containsKey(anilistId)) {
            return cache[anilistId]
        }

        return withContext(Dispatchers.IO) {
            try {
                // 2. Request data from Vercel
                val response = client.get("$MAPPER_API_URL?anilist_id=$anilistId")

                // 3. Parse the JSON result
                val data = Mapper.json.decodeFromString<AnimeId>(response.text)

                // 4. Save to cache
                if (data.anilistId != null) {
                    cache[data.anilistId] = data
                }

                data
            } catch (e: Exception) {
                // If 404 or no internet, return null safely
                e.printStackTrace()
                null
            }
        }
    }


    // --- Helper Functions ---

    suspend fun getSimklId(anilistId: Int): Int? {
        return getIds(anilistId)?.simklId
    }

    suspend fun getImdbId(anilistId: Int): String? {
        // First try the main mapper
        val mainId = getIds(anilistId)?.imdbId
        if (mainId != null) return mainId

        // Fallback to ani.zip
        return getAniZipId(anilistId)
    }

    suspend fun getMalId(anilistId: Int): Int? {
        return getIds(anilistId)?.malId
    }

    private suspend fun getAniZipId(anilistId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.get("https://api.ani.zip/mappings?anilist_id=$anilistId")
                val data = Mapper.json.decodeFromString<AniZipResponse>(response.text)
                // Accessing the first mapping's imdb_id, if available
                data.mappings.values.firstOrNull()?.imdbId
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

}

@Serializable
data class AnimeId(
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("mal_id") val malIdElement: JsonElement? = null,
    @SerialName("simkl_id") val simklId: Int? = null,
    @SerialName("themoviedb_id") val tmdbId: Int? = null,
    @SerialName("imdb_id") val imdbIdElement: JsonElement? = null,
    @SerialName("kitsu_id") val kitsuId: Int? = null,
    @SerialName("anidb_id") val anidbId: Int? = null,
    @SerialName("anime-planet_id") val animePlanetId: String? = null,
    @SerialName("animenewsnetwork_id") val annId: Int? = null,
    @SerialName("anisearch_id") val anisearchId: Int? = null,
    @SerialName("livechart_id") val livechartId: Int? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("animecountdown_id") val animeCountdownId: Int? = null,
    @SerialName("tmdb_mappings") val tmdbMappings: Map<String, String>? = null,
    @SerialName("tvdb_mappings") val tvdbMappings: Map<String, String>? = null
) {
    val malId: Int?
        get() = when (val el = malIdElement) {
            is JsonPrimitive -> el.intOrNull
            is JsonArray -> el.firstOrNull()?.jsonPrimitive?.intOrNull
            else -> null
        }
    val imdbId: String?
        get() = when (val el = imdbIdElement) {
            is JsonPrimitive -> el.contentOrNull
            is JsonArray -> el.firstOrNull()?.jsonPrimitive?.contentOrNull
            else -> null
        }
}

@Serializable
data class AniZipResponse(
    val mappings: Map<String, AniZipMapping> = emptyMap()
)

@Serializable
data class AniZipMapping(
    @SerialName("imdb_id") val imdbId: String? = null
)
