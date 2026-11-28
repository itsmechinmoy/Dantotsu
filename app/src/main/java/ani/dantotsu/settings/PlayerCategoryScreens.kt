package ani.dantotsu.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import ani.dantotsu.R
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.sliderRow
import ani.dantotsu.settings.rows.stepperRow
import ani.dantotsu.settings.rows.stepsRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorWheelDialog
import kotlin.math.roundToInt

/** Base activity for each categorized player settings screen, supporting color pickers and text editors. */
open class PlayerCategoryActivity(
    override val screen: SettingsScreen,
) : SettingsScreenActivity(), SimpleDialog.OnDialogResultListener {

    private var colorCallback: ((Int) -> Unit)? = null

    fun pickColor(originalColor: Int, title: String, callback: (Int) -> Unit) {
        colorCallback = callback
        SimpleColorWheelDialog()
            .title(title)
            .color(originalColor)
            .alpha(true)
            .neg()
            .theme(R.style.MyPopup)
            .show(this, "colorPicker")
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (dialogTag == "colorPicker" && which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE) {
            val color = extras.getInt(SimpleColorWheelDialog.COLOR)
            colorCallback?.invoke(color)
            colorCallback = null
            refresh()
            return true
        }
        return false
    }
}

class PlayerInternalSettingsActivity : PlayerCategoryActivity(PlayerInternalScreen)
class PlayerGesturesSettingsActivity : PlayerCategoryActivity(PlayerGesturesScreen)
class PlayerDecoderSettingsActivity : PlayerCategoryActivity(PlayerDecoderScreen)
class PlayerSubtitlesSettingsActivity : PlayerCategoryActivity(PlayerSubtitlesScreen)
class PlayerAudioSettingsActivity : PlayerCategoryActivity(PlayerAudioScreen)
class PlayerCustomButtonsSettingsActivity : PlayerCategoryActivity(PlayerCustomButtonsScreen)
class PlayerScriptEditorSettingsActivity : PlayerCategoryActivity(PlayerScriptEditorScreen)
class PlayerAdvancedSettingsActivity : PlayerCategoryActivity(PlayerAdvancedScreen)

