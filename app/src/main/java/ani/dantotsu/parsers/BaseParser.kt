package ani.dantotsu.parsers

import android.graphics.drawable.Drawable
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import me.xdrop.fuzzywuzzy.FuzzySearch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.system.measureTimeMillis


abstract class BaseParser {

    /**
     * Name that will be shown in Source Selection
     * **/
    open val name: String = ""

    /**
     * Name used to save the ShowResponse selected by user or by autoSearch
     * **/
    open val saveName: String = ""

    /**
     * The main URL of the Site
     * **/
    open val hostUrl: String = ""

    /**
     * override as `true` if the site **only** has NSFW media
     * **/
    open val isNSFW = false

    /**
     * mostly redundant for official app, But override if you want to add different languages
     * **/
    open val language = "English"

    /**
     * Icon of the site, can be null
     */
    open val icon: Drawable? = null

    /**
     * Icon URL of the site, used if local icon is null
     */
    open val iconUrl: String? = null

    /**
     *  Search for Anime/Manga/Novel, returns a List of Responses
     *
     *  use `encode(query)` to encode the query for making requests
     * **/
    abstract suspend fun search(query: String): List<ShowResponse>

    /**
     * The function app uses to auto find the anime/manga using Media data provided by anilist
     *
     * Isn't necessary to override, but recommended, if you want to improve auto search results
     * **/
    open suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        (this as? DynamicMangaParser)?.let { ext ->
            mediaObj.selected?.langIndex?.let {
                ext.sourceLanguage = it
            }
        }
        var response: ShowResponse? = loadSavedShowResponse(mediaObj.id)
        if (response != null && this !is OfflineMangaParser && this !is OfflineAnimeParser) {
            saveShowResponse(mediaObj.id, response, true)
        } else {
            setUserText("Searching : ${mediaObj.mainName()}")
            Logger.log("Searching : ${mediaObj.mainName()}")
            val results = search(mediaObj.mainName())
            //log all results
            results.forEach {
                Logger.log("Result: ${it.name}")
            }
            val sortedResults = if (results.isNotEmpty()) {
                results.sortedByDescending {
                    FuzzySearch.ratio(
                        it.name.lowercase(),
                        mediaObj.mainName().lowercase()
                    )
                }
            } else {
                emptyList()
            }
            response = sortedResults.firstOrNull()

            if (response == null || FuzzySearch.ratio(
                    response.name.lowercase(),
                    mediaObj.mainName().lowercase()
                ) < 100
            ) {
                setUserText("Searching : ${mediaObj.nameRomaji}")
                Logger.log("Searching : ${mediaObj.nameRomaji}")
                val romajiResults = search(mediaObj.nameRomaji)
                val sortedRomajiResults = if (romajiResults.isNotEmpty()) {
                    romajiResults.sortedByDescending {
                        FuzzySearch.ratio(
                            it.name.lowercase(),
                            mediaObj.nameRomaji.lowercase()
                        )
                    }
                } else {
                    emptyList()
                }
                val closestRomaji = sortedRomajiResults.firstOrNull()
                Logger.log("Closest match from RomajiResults: ${closestRomaji?.name ?: "None"}")

                response = if (response == null) {
                    Logger.log("No exact match found in results. Using closest match from RomajiResults.")
                    closestRomaji
                } else {
                    val romajiRatio = FuzzySearch.ratio(
                        closestRomaji?.name?.lowercase() ?: "",
                        mediaObj.nameRomaji.lowercase()
                    )
                    val mainNameRatio = FuzzySearch.ratio(
                        response.name.lowercase(),
                        mediaObj.mainName().lowercase()
                    )
                    Logger.log("Fuzzy ratio for closest match in results: $mainNameRatio for ${response.name.lowercase()}")
                    Logger.log("Fuzzy ratio for closest match in RomajiResults: $romajiRatio for ${closestRomaji?.name?.lowercase() ?: "None"}")

                    if (romajiRatio > mainNameRatio) {
                        Logger.log("RomajiResults has a closer match. Replacing response.")
                        closestRomaji
                    } else {
                        Logger.log("Results has a closer or equal match. Keeping existing response.")
                        response
                    }
                }
            }

            if (response == null || FuzzySearch.ratio(
                    response.name.lowercase(),
                    mediaObj.mainName().lowercase()
                ) < 80
            ) {
                for (synonym in mediaObj.synonyms) {
                    if (synonym.isBlank() || synonym == mediaObj.mainName() || synonym == mediaObj.nameRomaji) continue
                    setUserText("Searching : $synonym")
                    Logger.log("Searching : $synonym")
                    val synonymResults = try {
                        search(synonym)
                    } catch (e: Exception) {
                        Logger.log("Synonym search failed for $synonym: $e")
                        emptyList()
                    }
                    val sortedSynonymResults = if (synonymResults.isNotEmpty()) {
                        synonymResults.sortedByDescending {
                            FuzzySearch.ratio(
                                it.name.lowercase(),
                                synonym.lowercase()
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val closestSynonym = sortedSynonymResults.firstOrNull()
                    if (closestSynonym != null) {
                        val synonymRatio = FuzzySearch.ratio(
                            closestSynonym.name.lowercase(),
                            synonym.lowercase()
                        )
                        if (synonymRatio >= 80) {
                            val currentRatio = if (response != null) FuzzySearch.ratio(
                                response.name.lowercase(),
                                mediaObj.mainName().lowercase()
                            ) else 0
                            if (synonymRatio > currentRatio) {
                                Logger.log("Synonym search found a better match: ${closestSynonym.name}")
                                response = closestSynonym
                                if (synonymRatio >= 95) {
                                    break
                                }
                            }
                        }
                    }
                }
            }
            saveShowResponse(mediaObj.id, response)
        }
        return response
    }

    /**
     * ping the site to check if it's working or not.
     * @return Triple<Int, Int?, String> : First Int is the status code, Second Int is the response time in milliseconds, Third String is the response message.
     */
    fun ping(): Triple<Int, Int?, String> {
        val client = OkHttpClient()
        var statusCode = 0
        var responseTime: Int? = null
        var responseMessage = ""
        println("Pinging $name at $hostUrl")
        try {
            val request = Request.Builder()
                .url(hostUrl)
                .build()
            responseTime = measureTimeMillis {
                client.newCall(request).execute().use { response ->
                    statusCode = response.code
                    responseMessage = response.message.ifEmpty { "None" }
                }
            }.toInt()
        } catch (e: Exception) {
            Logger.log("Failed to ping $name")
            statusCode = -1
            responseMessage = if (e.message.isNullOrEmpty()) "None" else e.message!!
            Logger.log(e)
        }
        return Triple(statusCode, responseTime, responseMessage)
    }

    /**
     * Used to get an existing Search Response which was selected by the user.
     * @param mediaId : The mediaId of the Media object.
     * @return ShowResponse? : The ShowResponse object if found, else null.
     */
    open suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        checkIfVariablesAreEmpty()
        return PrefManager.getNullableCustomVal(
            "${saveName}_$mediaId",
            null,
            ShowResponse::class.java
        )
    }

    /**
     * Used to save Shows Response using `saveName`.
     * @param mediaId : The mediaId of the Media object.
     * @param response : The ShowResponse object to save.
     * @param selected : Boolean : If the ShowResponse was selected by the user or not.
     */
    open fun saveShowResponse(mediaId: Int, response: ShowResponse?, selected: Boolean = false) {
        if (response != null) {
            checkIfVariablesAreEmpty()
            setUserText(
                "${
                    if (selected) currContext()!!.getString(R.string.selected) else currContext()!!.getString(
                        R.string.found
                    )
                } : ${response.name}"
            )
            PrefManager.setCustomVal("${saveName}_$mediaId", response)
        }
    }

