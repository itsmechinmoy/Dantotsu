package ani.dantotsu.connections.anilist

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mal.MalAnimeNode
import ani.dantotsu.media.Media
import ani.dantotsu.media.Character
import ani.dantotsu.media.Studio
import ani.dantotsu.media.Author
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.profile.User
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.connections.syncPendingProgressUpdates
import ani.dantotsu.connections.syncPendingDeletions
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun getUserId(context: Context, block: () -> Unit) {
    if (!Anilist.initialized && PrefManager.getVal<String>(PrefName.AnilistToken) != "") {
        if (Anilist.query.getUserData()) {
            tryWithSuspend {
                if (MAL.token != null && !MAL.query.getUserData())
                    snackString(context.getString(R.string.error_loading_mal_user_data))
            }
        } else {
            snackString(context.getString(R.string.error_loading_anilist_user_data))
        }
    }
    block.invoke()
}

class AnilistHomeViewModel : ViewModel() {
    private val listImages: MutableLiveData<ArrayList<String?>> =
        MutableLiveData<ArrayList<String?>>(arrayListOf())

    fun getListImages(): LiveData<ArrayList<String?>> = listImages
    suspend fun setListImages() = listImages.postValue(Anilist.query.getBannerImages())

    private val animeContinue: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimeContinue(): LiveData<ArrayList<Media>> = animeContinue

    private val animeFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimeFav(): LiveData<ArrayList<Media>> = animeFav

    private val animePlanned: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimePlanned(): LiveData<ArrayList<Media>> = animePlanned

    private val mangaContinue: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaContinue(): LiveData<ArrayList<Media>> = mangaContinue

    private val mangaFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaFav(): LiveData<ArrayList<Media>> = mangaFav

    private val mangaPlanned: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaPlanned(): LiveData<ArrayList<Media>> = mangaPlanned

    private val recommendation: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getRecommendation(): LiveData<ArrayList<Media>> = recommendation

    private val missingSequels: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMissingSequels(): LiveData<ArrayList<Media>> = missingSequels

    private val userStatus: MutableLiveData<ArrayList<User>> =
        MutableLiveData<ArrayList<User>>(null)

    fun getUserStatus(): LiveData<ArrayList<User>> = userStatus
    suspend fun initUserStatus() {
        val res = Anilist.query.getUserStatus()
        res?.let { userStatus.postValue(it) }
    }

    private val hidden: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getHidden(): LiveData<ArrayList<Media>> = hidden

