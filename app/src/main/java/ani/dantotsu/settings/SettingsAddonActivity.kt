package ani.dantotsu.settings

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.addons.AddonDownloader
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.databinding.ActivitySettingsAddonsBinding
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.TorrentSettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsAddonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAddonsBinding
    private val downloadAddonManager: DownloadAddonManager = Injekt.get()
    private val torrentServerManager: TorrentServerManager = Injekt.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAddonsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAddonsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            addonSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            val settingsList = ArrayList<Settings>()

            val torrentSettingsItem = Settings(
                type = 1,
                name = getString(R.string.torrent_settings),
                desc = getString(R.string.torrent_settings_desc),
                icon = R.drawable.ic_round_settings_24,
                onClick = {
                    context.startActivity(android.content.Intent(context, TorrentSettingsActivity::class.java))
                },
                isActivity = true,
                isVisible = PrefManager.getVal(PrefName.TorrentEnabled)
            )

            settingsList.add(
                Settings(
                    type = 1,
                    name = getString(R.string.anime_downloader_addon),
                    desc = getString(R.string.not_installed),
                    icon = R.drawable.ic_download_24,
                    isActivity = true,
                    attach = {
                        setStatus(
                            view = it,
                            context = context,
                            status = downloadAddonManager.hadError(context),
                            hasUpdate = downloadAddonManager.hasUpdate
                        )
                        var job = Job()
                        downloadAddonManager.addListenerAction { _ ->
                            job.cancel()
                            it.settingsIconRight.animate().cancel()
                            it.settingsIconRight.rotation = 0f
                            setStatus(
                                view = it,
                                context = context,
                                status = downloadAddonManager.hadError(context),
                                hasUpdate = false
                            )
                        }
                        it.settingsIconRight.setOnClickListener { _ ->
                            if (it.settingsDesc.text == getString(R.string.installed)) {
                                downloadAddonManager.uninstall()
                                return@setOnClickListener
                            } else {
                                job = Job()
                                val scope = CoroutineScope(Dispatchers.Main + job)
                                it.settingsIconRight.setImageResource(R.drawable.ic_sync)
                                scope.launch {
                                    while (isActive) {
                                        withContext(Dispatchers.Main) {
                                            it.settingsIconRight.animate()
                                                .rotationBy(360f)
                                                .setDuration(1000)
                                                .setInterpolator(LinearInterpolator())
                                                .start()
                                        }
                                        delay(1000)
                                    }
                                }
                                snackString(getString(R.string.downloading))
                                lifecycleScope.launchIO {
                                    AddonDownloader.update(
                                        activity = context,
                                        downloadAddonManager,
                                        repo = DownloadAddonManager.REPO,
                                        currentVersion = downloadAddonManager.getVersion() ?: ""
                                    )
                                }
                            }
                        }
                    },
                )
            )

            settingsList.add(
                Settings(
                    type = 2,
                    name = getString(R.string.enable_torrent),
                    desc = getString(R.string.enable_torrent_desc),
                    icon = R.drawable.ic_round_dns_24,
                    isChecked = PrefManager.getVal(PrefName.TorrentEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.TorrentEnabled, isChecked)
                        if (isChecked) {
                            lifecycleScope.launchIO {
                                if (!TorrentServerService.isRunning()) {
                                    TorrentServerService.start()
                                }
                            }
                        } else {
                            lifecycleScope.launchIO {
                                if (TorrentServerService.isRunning()) {
                                    TorrentServerService.stop()
                                }
                            }
                        }
                        torrentSettingsItem.isVisible = isChecked
                        settingsRecyclerView.adapter?.notifyItemChanged(2)
                    },
                    isVisible = true
                )
            )

            settingsList.add(torrentSettingsItem)

            settingsRecyclerView.adapter = SettingsAdapter(settingsList)
            settingsRecyclerView.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadAddonManager.removeListenerAction()
    }

    private fun setStatus(
        view: ItemSettingsBinding,
        context: Context,
        status: String?,
        hasUpdate: Boolean
    ) {
        try {
            when (status) {
                context.getString(R.string.loaded_successfully) -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_delete_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.installed)
                }

                null -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_download_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.not_installed)
                }

                else -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_new_releases_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.error_msg, status)
                }
            }
            if (hasUpdate) {
                view.settingsIconRight.setImageResource(R.drawable.ic_round_sync_24)
                view.settingsDesc.text = context.getString(R.string.update_addon)
            }
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}
