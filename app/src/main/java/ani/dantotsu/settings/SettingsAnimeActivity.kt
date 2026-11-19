package ani.dantotsu.settings

import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.media.MediaType
import ani.dantotsu.restartApp
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.Segment
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.segmentedRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsAnimeActivity : SettingsScreenActivity() {
    override val screen get() = AnimeScreen
}

object AnimeScreen : SettingsScreen("anime", R.string.anime, R.drawable.ic_round_movie_filter_24, R.string.anime_desc) {

    override val activity get() = SettingsAnimeActivity::class.java

    override fun sections() = listOf(
        Section(0, listOf(
            segmentedRow(PrefName.AnimeDefaultView, R.string.default_ep_view, R.drawable.ic_round_view_list_24,
                options = listOf(
                    Segment(R.drawable.ic_round_view_list_24, R.string.list),
                    Segment(R.drawable.ic_round_grid_view_24, R.string.grid),
                    Segment(R.drawable.ic_round_view_comfy_24, R.string.compact),
                )),
            actionRow("player_settings", R.string.player_settings, R.string.player_settings_desc,
                R.drawable.ic_round_video_settings_24, opensScreen = true,
                keywords = listOf(R.string.default_speed, R.string.cursed_speeds),
                onClick = { host, _ -> host.activity.startActivity(Intent(host.activity, PlayerSettingsActivity::class.java)) }),
            actionRow("purge_anime_downloads", R.string.purge_anime_downloads, R.string.purge_anime_downloads_desc,
                R.drawable.ic_round_delete_24,
                onClick = { host, _ ->
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.purge_anime_downloads)
                        setMessage(R.string.purge_confirm, host.activity.getString(R.string.anime))
                        setPosButton(R.string.yes, onClick = {
                            Injekt.get<DownloadsManager>().purgeDownloads(MediaType.ANIME)
                        })
                        setNegButton(R.string.no)
                        show()
                    }
                }),
            actionRow(PrefName.PreferredDownloadResolutions.name, R.string.preferred_download_resolutions,
                icon = R.drawable.ic_round_high_quality_24, keys = listOf(PrefName.PreferredDownloadResolutions),
                current = { PrefManager.getVal<List<String>>(PrefName.PreferredDownloadResolutions).joinToString(", ") },
                onClick = { host, _ -> ResolutionPriorityDialog.show(host.activity, null) { host.refresh() } }),
            switchRow(PrefName.AutoSelectServer, R.string.auto_select_server_title, R.string.auto_select_server_desc,
                R.drawable.ic_round_auto_awesome_24),
            switchRow(PrefName.SmartDownloadAnime, R.string.smart_download_anime, R.string.smart_download_anime_desc,
                R.drawable.ic_download_24),
            switchRow(PrefName.ShowEpisodeRating, R.string.show_episode_rating, R.string.show_episode_rating_desc,
                R.drawable.ic_round_star_24),
            switchRow(PrefName.SettingsPreferDub, R.string.prefer_dub, R.string.prefer_dub_desc,
                R.drawable.ic_round_audiotrack_24),
            switchRow(PrefName.ShowYtButton, R.string.show_yt, R.string.show_yt_desc, R.drawable.ic_round_play_circle_24),
            switchRow(PrefName.IncludeAnimeList, R.string.include_list, R.string.include_list_anime_desc,
                R.drawable.view_list_24, onChange = { host, _ -> host.activity.restartApp() }),
        )),
    )
}

object ResolutionPriorityDialog {
    val ALL_RESOLUTIONS = listOf("1080p", "720p", "480p", "360p", "240p", "144p")

    fun show(
        context: android.content.Context,
        extensionName: String? = null,
        onUpdated: ((List<String>) -> Unit)? = null
    ) {
        val currentResolutions = PrefManager.getPreferredDownloadResolutions(extensionName).toMutableList()
        val fullList = currentResolutions + ALL_RESOLUTIONS.filter { it !in currentResolutions }
        val checkedItems = fullList.map { it in currentResolutions }.toBooleanArray()

        context.customAlertDialog().apply {
            setTitle(R.string.preferred_download_resolutions)
            multiChoiceItems(fullList.toTypedArray(), checkedItems) { updatedSelection ->
                currentResolutions.clear()
                fullList.forEachIndexed { index, res ->
                    if (updatedSelection[index]) {
                        currentResolutions.add(res)
                    }
                }
            }
            setPosButton(R.string.ok) {
                val toSave = if (currentResolutions.isEmpty()) ALL_RESOLUTIONS else currentResolutions
                PrefManager.setPreferredDownloadResolutions(extensionName, toSave)
                onUpdated?.invoke(toSave)
            }
            setNeutralButton(R.string.reset) {
                PrefManager.setPreferredDownloadResolutions(extensionName, ALL_RESOLUTIONS)
                onUpdated?.invoke(ALL_RESOLUTIONS)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