    suspend fun initHomePage() {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            initHomePageFromMAL()
            return
        }
        val res = Anilist.query.initHomePage()
        res["currentAnime"]?.let { animeContinue.postValue(it) }
        res["favoriteAnime"]?.let { animeFav.postValue(it) }
        res["currentAnimePlanned"]?.let { animePlanned.postValue(it) }
        res["currentManga"]?.let { mangaContinue.postValue(it) }
        res["favoriteManga"]?.let { mangaFav.postValue(it) }
        res["currentMangaPlanned"]?.let { mangaPlanned.postValue(it) }
        res["recommendations"]?.let { recommendation.postValue(it) }
        res["missingSequels"]?.let { missingSequels.postValue(it) }
        res["hidden"]?.let { hidden.postValue(it) }
    }

    private suspend fun initHomePageFromMAL() {
        hidden.postValue(arrayListOf())

        if (MAL.token == null) {
            tryWithSuspend {
                val res = MAL.jikan.getSeasonUpcoming(limit = 15)
                recommendation.postValue(ArrayList(res?.data?.map { Media(it, true) } ?: emptyList()))
            }
            animeFav.postValue(arrayListOf())
            mangaFav.postValue(arrayListOf())
            missingSequels.postValue(arrayListOf())
            return
        }

        tryWithSuspend {
            val suggestions = MAL.query.getAnimeSuggestions(limit = 15)
            if (!suggestions?.data.isNullOrEmpty()) {
                recommendation.postValue(ArrayList(suggestions!!.data.map { Media(it.node, true) }))
            } else {
                val res = MAL.jikan.getSeasonUpcoming(limit = 15)
                recommendation.postValue(ArrayList(res?.data?.map { Media(it, true) } ?: emptyList()))
            }
        }

        tryWithSuspend {
            MAL.query.getUserAnimeList(status = "watching", limit = 20)?.data?.let { entries ->
                animeContinue.postValue(ArrayList(entries.map { Media(it, true) }))
            }
        }
        tryWithSuspend {
            MAL.query.getUserAnimeList(status = "plan_to_watch", limit = 20)?.data?.let { entries ->
                animePlanned.postValue(ArrayList(entries.map { Media(it, true) }))
            }
        }
        tryWithSuspend {
            MAL.query.getUserMangaList(status = "reading", limit = 20)?.data?.let { entries ->
                mangaContinue.postValue(ArrayList(entries.map { Media(it, false) }))
            }
        }
        tryWithSuspend {
            MAL.query.getUserMangaList(status = "plan_to_read", limit = 20)?.data?.let { entries ->
                mangaPlanned.postValue(ArrayList(entries.map { Media(it, false) }))
            }
        }

        val username = MAL.username
        if (!username.isNullOrBlank()) {
            tryWithSuspend {
                val favData = MAL.jikan.getUserFavorites(username)
                if (favData != null) {
                    val favAnime = favData.anime.map { fav ->
                        Media(
                            id = fav.malId,
                            idMAL = fav.malId,
                            name = fav.title,
                            nameRomaji = fav.title ?: "",
                            userPreferredName = fav.title ?: "",
                            cover = fav.images?.jpg?.largeImageUrl ?: fav.images?.jpg?.imageUrl,
                            isAdult = false,
                            anime = Anime(null, null, null),
                        )
                    }
                    animeFav.postValue(ArrayList(favAnime))

                    val favManga = favData.manga.map { fav ->
                        Media(
                            id = fav.malId,
                            idMAL = fav.malId,
                            name = fav.title,
                            nameRomaji = fav.title ?: "",
                            userPreferredName = fav.title ?: "",
                            cover = fav.images?.jpg?.largeImageUrl ?: fav.images?.jpg?.imageUrl,
                            isAdult = false,
                            manga = Manga(),
                        )
                    }
                    mangaFav.postValue(ArrayList(favManga))
                } else {
                    animeFav.postValue(arrayListOf())
                    mangaFav.postValue(arrayListOf())
                }
            }
        } else {
            animeFav.postValue(arrayListOf())
            mangaFav.postValue(arrayListOf())
        }

        tryWithSuspend {
            val completedWithRelations = MAL.query.getCompletedAnimeWithRelations(limit = 100)
            val allUserAnimeIds = MAL.query.getAllUserAnimeIds()

            val sequelIds = mutableSetOf<Int>()
            val sequelNodes = mutableMapOf<Int, MalAnimeNode>()

            completedWithRelations?.data?.forEach { entry ->
                entry.node.relatedAnime?.forEach { rel ->
                    if ((rel.relationType?.lowercase() == "sequel" || rel.relationTypeFormatted?.lowercase() == "sequel") && rel.node != null) {
                        val sequelId = rel.node.id
                        if (sequelId !in allUserAnimeIds) {
                            sequelIds.add(sequelId)
                            sequelNodes[sequelId] = rel.node
                        }
                    }
                }
            }

            if (sequelIds.isNotEmpty()) {
                val sequelMedia = sequelNodes.values.map { node ->
                    Media(
                        id = node.id,
                        idMAL = node.id,
                        name = node.alternativeTitles?.en ?: node.title,
                        nameRomaji = node.title,
                        userPreferredName = node.alternativeTitles?.en ?: node.title,
                        cover = node.mainPicture?.large ?: node.mainPicture?.medium,
                        meanScore = node.mean?.times(10)?.toInt(),
                        isFav = false,
                        isAdult = false,
                        status = node.status,
                        anime = Anime(
                            totalEpisodes = node.numEpisodes,
                            season = null,
                            seasonYear = null,
                        ),
                    )
                }
                missingSequels.postValue(ArrayList(sequelMedia))
            } else {
                missingSequels.postValue(arrayListOf())
            }
        } ?: missingSequels.postValue(arrayListOf())
    }

    suspend fun loadMain(context: FragmentActivity) {
        Anilist.getSavedToken()
        MAL.getSavedToken()
        Discord.getSavedToken()
        if (!BuildConfig.FLAVOR.contains("fdroid")) {
            if (PrefManager.getVal(PrefName.CheckUpdate))
                context.lifecycleScope.launch(Dispatchers.IO) {
                    AppUpdater.check(context, false)
                }
        }
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            if (MAL.token != null) tryWithSuspend { MAL.query.getUserData() }
            if (Anilist.genres == null) {
                Anilist.genres = arrayListOf(
                    "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy",
                    "Horror", "Mecha", "Music", "Mystery", "Psychological", "Romance",
                    "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
                    "Shounen", "Shoujo", "Seinen", "Josei", "Isekai", "Harem",
                    "Gourmet", "Historical", "Martial Arts", "Military", "Parody",
                    "School", "Space", "Super Power", "Vampire",
                    "Boys Love", "Girls Love", "Kids", "Samurai"
                )
                PrefManager.setVal(PrefName.GenresList, Anilist.genres!!.toSet())
            }
            withContext(Dispatchers.Main) { genres.value = true }
        } else {
            syncPendingProgressUpdates()
            syncPendingDeletions()
            val ret = Anilist.query.getGenresAndTags()
            withContext(Dispatchers.Main) { genres.value = ret }
        }
    }

    val empty = MutableLiveData<Boolean>(null)

    var loaded: Boolean = false
    val genres: MutableLiveData<Boolean?> = MutableLiveData(null)
}

