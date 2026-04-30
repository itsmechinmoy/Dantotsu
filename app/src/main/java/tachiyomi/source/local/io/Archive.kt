package tachiyomi.source.local.io

import java.io.File

object ArchiveAnime {

    private val SUPPORTED_ARCHIVE_TYPES = listOf("mp4", "mkv")

    fun isSupported(file: File): Boolean = with(file) {
        return extension.lowercase() in SUPPORTED_ARCHIVE_TYPES
    }
}

object ArchiveManga {

    private val SUPPORTED_ARCHIVE_TYPES = listOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt", "epub")

    fun isSupported(file: File): Boolean = with(file) {
        return extension.lowercase() in SUPPORTED_ARCHIVE_TYPES
    }

    fun isSupported(file: androidx.documentfile.provider.DocumentFile): Boolean = with(file) {
        val extension = name?.substringAfterLast('.')?.lowercase() ?: return false
        return extension in SUPPORTED_ARCHIVE_TYPES
    }
}

sealed interface Format {
    data class Directory(val file: androidx.documentfile.provider.DocumentFile) : Format
    data class Archive(val file: androidx.documentfile.provider.DocumentFile) : Format
    data class Epub(val file: androidx.documentfile.provider.DocumentFile) : Format

    companion object {
        fun valueOf(file: androidx.documentfile.provider.DocumentFile): Format? {
            return when {
                file.isDirectory -> Directory(file)
                file.name?.endsWith(".epub", ignoreCase = true) == true -> Epub(file)
                ArchiveManga.isSupported(file) -> Archive(file)
                else -> null
            }
        }
    }
}
