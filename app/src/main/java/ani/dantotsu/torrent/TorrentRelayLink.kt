package ani.dantotsu.torrent

/** A link to the local torrent relay (TorrentServerManager.getLink), parsed back. */
data class TorrentRelayLink(val hash: String, val index: Int) {
    companion object {
        private val PATTERN =
            Regex("""^http://127\.0\.0\.1:\d+/stream(?:/[^?]*)?\?hash=([0-9A-Fa-f]{40})&index=(\d+)$""")

        fun parse(url: String): TorrentRelayLink? {
            val m = PATTERN.matchEntire(url.trim()) ?: return null
            val index = m.groupValues[2].toIntOrNull() ?: return null
            return TorrentRelayLink(m.groupValues[1].uppercase(), index)
        }
    }
}

/** Shared parsing for torrent links handed around the player and picker. */
object TorrentLinks {
    private val INDEX = Regex("""[?&]index=(\d+)""")

    /**
     * The file index in a magnet/relay link (`index=N`), 0 when absent. Stops at the next
     * parameter so trailing query parameters do not break parsing.
     */
    fun fileIndex(url: String): Int =
        INDEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
