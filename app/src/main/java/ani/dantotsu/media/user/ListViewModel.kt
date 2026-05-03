package ani.dantotsu.media.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.tryWithSuspend

class ListViewModel : ViewModel() {
    var grid = MutableLiveData(PrefManager.getVal<Boolean>(PrefName.ListGrid))

    private val lists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    private val unfilteredLists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    fun getLists(): LiveData<MutableMap<String, ArrayList<Media>>> = lists
    suspend fun loadLists(anime: Boolean, userId: Int, sortOrder: String? = null) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        if (rescueMode) {
            loadListsFromMAL(anime)
            return
        }
        tryWithSuspend {
            val res = Anilist.query.getMediaLists(anime, userId, sortOrder)
            lists.postValue(res)
            unfilteredLists.postValue(res)
        }
    }
    private suspend fun loadListsFromMAL(anime: Boolean) {
        tryWithSuspend {
            val statuses = if (anime)
                listOf("watching" to "Watching", "completed" to "Completed", "plan_to_watch" to "Planned",
                    "on_hold" to "Paused", "dropped" to "Dropped")
            else
                listOf("reading" to "Reading", "completed" to "Completed", "plan_to_read" to "Planned",
                    "on_hold" to "Paused", "dropped" to "Dropped")

            val result = mutableMapOf<String, ArrayList<Media>>()
            for ((malStatus, label) in statuses) {
                var offset = 0
                val limit = 1000
                val mediaList = ArrayList<Media>()
                var hasNext = true
                while (hasNext) {
                    val response = if (anime)
                        MAL.query.getUserAnimeList(status = malStatus, limit = limit, offset = offset)
                    else
                        MAL.query.getUserMangaList(status = malStatus, limit = limit, offset = offset)

                    response?.data?.let { entries ->
                        mediaList.addAll(entries.map { Media(it, anime) })
                    }
                    if (response?.paging?.next != null) {
                        offset += limit
                    } else {
                        hasNext = false
                    }
                }
                if (mediaList.isNotEmpty()) {
                    result[label] = mediaList
                }
            }
            lists.postValue(result)
            unfilteredLists.postValue(result)
        }
    }

    fun filterLists(genre: String) {
        if (genre == "All") {
            lists.postValue(unfilteredLists.value)
            return
        }
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                genre in media.genres
            } as ArrayList<Media>
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    fun searchLists(search: String) {
        if (search.isEmpty()) {
            lists.postValue(unfilteredLists.value)
            return
        }
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                media.name?.contains(
                    search,
                    ignoreCase = true
                ) == true || media.synonyms.any { it.contains(search, ignoreCase = true) } ||
                        media.nameRomaji.contains(
                            search,
                            ignoreCase = true
                        )
            } as ArrayList<Media>
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    fun unfilterLists() {
        lists.postValue(unfilteredLists.value)
    }

}