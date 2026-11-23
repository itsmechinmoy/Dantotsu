package ani.dantotsu.settings

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.DialogSetPasswordBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.initActivity
import ani.dantotsu.others.calc.BiometricPromptUtils
import ani.dantotsu.restartApp
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.Segment
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.segmentedRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.toast
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class SettingsCommonActivity : SettingsScreenActivity() {
    override val screen get() = CommonScreen

    private lateinit var launcher: LauncherWrapper
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>

    override fun onScreenCreate(savedInstanceState: Bundle?) {
        openDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val jsonString =
                            contentResolver.openInputStream(uri)?.readBytes()
                                ?: throw Exception("Error reading file")
                        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
                        // .sani is encrypted, .ani is not
                        if (name.endsWith(".sani")) {
                            passwordAlertDialog(false) { password ->
                                if (password != null) {
                                    val salt = jsonString.copyOfRange(0, 16)
                                    val encrypted = jsonString.copyOfRange(16, jsonString.size)
                                    val decryptedJson =
                                        try {
                                            PreferenceKeystore.decryptWithPassword(
                                                password,
                                                encrypted,
                                                salt,
                                            )
                                        } catch (e: Exception) {
                                            toast(getString(R.string.incorrect_password))
                                            return@passwordAlertDialog
                                        }
                                    if (PreferencePackager.unpack(decryptedJson)) restartApp()
                                } else {
                                    toast(getString(R.string.password_cannot_be_empty))
                                }
                            }
                        } else if (name.endsWith(".ani")) {
                            val decryptedJson = jsonString.toString(Charsets.UTF_8)
                            if (PreferencePackager.unpack(decryptedJson)) restartApp()
                        } else {
                            toast(getString(R.string.unknown_file_type))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast(getString(R.string.error_importing_settings))
                    }
                }
            }
        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)
    }

    private fun passwordAlertDialog(
        isExporting: Boolean,
        callback: (CharArray?) -> Unit,
    ) {
        val password = CharArray(16).apply { fill('0') }

        // Inflate the dialog layout
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
        val box = dialogView.userAgentTextBox
        box.hint = getString(R.string.password)
        box.setSingleLine()

        val dialog =
            AlertDialog
                .Builder(this, R.style.MyPopup)
                .setTitle(getString(R.string.enter_password))
                .setView(dialogView.root)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    password.fill('0')
                    dialog.dismiss()
                    callback(null)
                }.create()

        fun handleOkAction() {
            val editText = dialogView.userAgentTextBox
            if (editText.text?.isNotBlank() == true) {
                editText.text
                    ?.toString()
                    ?.trim()
                    ?.toCharArray(password)
                dialog.dismiss()
                callback(password)
            } else {
                toast(getString(R.string.password_cannot_be_empty))
            }
        }
        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleOkAction()
                true
            } else {
                false
            }
        }
        dialogView.subtitle.visibility = View.VISIBLE
        if (!isExporting) {
            dialogView.subtitle.text =
                getString(R.string.enter_password_to_decrypt_file)
        }

        dialog.window?.apply {
            setDimAmount(0.8f)
            attributes.windowAnimations = android.R.style.Animation_Dialog
        }
        dialog.show()

        // Override the positive button here
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            handleOkAction()
        }
    }
    internal fun showAppLockDialog() {
        customAlertDialog().apply {
            val view = DialogSetPasswordBinding.inflate(layoutInflater)
            setTitle(R.string.app_lock)
            setCustomView(view.root)
            setPosButton(R.string.ok) {
                if (view.forgotPasswordCheckbox.isChecked) {
                    PrefManager.setVal(PrefName.OverridePassword, true)
                }
                val password = view.passwordInput.text.toString()
                val confirmPassword = view.confirmPasswordInput.text.toString()
                if (password == confirmPassword && password.isNotEmpty()) {
                    PrefManager.setVal(PrefName.AppPassword, password)
                    if (view.biometricCheckbox.isChecked) {
                        val canBiometricPrompt =
                            BiometricManager.from(applicationContext)
                                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                                BiometricManager.BIOMETRIC_SUCCESS

                        if (canBiometricPrompt) {
                            val biometricPrompt =
                                BiometricPromptUtils.createBiometricPrompt(this@SettingsCommonActivity) { _ ->
                                    val token = UUID.randomUUID().toString()
                                    PrefManager.setVal(PrefName.BiometricToken, token)
                                    toast(R.string.success)
                                }
                            val promptInfo = BiometricPromptUtils.createPromptInfo(this@SettingsCommonActivity)
                            biometricPrompt.authenticate(promptInfo)
                        }
                    } else {
                        PrefManager.setVal(PrefName.BiometricToken, "")
                        toast(R.string.success)
                    }
                } else {
                    toast(R.string.password_mismatch)
                }
            }
            setNegButton(R.string.cancel)
            setNeutralButton(R.string.remove) {
                PrefManager.setVal(PrefName.AppPassword, "")
                PrefManager.setVal(PrefName.BiometricToken, "")
                PrefManager.setVal(PrefName.OverridePassword, false)
                toast(R.string.success)
            }
            setOnShowListener {
                view.passwordInput.requestFocus()
                val canAuthenticate =
                    BiometricManager.from(applicationContext).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                view.biometricCheckbox.isVisible = canAuthenticate
                view.biometricCheckbox.isChecked = PrefManager.getVal(PrefName.BiometricToken, "").isNotEmpty()
                view.forgotPasswordCheckbox.isChecked = PrefManager.getVal(PrefName.OverridePassword)
            }
            show()
        }
    }

    internal fun showBackupRestoreDialog() {
        val context = this
        StoragePermissions.downloadsPermission(context)
        val filteredLocations = Location.entries.filter { it.exportable }
        val selectedArray = BooleanArray(filteredLocations.size) { false }
        context.customAlertDialog().apply {
            setTitle(R.string.backup_restore)
            multiChoiceItems(
                filteredLocations.map { it.name }.toTypedArray(),
                selectedArray
            ) { updatedSelection ->
                for (i in updatedSelection.indices) {
                    selectedArray[i] = updatedSelection[i]
                }
            }
            setPosButton(R.string.button_restore) {
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
            setNegButton(R.string.button_backup) {
                if (!selectedArray.contains(true)) {
                    toast(R.string.no_location_selected)
                    return@setNegButton
                }
                val selected = filteredLocations.filterIndexed { index, _ -> selectedArray[index] }
                if (selected.contains(Location.Protected)) {
                    passwordAlertDialog(true) { password ->
                        if (password != null) {
                            savePrefsToDownloads(
                                "DantotsuSettings",
                                PrefManager.exportAllPrefs(selected),
                                context,
                                password
                            )
                        } else {
                            toast(R.string.password_cannot_be_empty)
                        }
                    }
                } else {
                    savePrefsToDownloads(
                        "DantotsuSettings",
                        PrefManager.exportAllPrefs(selected),
                        context,
                        null
                    )
                }
            }
            setNeutralButton(R.string.cancel) {}
            show()
        }
    }

    internal fun showChangeDownloadLocationDialog() {
        val context = this
        context.customAlertDialog().apply {
            setTitle(R.string.change_download_location)
            setMessage(R.string.download_location_msg)
            setPosButton(R.string.ok) {
                val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                launcher.registerForCallback { success ->
                    if (success) {
                        toast(getString(R.string.please_wait))
                        val newUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                        lifecycleScope.launch(Dispatchers.IO) {
                            Injekt.get<DownloadsManager>().moveDownloadsDir(
                                context,
                                Uri.parse(oldUri),
                                Uri.parse(newUri)
                            ) { finished, message ->
                                if (finished) {
                                    toast(getString(R.string.success))
                                } else {
                                    toast(message)
                                }
                            }
                        }
                    } else {
                        toast(getString(R.string.error))
                    }
                }
                launcher.launch()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    internal fun showRebuildDownloadIndexDialog() {
        customAlertDialog().apply {
            setTitle(R.string.rebuild_download_index)
            setMessage(R.string.rebuild_download_index_msg)
            setPosButton(R.string.ok) {
                toast(getString(R.string.please_wait))
                lifecycleScope.launch(Dispatchers.IO) {
                    val recovered = Injekt.get<DownloadsManager>().rebuildIndexFromDisk()
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.rebuild_download_index_done, recovered))
                    }
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}

object CommonScreen : SettingsScreen("common", R.string.common, R.drawable.ic_lightbulb_24, R.string.common_desc) {

    override val activity get() = SettingsCommonActivity::class.java

    private val DNS = listOf(
        "None", "Cloudflare", "Google", "AdGuard", "Quad9", "AliDNS", "DNSPod",
        "360", "Quad101", "Mullvad", "Controld", "Njalla", "Shecan", "Libre",
    )
    private val MANAGERS = listOf("Default", "1DM", "ADM")

    private fun common(host: SettingsHost) = host.activity as SettingsCommonActivity

    override fun sections() = listOf(
        Section(R.string.general, listOf(
            segmentedRow(PrefName.DefaultStartUpTab, R.string.startUpTab, R.drawable.ic_round_home_24,
                options = listOf(
                    Segment(R.drawable.ic_round_movie_filter_24, R.string.anime),
                    Segment(R.drawable.ic_round_home_24, R.string.home),
                    Segment(R.drawable.ic_round_import_contacts_24, R.string.manga),
                ),
                onChange = { host, _ -> initActivity(host.activity) }),
            actionRow("ui_settings", R.string.ui_settings, R.string.ui_settings_desc,
                R.drawable.ic_round_auto_awesome_24, opensScreen = true,
                // search reaches the interface settings through this row
                keywords = listOf(R.string.immersive_mode, R.string.hide_notification_dot,
                    R.string.home_layout_show, R.string.small_view, R.string.carousel_clearlogo,
                    R.string.banner_animations, R.string.layout_animations),
                onClick = { host, _ ->
                    host.activity.startActivity(android.content.Intent(host.activity, UserInterfaceSettingsActivity::class.java))
                }),
            choiceRow(PrefName.DohProvider, R.string.selected_dns, R.drawable.ic_round_dns_24,
                labels = { DNS }, onChange = { host, _ -> host.activity.restartApp() }),
            switchRow(PrefName.ContinueMedia, R.string.always_continue_content, R.string.always_continue_content_desc,
                R.drawable.ic_round_delete_24),
            switchRow(PrefName.SearchSources, R.string.search_source_list, R.string.search_source_list_desc,
                R.drawable.ic_round_search_sources_24),
            switchRow(PrefName.RecentlyListOnly, R.string.recentlyListOnly, R.string.recentlyListOnly_desc,
                R.drawable.ic_round_new_releases_24),
        )),
        Section(R.string.downloads, listOf(
            choiceRow(PrefName.DownloadManager, R.string.download_manager_select, R.drawable.ic_download_24,
                summary = R.string.download_manager_select_desc, labels = { MANAGERS }),
            choiceRow(PrefName.MaxParallelDownloads, R.string.max_parallel_downloads, R.drawable.ic_download_24,
                summary = R.string.max_parallel_downloads_desc,
                labels = { c -> listOf(c.getString(R.string.max_parallel_downloads_off)) + (1..10).map { it.toString() } }),
            switchRow(PrefName.DownloadWifiOnly, R.string.download_wifi_only, R.string.download_wifi_only_desc,
                R.drawable.lan_24),
            actionRow("change_download_location", R.string.change_download_location,
                R.string.change_download_location_desc, R.drawable.ic_round_source_24,
                onClick = { host, _ -> common(host).showChangeDownloadLocationDialog() }),
            actionRow("rebuild_download_index", R.string.rebuild_download_index, R.string.rebuild_download_index_desc,
                R.drawable.ic_download_24, onClick = { host, _ -> common(host).showRebuildDownloadIndexDialog() }),
        )),
        Section(R.string.settings_section_privacy_security, listOf(
            actionRow("app_lock", R.string.app_lock, R.string.app_lock_desc, R.drawable.ic_round_lock_open_24,
                keys = listOf(PrefName.AppPassword, PrefName.BiometricToken, PrefName.OverridePassword),
                onClick = { host, _ -> common(host).showAppLockDialog() }),
            switchRow(PrefName.HidePrivate, R.string.hide_private, R.string.hide_private_desc,
                R.drawable.ic_round_remove_red_eye_24, onChange = { host, _ -> host.activity.restartApp() }),
            switchRow(PrefName.AdultOnly, R.string.adult_only_content, R.string.adult_only_content_desc,
                R.drawable.ic_round_nsfw_24, visible = { Anilist.adult },
                onChange = { host, _ -> host.activity.restartApp() }),
        )),
        Section(R.string.settings_section_data_backup, listOf(
            actionRow("data_storage", R.string.label_data_storage, R.string.label_data_storage_desc,
                R.drawable.ic_round_folder_24, opensScreen = true,
                onClick = { host, _ ->
                    host.activity.startActivity(
                        android.content.Intent(host.activity, ani.dantotsu.settings.data.SettingsDataActivity::class.java))
                }),
            actionRow("backup_restore", R.string.backup_restore, R.string.backup_restore_desc, R.drawable.backup_restore,
                onClick = { host, _ -> common(host).showBackupRestoreDialog() }),
        )),
    )
}
