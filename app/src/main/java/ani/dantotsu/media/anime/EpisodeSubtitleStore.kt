package ani.dantotsu.media.anime

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object EpisodeSubtitleStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SavedEpisodeSubtitle(
        val id: String,
        val displayName: String,
        val provider: String,
        val language: String,
        val filePath: String? = null,
        val url: String? = null
    )

    private fun getPrefKey(mediaId: Int, episodeNumber: String): String =
        "saved_ep_online_sub_${mediaId}_$episodeNumber"

    fun saveSubtitle(
        context: Context,
        mediaId: Int,
        episodeNumber: String,
        sub: SavedEpisodeSubtitle,
        sourceFile: File? = null
    ) {
        try {
            var persistentPath = sub.filePath
            if (sourceFile != null && sourceFile.exists()) {
                val savedSubsDir = File(context.filesDir, "saved_online_subs").apply { mkdirs() }
                val ext = sourceFile.extension.ifBlank { "srt" }
                val destFile = File(savedSubsDir, "sub_${mediaId}_${episodeNumber}_${sub.id.hashCode()}.$ext")
                sourceFile.copyTo(destFile, overwrite = true)
                persistentPath = destFile.absolutePath
            }
            val record = sub.copy(filePath = persistentPath)
            val jsonStr = json.encodeToString(record)
            PrefManager.setCustomVal(getPrefKey(mediaId, episodeNumber), jsonStr)
            Logger.log("EpisodeSubtitleStore: Saved online sub for media $mediaId ep $episodeNumber: ${sub.displayName}")
        } catch (e: Exception) {
            Logger.log("EpisodeSubtitleStore: Error saving sub: ${e.message}")
        }
    }

    fun getSavedSubtitle(mediaId: Int, episodeNumber: String): SavedEpisodeSubtitle? {
        val jsonStr: String = PrefManager.getNullableCustomVal(getPrefKey(mediaId, episodeNumber), null, String::class.java)
            ?: return null
        return try {
            json.decodeFromString<SavedEpisodeSubtitle>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun clearSavedSubtitle(context: Context, mediaId: Int, episodeNumber: String) {
        val saved = getSavedSubtitle(mediaId, episodeNumber)
        if (saved?.filePath != null) {
            runCatching { File(saved.filePath).delete() }
        }
        PrefManager.setCustomVal(getPrefKey(mediaId, episodeNumber), null)
        Logger.log("EpisodeSubtitleStore: Cleared saved online sub for media $mediaId ep $episodeNumber")
    }
}
