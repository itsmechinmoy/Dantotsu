package ani.dantotsu.settings.paging

import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.paging.PagingSource
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.extension.model.BaseExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ExtensionPagingHelper {

    fun <T : BaseExtension> filterAndPaginate(
        availableList: List<T>,
        installedPkgNames: Set<String>,
        query: String,
        params: PagingSource.LoadParams<Int>
    ): PagingSource.LoadResult<Int, T> {
        val position = params.key ?: 0
        val availableExtensions = availableList.filterNot { it.pkgName in installedPkgNames }
        val isNsfwEnabled: Boolean = PrefManager.getVal(PrefName.NSFWExtension)

        val filteredExtensions = if (query.isEmpty()) {
            availableExtensions
        } else {
            availableExtensions.filter { it.name.contains(query, ignoreCase = true) }
        }
        val lang: String = PrefManager.getVal(PrefName.LangSort)
        val langFilter =
            if (lang != "all") filteredExtensions.filter { it.lang == lang } else filteredExtensions
        val filternsfw = if (isNsfwEnabled) langFilter else langFilter.filterNot { it.isNsfw }

        return try {
            val sublist = filternsfw.subList(
                fromIndex = position,
                toIndex = (position + params.loadSize).coerceAtMost(filternsfw.size)
            )
            PagingSource.LoadResult.Page(
                data = sublist,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (position + params.loadSize >= filternsfw.size) null else position + params.loadSize
            )
        } catch (e: Exception) {
            PagingSource.LoadResult.Error(e)
        }
    }

    fun animateInstallSync(closeTextView: ImageView, scope: CoroutineScope) {
        closeTextView.setImageResource(R.drawable.ic_sync)
        scope.launch {
            while (isActive) {
                withContext(Dispatchers.Main) {
                    closeTextView.animate()
                        .rotationBy(360f)
                        .setDuration(1000)
                        .setInterpolator(LinearInterpolator())
                        .start()
                }
                delay(1000)
            }
        }
    }
}
