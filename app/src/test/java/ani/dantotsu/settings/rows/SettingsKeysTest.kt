package ani.dantotsu.settings.rows

import android.graphics.Color
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
        g(PrefName.CommentsEnabled, Location.General, Int::class, 0),
        g(PrefName.rpcEnabled, Location.Irrelevant, Boolean::class, false),
        g(PrefName.AutoBackupDir, Location.Irrelevant, String::class, ""),
        g(PrefName.AutoBackupInterval, Location.General, Int::class, 0),
        g(PrefName.AutoBackupMaxCopies, Location.General, Int::class, 3),
        g(PrefName.LastAutoBackupTimestamp, Location.General, Long::class, 0L),
        g(PrefName.DownloadsDir, Location.Irrelevant, String::class, ""),
        g(PrefName.AdultOnly, Location.General, Boolean::class, false),
        g(PrefName.AppPassword, Location.Protected, String::class, ""),
        g(PrefName.BiometricToken, Location.Protected, String::class, ""),
        g(PrefName.ContinueMedia, Location.General, Boolean::class, true),
        g(PrefName.DefaultStartUpTab, Location.UI, Int::class, 1),
        g(PrefName.DohProvider, Location.General, Int::class, 0),
        g(PrefName.DownloadManager, Location.General, Int::class, 0),
        g(PrefName.DownloadWifiOnly, Location.General, Boolean::class, false),
        g(PrefName.HidePrivate, Location.General, Boolean::class, false),
        g(PrefName.MaxParallelDownloads, Location.General, Int::class, 0),
        g(PrefName.OverridePassword, Location.Protected, Boolean::class, false),
        g(PrefName.RecentlyListOnly, Location.General, Boolean::class, false),
        g(PrefName.SearchSources, Location.General, Boolean::class, false),
        g(PrefName.CustomThemeInt, Location.UI, Int::class, Color.parseColor("#6200EE")),
        g(PrefName.DarkMode, Location.UI, Int::class, 0),
        g(PrefName.UseCustomTheme, Location.UI, Boolean::class, false),
        g(PrefName.UseMaterialYou, Location.UI, Boolean::class, false),
        g(PrefName.UseOLED, Location.UI, Boolean::class, false),
        g(PrefName.UseSourceTheme, Location.UI, Boolean::class, false),
        g(PrefName.UseSystemFont, Location.UI, Boolean::class, false),
        g(PrefName.Theme, Location.UI, String::class, "PURPLE"),
        g(PrefName.AnilistFilteredTypes, Location.General, Set::class, setOf<String>()),
        g(PrefName.AnilistNotificationInterval, Location.General, Int::class, 3),
        g(PrefName.CommentNotificationInterval, Location.General, Int::class, 0),
        g(PrefName.SubscriptionCheckingNotifications, Location.General, Boolean::class, true),
        g(PrefName.SubscriptionNotificationInterval, Location.General, Int::class, 2),
        g(PrefName.SubscriptionPromptAtEnd, Location.General, Boolean::class, true),
        g(PrefName.UseAlarmManager, Location.General, Boolean::class, false),
        g(PrefName.DefaultUserAgent, Location.General, String::class, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"),
        g(PrefName.EnableSocks5Proxy, Location.General, Boolean::class, false),
        g(PrefName.NSFWExtension, Location.General, Boolean::class, true),
        g(PrefName.SkipExtensionIcons, Location.UI, Boolean::class, false),
        g(PrefName.AnimeExtensionRepos, Location.General, Set::class, setOf<String>()),
        g(PrefName.MangaExtensionRepos, Location.General, Set::class, setOf<String>()),
        g(PrefName.NovelExtensionRepos, Location.General, Set::class, setOf<String>()),
        g(PrefName.TorrentBatterySaving, Location.Player, Boolean::class, false),
        g(PrefName.TorrentDisableUtp, Location.Player, Boolean::class, false),
        g(PrefName.TorrentEncryption, Location.Player, Boolean::class, false),
        g(PrefName.TorrentWifiOnly, Location.Player, Boolean::class, false),
        g(PrefName.TorrentDownloadSpeedLimit, Location.Player, Int::class, 0),
        g(PrefName.TorrentMaxConnections, Location.Player, Int::class, 108),
        g(PrefName.TorrentPort, Location.Player, Int::class, 0),
        g(PrefName.TorrentUploadSpeedLimit, Location.Player, Int::class, 0),
        g(PrefName.IncludeMangaList, Location.General, Boolean::class, true),
        g(PrefName.MangaDefaultView, Location.UI, Int::class, 0),
        g(PrefName.SkipDuplicateChapters, Location.General, Boolean::class, true),
        g(PrefName.SmartDownloadManga, Location.General, Boolean::class, false),
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
        "accounts" to listOf("CommentsEnabled", "rpcEnabled"),
        "data" to listOf("AutoBackupDir", "AutoBackupInterval", "AutoBackupMaxCopies"),
        "common" to listOf("AdultOnly", "AppPassword", "BiometricToken", "ContinueMedia", "DefaultStartUpTab", "DohProvider", "DownloadManager", "DownloadWifiOnly", "HidePrivate", "MaxParallelDownloads", "OverridePassword", "RecentlyListOnly", "SearchSources"),
        "theme" to listOf("CustomThemeInt", "DarkMode", "Theme", "UseCustomTheme", "UseMaterialYou", "UseOLED", "UseSourceTheme", "UseSystemFont"),
        "notifications" to listOf("AnilistFilteredTypes", "AnilistNotificationInterval", "CommentNotificationInterval", "SubscriptionCheckingNotifications", "SubscriptionNotificationInterval", "SubscriptionPromptAtEnd", "UseAlarmManager"),
        "extensions" to listOf("DefaultUserAgent", "EnableSocks5Proxy", "NSFWExtension", "SkipExtensionIcons", "AnimeExtensionRepos", "MangaExtensionRepos", "NovelExtensionRepos"),
        "torrent" to listOf("TorrentBatterySaving", "TorrentDisableUtp", "TorrentEncryption", "TorrentWifiOnly", "TorrentDownloadSpeedLimit", "TorrentMaxConnections", "TorrentPort", "TorrentUploadSpeedLimit"),
        "manga" to listOf("IncludeMangaList", "MangaDefaultView", "SkipDuplicateChapters", "SmartDownloadManga"),
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
