package ani.dantotsu.connections.anilist

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.currContext
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import androidx.core.net.toUri
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object Anilist {
    val query: AnilistQueries = AnilistQueries()
    val mutation: AnilistMutations = AnilistMutations()

    var token: String? = null
    var username: String? = null

    var userid: Int? = null
    var avatar: String? = null
    var bg: String? = null
    var episodesWatched: Int? = null
    var chapterRead: Int? = null
    var unreadNotificationCount: Int = 0

    var genres: ArrayList<String>? = null
    var tags: Map<Boolean, List<String>>? = null

    val rateLimiter: AnilistRateLimiter = AnilistRateLimiter()
    val coalescer: AnilistRequestCoalescer<String, String> = AnilistRequestCoalescer()
    val responseCache: AnilistResponseCache = AnilistResponseCache(
        diskDirProvider = {
            currContext()?.cacheDir?.let { File(it, "anilist_cache") }
        }
    )

    val rateLimitState: StateFlow<AnilistRateLimitState>
        get() = rateLimiter.state

    /** Legacy epoch-seconds cooldown gate, kept synced with [rateLimiter]. */
    var rateLimitReset: Long
        get() = rateLimiter.currentBlockedUntilMs() / 1000L
        set(value) {
            if (value <= 0L) {
                rateLimiter.clearExternalGate()
            } else {
                rateLimiter.externalBlockedUntilMs = value * 1000L
            }
        }

    fun isRateLimited(): Boolean {
        val state = rateLimiter.state.value
        return state.waiting || state.exhausted || rateLimiter.isCurrentlyBlocked()
    }

    @Volatile
    private var lastRateLimitSnackMs: Long = 0L

    var initialized = false
    var adult: Boolean = false
    var titleLanguage: String? = null
    var staffNameLanguage: String? = null
    var airingNotifications: Boolean = false
    var restrictMessagesToFollowing: Boolean = false
    var scoreFormat: String? = null
    var rowOrder: String? = null
    var activityMergeTime: Int? = null
    var timezone: String? = null
    var animeCustomLists: List<String>? = null
    var mangaCustomLists: List<String>? = null

    /** Set to true when the AniList API reports a "disabled" error. Reset on next successful call. */
    @Volatile
    var anilistDisabledSignal: Boolean = false
    var lastError: String? = null

    val sortBy = listOf(
        "SCORE_DESC",
        "POPULARITY_DESC",
        "TRENDING_DESC",
        "START_DATE_DESC",
        "TITLE_ENGLISH",
        "TITLE_ENGLISH_DESC",
        "SCORE",
        "FAVOURITES_DESC",
        "UPDATED_AT_DESC"
    )

    val source = listOf(
        "ORIGINAL",
        "MANGA",
        "LIGHT NOVEL",
        "VISUAL NOVEL",
        "VIDEO GAME",
        "OTHER",
        "NOVEL",
        "DOUJINSHI",
        "ANIME",
        "WEB NOVEL",
        "LIVE ACTION",
        "GAME",
        "COMIC",
        "MULTIMEDIA PROJECT",
        "PICTURE BOOK"
    )

    val animeStatus = listOf(
        "FINISHED",
        "RELEASING",
        "NOT YET RELEASED",
        "CANCELLED"
    )

    val mangaStatus = listOf(
        "FINISHED",
        "RELEASING",
        "NOT YET RELEASED",
        "HIATUS",
        "CANCELLED"
    )

    val seasons = listOf(
        "WINTER", "SPRING", "SUMMER", "FALL"
    )

    val animeFormats = listOf(
        "TV", "TV SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC"
    )

    val mangaFormats = listOf(
        "MANGA", "NOVEL", "ONE SHOT"
    )

    val authorRoles = listOf(
        "Original Creator", "Story & Art", "Story"
    )

    val timeZone = listOf(
        "(GMT-11:00) Pago Pago",
        "(GMT-10:00) Hawaii Time",
        "(GMT-09:00) Alaska Time",
        "(GMT-08:00) Pacific Time",
        "(GMT-07:00) Mountain Time",
        "(GMT-06:00) Central Time",
        "(GMT-05:00) Eastern Time",
        "(GMT-04:00) Atlantic Time - Halifax",
        "(GMT-03:00) Sao Paulo",
        "(GMT-02:00) Mid-Atlantic",
        "(GMT-01:00) Azores",
        "(GMT+00:00) London",
        "(GMT+01:00) Berlin",
        "(GMT+02:00) Helsinki",
        "(GMT+03:00) Istanbul",
        "(GMT+04:00) Dubai",
        "(GMT+04:30) Kabul",
        "(GMT+05:00) Maldives",
        "(GMT+05:30) India Standard Time",
        "(GMT+05:45) Kathmandu",
        "(GMT+06:00) Dhaka",
        "(GMT+06:30) Cocos",
        "(GMT+07:00) Bangkok",
        "(GMT+08:00) Hong Kong",
        "(GMT+08:30) Pyongyang",
        "(GMT+09:00) Tokyo",
        "(GMT+09:30) Central Time - Darwin",
        "(GMT+10:00) Eastern Time - Brisbane",
        "(GMT+10:30) Central Time - Adelaide",
        "(GMT+11:00) Eastern Time - Melbourne, Sydney",
        "(GMT+12:00) Nauru",
        "(GMT+13:00) Auckland",
        "(GMT+14:00) Kiritimati",
    )

    val titleLang = listOf(
        "English (Attack on Titan)",
        "Romaji (Shingeki no Kyojin)",
        "Native (進撃の巨人)"
    )

    val staffNameLang = listOf(
        "Romaji, Western Order (Killua Zoldyck)",
        "Romaji (Zoldyck Killua)",
        "Native (キルア=ゾルディック)"
    )

    val scoreFormats = listOf(
        "100 Point (55/100)",
        "10 Point Decimal (5.5/10)",
        "10 Point (5/10)",
        "5 Star (3/5)",
        "3 Point Smiley :)"
    )

    val rowOrderMap = mapOf(
        "Score" to "score",
        "Title" to "title",
        "Last Updated" to "updatedAt",
        "Last Added" to "id"
    )

    val activityMergeTimeMap = mapOf(
        "Never" to 0,
        "30 mins" to 30,
        "69 mins" to 69,
        "1 hour" to 60,
        "2 hours" to 120,
        "3 hours" to 180,
        "6 hours" to 360,
        "12 hours" to 720,
        "1 day" to 1440,
        "2 days" to 2880,
        "3 days" to 4320,
        "1 week" to 10080,
        "2 weeks" to 20160,
        "Always" to 29160
    )

    private val cal: Calendar = Calendar.getInstance()
    private val currentYear = cal.get(Calendar.YEAR)
    private val currentSeason: Int = when (cal.get(Calendar.MONTH)) {
        0, 1, 2 -> 0
        3, 4, 5 -> 1
        6, 7, 8 -> 2
        9, 10, 11 -> 3
        else -> 0
    }

    fun getDisplayTimezone(apiTimezone: String, context: Context): String {
        val noTimezone = context.getString(R.string.selected_no_time_zone)
        val parts = apiTimezone.split(":")
        if (parts.size != 2) return noTimezone

        val hours = parts[0].toIntOrNull() ?: 0
        val minutes = parts[1].toIntOrNull() ?: 0
        val sign = if (hours >= 0) "+" else "-"
        val formattedHours = String.format(Locale.US, "%02d", abs(hours))
        val formattedMinutes = String.format(Locale.US, "%02d", minutes)

        val searchString = "(GMT$sign$formattedHours:$formattedMinutes)"
        return timeZone.find { it.contains(searchString) } ?: noTimezone
    }

    fun getApiTimezone(displayTimezone: String): String {
        val regex = """\(GMT([+-])(\d{2}):(\d{2})\)""".toRegex()
        val matchResult = regex.find(displayTimezone)
        return if (matchResult != null) {
            val (sign, hours, minutes) = matchResult.destructured
            val formattedSign = if (sign == "+") "" else "-"
            "$formattedSign$hours:$minutes"
        } else {
            "00:00"
        }
    }

    private fun getSeason(next: Boolean): Pair<String, Int> {
        var newSeason = if (next) currentSeason + 1 else currentSeason - 1
        var newYear = currentYear
        if (newSeason > 3) {
            newSeason = 0
            newYear++
        } else if (newSeason < 0) {
            newSeason = 3
            newYear--
        }
        return seasons[newSeason] to newYear
    }

    val currentSeasons = listOf(
        getSeason(false),
        seasons[currentSeason] to currentYear,
        getSeason(true)
    )

    fun loginIntent(context: Context) {
        val clientID = 14959
        try {
            CustomTabsIntent.Builder().build().launchUrl(
                context,
                "https://anilist.co/api/v2/oauth/authorize?client_id=$clientID&response_type=token".toUri()
            )
        } catch (_: ActivityNotFoundException) {
            openLinkInBrowser("https://anilist.co/api/v2/oauth/authorize?client_id=$clientID&response_type=token")
        }
    }

    /** Set to true when AniList rejects the current token with 401 / Invalid token. */
    @Volatile
    var tokenInvalid: Boolean = false

    fun getSavedToken(): Boolean {
        token = PrefManager.getVal(PrefName.AnilistToken, null as String?)
        tokenInvalid = false
        return !token.isNullOrEmpty()
    }

    fun saveToken(newToken: String) {
        val changed = token != newToken
        token = newToken
        tokenInvalid = false
        PrefManager.setVal(PrefName.AnilistToken, newToken)
        if (changed) {
            clearAllCaches()
        }
    }

    fun removeSavedToken() {
        token = null
        tokenInvalid = false
        username = null
        adult = false
        userid = null
        avatar = null
        bg = null
        episodesWatched = null
        chapterRead = null
        initialized = false
        PrefManager.removeVal(PrefName.AnilistToken)
        PrefManager.removeVal(PrefName.AnilistUserId)
        PrefManager.removeVal(PrefName.AnilistUserName)
        // Reset per-section notification counts
        PrefManager.setVal(PrefName.UnreadUserNotifications, 0)
        PrefManager.setVal(PrefName.UnreadMediaNotifications, 0)
        PrefManager.setVal(PrefName.UnreadSubscriptionNotifications, 0)
        PrefManager.setVal(PrefName.UnreadCommentNotifications, 0)
        Anilist.unreadNotificationCount = 0
        clearAllCaches()
        //logout from comments api
        CommentsAPI.logout()

    }

    /**
     * Decodes the JWT token and returns the number of days until expiry.
     * Returns null if the token is missing or cannot be decoded.
     * Returns <= 0 if the token is already expired or invalidated (401).
     */
    fun getTokenExpiryDays(): Long? {
        val t = token?.takeIf { it.isNotEmpty() }
            ?: PrefManager.getVal(PrefName.AnilistToken, null as String?)?.takeIf { it.isNotEmpty() }
            ?: return null
        if (tokenInvalid) return -1L
        return try {
            val parts = t.split(".")
            if (parts.size != 3) return null
            // JWT segments are Base64URL encoded (- and _); replacing with + and / while
            // passing URL_SAFE breaks decoding on any token containing '-' or '_'.
            val payload = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val json = Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
            val expPrim = json["exp"]?.jsonPrimitive ?: return null
            val expSeconds = expPrim.longOrNull ?: expPrim.doubleOrNull?.toLong() ?: return null
            val nowSeconds = System.currentTimeMillis() / 1000L
            val remainingSeconds = expSeconds - nowSeconds
            if (remainingSeconds <= 0L) {
                remainingSeconds / 86400L
            } else {
                (remainingSeconds + 86399L) / 86400L
            }
        } catch (e: Exception) {
            Logger.log("getTokenExpiryDays error: ${e.message}")
            null
        }
    }

    fun clearAllCaches() {
        responseCache.clearAll()
        query.invalidateHomePageCache()
        query.invalidateUserStatusCache()
        query.invalidateBannerCache()
        rateLimiter.reset()
    }

    fun buildRequestKey(query: String, variables: String = "", useToken: Boolean = true): String {
        val authScope = if (useToken && !token.isNullOrEmpty()) {
            "u:${userid ?: token.hashCode()}"
        } else {
            "anon"
        }
        return "$authScope|${query.trim()}|${variables.trim()}"
    }

    private fun buildCacheKey(
        query: String,
        variables: String,
        useToken: Boolean,
        cacheSpec: AnilistCacheSpec,
    ): String {
        val override = cacheSpec.cacheKeyOverride ?: return buildRequestKey(query, variables, useToken)
        val authScope = if (useToken && !token.isNullOrEmpty()) {
            "u:${userid ?: token.hashCode()}"
        } else {
            "anon"
        }
        return "$authScope|$override"
    }

    fun peekCachedRawQuery(
        query: String,
        variables: String = "",
        useToken: Boolean = true,
        cacheSpec: AnilistCacheSpec,
    ): CacheLookup {
        val key = buildCacheKey(query, variables, useToken, cacheSpec)
        return responseCache.get(key, cacheSpec.category, checkDisk = cacheSpec.persistToDisk)
    }

    fun buildGraphQLHeaders(useToken: Boolean): MutableMap<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
            "Referer" to "https://anilist.co/",
            "Origin" to "https://anilist.co"
        )
        if (token != null && useToken) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    fun buildGraphQLRequestBody(query: String, variables: String): okhttp3.RequestBody {
        val jsonPayload = buildString {
            append("{\"query\":")
            append(Json.encodeToString(query))
            if (variables.isNotBlank() && variables.trim().startsWith("{")) {
                append(",\"variables\":")
                append(variables.trim())
            }
            append("}")
        }
        return jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    fun handle403Or400Error(jsonText: String, jsonCode: Int, show: Boolean) {
        val message = extractGraphQLForbiddenMessage(jsonText, jsonCode)
        lastError = message
        if (message.contains("disabled", ignoreCase = true)) {
            anilistDisabledSignal = true
        } else if (jsonCode == 401 || message.contains("Invalid token", ignoreCase = true)) {
            tokenInvalid = true
            if (!show) snackString("Anilist token expired, please login again")
        } else {
            if (!show) snackString("Error fetching Anilist data: $message")
        }
        throw Exception(message)
    }

    fun handleQueryException(e: Exception, show: Boolean) {
        if (e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e.cause is java.net.UnknownHostException ||
            e.cause is java.net.ConnectException) {
            anilistDisabledSignal = true
        }
        if (lastError == null) {
            lastError = e.message
        }
        if (show) snackString("Error fetching Anilist data: ${e.message}")
        Logger.log("Anilist Query Error: ${e.message}")
    }

    private fun handleRateLimitException(
        e: AnilistRateLimitException,
        isMutation: Boolean,
        show: Boolean,
    ) {
        val rateMsg = e.message ?: "Rate limited. Try after ${e.waitSeconds} seconds"
        lastError = rateMsg
        if (isMutation && show) {
            val now = System.currentTimeMillis()
            if (now - lastRateLimitSnackMs > 30_000L) {
                lastRateLimitSnackMs = now
                snackString(rateMsg)
            }
        }
        Logger.log("Anilist Rate Limited: $rateMsg")
    }

    suspend fun executeRawQuery(
        query: String,
        variables: String = "",
        force: Boolean = false,
        useToken: Boolean = true,
        show: Boolean = false,
        @Suppress("UNUSED_PARAMETER") cache: Int? = null,
        cacheSpec: AnilistCacheSpec? = null,
        bypassCache: Boolean = false,
    ): String? {
        lastError = null
        if (token == null && !force) return null
        val isMutation = isGraphqlMutation(query)
        // Mutations are never cached.
        val cacheKey = cacheSpec?.takeIf { !isMutation }?.let { buildCacheKey(query, variables, useToken, it) }
        if (cacheSpec != null && cacheKey != null && !bypassCache) {
            val cached = responseCache.get(cacheKey, cacheSpec.category, checkDisk = cacheSpec.persistToDisk)
            if (cached is CacheLookup.Fresh) return cached.value
        }
        val request = AnilistRawRequest(query, variables, useToken, show, isMutation, cacheSpec, cacheKey)
        return try {
            if (isMutation) {
                fetchOverNetwork(request)
            } else {
                coalescer.execute(buildRequestKey(query, variables, useToken)) { fetchOverNetwork(request) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnilistRateLimitException) {
            handleRateLimitException(e, isMutation, show)
            null
        } catch (e: Exception) {
            handleQueryException(e, show)
            null
        }
    }

    /** One GraphQL call as [executeRawQuery] sends it. */
    private class AnilistRawRequest(
        val query: String,
        val variables: String,
        val useToken: Boolean,
        val show: Boolean,
        val isMutation: Boolean,
        val cacheSpec: AnilistCacheSpec?,
        val cacheKey: String?,
    )

    /** Sends [r] through the rate limiter, checks the answer and caches it. */
    private suspend fun fetchOverNetwork(r: AnilistRawRequest): String {
        if (r.show) Logger.log("Anilist Query: ${r.query}")
        val headers = buildGraphQLHeaders(r.useToken)
        val weight = estimateGraphqlOperationCount(r.query)
        val response = rateLimiter.execute(isMutation = r.isMutation, weight = weight) {
            val res = client.post(
                "https://graphql.anilist.co/",
                headers,
                requestBody = buildGraphQLRequestBody(r.query, r.variables),
                cacheTime = 0
            )
            AnilistHttpResponse(
                code = res.code,
                text = res.text,
                retryAfterSeconds = res.headers["Retry-After"]?.toIntOrNull(),
                rateLimitRemaining = res.headers["X-RateLimit-Remaining"]?.toIntOrNull(),
                rateLimitResetRaw = res.headers["X-RateLimit-Reset"]?.toLongOrNull(),
                rateLimitLimit = res.headers["X-RateLimit-Limit"]?.toIntOrNull(),
            )
        }
        Logger.log("Remaining requests: ${response.rateLimitRemaining ?: -1}")
        if (response.code == 403 || response.code == 400 || response.code == 401) {
            handle403Or400Error(response.text, response.code, r.show)
        }
        if (!response.text.startsWith("{")) {
            anilistDisabledSignal = true
            val downMsg = (currContext()?.getString(R.string.anilist_down) ?: "AniList is down") + " (error: ${response.code})"
            lastError = downMsg
            throw Exception(downMsg)
        }
        anilistDisabledSignal = false
        if (r.useToken && token != null) tokenInvalid = false
        if (r.cacheSpec != null && r.cacheKey != null) responseCache.put(r.cacheKey, response.text, r.cacheSpec)
        return response.text
    }

    suspend inline fun <reified T : Any> executeQuery(
        query: String,
        variables: String = "",
        force: Boolean = false,
        useToken: Boolean = true,
        show: Boolean = false,
        cache: Int? = null,
        cacheSpec: AnilistCacheSpec? = null,
        bypassCache: Boolean = false,
    ): T? {
        val raw = executeRawQuery(
            query = query,
            variables = variables,
            force = force,
            useToken = useToken,
            show = show,
            cache = cache,
            cacheSpec = cacheSpec,
            bypassCache = bypassCache,
        ) ?: return null
        return try {
            Mapper.parse<T>(raw)
        } catch (e: Exception) {
            if (lastError == null) {
                lastError = e.message
            }
            Logger.log("Anilist Query Parse Error: ${e.message}")
            null
        }
    }

    fun extractGraphQLForbiddenMessage(text: String, code: Int): String {
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val firstError = root["errors"]?.jsonArray?.firstOrNull()?.jsonObject
            val validationObj = firstError?.get("validation")?.jsonObject
            if (validationObj != null) {
                val validationMessages = validationObj.values.flatMap { elem ->
                    (elem as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        it.jsonPrimitive.contentOrNull
                    } ?: emptyList()
                }
                if (validationMessages.isNotEmpty()) {
                    validationMessages.joinToString("\n")
                } else {
                    firstError["message"]?.jsonPrimitive?.contentOrNull
                }
            } else {
                firstError?.get("message")?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull() ?: "Forbidden (error $code)"
    }

}
