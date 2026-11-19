package ani.dantotsu.settings

import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.media.MediaType
import ani.dantotsu.restartApp
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.Segment
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.segmentedRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsMangaActivity : SettingsScreenActivity() {
    override val screen get() = MangaScreen
}

object MangaScreen : SettingsScreen("manga", R.string.manga, R.drawable.ic_round_import_contacts_24, R.string.manga_desc) {

    override val activity get() = SettingsMangaActivity::class.java

    override fun sections() = listOf(
        Section(0, listOf(
            segmentedRow(PrefName.MangaDefaultView, R.string.default_chp_view, R.drawable.ic_round_view_list_24,
                options = listOf(
                    Segment(R.drawable.ic_round_view_list_24, R.string.list),
                    Segment(R.drawable.ic_round_view_comfy_24, R.string.compact),
                )),
            actionRow("reader_settings", R.string.reader_settings, R.string.reader_settings_desc,
                R.drawable.ic_round_reader_settings, opensScreen = true,
                onClick = { host, _ -> host.activity.startActivity(Intent(host.activity, ReaderSettingsActivity::class.java)) }),
            actionRow("purge_manga_downloads", R.string.purge_manga_downloads, R.string.purge_manga_downloads_desc,
                R.drawable.ic_round_delete_24,
                onClick = { host, _ -> purge(host, R.string.purge_manga_downloads, R.string.manga, MediaType.MANGA) }),
            actionRow("purge_novel_downloads", R.string.purge_novel_downloads, R.string.purge_novel_downloads_desc,
                R.drawable.ic_round_delete_24,
                onClick = { host, _ -> purge(host, R.string.purge_novel_downloads, R.string.novels, MediaType.NOVEL) }),
            switchRow(PrefName.SkipDuplicateChapters, R.string.skip_duplicate_chapters,
                R.string.skip_duplicate_chapters_desc, R.drawable.view_list_24),
            switchRow(PrefName.SmartDownloadManga, R.string.smart_download_manga, R.string.smart_download_manga_desc,
                R.drawable.ic_download_24),
            switchRow(PrefName.IncludeMangaList, R.string.include_list, R.string.include_list_desc,
                R.drawable.view_list_24, onChange = { host, _ -> host.activity.restartApp() }),
        )),
    )

    private fun purge(host: SettingsHost, title: Int, what: Int, type: MediaType) {
        host.activity.customAlertDialog().apply {
            setTitle(title)
            setMessage(R.string.purge_confirm, host.activity.getString(what))
            setPosButton(R.string.yes) { Injekt.get<DownloadsManager>().purgeDownloads(type) }
            setNegButton(R.string.no)
            show()
        }
    }
}
