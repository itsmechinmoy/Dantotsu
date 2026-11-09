package ani.dantotsu.home

import android.content.Intent
import android.view.View
import android.view.animation.LayoutAnimationController
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.databinding.LayoutTrendingBinding
import ani.dantotsu.loadImage
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
        it: AniMangaSearchResults,
        aniMangaSearchResults: AniMangaSearchResults,
        popularAdaptor: MediaAdaptor,
        progressAdaptor: ProgressAdapter,
        oldIncludeList: Boolean,
        onUpdateOldIncludeList: (Boolean) -> Unit,
        snackStringFn: (String) -> Unit,
        joblessMessage: String
    ) {
        if (oldIncludeList == (it.onList != false)) {
            val prev = aniMangaSearchResults.results.size
            aniMangaSearchResults.results.addAll(it.results)
            popularAdaptor.notifyItemRangeInserted(prev, it.results.size)
        } else {
            aniMangaSearchResults.results.addAll(it.results)
            popularAdaptor.notifyDataSetChanged()
            onUpdateOldIncludeList(it.onList ?: true)
        }
        aniMangaSearchResults.onList = it.onList
        aniMangaSearchResults.hasNextPage = it.hasNextPage
        aniMangaSearchResults.page = it.page
        if (it.hasNextPage) {
            progressAdaptor.bar?.visibility = View.VISIBLE
        } else {
            if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                snackStringFn(joblessMessage)
            }
            progressAdaptor.bar?.visibility = View.GONE
        }
    }

    fun updateTrendingAvatar(trendingBinding: LayoutTrendingBinding, isReady: Boolean) {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        val avatarUrl = if (rescueMode) MAL.avatar else Anilist.avatar
        if (avatarUrl != null && isReady) {
            trendingBinding.userAvatar.loadImage(avatarUrl)
            trendingBinding.userAvatar.imageTintList = null
        }
    }

    fun updateTrendingNotificationCount(trendingBinding: LayoutTrendingBinding) {
        val isRescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        trendingBinding.notificationCount.isVisible = !isRescueMode && Anilist.unreadNotificationCount > 0
                && PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) == true
        trendingBinding.notificationCount.text = Anilist.unreadNotificationCount.toString()
    }
}
