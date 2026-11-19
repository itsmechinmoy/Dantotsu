package ani.dantotsu.settings.rows

import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.Pref
import ani.dantotsu.settings.screens.SettingsScreens
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Moving a group onto rows must not lose a setting or change what is stored. [GOLDEN] is
 * copied from Preferences.kt before the move, for every key a settings row writes; [WRITTEN]
 * lists, per screen, the keys the old activity of that group wrote (its setVal calls).
 */
class SettingsKeysTest {

    private fun g(key: PrefName, location: Location, type: KClass<*>, default: Any) = key to Pref(location, type, default)

    private val GOLDEN: List<Pair<PrefName, Pref>> = listOf(
        g(PrefName.PreferredDownloadResolutions, Location.General, List::class, listOf("1080p", "720p", "480p", "360p", "240p", "144p")),
        g(PrefName.AnimeDefaultView, Location.UI, Int::class, 0),
        g(PrefName.AutoSelectServer, Location.Player, Boolean::class, false),
        g(PrefName.IncludeAnimeList, Location.General, Boolean::class, true),
        g(PrefName.SettingsPreferDub, Location.General, Boolean::class, false),
        g(PrefName.ShowEpisodeRating, Location.General, Boolean::class, true),
        g(PrefName.ShowYtButton, Location.UI, Boolean::class, true),
        g(PrefName.SmartDownloadAnime, Location.General, Boolean::class, false),
        g(PrefName.CheckUpdate, Location.General, Boolean::class, true),
        g(PrefName.LogToFile, Location.Irrelevant, Boolean::class, false),
        g(PrefName.SharedUserID, Location.General, Boolean::class, true),
        g(PrefName.TrackMemoryLeaks, Location.Irrelevant, Boolean::class, false),
    )

    private val WRITTEN: Map<String, List<String>> = mapOf(
        "anime" to listOf("AnimeDefaultView", "AutoSelectServer", "IncludeAnimeList", "SettingsPreferDub", "ShowEpisodeRating", "ShowYtButton", "SmartDownloadAnime", "PreferredDownloadResolutions"),
        "about" to listOf("CheckUpdate", "LogToFile", "SharedUserID", "TrackMemoryLeaks"),
    )

    private val screens = SettingsScreens.all
    private val rowKeys = screens.flatMap { s -> s.rows().flatMap { it.keys } }.toSet()

    @Test
    fun keysTypesAndDefaultsMatchTheOldScreens() {
        for ((key, pref) in GOLDEN) assertEquals("pref $key changed", pref, key.data)
    }

    @Test
    fun everyRowKeyIsInTheGoldenList() {
        val golden = GOLDEN.map { it.first }.toSet()
        assertEquals(emptySet<PrefName>(), rowKeys - golden)
    }

    @Test
    fun everyKeyTheOldScreenWroteIsOnItsRows() {
        for (screen in screens) {
            val written = WRITTEN[screen.id] ?: error("no old keys listed for ${screen.id}")
            val onRows = screen.rows().flatMap { it.keys }.map { it.name }.toSet()
            assertEquals("missing on ${screen.id}", emptyList<String>(), written.filter { it !in onRows })
        }
    }
}
