package ani.dantotsu.home

import android.content.Intent
import android.view.View
import android.view.animation.LayoutAnimationController
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.connections.anilist.AnilistViewModel
import ani.dantotsu.connections.anilist.SearchResults
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaAdaptor
import ani.dantotsu.media.MediaListViewActivity
import ani.dantotsu.media.ProgressAdapter
import ani.dantotsu.setSlideIn
import ani.dantotsu.setSlideUp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

object HomePageAdapterHelper {

    fun initMediaRow(
        adaptor: MediaAdaptor,
        recyclerView: RecyclerView,
        progress: View,
        title: View,
        more: View,
        string: String,
        media: MutableList<Media>
    ) {
        progress.visibility = View.GONE
        recyclerView.setHasFixedSize(true)
        val llm = LinearLayoutManager(
            recyclerView.context,
            LinearLayoutManager.HORIZONTAL,
            false
        ).apply {
            initialPrefetchItemCount = 4
        }
        recyclerView.layoutManager = llm
        recyclerView.adapter = adaptor
        more.setOnClickListener {
            MediaListViewActivity.passedMedia = media.toCollection(ArrayList())
            ContextCompat.startActivity(
                it.context, Intent(it.context, MediaListViewActivity::class.java)
                    .putExtra("title", string),
                null
            )
        }
        recyclerView.visibility = View.VISIBLE
        title.visibility = View.VISIBLE
        more.visibility = View.VISIBLE
        title.startAnimation(setSlideUp())
        more.startAnimation(setSlideUp())
        recyclerView.layoutAnimation =
            LayoutAnimationController(setSlideIn(), 0.25f)
    }

    fun handlePopularResults(
        it: SearchResults,
        model: AnilistViewModel,
        popularAdaptor: MediaAdaptor,
        progressAdaptor: ProgressAdapter,
        oldIncludeList: Boolean,
        onUpdateOldIncludeList: (Boolean) -> Unit,
        snackStringFn: (String) -> Unit,
        joblessMessage: String
    ) {
        if (oldIncludeList == (it.onList != false)) {
            val prev = model.aniMangaSearchResults.results.size
            model.aniMangaSearchResults.results.addAll(it.results)
            popularAdaptor.notifyItemRangeInserted(prev, it.results.size)
        } else {
            model.aniMangaSearchResults.results.addAll(it.results)
            popularAdaptor.notifyDataSetChanged()
            onUpdateOldIncludeList(it.onList ?: true)
        }
        model.aniMangaSearchResults.onList = it.onList
        model.aniMangaSearchResults.hasNextPage = it.hasNextPage
        model.aniMangaSearchResults.page = it.page
        if (it.hasNextPage) {
            progressAdaptor.bar?.visibility = View.VISIBLE
        } else {
            if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                snackStringFn(joblessMessage)
            }
            progressAdaptor.bar?.visibility = View.GONE
        }
    }
}
