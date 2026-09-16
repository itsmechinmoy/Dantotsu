package ani.dantotsu.media.anime

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

object EpisodeStorage {
    private const val EPISODES_DIR = "episodes_storage"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, EPISODES_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getEpisodeFile(context: Context, sourceKey: String, showLink: String): File {
        val safeKey = sourceKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        val linkHash = java.lang.Long.toHexString(showLink.hashCode().toLong() and 0xFFFFFFFFL)
        return File(getStorageDir(context), "${safeKey}_${linkHash}.bin")
    }

    @Suppress("UNCHECKED_CAST")
    fun loadEpisodes(sourceKey: String, showLink: String): MutableMap<String, Episode>? {
        if (showLink.isBlank()) return null
        val context = currContext() ?: return null
        val file = getEpisodeFile(context, sourceKey, showLink)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gis ->
                    ObjectInputStream(gis).use { ois ->
                        val result = ois.readObject() as? MutableMap<String, Episode>
                        if (!result.isNullOrEmpty()) {
                            Logger.log("EpisodeStorage: Loaded ${result.size} episodes from local storage for source=$sourceKey, link=$showLink")
                        }
                        result
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.log("EpisodeStorage: Error reading local episodes for source=$sourceKey: ${e.message}")
            runCatching { file.delete() }
            null
        }
    }

    fun saveEpisodes(sourceKey: String, showLink: String, episodes: MutableMap<String, Episode>) {
        if (episodes.isEmpty() || showLink.isBlank()) return
        val context = currContext() ?: return
        val file = getEpisodeFile(context, sourceKey, showLink)

        try {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    ObjectOutputStream(gos).use { oos ->
                        oos.writeObject(episodes)
                        oos.flush()
                    }
                }
            }
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
                Logger.log("EpisodeStorage: Saved ${episodes.size} episodes to local storage for source=$sourceKey, link=$showLink")
            }
        } catch (e: Throwable) {
            Logger.log("EpisodeStorage: Error saving episodes for source=$sourceKey: ${e.message}")
        }
    }

    fun removeEpisodes(sourceKey: String, showLink: String) {
        if (showLink.isBlank()) return
        val context = currContext() ?: return
        val file = getEpisodeFile(context, sourceKey, showLink)
        if (file.exists()) {
            file.delete()
        }
    }
}
