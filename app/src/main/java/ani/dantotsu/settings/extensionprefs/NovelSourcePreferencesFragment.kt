package ani.dantotsu.settings.extensionprefs

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.forEach
import androidx.preference.getOnBindEditTextListener
import ani.dantotsu.R
import ani.dantotsu.parsers.novel.ConfigurableNovelSource
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.parsers.novel.isSameNovelExtension
import ani.dantotsu.parsers.novel.preferenceKey
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import eu.kanade.tachiyomi.PreferenceScreen
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelSourcePreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = try {
            populateNovelPreferenceScreen()
        } catch (e: Exception) {
            snackString(e.message ?: "Unknown error")
            preferenceManager.createPreferenceScreen(requireContext())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(requireActivity()).applyTheme()
    }

    private var onCloseAction: (() -> Unit)? = null

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            onCloseAction?.invoke()
        } catch (_: Exception) {}
    }

    private fun populateNovelPreferenceScreen(): PreferenceScreen {
        val pkgName = requireArguments().getString(PKG_NAME) ?: error("Package name not found!")
        val novelExtensionManager = Injekt.get<NovelExtensionManager>()
        val installed = novelExtensionManager.installedExtensionsFlow.value
        val extension = installed.find { isSameNovelExtension(it.pkgName, it.name, pkgName, "") }
            ?: error("Extension $pkgName not found!")

        val source = extension.sources.filterIsInstance<ConfigurableNovelSource>().firstOrNull()
            ?: extension.sources.firstOrNull { src ->
                src.javaClass.methods.any { it.name == "setupPreferenceScreen" }
            }
            ?: error("Source is not configurable!")

        val prefKey = if (source is ConfigurableNovelSource) {
            source.preferenceKey()
        } else {
            "novel_source_${source.javaClass.simpleName}"
        }

        val sharedPreferences =
            requireContext().getSharedPreferences(prefKey, Context.MODE_PRIVATE)
        val dataStore = SharedPreferencesDataStore(sharedPreferences)
        preferenceManager.preferenceDataStore = dataStore
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())

        if (source is ConfigurableNovelSource) {
            source.setupPreferenceScreen(sourceScreen)
        } else {
            // Reflection fallback if loaded from separate classloader
            val setupMethod = source.javaClass.methods.firstOrNull { it.name == "setupPreferenceScreen" }
            setupMethod?.invoke(source, sourceScreen)
        }

        sourceScreen.forEach { pref ->
            pref.isIconSpaceReserved = false
            if (pref is DialogPreference) {
                pref.dialogTitle = pref.title
            }
            if (pref is EditTextPreference) {
                val setListener = pref.getOnBindEditTextListener()
                pref.setOnBindEditTextListener {
                    setListener?.onBindEditText(it)
                    it.setIncognito(lifecycleScope)
                }
            }
        }
        return sourceScreen
    }

    fun getInstance(
        pkgName: String,
        onCloseAction: (() -> Unit)? = null
    ): NovelSourcePreferencesFragment {
        val fragment = NovelSourcePreferencesFragment()
        fragment.arguments = bundleOf(PKG_NAME to pkgName)
        fragment.onCloseAction = onCloseAction
        return fragment
    }

    companion object {
        private const val PKG_NAME = "pkg_name"
    }
}