package ani.dantotsu.media.novel.novelreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NovelTextTranslator {

    private val cache = android.util.LruCache<String, String>(500)
    
    suspend fun translate(text: String, targetLang: String): String {
        if (text.isBlank() || targetLang == "none") return text
        val key = "$targetLang:$text"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val translated = translateWithGoogle(text, targetLang)
                ?: translateWithMyMemory(text, targetLang)
                ?: error("Translation unavailable (services rate-limited or offline)")
            cache.put(key, translated)
            translated
        }
    }

    private fun translateWithGoogle(text: String, targetLang: String): String? = try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&tl=$targetLang&dt=t&q=$encoded"
        val response = httpGet(url)
        if (response.contains("google.com/sorry") || !response.trimStart().startsWith("[")) null
        else {
            val firstArray = JSONArray(response).getJSONArray(0)
            val result = StringBuilder()
            for (i in 0 until firstArray.length()) {
                result.append(firstArray.getJSONArray(i).getString(0))
            }
            result.toString().takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) { null }

    private fun translateWithMyMemory(text: String, targetLang: String): String? = try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val langPair = "autodetect|$targetLang"
        val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"
        val response = httpGet(url)
        val json = JSONObject(response)
        val status = json.optInt("responseStatus", 0)
        if (status != 200) null
        else {
            json.getJSONObject("responseData")
                .getString("translatedText")
                .takeIf { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
        }
    } catch (_: Exception) { null }

    private fun httpGet(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
    
    val languages: LinkedHashMap<String, String> = linkedMapOf(
        "none"  to "Original",
        "en"    to "English",
        "ja"    to "Japanese",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
        "ko"    to "Korean",
        "hi"    to "Hindi",
        "ar"    to "Arabic",
        "as"    to "Assamese",
        "bn"    to "Bengali",
        "fr"    to "French",
        "de"    to "German",
        "es"    to "Spanish",
        "pt"    to "Portuguese",
        "pt-BR" to "Portuguese (Brazil)",
        "ru"    to "Russian",
        "it"    to "Italian",
        "nl"    to "Dutch",
        "pl"    to "Polish",
        "tr"    to "Turkish",
        "id"    to "Indonesian",
        "ms"    to "Malay",
        "th"    to "Thai",
        "vi"    to "Vietnamese",
        "ta"    to "Tamil",
        "te"    to "Telugu",
        "ml"    to "Malayalam",
        "kn"    to "Kannada",
        "mr"    to "Marathi",
        "pa"    to "Punjabi",
        "gu"    to "Gujarati",
        "ur"    to "Urdu",
        "fa"    to "Persian",
        "he"    to "Hebrew",
        "sv"    to "Swedish",
        "da"    to "Danish",
        "fi"    to "Finnish",
        "no"    to "Norwegian",
        "cs"    to "Czech",
        "sk"    to "Slovak",
        "ro"    to "Romanian",
        "hu"    to "Hungarian",
        "uk"    to "Ukrainian",
        "hr"    to "Croatian",
        "sr"    to "Serbian",
        "bg"    to "Bulgarian",
        "el"    to "Greek",
        "lt"    to "Lithuanian",
        "lv"    to "Latvian",
        "et"    to "Estonian",
        "sl"    to "Slovenian",
        "sw"    to "Swahili",
        "yo"    to "Yoruba",
        "ig"    to "Igbo",
        "ha"    to "Hausa",
        "zu"    to "Zulu",
        "af"    to "Afrikaans",
        "km"    to "Khmer",
        "lo"    to "Lao",
        "my"    to "Burmese",
        "si"    to "Sinhala",
        "ne"    to "Nepali",
        "cy"    to "Welsh",
    )

    fun clearCache() = cache.evictAll()
}
