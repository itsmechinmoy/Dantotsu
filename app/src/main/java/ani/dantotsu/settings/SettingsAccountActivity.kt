package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.Comick
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.databinding.ActivitySettingsAccountsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

class SettingsAccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAccountsBinding
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this

        binding = ActivitySettingsAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

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

            setupAccountConnections(context)
        }
        val highlightKey = intent.getStringExtra(ani.dantotsu.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.enable_rpc),
                    desc = getString(R.string.enable_rpc_desc),
                    icon = R.drawable.interests_24,
                    isChecked = PrefManager.getVal(PrefName.rpcEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.rpcEnabled, isChecked)
                    },
                    isVisible = RPCManager.isAvailable(context)
                ),
                Settings(
                    type = 1,
                    name = getString(R.string.anilist_settings),
                    desc = getString(R.string.alsettings_desc),
                    icon = R.drawable.ic_anilist,
                    onClick = {
                        lifecycleScope.launch {
                            Anilist.query.getUserData()
                            startActivity(Intent(context, AnilistSettingsActivity::class.java))
                        }
                    },
                    isActivity = true
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.comments_button),
                    desc = getString(R.string.comments_button_desc),
                    icon = R.drawable.ic_round_comment_24,
                    isChecked = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1,
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.CommentsEnabled, if (isChecked) 1 else 2)
                        reload()
                    },
                    isVisible = Anilist.token != null
                ),
            ),
            highlightKey = highlightKey
        )
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

    }

    fun reload() {
        snackString(getString(R.string.restart_app_extra))
    }
    private fun setupAccountConnections(context: Context) {
        binding.apply {
            fun reload() {
                if (Anilist.token != null) {
                    settingsAnilistLogin.setText(R.string.logout)
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
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
                        settingsMALLogin.setOnClickListener {
                            MAL.removeSavedToken()
                            restartMainActivity.isEnabled = true
                            reload()
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
                    settingsRecyclerView.visibility = View.GONE
                    settingsAnilistLogin.setText(R.string.login)
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
                    settingsMangaBakaLogin.setOnClickListener {
                        MangaBaka.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
                    }
                    settingsMangaBakaUsername.visibility = View.VISIBLE
                    settingsMangaBakaUsername.text = MangaBaka.username
                    settingsMangaBakaAvatar.loadImage(MangaBaka.avatar)
                } else {
                    settingsMangaBakaAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsMangaBakaUsername.visibility = View.GONE
                    settingsMangaBakaLogin.setText(R.string.login)
                    settingsMangaBakaLogin.setOnClickListener {
                        MangaBaka.loginIntent(context)
                    }
                }

                if (Comick.token != null) {
                    settingsComickLogin.setText(R.string.logout)
                    settingsComickLogin.setOnClickListener {
                        Comick.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
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
                    settingsComickLogin.setOnClickListener {
                        Comick.loginIntent(context)
                    }
                }
            }
            reload()
                }
    }

}
