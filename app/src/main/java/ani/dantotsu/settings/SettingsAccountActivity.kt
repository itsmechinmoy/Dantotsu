package ani.dantotsu.settings

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.Comick
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ItemSettingsAccountsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.customRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

class SettingsAccountActivity : SettingsScreenActivity() {
    override val screen get() = AccountsScreen
    private var reloadAccountsCard: (() -> Unit)? = null

    /** On after a logout: back then restarts the main screen. */
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onScreenCreate(savedInstanceState: android.os.Bundle?) {
        onBackPressedDispatcher.addCallback(this, restartMainActivity)
    }

    override fun onResume() {
        super.onResume()
        reloadAccountsCard?.invoke()
    }

    /** Fills the account logins block (AniList card, MAL, Discord, MangaBaka, Comick). */
    internal fun bindAccounts(b: ItemSettingsAccountsBinding) {
        val context = this
        b.apply {
            settingsAccountHelp.setOnClickListener {
                CustomBottomDialog.newInstance().apply {
                    setTitleText(context.getString(R.string.account_help))
                    addView(
                        TextView(it.context).apply {
                            val markWon = Markwon.builder(it.context)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, context.getString(R.string.full_account_help))
                        }
                    )
                }.show(supportFragmentManager, "dialog")
            }

            fun reload() {
                if (Anilist.token != null) {
                    settingsAnilistLogin.setText(R.string.logout)
                    settingsAnilistLogin.contentDescription = getString(R.string.log_out_of, getString(R.string.anilist))
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        refresh()
                    }
                    settingsAnilistUsername.visibility = View.VISIBLE
                    settingsAnilistUsername.text = Anilist.username
                    settingsAnilistAvatar.loadImage(Anilist.avatar)
                    settingsAnilistAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val anilistLink = getString(
                            R.string.anilist_link,
                            PrefManager.getVal<String>(PrefName.AnilistUserName)
                        )
                        openLinkInBrowser(anilistLink)
                    }

                    if (Anilist.bg != null) {
                        settingsAnilistBanner.visibility = View.VISIBLE
                        settingsAnilistScrim.visibility = View.VISIBLE
                        settingsAnilistBanner.loadImage(Anilist.bg)
                    } else {
                        settingsAnilistBanner.visibility = View.GONE
                        settingsAnilistScrim.visibility = View.GONE
                    }
                
                    val daysLeft = Anilist.getTokenExpiryDays()
                    if (daysLeft != null) {
                        settingsAnilistTokenExpiry.visibility = View.VISIBLE
                        settingsAnilistTokenExpiry.text = when {
                            daysLeft <= 0 -> "Reconnect Now"
                            daysLeft == 1L -> "Reconnect in 1 day"
                            else -> "Reconnect in $daysLeft days"
                        }
                        settingsAnilistTokenExpiry.setOnClickListener {
                            Anilist.loginIntent(context)
                        }
                    } else {
                        settingsAnilistTokenExpiry.visibility = View.GONE
                    }

                    settingsMALLoginRequired.visibility = View.GONE
                    settingsMALLogin.visibility = View.VISIBLE
                    settingsMALUsername.visibility = View.VISIBLE

                    if (MAL.token != null) {
                        settingsMALLogin.setText(R.string.logout)
                        settingsMALLogin.contentDescription = getString(R.string.log_out_of, getString(R.string.myanimelist))
                        settingsMALLogin.setOnClickListener {
                            MAL.removeSavedToken()
                            restartMainActivity.isEnabled = true
                            refresh()
                        }
                        settingsMALUsername.visibility = View.VISIBLE
                        settingsMALUsername.text = MAL.username
                        settingsMALAvatar.loadImage(MAL.avatar)
                        settingsMALAvatar.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            openLinkInBrowser(getString(R.string.myanilist_link, MAL.username))
                        }
                    } else {
                        settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                        settingsMALUsername.visibility = View.GONE
                        settingsMALLogin.setText(R.string.login)
                        settingsMALLogin.contentDescription = getString(R.string.log_in_to, getString(R.string.myanimelist))
                        settingsMALLogin.setOnClickListener {
                            MAL.loginIntent(context)
                        }
                    }
                } else {
                    settingsAnilistAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsAnilistUsername.visibility = View.GONE
                    settingsAnilistTokenExpiry.visibility = View.GONE
                    settingsAnilistBanner.visibility = View.GONE
                    settingsAnilistScrim.visibility = View.GONE
                    settingsAnilistLogin.setText(R.string.login)
                    settingsAnilistLogin.contentDescription = getString(R.string.log_in_to, getString(R.string.anilist))
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.loginIntent(context)
                    }
                    settingsMALLoginRequired.visibility = View.VISIBLE
                    settingsMALLogin.visibility = View.GONE
                    settingsMALUsername.visibility = View.GONE
                }

                settingsDiscordAvatarContainer.visibility = View.GONE
                settingsDiscordUsername.visibility = View.VISIBLE
                if (RPCManager.isAvailable(context)) {
                    settingsDiscordUsername.setText(R.string.discord_rich_presence)
                    settingsDiscordLogin.visibility = View.GONE
                    settingsPresenceSwitcher.visibility = View.VISIBLE
                    settingsPresenceSwitcher.setImageResource(R.drawable.ic_round_settings_24)
                    settingsPresenceSwitcher.setOnClickListener {
                        DiscordDialogFragment().show(supportFragmentManager, "dialog")
                    }
                } else {
                    settingsDiscordUsername.setText(R.string.discord_app_required)
                    settingsPresenceSwitcher.visibility = View.GONE
                    settingsDiscordLogin.visibility = View.VISIBLE
                    settingsDiscordLogin.setText(R.string.get_discord)
                    settingsDiscordLogin.setOnClickListener {
                        openLinkInBrowser("https://play.google.com/store/apps/details?id=com.discord")
                    }
                }

                if (MangaBaka.token != null) {
                    settingsMangaBakaLogin.setText(R.string.logout)
                    settingsMangaBakaLogin.contentDescription = getString(R.string.log_out_of, getString(R.string.mangabaka))
                    settingsMangaBakaLogin.setOnClickListener {
                        MangaBaka.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        refresh()
                    }
                    settingsMangaBakaUsername.visibility = View.VISIBLE
                    settingsMangaBakaUsername.text = MangaBaka.username
                    settingsMangaBakaAvatar.loadImage(MangaBaka.avatar)
                } else {
                    settingsMangaBakaAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsMangaBakaUsername.visibility = View.GONE
                    settingsMangaBakaLogin.setText(R.string.login)
                    settingsMangaBakaLogin.contentDescription = getString(R.string.log_in_to, getString(R.string.mangabaka))
                    settingsMangaBakaLogin.setOnClickListener {
                        MangaBaka.loginIntent(context)
                    }
                }

                if (Comick.token != null) {
                    settingsComickLogin.setText(R.string.logout)
                    settingsComickLogin.contentDescription = getString(R.string.log_out_of, getString(R.string.comick))
                    settingsComickLogin.setOnClickListener {
                        Comick.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        refresh()
                    }
                    settingsComickUsername.visibility = View.VISIBLE
                    settingsComickUsername.text = Comick.username
                    settingsComickAvatar.loadImage(Comick.avatar)
                    settingsComickAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        openLinkInBrowser(getString(R.string.comick_link, Comick.username))
                    }
                } else {
                    settingsComickAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsComickUsername.visibility = View.GONE
                    settingsComickLogin.setText(R.string.login)
                    settingsComickLogin.contentDescription = getString(R.string.log_in_to, getString(R.string.comick))
                    settingsComickLogin.setOnClickListener {
                        Comick.loginIntent(context)
                    }
                }
            }
            reloadAccountsCard = { reload() }
            reload()
        }
    }
}