object PlayerInternalScreen : SettingsScreen(
    id = "player_internal",
    title = R.string.pref_player_internal,
    icon = R.drawable.ic_round_slow_motion_video_24,
    summary = R.string.pref_player_internal_summary,
) {
    override val activity get() = PlayerInternalSettingsActivity::class.java

    private val normalSpeeds = arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
    private val cursedSpeeds = arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
    private val bufferSizes = listOf(10, 15, 20, 30, 45, 60)

    override fun sections() = listOf(
        Section(R.string.video, listOf(
            switchRow(PrefName.CompactPlayerViewEnabled, R.string.compact_player_view, R.string.compact_player_view_desc, R.drawable.ic_round_video_settings_24),
            choiceRow(
                key = PrefName.DefaultSpeed,
                title = R.string.default_speed,
                icon = R.drawable.ic_round_speed_24,
                labels = {
                    val arr = if (PrefManager.getVal(PrefName.CursedSpeeds)) cursedSpeeds else normalSpeeds
                    arr.map { s -> "${s}x" }
                },
            ),
            switchRow(
                key = PrefName.CursedSpeeds,
                title = R.string.cursed_speeds,
                icon = R.drawable.ic_round_fast_forward_24,
                refresh = true,
                onChange = { _, isChecked ->
                    PrefManager.setVal(PrefName.DefaultSpeed, if (isChecked) 0 else 5)
                },
            ),
            choiceRow(
                key = PrefName.Resize,
                title = R.string.default_resize_mode,
                icon = R.drawable.ic_round_photo_size_select_actual_24,
                labels = { listOf("Fit (Original)", "Zoom", "Stretch") },
            ),
            stepsRow(
                id = PrefName.PlayerBufferSize.name,
                title = R.string.player_buffer_size,
                count = bufferSizes.size,
                label = { c, idx -> c.getString(R.string.player_buffer_size_desc, bufferSizes[idx]) },
                get = {
                    val cur = PrefManager.getVal<Int>(PrefName.PlayerBufferSize).coerceIn(10, 60)
                    bufferSizes.indexOf(cur).coerceAtLeast(0)
                },
                icon = R.drawable.ic_round_sd_card_24,
                keys = listOf(PrefName.PlayerBufferSize),
                set = { _, idx -> PrefManager.setVal(PrefName.PlayerBufferSize, bufferSizes[idx]) },
            ),
        )),
        Section(R.string.behaviour, listOf(
            switchRow(PrefName.AlwaysContinue, R.string.always_continue, icon = R.drawable.ic_round_history_24),
            switchRow(PrefName.FocusPause, R.string.pause_video_focus, icon = R.drawable.ic_round_pause_24),
            switchRow(PrefName.RotationPlayer, R.string.show_rotate_button, icon = R.drawable.ic_round_screen_rotation_24),
            switchRow(
                key = PrefName.Pip,
                title = R.string.picture_in_picture,
                icon = R.drawable.ic_round_picture_in_picture_alt_24,
                visible = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.N },
            ),
            switchRow(PrefName.Cast, R.string.show_cast_button, icon = R.drawable.ic_round_cast_24),
            switchRow(PrefName.UseInternalCast, R.string.try_internal_cast_experimental, icon = R.drawable.ic_round_cast_24, advanced = true),
            switchRow(PrefName.AutoPlay, R.string.auto_play_next_episode, R.string.auto_play_next_episode_info, R.drawable.ic_round_playlist_play_24),
            switchRow(PrefName.AutoSkipFiller, R.string.auto_skip_fillers, R.string.auto_skip_fillers_info, R.drawable.ic_round_skip_next_24),
        )),
        Section(R.string.timestamps, listOf(
            switchRow(PrefName.TimeStampsEnabled, R.string.always_load_time_stamps, icon = R.drawable.ic_round_auto_awesome_24, refresh = true),
            switchRow(
                key = PrefName.UseProxyForTimeStamps,
                title = R.string.timestamp_proxy,
                summary = R.string.timestamp_proxy_desc,
                icon = R.drawable.ic_globe_24,
                enabled = { PrefManager.getVal(PrefName.TimeStampsEnabled) },
            ),
            switchRow(
                key = PrefName.ShowTimeStampButton,
                title = R.string.show_skip_time_stamp_button,
                icon = R.drawable.ic_round_smart_button_24,
                refresh = true,
            ),
            switchRow(
                key = PrefName.AutoHideTimeStamps,
                title = R.string.auto_hide_time_stamps,
                summary = R.string.hide_skip_button,
                icon = R.drawable.ic_round_history_24,
                enabled = { PrefManager.getVal(PrefName.ShowTimeStampButton) },
            ),
            switchRow(
                key = PrefName.AutoSkipOPED,
                title = R.string.auto_skip_op_ed,
                summary = R.string.requires_time_stamps_to_be_enabled,
                icon = R.drawable.ic_round_fast_forward_24,
                enabled = { PrefManager.getVal(PrefName.TimeStampsEnabled) },
            ),
            switchRow(
                key = PrefName.AutoSkipRecap,
                title = R.string.auto_skip_recap,
                summary = R.string.requires_time_stamps_to_be_enabled,
                icon = R.drawable.ic_round_fast_forward_24,
                enabled = { PrefManager.getVal(PrefName.TimeStampsEnabled) },
            ),
        )),
        Section(R.string.update_progress, listOf(
            switchRow(PrefName.AskIndividualPlayer, R.string.ask_update_progress_anime, R.string.ask_update_progress_info_ep, R.drawable.ic_round_info_24, refresh = true),
            switchRow(
                key = PrefName.ChapterZeroPlayer,
                title = R.string.ask_update_progress_chapter_zero,
                summary = R.string.ask_update_progress_info_zero,
                icon = R.drawable.ic_round_new_releases_24,
                enabled = { !PrefManager.getVal<Boolean>(PrefName.AskIndividualPlayer) },
            ),
            switchRow(
                key = PrefName.UpdateForHPlayer,
                title = R.string.ask_update_progress_hentai,
                icon = R.drawable.ic_round_nsfw_24,
                onChange = { host, checked -> if (checked) snackString(host.activity.getString(R.string.very_bold)) },
            ),
            sliderRow(
                key = PrefName.WatchPercentage,
                title = R.string.watch_complete_percentage,
                summary = R.string.watch_complete_percentage_info,
                icon = R.drawable.ic_round_area_chart_24,
                from = 0.50f,
                to = 1.00f,
                step = 0.05f,
                format = { _, v -> "${(v * 100f).roundToInt()}%" },
            ),
        )),
    )
}

