package ani.dantotsu.others

import ani.dantotsu.FileUrl
import ani.dantotsu.client
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AniZip API integration for episode metadata.
 * API: https://api.ani.zip/mappings?anilist_id=<id>
 *
 * This object was previously named "Anify" and called the defunct Anify API.
 * It is now backed by api.ani.zip but keeps the same name/interface so the
 * rest of the code (MediaDetailsViewModel, AnimeWatchFragment, Anime.kt) does
 * not need renaming.
 */
object Anify {

    suspend fun fetchAndParseMetadata(anilistId: Int): Map<String, Episode> {
        return try {
            Logger.log("AniZip : fetching episodes for anilist_id=$anilistId")
            val response = client.get("https://api.ani.zip/mappings?anilist_id=$anilistId")
                .parsed<AniZipResponse>()

            val episodes = response.episodes ?: return emptyMap()

            episodes.entries
                .filter { (key, _) ->
                    // Only include numbered episodes (1, 2, 3 …); skip specials like "S1", "S2"
                    key.toIntOrNull() != null
                }
                .associate { (key, ep) ->
                    val title = ep.title?.en
                        ?: ep.title?.xJat
                        ?: ep.title?.ja

                    key to Episode(
                        number = key,
                        title = title,
                        desc = ep.overview ?: ep.summary,
                        thumb = FileUrl[ep.image],
                        extra = buildMap {
                            ep.airDate?.let { put("airDate", it) }
                            ep.rating?.let { put("rating", it) }
                            ep.seasonNumber?.let { put("season", it.toString()) }
                            ep.episodeNumber?.let { put("episode", it.toString()) }
                        }
                    )
                }
        } catch (e: Exception) {
            Logger.log("AniZip : error fetching episodes: ${e.message}")
            emptyMap()
        }
    }

    // ── Data models ────────────────────────────────────────────────────────────

    @Serializable
    data class AniZipResponse(
        val episodes: Map<String, AniZipEpisode>? = null,
        val episodeCount: Int? = null,
        val specialCount: Int? = null,
        val mappings: AniZipMappings? = null
    )

    @Serializable
    data class AniZipEpisode(
        val episode: String? = null,
        val title: AniZipTitle? = null,
        val overview: String? = null,
        val summary: String? = null,
        val image: String? = null,
        val airDate: String? = null,
        @SerialName("airdate") val airdate: String? = null,
        val runtime: Int? = null,
        val length: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val absoluteEpisodeNumber: Int? = null,
        val rating: String? = null,
        val anidbEid: Int? = null,
        val tvdbId: Int? = null,
        val tvdbShowId: Int? = null
    )

    @Serializable
    data class AniZipTitle(
        val ja: String? = null,
        val en: String? = null,
        @SerialName("x-jat") val xJat: String? = null
    )

    @Serializable
    data class AniZipMappings(
        @SerialName("anilist_id") val anilistId: Int? = null,
        @SerialName("mal_id") val malId: Int? = null,
        @SerialName("kitsu_id") val kitsuId: Int? = null,
        @SerialName("thetvdb_id") val tvdbId: Int? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("themoviedb_id") val tmdbId: String? = null
    )
}
