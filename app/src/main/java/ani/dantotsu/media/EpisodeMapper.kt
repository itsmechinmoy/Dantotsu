package ani.dantotsu.media

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.AnilistQueries
import ani.dantotsu.others.IdMappers
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.anime.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ani.dantotsu.util.Logger

object EpisodeMapper {

    data class SeasonEpisode(val season: Int, val episode: Int)

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val episodeCache = HashMap<String, SeasonEpisode>()

    suspend fun mapEpisode(media: Media, episodeNumber: Int, episode: Episode? = null): SeasonEpisode {
        val cacheKey = "${media.id}-$episodeNumber"
        if (episodeCache.containsKey(cacheKey)) {
            return episodeCache[cacheKey]!!
        }
        val result = mapEpisodeInternal(media, episodeNumber, episode)
        episodeCache[cacheKey] = result
        return result
    }

    /**
     * MAIN FUNCTION
     * Pass the Episode object so we can read AniZip data already stored in
     * episode.extra["season"] / episode.extra["episode"] — no second network call.
     */
    private suspend fun mapEpisodeInternal(media: Media, episodeNumber: Int, episode: Episode? = null): SeasonEpisode {
        Logger.log("EpisodeMapper: Mapping ${media.userPreferredName} (ID: ${media.id}) Ep: $episodeNumber")

        // STEP 0: Read season/episode from already-fetched AniZip data in Episode.extra
        val aniZipResult = getAniZipFromEpisode(episode, episodeNumber)
        if (aniZipResult != null) {
            Logger.log("EpisodeMapper: AniZip (from cache) mapping found: $aniZipResult")
            return aniZipResult
        }
        Logger.log("EpisodeMapper: No AniZip data in episode, falling back to API mapping")

        // STEP 1: TMDB/TVDB API mapping
        val apiMapping = getApiMapping(media, episodeNumber)
        if (apiMapping != null) {
            Logger.log("EpisodeMapper: API Mapping found: $apiMapping")
            return apiMapping
        }
        Logger.log("EpisodeMapper: No API Mapping found")

        // STEP 2: Only now check if it's long-running
        val totalEpisodes = media.anime?.totalEpisodes ?: 0
        val isLongRunning = totalEpisodes > 300 || totalEpisodes == 0  // ← Raised to 300+
        Logger.log("EpisodeMapper: TotalEp: $totalEpisodes, isLongRunning: $isLongRunning")

        if (isLongRunning && episodeNumber > 100) {  // ← Extra safety for One Piece
            Logger.log("EpisodeMapper: Using Continuous Mapping (Long Running & Ep > 100)")
            return getContinuousMapping(media, episodeNumber)
        }

        // STEP 3: For everything else (seasonal shows)
        Logger.log("EpisodeMapper: Using Seasonal Offset")
        val calculatedSeason = getSeasonOffset(media)
        Logger.log("EpisodeMapper: Calculated Season: $calculatedSeason")
        return SeasonEpisode(calculatedSeason.coerceAtLeast(1), episodeNumber)
    }

    /**
     * STEP 0: Read AniZip season/episode from Episode.extra (already fetched by Anify).
     * No network call — episode.extra["season"] is set by Anify.fetchAndParseMetadata().
     * episode.extra["episode"] may also be present; if not, use the incoming episodeNumber.
     */
    private fun getAniZipFromEpisode(episode: Episode?, episodeNumber: Int): SeasonEpisode? {
        val extra = episode?.extra ?: return null
        val season = extra["season"]?.toIntOrNull() ?: return null
        val epInSeason = extra["episode"]?.toIntOrNull() ?: episodeNumber
        return SeasonEpisode(season, epInSeason)
    }

    /**
     * STEP 1: TMDB/TVDB API MAPPING
     */
    private suspend fun getApiMapping(media: Media, episodeNumber: Int): SeasonEpisode? {
        val ids = IdMappers.getIds(media.id)

        // 1. Try TMDB Mappings
        var mappings = ids?.tmdbMappings
        if (mappings != null) {
            val result = checkMappings(mappings, episodeNumber)
            if (result != null) return result
        }

        // 2. Try TVDB Mappings (Fallback)
        mappings = ids?.tvdbMappings
        if (mappings != null) {
            val result = checkMappings(mappings, episodeNumber)
            if (result != null) return result
        }

        Logger.log("EpisodeMapper: No API Mapping found")
        return null
    }

