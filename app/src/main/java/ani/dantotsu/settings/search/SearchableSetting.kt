package ani.dantotsu.settings.search

import android.app.Activity

/** One search hit: a row on a settings screen. */
data class SearchableSetting(
    val title: String,
    val desc: String? = null,
    val icon: Int = ani.dantotsu.R.drawable.ic_round_settings_24,
    val breadcrumbs: String = "",
    val targetActivity: Class<out Activity>,
    /** Row to scroll to and flash on the target screen. */
    val rowId: String,
)
