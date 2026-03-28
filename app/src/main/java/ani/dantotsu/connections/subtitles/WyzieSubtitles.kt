package ani.dantotsu.connections.subtitles

import ani.dantotsu.Mapper
import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object WyzieSubtitles {

    private const val BASE_URL = "https://sub.wyzie.ru/search"


    suspend fun getWyzieSubtitles(imdbId: String, season: Int, episode: Int): List<WyzieSub> {
        return withContext(Dispatchers.IO) {
            try {
                // Get languages from Prefs
                val languages = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages).joinToString(",")

                val url = "$BASE_URL?id=$imdbId&season=$season&episode=$episode&language=$languages"
                Logger.log("WyzieSubtitles: Fetching from $url")

                val response = client.get(url)
                val text = response.text

                // Logger.log("WyzieSubtitles: Response: ${text.take(100)}...")

                // Basic check for valid JSON array start
                if (text.trim().startsWith("<") || !text.trim().startsWith("[")) {
                     Logger.log("WyzieSubtitles: Invalid response (likely 404/Error Page)")
                     return@withContext emptyList()
                }

                val data = Mapper.json.decodeFromString<List<WyzieSub>>(text)
                Logger.log("WyzieSubtitles: Decoded ${data.size} subs")

                // Filter & Sort Logic
                // 1. Language: Favor English ("en", "eng", "English") or current locale?
                //    For now, let's keep all and let the UI filter, or filter to English + User Pref.
                //    Prompt says: "Filter by Language: Only show 'English' or the user's preference."
                //    Since I don't easily have the user's preference *code* handy here (it's often UI specific),
                //    I will return ALL, but sorted. The UI adapter already groups/handles display?
                //    Actually `SubtitleDialogFragment` displays everything returned.
                //    So I should filter here to avoid spam.
                //    Lets filter for "English" for now as a safe default,
                //    and maybe Spanish/etc if we can detect locale.
                //    We can check `Locale.getDefault().language`.

                val userLang = java.util.Locale.getDefault().language // e.g., "en", "es"

                // We requested specific languages in the URL, so trust the API results.
                // Return ALL results (sorted), so the user sees everything.
                data.sortedWith(compareByDescending<WyzieSub> {
                    // Quality preference: ASS > SRT (field is 'format')
                    it.format.lowercase() == "ass"
                }.thenBy {
                    // Secondary sort: maybe name/label length
                    it.displayLabel
                })

            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}

@Serializable
data class WyzieSub(
    @SerialName("id") val id: String,
    @SerialName("url") val url: String,
    @SerialName("display") val display: String?,
    @SerialName("language") val language: String,
    @SerialName("format") val format: String
) {
    val displayLabel: String
        get() = display ?: language
}
