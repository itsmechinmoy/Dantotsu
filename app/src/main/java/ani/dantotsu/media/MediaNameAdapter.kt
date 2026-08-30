package ani.dantotsu.media

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

object MediaNameAdapter {

    private const val REGEX_ITEM = "[\\s:.\\-]*(\\d+\\.?\\d*)[\\s:.\\-]*"
    private const val REGEX_PART_NUMBER = "(?<!part\\s)\\b(\\d+)\\b"
    private const val REGEX_EPISODE =
        "(episode|episodio|ep|e)${REGEX_ITEM}\\(?\\s*(sub|subbed|dub|dubbed)*\\s*\\)?\\s*"
    private const val REGEX_SEASON = "(season|s)[\\s:.\\-]*(\\d+)[\\s:.\\-]*"
    private const val REGEX_SUBDUB = "^(soft)?[\\s-]*(sub|dub|mixed)(bed|s)?\\s*$"
    private const val REGEX_CHAPTER = "(chapter|chap|ch|c)${REGEX_ITEM}"

    private val SUBDUB_PATTERN = Pattern.compile(REGEX_SUBDUB, Pattern.CASE_INSENSITIVE)
    private val SEASON_PATTERN = Pattern.compile(REGEX_SEASON, Pattern.CASE_INSENSITIVE)
    private val CHAPTER_PATTERN = Pattern.compile(REGEX_CHAPTER, Pattern.CASE_INSENSITIVE)
    private val PART_NUMBER_PATTERN = Pattern.compile(REGEX_PART_NUMBER, Pattern.CASE_INSENSITIVE)
    private val EPISODE_REGEX = Regex(REGEX_EPISODE, RegexOption.IGNORE_CASE)
    private val PART_NUMBER_REGEX = Regex(REGEX_PART_NUMBER, RegexOption.IGNORE_CASE)
    private val LETTER_REGEX = Regex("[a-zA-Z]")

    fun setSubDub(text: String, typeToSetTo: SubDubType): String? {
        val subdubMatcher: Matcher = SUBDUB_PATTERN.matcher(text)

        return if (subdubMatcher.find()) {
            val soft = subdubMatcher.group(1)
            val subdub = subdubMatcher.group(2)
            val bed = subdubMatcher.group(3) ?: ""

            val toggled = when (typeToSetTo) {
                SubDubType.SUB -> "sub"
                SubDubType.DUB -> "dub"
                SubDubType.NULL -> ""
            }
            val toggledCasePreserved =
                if (subdub?.get(0)?.isUpperCase() == true || soft?.get(0)
                        ?.isUpperCase() == true
                ) toggled.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(
                        Locale.ROOT
                    ) else it.toString()
                } else toggled

            subdubMatcher.replaceFirst(toggledCasePreserved + bed)
        } else {
            null
        }
    }

    fun getSubDub(text: String): SubDubType {
        val subdubMatcher: Matcher = SUBDUB_PATTERN.matcher(text)

        return if (subdubMatcher.find()) {
            val subdub = subdubMatcher.group(2)?.lowercase(Locale.ROOT)
            when (subdub) {
                "sub" -> SubDubType.SUB
                "dub" -> SubDubType.DUB
                else -> SubDubType.NULL
            }
        } else {
            SubDubType.NULL
        }
    }

    enum class SubDubType {
        SUB, DUB, NULL
    }

    fun findSeasonNumber(text: String): Int? {
        val seasonMatcher: Matcher = SEASON_PATTERN.matcher(text)

        return if (seasonMatcher.find()) {
            seasonMatcher.group(2)?.toInt()
        } else {
            text.toIntOrNull()
        }
    }

    private val TAG_REGEX = Regex("""^\[[^\]]+\]|\[[^\]]+\]\s*${'$'}|^\([^\)]+\)|\([^\)]+\)\s*${'$'}""")
    private val UNWANTED_TAGS = Regex("""(?i)\b(?:sub|subbed|dub|dubbed|raw|softsub|hardsub|multi|dual|audio|v\d+|ver\d+|version\d+|season\s*\d+|s\d+|\d+p|hi10|hevc|x264|x265|aac)\b""")
    private val BASIC_EP_REGEX = Regex("""(?i)(?<=\be\.|\be|episode|\bep)[\s:.\-]*([0-9]+(?:\.[0-9]+)?)""")
    private val NUMBER_REGEX = Regex("""\b([0-9]+(?:\.[0-9]+)?)\b""")

    fun findEpisodeNumber(text: String): Float? {
        if (text.isBlank()) return null

        text.trim().toFloatOrNull()?.let { return it }

        val basicMatch = BASIC_EP_REGEX.find(text)
        if (basicMatch != null) {
            val numStr = basicMatch.groupValues[1]
            numStr.toFloatOrNull()?.let { return it }
        }

        var clean = text
        while (TAG_REGEX.containsMatchIn(clean)) {
            clean = TAG_REGEX.replace(clean, "")
        }
        clean = UNWANTED_TAGS.replace(clean, " ")
            .replace(',', '.')
            .replace('-', ' ')
            .trim()

        val cleanedBasic = BASIC_EP_REGEX.find(clean)
        if (cleanedBasic != null) {
            val numStr = cleanedBasic.groupValues[1]
            numStr.toFloatOrNull()?.let { return it }
        }

        val matches = NUMBER_REGEX.findAll(clean).toList()
        if (matches.isNotEmpty()) {
            for (match in matches) {
                val num = match.groupValues[1].toFloatOrNull()
                if (num != null) return num
            }
        }

        return text.toFloatOrNull()
    }

    fun removeEpisodeNumber(text: String): String {
        val removedNumber = text.replace(EPISODE_REGEX, "").ifEmpty {
            text
        }
        return if (LETTER_REGEX.containsMatchIn(removedNumber)) {
            removedNumber
        } else {
            text
        }
    }


    fun removeEpisodeNumberCompletely(text: String): String {
        val removedNumber = text.replace(EPISODE_REGEX, "")
        return if (removedNumber.equals(text, true)) {  // if nothing was removed
            PART_NUMBER_REGEX.replace(removedNumber) { mr ->
                mr.value.replaceFirst(mr.groupValues[1], "")
            }
        } else {
            removedNumber
        }
    }

    fun findChapterNumber(text: String): Float? {
        val matcher: Matcher = CHAPTER_PATTERN.matcher(text)

        return if (matcher.find()) {
            matcher.group(2)?.toFloat()
        } else {
            val failedChapterNumberMatcher: Matcher =
                PART_NUMBER_PATTERN.matcher(text)
            if (failedChapterNumberMatcher.find()) {
                failedChapterNumberMatcher.group(1)?.toFloat()
            } else {
                text.toFloatOrNull()
            }
        }
    }
}