object PlayerGesturesScreen : SettingsScreen(
    id = "player_gestures",
    title = R.string.pref_player_gestures,
    icon = R.drawable.ic_round_touch_app_24,
    summary = R.string.pref_player_gestures_summary,
) {
    override val activity get() = PlayerGesturesSettingsActivity::class.java

    override fun sections() = listOf(
        Section(0, listOf(
            switchRow(PrefName.Gestures, R.string.gestures, icon = R.drawable.ic_round_swipe_vertical_24),
            switchRow(PrefName.DoubleTap, R.string.double_tap, R.string.double_tap_info, R.drawable.ic_round_touch_app_24),
            switchRow(PrefName.FastForward, R.string.fast_forward, icon = R.drawable.ic_round_fast_forward_24),
            sliderRow(
                key = PrefName.SeekTime,
                title = R.string.seek_time,
                summary = R.string.seek_time_info,
                icon = R.drawable.ic_round_fast_forward_24,
                from = 5f,
                to = 60f,
                step = 5f,
                format = { _, v -> "${v.toInt()}s" },
            ),
            stepperRow(
                key = PrefName.SkipTime,
                title = R.string.skip_time,
                summary = R.string.skip_time_info,
                icon = R.drawable.ic_round_skip_next_24,
                from = 10f,
                to = 180f,
                step = 5f,
                format = { _, v -> "${v.toInt()}s" },
            ),
        )),
    )
}

