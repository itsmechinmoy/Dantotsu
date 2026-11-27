package ani.dantotsu.settings

import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow

class PlayerSettingsActivity : SettingsScreenActivity() {
    override val screen get() = PlayerHubScreen
}

object PlayerHubScreen : SettingsScreen(
    id = "player",
    title = R.string.player_settings,
    icon = R.drawable.ic_round_video_settings_24,
    summary = R.string.player_settings_desc,
) {
    override val activity get() = PlayerSettingsActivity::class.java

    override fun sections() = listOf(
        Section(0, listOf(
            categoryRow("player_cat_internal", R.string.pref_player_internal, R.string.pref_player_internal_summary, R.drawable.ic_round_slow_motion_video_24, PlayerInternalSettingsActivity::class.java),
            categoryRow("player_cat_gestures", R.string.pref_player_gestures, R.string.pref_player_gestures_summary, R.drawable.ic_round_touch_app_24, PlayerGesturesSettingsActivity::class.java),
            categoryRow("player_cat_decoder", R.string.pref_player_decoder, R.string.pref_player_decoder_summary, R.drawable.ic_round_high_quality_24, PlayerDecoderSettingsActivity::class.java),
            categoryRow("player_cat_subtitles", R.string.pref_player_subtitle, R.string.pref_player_subtitle_summary, R.drawable.ic_round_subtitles_24, PlayerSubtitlesSettingsActivity::class.java),
            categoryRow("player_cat_audio", R.string.pref_player_audio, R.string.pref_player_audio_summary, R.drawable.ic_round_volume_up_24, PlayerAudioSettingsActivity::class.java),
            categoryRow("player_cat_custom_buttons", R.string.pref_player_custom_button, R.string.pref_player_custom_button_summary, R.drawable.ic_round_smart_button_24, PlayerCustomButtonsSettingsActivity::class.java),
            categoryRow("player_cat_script_editor", R.string.pref_player_editor, R.string.pref_player_editor_summary, R.drawable.ic_round_edit_note_24, PlayerScriptEditorSettingsActivity::class.java),
            categoryRow("player_cat_torrent", R.string.pref_player_torrents, R.string.pref_player_torrents_summary, R.drawable.ic_round_magnet_24, TorrentSettingsActivity::class.java),
            categoryRow("player_cat_advanced", R.string.pref_player_advanced, R.string.pref_player_advanced_summary, R.drawable.ic_round_settings_24, PlayerAdvancedSettingsActivity::class.java),
        )),
    )

    private fun categoryRow(
        id: String,
        title: Int,
        summary: Int,
        icon: Int,
        target: Class<*>,
    ) = actionRow(
        id = id,
        title = title,
        summary = summary,
        icon = icon,
        opensScreen = true,
        onClick = { host, _ -> host.activity.startActivity(Intent(host.activity, target)) },
    )
}
