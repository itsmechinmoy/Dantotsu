package ani.dantotsu.settings

import android.view.ViewGroup
import ani.dantotsu.R
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog

class TorrentSettingsActivity : SettingsScreenActivity() {
    override val screen get() = TorrentScreen
}

object TorrentScreen : SettingsScreen("torrent", R.string.torrent_settings, R.drawable.ic_round_dns_24, R.string.torrent_settings_desc) {

    override val activity get() = TorrentSettingsActivity::class.java

    private fun speed(c: android.content.Context, key: PrefName): String {
        val v = PrefManager.getVal<Int>(key)
        return if (v > 0) c.getString(R.string.speed_kbps, v) else c.getString(R.string.disabled)
    }

    override fun sections() = listOf(
        Section(0, listOf(
            actionRow("play_magnet", R.string.play_magnet_or_torrent, R.string.play_magnet_or_torrent_desc,
                R.drawable.ic_round_play_arrow_24,
                onClick = { host, _ ->
                    ani.dantotsu.torrent.DirectTorrentBottomSheet()
                        .show(host.activity.supportFragmentManager, "DirectTorrentBottomSheet")
                }),
            switchRow(PrefName.TorrentEncryption, R.string.torrent_encryption, R.string.torrent_encryption_desc,
                R.drawable.ic_shield),
            switchRow(PrefName.TorrentWifiOnly, R.string.torrent_wifi_only, R.string.torrent_wifi_only_desc,
                R.drawable.lan_24),
            numberRow(PrefName.TorrentDownloadSpeedLimit, R.string.torrent_download_speed, R.drawable.ic_download_24) {
                speed(it, PrefName.TorrentDownloadSpeedLimit)
            },
            numberRow(PrefName.TorrentUploadSpeedLimit, R.string.torrent_upload_speed, R.drawable.ic_download_24) {
                speed(it, PrefName.TorrentUploadSpeedLimit)
            },
            numberRow(PrefName.TorrentMaxConnections, R.string.torrent_max_connections, R.drawable.ic_round_dns_24) {
                PrefManager.getVal<Int>(PrefName.TorrentMaxConnections).toString()
            },
            switchRow(PrefName.TorrentBatterySaving, R.string.torrent_battery_saving,
                R.string.torrent_battery_saving_desc, R.drawable.ic_lightbulb_24),
            numberRow(PrefName.TorrentPort, R.string.torrent_port, R.drawable.network_node_24) { c ->
                val port = PrefManager.getVal<Int>(PrefName.TorrentPort)
                if (port > 0) port.toString() else c.getString(R.string.disabled)
            },
            switchRow(PrefName.TorrentDisableUtp, R.string.torrent_disable_utp, R.string.torrent_disable_utp_desc,
                R.drawable.lan_24),
        )),
    )

    /** Int pref typed in a dialog; the row shows [current]. */
    private fun numberRow(key: PrefName, title: Int, icon: Int, current: (android.content.Context) -> String) =
        actionRow(key.name, title, icon = icon, keys = listOf(key), current = current,
            onClick = { host, _ -> showNumberInputDialog(host, title, key) })

    private fun showNumberInputDialog(host: SettingsHost, titleRes: Int, prefName: PrefName) = with(host.activity) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefManager.getVal<Int>(prefName).toString())
            setSelection(text.length)
        }
        val container = android.widget.FrameLayout(this)
        val margin = (24 * resources.displayMetrics.density).toInt()
        input.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = margin
            rightMargin = margin
            topMargin = (8 * resources.displayMetrics.density).toInt()
            bottomMargin = (8 * resources.displayMetrics.density).toInt()
        }
        container.addView(input)
        customAlertDialog().apply {
            setTitle(getString(titleRes))
            setCustomView(container)
            setPosButton(R.string.yes, onClick = {
                PrefManager.setVal(prefName, input.text.toString().toIntOrNull() ?: 0)
                host.refresh()
            })
            setNegButton(R.string.no)
            show()
        }
    }
}
