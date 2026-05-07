package ani.dantotsu.addons.torrent

import eu.kanade.tachiyomi.data.torrentServer.model.Torrent

data class TorrentStreamingSettings(
    val enableDLNA: Boolean = false,
    val friendlyName: String = "Dantotsu",
    val disableUTP: Boolean = false,
    val disableDHT: Boolean = false,
    val disablePEX: Boolean = false,
    val enableIPv6: Boolean = false,
    val disableUPNP: Boolean = false,
    val nzbDomain: String = "",
    val nzbPort: Int = 0,
    val nzbLogin: String = "",
    val nzbPassword: String = "",
    val nzbPoolSize: Int = 4,
)

interface TorrentAddonApi {

    // Default no-op for compatibility with existing add-on builds that don't support these settings.
    fun applyStreamingSettings(settings: TorrentStreamingSettings) {}

    fun startServer(path: String, settings: TorrentStreamingSettings) {
        applyStreamingSettings(settings)
        startServer(path)
    }

    fun startServer(path: String)

    fun stopServer()

    fun echo(): String

    fun removeTorrent(torrent: String)

    fun addTorrent(
        link: String,
        title: String,
        poster: String,
        data: String,
        save: Boolean,
    ): Torrent

    fun addTorrent(
        link: String,
        title: String,
        save: Boolean,
    ): Torrent {
        return addTorrent(link, title, "", "", save)
    }

    fun uploadTorrent(
        file: ByteArray,
        fileName: String,
        title: String,
        save: Boolean,
    ): Torrent {
        throw UnsupportedOperationException("Torrent upload is not supported by this add-on version")
    }

    fun getLink(torrent: Torrent, index: Int): String
}
