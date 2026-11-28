package ani.dantotsu.settings.rows

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One settings group screen (Player, Reading, ...). */
abstract class SettingsScreen(
    val id: String,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    /** Second line on the settings home list. */
    @StringRes val summary: Int,
) {
    /** The activity that shows this screen; search opens it. */
    abstract val activity: Class<out Activity>

    /** Built fresh on every refresh. Must not read prefs or a Context (see [Row]). */
    abstract fun sections(): List<Section>

    fun rows(): List<Row> = sections().flatMap { it.rows }
}

/** What the list on a screen shows, in order. */
sealed interface ScreenItem {
    val key: String

    data class Header(@StringRes val title: Int, val label: CharSequence? = null) : ScreenItem {
        override val key get() = "header:$title:${label ?: ""}"
    }

    class RowItem(val row: Row) : ScreenItem {
        override val key get() = row.id
    }

    /** Last row of a section with advanced rows: opens or closes them in that section. */
    data class More(val section: Int, val count: Int, val expanded: Boolean) : ScreenItem {
        override val key get() = "more:$section"
    }
}

object ScreenItems {
    /**
     * Section headers and their rows. A section's advanced rows stay hidden until its
     * index is in [expanded]; then they follow its other rows, directly above the
     * section's [ScreenItem.More] row, so they open where the user tapped. A section with
     * nothing to show loses its header, and a section titled 0 has none (single-section
     * screens).
     */
    fun build(
        sections: List<Section>,
        expanded: Set<Int>,
        isVisible: (Row) -> Boolean = { it.visible() },
    ): List<ScreenItem> {
        val out = ArrayList<ScreenItem>()
        sections.forEachIndexed { index, section ->
            val visible = section.rows.filter(isVisible)
            if (visible.isEmpty()) return@forEachIndexed
            val (advanced, basic) = visible.partition { it.advanced }
            val open = index in expanded
            if (section.title != 0 || section.label != null) out += ScreenItem.Header(section.title, section.label)
            basic.forEach { out += ScreenItem.RowItem(it) }
            if (open) advanced.forEach { out += ScreenItem.RowItem(it) }
            if (advanced.isNotEmpty()) out += ScreenItem.More(index, advanced.size, open)
        }
        return out
    }

    /** Index of the section that holds row [id], or -1. */
    fun sectionOf(sections: List<Section>, id: String) = sections.indexOfFirst { s -> s.rows.any { it.id == id } }
}