    private fun checkMappings(mappings: Map<String, String>, episodeNumber: Int): SeasonEpisode? {
        Logger.log("EpisodeMapper: Checking ${mappings.size} mappings")
        for ((key, value) in mappings) {
            try {
                // Key format: "s1", "s2"
                val season = key.removePrefix("s").toIntOrNull() ?: continue

                // Value format: "e1-e12", "e13-", "e5"
                val parts = value.split("-")
                val start = parts[0].removePrefix("e").toIntOrNull() ?: continue
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].removePrefix("e").toIntOrNull() ?: Int.MAX_VALUE
                } else if (parts.size > 1) {
                    Int.MAX_VALUE // "e13-" means 13 to infinity
                } else {
                    start // "e5" means just 5
                }

                if (episodeNumber in start..end) {
                    Logger.log("EpisodeMapper: Match found for $key: $value")
                    return SeasonEpisode(season, episodeNumber - start + 1)
                }
            } catch (e: Exception) {
                Logger.log("EpisodeMapper: Error parsing mapping $key=$value")
            }
        }
        return null
    }

    /**
     * STRATEGY MANAGER
     */
    private suspend fun getContinuousMapping(media: Media, episodeNumber: Int): SeasonEpisode {
        Logger.log("EpisodeMapper: Entering getContinuousMapping")
        // EXTRA SAFETY: If tmdb_mappings exist, trust them over everything
        val apiMapping = getApiMapping(media, episodeNumber)
        if (apiMapping != null) return apiMapping

        val imdbId = IdMappers.getImdbId(media.id) ?: return SeasonEpisode(1, episodeNumber)

        // SPECIAL FIX FOR ONE PIECE:
        // ImdbApi.dev often fails for One Piece. We trust TVMaze (Hardcoded 1505) more.
        if (imdbId == "tt0388629") {
            val onePieceResult = getTvMazeMapping(imdbId, episodeNumber)
            if (onePieceResult != null) return onePieceResult
        }

        // STRATEGY A: ImdbApi.dev (Fastest for normal shows)
        val primaryResult = getImdbApiMapping(imdbId, episodeNumber)
        if (primaryResult != null) return primaryResult

        // STRATEGY B: TVMaze (Backup for everyone else)
        val backupResult = getTvMazeMapping(imdbId, episodeNumber)
        if (backupResult != null) return backupResult

        // Fail
        return SeasonEpisode(1, episodeNumber)
    }

    /**
     * LOGIC A: ImdbApi.dev
     */
    private suspend fun getImdbApiMapping(imdbId: String, absoluteEpisode: Int): SeasonEpisode? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.imdbapi.dev/titles/$imdbId/episodes"
                val response = client.get(url)

                if (response.code in 200..299) {
                    val data = jsonParser.decodeFromString<ImdbApiResult>(response.text)
                    val episodes = data.episodes

                    // Map by Index
                    val index = absoluteEpisode - 1

                    if (index >= 0 && index < episodes.size) {
                        val ep = episodes[index]
                        return@withContext SeasonEpisode(ep.season, ep.episodeNumber)
                    }
                }
                return@withContext null
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    /**
     * LOGIC B: TVMaze (Backup)
     */
    private suspend fun getTvMazeMapping(imdbId: String, absoluteEpisode: Int): SeasonEpisode? {
        return withContext(Dispatchers.IO) {
            try {
                var tvMazeId = 0

                // 1. HARDCODED SHORTCUTS
                if (imdbId == "tt0388629") {
                    tvMazeId = 1505 // One Piece
                } else if (imdbId == "tt0115135") {
                    tvMazeId = 331  // Detective Conan
                }
                // 2. NETWORK LOOKUP
                else {
                    val lookupUrl = "https://api.tvmaze.com/lookup/shows?imdb=$imdbId"
                    val showResponse = client.get(lookupUrl)

                    if (showResponse.code in 200..299) {
                        val showData = jsonParser.decodeFromString<TvMazeShow>(showResponse.text)
                        tvMazeId = showData.id
                    } else if (showResponse.code in 300..399) {
                        val location = showResponse.headers["Location"]
                        tvMazeId = location?.substringAfterLast("/")?.toIntOrNull() ?: 0
                    }
                }

                if (tvMazeId == 0) return@withContext null

                // 3. FETCH EPISODES
                val episodesUrl = "https://api.tvmaze.com/shows/$tvMazeId/episodes"
                val epsResponse = client.get(episodesUrl)
                val epsData = jsonParser.decodeFromString<List<TvMazeEpisode>>(epsResponse.text)

                // 4. MAP BY INDEX
                val index = absoluteEpisode - 1

                if (index >= 0 && index < epsData.size) {
                    val ep = epsData[index]
                    // Safe calculation
                    val s = ep.season
                    val e = ep.number ?: (index + 1)

                    return@withContext SeasonEpisode(s, e)
                }

                return@withContext null

            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    /**
     * LOGIC C: Prequel Counting
     */
    private suspend fun getSeasonOffset(media: Media): Int {
        // Logger.log("EpisodeMapper: Calculating season offset for ${media.userPreferredName} (ID: ${media.id})")
        var current = media
        var season = 1
        var safety = 0

        while (safety < 20) {
            val relations = current.relations
            // Logger.log("EpisodeMapper: S$season Current media ${current.id} has ${relations?.size ?: 0} relations")

            val prequel = relations?.find {
                it.relation == "PREQUEL" &&
                        (it.format == "TV" || it.format == "TV_SHORT" || it.format == "ONA")
            }

            if (prequel != null) {
                // Logger.log("EpisodeMapper: Found prequel: ${prequel.userPreferredName} (ID: ${prequel.id})")
                season++
                val fetchedPrequel = AnilistQueries().getMedia(prequel.id)
                if (fetchedPrequel != null) {
                    val detailedPrequel = AnilistQueries().mediaDetails(fetchedPrequel)
                    current = detailedPrequel
                } else {
                    // Logger.log("EpisodeMapper: Failed to fetch prequel details for ID ${prequel.id}")
                    break
                }
            } else {
                // Logger.log("EpisodeMapper: No TV/ONA prequel found for ${current.id}")
                break
            }
            safety++
        }
        // Logger.log("EpisodeMapper: Final calculated season: $season")
        return season
    }
}

// --- DATA CLASSES ---

@Serializable
data class ImdbApiResult(
    val episodes: List<ImdbApiEpisode> = emptyList()
)

@Serializable
data class ImdbApiEpisode(
    val season: Int,
    val episodeNumber: Int
)

@Serializable
data class TvMazeShow(
    val id: Int,
    val name: String
)

@Serializable
data class TvMazeEpisode(
    val id: Int,
    val season: Int,
    val number: Int? = null
)