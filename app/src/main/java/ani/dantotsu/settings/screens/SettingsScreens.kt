package ani.dantotsu.settings.screens

import ani.dantotsu.settings.rows.SettingsScreen

/** Every settings group drawn from rows, in the order of the settings home list. Search reads this. */
object SettingsScreens {
    val all: List<SettingsScreen> get() = listOf(
        ani.dantotsu.settings.ThemeScreen,
        ani.dantotsu.settings.CommonScreen,
        ani.dantotsu.settings.AnimeScreen,
        ani.dantotsu.settings.MangaScreen,
        ani.dantotsu.settings.ExtensionsScreen,
        ani.dantotsu.settings.TorrentScreen,
        ani.dantotsu.settings.NotificationScreen,
        ani.dantotsu.settings.AboutScreen,
    )
}
