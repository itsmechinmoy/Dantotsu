package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build.BRAND
import android.os.Build.DEVICE
import android.os.Build.SUPPORTED_ABIS
import android.os.Build.VERSION.CODENAME
import android.os.Build.VERSION.RELEASE
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BuildConfig
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivitySettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.pop
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.rows.RowAdapter
import ani.dantotsu.settings.rows.ScreenItem
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsIndex
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.screens.SettingsScreens
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random


class SettingsActivity : AppCompatActivity(), SettingsHost {
    override val activity: AppCompatActivity get() = this

    /** The home list has no state of its own to re-read. */
    override fun refresh() {}

    lateinit var binding: ActivitySettingsBinding
    private var cursedCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeaderAndVersion()
        setupBackNavigation()
        // One row per settings group, in a single card.
        val mainAdapter = RowAdapter(this)
        mainAdapter.submitList(homeRows().map { ScreenItem.RowItem(it) })
        val searchAdapter = ani.dantotsu.settings.search.SettingsSearchAdapter(emptyList())
        binding.settingsRecyclerView.apply {
            adapter = mainAdapter
            layoutManager = LinearLayoutManager(this@SettingsActivity, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
        }
        setupSettingsSearch(mainAdapter, searchAdapter)
        setupSocialButtonsAndLinks()
        setupEasterEggAndDonationPrompt()
    }

    private fun setupHeaderAndVersion() {
        binding.apply {
            settingsVersion.apply {
                text = getString(R.string.version_current, BuildConfig.VERSION_NAME)
                setOnLongClickListener {
                    copyToClipboard(getDeviceInfo(), false)
                    toast(getString(R.string.copied_device_info))
                    true
                }
            }
            checkIfOutdated()
            settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.settingsSearchBarText.text?.isNotBlank() == true) {
                binding.settingsSearchBarText.setText("")
            } else if (PrefManager.getCustomVal("reload", false)) {
                startMainActivity(this@SettingsActivity)
                PrefManager.setCustomVal("reload", false)
            } else {
                finish()
            }
        }

