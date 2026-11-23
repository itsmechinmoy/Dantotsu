package ani.dantotsu.settings.rows

import ani.dantotsu.settings.screens.SettingsScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index is the screens' own rows, so these hold for every row on every screen. */
class SettingsIndexTest {

    /** Value rows stored outside PrefName (extension preference store, AniList account). */
    private val NOT_PREFS = setOf("extension_installer")

    private val entries = SettingsIndex.entries()

    @Test
    fun everyRowOnEveryScreenIsIndexed() {
        val onScreens = SettingsScreens.all.flatMap { s -> s.rows().map { s.id to it.id } }
        assertEquals(onScreens, entries.map { it.screen.id to it.row.id })
    }

    @Test
    fun rowIdsAreUniqueAcrossScreens() {
        val dupes = entries.groupBy { it.row.id }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), dupes)
    }

    @Test
    fun screenIdsAreUnique() {
        val ids = SettingsScreens.all.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun sectionTitlesAreUniqueWithinEachScreen() {
        for (screen in SettingsScreens.all) {
            val titles = screen.sections().map { it.title }.filter { it != 0 }
            assertEquals("duplicate section heading on ${screen.id}", titles.distinct(), titles)
        }
    }

    @Test
    fun everyRowHasATitleAndPrefRowsUseTheirKeyAsId() {
        for (e in entries) {
            assertNotEquals("row ${e.row.id} has no title", 0, e.row.title)
            val k = e.row.kind
            val valueRow = k is Kind.Switch || k is Kind.Choice || k is Kind.Slider || k is Kind.Stepper || k is Kind.Segmented
            if (valueRow && e.row.id !in NOT_PREFS) {
                assertTrue("row ${e.row.id} must name its pref", e.row.keys.isNotEmpty())
                assertTrue("row ${e.row.id} id must be one of its keys",
                    e.row.keys.any { it.name == e.row.id })
            }
        }
    }

    @Test
    fun scoreOrdersExactThenPrefixThenContains() {
        val s = { title: String -> SettingsIndex.score("backup", listOf("backup"), title, "", "", "") }
        assertTrue(s("backup") > s("backup now"))
        assertTrue(s("backup now") > s("auto backup"))
        assertEquals(0, s("restore"))
        assertEquals(40, SettingsIndex.score("export", listOf("export"), "backup now", "", "export import", ""))
    }
}
