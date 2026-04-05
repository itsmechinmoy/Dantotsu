package ani.dantotsu.media.mangaupdates

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import ani.dantotsu.client
import ani.dantotsu.currActivity
import ani.dantotsu.media.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume

object MangaAnimeUtil {

    private const val MANGABAKA_BASE = "https://api.mangabaka.dev/v1"
    private const val MU_ARCHIVE_BASE = "https://www.mangaupdates.com/releases/archive"

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFormat = SimpleDateFormat("d MMMM", Locale.US)

    @Serializable
    data class MangaBakaResponse(val data: MangaBakaData? = null)

    @Serializable
    data class MangaBakaData(val series: List<MangaBakaSeries>? = null)

    @Serializable
    data class MangaBakaSeries(
        val id: Int? = null,
        val has_anime: Boolean? = null,
        val anime: MangaBakaAnime? = null,
        val source: MangaBakaSource? = null
    )

    @Serializable
    data class MangaBakaAnime(val start: String? = null, val end: String? = null)

    @Serializable
    data class MangaBakaSource(val manga_updates: MangaUpdatesInfo? = null)

    @Serializable
    data class MangaUpdatesInfo(val id: String? = null)

    data class AnimeAdaptation(
        val hasAdaptation: Boolean = false,
        val animeStart: String? = null,
        val animeEnd: String? = null,
        val error: String? = null
    )

    data class NextRelease(
        val nextReleaseDate: Date? = null,
        val averageIntervalDays: Int? = null,
        val latestChapter: String? = null,
        val nextChapter: String? = null,
        val error: String? = null
    )

    private data class PredictionResult(
        val date: Date,
        val interval: Int,
        val chapterName: String
    )

    suspend fun getSeriesFromMedia(media: Media): List<MangaBakaSeries>? =
        withContext(Dispatchers.IO) {
            val endpoints = buildList {
                if (media.source == "anilist" || media.source == null) {
                    add("$MANGABAKA_BASE/source/anilist/${media.id}")
                }
                if (media.idMAL != null) {
                    add("$MANGABAKA_BASE/source/my-anime-list/${media.idMAL}")
                }
            }
            if (endpoints.isEmpty()) return@withContext null

            val deferred = endpoints.map { endpoint ->
                async {
                    runCatching {
                        val response = client.get(endpoint)
                        if (response.code == 200) {
                            val body = response.body?.string()
                            val result = json.decodeFromString<MangaBakaResponse>(
                                body ?: return@async null
                            )
                            result.data?.series?.takeIf { it.isNotEmpty() }
                        } else null
                    }.getOrNull()
                }
            }

            for (result in deferred) {
                result.await()?.let { return@withContext it }
            }
            null
        }

    suspend fun getAnimeAdaptation(data:List<MangaBakaSeries>?): AnimeAdaptation = withContext(Dispatchers.IO) {
        runCatching {
            val seriesData = data
                ?: return@runCatching AnimeAdaptation(hasAdaptation = false)
            val series = seriesData.firstOrNull()
                ?: return@runCatching AnimeAdaptation(hasAdaptation = false)

            if (series.has_anime == true && series.anime != null) {
                val (start, end) = series.anime
                if (start != null || end != null) {
                    return@runCatching AnimeAdaptation(
                        animeStart = start,
                        animeEnd = end,
                        hasAdaptation = true
                    )
                }
            }
            AnimeAdaptation(hasAdaptation = false)
        }.getOrElse { AnimeAdaptation(hasAdaptation = false, error = it.message) }
    }