    fun checkIfVariablesAreEmpty() {
        if (hostUrl.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
        if (name.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
        if (saveName.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
    }

    open var showUserText = ""
    open var showUserTextListener: ((String) -> Unit)? = null

    /**
     * Used to show messages & errors to the User, a useful way to convey what's currently happening or what was done.
     * **/
    fun setUserText(string: String) {
        showUserText = string
        showUserTextListener?.invoke(showUserText)
    }

    fun encode(input: String): String = URLEncoder.encode(input, "utf-8").replace("+", "%20")
    fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

    val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"
}


/**
 * A single show which contains some episodes/chapters which is sent by the site using their search function.
 *
 * You might wanna include `otherNames` & `total` too, to further improve user experience.
 *
 * You can also store a Map of Strings if you want to save some extra data.
 * **/
data class ShowResponse(
    val name: String,
    val link: String,
    val coverUrl: FileUrl,

    //would be Useful for custom search, ig
    val otherNames: List<String> = listOf(),

    //Total number of Episodes/Chapters in the show.
    val total: Int? = null,

    //In case you want to sent some extra data
    val extra: MutableMap<String, String>? = null,

    //SAnime object from Aniyomi
    @Transient var sAnime: SAnime? = null,

    //SManga object from Aniyomi
    @Transient var sManga: SManga? = null
) : Serializable {
    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null,
        extra: MutableMap<String, String>? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total, extra)

    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total)

    constructor(name: String, link: String, coverUrl: String, otherNames: List<String> = listOf())
            : this(name, link, FileUrl(coverUrl), otherNames)

    constructor(name: String, link: String, coverUrl: String)
            : this(name, link, FileUrl(coverUrl))

    constructor(name: String, link: String, coverUrl: String, sAnime: SAnime)
            : this(name, link, FileUrl(coverUrl), sAnime = sAnime)

    constructor(name: String, link: String, coverUrl: String, sManga: SManga)
            : this(name, link, FileUrl(coverUrl), sManga = sManga)

    private fun writeObject(out: java.io.ObjectOutputStream) {
        out.defaultWriteObject()
        val anime = sAnime
        if (anime != null) {
            out.writeBoolean(true)
            out.writeObject(anime.url)
            out.writeObject(anime.title)
            out.writeObject(anime.artist)
            out.writeObject(anime.author)
            out.writeObject(anime.description)
            out.writeObject(anime.genre)
            out.writeInt(anime.status)
            out.writeObject(anime.thumbnail_url)
            out.writeObject(anime.background_url)
            out.writeDouble(anime.season_number)
            out.writeBoolean(anime.initialized)
        } else {
            out.writeBoolean(false)
        }
        val manga = sManga
        if (manga != null) {
            out.writeBoolean(true)
            out.writeObject(manga.url)
            out.writeObject(manga.title)
            out.writeObject(manga.artist)
            out.writeObject(manga.author)
            out.writeObject(manga.description)
            out.writeObject(manga.genre)
            out.writeInt(manga.status)
            out.writeObject(manga.thumbnail_url)
            out.writeBoolean(manga.initialized)
        } else {
            out.writeBoolean(false)
        }
    }

    private fun readObject(inStream: java.io.ObjectInputStream) {
        inStream.defaultReadObject()
        if (inStream.readBoolean()) {
            val anime = SAnime.create().apply {
                url = inStream.readObject() as String
                title = inStream.readObject() as String
                artist = inStream.readObject() as? String
                author = inStream.readObject() as? String
                description = inStream.readObject() as? String
                genre = inStream.readObject() as? String
                status = inStream.readInt()
                thumbnail_url = inStream.readObject() as? String
                background_url = inStream.readObject() as? String
                season_number = inStream.readDouble()
                initialized = inStream.readBoolean()
            }
            sAnime = anime
        }
        if (inStream.readBoolean()) {
            val manga = SManga.create().apply {
                url = inStream.readObject() as String
                title = inStream.readObject() as String
                artist = inStream.readObject() as? String
                author = inStream.readObject() as? String
                description = inStream.readObject() as? String
                genre = inStream.readObject() as? String
                status = inStream.readInt()
                thumbnail_url = inStream.readObject() as? String
                initialized = inStream.readBoolean()
            }
            sManga = manga
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