class AnilistAnimeViewModel : ViewModel() {
    var searched = false
    var notSet = true
    lateinit var aniMangaSearchResults: AniMangaSearchResults
    private val type = "ANIME"
    private val trending: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getTrending(): LiveData<MutableList<Media>> = trending
    suspend fun loadTrending(i: Int) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val res = when (i) {
                0 -> {
                    val (season, year) = Anilist.currentSeasons[0]
                    MAL.jikan.getSeason(year, season.lowercase(), limit = 12)
                }
                2 -> MAL.jikan.getSeasonUpcoming(limit = 12)
                else -> MAL.jikan.getSeasonNow(limit = 12)
            }
            trending.postValue(res?.data?.map { Media(it, true) }?.toMutableList())
            return
        }
        val (season, year) = Anilist.currentSeasons[i]
        trending.postValue(
            Anilist.query.searchAniManga(
                type,
                perPage = 12,
                sort = Anilist.sortBy[2],
                season = season,
                seasonYear = year,
                hd = true,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )?.results
        )
    }


    private val animePopular = MutableLiveData<AniMangaSearchResults?>(null)

    fun getPopular(): LiveData<AniMangaSearchResults?> = animePopular
    suspend fun loadPopular(
        type: String,
        searchVal: String? = null,
        genres: ArrayList<String>? = null,
        sort: String = Anilist.sortBy[1],
        onList: Boolean = true,
    ) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            if (searchVal.isNullOrBlank()) {
                val limit = 50
                val malRes = MAL.query.getAnimeRanking("bypopularity", limit = limit)
                val mapped = malRes?.data?.map { Media(it.node, true) } ?: emptyList()
                val filtered = if (!onList) mapped.filter { it.userStatus == null } else mapped
                animePopular.postValue(AniMangaSearchResults(
                    type = "ANIME", isAdult = false, search = null, onList = onList,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            } else {
                val limit = if (onList) 25 else 50
                val malRes = MAL.query.searchAnime(searchVal, limit = limit)
                val mapped = malRes?.data?.map { Media(it.node, true) } ?: emptyList()
                val filtered = if (!onList) mapped.filter { it.userStatus == null } else mapped
                animePopular.postValue(AniMangaSearchResults(
                    type = "ANIME", isAdult = false, search = searchVal, onList = if (onList) null else false,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            }
            return
        }
        animePopular.postValue(
            Anilist.query.searchAniManga(
                type,
                search = searchVal,
                onList = if (onList) null else false,
                sort = sort,
                genres = genres,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )
        )
    }


    suspend fun loadNextPage(r: AniMangaSearchResults) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val searchTerm = r.search
            if (searchTerm.isNullOrBlank()) {
                val limit = 50
                val malRes = MAL.query.getAnimeRanking("bypopularity", limit = limit, offset = r.page * limit)
                val mapped = malRes?.data?.map { Media(it.node, true) } ?: emptyList()
                val filtered = if (r.onList == false) mapped.filter { it.userStatus == null } else mapped
                animePopular.postValue(AniMangaSearchResults(
                    type = "ANIME", isAdult = false, search = null, page = r.page + 1, onList = r.onList,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            } else {
                val limit = if (r.onList == false) 50 else 25
                val malRes = MAL.query.searchAnime(searchTerm, limit = limit, offset = r.page * limit)
                val mapped = malRes?.data?.map { Media(it.node, true) } ?: emptyList()
                val filtered = if (r.onList == false) mapped.filter { it.userStatus == null } else mapped
                animePopular.postValue(AniMangaSearchResults(
                    type = "ANIME", isAdult = false, search = searchTerm, page = r.page + 1, onList = r.onList,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            }
            return
        }
        animePopular.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page + 1,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly),
            )
        )
    }

    var loaded: Boolean = false
    private val updated: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getUpdated(): LiveData<MutableList<Media>> = updated

    private val popularMovies: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getMovies(): LiveData<MutableList<Media>> = popularMovies

    private val topRatedAnime: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getTopRated(): LiveData<MutableList<Media>> = topRatedAnime

    private val mostFavAnime: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getMostFav(): LiveData<MutableList<Media>> = mostFavAnime
    suspend fun loadAll() {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadAllFromMAL()
            return
        }
        val list = Anilist.query.loadAnimeList()
        updated.postValue(list["recentUpdates"])
        popularMovies.postValue(list["trendingMovies"])
        topRatedAnime.postValue(list["topRated"])
        mostFavAnime.postValue(list["mostFav"])
    }

    private suspend fun loadAllFromMAL() {
        tryWithSuspend {
            MAL.query.getAnimeRanking("airing", 15)?.data?.let { entries ->
                updated.postValue(entries.map { Media(it.node, true) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getAnimeRanking("bypopularity", 15)?.data?.let { entries ->
                popularMovies.postValue(entries.map { Media(it.node, true) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getAnimeRanking("all", 15)?.data?.let { entries ->
                topRatedAnime.postValue(entries.map { Media(it.node, true) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getAnimeRanking("favorite", 15)?.data?.let { entries ->
                mostFavAnime.postValue(entries.map { Media(it.node, true) }.toMutableList())
            }
        }
    }
}

class AnilistMangaViewModel : ViewModel() {
    var searched = false
    var notSet = true
    lateinit var aniMangaSearchResults: AniMangaSearchResults
    private val type = "MANGA"
    private val trending: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getTrending(): LiveData<MutableList<Media>> = trending
    suspend fun loadTrending() {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val res = MAL.jikan.getTopManga(filter = "publishing", limit = 10)
            trending.postValue(res?.data?.map { Media(it, false) }?.toMutableList())
            return
        }
        trending.postValue(
            Anilist.query.searchAniManga(
                type,
                perPage = 10,
                sort = Anilist.sortBy[2],
                hd = true,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )?.results
        )
    }


    private val mangaPopular = MutableLiveData<AniMangaSearchResults?>(null)
    fun getPopular(): LiveData<AniMangaSearchResults?> = mangaPopular
    suspend fun loadPopular(
        type: String,
        searchVal: String? = null,
        genres: ArrayList<String>? = null,
        sort: String = Anilist.sortBy[1],
        onList: Boolean = true,
    ) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            if (searchVal.isNullOrBlank()) {
                val limit = 50
                val malRes = MAL.query.getMangaRanking("bypopularity", limit = limit)
                val mapped = malRes?.data?.map { Media(it.node, false) } ?: emptyList()
                val filtered = if (!onList) mapped.filter { it.userStatus == null } else mapped
                mangaPopular.postValue(AniMangaSearchResults(
                    type = "MANGA", isAdult = false, search = null, onList = onList,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            } else {
                val limit = if (onList) 25 else 50
                val malRes = MAL.query.searchManga(searchVal, limit = limit)
                val mapped = malRes?.data?.map { Media(it.node, false) } ?: emptyList()
                val filtered = if (!onList) mapped.filter { it.userStatus == null } else mapped
                mangaPopular.postValue(AniMangaSearchResults(
                    type = "MANGA", isAdult = false, search = searchVal, onList = if (onList) null else false,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            }
            return
        }
        mangaPopular.postValue(
            Anilist.query.searchAniManga(
                type,
                search = searchVal,
                onList = if (onList) null else false,
                sort = sort,
                genres = genres,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )
        )
    }


    suspend fun loadNextPage(r: AniMangaSearchResults) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val searchTerm = r.search
            if (searchTerm.isNullOrBlank()) {
                val limit = 50
                val malRes = MAL.query.getMangaRanking("bypopularity", limit = limit, offset = r.page * limit)
                val mapped = malRes?.data?.map { Media(it.node, false) } ?: emptyList()
                val filtered = if (r.onList == false) mapped.filter { it.userStatus == null } else mapped
                mangaPopular.postValue(AniMangaSearchResults(
                    type = "MANGA", isAdult = false, search = null, page = r.page + 1, onList = r.onList,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            } else {
                val limit = if (r.onList == false) 50 else 25
                val malRes = MAL.query.searchManga(searchTerm, limit = limit, offset = r.page * limit)
                val mapped = malRes?.data?.map { Media(it.node, false) } ?: emptyList()
                val filtered = if (r.onList == false) mapped.filter { it.userStatus == null } else mapped
                mangaPopular.postValue(AniMangaSearchResults(
                    type = "MANGA", isAdult = false, search = searchTerm, page = r.page + 1, onList = r.onList,
                    results = filtered.toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
            }
            return
        }
        mangaPopular.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page + 1,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )
        )
    }

    var loaded: Boolean = false

    private val popularManga: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getPopularManga(): LiveData<MutableList<Media>> = popularManga

    private val popularManhwa: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getPopularManhwa(): LiveData<MutableList<Media>> = popularManhwa

    private val popularNovel: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getPopularNovel(): LiveData<MutableList<Media>> = popularNovel

    private val topRatedManga: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getTopRated(): LiveData<MutableList<Media>> = topRatedManga

    private val mostFavManga: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getMostFav(): LiveData<MutableList<Media>> = mostFavManga
    suspend fun loadAll() {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadAllFromMAL()
            return
        }
        val list = Anilist.query.loadMangaList()
        popularManga.postValue(list["trendingManga"])
        popularManhwa.postValue(list["trendingManhwa"])
        popularNovel.postValue(list["trendingNovel"])
        topRatedManga.postValue(list["topRated"])
        mostFavManga.postValue(list["mostFav"])
    }

    private suspend fun loadAllFromMAL() {
        tryWithSuspend {
            MAL.query.getMangaRanking("manga", 15)?.data?.let { entries ->
                popularManga.postValue(entries.map { Media(it.node, false) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getMangaRanking("manhwa", 15)?.data?.let { entries ->
                popularManhwa.postValue(entries.map { Media(it.node, false) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getMangaRanking("novels", 15)?.data?.let { entries ->
                popularNovel.postValue(entries.map { Media(it.node, false) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getMangaRanking("all", 15)?.data?.let { entries ->
                topRatedManga.postValue(entries.map { Media(it.node, false) }.toMutableList())
            }
        }
        tryWithSuspend {
            MAL.query.getMangaRanking("favorite", 15)?.data?.let { entries ->
                mostFavManga.postValue(entries.map { Media(it.node, false) }.toMutableList())
            }
        }
    }
}

class AnilistSearch : ViewModel() {

    enum class SearchType {
        ANIME, MANGA, CHARACTER, STAFF, STUDIO, USER;

        companion object {

            fun SearchType.toAnilistString(): String {
                return when (this) {
                    ANIME -> "ANIME"
                    MANGA -> "MANGA"
                    CHARACTER -> "CHARACTER"
                    STAFF -> "STAFF"
                    STUDIO -> "STUDIO"
                    USER -> "USER"
                }
            }

            fun fromString(string: String): SearchType {
                return when (string.uppercase()) {
                    "ANIME" -> ANIME
                    "MANGA" -> MANGA
                    "CHARACTER" -> CHARACTER
                    "STAFF" -> STAFF
                    "STUDIO" -> STUDIO
                    "USER" -> USER
                    else -> throw IllegalArgumentException("Invalid search type")
                }
            }
        }
    }

    var searched = false
    var notSet = true
    lateinit var aniMangaSearchResults: AniMangaSearchResults
    private val aniMangaResult: MutableLiveData<AniMangaSearchResults?> =
        MutableLiveData<AniMangaSearchResults?>(null)

    lateinit var characterSearchResults: CharacterSearchResults
    private val characterResult: MutableLiveData<CharacterSearchResults?> =
        MutableLiveData<CharacterSearchResults?>(null)

    lateinit var studioSearchResults: StudioSearchResults
    private val studioResult: MutableLiveData<StudioSearchResults?> =
        MutableLiveData<StudioSearchResults?>(null)

    lateinit var staffSearchResults: StaffSearchResults
    private val staffResult: MutableLiveData<StaffSearchResults?> =
        MutableLiveData<StaffSearchResults?>(null)

    lateinit var userSearchResults: UserSearchResults
    private val userResult: MutableLiveData<UserSearchResults?> =
        MutableLiveData<UserSearchResults?>(null)

    fun <T> getSearch(type: SearchType): MutableLiveData<T?> {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaResult as MutableLiveData<T?>
            SearchType.CHARACTER -> characterResult as MutableLiveData<T?>
            SearchType.STUDIO -> studioResult as MutableLiveData<T?>
            SearchType.STAFF -> staffResult as MutableLiveData<T?>
            SearchType.USER -> userResult as MutableLiveData<T?>
        }
    }

    suspend fun loadSearch(type: SearchType) {
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> loadAniMangaSearch(aniMangaSearchResults)
            SearchType.CHARACTER -> loadCharacterSearch(characterSearchResults)
            SearchType.STUDIO -> loadStudiosSearch(studioSearchResults)
            SearchType.STAFF -> loadStaffSearch(staffSearchResults)
            SearchType.USER -> loadUserSearch(userSearchResults)
        }
    }

    suspend fun loadNextPage(type: SearchType) {
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> loadNextAniMangaPage(aniMangaSearchResults)
            SearchType.CHARACTER -> loadNextCharacterPage(characterSearchResults)
            SearchType.STUDIO -> loadNextStudiosPage(studioSearchResults)
            SearchType.STAFF -> loadNextStaffPage(staffSearchResults)
            SearchType.USER -> loadNextUserPage(userSearchResults)
        }
    }

    fun hasNextPage(type: SearchType): Boolean {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.hasNextPage
            SearchType.CHARACTER -> characterSearchResults.hasNextPage
            SearchType.STUDIO -> studioSearchResults.hasNextPage
            SearchType.STAFF -> staffSearchResults.hasNextPage
            SearchType.USER -> userSearchResults.hasNextPage
        }
    }

    fun resultsIsNotEmpty(type: SearchType): Boolean {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.results.isNotEmpty()
            SearchType.CHARACTER -> characterSearchResults.results.isNotEmpty()
            SearchType.STUDIO -> studioSearchResults.results.isNotEmpty()
            SearchType.STAFF -> staffSearchResults.results.isNotEmpty()
            SearchType.USER -> userSearchResults.results.isNotEmpty()
        }
    }

    fun size(type: SearchType): Int {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.results.size
            SearchType.CHARACTER -> characterSearchResults.results.size
            SearchType.STUDIO -> studioSearchResults.results.size
            SearchType.STAFF -> staffSearchResults.results.size
            SearchType.USER -> userSearchResults.results.size
        }
    }

    fun clearResults(type: SearchType) {
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.results.clear()
            SearchType.CHARACTER -> characterSearchResults.results.clear()
            SearchType.STUDIO -> studioSearchResults.results.clear()
            SearchType.STAFF -> staffSearchResults.results.clear()
            SearchType.USER -> userSearchResults.results.clear()
        }
    }

    private suspend fun loadAniMangaSearch(r: AniMangaSearchResults) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val isAnime = r.type == "ANIME"

            if (!r.search.isNullOrBlank() && MAL.token != null) {
                val malRes = tryWithSuspend {
                    if (isAnime) MAL.query.searchAnime(r.search!!, limit = 25)
                    else MAL.query.searchManga(r.search!!, limit = 25)
                }
                aniMangaResult.postValue(AniMangaSearchResults(
                    type = r.type,
                    isAdult = r.isAdult,
                    search = r.search,
                    sort = r.sort,
                    genres = r.genres,
                    status = r.status,
                    format = r.format,
                    seasonYear = r.seasonYear,
                    startYear = r.startYear,
                    page = r.page,
                    results = (malRes?.data?.map { Media(it.node, isAnime) } ?: emptyList()).toMutableList(),
                    hasNextPage = malRes?.paging?.next != null,
                ))
                return
            }

            val jikanType = if (isAnime) "anime" else "manga"
            val jikanStatus = when (r.status?.uppercase()) {
                "RELEASING", "AIRING" -> "airing"
                "FINISHED", "COMPLETE" -> "complete"
                "NOT_YET_RELEASED", "UPCOMING" -> "upcoming"
                else -> null
            }
            val (orderBy, sortDir) = when (r.sort) {
                Anilist.sortBy[0] -> "score" to "desc"
                Anilist.sortBy[1] -> "members" to "desc"
                Anilist.sortBy[2] -> "members" to "desc"
                Anilist.sortBy[3] -> "start_date" to "desc"
                Anilist.sortBy[4] -> "title" to "asc"
                Anilist.sortBy[5] -> "title" to "desc"
                else -> "members" to "desc"
            }
            val jikanGenreMap = mapOf(
                "Action" to 1, "Adventure" to 2, "Cars" to 3, "Comedy" to 4,
                "Mystery" to 7, "Drama" to 8, "Ecchi" to 9, "Fantasy" to 10,
                "Game" to 11, "Historical" to 13, "Horror" to 14, "Kids" to 15,
                "Magic" to 16, "Martial Arts" to 17, "Mecha" to 18, "Music" to 19,
                "Parody" to 20, "Samurai" to 21, "Romance" to 22, "School" to 23,
                "Sci-Fi" to 24, "Shoujo" to 25, "Girls Love" to 26, "Shounen" to 27,
                "Boys Love" to 28, "Space" to 29, "Sports" to 30, "Super Power" to 31,
                "Vampire" to 32, "Harem" to 35, "Slice of Life" to 36, "Supernatural" to 37,
                "Military" to 38, "Police" to 39, "Psychological" to 40, "Thriller" to 41,
                "Seinen" to 42, "Josei" to 43, "Gourmet" to 47, "Work" to 48,
                "Erotica" to 49, "Isekai" to 62
            )
            val genresParam = r.genres?.mapNotNull { jikanGenreMap[it] }?.joinToString(",")

            val jikanFormat = r.format?.lowercase()?.let {
                when (it) {
                    "tv", "movie", "ova", "special", "ona", "music" -> it
                    "tv_short" -> "tv"
                    "manga", "novel", "light_novel", "oneshot", "doujin", "manhwa", "manhua" -> it.replace("_", "")
                    else -> null
                }
            }

            val year = if (isAnime) r.seasonYear else r.startYear
            val (startDate, endDate) = if (year != null) "$year-01-01" to "$year-12-31" else null to null
            val res = MAL.jikan.search(
                query = r.search ?: "",
                endpoint = jikanType,
                type = jikanFormat,
                page = r.page,
                status = jikanStatus,
                orderBy = orderBy,
                sort = sortDir,
                genres = genresParam,
                startDate = startDate,
                endDate = endDate,
                sfw = !r.isAdult,
            )
            aniMangaResult.postValue(AniMangaSearchResults(
                type = r.type,
                isAdult = r.isAdult,
                search = r.search,
                sort = r.sort,
                genres = r.genres,
                status = r.status,
                format = r.format,
                seasonYear = r.seasonYear,
                startYear = r.startYear,
                page = r.page,
                results = (res?.data?.map { Media(it, isAnime) } ?: emptyList()).toMutableList(),
                hasNextPage = res?.pagination?.hasNextPage ?: false,
            ))
            return
        }
        aniMangaResult.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season,
            )
        )
    }

    private suspend fun loadCharacterSearch(r: CharacterSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchCharacters(search, r.page)
                characterResult.postValue(CharacterSearchResults(
                    search = search, page = r.page,
                    results = res?.data?.map { Character(it.malId, it.name, it.images?.jpg?.imageUrl, null, "", false) }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                characterResult.postValue(CharacterSearchResults(
                    search = search, page = r.page,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        characterResult.postValue(Anilist.query.searchCharacters(r.page, r.search))
    }

    private suspend fun loadStudiosSearch(r: StudioSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchStudios(search, r.page)
                studioResult.postValue(StudioSearchResults(
                    search = search, page = r.page,
                    results = res?.data?.map { Studio(it.malId.toString(), it.name ?: "", false, it.favorites ?: 0, it.images?.jpg?.imageUrl) }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                studioResult.postValue(StudioSearchResults(
                    search = search, page = r.page,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        studioResult.postValue(Anilist.query.searchStudios(r.page, r.search))
    }

    private suspend fun loadStaffSearch(r: StaffSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchStaff(search, r.page)
                staffResult.postValue(StaffSearchResults(
                    search = search, page = r.page,
                    results = res?.data?.map { Author(it.malId, it.name ?: "", it.images?.jpg?.imageUrl, "STAFF") }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                staffResult.postValue(StaffSearchResults(
                    search = search, page = r.page,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        staffResult.postValue(Anilist.query.searchStaff(r.page, r.search))
    }

    private suspend fun loadUserSearch(r: UserSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchUsers(search, r.page)
                val resultsList = res?.data?.map {
                    User(
                        id = it.username.hashCode().and(0x7FFFFFFF),
                        name = it.username ?: "",
                        pfp = it.images?.jpg?.imageUrl,
                        banner = "https://myanimelist.net/profile/${it.username ?: ""}"
                    )
                }?.toMutableList() ?: mutableListOf()

                if (resultsList.isEmpty()) {
                    val exactProfile = MAL.jikan.getUserProfile(search)
                    if (exactProfile != null) {
                        resultsList.add(
                            User(
                                id = exactProfile.username.hashCode().and(0x7FFFFFFF),
                                name = exactProfile.username ?: "",
                                pfp = exactProfile.images?.jpg?.imageUrl,
                                banner = "https://myanimelist.net/profile/${exactProfile.username ?: ""}"
                            )
                        )
                    }
                }

                userResult.postValue(UserSearchResults(
                    search = search, page = r.page,
                    results = resultsList,
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                userResult.postValue(UserSearchResults(
                    search = search, page = r.page,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        userResult.postValue(Anilist.query.searchUsers(r.page, r.search))
    }

    private suspend fun loadNextAniMangaPage(r: AniMangaSearchResults) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            val isAnime = r.type == "ANIME"
            val jikanEndpoint = if (isAnime) "anime" else "manga"
            val jikanStatus = when (r.status?.uppercase()) {
                "RELEASING", "AIRING" -> "airing"
                "FINISHED", "COMPLETE" -> "complete"
                "NOT_YET_RELEASED", "UPCOMING" -> "upcoming"
                else -> null
            }
            val (orderBy, sortDir) = when (r.sort) {
                Anilist.sortBy[0] -> "score" to "desc"
                Anilist.sortBy[1] -> "members" to "desc"
                Anilist.sortBy[2] -> "members" to "desc"
                Anilist.sortBy[3] -> "start_date" to "desc"
                Anilist.sortBy[4] -> "title" to "asc"
                Anilist.sortBy[5] -> "title" to "desc"
                else -> "members" to "desc"
            }
            val jikanGenreMap = mapOf(
                "Action" to 1, "Adventure" to 2, "Cars" to 3, "Comedy" to 4,
                "Mystery" to 7, "Drama" to 8, "Ecchi" to 9, "Fantasy" to 10,
                "Game" to 11, "Historical" to 13, "Horror" to 14, "Kids" to 15,
                "Magic" to 16, "Martial Arts" to 17, "Mecha" to 18, "Music" to 19,
                "Parody" to 20, "Samurai" to 21, "Romance" to 22, "School" to 23,
                "Sci-Fi" to 24, "Shoujo" to 25, "Girls Love" to 26, "Shounen" to 27,
                "Boys Love" to 28, "Space" to 29, "Sports" to 30, "Super Power" to 31,
                "Vampire" to 32, "Harem" to 35, "Slice of Life" to 36, "Supernatural" to 37,
                "Military" to 38, "Police" to 39, "Psychological" to 40, "Thriller" to 41,
                "Seinen" to 42, "Josei" to 43, "Gourmet" to 47, "Work" to 48,
                "Erotica" to 49, "Isekai" to 62
            )
            val genresParam = r.genres?.mapNotNull { jikanGenreMap[it] }?.joinToString(",")
            val jikanFormat = r.format?.lowercase()?.let {
                when (it) {
                    "tv", "movie", "ova", "special", "ona", "music" -> it
                    "tv_short" -> "tv"
                    "manga", "novel", "light_novel", "oneshot", "doujin", "manhwa", "manhua" -> it.replace("_", "")
                    else -> null
                }
            }
            val year = if (isAnime) r.seasonYear else r.startYear
            val (startDate, endDate) = if (year != null) "$year-01-01" to "$year-12-31" else null to null
            val res = MAL.jikan.search(
                query = r.search ?: "",
                endpoint = jikanEndpoint,
                type = jikanFormat,
                page = r.page + 1,
                status = jikanStatus,
                orderBy = orderBy,
                sort = sortDir,
                genres = genresParam,
                startDate = startDate,
                endDate = endDate,
                sfw = !r.isAdult,
            )
            aniMangaResult.postValue(AniMangaSearchResults(
                type = r.type,
                isAdult = r.isAdult,
                search = r.search,
                sort = r.sort,
                genres = r.genres,
                status = r.status,
                format = r.format,
                seasonYear = r.seasonYear,
                startYear = r.startYear,
                page = r.page + 1,
                results = (res?.data?.map { Media(it, isAnime) } ?: emptyList()).toMutableList(),
                hasNextPage = res?.pagination?.hasNextPage ?: false,
            ))
            return
        }
        aniMangaResult.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page + 1,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season
            )
        )
    }

    private suspend fun loadNextCharacterPage(r: CharacterSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchCharacters(search, r.page + 1)
                characterResult.postValue(CharacterSearchResults(
                    search = search, page = r.page + 1,
                    results = res?.data?.map { Character(it.malId, it.name, it.images?.jpg?.imageUrl, null, "", false) }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                characterResult.postValue(CharacterSearchResults(
                    search = search, page = r.page + 1,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        characterResult.postValue(Anilist.query.searchCharacters(r.page + 1, r.search))
    }

    private suspend fun loadNextStudiosPage(r: StudioSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchStudios(search, r.page + 1)
                studioResult.postValue(StudioSearchResults(
                    search = search, page = r.page + 1,
                    results = res?.data?.map { Studio(it.malId.toString(), it.name ?: "", false, it.favorites ?: 0, it.images?.jpg?.imageUrl) }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                studioResult.postValue(StudioSearchResults(
                    search = search, page = r.page + 1,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        studioResult.postValue(Anilist.query.searchStudios(r.page + 1, r.search))
    }

    private suspend fun loadNextStaffPage(r: StaffSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchStaff(search, r.page + 1)
                staffResult.postValue(StaffSearchResults(
                    search = search, page = r.page + 1,
                    results = res?.data?.map { Author(it.malId, it.name ?: "", it.images?.jpg?.imageUrl, "STAFF") }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                staffResult.postValue(StaffSearchResults(
                    search = search, page = r.page + 1,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        staffResult.postValue(Anilist.query.searchStaff(r.page + 1, r.search))
    }

    private suspend fun loadNextUserPage(r: UserSearchResults) {
        if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            val search = r.search
            if (!search.isNullOrBlank()) {
                val res = MAL.jikan.searchUsers(search, r.page + 1)
                userResult.postValue(UserSearchResults(
                    search = search, page = r.page + 1,
                    results = res?.data?.map { User(it.username.hashCode().and(0x7FFFFFFF), it.username ?: "", it.images?.jpg?.imageUrl, "https://myanimelist.net/profile/${it.username ?: ""}") }?.toMutableList() ?: mutableListOf(),
                    hasNextPage = res?.pagination?.hasNextPage ?: false
                ))
            } else {
                userResult.postValue(UserSearchResults(
                    search = search, page = r.page + 1,
                    results = mutableListOf(),
                    hasNextPage = false
                ))
            }
            return
        }
        userResult.postValue(Anilist.query.searchUsers(r.page + 1, r.search))
    }
}

class GenresViewModel : ViewModel() {
    var genres: MutableMap<String, String>? = null
    var done = false
    var doneListener: (() -> Unit)? = null
    suspend fun loadGenres(genre: ArrayList<String>, listener: (Pair<String, String>) -> Unit) {
        if (genres == null) {
            genres = mutableMapOf()
            if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                loadGenresFromJikan(genre, listener)
            } else {
                Anilist.query.getGenres(genre) {
                    genres!![it.first] = it.second
                    listener.invoke(it)
                    if (genres!!.size == genre.size) {
                        done = true
                        doneListener?.invoke()
                    }
                }
            }
        }
    }

    private suspend fun loadGenresFromJikan(genre: ArrayList<String>, listener: (Pair<String, String>) -> Unit) {
        val jikanGenreMap = mapOf(
            "Action" to 1, "Adventure" to 2, "Comedy" to 4,
            "Mystery" to 7, "Drama" to 8, "Ecchi" to 9, "Fantasy" to 10,
            "Horror" to 14, "Mecha" to 18, "Music" to 19,
            "Romance" to 22, "Sci-Fi" to 24, "Slice of Life" to 36,
            "Sports" to 30, "Supernatural" to 37, "Thriller" to 41,
            "Psychological" to 40, "Shounen" to 27, "Shoujo" to 25,
            "Seinen" to 42, "Josei" to 43, "Isekai" to 62,
            "Harem" to 35, "Gourmet" to 47, "Boys Love" to 28,
            "Girls Love" to 26, "Martial Arts" to 17, "Parody" to 20,
            "Super Power" to 31, "Military" to 38, "Historical" to 13,
            "Space" to 29, "Vampire" to 32, "School" to 23,
            "Kids" to 15, "Samurai" to 21,
        )
        for (g in genre) {
            val genreId = jikanGenreMap[g]
            if (genreId != null) {
                val res = tryWithSuspend {
                    MAL.jikan.search(
                        query = "",
                        endpoint = "anime",
                        page = 1,
                        limit = 1,
                        orderBy = "members",
                        sort = "desc",
                        genres = genreId.toString(),
                    )
                }
                val imageUrl = res?.data?.firstOrNull()?.let { media ->
                    media.images?.jpg?.largeImageUrl ?: media.images?.jpg?.imageUrl
                } ?: ""
                genres!![g] = imageUrl
                listener.invoke(g to imageUrl)
            } else {
                genres!![g] = ""
                listener.invoke(g to "")
            }
            if (genres!!.size == genre.size) {
                done = true
                doneListener?.invoke()
            }
        }
    }
}

class ProfileViewModel : ViewModel() {

    private val mangaFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaFav(): LiveData<ArrayList<Media>> = mangaFav

    private val animeFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimeFav(): LiveData<ArrayList<Media>> = animeFav

    suspend fun setData(id: Int) {
        val res = Anilist.query.initProfilePage(id)
        val mangaList = res?.data?.favoriteManga?.favourites?.manga?.edges?.mapNotNull {
            it.node?.let { i -> Media(i) }
        }
        mangaFav.postValue(ArrayList(mangaList ?: arrayListOf()))
        val animeList = res?.data?.favoriteAnime?.favourites?.anime?.edges?.mapNotNull {
            it.node?.let { i -> Media(i) }
        }
        animeFav.postValue(ArrayList(animeList ?: arrayListOf()))

    }

    fun refresh() {
        mangaFav.postValue(mangaFav.value)
        animeFav.postValue(animeFav.value)

    }
}
