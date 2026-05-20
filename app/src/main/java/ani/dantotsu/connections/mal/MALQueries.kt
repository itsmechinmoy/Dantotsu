package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import kotlinx.serialization.Serializable
import java.net.URLEncoder

class MALQueries {
    private val apiUrl = "https://api.myanimelist.net/v2"
    private val authHeader: Map<String, String>?
        get() {
            return mapOf("Authorization" to "Bearer ${MAL.token ?: return null}")
        }
    private val clientIdHeader: Map<String, String>
        get() = mapOf("X-MAL-CLIENT-ID" to MAL.clientId)

    private fun preferredHeader(): Map<String, String> = authHeader ?: clientIdHeader

    @Serializable
    data class MalUser(
        val id: Int,
        val name: String,
        val picture: String?,
        @kotlinx.serialization.SerialName("anime_statistics") val animeStatistics: MalAnimeStatistics? = null,
        @kotlinx.serialization.SerialName("manga_statistics") val mangaStatistics: MalMangaStatistics? = null,
    )

    private suspend fun executeRequest(
        requestBlock: suspend () -> com.lagradost.nicehttp.NiceResponse
    ): com.lagradost.nicehttp.NiceResponse {
        var lastResponse: com.lagradost.nicehttp.NiceResponse? = null
        var lastException: Exception? = null
        var delayMs = 1000L
        val maxAttempts = 3

        for (attempt in 1..maxAttempts) {
            try {
                val response = requestBlock()
                lastResponse = response
                if (response.code != 429) {
                    return response
                }
            } catch (e: Exception) {
                lastException = e
            }

            if (attempt < maxAttempts) {
                val jitter = (0..200).random().toLong()
                kotlinx.coroutines.delay(delayMs + jitter)
                delayMs *= 2
            }
        }

        return lastResponse ?: throw (lastException ?: Exception("Request failed after $maxAttempts attempts"))
    }

