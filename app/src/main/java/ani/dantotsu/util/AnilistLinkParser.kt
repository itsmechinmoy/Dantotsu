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
    private val ANIME_URL_PATTERN = Regex("""https://anilist\.co/anime/(\d+)/?\S*""")
    private val MANGA_URL_PATTERN = Regex("""https://anilist\.co/manga/(\d+)/?\S*""")
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

    /**
     * Like [removeAnilistUrlsFromHtml] but substitutes each AniList URL with the
     * corresponding media title from [titleMap] (mediaId → title).
     * URLs whose ID is not present in [titleMap] are removed entirely.
     */
    fun replaceAnilistUrlsInHtml(html: String, titleMap: Map<Int, String>): String {
        var result = html

        // Pattern 1: <a href="AniListUrl">AniListUrl</a> — bare URL auto-linked → replace with title
        val linkTagSamePattern = Regex("""<a\s+href=["'](https://anilist\.co/(?:anime|manga)/(\d+)[^"']*)["'][^>]*>\s*\1\s*</a>""")
        result = linkTagSamePattern.replace(result) { matchResult ->
            val id = matchResult.groupValues[2].toIntOrNull()
            titleMap[id]?.let { "<u><b>$it</b></u>" } ?: ""
        }

        // Pattern 2: <a href="AniListUrl">custom text</a> — keep user's link text, remove anchor
        val linkTagWithTextPattern = Regex("""<a\s+href=["']https://anilist\.co/(?:anime|manga)/\d+[^"']*["'][^>]*>(.*?)</a>""")
        result = linkTagWithTextPattern.replace(result) { matchResult ->
            matchResult.groupValues[1]
        }

        // Pattern 3: bare AniList URLs not wrapped in an anchor
        result = ANIME_URL_PATTERN.replace(result) { matchResult ->
            val id = matchResult.groupValues[1].toIntOrNull()
            titleMap[id]?.let { "<u><b>$it</b></u>" } ?: ""
        }
        result = MANGA_URL_PATTERN.replace(result) { matchResult ->
            val id = matchResult.groupValues[1].toIntOrNull()
            titleMap[id]?.let { "<u><b>$it</b></u>" } ?: ""
        }

        // Clean up whitespace artifacts
        result = result.replace(Regex("""\s{2,}"""), " ")
        result = result.replace(Regex("""\s+([.,!?;:])"""), "$1")
        result = result.replace(Regex("""(<br>\s*){3,}"""), "<br><br>")
        result = result.trim()

        return result
    }
}
