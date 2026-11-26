package ani.dantotsu.media.anime.selector

/**
 * The single episode a release name is for, when the name says so plainly ("S03E02").
 * Dash numbers ("- 51") are left out on purpose: many groups number a later season from the
 * first episode of the show (S3E03 = "- 51"), so they say nothing about this entry's episode.
 * Torrent sources list neighbouring episodes under an episode, so a remembered release name
 * from the last episode can be present in this episode's list too; it must not be picked.
 */
object ReleaseEpisode {
    private val SEASON_EPISODE = Regex("""(?i)\bS\d{1,2}\s?E(\d{1,4})(?:v\d)?\b(?!\s?[-~]\s?E?\d)""")

    /** Episode number the name is for, or null for batches and names that don't say. */
    fun number(name: String): Int? {
        val line = name.lineSequence().firstOrNull().orEmpty()
        return SEASON_EPISODE.find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** False only when [name] clearly names another episode than [episode]. */
    fun fits(name: String, episode: String?): Boolean {
        val wanted = episode?.trim()?.toDoubleOrNull() ?: return true
        val named = number(name) ?: return true
        return named.toDouble() == wanted
    }
}
