package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
    val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
    if (!incognito) {
        if (rescueMode) {
            // In rescue mode: cache the update for later AL sync and mirror to MAL
            val a = number.toFloatOrNull()?.toInt()
            if ((a ?: 0) > (media.userProgress ?: -1)) {
                val status = if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT"
                val pending = PendingProgressUpdate(
                    mediaId = media.id,
                    idMAL = media.idMAL,
                    isAnime = media.anime != null,
                    progress = a ?: 0,
                    status = status,
                )
                val existing: List<PendingProgressUpdate> =
                    PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
                val updated = existing.filterNot { it.mediaId == media.id } + pending
                PrefManager.setVal(PrefName.PendingProgressUpdates, updated)
                CoroutineScope(Dispatchers.IO).launch {
                    MAL.query.editList(
                        media.idMAL,
                        media.anime != null,
                        a, null, status
                    )
                    toast(currContext()?.getString(R.string.setting_progress, a))
                }
            }
            media.userProgress = number.toFloatOrNull()?.toInt()
            Refresh.all()
        } else if (Anilist.userid != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val a = number.toFloatOrNull()?.toInt()
                if ((a ?: 0) > (media.userProgress ?: -1)) {
                    Anilist.mutation.editList(
                        media.id,
                        a,
                        status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                    )
                    MAL.query.editList(
                        media.idMAL,
                        media.anime != null,
                        a, null,
                        if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT"
                    )
                    toast(currContext()?.getString(R.string.setting_progress, a))
                }
                media.userProgress = a
                Refresh.all()
            }
        } else {
            toast(currContext()?.getString(R.string.login_anilist_account))
        }
    } else {
        toast("Sneaky sneaky :3")
    }
}

/** Sync all pending progress updates (cached during rescue mode) to AniList. */
fun syncPendingProgressUpdates() {
    val pending: List<PendingProgressUpdate> =
        PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
    if (pending.isEmpty()) return
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (update in pending) {
            // executeQuery returns null on failure; editList doesn't surface this,
            // so we rely on the disabled signal to detect AniList being down
            Anilist.mutation.editList(
                update.mediaId,
                update.progress,
                status = update.status
            )
            if (!Anilist.anilistDisabledSignal) {
                remaining.remove(update)
            } else {
                // AniList appears to be down again; stop and keep remaining
                break
            }
        }
        PrefManager.setVal(PrefName.PendingProgressUpdates, remaining)
        Refresh.all()
    }
}