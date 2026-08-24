package ani.dantotsu.connections.subtitles

import ani.dantotsu.Mapper
import ani.dantotsu.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger

object StremioSubtitles {

    // The free Stremio OpenSubtitles v3 endpoint
    private const val BASE_URL = "https://opensubtitles-v3.strem.io/subtitles"

    suspend fun getSubtitles(media: Media, season: Int, episode: Int): List<StremioSub> {
        // Check if Online Subtitles are enabled
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return emptyList()

        val providers = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
        val allSubs = mutableListOf<StremioSub>()

        return withContext(Dispatchers.IO) {
            // 1. Try Wyzie if enabled
            if (providers.contains("Wyzie")) {
                try {
                    val imdbId = media.idIMDB
                    if (imdbId != null) {
                        val wyzieSubs = WyzieSubtitles.getWyzieSubtitles(imdbId, season, episode)
                        Logger.log("StremioSubtitles: Wyzie returned ${wyzieSubs.size} subs")
                        if (wyzieSubs.isNotEmpty()) {
                            val mapped = wyzieSubs.map {
                                StremioSub(
                                    id = it.id,
                                    url = it.url,
                                    lang = it.displayLabel // Use display label for nicer UI
                                )
                            }
                            allSubs.addAll(mapped)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. Try OpenSubtitles (Stremio) if enabled
            if (providers.contains("Stremio")) {
                Logger.log("StremioSubtitles: Fetching OpenSubtitles for S${season}:E${episode}...")
                try {
                    val imdbId = media.idIMDB
                    if (imdbId != null) {
                        val isMovie = media.format == "MOVIE"
                        val urlsToTry = mutableListOf<String>()
                        if (isMovie) {
                            urlsToTry.add("$BASE_URL/movie/$imdbId.json")
                        } else {
                            urlsToTry.add("$BASE_URL/series/$imdbId:$season:$episode.json")
                            if (season != 1) {
                                urlsToTry.add("$BASE_URL/series/$imdbId:1:$episode.json")
                            }
                        }

                        for (url in urlsToTry) {
                            try {
                                val request = Request.Builder().url(url).build()
                                val response = okHttpClient.newCall(request).execute()
                                if (response.isSuccessful) {
                                    val text = response.body.string()
                                    val data = Mapper.json.decodeFromString<StremioResponse>(text)
                                    val existingUrls = allSubs.map { it.url }.toSet()
                                    val newSubs = data.subtitles.filter { sub ->
                                        sub.url !in existingUrls && (!sub.id.contains(":1:1") || episode == 1)
                                    }
                                    allSubs.addAll(newSubs)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            allSubs
        }
    }
}




@Serializable
data class StremioResponse(
    val subtitles: List<StremioSub> = emptyList()
)

@Serializable
data class StremioSub(
    val id: String,
    val url: String,
    val lang: String
)
