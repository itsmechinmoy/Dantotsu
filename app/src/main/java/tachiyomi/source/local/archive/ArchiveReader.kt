package tachiyomi.source.local.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(address, size).use {
        block(generateSequence { it.getNextEntry() })
    }

    fun consume(block: (tachiyomi.source.local.archive.ArchiveInputStream) -> Unit) {
        ArchiveInputStream(address, size).use(block)
    }

    fun getInputStream(entryName: String): InputStream? {
        val archive = ArchiveInputStream(address, size)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    override fun close() {
        try {
            Os.munmap(address, size)
        } catch (e: Exception) {
            // Ignored
        }
    }
}

fun DocumentFile.archiveReader(context: Context): ArchiveReader {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Failed to open file descriptor: $uri")
    return pfd.use { ArchiveReader(it) }
}

class ArchiveEntry(
    val name: String,
    val isFile: Boolean,
)
