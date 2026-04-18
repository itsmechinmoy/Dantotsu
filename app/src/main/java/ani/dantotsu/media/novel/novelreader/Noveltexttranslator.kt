package ani.dantotsu.media.novel.novelreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

object NovelTextTranslator {

    private val cache = android.util.LruCache<String, String>(500)
    
    suspend fun translate(text: String, targetLang: String): String {
        if (text.isBlank() || targetLang == "none") return text
        val key = "$targetLang:$text"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single" +
                        "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
                val response = URL(url).readText()
                val json = JSONArray(response)
                val parts = json.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONArray(i).getString(0))
                }
                val result = sb.toString()
                cache.put(key, result)
                result
            } catch (e: Exception) {
                text
            }
        }
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
