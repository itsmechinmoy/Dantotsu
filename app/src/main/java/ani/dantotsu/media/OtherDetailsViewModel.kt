package ani.dantotsu.media

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.MediaExternalLink
import ani.dantotsu.connections.mal.JikanQueries
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OtherDetailsViewModel : ViewModel() {
    companion object {
        private const val DUB_FEED_URL =
            "https://raw.githubusercontent.com/RockinChaos/AniSchedule/master/readable/dub-episode-feed-readable.json"
    }

    private val character: MutableLiveData<Character> = MutableLiveData(null)
    fun getCharacter(): LiveData<Character> = character
    suspend fun loadCharacter(m: Character) {
        if (character.value == null) character.postValue(Anilist.query.getCharacterDetails(m))
    }

    private val studio: MutableLiveData<Studio> = MutableLiveData(null)
    fun getStudio(): LiveData<Studio> = studio
    suspend fun loadStudio(m: Studio) {
        if (studio.value == null) studio.postValue(Anilist.query.getStudioDetails(m))
    }

    private val author: MutableLiveData<Author> = MutableLiveData(null)
    fun getAuthor(): LiveData<Author> = author
    suspend fun loadAuthor(m: Author) {
        if (author.value == null) author.postValue(Anilist.query.getAuthorDetails(m))
    }

    private var cachedAllCalendarData: Map<String, MutableList<Media>>? = null
    private var cachedLibraryCalendarData: Map<String, MutableList<Media>>? = null
    private var cachedDubAnilistIds: Set<Int>? = null
    private var cachedDubMalToAnilistId: Map<Int, Int>? = null
    private var cachedDubSiteMap: Map<Int, List<MediaExternalLink>>? = null
    private val calendar: MutableLiveData<Map<String, MutableList<Media>>> = MutableLiveData(null)
    fun getCalendar(): LiveData<Map<String, MutableList<Media>>> = calendar
    suspend fun loadCalendar(showOnlyLibrary: Boolean = false, showOnlyDubbed: Boolean = false) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadCalendarFromJikan(showOnlyLibrary, showOnlyDubbed)
        } else {
            loadCalendarFromAnilist(showOnlyLibrary, showOnlyDubbed)
        }
    }

    private suspend fun loadCalendarFromAnilist(showOnlyLibrary: Boolean, showOnlyDubbed: Boolean) {
        if (cachedAllCalendarData == null || cachedLibraryCalendarData == null) {
            val curr = System.currentTimeMillis() / 1000
            val res = Anilist.query.recentlyUpdated(curr - 86400, curr + (86400 * 6))
            val df = DateFormat.getDateInstance(DateFormat.FULL)
            val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
            val allMap = mutableMapOf<String, MutableList<Media>>()
            val libraryMap = mutableMapOf<String, MutableList<Media>>()
            val idMap = mutableMapOf<String, MutableList<Int>>()

            val userId = Anilist.userid ?: 0
            val userLibrary = Anilist.query.getMediaLists(true, userId)
            val libraryMediaIds = userLibrary.flatMap { it.value }.map { it.id }

            res.forEach {
                val v = it.relation?.split(",")?.map { i -> i.toLong() }!!
                val dateInfo = df.format(Date(v[1] * 1000))
                val timeInfo = tf.format(Date(v[1] * 1000))
                val list = allMap.getOrPut(dateInfo) { mutableListOf() }
                val libraryList = if (libraryMediaIds.contains(it.id)) {
                    libraryMap.getOrPut(dateInfo) { mutableListOf() }
                } else {
                    null
                }
                val idList = idMap.getOrPut(dateInfo) { mutableListOf() }
                it.relation = "Episode ${v[0]}\n$timeInfo"
                if (!idList.contains(it.id)) {
                    idList.add(it.id)
                    list.add(it)
                    libraryList?.add(it)
                }
            }

            cachedAllCalendarData = allMap
            cachedLibraryCalendarData = libraryMap
        }

        var cacheToUse: Map<String, MutableList<Media>> = if (showOnlyLibrary) {
            cachedLibraryCalendarData ?: emptyMap()
        } else {
            cachedAllCalendarData ?: emptyMap()
        }
        if (showOnlyDubbed) {
            cacheToUse = applyDubFilter(cacheToUse, rescueMode = false)
        }
        calendar.postValue(cacheToUse)
    }

    private suspend fun loadCalendarFromJikan(showOnlyLibrary: Boolean, showOnlyDubbed: Boolean) {
        if (cachedAllCalendarData == null || cachedLibraryCalendarData == null) {
            val jikan = JikanQueries()
            val df = DateFormat.getDateInstance(DateFormat.FULL)

            val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
            val dayNames = arrayOf(
                "sunday", "monday", "tuesday", "wednesday",
                "thursday", "friday", "saturday"
            )

            val allMap = mutableMapOf<String, MutableList<Media>>()
            val libraryMap = mutableMapOf<String, MutableList<Media>>()


            val watchingMalIds = mutableSetOf<Int>()
            val watchedEpisodesMap = mutableMapOf<Int, Int>()
            if (MAL.token != null) {
                tryWithSuspend {
                    var offset = 0
                    do {
                        val listResp = MAL.query.getUserAnimeList(
                            status = "watching", limit = 100, offset = offset
                        )
                        listResp?.data?.forEach { entry ->
                            watchingMalIds.add(entry.node.id)
                            watchedEpisodesMap[entry.node.id] =
                                entry.listStatus?.numEpisodesWatched ?: 0
                        }
                        offset += 100
                    } while (listResp?.paging?.next != null)
                }
            }


            for (offsetDay in -1..6) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, offsetDay)
                val dayName = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
                val dateStr = df.format(cal.time)

                val allMedia = mutableListOf<Media>()
                val seenIds = mutableSetOf<Int>()
                var page = 1
                var hasNext = true
                while (hasNext) {
                    val resp = jikan.getSchedules(
                        filter = dayName, page = page, limit = 25
                    ) ?: break
                    resp.data.forEach { jikanData ->
                        if (!seenIds.contains(jikanData.malId)) {
                            seenIds.add(jikanData.malId)
                            val media = Media(jikanData, isAnime = true)
                            media.userProgress = watchedEpisodesMap[jikanData.malId]
                            

                            val fromDateStr = jikanData.aired?.from
                            var airedEps: Int? = null
                            if (fromDateStr != null) {
                                try {
                                    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                    val startDate = isoFormat.parse(fromDateStr.take(19))
                                    if (startDate != null) {
                                        val targetTime = cal.time.time
                                        val diff = targetTime - startDate.time
                                        if (diff >= 0) {
                                            val weeks = (diff / (7L * 24 * 60 * 60 * 1000)).toInt()
                                            var estimated = weeks + 1
                                            if (jikanData.episodes != null && estimated > jikanData.episodes) {
                                                estimated = jikanData.episodes
                                            }

                                            val watched = watchedEpisodesMap[jikanData.malId] ?: 0
                                            if (watched > estimated) {
                                                estimated = watched
                                            }
                                            airedEps = estimated
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            media.anime?.nextAiringEpisode = airedEps


                            val nextEp = watchedEpisodesMap[jikanData.malId]?.let { it + 1 }
                            
                            var timeStr = ""
                            var sortTime = Int.MAX_VALUE
                            val bTime = jikanData.broadcast?.time
                            val bTz = jikanData.broadcast?.timezone
                            if (!bTime.isNullOrBlank() && !bTz.isNullOrBlank()) {
                                try {
                                    val parser = SimpleDateFormat("HH:mm", Locale.US)
                                    parser.timeZone = TimeZone.getTimeZone(bTz)
                                    val parsedTime = parser.parse(bTime)
                                    if (parsedTime != null) {

                                        val targetCal = Calendar.getInstance(TimeZone.getTimeZone(bTz))
                                        targetCal.time = cal.time
                                        val timeCal = Calendar.getInstance(TimeZone.getTimeZone(bTz))
                                        timeCal.time = parsedTime
                                        targetCal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                                        targetCal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
                                        
                                        tf.timeZone = TimeZone.getDefault()
                                        timeStr = tf.format(targetCal.time)
                                        
                                        val localCal = Calendar.getInstance()
                                        localCal.time = targetCal.time
                                        sortTime = localCal.get(Calendar.HOUR_OF_DAY) * 60 + localCal.get(Calendar.MINUTE)
                                    }
                                } catch (_: Exception) {
                                    timeStr = "$bTime $bTz"
                                }
                            } else if (!bTime.isNullOrBlank()) {
                                timeStr = bTime
                            }

                            media.relation = if (nextEp != null) {
                                "Episode $nextEp" + (if (timeStr.isNotBlank()) "\n$timeStr" else "")
                            } else {
                                timeStr.ifBlank { null }
                            }
                            media.userUpdatedAt = sortTime.toLong()
                            allMedia.add(media)
                        }
                    }
                    hasNext = resp.pagination?.hasNextPage == true

                    kotlinx.coroutines.delay(350)
                    page++
                }

                allMedia.sortBy { it.userUpdatedAt ?: Long.MAX_VALUE }

                allMap[dateStr] = allMedia
                val libList = allMedia.filter { watchingMalIds.contains(it.id) }.toMutableList()
                if (libList.isNotEmpty()) {
                    libraryMap[dateStr] = libList
                }
            }

            cachedAllCalendarData = allMap
            cachedLibraryCalendarData = libraryMap
        }

        var cacheToUse: Map<String, MutableList<Media>> = if (showOnlyLibrary) {
            cachedLibraryCalendarData ?: emptyMap()
        } else {
            cachedAllCalendarData ?: emptyMap()
        }
        if (showOnlyDubbed) {
            cacheToUse = applyDubFilter(cacheToUse, rescueMode = true)
        }
        calendar.postValue(cacheToUse)
    }

    private suspend fun applyDubFilter(
        data: Map<String, MutableList<Media>>,
        rescueMode: Boolean
    ): Map<String, MutableList<Media>> {
        ensureDubDataLoaded()
        val dubAnilistIds = cachedDubAnilistIds ?: emptySet()
        val dubMalToAnilist = cachedDubMalToAnilistId ?: emptyMap()
        val dubSiteMap = cachedDubSiteMap ?: emptyMap()

        val filteredMap = linkedMapOf<String, MutableList<Media>>()
        data.forEach { (date, mediaList) ->
            val filteredList = mediaList.mapNotNull { media ->
                val matchedAnilistId = when {
                    dubAnilistIds.contains(media.id) -> media.id
                    rescueMode -> media.idMAL?.let { dubMalToAnilist[it] }
                    else -> null
                } ?: return@mapNotNull null

                media.dubStreamingSites = ArrayList(dubSiteMap[matchedAnilistId] ?: emptyList())
                media
            }.toMutableList()
            if (filteredList.isNotEmpty()) {
                filteredMap[date] = filteredList
            }
        }
        return filteredMap
    }

    private suspend fun ensureDubDataLoaded() {
        if (cachedDubAnilistIds != null && cachedDubMalToAnilistId != null && cachedDubSiteMap != null) {
            return
        }

        val feedItems = tryWithSuspend {
            val response = client.get(DUB_FEED_URL)
            Mapper.json.decodeFromString<List<DubEpisodeFeedItem>>(response.text)
        } ?: emptyList()

        val anilistIds = feedItems.map { it.id }.toSet()
        val malToAnilist = feedItems.mapNotNull { item ->
            item.idMal?.let { it to item.id }
        }.toMap()

        cachedDubAnilistIds = anilistIds
        cachedDubMalToAnilistId = malToAnilist

        if (anilistIds.isEmpty()) {
            cachedDubSiteMap = emptyMap()
            return
        }

        val siteMap = mutableMapOf<Int, List<MediaExternalLink>>()
        anilistIds.toList().chunked(50).forEach { chunk ->
            val chunkMap = tryWithSuspend {
                Anilist.query.getStreamingExternalLinks(chunk)
            } ?: emptyMap()
            chunkMap.forEach { (id, links) ->
                if (links.isNotEmpty()) siteMap[id] = links
            }
        }
        cachedDubSiteMap = siteMap
    }
}

@Serializable
private data class DubEpisodeFeedItem(
    @SerialName("id") val id: Int,
    @SerialName("idMal") val idMal: Int? = null,
)
