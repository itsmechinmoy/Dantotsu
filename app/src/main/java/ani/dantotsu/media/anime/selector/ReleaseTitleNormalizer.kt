package ani.dantotsu.media.anime.selector

/**
 * Last pass over a cleaned card title. Removes what tag stripping leaves
 * behind: separators just inside brackets ("( PT-BR)" -> "(PT-BR)",
 * "(Kusuriya no Hitorigoto,)" -> "(Kusuriya no Hitorigoto)"),
 * empty groups ("", "[ - ]"), doubled spaces and dangling edge
 * separators. Runs until nothing changes, so it is idempotent.
 * Dots are not trimmed at the edges (".hack", "Subtitles." are title text).
 */
object ReleaseTitleNormalizer {
    private val INNER_LEAD = Regex("""([\[({])[\s_|,\-]+""")
    private val INNER_TRAIL = Regex("""[\s_|,\-]+([\])}])""")
    private val EMPTY_GROUP = Regex("""\[[\s._|,\-]*\]|\([\s._|,\-]*\)|\{[\s._|,\-]*\}""")
    private val WS_RUN = Regex("""\s+""")
    private val EDGE = Regex("""^[\s_|/\-]+|[\s_|/\-]+$""")

    fun normalize(title: String): String {
        var cur = title
        repeat(MAX_PASSES) {
            val next = cur
                .replace(INNER_LEAD, "$1")
                .replace(INNER_TRAIL, "$1")
                .replace(EMPTY_GROUP, " ")
                .replace(WS_RUN, " ")
                .replace(EDGE, "")
            if (next == cur) return finish(cur)
            cur = next
        }
        return finish(cur)
    }

    private fun finish(s: String): String = if (s.none { it.isLetterOrDigit() }) "" else s

    private const val MAX_PASSES = 8
}
