package ani.dantotsu.media

import android.graphics.Bitmap
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.anilist.api.MediaEdge
import ani.dantotsu.connections.anilist.api.MediaExternalLink
import ani.dantotsu.connections.anilist.api.MediaList
import ani.dantotsu.connections.anilist.api.MediaStreamingEpisode
import ani.dantotsu.connections.anilist.api.MediaType
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mal.MalAnimeNode
import ani.dantotsu.connections.mal.MalListEntry
import ani.dantotsu.connections.mal.JikanMediaData
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.profile.User
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import ani.dantotsu.connections.anilist.api.Media as ApiMedia

data class Media(
    val anime: Anime? = null,
    val manga: Manga? = null,
    val id: Int,

    var idMAL: Int? = null,
    var typeMAL: String? = null,

    val name: String?,
    val nameRomaji: String,
    val userPreferredName: String,

    var cover: String? = null,
    var banner: String? = null,
    var relation: String? = null,
    var favourites: Int? = null,

    var isAdult: Boolean,
    var isFav: Boolean = false,
    var notify: Boolean = false,

    var userListId: Int? = null,
    var isListPrivate: Boolean = false,
    var notes: String? = null,
    var userProgress: Int? = null,
    var userProgressVolumes: Int? = null,
    var userStatus: String? = null,
    var userScore: Int = 0,
    var userRepeat: Int = 0,
    var userUpdatedAt: Long? = null,
    var userStartedAt: FuzzyDate = FuzzyDate(),
    var userCompletedAt: FuzzyDate = FuzzyDate(),
    var inCustomListsOf: MutableMap<String, Boolean>? = null,
    var userFavOrder: Int? = null,

    val status: String? = null,
    var format: String? = null,
    var source: String? = null,
    var countryOfOrigin: String? = null,
    val meanScore: Int? = null,
    var genres: ArrayList<String> = arrayListOf(),
    var tags: ArrayList<String> = arrayListOf(),
    var description: String? = null,
    var synonyms: ArrayList<String> = arrayListOf(),
    var trailer: String? = null,
    var startDate: FuzzyDate? = null,
    var endDate: FuzzyDate? = null,
    var popularity: Int? = null,

    var timeUntilAiring: Long? = null,

    var characters: ArrayList<Character>? = null,
    var review: ArrayList<Query.Review>? = null,
    var staff: ArrayList<Author>? = null,
    var prequel: Media? = null,
    var sequel: Media? = null,
    var relations: ArrayList<Media>? = null,
    var recommendations: ArrayList<Media>? = null,
    var users: ArrayList<User>? = null,
    var vrvId: String? = null,
    var crunchySlug: String? = null,

    var nameMAL: String? = null,
    var folderName: String? = null,
    var shareLink: String? = null,
    var selected: Selected? = null,
    var streamingEpisodes: List<MediaStreamingEpisode>? = null,
    var idKitsu: String? = null,
    var externalLinks: ArrayList<MediaExternalLink>? = null,
    var idIMDB: String? = null,

    var cameFromContinue: Boolean = false
) : Serializable {

    constructor(apiMedia: ApiMedia) : this(
        id = apiMedia.id,
        idMAL = apiMedia.idMal,
        popularity = apiMedia.popularity,
        name = apiMedia.title!!.english,
        nameRomaji = apiMedia.title!!.romaji,
        userPreferredName = apiMedia.title!!.userPreferred,
        cover = apiMedia.coverImage?.large ?: apiMedia.coverImage?.medium,
        banner = apiMedia.bannerImage,
        status = apiMedia.status.toString(),
        isFav = apiMedia.isFavourite!!,
        isAdult = apiMedia.isAdult ?: false,
        isListPrivate = apiMedia.mediaListEntry?.private ?: false,
        userProgress = apiMedia.mediaListEntry?.progress,
        userProgressVolumes = apiMedia.mediaListEntry?.progressVolumes,
        userScore = apiMedia.mediaListEntry?.score?.toInt() ?: 0,
        userStatus = apiMedia.mediaListEntry?.status?.toString(),
        meanScore = apiMedia.meanScore,
        startDate = apiMedia.startDate,
        endDate = apiMedia.endDate,
        favourites = apiMedia.favourites,
        timeUntilAiring = apiMedia.nextAiringEpisode?.timeUntilAiring?.let { it.toLong() * 1000 },
        anime = if (apiMedia.type == MediaType.ANIME) Anime(
            totalEpisodes = apiMedia.episodes,
            nextAiringEpisode = apiMedia.nextAiringEpisode?.episode?.minus(1)
        ) else null,
        manga = if (apiMedia.type == MediaType.MANGA) Manga(
            totalChapters = apiMedia.chapters,
            totalVolumes = apiMedia.volumes
        ) else null,
        format = apiMedia.format?.toString(),
    )

    constructor(mediaList: MediaList) : this(mediaList.media!!) {
        this.userProgress = mediaList.progress
        this.userProgressVolumes = mediaList.progressVolumes
        this.isListPrivate = mediaList.private ?: false
        this.userScore = mediaList.score?.toInt() ?: 0
        this.userStatus = mediaList.status?.toString()
        this.userUpdatedAt = mediaList.updatedAt?.toLong()
        this.userStartedAt = mediaList.startedAt ?: FuzzyDate()
        this.userCompletedAt = mediaList.completedAt ?: FuzzyDate()
        this.genres =
            mediaList.media?.genres?.toMutableList() as? ArrayList<String>? ?: arrayListOf()
    }

    constructor(mediaEdge: MediaEdge) : this(mediaEdge.node!!) {
        this.relation = mediaEdge.relationType?.toString()
    }

    constructor(node: MalAnimeNode, isAnime: Boolean) : this(
        id = node.id,
        idMAL = node.id,
        name = node.alternativeTitles?.en ?: node.title,
        nameRomaji = node.title,
        userPreferredName = node.alternativeTitles?.en ?: node.title,
        cover = node.mainPicture?.large ?: node.mainPicture?.medium,
        banner = node.mainPicture?.large,
        status = when (node.status?.lowercase()) {
            "currently_airing", "currently_publishing" -> "RELEASING"
            "finished_airing", "finished" -> "FINISHED"
            "not_yet_aired", "not_yet_published" -> "NOT_YET_RELEASED"
            "on_hiatus" -> "HIATUS"
            "discontinued" -> "CANCELLED"
            else -> node.status?.replace("_", " ")?.uppercase()
        },
        isAdult = node.rating == "rx",
        meanScore = node.mean?.times(10)?.toInt(),
        popularity = node.popularity,
        format = node.mediaType?.uppercase(),
        source = node.source?.replace("_", " "),
        genres = ArrayList(node.genres?.map { it.name } ?: emptyList()),
        description = node.synopsis,
        startDate = parseIsoDate(node.startDate),
        endDate = parseIsoDate(node.endDate),
        countryOfOrigin = when (node.mediaType?.lowercase()) {
            "manhwa" -> "KR"
            "manhua" -> "CN"
            else -> if (!isAnime) "JP" else null
        },
        userStatus = if (isAnime && node.myListStatus?.isRewatching == true ||
                         !isAnime && node.myListStatus?.isRereading == true)
            "REPEATING"
        else
            convertMalStatusToAnilist(node.myListStatus?.status, isAnime),
        userProgress = if (isAnime) node.myListStatus?.numEpisodesWatched else node.myListStatus?.numChaptersRead,
        userScore = node.myListStatus?.score?.times(10) ?: 0,
        anime = if (isAnime) Anime(
            totalEpisodes = if (node.numEpisodes == 0) null else node.numEpisodes,
            season = node.startSeason?.season,
            seasonYear = node.startSeason?.year,
            episodeDuration = node.averageEpisodeDuration?.div(60),
        ) else null,
        manga = if (!isAnime) Manga(
            totalChapters = if (node.numChapters == 0) null else node.numChapters,
        ) else null,
        userStartedAt = parseIsoDate(node.myListStatus?.startDate) ?: FuzzyDate(),
        userCompletedAt = parseIsoDate(node.myListStatus?.finishDate) ?: FuzzyDate(),
    )

    constructor(entry: MalListEntry, isAnime: Boolean) : this(entry.node, isAnime) {
        val ls = entry.listStatus
        this.userProgress = if (isAnime) ls?.numEpisodesWatched else ls?.numChaptersRead
        this.userScore = ls?.score?.times(10) ?: 0
        this.userStatus = if (isAnime && ls?.isRewatching == true ||
                              !isAnime && ls?.isRereading == true)
            "REPEATING"
        else
            convertMalStatusToAnilist(ls?.status, isAnime)
        this.cameFromContinue = true
        ls?.startDate?.let { dateStr ->
            parseIsoDate(dateStr)?.let { this.userStartedAt = it }
        }
        ls?.finishDate?.let { dateStr ->
            parseIsoDate(dateStr)?.let { this.userCompletedAt = it }
        }
    }

    constructor(jikan: JikanMediaData, isAnime: Boolean) : this(
        id = jikan.malId,
        idMAL = jikan.malId,
        name = jikan.titleEnglish ?: jikan.title,
        nameRomaji = jikan.title ?: "",
        userPreferredName = jikan.titleEnglish ?: jikan.title ?: "",
        cover = jikan.images?.jpg?.largeImageUrl ?: jikan.images?.jpg?.imageUrl,
        banner = jikan.images?.jpg?.largeImageUrl,
        status = when (jikan.status?.lowercase()) {
            "currently airing", "publishing" -> "RELEASING"
            "finished airing", "finished" -> "FINISHED"
            "not yet aired", "not yet published" -> "NOT_YET_RELEASED"
            "on hiatus" -> "HIATUS"
            "discontinued" -> "CANCELLED"
            else -> jikan.status?.replace("_", " ")?.uppercase()
        },
        isAdult = jikan.rating?.contains("rx", true) == true,
        meanScore = jikan.score?.times(10)?.toInt(),
        popularity = jikan.popularity,
        format = jikan.type?.uppercase(),
        source = jikan.source?.replace("_", " "),
        genres = ArrayList(jikan.genres?.map { it.name } ?: emptyList()),
        description = jikan.synopsis,
        startDate = parseIsoDate(if (isAnime) jikan.aired?.from else jikan.published?.from),
        endDate = parseIsoDate(if (isAnime) jikan.aired?.to else jikan.published?.to),
        countryOfOrigin = when (jikan.type?.lowercase()) {
            "manhwa" -> "KR"
            "manhua" -> "CN"
            else -> if (!isAnime) "JP" else null
        },
        anime = if (isAnime) Anime(
            totalEpisodes = if (jikan.episodes == 0) null else jikan.episodes,
            season = jikan.season,
            seasonYear = jikan.year,
        ) else null,
        manga = if (!isAnime) Manga(
            totalChapters = if (jikan.chapters == 0) null else jikan.chapters,
        ) else null,
    )

    fun mainName() = name ?: nameMAL ?: nameRomaji
    fun mangaName() = if (countryOfOrigin != "JP") mainName() else nameRomaji
}


