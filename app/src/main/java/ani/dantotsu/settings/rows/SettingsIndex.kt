package ani.dantotsu.settings.rows

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.settings.screens.SettingsScreens

/** One searchable row: where it lives and what it says. */
class IndexEntry(val screen: SettingsScreen, val section: Section, val row: Row)

/**
 * The settings search index, built from the same [Row]s that draw the screens.
 * There is no hand-written list to keep in step.
 */
object SettingsIndex {

    fun entries(screens: List<SettingsScreen> = SettingsScreens.all): List<IndexEntry> =
        screens.flatMap { screen ->
            screen.sections().flatMap { section -> section.rows.map { IndexEntry(screen, section, it) } }
        }

    class Hit(
        val title: String,
        val summary: String?,
        val breadcrumbs: String,
        val icon: Int,
        val entry: IndexEntry,
    )

    fun search(context: Context, query: String): List<Hit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotBlank() }
        val root = context.getString(R.string.settings)
        return entries()
            .filter { runCatching { it.row.visible() }.getOrDefault(false) }
            .mapNotNull { entry ->
                val title = context.getString(entry.row.title)
                val summary = entry.row.summary?.let(context::getString)
                val keywords = entry.row.keywords.joinToString(" ") { context.getString(it) }
                val screen = context.getString(entry.screen.title)
                // Single-section screens (About) have no section title.
                val section = entry.section.title.takeIf { it != 0 }?.let(context::getString)
                val score = score(q, words, title.lowercase(), summary?.lowercase().orEmpty(),
                    keywords.lowercase(), "$screen ${section.orEmpty()}".lowercase())
                if (score == 0) null
                else score to Hit(title, summary, listOfNotNull(root, screen, section).joinToString(" > "),
                    entry.row.icon ?: R.drawable.ic_round_settings_24, entry)
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    internal fun score(q: String, words: List<String>, title: String, summary: String, keywords: String, place: String): Int = when {
        title == q -> 100
        title.startsWith(q) -> 75
        title.contains(q) -> 50
        keywords.contains(q) -> 40
        summary.contains(q) -> 25
        place.contains(q) -> 15
        else -> {
            val hay = "$title $summary $keywords $place"
            if (words.size > 1 && words.all { hay.contains(it) }) 10 else 0
        }
    }
}