object PlayerDecoderScreen : SettingsScreen(
    id = "player_decoder",
    title = R.string.pref_player_decoder,
    icon = R.drawable.ic_round_high_quality_24,
    summary = R.string.pref_player_decoder_summary,
) {
    override val activity get() = PlayerDecoderSettingsActivity::class.java

    private val hwDecValues = listOf("mediacodec,mediacodec-copy", "mediacodec", "auto", "no")
    private val hwDecLabels = listOf("HW+ (MediaCodec + Copy)", "HW (MediaCodec)", "Auto", "Software (No HW)")

    override fun sections() = listOf(
        Section(R.string.pref_player_decoder, listOf(
            actionRow(
                id = PrefName.MpvHwDec.name,
                title = R.string.hardware_decoding,
                summary = R.string.hardware_decoding_desc,
                icon = R.drawable.ic_round_high_quality_24,
                keys = listOf(PrefName.MpvHwDec),
                current = {
                    val cur = PrefManager.getVal<String>(PrefName.MpvHwDec)
                    val idx = hwDecValues.indexOf(cur).coerceAtLeast(0)
                    hwDecLabels[idx]
                },
                onClick = { host, _ ->
                    val curIdx = hwDecValues.indexOf(PrefManager.getVal<String>(PrefName.MpvHwDec)).coerceAtLeast(0)
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.hardware_decoding)
                        singleChoiceItems(hwDecLabels.toTypedArray(), curIdx) { which ->
                            PrefManager.setVal(PrefName.MpvHwDec, hwDecValues[which])
                            host.refresh()
                        }
                        show()
                    }
                },
            ),
            switchRow(PrefName.MpvGpuNext, R.string.gpu_next, R.string.gpu_next_desc, R.drawable.ic_round_movie_filter_24),
            choiceRow(
                key = PrefName.MpvDebanding,
                title = R.string.debanding,
                icon = R.drawable.ic_round_filter_24,
                summary = R.string.debanding_desc,
                labels = { listOf("None", "CPU", "GPU") },
            ),
            switchRow(PrefName.MpvYuv420p, R.string.mpv_yuv420p, R.string.mpv_yuv420p_desc, R.drawable.ic_round_color_24),
        )),
        Section(R.string.settings_section_filters, listOf(
            sliderRow(PrefName.MpvBrightness, R.string.mpv_brightness, icon = R.drawable.ic_round_brightness_medium_24, from = -100f, to = 100f, step = 5f, format = { _, v -> "${v.toInt()}" }),
            sliderRow(PrefName.MpvContrast, R.string.mpv_contrast, icon = R.drawable.ic_round_brightness_4_24, from = -100f, to = 100f, step = 5f, format = { _, v -> "${v.toInt()}" }),
            sliderRow(PrefName.MpvSaturation, R.string.mpv_saturation, icon = R.drawable.ic_round_color_24, from = -100f, to = 100f, step = 5f, format = { _, v -> "${v.toInt()}" }),
            sliderRow(PrefName.MpvGamma, R.string.mpv_gamma, icon = R.drawable.ic_round_brightness_high_24, from = -100f, to = 100f, step = 5f, format = { _, v -> "${v.toInt()}" }),
            sliderRow(PrefName.MpvHue, R.string.mpv_hue, icon = R.drawable.ic_round_color_picker_24, from = -100f, to = 100f, step = 5f, format = { _, v -> "${v.toInt()}" }),
        )),
    )
}