private fun parseIsoDate(dateStr: String?): FuzzyDate? {
    if (dateStr.isNullOrBlank()) return null
    val parts = dateStr.substringBefore('T').split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    return FuzzyDate(
        year = year,
        month = parts.getOrNull(1)?.toIntOrNull(),
        day = parts.getOrNull(2)?.toIntOrNull(),
    )
}


private fun convertMalStatusToAnilist(malStatus: String?, isAnime: Boolean): String? {
    return when (malStatus?.lowercase()) {
        "watching", "reading" -> "CURRENT"
        "completed" -> "COMPLETED"
        "on_hold" -> "PAUSED"
        "dropped" -> "DROPPED"
        "plan_to_watch", "plan_to_read" -> "PLANNING"
        "rewatching", "rereading" -> "REPEATING"
        else -> null 
    }
}

fun Media?.deleteFromList(
    scope: CoroutineScope,
    onSuccess: suspend () -> Unit,
    onError: suspend (e: Exception) -> Unit,
    onNotFound: suspend () -> Unit
) {
    val id = this?.userListId
    val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
    scope.launch {
        withContext(Dispatchers.IO) {
            this@deleteFromList?.let { media ->
                if (rescueMode) {
                    
                    val pending = ani.dantotsu.connections.PendingDeletion(
                        mediaId = media.id,
                        idMAL = media.idMAL,
                        isAnime = media.anime != null,
                    )
                    val existing: List<ani.dantotsu.connections.PendingDeletion> =
                        PrefManager.getVal(PrefName.PendingDeletions, listOf())
                    val updated = existing.filterNot { it.mediaId == media.id } + pending
                    PrefManager.setVal(PrefName.PendingDeletions, updated)
                    val removeList = PrefManager.getCustomVal("removeList", setOf<Int>())
                    PrefManager.setCustomVal("removeList", removeList.minus(media.id))
                    try {
                        MAL.query.deleteList(media.anime != null, media.idMAL)
                    } catch (_: Exception) { /* MAL delete failed; AniList sync still queued */ }
                    onSuccess()
                } else {
                    val _id = id ?: Anilist.query.userMediaDetails(media).userListId
                    _id?.let { listId ->
                        try {
                            Anilist.mutation.deleteList(listId)
                            MAL.query.deleteList(media.anime != null, media.idMAL)

                            val removeList = PrefManager.getCustomVal("removeList", setOf<Int>())
                            PrefManager.setCustomVal(
                                "removeList", removeList.minus(media.id)
                            )

                            onSuccess()
                        } catch (e: Exception) {
                            onError(e)
                        }
                    } ?: onNotFound()
                }
            }
        }
    }
}

fun emptyMedia() = Media(
    id = 0,
    name = "No media found",
    nameRomaji = "No media found",
    userPreferredName = "",
    isAdult = false,
    isFav = false,
    isListPrivate = false,
    userScore = 0,
    userStatus = "",
    format = "",
)

object MediaSingleton {
    var media: Media? = null
    var bitmap: Bitmap? = null
}
