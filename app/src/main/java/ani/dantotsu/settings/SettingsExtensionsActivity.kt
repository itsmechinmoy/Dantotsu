package ani.dantotsu.settings

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.databinding.ItemRepositoryBinding
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import eu.kanade.domain.base.BasePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsExtensionsActivity : SettingsScreenActivity() {
    override val screen get() = ExtensionsScreen
}

object ExtensionsScreen : SettingsScreen("extensions", R.string.extensions, R.drawable.ic_extension, R.string.extensions_desc) {

    override val activity get() = SettingsExtensionsActivity::class.java

    private fun installer() = Injekt.get<BasePreferences>().extensionInstaller()

    override fun sections() = listOf(
        Section(R.string.settings_section_repositories, listOf(
            repoRow(MediaType.ANIME, PrefName.AnimeExtensionRepos, R.string.anime_add_repository,
                R.string.anime_add_repository_desc),
            repoRow(MediaType.MANGA, PrefName.MangaExtensionRepos, R.string.manga_add_repository,
                R.string.manga_add_repository_desc),
            repoRow(MediaType.NOVEL, PrefName.NovelExtensionRepos, R.string.novel_add_repository,
                R.string.novel_add_repository_desc),
        )),
        Section(R.string.settings_section_network, listOf(
            actionRow(PrefName.DefaultUserAgent.name, R.string.user_agent, R.string.user_agent_desc,
                R.drawable.ic_round_video_settings_24, keys = listOf(PrefName.DefaultUserAgent),
                onClick = { host, _ -> editUserAgent(host) }),
            switchRow(PrefName.EnableSocks5Proxy, R.string.proxy, R.string.proxy_desc,
                R.drawable.swap_horizontal_circle_24, onChange = { host, _ -> host.activity.restartApp() }),
            actionRow("proxy_setup", R.string.proxy_setup, R.string.proxy_setup_desc, R.drawable.lan_24,
                onClick = { host, _ -> ProxyDialogFragment().show(host.activity.supportFragmentManager, "dialog") }),
        )),
        Section(R.string.general, listOf(
            Row(
                id = "extension_installer", title = R.string.extension_installer,
                icon = R.drawable.ic_round_new_releases_24,
                kind = Kind.Choice(
                    labels = { installer().entries.map { it.titleResId } },
                    get = { installer().entries.indexOf(installer().get()) },
                    set = { host, which ->
                        installer().set(installer().entries[which])
                        host.activity.restartApp()
                    },
                ),
            ),
            switchRow(PrefName.SkipExtensionIcons, R.string.skip_loading_extension_icons,
                R.string.skip_loading_extension_icons_desc, R.drawable.ic_round_no_icon_24),
            switchRow(PrefName.NSFWExtension, R.string.NSFWExtention, R.string.NSFWExtention_desc,
                R.drawable.ic_round_nsfw_24),
            actionRow("extension_test", R.string.extension_test, R.string.extension_test_desc,
                R.drawable.ic_round_search_sources_24, opensScreen = true,
                onClick = { host, _ -> host.activity.startActivity(Intent(host.activity, ParserTestActivity::class.java)) }),
        )),
    )

    /** Opens the repository sheet; the saved repositories are listed under the row. */
    private fun repoRow(type: MediaType, key: PrefName, title: Int, summary: Int) = actionRow(
        "repos_${type.name.lowercase()}", title, summary, R.drawable.ic_github, keys = listOf(key),
        extra = { _, box -> listRepos(box, key) },
        onClick = { host, _ ->
            AddRepositoryBottomSheet.newInstance(
                type,
                PrefManager.getVal<Set<String>>(key).toList(),
                onRepositoryAdded = { input, mediaType ->
                    AddRepositoryBottomSheet.addRepo(input, mediaType)
                    host.refresh()
                },
                onRepositoryRemoved = { item, mediaType ->
                    AddRepositoryBottomSheet.removeRepo(item, mediaType)
                    host.refresh()
                }
            ).show(host.activity.supportFragmentManager, "add_repo")
        },
    )

    private fun listRepos(box: ViewGroup, key: PrefName) {
        PrefManager.getVal<Set<String>>(key).forEach { item ->
            val view = ItemRepositoryBinding.inflate(LayoutInflater.from(box.context), box, true)
            view.repositoryItem.text = item.removePrefix("https://raw.githubusercontent.com/")
            view.repositoryItem.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                copyToClipboard(item, true)
                true
            }
        }
        box.visibility = if (box.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun editUserAgent(host: SettingsHost) = with(host.activity) {
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
        val editText = dialogView.userAgentTextBox
        editText.setText(PrefManager.getVal<String>(PrefName.DefaultUserAgent))
        customAlertDialog().apply {
            setTitle(R.string.user_agent)
            setCustomView(dialogView.root)
            setPosButton(R.string.ok) {
                PrefManager.setVal(PrefName.DefaultUserAgent, editText.text.toString())
            }
            setNeutralButton(R.string.reset) {
                PrefManager.removeVal(PrefName.DefaultUserAgent)
                editText.setText("")
            }
            setNegButton(R.string.cancel)
        }.show()
    }
}
