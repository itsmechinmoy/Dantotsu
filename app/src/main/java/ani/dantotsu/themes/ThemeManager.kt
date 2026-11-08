package ani.dantotsu.themes

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions


class ThemeManager(private val context: Activity) {
    fun applyTheme(fromImage: Bitmap? = null) {
        val useOLED = PrefManager.getVal(PrefName.UseOLED) && isDarkThemeActive(context)
        val useCustomTheme: Boolean = PrefManager.getVal(PrefName.UseCustomTheme)
        val customTheme: Int = PrefManager.getVal(PrefName.CustomThemeInt)
        val useSource: Boolean = PrefManager.getVal(PrefName.UseSourceTheme)
        val useMaterial: Boolean = PrefManager.getVal(PrefName.UseMaterialYou)
        if (!applyColors(useSource, useCustomTheme, customTheme, useMaterial, useOLED, fromImage)) return
        val theme: String = PrefManager.getVal(PrefName.Theme)

        val themeToApply = resolveThemeRes(theme, useOLED)
        val window = context.window
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = 0x00000000
        context.setTheme(themeToApply)
        val useSystemFont = PrefManager.getVal<Boolean>(PrefName.UseSystemFont)
        if (useSystemFont) {
            context.theme.applyStyle(R.style.ThemeOverlay_Dantotsu_SystemFont, true)
        }
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    fun setWindowFlag(activity: Activity, bits: Int, on: Boolean) {
        val win: Window = activity.window
        val winParams: WindowManager.LayoutParams = win.attributes
        if (on) {
            winParams.flags = winParams.flags or bits
        } else {
            winParams.flags = winParams.flags and bits.inv()
        }
        win.attributes = winParams
    }

    private fun applyDynamicColors(
        useMaterialYou: Boolean,
        context: Context,
        useOLED: Boolean,
        bitmap: Bitmap? = null,
        useCustom: Int? = null
    ): Boolean {
        val builder = DynamicColorsOptions.Builder()
        var needMaterial = true

        // Set content-based source if a bitmap is provided
        if (bitmap != null) {
            builder.setContentBasedSource(bitmap)
            needMaterial = false
        } else if (useCustom != null) {
            builder.setContentBasedSource(useCustom)
            needMaterial = false
        }

        if (useOLED) {
            builder.setThemeOverlay(R.style.AppTheme_Amoled)
        }
        val useSystemFont = PrefManager.getVal<Boolean>(PrefName.UseSystemFont)
        if (useSystemFont) {
            builder.setThemeOverlay(R.style.ThemeOverlay_Dantotsu_SystemFont)
        }
        if (needMaterial && !useMaterialYou) return true

        // Build the options
        val options = builder.build()

        // Apply the dynamic colors to the activity
        val activity = context as Activity
        DynamicColors.applyToActivityIfAvailable(activity, options)

        if (useOLED) {
            val options2 = DynamicColorsOptions.Builder()
                .setThemeOverlay(R.style.AppTheme_Amoled)
                .build()
            DynamicColors.applyToActivityIfAvailable(activity, options2)
        }
        if (useSystemFont) {
            val optionsFont = DynamicColorsOptions.Builder()
                .setThemeOverlay(R.style.ThemeOverlay_Dantotsu_SystemFont)
                .build()
            DynamicColors.applyToActivityIfAvailable(activity, optionsFont)
        }

        return false
    }

    private fun isDarkThemeActive(context: Context): Boolean {
        return when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }


    companion object {
        enum class Theme(val theme: String) {
            BLUE("BLUE"),
            GREEN("GREEN"),
            PURPLE("PURPLE"),
            PINK("PINK"),
            ORIAX("ORIAX"),
            SAIKOU("SAIKOU"),
            RED("RED"),
            LAVENDER("LAVENDER"),
            OCEAN("OCEAN"),
            MONOCHROME("MONOCHROME (BETA)");

            companion object {
                fun fromString(value: String): Theme {
                    return entries.find { it.theme == value } ?: PURPLE
                }
            }
        }
    }
    private fun applyColors(
        useSource: Boolean,
        useCustomTheme: Boolean,
        customTheme: Int,
        useMaterial: Boolean,
        useOLED: Boolean,
        fromImage: Bitmap?
    ): Boolean {
        if (useSource) {
            val returnedEarly = applyDynamicColors(
                useMaterial,
                context,
                useOLED,
                fromImage,
                useCustom = if (useCustomTheme) customTheme else null
            )
            return returnedEarly
        } else if (useCustomTheme) {
            val returnedEarly =
                applyDynamicColors(useMaterial, context, useOLED, useCustom = customTheme)
            return returnedEarly
        } else {
            val returnedEarly = applyDynamicColors(useMaterial, context, useOLED, useCustom = null)
            return returnedEarly
        }
        return true
    }

    private val themeMap = mapOf(
        "BLUE" to (R.style.Theme_Dantotsu_Blue to R.style.Theme_Dantotsu_BlueOLED),
        "GREEN" to (R.style.Theme_Dantotsu_Green to R.style.Theme_Dantotsu_GreenOLED),
        "PURPLE" to (R.style.Theme_Dantotsu_Purple to R.style.Theme_Dantotsu_PurpleOLED),
        "PINK" to (R.style.Theme_Dantotsu_Pink to R.style.Theme_Dantotsu_PinkOLED),
        "ORIAX" to (R.style.Theme_Dantotsu_Oriax to R.style.Theme_Dantotsu_OriaxOLED),
        "SAIKOU" to (R.style.Theme_Dantotsu_Saikou to R.style.Theme_Dantotsu_SaikouOLED),
        "RED" to (R.style.Theme_Dantotsu_Red to R.style.Theme_Dantotsu_RedOLED),
        "LAVENDER" to (R.style.Theme_Dantotsu_Lavender to R.style.Theme_Dantotsu_LavenderOLED),
        "OCEAN" to (R.style.Theme_Dantotsu_Ocean to R.style.Theme_Dantotsu_OceanOLED),
        "MONOCHROME (BETA)" to (R.style.Theme_Dantotsu_Monochrome to R.style.Theme_Dantotsu_MonochromeOLED),
    )

    private fun resolveThemeRes(theme: String, useOLED: Boolean): Int {
        val pair = themeMap[theme] ?: (R.style.Theme_Dantotsu_Purple to R.style.Theme_Dantotsu_PurpleOLED)
        return if (useOLED) pair.second else pair.first
    }

}