object AccountsScreen : SettingsScreen("accounts", R.string.accounts, R.drawable.ic_round_person_24, R.string.accounts_desc) {

    override val activity get() = SettingsAccountActivity::class.java

    private val loggedIn = { Anilist.token != null }

    override fun sections() = listOf(
        Section(0, listOf(
            customRow("accounts", R.string.accounts_cards, R.layout.item_settings_accounts,
                keywords = listOf(R.string.accounts_keywords)) { host, view ->
                (host.activity as SettingsAccountActivity).bindAccounts(ItemSettingsAccountsBinding.bind(view))
            },
        )),
        // Signed out, every row in this section is hidden, so ScreenItems drops the heading too.
        Section(R.string.settings_section_preferences, listOf(
            switchRow(PrefName.rpcEnabled, R.string.enable_rpc, R.string.enable_rpc_desc, R.drawable.interests_24,
                visible = { currContext()?.let { RPCManager.isAvailable(it) } == true }),
            actionRow("anilist_settings", R.string.anilist_settings, R.string.alsettings_desc, R.drawable.ic_anilist,
                opensScreen = true, visible = loggedIn,
                // AniList's own settings live on that screen; search finds them through this row.
                keywords = listOf(R.string.airing_notifications, R.string.display_adult_content,
                    R.string.edit_bio, R.string.restrict_messages),
                onClick = { host, _ ->
                    host.activity.lifecycleScope.launch {
                        Anilist.query.getUserData()
                        host.activity.startActivity(Intent(host.activity, AnilistSettingsActivity::class.java))
                    }
                }),
            Row(
                id = PrefName.CommentsEnabled.name, title = R.string.comments_button,
                summary = R.string.comments_button_desc, icon = R.drawable.ic_round_comment_24,
                visible = loggedIn, keys = listOf(PrefName.CommentsEnabled),
                kind = Kind.Switch(
                    get = { PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1 },
                    set = { host, on ->
                        PrefManager.setVal(PrefName.CommentsEnabled, if (on) 1 else 2)
                        snackString(host.activity.getString(R.string.restart_app_extra))
                    },
                ),
            ),
        )),
    )
}
