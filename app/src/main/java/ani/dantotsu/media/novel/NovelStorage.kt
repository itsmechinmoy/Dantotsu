package ani.dantotsu.media.novel

import android.content.Context
import ani.dantotsu.currContext
import ani.dantotsu.parsers.Book
import ani.dantotsu.util.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object NovelStorage {
    private const val NOVELS_DIR = "novels_storage"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, NOVELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getNovelFile(context: Context, sourceKey: String, novelLink: String): File {
        val safeKey = sourceKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        val linkHash = java.lang.Long.toHexString(novelLink.hashCode().toLong() and 0xFFFFFFFFL)
        return File(getStorageDir(context), "${safeKey}_${linkHash}.bin")
    }

    fun loadBook(sourceKey: String, novelLink: String): Book? {
        if (novelLink.isBlank()) return null
        val context = currContext() ?: return null
        val file = getNovelFile(context, sourceKey, novelLink)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gis ->
                    ObjectInputStream(gis).use { ois ->
                        val result = ois.readObject() as? Book
                        if (result != null && result.links.isNotEmpty()) {
                            Logger.log("NovelStorage: Loaded ${result.links.size} chapters from local storage for source=$sourceKey, link=$novelLink")
                        }
                        result
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.log("NovelStorage: Error reading local novel for source=$sourceKey: ${e.message}")
            runCatching { file.delete() }
            null
        }
    }

    fun saveBook(sourceKey: String, novelLink: String, book: Book) {
        if (book.links.isEmpty() || novelLink.isBlank()) return
        val context = currContext() ?: return
        val file = getNovelFile(context, sourceKey, novelLink)

        try {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    ObjectOutputStream(gos).use { oos ->
                        oos.writeObject(book)
                        oos.flush()
                    }
                }
            }
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
                Logger.log("NovelStorage: Saved ${book.links.size} chapters to local storage for source=$sourceKey, link=$novelLink")
            }
        } catch (e: Throwable) {
            Logger.log("NovelStorage: Error saving novel for source=$sourceKey: ${e.message}")
        }
    }

    fun removeBook(sourceKey: String, novelLink: String) {
        if (novelLink.isBlank()) return
        val context = currContext() ?: return
        val file = getNovelFile(context, sourceKey, novelLink)
        if (file.exists()) {
            file.delete()
        }
    }
}
