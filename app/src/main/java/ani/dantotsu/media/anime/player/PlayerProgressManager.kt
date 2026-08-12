package ani.dantotsu.media.anime.player

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.widgets.continue_widget.ContinueWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@UnstableApi
class PlayerProgressManager(
    private val activity: AppCompatActivity,
    private val model: MediaDetailsViewModel,
    private val getPlayer: () -> Player?,
    private val isPlayerInitialized: () -> Boolean
) {

    private val handler = Handler(Looper.getMainLooper())
    private var preloading = false
    private var lastSubscriptionPromptEpisode: String? = null
    private var isTrackingProgress = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            checkAndPreloadProgress()
            if (isTrackingProgress && !preloading) {
                handler.postDelayed(this, 2500)
            }
        }
    }

    var episodeLength: Float = 0f
    var currentEpisodeIndex: Int = 0
    var episodeArr: List<String> = emptyList()
    var episodes: MutableMap<String, Episode> = mutableMapOf()
    var episodeTitleArr: ArrayList<String> = arrayListOf()
    var media: Media? = null

    fun startTracking() {
        isTrackingProgress = true
        preloading = false
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    fun stopTracking() {
        isTrackingProgress = false
        handler.removeCallbacks(progressRunnable)
    }

    fun updateWidgetState(isExiting: Boolean) {
        val m = media ?: return
        val currentEpTitle = episodeTitleArr.getOrNull(currentEpisodeIndex)
        ContinueWidget.updatePlaybackState(
            activity,
            m.userPreferredName,
            m.cover,
            currentEpTitle,
            isExiting = isExiting
        )
    }

    private fun checkAndPreloadProgress() {
        val player = getPlayer() ?: return
        val m = media ?: return
        if (!isPlayerInitialized() || player.duration <= 0) return

        val watchRatio = player.currentPosition.toFloat() / player.duration
        if (watchRatio > PrefManager.getVal<Float>(PrefName.WatchPercentage)) {
            preloading = true
            nextEpisode(false) { i ->
                val nextKey = episodeArr.getOrNull(currentEpisodeIndex + i) ?: return@nextEpisode
                val ep = episodes[nextKey] ?: return@nextEpisode
                val selected = m.selected ?: return@nextEpisode
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    if (selected.server != null) {
                        model.loadEpisodeSingleVideo(ep, selected, false)
                    } else {
                        model.loadEpisodeVideos(ep, selected.sourceIndex, false)
                    }
                }
            }
        }
    }

    fun updateAniProgress() {
        val player = getPlayer() ?: return
        val m = media ?: return
        val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
        if (episodeLength <= 0f) {
            maybeHandleSubscriptionAfterEpisodeCompletion(false, incognito)
            return
        }

        val episodeEnd = player.currentPosition / episodeLength >
                PrefManager.getVal<Float>(PrefName.WatchPercentage)
        val episode0 = currentEpisodeIndex == 0 && PrefManager.getVal<Boolean>(PrefName.ChapterZeroPlayer)

        if (!incognito && (episodeEnd || episode0) && Anilist.userid != null) {
            if (PrefManager.getCustomVal("${m.id}_save_progress", true) &&
                (if (m.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true)
            ) {
                if (episode0 && !episodeEnd) {
                    updateProgress(m, "0")
                } else {
                    m.anime?.selectedEpisode?.let { epNum ->
                        updateProgress(m, epNum)
                    }
                }
            }
        }
        maybeHandleSubscriptionAfterEpisodeCompletion(episodeEnd, incognito)
    }

    private fun maybeHandleSubscriptionAfterEpisodeCompletion(episodeEnd: Boolean, incognito: Boolean) {
        if (!episodeEnd || incognito) return
        val m = media ?: return
        val currentEpisode = m.anime?.selectedEpisode ?: return
        if (lastSubscriptionPromptEpisode == currentEpisode) return
        lastSubscriptionPromptEpisode = currentEpisode

        val subscriptionsEnabled = PrefManager.getVal<Boolean>(PrefName.SubscriptionPromptAtEnd)
        if (!subscriptionsEnabled) return

        val isCompleted = isAnimeCompleted(m)
        val alreadySubscribed = SubscriptionHelper.getSubscriptions().containsKey(m.id)
        if (isCompleted) {
            if (alreadySubscribed) {
                SubscriptionHelper.saveSubscription(m, false)
                toast(activity.getString(R.string.unsubscribed_notification))
            }
            return
        }
        if (alreadySubscribed) return

        activity.customAlertDialog().apply {
            setTitle(activity.getString(R.string.subscribe_prompt_title))
            setMessage(activity.getString(R.string.subscribe_prompt_anime_message, m.userPreferredName))
            setPosButton(R.string.yes) {
                SubscriptionHelper.saveSubscription(m, true)
                toast(activity.getString(R.string.subscribed_notification, activity.getString(R.string.anime)))
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun isAnimeCompleted(m: Media): Boolean {
        if (m.status == "FINISHED") return true
        if (m.userStatus == "COMPLETED") return true
        val totalEpisodes = m.anime?.totalEpisodes ?: return false
        val currentEpisodeNumber = m.anime?.selectedEpisode?.toFloatOrNull() ?: return false
        return currentEpisodeNumber >= totalEpisodes
    }

    fun nextEpisode(
        showToast: Boolean = true,
        runnable: (Int) -> Unit
    ) {
        var isFiller = true
        var i = 1
        while (isFiller) {
            if (episodeArr.size > currentEpisodeIndex + i) {
                isFiller = if (PrefManager.getVal(PrefName.AutoSkipFiller)) {
                    episodes[episodeArr[currentEpisodeIndex + i]]?.filler ?: false
                } else {
                    false
                }
                if (!isFiller) runnable.invoke(i)
                i++
            } else {
                if (showToast) {
                    toast(activity.getString(R.string.no_next_episode))
                }
                isFiller = false
            }
        }
    }
}