object PlayerSubtitlesScreen : SettingsScreen(
    id = "player_subtitles",
    title = R.string.pref_player_subtitle,
    icon = R.drawable.ic_round_subtitles_24,
    summary = R.string.pref_player_subtitle_summary,
) {
    override val activity get() = PlayerSubtitlesSettingsActivity::class.java

    private val subLanguages = listOf(
        "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
        "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
        "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian", "Polish",
        "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish", "Turkish",
        "Ukrainian", "Urdu", "Vietnamese",
    )
    private val assOverrideValues = listOf("yes", "force", "scale", "no")
    private val assOverrideLabels = listOf("Yes", "Force", "Scale", "No")
    private val outlineTypes = listOf("Outline", "Shine", "Drop Shadow", "None")
    private val fonts = listOf("Poppins Semi Bold", "Poppins Bold", "Poppins", "Poppins Thin", "Century Gothic", "Levenim MT Bold", "Blocky")

    private val allOnlineProviders = arrayOf("Wyzie", "Stremio", "SubSource", "OpenSubtitles")
    private val allOnlineProviderLabels = arrayOf("Wyzie", "Stremio OpenSubtitles", "SubSource", "OpenSubtitles REST")
    private val allOnlineLangCodes = arrayOf("en", "ar", "pt", "es", "id", "fr", "ru", "zh", "ja", "tr", "it", "de", "pl", "th", "vi", "ko")
    private val allOnlineLangNames = arrayOf(
        "English", "Arabic", "Portuguese", "Spanish", "Indonesian", "French", "Russian", "Chinese",
        "Japanese", "Turkish", "Italian", "German", "Polish", "Thai", "Vietnamese", "Korean",
    )

    override fun sections() = listOf(
        Section(R.string.subtitles, listOf(
            switchRow(PrefName.Subtitles, R.string.subtitle_toggle, icon = R.drawable.ic_round_subtitles_24, refresh = true),
            choiceRow(
                key = PrefName.SubLanguage,
                title = R.string.subtitle_langauge,
                icon = R.drawable.ic_round_translate_24,
                labels = { subLanguages },
                enabled = { PrefManager.getVal(PrefName.Subtitles) },
            ),
            actionRow(
                id = PrefName.MpvSubAssOverride.name,
                title = R.string.override_ass_subtitles,
                summary = R.string.override_ass_subtitles_desc,
                icon = R.drawable.ic_round_format_text_24,
                keys = listOf(PrefName.MpvSubAssOverride),
                enabled = { PrefManager.getVal(PrefName.Subtitles) },
                current = {
                    val cur = PrefManager.getVal<String>(PrefName.MpvSubAssOverride)
                    assOverrideLabels[assOverrideValues.indexOf(cur).coerceAtLeast(0)]
                },
                onClick = { host, _ ->
                    val idx = assOverrideValues.indexOf(PrefManager.getVal<String>(PrefName.MpvSubAssOverride)).coerceAtLeast(0)
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.override_ass_subtitles)
                        singleChoiceItems(assOverrideLabels.toTypedArray(), idx) { which ->
                            PrefManager.setVal(PrefName.MpvSubAssOverride, assOverrideValues[which])
                            host.refresh()
                        }
                        show()
                    }
                },
            ),
            switchRow(
                key = PrefName.OnlineSubtitlesEnabled,
                title = R.string.online_subtitles_toggle,
                icon = R.drawable.ic_globe_24,
                refresh = true,
            ),
            actionRow(
                id = PrefName.OnlineSubtitleProviders.name,
                title = R.string.online_subtitles_providers,
                icon = R.drawable.ic_round_dns_24,
                keys = listOf(PrefName.OnlineSubtitleProviders),
                enabled = { PrefManager.getVal(PrefName.OnlineSubtitlesEnabled) },
                current = { PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders).joinToString(", ") },
                onClick = { host, _ ->
                    val current = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
                    val checked = BooleanArray(allOnlineProviders.size) { current.contains(allOnlineProviders[it]) }
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.online_subtitles_providers)
                        multiChoiceItems(allOnlineProviderLabels, checked) { updated ->
                            val selected = buildSet {
                                updated.forEachIndexed { idx, on -> if (on) add(allOnlineProviders[idx]) }
                            }
                            PrefManager.setVal(PrefName.OnlineSubtitleProviders, selected)
                            host.refresh()
                        }
                        setPosButton(android.R.string.ok, null)
                        show()
                    }
                },
            ),
            actionRow(
                id = PrefName.OnlineSubtitleLanguages.name,
                title = R.string.online_subtitles_languages,
                icon = R.drawable.ic_round_translate_variant_24,
                keys = listOf(PrefName.OnlineSubtitleLanguages),
                enabled = { PrefManager.getVal(PrefName.OnlineSubtitlesEnabled) },
                current = { PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages).joinToString(", ") },
                onClick = { host, _ ->
                    val current = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)
                    val checked = BooleanArray(allOnlineLangCodes.size) { current.contains(allOnlineLangCodes[it]) }
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.online_subtitles_languages)
                        multiChoiceItems(allOnlineLangNames, checked) { updated ->
                            val selected = buildSet {
                                updated.forEachIndexed { idx, on -> if (on) add(allOnlineLangCodes[idx]) }
                            }
                            PrefManager.setVal(PrefName.OnlineSubtitleLanguages, selected)
                            host.refresh()
                        }
                        setPosButton(android.R.string.ok, null)
                        show()
                    }
                },
            ),
        )),
        Section(R.string.settings_section_appearance, listOf(
            choiceRow(PrefName.Font, R.string.sub_font_select, R.drawable.ic_round_format_text_24, labels = { fonts }, enabled = { PrefManager.getVal(PrefName.Subtitles) }),
            sliderRow(PrefName.FontSize, R.string.subtitle_font_size, icon = R.drawable.ic_round_font_size_24, from = 10f, to = 72f, step = 2f, enabled = { PrefManager.getVal(PrefName.Subtitles) }, format = { _, v -> "${v.toInt()}" }),
            choiceRow(PrefName.Outline, R.string.secondary_sub_outline_type_select, R.drawable.ic_round_format_text_24, labels = { outlineTypes }, enabled = { PrefManager.getVal(PrefName.Subtitles) }),
            sliderRow(PrefName.SubAlpha, R.string.sub_alpha, icon = R.drawable.ic_round_brightness_medium_24, from = 0.1f, to = 1.0f, step = 0.1f, enabled = { PrefManager.getVal(PrefName.Subtitles) }, format = { _, v -> "${(v * 100f).roundToInt()}%" }),
            sliderRow(PrefName.SubStroke, R.string.textview_sub_stroke, icon = R.drawable.ic_round_straighten_24, from = 0f, to = 16f, step = 1f, enabled = { PrefManager.getVal(PrefName.Subtitles) }, format = { _, v -> "${v.toInt()}" }),
            sliderRow(PrefName.SubBottomMargin, R.string.textview_sub_bottom_margin, icon = R.drawable.ic_round_straighten_24, from = 0f, to = 50f, step = 1f, enabled = { PrefManager.getVal(PrefName.Subtitles) }, format = { _, v -> "${v.toInt()}" }),
            colorRow(PrefName.PrimaryColor, R.string.primary_sub_color_select, R.string.primary_sub_color),
            colorRow(PrefName.SecondaryColor, R.string.secondary_sub_color_select, R.string.outline_sub_color),
            colorRow(PrefName.SubBackground, R.string.sub_background_color_select, R.string.sub_background_color_select),
            colorRow(PrefName.SubWindow, R.string.sub_window_color_select, R.string.sub_window_color_select),
        )),
        Section(R.string.settings_section_timing, listOf(
            stepperRow(PrefName.MpvSubDelay, R.string.mpv_sub_delay, icon = R.drawable.ic_round_history_24, from = -5000f, to = 5000f, step = 100f, format = { _, v -> "${v.toInt()} ms" }),
            stepperRow(PrefName.MpvSubSpeed, R.string.mpv_sub_speed, icon = R.drawable.ic_round_speed_24, from = 0.5f, to = 2.0f, step = 0.05f, format = { _, v -> "${"%.2f".format(v)}x" }),
            stepperRow(PrefName.MpvSubSecondaryDelay, R.string.mpv_sub_secondary_delay, icon = R.drawable.ic_round_history_24, from = -5000f, to = 5000f, step = 100f, advanced = true, format = { _, v -> "${v.toInt()} ms" }),
        )),
    )

    private fun colorRow(key: PrefName, title: Int, dialogTitle: Int) = actionRow(
        id = key.name,
        title = title,
        icon = R.drawable.ic_round_color_picker_24,
        keys = listOf(key),
        enabled = { PrefManager.getVal(PrefName.Subtitles) },
        current = { "#%08X".format(PrefManager.getVal<Int>(key)) },
        onClick = { host, _ ->
            val act = host.activity as? PlayerCategoryActivity ?: return@actionRow
            act.pickColor(PrefManager.getVal(key), act.getString(dialogTitle)) { color ->
                PrefManager.setVal(key, color)
            }
        },
    )
}

