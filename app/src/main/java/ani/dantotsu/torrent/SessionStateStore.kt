package ani.dantotsu.torrent

import java.io.File

/**
 * Persists libtorrent session state (DHT routing table and node id) across restarts
 * so peer discovery does not start cold each launch. Writes go through a temp file
 * and rename so an interrupted write never leaves a half-written state file.
 */
class SessionStateStore(private val file: File) {

    @Synchronized
    fun load(): ByteArray? {
        if (!file.isFile) return null
        val length = file.length()
        if (length !in 1..MAX_BYTES) {
            discard()
            return null
        }
        return try {
            file.readBytes()
        } catch (_: Exception) {
            discard()
            null
        }
    }

    /**
     * Loads saved session state and parses it with [parse]. If [parse] throws,
     * the corrupt state file is discarded and null is returned.
     */
    fun <T> loadParsed(
        onCorrupt: ((Throwable) -> Unit)? = null,
        parse: (ByteArray) -> T
    ): T? {
        val bytes = load() ?: return null
        return try {
            parse(bytes)
        } catch (t: Throwable) {
            onCorrupt?.invoke(t)
            discard()
            null
        }
    }

    @Synchronized
    fun save(state: ByteArray?): Boolean {
        if (state == null || state.isEmpty() || state.size > MAX_BYTES) return false
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.writeBytes(state)
            val ok = tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
            if (!ok) {
                try { tmp.delete() } catch (_: Exception) {}
            }
            ok
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    /** Deletes unreadable or corrupt saved state so the next start begins clean. */
    @Synchronized
    fun discard() {
        try {
            file.delete()
        } catch (_: Exception) {}
    }

    companion object {
        /** A DHT state is a few KB; anything larger is treated as invalid. */
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
