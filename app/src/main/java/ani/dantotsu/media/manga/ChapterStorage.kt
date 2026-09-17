package ani.dantotsu.media.manga

import android.content.Context
import ani.dantotsu.currContext
import ani.dantotsu.util.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ChapterStorage {
    private const val CHAPTERS_DIR = "chapters_storage"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, CHAPTERS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getChapterFile(context: Context, sourceKey: String, mangaLink: String): File {
        val safeKey = sourceKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        val linkHash = java.lang.Long.toHexString(mangaLink.hashCode().toLong() and 0xFFFFFFFFL)
        return File(getStorageDir(context), "${safeKey}_${linkHash}.bin")
    }

    @Suppress("UNCHECKED_CAST")
    fun loadChapters(sourceKey: String, mangaLink: String): MutableMap<String, MangaChapter>? {
        if (mangaLink.isBlank()) return null
        val context = currContext() ?: return null
        val file = getChapterFile(context, sourceKey, mangaLink)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gis ->
                    ObjectInputStream(gis).use { ois ->
                        val result = ois.readObject() as? MutableMap<String, MangaChapter>
                        if (!result.isNullOrEmpty()) {
                            Logger.log("ChapterStorage: Loaded ${result.size} chapters from local storage for source=$sourceKey, link=$mangaLink")
                        }
                        result
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.log("ChapterStorage: Error reading local chapters for source=$sourceKey: ${e.message}")
            runCatching { file.delete() }
            null
        }
    }

    fun saveChapters(sourceKey: String, mangaLink: String, chapters: MutableMap<String, MangaChapter>) {
        if (chapters.isEmpty() || mangaLink.isBlank()) return
        val context = currContext() ?: return
        val file = getChapterFile(context, sourceKey, mangaLink)

        try {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    ObjectOutputStream(gos).use { oos ->
                        oos.writeObject(chapters)
                        oos.flush()
                    }
                }
            }
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
                Logger.log("ChapterStorage: Saved ${chapters.size} chapters to local storage for source=$sourceKey, link=$mangaLink")
            }
        } catch (e: Throwable) {
            Logger.log("ChapterStorage: Error saving chapters for source=$sourceKey: ${e.message}")
        }
    }

    fun removeChapters(sourceKey: String, mangaLink: String) {
        if (mangaLink.isBlank()) return
        val context = currContext() ?: return
        val file = getChapterFile(context, sourceKey, mangaLink)
        if (file.exists()) {
            file.delete()
        }
    }
}