    suspend fun getUserData(): Boolean {
        val headers = authHeader ?: return false
        val res = tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/users/@me?fields=anime_statistics,manga_statistics",
                    headers
                )
            }.parsed<MalUser>()
        } ?: return false
        MAL.userid = res.id
        MAL.username = res.name
        MAL.avatar = res.picture
        MAL.episodesWatched = res.animeStatistics?.numEpisodes ?: estimateEpisodesWatched()
        MAL.chaptersRead = res.mangaStatistics?.numChaptersRead ?: estimateChaptersRead()
        PrefManager.setVal(PrefName.MALUserName, res.name)
        PrefManager.setVal(PrefName.MALAvatar, res.picture ?: "")

        return true
    }

    private suspend fun estimateEpisodesWatched(): Int? {
        val statuses = listOf("watching", "completed", "on_hold", "dropped", "plan_to_watch")
        var total = 0
        var loaded = false
        for (status in statuses) {
            getUserAnimeList(status = status, limit = 100)?.data?.let { entries ->
                loaded = true
                total += entries.sumOf { it.listStatus?.numEpisodesWatched ?: 0 }
            }
        }
        return if (loaded) total else null
    }

    private suspend fun estimateChaptersRead(): Int? {
        val statuses = listOf("reading", "completed", "on_hold", "dropped", "plan_to_read")
        var total = 0
        var loaded = false
        for (status in statuses) {
            getUserMangaList(status = status, limit = 100)?.data?.let { entries ->
                loaded = true
                total += entries.sumOf { it.listStatus?.numChaptersRead ?: 0 }
            }
        }
        return if (loaded) total else null
    }

    suspend fun editList(
        idMAL: Int?,
        isAnime: Boolean,
        progress: Int?,
        score: Int?,
        status: String,
        rewatch: Int? = null,
        start: FuzzyDate? = null,
        end: FuzzyDate? = null
    ) {
        if (idMAL == null) return
        val headers = authHeader ?: return
        val data = mutableMapOf("status" to convertStatus(isAnime, status))
        if (progress != null)
            data[if (isAnime) "num_watched_episodes" else "num_chapters_read"] = progress.toString()
        data[if (isAnime) "is_rewatching" else "is_rereading"] = (status == "REPEATING").toString()
        if (score != null)
            data["score"] = Math.round(score / 10.0).coerceIn(0L, 10L).toString()
        if (rewatch != null)
            data[if (isAnime) "num_times_rewatched" else "num_times_reread"] = rewatch.toString()
        if (start != null && !start.isEmpty())
            data["start_date"] = start.toMALString()
        if (end != null && !end.isEmpty())
            data["finish_date"] = end.toMALString()
        tryWithSuspend {
            executeRequest {
                client.put(
                    "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                    headers,
                    data = data,
                )
            }
        }
    }

    suspend fun deleteList(isAnime: Boolean, idMAL: Int?) {
        if (idMAL == null) return
        val headers = authHeader ?: return
        tryWithSuspend {
            executeRequest {
                client.delete(
                    "$apiUrl/${if (isAnime) "anime" else "manga"}/$idMAL/my_list_status",
                    headers
                )
            }
        }
    }

    private val listFields = "list_status,num_episodes,num_chapters,main_picture,mean,media_type,status,genres,start_date,start_season"

    suspend fun getUserAnimeList(
        status: String? = null,
        sort: String = "list_updated_at",
        limit: Int = 100,
        offset: Int = 0,
    ): MalListResponse? {
        val headers = authHeader ?: return null
        val statusParam = status?.let { "&status=$it" } ?: ""
        val offsetParam = if (offset > 0) "&offset=$offset" else ""
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/users/@me/animelist?fields=$listFields&sort=$sort&limit=$limit$offsetParam&nsfw=1$statusParam",
                    headers
                )
            }.parsed<MalListResponse>()
        }
    }

    suspend fun getUserMangaList(
        status: String? = null,
        sort: String = "list_updated_at",
        limit: Int = 100,
        offset: Int = 0,
    ): MalListResponse? {
        val headers = authHeader ?: return null
        val statusParam = status?.let { "&status=$it" } ?: ""
        val offsetParam = if (offset > 0) "&offset=$offset" else ""
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/users/@me/mangalist?fields=$listFields&sort=$sort&limit=$limit$offsetParam&nsfw=1$statusParam",
                    headers
                )
            }.parsed<MalListResponse>()
        }
    }

    private val rankingFields = "mean,status,media_type,num_episodes,num_chapters,main_picture,genres,my_list_status,start_date,start_season"

    suspend fun getAnimeRanking(
        rankingType: String = "all",
        limit: Int = 15,
        offset: Int = 0,
    ): MalRankingResponse? {
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/anime/ranking?ranking_type=$rankingType&limit=$limit&offset=$offset&fields=$rankingFields",
                    headers
                )
            }.parsed<MalRankingResponse>()
        }
    }

    suspend fun getMangaRanking(
        rankingType: String = "all",
        limit: Int = 15,
        offset: Int = 0,
    ): MalRankingResponse? {
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/manga/ranking?ranking_type=$rankingType&limit=$limit&offset=$offset&fields=$rankingFields",
                    headers
                )
            }.parsed<MalRankingResponse>()
        }
    }

    suspend fun searchAnime(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MalRankingResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/anime?q=$encodedQuery&limit=$limit&offset=$offset&fields=$rankingFields",
                    headers
                )
            }.parsed<MalRankingResponse>()
        }
    }

    suspend fun searchManga(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MalRankingResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/manga?q=$encodedQuery&limit=$limit&offset=$offset&fields=$rankingFields",
                    headers
                )
            }.parsed<MalRankingResponse>()
        }
    }

    private val recRelFields = "%7Bnode%7Bid,title,main_picture,num_episodes,num_chapters,mean,media_type,status,alternative_titles,rating,popularity%7D%7D"
    private val detailFields = "mean,status,media_type,synopsis,genres,num_episodes,num_chapters," +
        "main_picture,alternative_titles,title_synonyms,start_date,end_date,start_season,source,rating," +
        "average_episode_duration,studios,authors,rank,popularity,my_list_status," +
        "recommendations$recRelFields,related_anime$recRelFields,related_manga$recRelFields"

    suspend fun getAnimeDetails(malId: Int): MalAnimeNode? {
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/anime/$malId?fields=$detailFields",
                    headers
                )
            }.parsed<MalAnimeNode>()
        }
    }

    suspend fun getMangaDetails(malId: Int): MalAnimeNode? {
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/manga/$malId?fields=$detailFields",
                    headers
                )
            }.parsed<MalAnimeNode>()
        }
    }

    suspend fun getSeasonalAnime(
        year: Int,
        season: String,
        sort: String = "anime_num_list_users",
        limit: Int = 15,
    ): MalRankingResponse? {
        val headers = preferredHeader()
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/anime/season/$year/$season?sort=$sort&limit=$limit&fields=$rankingFields",
                    headers
                )
            }.parsed<MalRankingResponse>()
        }
    }

    private fun convertStatus(isAnime: Boolean, status: String): String {
        return when (status) {
            "PLANNING"   -> if (isAnime) "plan_to_watch" else "plan_to_read"
            "COMPLETED"  -> "completed"
            "PAUSED"     -> "on_hold"
            "DROPPED"    -> "dropped"
            "REPEATING"  -> if (isAnime) "rewatching" else "rereading"
            "CURRENT"    -> if (isAnime) "watching" else "reading"
            else         -> if (isAnime) "watching" else "reading"
        }
    }

    suspend fun getAnimeSuggestions(limit: Int = 15): MalRankingResponse? {
        val headers = authHeader ?: return null
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/anime/suggestions?limit=$limit&fields=$rankingFields",
                    headers
                )
            }.parsed<MalRankingResponse>()
        }
    }

    private val sequelListFields = "list_status,num_episodes,main_picture,mean,media_type,status,related_anime${recRelFields}"

    suspend fun getCompletedAnimeWithRelations(limit: Int = 100): MalListResponse? {
        val headers = authHeader ?: return null
        return tryWithSuspend {
            executeRequest {
                client.get(
                    "$apiUrl/users/@me/animelist?fields=$sequelListFields&status=completed&sort=list_updated_at&limit=$limit&nsfw=1",
                    headers
                )
            }.parsed<MalListResponse>()
        }
    }

    suspend fun getAllUserAnimeIds(): Set<Int> {
        val allIds = mutableSetOf<Int>()
        var offset = 0
        val batchSize = 100
        val headers = authHeader ?: return emptySet()
        while (true) {
            val response = tryWithSuspend {
                executeRequest {
                    client.get(
                        "$apiUrl/users/@me/animelist?fields=&sort=list_updated_at&limit=$batchSize&offset=$offset&nsfw=1",
                        headers
                    )
                }.parsed<MalListResponse>()
            } ?: break
            response.data.forEach { allIds.add(it.node.id) }
            if (response.data.size < batchSize || response.paging?.next == null) break
            offset += batchSize
        }
        return allIds
    }
}
