package ani.dantotsu.settings

import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Opened from the stream picker; not on the settings home, so not in search either. */
class SettingsAddonActivity : SettingsScreenActivity() {
    override val screen get() = AddonScreen

    private val downloadAddonManager: DownloadAddonManager = Injekt.get()

    override fun onDestroy() {
        super.onDestroy()
        downloadAddonManager.removeListenerAction()
    }
}

object AddonScreen : SettingsScreen("addons", R.string.addons, R.drawable.ic_round_restaurant_24, R.string.addons_desc) {

    override val activity get() = SettingsAddonActivity::class.java

    override fun sections() = listOf(
        Section(0, listOf(
            actionRow("addon_torrent_settings", R.string.torrent_settings, R.string.torrent_settings_desc,
                R.drawable.ic_round_settings_24, opensScreen = true,
                onClick = { host, _ -> host.activity.startActivity(Intent(host.activity, TorrentSettingsActivity::class.java)) }),
        )),
    )
}
