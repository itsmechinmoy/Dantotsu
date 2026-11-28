package ani.dantotsu.settings

import android.content.Intent
import android.widget.TextView
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.client
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.restartApp
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsAboutActivity : SettingsScreenActivity() {
    override val screen get() = AboutScreen
}

object AboutScreen : SettingsScreen("about", R.string.about, R.drawable.ic_round_info_24, R.string.about_desc) {

    override val activity get() = SettingsAboutActivity::class.java

    private val notFdroid = { !BuildConfig.FLAVOR.contains("fdroid") }

    override fun sections() = listOf(
        Section(R.string.info, listOf(
            actionRow("faq", R.string.faq, R.string.faq_desc, R.drawable.ic_round_help_24, opensScreen = true,
                onClick = { host, _ -> host.activity.startActivity(Intent(host.activity, FAQActivity::class.java)) }),
            switchRow(PrefName.CheckUpdate, R.string.check_app_updates, R.string.check_app_updates_desc,
                R.drawable.ic_round_new_releases_24, visible = notFdroid),
            actionRow("devs", R.string.devs, R.string.devs_desc, R.drawable.ic_round_accessible_forward_24,
                onClick = { host, _ -> DevelopersDialogFragment().show(host.activity.supportFragmentManager, "dialog") }),
            actionRow("forks", R.string.forks, R.string.forks_desc, R.drawable.ic_round_restaurant_24,
                onClick = { host, _ -> ForksDialogFragment().show(host.activity.supportFragmentManager, "dialog") }),
        )),
        Section(R.string.settings_section_diagnostics, listOf(
            switchRow(PrefName.SharedUserID, R.string.share_username_in_crash_reports,
                R.string.share_username_in_crash_reports_desc, R.drawable.ic_round_search_24, visible = notFdroid),
            switchRow(PrefName.LogToFile, R.string.log_to_file, R.string.logging_warning,
                R.drawable.ic_round_edit_note_24,
                trailing = { host, box -> shareLogButton(host, box) },
                onChange = { host, _ ->
                    Logger.clearLog()
                    host.activity.restartApp()
                }),
            switchRow(PrefName.TrackMemoryLeaks, R.string.track_memory_leaks, R.string.track_memory_leaks_desc,
                R.drawable.ic_round_history_24),
        )),
        Section(R.string.settings_section_legal, listOf(
            actionRow("disclaimer", R.string.disclaimer, R.string.disclaimer_desc, R.drawable.ic_round_info_24,
                onClick = { host, _ -> showDisclaimer(host) }),
            actionRow("privacy_policy", R.string.privacy_policy, R.string.privacy_policy_desc, R.drawable.ic_incognito_24,
                onClick = { host, _ -> showPrivacyPolicy(host) }),
        )),
    )

    private fun shareLogButton(host: SettingsHost, box: android.view.ViewGroup) {
        val context = host.activity
        val size = (48 * context.resources.displayMetrics.density).toInt()
        box.addView(android.widget.ImageButton(context).apply {
            setImageResource(R.drawable.ic_round_share_24)
            contentDescription = context.getString(R.string.share_log)
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attrs.getDrawable(0)
            attrs.recycle()
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setOnClickListener { Logger.shareLog(context) }
        }, android.view.ViewGroup.LayoutParams(size, size))
    }

    private fun showDisclaimer(host: SettingsHost) = with(host.activity) {
        val text = TextView(this)
        text.setText(R.string.full_disclaimer)
        // Strings from the activity: the dialog fragment has no context until it is shown.
        val title = getString(R.string.disclaimer)
        val close = getString(R.string.close)
        CustomBottomDialog.newInstance().apply {
            setTitleText(title)
            addView(text)
            setNegativeButton(close) { dismiss() }
            show(supportFragmentManager, "dialog")
        }
    }

    private fun showPrivacyPolicy(host: SettingsHost): Unit = with(host.activity) {
        val text = TextView(this)
        val pPLink = "https://raw.githubusercontent.com/rebelonion/Dantotsu/main/privacy_policy.md"
        val backup = "https://gcore.jsdelivr.net/gh/rebelonion/dantotsu/privacy_policy.md"
        text.text = getString(R.string.loading)
        val markWon = try {
            buildMarkwon(this, false)
        } catch (e: IllegalArgumentException) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val res = try {
                val out = client.get(pPLink)
                if (out.code != 200) client.get(backup).text else out.text
            } catch (e: Exception) {
                getString(R.string.failed_to_load)
            }
            runOnUiThread { markWon.setMarkdown(text, res) }
        }
        val title = getString(R.string.privacy_policy)
        val close = getString(R.string.close)
        CustomBottomDialog.newInstance().apply {
            setTitleText(title)
            addView(text)
            setNegativeButton(close) { dismiss() }
            show(supportFragmentManager, "dialog")
        }
    }
}
