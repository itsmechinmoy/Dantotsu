package ani.dantotsu.util
object AnilistLinkParser {
    data class AnilistLink(
        val id: Int,
        val type: MediaType,
        val url: String,
        val startIndex: Int,
        val endIndex: Int
    )
    enum class MediaType {
        ANIME,
        MANGA
    }
    private val ANIME_URL_PATTERN = Regex("""https://anilist\.co/anime/(\d+)/?[^\s]*""")
    private val MANGA_URL_PATTERN = Regex("""https://anilist\.co/manga/(\d+)/?[^\s]*""")
    fun extractAnilistLinks(text: String): List<AnilistLink> {
        val links = mutableListOf<AnilistLink>()

        ANIME_URL_PATTERN.findAll(text).forEach { matchResult ->
            val id = matchResult.groupValues[1].toIntOrNull()
            if (id != null) {
                links.add(
                    AnilistLink(
                        id = id,
                        type = MediaType.ANIME,
                        url = matchResult.value,
                        startIndex = matchResult.range.first,
                        endIndex = matchResult.range.last + 1
                    )
                )
            }
        }

        MANGA_URL_PATTERN.findAll(text).forEach { matchResult ->
            val id = matchResult.groupValues[1].toIntOrNull()
            if (id != null) {
                links.add(
                    AnilistLink(
                        id = id,
                        type = MediaType.MANGA,
                        url = matchResult.value,
                        startIndex = matchResult.range.first,
                        endIndex = matchResult.range.last + 1
                    )
                )
            }
        }

        return links.sortedBy { it.startIndex }
    }
    fun removeAnilistUrlsFromHtml(html: String): String {
        var result = html

        // Pattern 1: Remove <a href="...">URL</a> where the URL is an AniList link
        // This handles markdown-style links like [text](url) that got converted to HTML
        val linkTagPattern = Regex("""<a\s+href=["'](https://anilist\.co/(?:anime|manga)/\d+[^"']*)["'][^>]*>\s*\1\s*</a>""")
        result = linkTagPattern.replace(result, "")

        // Pattern 2: Remove <a href="...">custom text</a> where href is an AniList link
        // This preserves the link text but removes the anchor tag
        val linkTagWithTextPattern = Regex("""<a\s+href=["']https://anilist\.co/(?:anime|manga)/\d+[^"']*["'][^>]*>(.*?)</a>""")
        result = linkTagWithTextPattern.replace(result) { matchResult ->
            matchResult.groupValues[1] // Keep the link text, remove the anchor
        }

        // Pattern 3: Remove bare AniList URLs (not in anchor tags)
        result = ANIME_URL_PATTERN.replace(result, "")
        result = MANGA_URL_PATTERN.replace(result, "")

        // Clean up extra whitespace
        // Multiple spaces → single space
        result = result.replace(Regex("""\s{2,}"""), " ")
        
        // Clean up space before punctuation
        result = result.replace(Regex("""\s+([.,!?;:])"""), "$1")
        
        // Clean up multiple <br> tags in a row (keep max 2)
        result = result.replace(Regex("""(<br>\s*){3,}"""), "<br><br>")
        
        // Trim leading/trailing whitespace
        result = result.trim()

        return result
    }
}
