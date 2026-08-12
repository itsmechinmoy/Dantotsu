package ani.dantotsu.settings.search

import android.app.Activity

data class SearchableSetting(
    val title: String,
    val desc: String? = null,
    val icon: Int = ani.dantotsu.R.drawable.ic_round_settings_24,
    val category: String = "",
    val breadcrumbs: String = "",
    val targetActivity: Class<out Activity>,
    val highlightKey: String? = null,
    val isVisible: Boolean = true
)
