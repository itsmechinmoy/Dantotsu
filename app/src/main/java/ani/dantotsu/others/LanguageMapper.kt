package ani.dantotsu.others

import java.util.Locale

class LanguageMapper {
    companion object {

        private val codeMap: Map<String, String> = mapOf(
            "all" to "Multi",
            "af" to "Afrikaans",
            "am" to "Amharic",
            "ar" to "Arabic",
            "as" to "Assamese",
            "az" to "Azerbaijani",
            "be" to "Belarusian",
            "bg" to "Bulgarian",
            "bn" to "Bengali",
            "bs" to "Bosnian",
            "ca" to "Catalan",
            "ceb" to "Cebuano",
            "cs" to "Czech",
            "da" to "Danish",
            "de" to "German",
            "el" to "Greek",
            "en" to "English",
            "en-Us" to "English (United States)",
            "eo" to "Esperanto",
            "es" to "Spanish",
            "es-419" to "Spanish (Latin America)",
            "es-ES" to "Spanish (Spain)",
            "et" to "Estonian",
            "eu" to "Basque",
            "fa" to "Persian",
            "fi" to "Finnish",
            "fil" to "Filipino",
            "fo" to "Faroese",
            "fr" to "French",
            "ga" to "Irish",
            "gn" to "Guarani",
            "gu" to "Gujarati",
            "ha" to "Hausa",
            "he" to "Hebrew",
            "hi" to "Hindi",
            "hr" to "Croatian",
            "ht" to "Haitian Creole",
            "hu" to "Hungarian",
            "hy" to "Armenian",
            "id" to "Indonesian",
            "ig" to "Igbo",
            "is" to "Icelandic",
            "it" to "Italian",
            "ja" to "Japanese",
            "jv" to "Javanese",
            "ka" to "Georgian",
            "kk" to "Kazakh",
            "km" to "Khmer",
            "kn" to "Kannada",
            "ko" to "Korean",
            "ku" to "Kurdish",
            "ky" to "Kyrgyz",
            "la" to "Latin",
            "lb" to "Luxembourgish",
            "lo" to "Lao",
            "lt" to "Lithuanian",
            "lv" to "Latvian",
            "mg" to "Malagasy",
            "mi" to "Maori",
            "mk" to "Macedonian",
            "ml" to "Malayalam",
            "mn" to "Mongolian",
            "mo" to "Moldovan",
            "mr" to "Marathi",
            "ms" to "Malay",
            "mt" to "Maltese",
            "my" to "Burmese",
            "ne" to "Nepali",
            "nl" to "Dutch",
            "no" to "Norwegian",
            "ny" to "Chichewa",
            "pl" to "Polish",
            "pt" to "Portuguese",
            "pt-BR" to "Portuguese (Brazil)",
            "pt-PT" to "Portuguese (Portugal)",
            "ps" to "Pashto",
            "ro" to "Romanian",
            "rm" to "Romansh",
            "ru" to "Russian",
            "sd" to "Sindhi",
            "sh" to "Serbo-Croatian",
            "si" to "Sinhala",
            "sk" to "Slovak",
            "sl" to "Slovenian",
            "sm" to "Samoan",
            "sn" to "Shona",
            "so" to "Somali",
            "sq" to "Albanian",
            "sr" to "Serbian",
            "st" to "Southern Sotho",
            "sv" to "Swedish",
            "sw" to "Swahili",
            "ta" to "Tamil",
            "te" to "Telugu",
            "tg" to "Tajik",
            "th" to "Thai",
            "ti" to "Tigrinya",
            "tk" to "Turkmen",
            "tl" to "Tagalog",
            "to" to "Tongan",
            "tr" to "Turkish",
            "uk" to "Ukrainian",
            "ur" to "Urdu",
            "uz" to "Uzbek",
            "vi" to "Vietnamese",
            "yo" to "Yoruba",
            "zh" to "Chinese",
            "zh-Hans" to "Chinese (Simplified)",
            "zh-Hant" to "Chinese (Traditional)",
            "zh-Habt" to "Chinese (Hakka)",
            "zu" to "Zulu"
        )

        private val nativeToCodeMap: Map<String, String> = mapOf(
            "english" to "en",
            "français" to "fr",
            "french" to "fr",
            "español" to "es",
            "spanish" to "es",
            "bahasa indonesia" to "id",
            "indonesian" to "id",
            "日本語" to "ja",
            "japanese" to "ja",
            "조선말, 한국어" to "ko",
            "한국어" to "ko",
            "korean" to "ko",
            "polski" to "pl",
            "polish" to "pl",
            "português" to "pt",
            "portuguese" to "pt",
            "português (brasil)" to "pt-BR",
            "portuguese (brazil)" to "pt-BR",
            "русский" to "ru",
            "russian" to "ru",
            "ไทย" to "th",
            "thai" to "th",
            "türkçe" to "tr",
            "turkish" to "tr",
            "українська" to "uk",
            "ukrainian" to "uk",
            "tiếng việt" to "vi",
            "vietnamese" to "vi",
            "中文, 汉语, 漢語" to "zh",
            "中文" to "zh",
            "chinese" to "zh",
            "chinese (simplified)" to "zh-Hans",
            "chinese (traditional)" to "zh-Hant",
            "العربية" to "ar",
            "arabic" to "ar",
            "german" to "de",
            "deutsch" to "de",
            "italian" to "it",
            "italiano" to "it",
            "multi" to "all",
            "all" to "all"
        )

        private fun cleanLangString(raw: String): String {
            return raw.replace("[\u200B-\u200F\u202A-\u202E\uFEFF]".toRegex(), "")
                .trim()
                .lowercase()
        }

        fun getLanguageName(codeOrName: String): String {
            val clean = cleanLangString(codeOrName)
            if (clean.isEmpty() || clean == "all" || clean == "multi") {
                return codeMap["all"] ?: "Multi"
            }
            if (codeMap.containsKey(clean)) {
                return codeMap[clean] ?: codeOrName.trim()
            }
            val mappedCode = nativeToCodeMap[clean]
            if (mappedCode != null && codeMap.containsKey(mappedCode)) {
                return codeMap[mappedCode] ?: codeOrName.trim()
            }
            val trimmed = codeOrName.trim()
            return try {
                val locale = Locale.forLanguageTag(trimmed)
                val name = locale.displayName
                if (name.isNotBlank() && name != trimmed) name else trimmed
            } catch (ignored: Exception) {
                trimmed
            }
        }

        fun getLanguageCode(language: String): String {
            val clean = cleanLangString(language)
            if (clean.isEmpty() || clean == "all" || clean == "multi") return "all"
            if (codeMap.containsKey(clean)) return clean
            nativeToCodeMap[clean]?.let { return it }
            for ((k, v) in codeMap) {
                if (v.lowercase() == clean || clean.contains(v.lowercase())) return k
            }
            for ((k, v) in nativeToCodeMap) {
                if (clean.contains(k)) return v
            }
            return "all"
        }

        enum class Language(val code: String) {
            ALL("all"),
            ARABIC("ar"),
            GERMAN("de"),
            ENGLISH("en"),
            SPANISH("es"),
            FRENCH("fr"),
            INDONESIAN("id"),
            ITALIAN("it"),
            JAPANESE("ja"),
            KOREAN("ko"),
            POLISH("pl"),
            PORTUGUESE_BRAZIL("pt-BR"),
            RUSSIAN("ru"),
            THAI("th"),
            TURKISH("tr"),
            UKRAINIAN("uk"),
            VIETNAMESE("vi"),
            CHINESE("zh"),
            CHINESE_SIMPLIFIED("zh-Hans")
        }
    }
}
