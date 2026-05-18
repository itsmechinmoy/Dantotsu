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
import ani.dantotsu.media.Media
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.profile.User
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.connections.syncPendingProgressUpdates
import ani.dantotsu.connections.syncPendingDeletions
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
            setListImages()
            return
        }
        val res = Anilist.query.initHomePage()
        res.media["currentAnime"]?.let { animeContinue.postValue(it) }
        res.media["favoriteAnime"]?.let { animeFav.postValue(it) }
        res.media["currentAnimePlanned"]?.let { animePlanned.postValue(it) }
        res.media["currentManga"]?.let { mangaContinue.postValue(it) }
        res.media["favoriteManga"]?.let { mangaFav.postValue(it) }
        res.media["currentMangaPlanned"]?.let { mangaPlanned.postValue(it) }
        res.media["recommendations"]?.let { recommendation.postValue(it) }
        res.media["missingSequels"]?.let { missingSequels.postValue(it) }
        res.media["hidden"]?.let { hidden.postValue(it) }
        res.userStatus?.let { userStatus.postValue(it) }
        res.bannerImages?.let { listImages.postValue(it) }
    }

    private suspend fun initHomePageFromMAL() {
        animeFav.postValue(arrayListOf())
        mangaFav.postValue(arrayListOf())
        missingSequels.postValue(arrayListOf())
        hidden.postValue(arrayListOf())

        tryWithSuspend {
            val res = MAL.jikan.getSeasonUpcoming(limit = 15)
            recommendation.postValue(ArrayList(res?.data?.map { Media(it, true) } ?: emptyList()))
        }

        if (MAL.token == null) return

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

    suspend fun initAnimePage(seasonIndex: Int, popularOnList: Boolean) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadTrending(seasonIndex)
            loadAllFromMAL()
            loadPopular("ANIME", sort = Anilist.sortBy[1], onList = popularOnList)
            return
        }
        val res = Anilist.query.fetchAnimePageData(seasonIndex, popularOnList)
        trending.postValue(res.trending)
        updated.postValue(res.media["recentUpdates"])
        popularMovies.postValue(res.media["trendingMovies"])
        topRatedAnime.postValue(res.media["topRated"])
        mostFavAnime.postValue(res.media["mostFav"])
        animePopular.postValue(res.popular)
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

    suspend fun initMangaPage(popularOnList: Boolean) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadTrending()
            loadAllFromMAL()
            loadPopular("MANGA", sort = Anilist.sortBy[1], onList = popularOnList)
            return
        }
        val res = Anilist.query.fetchMangaPageData(popularOnList)
        trending.postValue(res.trending)
        popularManga.postValue(res.media["trendingManga"])
        popularManhwa.postValue(res.media["trendingManhwa"])
        popularNovel.postValue(res.media["trendingNovel"])
        topRatedManga.postValue(res.media["topRated"])
        mostFavManga.postValue(res.media["mostFav"])
        mangaPopular.postValue(res.popular)
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
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> loadAniMangaSearch(aniMangaSearchResults)
            SearchType.CHARACTER, SearchType.STUDIO, SearchType.STAFF, SearchType.USER -> {
                if (!rescueMode) {
                    when (type) {
                        SearchType.CHARACTER -> loadCharacterSearch(characterSearchResults)
                        SearchType.STUDIO -> loadStudiosSearch(studioSearchResults)
                        SearchType.STAFF -> loadStaffSearch(staffSearchResults)
                        SearchType.USER -> loadUserSearch(userSearchResults)
                        else -> {}
                    }
                }
            }
        }
    }

    suspend fun loadNextPage(type: SearchType) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> loadNextAniMangaPage(aniMangaSearchResults)
            SearchType.CHARACTER, SearchType.STUDIO, SearchType.STAFF, SearchType.USER -> {
                if (!rescueMode) {
                    when (type) {
                        SearchType.CHARACTER -> loadNextCharacterPage(characterSearchResults)
                        SearchType.STUDIO -> loadNextStudiosPage(studioSearchResults)
                        SearchType.STAFF -> loadNextStaffPage(staffSearchResults)
                        SearchType.USER -> loadNextUserPage(userSearchResults)
                        else -> {}
                    }
                }
            }
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

    private suspend fun loadCharacterSearch(r: CharacterSearchResults) = characterResult.postValue(
        Anilist.query.searchCharacters(
            r.page,
            r.search,
        )
    )

    private suspend fun loadStudiosSearch(r: StudioSearchResults) = studioResult.postValue(
        Anilist.query.searchStudios(
            r.page,
            r.search,
        )
    )

    private suspend fun loadStaffSearch(r: StaffSearchResults) = staffResult.postValue(
        Anilist.query.searchStaff(
            r.page,
            r.search,
        )
    )

    private suspend fun loadUserSearch(r: UserSearchResults) = userResult.postValue(
        Anilist.query.searchUsers(
            r.page,
            r.search,
        )
    )

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

    private suspend fun loadNextCharacterPage(r: CharacterSearchResults) =
        characterResult.postValue(
            Anilist.query.searchCharacters(
                r.page + 1,
                r.search,
            )
        )

    private suspend fun loadNextStudiosPage(r: StudioSearchResults) = studioResult.postValue(
        Anilist.query.searchStudios(
            r.page + 1,
            r.search,
        )
    )

    private suspend fun loadNextStaffPage(r: StaffSearchResults) = staffResult.postValue(
        Anilist.query.searchStaff(
            r.page + 1,
            r.search,
        )
    )

    private suspend fun loadNextUserPage(r: UserSearchResults) = userResult.postValue(
        Anilist.query.searchUsers(
            r.page + 1,
            r.search,
        )
    )
}

class GenresViewModel : ViewModel() {
    var genres: MutableMap<String, String>? = null
    var done = false
    var doneListener: (() -> Unit)? = null
    suspend fun loadGenres(genre: ArrayList<String>, listener: (Pair<String, String>) -> Unit) {
        if (genres == null) {
            genres = mutableMapOf()
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