        binding.settingsBack.setOnClickListener {
            if (binding.settingsSearchBarText.text?.isNotBlank() == true) {
                binding.settingsSearchBarText.setText("")
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun setupSettingsSearch(
        mainAdapter: RowAdapter,
        searchAdapter: ani.dantotsu.settings.search.SettingsSearchAdapter
    ) {
        val context = this
        binding.settingsSearchBarText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    val results = searchSettings(context, query)
                    if (results.isNotEmpty()) {
                        searchAdapter.updateResults(results)
                        binding.settingsRecyclerView.adapter = searchAdapter
                        binding.settingsRecyclerView.visibility = View.VISIBLE
                        binding.settingsEmptyLayout.visibility = View.GONE
                    } else {
                        binding.settingsRecyclerView.visibility = View.GONE
                        binding.settingsEmptyLayout.visibility = View.VISIBLE
                    }
                    binding.settingsFooterLayout.visibility = View.GONE
                    binding.settingsSearchBar.setEndIconDrawable(R.drawable.ic_round_close_24)
                    binding.settingsSearchBar.setEndIconOnClickListener {
                        binding.settingsSearchBarText.setText("")
                    }
                } else {
                    binding.settingsRecyclerView.adapter = mainAdapter
                    binding.settingsRecyclerView.visibility = View.VISIBLE
                    binding.settingsEmptyLayout.visibility = View.GONE
                    binding.settingsFooterLayout.visibility = View.VISIBLE
                    binding.settingsSearchBar.setEndIconDrawable(R.drawable.ic_round_search_24)
                    binding.settingsSearchBar.setEndIconOnClickListener(null)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupSocialButtonsAndLinks() {
        binding.apply {
            if (!BuildConfig.FLAVOR.contains("fdroid")) {
                settingsLogo.setOnLongClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppUpdater.check(this@SettingsActivity, true)
                    }
                    true
                }
            }

            settingBuyMeCoffee.setOnClickListener {
                lifecycleScope.launch { it.pop() }
                openLinkInBrowser(getString(R.string.coffee))
            }
            lifecycleScope.launch {
                settingBuyMeCoffee.pop()
            }

            loginDiscord.setOnClickListener {
                openLinkInBrowser(getString(R.string.discord))
            }
            loginGithub.setOnClickListener {
                openLinkInBrowser(getString(R.string.github))
            }
        }
    }

    private fun setupEasterEggAndDonationPrompt() {
        binding.apply {
            (settingsLogo.drawable as Animatable).start()
            val array = resources.getStringArray(R.array.tips)

            settingsLogo.setSafeOnClickListener {
                cursedCounter++
                (settingsLogo.drawable as Animatable).start()
                if (cursedCounter % 16 == 0) {
                    val oldVal: Boolean = PrefManager.getVal(PrefName.OC)
                    toast(if (!oldVal) R.string.omega_cursed else R.string.omega_freed)
                    PrefManager.setVal(PrefName.OC, !oldVal)
                } else {
                    snackString(array[(Math.random() * array.size).toInt()], this@SettingsActivity)
                }
            }

            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000)
                runOnUiThread {
                    if (Random.nextInt(0, 100) > 69) {
                        CustomBottomDialog.newInstance().apply {
                            title = this@SettingsActivity.getString(R.string.enjoying_app)
                            addView(TextView(this@SettingsActivity).apply {
                                text = this@SettingsActivity.getString(R.string.consider_donating)
                            })
                            setNegativeButton(this@SettingsActivity.getString(R.string.no_moners)) {
                                snackString(R.string.you_be_rich)
                                dismiss()
                            }
                            setPositiveButton(this@SettingsActivity.getString(R.string.donate)) {
                                settingBuyMeCoffee.performClick()
                                dismiss()
                            }
                            show(supportFragmentManager, "dialog")
                        }
                    }
                }
            }
        }
    }

    private fun checkIfOutdated() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val res = client.get("https://api.github.com/repos/itsmechinmoy/dantotsu-updater/releases/latest")
                if (res.code == 200) {
                    val json = Mapper.json.parseToJsonElement(res.text).jsonObject
                    val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull
                    if (tagName != null && isOutdated(tagName, BuildConfig.VERSION_NAME)) {
                        withContext(Dispatchers.Main) {
                            binding.settingsVersion.text = getString(
                                R.string.version_current_outdated,
                                BuildConfig.VERSION_NAME
                            )
                            binding.settingsVersion.setOnClickListener {
                                openLinkInBrowser("https://github.com/itsmechinmoy/dantotsu-updater/releases/latest")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.log("Failed to check dantotsu-updater releases: ${e.message}")
            }
        }
    }

    private fun isOutdated(latestTag: String, currentVersion: String): Boolean =
        ani.dantotsu.util.VersionUtil.isOutdated(latestTag, currentVersion)

    companion object {
        fun getDeviceInfo(): String {
            return """
                dantotsu Version: ${BuildConfig.VERSION_NAME}
                Device: $BRAND $DEVICE
                Architecture: ${getArch()}
                OS Version: $CODENAME $RELEASE ($SDK_INT)
            """.trimIndent()
        }

        private fun getArch(): String {
            SUPPORTED_ABIS.forEach {
                when (it) {
                    "arm64-v8a" -> return "aarch64"
                    "armeabi-v7a" -> return "arm"
                    "x86_64" -> return "x86_64"
                    "x86" -> return "i686"
                }
            }
            return System.getProperty("os.arch") ?: System.getProperty("os.product.cpu.abi")
            ?: "Unknown Architecture"
        }
    }

    override fun onResume() {
        ThemeManager(this).applyTheme()
        super.onResume()
    }
    /** Groups on the settings home, in order; each opens its screen. */
    private fun homeRows() = listOf(
        home("accounts", R.string.accounts, R.string.accounts_desc, R.drawable.ic_round_person_24, SettingsAccountActivity::class.java),
        home("theme", R.string.theme, R.string.theme_desc, R.drawable.ic_palette, SettingsThemeActivity::class.java),
        home("common", R.string.common, R.string.common_desc, R.drawable.ic_lightbulb_24, SettingsCommonActivity::class.java),
        home("data", R.string.label_data_storage, R.string.label_data_storage_desc, R.drawable.ic_round_folder_24,
            ani.dantotsu.settings.data.SettingsDataActivity::class.java),
        home("anime", R.string.anime, R.string.anime_desc, R.drawable.ic_round_movie_filter_24, SettingsAnimeActivity::class.java),
        home("manga", R.string.manga, R.string.manga_desc, R.drawable.ic_round_import_contacts_24, SettingsMangaActivity::class.java),
        home("extensions", R.string.extensions, R.string.extensions_desc, R.drawable.ic_extension, SettingsExtensionsActivity::class.java),
        home("torrent", R.string.torrent_settings, R.string.torrent_settings_desc, R.drawable.ic_round_dns_24, TorrentSettingsActivity::class.java),
        home("notifications", R.string.notifications, R.string.notifications_desc, R.drawable.ic_round_notifications_none_24,
            SettingsNotificationActivity::class.java),
        home("about", R.string.about, R.string.about_desc, R.drawable.ic_round_info_24, SettingsAboutActivity::class.java),
    )

    private fun home(id: String, title: Int, summary: Int, icon: Int, target: Class<out android.app.Activity>) =
        actionRow("home_$id", title, summary, icon, opensScreen = true) { host, _ ->
            host.activity.startActivity(Intent(host.activity, target))
        }

    /** Hits come from the rows of every screen in [SettingsScreens.all]. */
    private fun searchSettings(context: Context, query: String): List<ani.dantotsu.settings.search.SearchableSetting> =
        SettingsIndex.search(context, query).map {
            ani.dantotsu.settings.search.SearchableSetting(
                title = it.title,
                desc = it.summary,
                icon = it.icon,
                breadcrumbs = it.breadcrumbs,
                targetActivity = it.entry.screen.activity,
                rowId = it.entry.row.id,
            )
        }

}
