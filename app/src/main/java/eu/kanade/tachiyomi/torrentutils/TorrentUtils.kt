package eu.kanade.tachiyomi.torrentutils

import ani.dantotsu.addons.torrent.TorrentAddonManager
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.SocketTimeoutException

object TorrentUtils {
    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        @Suppress("SwallowedException")
        try {
            val extension = Injekt.get<TorrentAddonManager>().extension!!.extension
            val torrent = extension.addTorrent(url, title, "", "", false)
            return TorrentInfo(
                torrent.title,
                torrent.file_stats?.map { file ->
                    TorrentFile(file.path, file.id ?: 0, file.length, torrent.hash!!, emptyList())
                } ?: emptyList(),
                torrent.hash!!,
                torrent.torrent_size!!,
                emptyList(),
            )
        } catch (e: SocketTimeoutException) {
            throw DeadTorrentException()
        }
    }
}
