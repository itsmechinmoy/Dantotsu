package ani.dantotsu.media.anime.player

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RPC
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.mpv.MpvVideoView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.launch

class PlayerDiscordManager(
    private val activity: AppCompatActivity
) {

    fun updatePresence(
        media: Media?,
        episode: Episode?,
        player: MpvVideoView?,
        isPlaying: Boolean
    ) {
        if (media == null || episode == null || player == null) return
        val context = activity
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode)
        val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
        val rpcEnabled = PrefManager.getVal(PrefName.rpcEnabled, true)

        if (RPCManager.shouldSuppressForAdultMedia(media.isAdult)) {
            RPCManager.clearPresence(context)
            return
        }

        if (isOnline(context) && !offline && !incognito && rpcEnabled) {
            activity.lifecycleScope.launch {
                val rpcData = buildDiscordRpcData(context, media, episode, player, isPlaying)
                RPCManager.setPresence(context, rpcData, activity)
            }
        }
    }

    private fun buildDiscordRpcData(
        context: Context,
        media: Media,
        episode: Episode,
        player: MpvVideoView,
        isPlaying: Boolean
    ): RPC.Companion.RPCData {
        val buttons = mutableListOf<RPC.Link>()
        buttons.add(RPC.Link("View Anime", "https://anilist.co/anime/${media.id}/"))
        media.idMAL?.let {
            buttons.add(RPC.Link("View on MyAnimeList", "https://myanimelist.net/anime/$it"))
        }

        val now = System.currentTimeMillis()
        val posMs = player.currentPosition.value
        val durMs = player.duration.value
        val currentPosMs = if (posMs > 0L) posMs else 0L
        val safeDurationMs = if (durMs > 0L) durMs else 1440000L

        val isPaused = !isPlaying
        val startTimestamp = if (isPaused) null else now - currentPosMs
        val endTimestamp = if (isPaused) null else now - currentPosMs + safeDurationMs

        val stateText = "Episode : ${episode.number}/${media.anime?.totalEpisodes ?: "??"}"
        val finalState = if (isPaused) "Paused - $stateText" else stateText

        return RPC.Companion.RPCData(
            applicationId = Discord.application_Id,
            type = RPC.Type.WATCHING,
            activityName = media.userPreferredName,
            details = episode.title?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.episode_num, episode.number),
            startTimestamp = startTimestamp,
            stopTimestamp = endTimestamp,
            state = finalState,
            largeImage = media.cover?.let { RPC.Link(media.userPreferredName, it) },
            smallImage = RPC.Link("Dantotsu", Discord.small_Image),
            buttons = buttons,
        )
    }

    fun clear() {
        RPCManager.clearPresence(activity, activity)
    }
}