    suspend fun getNextChapterPrediction(
        media: Media,
        data: List<MangaBakaSeries>?
    ): NextRelease = withContext(Dispatchers.IO) {

        if (media.status?.uppercase() != "RELEASING") {
            return@withContext NextRelease(error = "Series is not releasing")
        }

        runCatching {
            val seriesData = data
                ?: throw Exception("MangaBaka series not found")

            val muId = seriesData.firstOrNull()?.source?.manga_updates?.id
                ?: throw Exception("MU ID missing")

            val numericId = resolveNumericMuId(muId)

            val url = "$MU_ARCHIVE_BASE?search=$numericId&search_type=series"

            val response = client.get(url, headers = muHeaders)

            if (!response.isSuccessful) {
                throw Exception("Failed to fetch archive page")
            }

            val html = response.body.string()

            val (releaseDates, latestChapter) = parseArchiveData(html)

            if (releaseDates.size < 2) {
                throw Exception("Insufficient release data found")
            }

            val prediction = calculatePrediction(releaseDates, latestChapter)

            NextRelease(
                nextReleaseDate = prediction.date,
                averageIntervalDays = prediction.interval,
                latestChapter = latestChapter,
                nextChapter = prediction.chapterName
            )
        }.getOrElse {
            NextRelease(error = it.message)
        }
    }
    private val muHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.mangaupdates.com/"
    )
    suspend fun resolveNumericMuId(muId: String): String = withContext(Dispatchers.IO) {
        val seriesUrl = "https://www.mangaupdates.com/series/$muId"

        val response = client.get(seriesUrl, headers = muHeaders)

        if (!response.isSuccessful) {
            throw Exception("Failed to fetch MU series page")
        }

        val html = response.body.string()

        Regex("""series_id["\s:]+(\d+)""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""search=(\d+)&amp;search_type=series""")
                .find(html)?.groupValues?.get(1)
            ?: throw Exception("Numeric MU ID not found (site changed)")
    }

    private fun parseArchiveData(html: String): Pair<List<Date>, String?> {
        val releaseDates = mutableListOf<Date>()
        var latestChapter: String? = null

        val rowPattern = Pattern.compile(
            "<div class=\"col-12 row.*?\">(.*?)</div>\\s*</div>",
            Pattern.DOTALL
        )

        val datePattern = Pattern.compile("col-2 text\">(\\d{4}-\\d{2}-\\d{2})")
        val chapterPattern = Pattern.compile("col-1 text text-center\">([^<]*)</div>")

        val rowMatcher = rowPattern.matcher(html)

        while (rowMatcher.find()) {
            val row = rowMatcher.group(1)

            val date = datePattern.matcher(row).takeIf { it.find() }?.group(1)
            val chapter = chapterPattern.matcher(row).let {
                var last: String? = null
                while (it.find()) last = it.group(1)
                last
            }

            if (date != null && chapter != null) {
                val parsedDate = runCatching { dateFormat.parse(date) }.getOrNull()
                if (parsedDate != null) {
                    releaseDates.add(parsedDate)

                    val cleanChapter = chapter.replace("c.", "").trim()
                    if (latestChapter == null && cleanChapter.any { it.isDigit() }) {
                        latestChapter = cleanChapter
                    }
                }
            }
        }

        return releaseDates to latestChapter
    }

    private fun calculatePrediction(
        releaseDates: List<Date>,
        latestChapter: String?
    ): PredictionResult {
        val sampleSize = minOf(releaseDates.size, 10)
        val intervals = mutableListOf<Long>()

        for (i in 0 until sampleSize - 1) {
            val diff = (releaseDates[i].time - releaseDates[i + 1].time) / (1000 * 60 * 60 * 24)
            if (diff in 1..364) intervals.add(diff)
        }

        val avgInterval = if (intervals.isNotEmpty()) {
            intervals.average().toInt().coerceAtLeast(1)
        } else 7

        val latestRelease = releaseDates.first()
        var predictedDate = Date(latestRelease.time + (avgInterval * 24L * 60 * 60 * 1000))
        val now = Date()

        var chaptersToAdd = 1
        while (predictedDate.before(now)) {
            predictedDate = Date(predictedDate.time + (avgInterval * 24L * 60 * 60 * 1000))
            chaptersToAdd++
        }

        val chapterName = latestChapter?.let { chapter ->
            val numericMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(chapter)
            if (numericMatcher.find()) {
                numericMatcher.group(1).toDoubleOrNull()?.let { num ->
                    val nextNum = num + chaptersToAdd
                    if (nextNum % 1 == 0.0) "Chapter ${nextNum.toInt()}"
                    else "Chapter $nextNum"
                }
            } else null
        } ?: "Next Chapter"

        return PredictionResult(predictedDate, avgInterval, chapterName)
    }
}