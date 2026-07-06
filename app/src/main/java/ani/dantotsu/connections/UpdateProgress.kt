package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.media.emptyMedia
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.findValidName
import ani.dantotsu.media.MediaType
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
    val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)

    val autoDelete = PrefManager.getCustomVal("auto_delete_downloads", false)
    if (autoDelete) {
        val type = if (media.anime != null) MediaType.ANIME else if (media.format == "NOVEL") MediaType.NOVEL else MediaType.MANGA
        val downloadsManager = Injekt.get<DownloadsManager>()
        val downloadedTypes = when (type) {
            MediaType.ANIME -> downloadsManager.animeDownloadedTypes
            MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
            MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
        }
        val downloadedType = downloadedTypes.find {
            it.titleName == media.mainName().findValidName() &&
            it.chapterName == number.findValidName()
        }
        if (downloadedType != null) {
            downloadsManager.removeDownload(downloadedType, toast = false) {}
        }
    }

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

    if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) return
    if (Anilist.userid == null) return
    val pending: List<PendingProgressUpdate> = try {
        PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
    } catch (e: Exception) {
        PrefManager.setVal(PrefName.PendingProgressUpdates, listOf<PendingProgressUpdate>())
        return
    }
    if (pending.isEmpty()) return
    toast(currContext()?.getString(R.string.syncing_progress, pending.size))
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (update in pending) {
            try {
                
                val anilistId: Int = if (update.idMAL != null && update.mediaId == update.idMAL) {
                    val type = if (update.isAnime) "ANIME" else "MANGA"
                    val resolved = Anilist.query.getMedia(update.idMAL, mal = true, type = type)?.id
                    if (resolved == null) {
                        if (Anilist.anilistDisabledSignal) break
                        remaining.remove(update)
                        continue
                    }
                    resolved
                } else {
                    update.mediaId
                }
                Anilist.mutation.editList(
                    mediaID = anilistId,
                    progress = update.progress,
                    score = update.score,
                    repeat = update.rewatch,
                    notes = update.notes,
                    status = update.status,
                    private = update.isPrivate,
                    startedAt = update.startDate,
                    completedAt = update.endDate,
                    customList = update.customLists,
                )
                if (!Anilist.anilistDisabledSignal) {
                    remaining.remove(update)
                } else {
                    break
                }
            } catch (_: Exception) {
            }
        }
        PrefManager.setVal(PrefName.PendingProgressUpdates, remaining)
        if (remaining.isEmpty()) {
            toast(currContext()?.getString(R.string.sync_complete))
        } else {
            toast(currContext()?.getString(R.string.sync_partial, remaining.size))
        }
        Refresh.all()
    }
}

// sync changes to anilist in background 
fun syncPendingDeletions() {
    if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) return
    if (Anilist.userid == null) return
    val pending: List<PendingDeletion> = try {
        PrefManager.getVal(PrefName.PendingDeletions, listOf())
    } catch (e: Exception) {
        PrefManager.setVal(PrefName.PendingDeletions, listOf<PendingDeletion>())
        return
    }
    if (pending.isEmpty()) return
    toast(currContext()?.getString(R.string.syncing_deletions, pending.size))
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (deletion in pending) {
            if (Anilist.anilistDisabledSignal) break
            try {
                val anilistId: Int = if (deletion.idMAL != null && deletion.mediaId == deletion.idMAL) {
                    val type = if (deletion.isAnime) "ANIME" else "MANGA"
                    val resolved = Anilist.query.getMedia(deletion.idMAL, mal = true, type = type)?.id
                    if (resolved == null) {
                        if (Anilist.anilistDisabledSignal) break  // AniList down — abort entire sync
                        remaining.remove(deletion)
                        continue
                    }
                    resolved
                } else {
                    deletion.mediaId
                }
                val fakeMedia = emptyMedia().copy(id = anilistId, idMAL = deletion.idMAL)
                val listId = Anilist.query.userMediaDetails(fakeMedia).userListId
                if (listId != null) {
                    Anilist.mutation.deleteList(listId)
                }
                val removeList = PrefManager.getCustomVal("removeList", setOf<Int>())
                PrefManager.setCustomVal("removeList", removeList.minus(anilistId))
                val progressUpdates: List<PendingProgressUpdate> =
                    PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
                val filteredUpdates = progressUpdates.filterNot { update ->
                    update.mediaId == deletion.mediaId ||
                        (deletion.idMAL != null && update.idMAL == deletion.idMAL && update.mediaId == update.idMAL)
                }
                if (filteredUpdates.size != progressUpdates.size) {
                    PrefManager.setVal(PrefName.PendingProgressUpdates, filteredUpdates)
                }
                if (!Anilist.anilistDisabledSignal) {
                    remaining.remove(deletion)
                }
            } catch (_: Exception) {
            }
        }
        PrefManager.setVal(PrefName.PendingDeletions, remaining)
        if (remaining.isEmpty()) {
            toast(currContext()?.getString(R.string.sync_complete))
        } else {
            toast(currContext()?.getString(R.string.sync_partial, remaining.size))
        }

        Refresh.all()
    }
}
