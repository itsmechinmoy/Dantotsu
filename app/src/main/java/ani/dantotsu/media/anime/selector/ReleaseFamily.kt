package ani.dantotsu.media.anime.selector

/**
 * The release a remembered server belongs to: its group and resolution.
 *
 * A torrent source names each server after the full release title ("[Erai-raws] Show - 01v2
 * [1080p]..."), so the name remembered on episode 1 never exists on episode 2. Matching by
 * family picks the same group's release of the new episode instead of whatever comes first.
 */
object ReleaseFamily {
    data class Key(val group: String, val resolution: String?)

    /** Null when [name] carries no release group (plain stream servers such as "HD-1"). */
    fun of(name: String): Key? {
        val fields = runCatching { SourceRowClassifier.parseRelease(name) }.getOrNull() ?: return null
        val group = fields.releaseGroup?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return Key(group, fields.resolution?.trim()?.lowercase())
    }

    /**
     * The first of [candidates] from the remembered release's group, preferring the same
     * resolution. Null when [remembered] has no group or the group has no release here.
     */
    fun <T> pick(remembered: String, candidates: List<T>, name: (T) -> String): T? {
        val want = of(remembered) ?: return null
        val keyed = candidates.map { it to of(name(it)) }
        val sameGroup = keyed.filter { it.second?.group == want.group }
        return (sameGroup.firstOrNull { it.second?.resolution == want.resolution } ?: sameGroup.firstOrNull())?.first
    }

    /** Short label for the auto-play dialog: "Erai-raws · 1080p" instead of another episode's title. */
    fun label(name: String): String? {
        val fields = runCatching { SourceRowClassifier.parseRelease(name) }.getOrNull() ?: return null
        val group = fields.releaseGroup?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return listOfNotNull(group, fields.resolution?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }
}
