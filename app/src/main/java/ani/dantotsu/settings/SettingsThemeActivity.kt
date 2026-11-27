package ani.dantotsu.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.addCallback
import ani.dantotsu.R
import ani.dantotsu.reloadActivity
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.Segment
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.segmentedRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorDialog

/** Theme changes recreate this screen; back then restarts into Settings so the new theme applies. */
class SettingsThemeActivity : SettingsScreenActivity(), SimpleDialog.OnDialogResultListener {
    override val screen get() = ThemeScreen

    private var reload = PrefManager.getCustomVal("reload", true)

    override fun onScreenCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            if (reload) {
                val mainIntent = Intent.makeRestartActivityTask(
                    packageManager.getLaunchIntentForPackage(packageName)!!.component
                )
                val component = ComponentName(packageName, SettingsActivity::class.qualifiedName!!)
                try {
                    startActivity(Intent().setComponent(component))
                } catch (e: Exception) {
                    startActivity(mainIntent)
                }
                finishAndRemoveTask()
                reload = false
            } else {
                finish()
            }
        }
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE && dialogTag == COLOR_PICKER) {
            val color = extras.getInt(SimpleColorDialog.COLOR)
            PrefManager.setVal(PrefName.CustomThemeInt, color)
            Logger.log("Custom Theme: $color")
        }
        return true
    }

    fun reload() {
        PrefManager.setCustomVal("reload", true)
        Handler(Looper.getMainLooper()).postDelayed({
            reloadActivity()
            finishAndRemoveTask()
        }, 100)
    }

    companion object {
        const val COLOR_PICKER = "colorPicker"
    }
}

object ThemeScreen : SettingsScreen("theme", R.string.theme, R.drawable.ic_palette, R.string.theme_desc) {

    override val activity get() = SettingsThemeActivity::class.java

    private val sdkS = { Build.VERSION.SDK_INT > Build.VERSION_CODES.R }

    private fun reload(host: SettingsHost) = (host.activity as? SettingsThemeActivity)?.reload()

    private fun themeNames() = ThemeManager.Companion.Theme.entries.map {
        it.theme.substring(0, 1) + it.theme.substring(1).lowercase()
    }

    override fun sections() = listOf(
        Section(R.string.settings_section_appearance, listOf(
            segmentedRow(PrefName.DarkMode, R.string.theme_mode, R.drawable.ic_round_brightness_auto_24,
                options = listOf(
                    Segment(R.drawable.ic_round_brightness_auto_24, R.string.theme_mode_auto),
                    Segment(R.drawable.ic_round_brightness_high_24, R.string.theme_mode_light),
                    Segment(R.drawable.ic_round_brightness_4_24, R.string.theme_mode_dark),
                ),
                onChange = { host, mode ->
                    if (mode == 1) PrefManager.setVal(PrefName.UseOLED, false)
                    reload(host)
                }),
            Row(
                id = PrefName.Theme.name, title = R.string.theme, icon = R.drawable.ic_palette,
                keys = listOf(PrefName.Theme),
                kind = Kind.Choice(
                    labels = { themeNames() },
                    get = {
                        val current = PrefManager.getVal<String>(PrefName.Theme)
                        ThemeManager.Companion.Theme.entries.indexOfFirst { it.theme == current }
                    },
                    set = { host, i ->
                        PrefManager.setVal(PrefName.Theme, ThemeManager.Companion.Theme.entries[i].theme)
                        reload(host)
                    },
                ),
            ),
            switchRow(PrefName.UseOLED, R.string.oled_theme_variant, R.string.oled_theme_variant_desc,
                R.drawable.ic_round_brightness_4_24, onChange = { host, _ -> reload(host) }),
            switchRow(PrefName.UseSystemFont, R.string.use_system_font, R.string.use_system_font_desc,
                R.drawable.ic_round_font_size_24, onChange = { host, _ -> reload(host) }),
        )),
        Section(R.string.settings_section_dynamic_colors, listOf(
            switchRow(PrefName.UseMaterialYou, R.string.use_material_you, R.string.use_material_you_desc,
                R.drawable.ic_round_new_releases_24, visible = sdkS,
                onChange = { host, on ->
                    if (on) PrefManager.setVal(PrefName.UseCustomTheme, false)
                    reload(host)
                }),
            switchRow(PrefName.UseSourceTheme, R.string.use_unique_theme_for_each_item,
                R.string.use_unique_theme_for_each_item_desc, R.drawable.ic_palette, visible = sdkS),
            switchRow(PrefName.UseCustomTheme, R.string.use_custom_theme, R.string.use_custom_theme_desc,
                R.drawable.ic_palette, visible = sdkS,
                onChange = { host, on ->
                    if (on) PrefManager.setVal(PrefName.UseMaterialYou, false)
                    reload(host)
                }),
            actionRow(PrefName.CustomThemeInt.name, R.string.color_picker, R.string.color_picker_desc,
                R.drawable.ic_palette, visible = sdkS, keys = listOf(PrefName.CustomThemeInt),
                onClick = { host, _ -> pickColor(host) }),
        )),
    )

    private fun pickColor(host: SettingsHost) {
        val activity = host.activity
        val originalColor: Int = PrefManager.getVal(PrefName.CustomThemeInt)

        class CustomColorDialog : SimpleColorDialog() {
            override fun onPositiveButtonClick() {
                (activity as? SettingsThemeActivity)?.reload()
                super.onPositiveButtonClick()
            }
        }

        CustomColorDialog().title(R.string.custom_theme)
            .colorPreset(originalColor)
            .colors(activity, SimpleColorDialog.MATERIAL_COLOR_PALLET)
            .allowCustom(true).showOutline(0x46000000).gridNumColumn(5)
            .choiceMode(SimpleColorDialog.SINGLE_CHOICE).neg()
            .show(activity, SettingsThemeActivity.COLOR_PICKER)
    }
}
