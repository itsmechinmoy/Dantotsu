package ani.dantotsu.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.Comick
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * Dartotsu-style bottom sheet for switching active browsing service (AniList / MyAnimeList)
 * and jumping straight into each service's settings.
 */
object ServiceSwitcherDialog {

    private data class ServiceOption(
        val name: String,
        val subtitle: String,
        val iconRes: Int,
        val selected: Boolean,
        val onSelect: (() -> Unit)?,
        val onSettings: () -> Unit,
    )

    fun show(activity: FragmentActivity) {
        val isRescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        val dialog = CustomBottomDialog().apply {
            setTitleText(activity.getString(R.string.select_media_service))
        }

        val options = buildOptions(activity, isRescueMode) {
            dialog.dismiss()
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(activity, 12)
            val padV = dp(activity, 6)
            setPadding(padH, padV, padH, padV)
            options.forEach { option ->
                addView(buildOptionCard(activity, option))
            }
        }

        dialog.addView(container)
        dialog.show(activity.supportFragmentManager, "service_switcher")
    }

    private fun buildOptions(
        activity: FragmentActivity,
        isRescueMode: Boolean,
        dismiss: () -> Unit,
    ): List<ServiceOption> {
        fun switchToRescue(enable: Boolean) {
            dismiss()
            if (isRescueMode == enable) return
            PrefManager.setVal(PrefName.RescueMode, enable)
            when (activity) {
                is MainActivity -> activity.serviceChanged()
                else -> startMainActivity(activity)
            }
        }

        fun openAccountSettings() {
            dismiss()
            activity.startActivity(Intent(activity, SettingsAccountActivity::class.java))
        }

        fun subtitleFor(username: String?, loggedIn: Boolean, trackingOnly: Boolean): String {
            val base = when {
                !username.isNullOrBlank() -> username
                loggedIn -> activity.getString(R.string.signed_in)
                else -> activity.getString(R.string.not_signed_in)
            }
            return if (trackingOnly) {
                "$base • ${activity.getString(R.string.tracking_only)}"
            } else {
                base
            }
        }

        return listOf(
            ServiceOption(
                name = activity.getString(R.string.anilist),
                subtitle = subtitleFor(Anilist.username, Anilist.token != null, trackingOnly = false),
                iconRes = R.drawable.ic_anilist,
                selected = !isRescueMode,
                onSelect = { switchToRescue(false) },
                onSettings = {
                    dismiss()
                    if (Anilist.token != null) {
                        activity.startActivity(Intent(activity, AnilistSettingsActivity::class.java))
                    } else {
                        activity.startActivity(Intent(activity, SettingsAccountActivity::class.java))
                    }
                },
            ),
            ServiceOption(
                name = activity.getString(R.string.myanimelist),
                subtitle = subtitleFor(MAL.username, MAL.token != null, trackingOnly = false),
                iconRes = R.drawable.ic_myanimelist,
                selected = isRescueMode,
                onSelect = { switchToRescue(true) },
                onSettings = ::openAccountSettings,
            ),
            ServiceOption(
                name = activity.getString(R.string.mangabaka),
                subtitle = subtitleFor(MangaBaka.username, MangaBaka.token != null, trackingOnly = true),
                iconRes = R.drawable.ic_mangabaka,
                selected = false,
                onSelect = {
                    snackString(activity.getString(R.string.tracking_only_hint, activity.getString(R.string.mangabaka)))
                    openAccountSettings()
                },
                onSettings = ::openAccountSettings,
            ),
            ServiceOption(
                name = activity.getString(R.string.comick),
                subtitle = subtitleFor(Comick.username, Comick.token != null, trackingOnly = true),
                iconRes = R.drawable.ic_comick,
                selected = false,
                onSelect = {
                    snackString(activity.getString(R.string.tracking_only_hint, activity.getString(R.string.comick)))
                    openAccountSettings()
                },
                onSettings = ::openAccountSettings,
            ),
        )
    }

    private fun buildOptionCard(activity: FragmentActivity, option: ServiceOption): MaterialCardView {
        val bgColor = if (option.selected) {
            MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimaryContainer, 0)
        } else {
            MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0)
        }
        val fgColor = if (option.selected) {
            MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimaryContainer, 0)
        } else {
            MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0)
        }
        val primaryColor = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, fgColor)
        val mutedColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, fgColor)
        val boldFont = ResourcesCompat.getFont(activity, R.font.poppins_bold)
        val regularFont = ResourcesCompat.getFont(activity, R.font.poppins)

        val card = MaterialCardView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp(activity, 3), 0, dp(activity, 3))
            }
            radius = dp(activity, 18).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(bgColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { option.onSelect?.invoke() }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 64)
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 8), dp(activity, 10))
        }

        val icon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28)).apply {
                marginEnd = dp(activity, 14)
            }
            setImageResource(option.iconRes)
            imageTintList = ColorStateList.valueOf(if (option.selected) fgColor else primaryColor)
        }
        row.addView(icon)

        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(activity).apply {
            text = option.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(fgColor)
            typeface = boldFont ?: Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subView = TextView(activity).apply {
            text = option.subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (option.selected) fgColor else mutedColor)
            alpha = if (option.selected) 0.85f else 1f
            typeface = regularFont ?: Typeface.DEFAULT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        labels.addView(titleView)
        labels.addView(subView)
        row.addView(labels)

        if (option.selected) {
            val check = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)).apply {
                    marginEnd = dp(activity, 4)
                }
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(fgColor)
            }
            row.addView(check)
        }

        val borderlessRipple = TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        val settingsBtn = ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44))
            setBackgroundResource(borderlessRipple)
            setImageResource(R.drawable.ic_round_settings_24)
            imageTintList = ColorStateList.valueOf(if (option.selected) fgColor else mutedColor)
            contentDescription = activity.getString(R.string.service_settings)
            setOnClickListener { option.onSettings() }
        }
        row.addView(settingsBtn)

        card.addView(row)
        return card
    }

    private fun dp(activity: FragmentActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
