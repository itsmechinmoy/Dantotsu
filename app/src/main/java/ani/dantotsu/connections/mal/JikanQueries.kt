package ani.dantotsu.connections.mal

import ani.dantotsu.client
import ani.dantotsu.tryWithSuspend
import java.net.URLEncoder

class JikanQueries {
    private val apiUrl = "https://api.jikan.moe/v4"

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
            client.get("$apiUrl/$endpoint?$queryString")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getTopAnime(
        filter: String = "airing",
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            client.get("$apiUrl/top/anime?filter=$filter&page=$page&limit=$limit")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getTopManga(
        filter: String = "publishing",
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            client.get("$apiUrl/top/manga?filter=$filter&page=$page&limit=$limit")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getSeasonNow(
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            client.get("$apiUrl/seasons/now?page=$page&limit=$limit")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getSeasonUpcoming(
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            client.get("$apiUrl/seasons/upcoming?page=$page&limit=$limit")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getSeason(
        year: Int,
        season: String,
        page: Int = 1,
        limit: Int = 15,
    ): JikanSearchResponse? {
        return tryWithSuspend {
            client.get("$apiUrl/seasons/$year/$season?page=$page&limit=$limit")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getAnimeById(malId: Int): JikanMediaData? {
        return tryWithSuspend {
            val response = client.get("$apiUrl/anime/$malId/full")
            val wrapper = response.parsed<JikanSingleResponse>()
            wrapper.data
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
            client.get("$apiUrl/schedules?$queryString")
                .parsed<JikanSearchResponse>()
        }
    }

    suspend fun getMangaById(malId: Int): JikanMediaData? {
        return tryWithSuspend {
            val response = client.get("$apiUrl/manga/$malId/full")
            val wrapper = response.parsed<JikanSingleResponse>()
            wrapper.data
        }
    }

    suspend fun getAnimeCharacters(malId: Int): List<JikanAnimeCharacter> {
        return tryWithSuspend {
            client.get("$apiUrl/anime/$malId/characters")
                .parsed<JikanAnimeCharactersResponse>()
                .data
        } ?: emptyList()
    }

    suspend fun getMangaCharacters(malId: Int): List<JikanAnimeCharacter> {
        return tryWithSuspend {
            client.get("$apiUrl/manga/$malId/characters")
                .parsed<JikanAnimeCharactersResponse>()
                .data
        } ?: emptyList()
    }

    suspend fun getAnimeStaff(malId: Int): List<JikanStaffMember> {
        return tryWithSuspend {
            client.get("$apiUrl/anime/$malId/staff")
                .parsed<JikanStaffResponse>()
                .data
        } ?: emptyList()
    }
}
