package ani.dantotsu.settings.rows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenItemsTest {

    private fun row(id: String, advanced: Boolean = false, visible: Boolean = true) = Row(
        id = id, title = 0, advanced = advanced, visible = { visible },
        kind = Kind.Action(onClick = { _, _ -> }),
    )

    private fun keys(items: List<ScreenItem>) = items.map {
        when (it) {
            is ScreenItem.Header -> "H${it.title}"
            is ScreenItem.RowItem -> it.row.id
            is ScreenItem.More -> if (it.expanded) "LESS${it.section}" else "MORE${it.section}x${it.count}"
        }
    }

    @Test
    fun advancedRowsOpenPerSectionAboveItsMoreRow() {
        val sections = listOf(
            Section(1, listOf(row("a"), row("x", advanced = true), row("b"))),
            Section(2, listOf(row("c"), row("y", advanced = true))),
        )
        assertEquals(listOf("H1", "a", "b", "MORE0x1", "H2", "c", "MORE1x1"), keys(ScreenItems.build(sections, emptySet())))
        // Opening section 0 leaves section 1 closed; the opened rows sit right above "show fewer".
        assertEquals(listOf("H1", "a", "b", "x", "LESS0", "H2", "c", "MORE1x1"), keys(ScreenItems.build(sections, setOf(0))))
    }

    @Test
    fun sectionWithOnlyAdvancedRowsKeepsItsHeaderAndMoreRow() {
        val sections = listOf(Section(1, listOf(row("a"))), Section(2, listOf(row("x", advanced = true))))
        assertEquals(listOf("H1", "a", "H2", "MORE1x1"), keys(ScreenItems.build(sections, emptySet())))
        assertEquals(listOf("H1", "a", "H2", "x", "LESS1"), keys(ScreenItems.build(sections, setOf(1))))
    }

    @Test
    fun noMoreRowWithoutVisibleAdvancedRows() {
        val sections = listOf(Section(1, listOf(row("a"), row("x", advanced = true, visible = false))))
        val items = ScreenItems.build(sections, emptySet())
        assertFalse(items.any { it is ScreenItem.More })
        assertEquals(listOf("H1", "a"), keys(items))
    }

    @Test
    fun hiddenRowsAndEmptySectionsAreDropped() {
        val sections = listOf(Section(1, listOf(row("a", visible = false))), Section(2, listOf(row("b"))))
        assertEquals(listOf("H2", "b"), keys(ScreenItems.build(sections, setOf(0, 1))))
    }

    @Test
    fun sectionOfFindsTheRowsSection() {
        val sections = listOf(Section(1, listOf(row("a"))), Section(2, listOf(row("x", advanced = true))))
        assertEquals(1, ScreenItems.sectionOf(sections, "x"))
        assertEquals(-1, ScreenItems.sectionOf(sections, "nope"))
    }

    @Test
    fun snapKeepsSliderValuesOnTheStepGrid() {
        assertEquals(1.25f, RowAdapter.snap(1.3f, 0.25f, 2f, 0.25f), 0.0001f)
        assertEquals(2f, RowAdapter.snap(9f, 0.25f, 2f, 0.25f), 0.0001f)
        assertEquals(0.25f, RowAdapter.snap(-1f, 0.25f, 2f, 0.25f), 0.0001f)
        assertTrue(RowAdapter.snap(37f, 0f, 100f, 5f) == 35f)
    }
}