object PlayerAudioScreen : SettingsScreen(
    id = "player_audio",
    title = R.string.pref_player_audio,
    icon = R.drawable.ic_round_volume_up_24,
    summary = R.string.pref_player_audio_summary,
) {
    override val activity get() = PlayerAudioSettingsActivity::class.java

    private val channelValues = listOf("auto-safe", "mono", "stereo", "5.1,stereo")
    private val channelLabels = listOf("Auto-Safe", "Mono", "Stereo", "5.1 Surround")

    override fun sections() = listOf(
        Section(R.string.pref_player_audio, listOf(
            switchRow(PrefName.MpvAudioPitchCorrection, R.string.pitch_correction, R.string.pitch_correction_desc, R.drawable.ic_round_audiotrack_24),
            actionRow(
                id = PrefName.MpvAudioChannels.name,
                title = R.string.audio_channels,
                summary = R.string.audio_channels_desc,
                icon = R.drawable.ic_round_volume_up_24,
                keys = listOf(PrefName.MpvAudioChannels),
                current = {
                    val cur = PrefManager.getVal<String>(PrefName.MpvAudioChannels)
                    channelLabels[channelValues.indexOf(cur).coerceAtLeast(0)]
                },
                onClick = { host, _ ->
                    val idx = channelValues.indexOf(PrefManager.getVal<String>(PrefName.MpvAudioChannels)).coerceAtLeast(0)
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.audio_channels)
                        singleChoiceItems(channelLabels.toTypedArray(), idx) { which ->
                            PrefManager.setVal(PrefName.MpvAudioChannels, channelValues[which])
                            host.refresh()
                        }
                        show()
                    }
                },
            ),
            sliderRow(
                key = PrefName.MpvVolumeBoostCap,
                title = R.string.volume_boost,
                summary = R.string.volume_boost_desc,
                icon = R.drawable.ic_round_volume_up_24,
                from = 100f,
                to = 200f,
                step = 10f,
                format = { _, v -> "${v.toInt()}%" },
            ),
            stepperRow(
                key = PrefName.MpvAudioDelay,
                title = R.string.mpv_audio_delay,
                icon = R.drawable.ic_round_history_24,
                from = -5000f,
                to = 5000f,
                step = 50f,
                format = { _, v -> "${v.toInt()} ms" },
            ),
        )),
    )
}

