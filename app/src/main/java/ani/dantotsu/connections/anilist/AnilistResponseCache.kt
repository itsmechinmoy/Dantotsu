package ani.dantotsu.connections.anilist

import java.io.File
import java.security.MessageDigest

enum class AnilistCacheCategory {
    STATIC,
    USER_PROFILE,
    HOME,
    STORIES,
    BANNERS,
    CATALOG,
    MEDIA_DETAILS,
    USER_FAVS,
    USER_LIST,
    SEARCH,
}

data class AnilistCacheSpec(
    val category: AnilistCacheCategory,
    val freshTtlMs: Long,
    val maxStaleTtlMs: Long = freshTtlMs,
    val mediaId: Int? = null,
    val persistToDisk: Boolean = false,
    val cacheKeyOverride: String? = null,
)

sealed class CacheLookup {
    data object Miss : CacheLookup()
    data class Fresh(val value: String, val storedAtMs: Long) : CacheLookup()
    data class Stale(val value: String, val storedAtMs: Long) : CacheLookup()
}

/**
 * In-memory LRU + optional disk TTL cache for AniList GraphQL responses.
 * Pure Kotlin (no Android framework dependencies) so TTL, stale-while-revalidate,
 * invalidation, and disk round-trip contracts are unit-testable on the JVM.
 */
class AnilistResponseCache(
    private val maxMemoryEntries: Int = 80,
    private val maxDiskEntries: Int = 24,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val diskDirProvider: () -> File? = { null },
) {
    private data class MemoryEntry(
        val value: String,
        val storedAtMs: Long,
        val freshTtlMs: Long,
        val maxStaleTtlMs: Long,
        val category: AnilistCacheCategory,
        val mediaId: Int?,
    )

    private val lock = Any()
    private val memory = object : LinkedHashMap<String, MemoryEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoryEntry>?): Boolean {
            return size > maxMemoryEntries
        }
    }

    fun get(
        key: String,
        category: AnilistCacheCategory? = null,
        checkDisk: Boolean = false,
    ): CacheLookup {
        val now = nowMs()
        val memEntry = synchronized(lock) { memory[key] }
        if (memEntry != null) {
            return evaluateEntry(key, memEntry, now, category, checkDisk, fromDisk = false)
        }
        if (!checkDisk || category == null) return CacheLookup.Miss
        val diskEntry = readDiskEntry(category, key) ?: return CacheLookup.Miss
        return evaluateEntry(key, diskEntry, now, category, checkDisk = true, fromDisk = true)
    }

    private fun evaluateEntry(
        key: String,
        entry: MemoryEntry,
        now: Long,
        category: AnilistCacheCategory?,
        checkDisk: Boolean,
        fromDisk: Boolean,
    ): CacheLookup {
        val age = now - entry.storedAtMs
        if (age < 0L || age > entry.maxStaleTtlMs) {
            if (!fromDisk) {
                synchronized(lock) {
                    if (memory[key] === entry) memory.remove(key)
                }
            }
            if (checkDisk && category != null) deleteDiskFile(category, key)
            return CacheLookup.Miss
        }
        if (fromDisk) {
            synchronized(lock) { memory[key] = entry }
        }
        return if (age <= entry.freshTtlMs) {
            CacheLookup.Fresh(entry.value, entry.storedAtMs)
        } else {
            CacheLookup.Stale(entry.value, entry.storedAtMs)
        }
    }

    fun put(
        key: String,
        value: String,
        spec: AnilistCacheSpec,
    ) {
        if (spec.freshTtlMs <= 0L && spec.maxStaleTtlMs <= 0L) return
        val storedAt = nowMs()
        val entry = MemoryEntry(
            value = value,
            storedAtMs = storedAt,
            freshTtlMs = spec.freshTtlMs,
            maxStaleTtlMs = maxOf(spec.freshTtlMs, spec.maxStaleTtlMs),
            category = spec.category,
            mediaId = spec.mediaId,
        )
        synchronized(lock) {
            memory[key] = entry
        }
        if (spec.persistToDisk) {
            writeDiskEntry(spec.category, key, entry)
        }
    }

    fun invalidateCategory(category: AnilistCacheCategory) {
        synchronized(lock) {
            val it = memory.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.category == category) {
                    it.remove()
                }
            }
        }
        val dir = safeDiskDir() ?: return
        val prefix = "${category.name}_"
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith(prefix)) {
                    file.delete()
                }
            }
        }
    }

    fun invalidateMedia(mediaId: Int) {
        synchronized(lock) {
            val it = memory.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next().value
                if (entry.category == AnilistCacheCategory.MEDIA_DETAILS && entry.mediaId == mediaId) {
                    it.remove()
                }
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            memory.clear()
        }
        val dir = safeDiskDir() ?: return
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun memorySize(): Int = synchronized(lock) { memory.size }

    private fun safeDiskDir(): File? = runCatching { diskDirProvider() }.getOrNull()

    private fun diskFile(category: AnilistCacheCategory, key: String): File? {
        val dir = safeDiskDir() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "${category.name}_${sha256Hex(key)}.cache")
    }

    private fun deleteDiskFile(category: AnilistCacheCategory, key: String) {
        runCatching { diskFile(category, key)?.delete() }
    }

    private fun writeDiskEntry(category: AnilistCacheCategory, key: String, entry: MemoryEntry) {
        runCatching {
            val target = diskFile(category, key) ?: return
            val dir = target.parentFile ?: return
            val tmp = File(dir, "${target.name}.tmp")
            val header = "${entry.storedAtMs} ${entry.freshTtlMs} ${entry.maxStaleTtlMs} ${entry.category.name} ${entry.mediaId ?: -1}\n"
            tmp.writeText(header + entry.value, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(header + entry.value, Charsets.UTF_8)
                tmp.delete()
            }
            trimDiskEntries(dir)
        }
    }

    private fun readDiskEntry(category: AnilistCacheCategory, key: String): MemoryEntry? {
        return runCatching {
            val file = diskFile(category, key) ?: return null
            if (!file.isFile) return null
            val content = file.readText(Charsets.UTF_8)
            val nl = content.indexOf('\n')
            if (nl <= 0) return null
            val headerParts = content.substring(0, nl).split(' ')
            if (headerParts.size < 4) return null
            val storedAt = headerParts[0].toLongOrNull() ?: return null
            val freshTtl = headerParts[1].toLongOrNull() ?: return null
            val maxStaleTtl = headerParts[2].toLongOrNull() ?: return null
            val parsedCategory = runCatching { AnilistCacheCategory.valueOf(headerParts[3]) }.getOrNull() ?: return null
            val mediaId = headerParts.getOrNull(4)?.toIntOrNull()?.takeIf { it >= 0 }
            val payload = content.substring(nl + 1)
            MemoryEntry(
                value = payload,
                storedAtMs = storedAt,
                freshTtlMs = freshTtl,
                maxStaleTtlMs = maxStaleTtl,
                category = parsedCategory,
                mediaId = mediaId,
            )
        }.getOrNull()
    }

    private fun trimDiskEntries(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".cache") } ?: return
        if (files.size <= maxDiskEntries) return
        files.sortBy { it.lastModified() }
        val toRemove = files.size - maxDiskEntries
        for (i in 0 until toRemove) {
            files[i].delete()
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private val HEX = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )
    }
}
