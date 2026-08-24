package eu.kanade.domain.base

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class BasePreferences(
    val context: Context,
    private val preferenceStore: PreferenceStore,
) {

    fun confirmExit() = preferenceStore.getBoolean("pref_confirm_exit", false)

    fun downloadedOnly() = preferenceStore.getBoolean("pref_downloaded_only", false)

    fun incognitoMode() = preferenceStore.getBoolean("incognito_mode", false)

    fun extensionInstaller() = ExtensionInstallerPreference(context, preferenceStore)

    fun deviceHasPip() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE
        )

    enum class ExtensionInstaller(val titleResId: String) {
        LEGACY("Legacy"),
        PACKAGEINSTALLER("PackageInstaller"),
        SHIZUKU("Shizuku"),
        PRIVATE("Private"),
    }
}