object PlayerCustomButtonsScreen : SettingsScreen(
    id = "player_custom_buttons",
    title = R.string.pref_player_custom_button,
    icon = R.drawable.ic_round_smart_button_24,
    summary = R.string.pref_player_custom_button_summary,
) {
    override val activity get() = PlayerCustomButtonsSettingsActivity::class.java

    private val allButtons = arrayOf("aspect_ratio", "screenshot", "stats", "audio_track", "sub_track", "pip", "speed", "lock")
    private val buttonLabels = arrayOf("Aspect Ratio", "Screenshot", "Stats for Nerds", "Audio Track", "Subtitle Track", "Picture-in-Picture", "Playback Speed", "Screen Lock")

    override fun sections() = listOf(
        Section(0, listOf(
            actionRow(
                id = PrefName.MpvCustomButtons.name,
                title = R.string.mpv_custom_buttons_configure,
                summary = R.string.pref_player_custom_button_summary,
                icon = R.drawable.ic_round_smart_button_24,
                keys = listOf(PrefName.MpvCustomButtons),
                current = { PrefManager.getVal<Set<String>>(PrefName.MpvCustomButtons).joinToString(", ") },
                onClick = { host, _ ->
                    val current = PrefManager.getVal<Set<String>>(PrefName.MpvCustomButtons)
                    val checked = BooleanArray(allButtons.size) { current.contains(allButtons[it]) }
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.mpv_custom_buttons_configure)
                        multiChoiceItems(buttonLabels, checked) { updated ->
                            val selected = buildSet {
                                updated.forEachIndexed { idx, on -> if (on) add(allButtons[idx]) }
                            }
                            PrefManager.setVal(PrefName.MpvCustomButtons, selected)
                            host.refresh()
                        }
                        setPosButton(android.R.string.ok, null)
                        show()
                    }
                },
            ),
        )),
    )
}

