package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

class JikanQueries {

    companion object {
        val apiUrls = listOf(
            "https://api.jikan.moe/v4",
            "https://jikanfortheweebs.midnightignite.me/v4",
            "https://api.tenrai.org/v1"
        )
        private val rateMutex = Mutex()
        private var lastRequestTime = 0L
        private const val MIN_INTERVAL_MS = 350L
    }

    private suspend fun rateLimitedGet(url: String): com.lagradost.nicehttp.NiceResponse {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < MIN_INTERVAL_MS) {
                delay(MIN_INTERVAL_MS - elapsed)
            }
            lastRequestTime = System.currentTimeMillis()
        }
        return client.get(url)
    }

    private suspend inline fun <reified T : Any> fetchWithFallback(path: String): T? {
        val cleanPath = if (path.startsWith("/")) path else "/$path"

        for (baseUrl in apiUrls) {
            val url = "$baseUrl$cleanPath"
            try {
                val response = rateLimitedGet(url)
                if (response.code in 200..299) {
                    val parsed = response.parsed<T>()
                    if (parsed != null) {
                        return parsed
                    }
                }
            } catch (_: Exception) {
                // Try next fallback URL in chain
            }
        }
        return null
    }

    suspend fun search(
        query: String,
        endpoint: String = "anime",
        type: String? = null,
        page: Int = 1,
        limit: Int = 25,
        sfw: Boolean = true,
        orderBy: String? = null,
        sort: String? = null,
        status: String? = null,
        rating: String? = null,
        genres: String? = null,
        startDate: String? = null,
        endDate: String? = null,
    ): JikanSearchResponse? {
        val params = mutableListOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
            "sfw" to sfw.toString(),
        )
        if (query.length >= 3) params.add(0, "q" to URLEncoder.encode(query, "UTF-8"))
        type?.let { params.add("type" to it) }
        orderBy?.let { params.add("order_by" to it) }
        sort?.let { params.add("sort" to it) }
        status?.let { params.add("status" to it) }
        rating?.let { params.add("rating" to it) }
        genres?.let { params.add("genres" to it) }
        startDate?.let { params.add("start_date" to it) }
        endDate?.let { params.add("end_date" to it) }

        val queryString = params.joinToString("&") { "${it.first}=${it.second}" }
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/$endpoint?$queryString")
        }
    }

    suspend fun getTopAnime(
        filter: String = "airing",
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/top/anime?filter=$filter&page=$page&limit=$limit")
        }
    }

    suspend fun getTopManga(
        filter: String = "publishing",
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/top/manga?filter=$filter&page=$page&limit=$limit")
        }
    }

    suspend fun getSeasonNow(
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/seasons/now?page=$page&limit=$limit")
        }
    }

    suspend fun getSeasonUpcoming(
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/seasons/upcoming?page=$page&limit=$limit")
        }
    }

    suspend fun getSeason(
        year: Int,
        season: String,
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/seasons/$year/$season?page=$page&limit=$limit")
        }
    }

    suspend fun getAnimeById(malId: Int): JikanMediaData? {
        return tryWithSuspend {
            fetchWithFallback<JikanSingleResponse>("/anime/$malId/full")?.data
        }
    }

    suspend fun getSchedules(
        filter: String? = null,  
        page: Int = 1,
        limit: Int = 25,
    ): JikanSearchResponse? {
        val params = mutableListOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
            "sfw" to "true",
        )
        filter?.let { params.add("filter" to it) }
        val queryString = params.joinToString("&") { "${it.first}=${it.second}" }
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/schedules?$queryString")
        }
    }

    suspend fun getMangaById(malId: Int): JikanMediaData? {
        return tryWithSuspend {
            fetchWithFallback<JikanSingleResponse>("/manga/$malId/full")?.data
        }
    }

    suspend fun getAnimeCharacters(malId: Int): List<JikanAnimeCharacter> {
        return tryWithSuspend {
            fetchWithFallback<JikanAnimeCharactersResponse>("/anime/$malId/characters")?.data
        } ?: emptyList()
    }

    suspend fun getMangaCharacters(malId: Int): List<JikanAnimeCharacter> {
        return tryWithSuspend {
            fetchWithFallback<JikanAnimeCharactersResponse>("/manga/$malId/characters")?.data
        } ?: emptyList()
    }

    suspend fun getAnimeStaff(malId: Int): List<JikanStaffMember> {
        return tryWithSuspend {
            fetchWithFallback<JikanStaffResponse>("/anime/$malId/staff")?.data
        } ?: emptyList()
    }

    suspend fun getAnimeReviews(malId: Int, page: Int = 1): List<JikanReview> {
        return tryWithSuspend {
            fetchWithFallback<JikanReviewResponse>("/anime/$malId/reviews?page=$page&preliminary=true&spoilers=false")?.data
        } ?: emptyList()
    }

    suspend fun getMangaReviews(malId: Int, page: Int = 1): List<JikanReview> {
        return tryWithSuspend {
            fetchWithFallback<JikanReviewResponse>("/manga/$malId/reviews?page=$page&preliminary=true&spoilers=false")?.data
        } ?: emptyList()
    }

    suspend fun searchCharacters(query: String, page: Int = 1): JikanCharacterSearchResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryWithSuspend {
            fetchWithFallback<JikanCharacterSearchResponse>("/characters?q=$encodedQuery&page=$page&order_by=favorites&sort=desc")
        }
    }

    suspend fun searchStaff(query: String, page: Int = 1): JikanPersonSearchResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryWithSuspend {
            fetchWithFallback<JikanPersonSearchResponse>("/people?q=$encodedQuery&page=$page")
        }
    }

    suspend fun searchStudios(query: String, page: Int = 1): JikanProducerSearchResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryWithSuspend {
            fetchWithFallback<JikanProducerSearchResponse>("/producers?q=$encodedQuery&page=$page")
        }
    }

    suspend fun searchUsers(query: String, page: Int = 1): JikanUserSearchResponse? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return tryWithSuspend {
            fetchWithFallback<JikanUserSearchResponse>("/users?q=$encodedQuery&page=$page")
        }
    }

    suspend fun getUserProfile(username: String): JikanUserRef? {
        val encodedUsername = URLEncoder.encode(username, "UTF-8")
        return tryWithSuspend {
            fetchWithFallback<JikanUserProfileResponse>("/users/$encodedUsername")?.data
        }
    }

    suspend fun getCharacterFull(malId: Int): JikanCharacterFullData? {
        return tryWithSuspend {
            fetchWithFallback<JikanCharacterFullResponse>("/characters/$malId/full")?.data
        }
    }

    suspend fun getPersonFull(malId: Int): JikanPersonFullData? {
        return tryWithSuspend {
            fetchWithFallback<JikanPersonFullResponse>("/people/$malId/full")?.data
        }
    }

    suspend fun getProducerFull(malId: Int): JikanProducerFullData? {
        return tryWithSuspend {
            fetchWithFallback<JikanProducerFullResponse>("/producers/$malId/full")?.data
        }
    }

    suspend fun getProducerAnime(malId: Int, page: Int = 1): JikanSearchResponse? {
        return tryWithSuspend {
            fetchWithFallback<JikanSearchResponse>("/anime?producers=$malId&order_by=start_date&sort=desc&page=$page&limit=25")
        }
    }

    suspend fun getUserFavorites(username: String): JikanUserFavoritesData? {
        return tryWithSuspend {
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            fetchWithFallback<JikanUserFavoritesResponse>("/users/$encodedUsername/favorites")?.data
        }
    }

    suspend fun getRecommendations(isAnime: Boolean, malId: Int): List<JikanRecommendation> {
        val type = if (isAnime) "anime" else "manga"
        return tryWithSuspend {
            fetchWithFallback<JikanRecommendationsResponse>("/$type/$malId/recommendations")?.data
        } ?: emptyList()
    }
}
