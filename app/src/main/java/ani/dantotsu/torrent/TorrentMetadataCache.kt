package ani.dantotsu.torrent

import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * Caches bencoded torrent metadata (built from a magnet's info dict) per info-hash so
 * re-adding a previously seen magnet skips fetching metadata from peers.
 * Writes go through a temp file and rename; the oldest entries are pruned past [maxEntries].
 */
class TorrentMetadataCache(private val dir: File, maxEntries: Int = MAX_ENTRIES) {
    private val maxEntries: Int = maxEntries.coerceAtLeast(1)

    @Synchronized
    fun contains(infoHash: String): Boolean {
        val file = fileFor(infoHash) ?: return false
        return file.isFile && file.length() in 1..MAX_BYTES
    }

    @Synchronized
    fun load(infoHash: String): ByteArray? {
        val file = fileFor(infoHash) ?: return null
        if (!file.isFile) return null
        val length = file.length()
        if (length !in 1..MAX_BYTES) {
            deleteQuietly(file)
            return null
        }
        return try {
            val bytes = file.readBytes()
            if (!isBencodedDict(bytes)) {
                deleteQuietly(file)
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: Exception) {
            deleteQuietly(file)
            null
        }
    }

    /**
     * Loads and parses cached metadata for [infoHash]. If [parse] throws or returns null,
     * the cached entry is treated as corrupt, deleted, and ignored.
     */
    fun <T> loadParsed(
        infoHash: String,
        onCorrupt: ((Throwable) -> Unit)? = null,
        parse: (ByteArray) -> T?
    ): T? {
        val bytes = load(infoHash) ?: return null
        return try {
            parse(bytes).also { if (it == null) discard(infoHash) }
        } catch (t: Throwable) {
            onCorrupt?.invoke(t)
            discard(infoHash)
            null
        }
    }

    @Synchronized
    fun save(infoHash: String, bencoded: ByteArray?): Boolean {
        val file = fileFor(infoHash) ?: return false
        if (bencoded == null || bencoded.size !in 1..MAX_BYTES.toInt() || !isBencodedDict(bencoded)) {
            return false
        }
        val tmp = File(dir, file.name + ".tmp")
        return try {
            dir.mkdirs()
            tmp.writeBytes(bencoded)
            val ok = tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
            if (!ok) {
                deleteQuietly(tmp)
            } else {
                prune()
            }
            ok
        } catch (_: Exception) {
            deleteQuietly(tmp)
            false
        }
    }

    /** Drops a corrupt or mismatched entry. */
    @Synchronized
    fun discard(infoHash: String) {
        fileFor(infoHash)?.let { deleteQuietly(it) }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach {
            deleteQuietly(it)
        }
    }

    private fun fileFor(infoHash: String): File? {
        val h = infoHash.trim().uppercase(Locale.US)
        if (!HASH.matches(h)) return null
        return File(dir, h + SUFFIX)
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {}
    }

    companion object {
        const val MAX_ENTRIES = 300

        /** Metadata of a large batch torrent is at most a few MB. */
        const val MAX_BYTES = 8L * 1024 * 1024
        private val WRAP_PREFIX = "d4:info".toByteArray(Charsets.US_ASCII)
        private val WRAP_SUFFIX = "e".toByteArray(Charsets.US_ASCII)
        val MAX_INFO_BYTES = MAX_BYTES.toInt() - WRAP_PREFIX.size - WRAP_SUFFIX.size
        private const val SUFFIX = ".torrent"
        private val HASH = Regex("^[0-9A-F]{40}$")

        private fun isBencodedDict(bytes: ByteArray): Boolean =
            bytes.size >= 2 &&
                bytes.first() == 'd'.code.toByte() &&
                bytes.last() == 'e'.code.toByte()

        /**
         * Wraps a raw bencoded info dict in a minimal `.torrent` (`d4:info<info>e`).
         * Because the info-hash is computed over the info dict, it matches the magnet's hash.
         */
        fun wrapInfoSection(info: ByteArray): ByteArray? {
            if (info.isEmpty() || info.size > MAX_INFO_BYTES || !isBencodedDict(info)) return null
            return WRAP_PREFIX + info + WRAP_SUFFIX
        }

        /** Extracts decoded `tr=` tracker URLs from a magnet link. */
        fun magnetTrackers(magnet: String): List<String> {
            if (!magnet.startsWith("magnet:", ignoreCase = true)) return emptyList()
            return magnet.substringAfter('?', "").split('&')
                .filter { it.startsWith("tr=", ignoreCase = true) }
                .mapNotNull { runCatching { URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }
}