object PlayerScriptEditorScreen : SettingsScreen(
    id = "player_script_editor",
    title = R.string.pref_player_editor,
    icon = R.drawable.ic_round_edit_note_24,
    summary = R.string.pref_player_editor_summary,
) {
    override val activity get() = PlayerScriptEditorSettingsActivity::class.java

    override fun sections() = listOf(
        Section(0, listOf(
            actionRow(
                id = PrefName.MpvConf.name,
                title = R.string.mpv_conf_editor,
                summary = R.string.mpv_conf_editor_desc,
                icon = R.drawable.ic_round_edit_note_24,
                keys = listOf(PrefName.MpvConf),
                current = { PrefManager.getVal<String>(PrefName.MpvConf).ifBlank { "Empty" }.lines().firstOrNull() },
                onClick = { host, _ -> showConfigEditor(host, R.string.mpv_conf_editor, PrefName.MpvConf, "# Example:\n# deband=yes") },
            ),
            actionRow(
                id = PrefName.MpvInput.name,
                title = R.string.mpv_input_editor,
                summary = R.string.mpv_input_editor_desc,
                icon = R.drawable.ic_round_edit_note_24,
                keys = listOf(PrefName.MpvInput),
                current = { PrefManager.getVal<String>(PrefName.MpvInput).ifBlank { "Empty" }.lines().firstOrNull() },
                onClick = { host, _ -> showConfigEditor(host, R.string.mpv_input_editor, PrefName.MpvInput, "# Example:\n# SPACE cycle pause") },
            ),
        )),
    )

    private fun showConfigEditor(host: SettingsHost, titleRes: Int, pref: PrefName, hintText: String) {
        val ctx = host.activity
        val input = EditText(ctx).apply {
            setText(PrefManager.getVal<String>(pref))
            hint = hintText
            minLines = 6
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val container = FrameLayout(ctx)
        val margin = (20 * ctx.resources.displayMetrics.density).toInt()
        input.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = margin
            rightMargin = margin
            topMargin = (8 * ctx.resources.displayMetrics.density).toInt()
            bottomMargin = (8 * ctx.resources.displayMetrics.density).toInt()
        }
        container.addView(input)
        ctx.customAlertDialog().apply {
            setTitle(titleRes)
            setCustomView(container)
            setPosButton(R.string.ok, onClick = {
                PrefManager.setVal(pref, input.text.toString())
                host.refresh()
            })
            setNegButton(R.string.cancel)
            show()
        }
    }
}

object PlayerAdvancedScreen : SettingsScreen(
    id = "player_advanced",
    title = R.string.pref_player_advanced,
    icon = R.drawable.ic_round_settings_24,
    summary = R.string.pref_player_advanced_summary,
) {
    override val activity get() = PlayerAdvancedSettingsActivity::class.java

    private val cacheSizesMb = listOf(32, 64, 128, 256)

    override fun sections() = listOf(
        Section(0, listOf(
            stepsRow(
                id = PrefName.MpvDemuxerCacheMb.name,
                title = R.string.mpv_demuxer_cache,
                count = cacheSizesMb.size,
                label = { _, idx -> "${cacheSizesMb[idx]} MB" },
                get = { cacheSizesMb.indexOf(PrefManager.getVal(PrefName.MpvDemuxerCacheMb)).coerceAtLeast(1) },
                icon = R.drawable.ic_round_sd_card_24,
                keys = listOf(PrefName.MpvDemuxerCacheMb),
                set = { _, idx -> PrefManager.setVal(PrefName.MpvDemuxerCacheMb, cacheSizesMb[idx]) },
            ),
            choiceRow(
                key = PrefName.MpvStatsPage,
                title = R.string.mpv_stats_page,
                icon = R.drawable.ic_round_area_chart_24,
                labels = { listOf("Off", "Page 1 (Summary)", "Page 2 (Frame Timings)", "Page 3 (Cache / IO)") },
            ),
            actionRow(
                id = "player_advanced_mpv_conf",
                title = R.string.mpv_conf_editor,
                summary = R.string.mpv_conf_editor_desc,
                icon = R.drawable.ic_round_edit_note_24,
                opensScreen = true,
                onClick = { host, _ ->
                    host.activity.startActivity(Intent(host.activity, PlayerScriptEditorSettingsActivity::class.java))
                },
            ),
        )),
    )
}
