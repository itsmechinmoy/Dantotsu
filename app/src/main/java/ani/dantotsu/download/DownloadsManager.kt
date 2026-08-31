package ani.dantotsu.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.download.DownloadCompat.Companion.removeDownloadCompat
import ani.dantotsu.download.DownloadCompat.Companion.removeMediaCompat
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.*
import com.anggrayudi.storage.file.*
import com.anggrayudi.storage.transfer.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.io.Serializable
import kotlin.math.ln
import kotlin.math.pow

@Inject
@SingleIn(AppScope::class)
class DownloadsManager(private val context: Context) {
    private val gson = Gson()
    private val downloadsList = loadDownloads().toMutableList()

    val mangaDownloadedTypes: List<DownloadedType>
        get() = downloadsList.filter { it.type == MediaType.MANGA }
    val animeDownloadedTypes: List<DownloadedType>
        get() = downloadsList.filter { it.type == MediaType.ANIME }
    val novelDownloadedTypes: List<DownloadedType>
        get() = downloadsList.filter { it.type == MediaType.NOVEL }

    private fun saveDownloads() {
        val jsonString = gson.toJson(downloadsList)
        PrefManager.setVal(PrefName.DownloadsKeys, jsonString)
    }

    private fun loadDownloads(): List<DownloadedType> {
        val jsonString = PrefManager.getVal(PrefName.DownloadsKeys, null as String?)
        return if (jsonString != null) {
            val type = object : TypeToken<List<DownloadedType>>() {}.type
            gson.fromJson(jsonString, type)
        } else {
            emptyList()
        }
    }

    fun addDownload(downloadedType: DownloadedType) {
        downloadsList.add(downloadedType)
        saveDownloads()
    }

    fun removeDownload(
        downloadedType: DownloadedType,
        toast: Boolean = true,
        onFinished: () -> Unit
    ) {
        removeDownloadCompat(context, downloadedType, toast)
        downloadsList.removeAll { it.titleName == downloadedType.titleName && it.chapterName == downloadedType.chapterName }
        CoroutineScope(Dispatchers.IO).launch {
            removeDirectory(downloadedType, toast)
            withContext(Dispatchers.Main) {
                onFinished()
            }
        }
        saveDownloads()
    }

    fun getSize(downloadedType: DownloadedType): Double {
        val index = downloadsList.indexOfFirst { it.titleName == downloadedType.titleName && it.chapterName == downloadedType.chapterName }
        if (index == -1) return 0.0
        if(downloadedType.size == null) {
            val episodeSize = bytesToDouble(
                getDirSize(
                    context,
                    MediaType.ANIME,
                    downloadedType.titleName,
                    downloadedType.chapterName
                )
            )
            downloadsList[index].size = episodeSize
            saveDownloads()
            return episodeSize
        }
        else
            return downloadedType.size ?: 0.0
    }

    fun removeMedia(title: String, type: MediaType) {
        removeMediaCompat(context, title, type)
        val baseDirectory = getBaseDirectory(context, type)
        val directory = baseDirectory?.findFolder(title)
        if (directory?.exists() == true) {
            val deleted = directory.delete()
            if (deleted) {
                snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
            cleanDownloads()
        }
        when (type) {
            MediaType.MANGA -> {
                downloadsList.removeAll { it.titleName == title && it.type == MediaType.MANGA }
            }

            MediaType.ANIME -> {
                downloadsList.removeAll { it.titleName == title && it.type == MediaType.ANIME }
            }

            MediaType.NOVEL -> {
                downloadsList.removeAll { it.titleName == title && it.type == MediaType.NOVEL }
            }
        }
        saveDownloads()
    }

    private fun cleanDownloads() {
        cleanDownload(MediaType.MANGA)
        cleanDownload(MediaType.ANIME)
        cleanDownload(MediaType.NOVEL)
    }

    private fun cleanDownload(type: MediaType) {
        // remove all folders that are not in the downloads list
        val directory = getBaseDirectory(context, type)
        val downloadsSubLists = when (type) {
            MediaType.MANGA -> mangaDownloadedTypes
            MediaType.ANIME -> animeDownloadedTypes
            else -> novelDownloadedTypes
        }
        if (directory?.exists() == true && directory.isDirectory) {
            val files = directory.listFiles()
            for (file in files) {
                if (!downloadsSubLists.any { it.titleName == file.name }) {
                    file.delete()
                }
            }
        }
        //now remove all downloads that do not have a folder
        val iterator = downloadsList.iterator()
        while (iterator.hasNext()) {
            val download = iterator.next()
            val downloadDir = directory?.findFolder(download.titleName)
            if ((downloadDir?.exists() == false && download.type == type) || download.titleName.isBlank()) {
                iterator.remove()
            }
        }
    }

    fun moveDownloadsDir(
        context: Context,
        oldUri: Uri,
        newUri: Uri,
        finished: (Boolean, String) -> Unit
    ) {
        if (oldUri == newUri) {
            Logger.log("Source and destination are the same")
            finished(false, "Source and destination are the same")
            return
        }
        if (oldUri == Uri.EMPTY) {
            Logger.log("Old Uri is empty")
            finished(true, "Old Uri is empty")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val oldBase =
                    DocumentFile.fromTreeUri(context, oldUri) ?: throw Exception("Old base is null")
                val newBase =
                    DocumentFile.fromTreeUri(context, newUri) ?: throw Exception("New base is null")
                val folder =
                    oldBase.findFolder(BASE_LOCATION) ?: throw Exception("Base folder not found")

                val sourceStorageFile = folder.toStorageFile(context)
                val targetStorageFile = newBase.toStorageFile(context)

                val result = sourceStorageFile.moveTo(targetStorageFile)

                when (result) {
                    is TransferResult.Success<*> -> {
                        finished(true, "Successfully moved downloads")
                    }

                    is TransferResult.Skipped -> {
                        finished(false, "Move skipped")
                    }

                    is TransferResult.Failure -> {
                        val message = result.message ?: "Failed to move downloads"
                        Logger.log("Failed to move downloads: $message")
                        finished(false, message)
                    }
                }

            } catch (e: Exception) {
                snackString("Error: ${e.message}")
                Logger.log("Failed to move downloads: ${e.message}")
                Logger.log(e)
                Logger.log("oldUri: $oldUri, newUri: $newUri")
                finished(false, "Failed to move downloads: ${e.message}")
                return@launch
            }
        }
    }

    fun queryDownload(downloadedType: DownloadedType): Boolean {
        return downloadsList.any {
            it.type == downloadedType.type &&
                    it.titleName.equals(downloadedType.titleName, ignoreCase = true) &&
                    it.chapterName.equals(downloadedType.chapterName, ignoreCase = true)
        }
    }

    fun queryDownload(title: String, chapter: String, type: MediaType? = null): Boolean {
        return if (type == null) {
            downloadsList.any { it.titleName.equals(title, ignoreCase = true) && it.chapterName.equals(chapter, ignoreCase = true) }
        } else {
            downloadsList.any { it.titleName.equals(title, ignoreCase = true) && it.chapterName.equals(chapter, ignoreCase = true) && it.type == type }
        }
    }

    private fun removeDirectory(downloadedType: DownloadedType, toast: Boolean) {
        val baseDirectory = getBaseDirectory(context, downloadedType.type)
        val directory =
            baseDirectory?.findFolder(downloadedType.titleName)
                ?.findFolder(downloadedType.chapterName)
        downloadsList.removeAll { it.titleName == downloadedType.titleName && it.chapterName == downloadedType.chapterName }
        // Check if the directory exists and delete it recursively
        if (directory?.exists() == true) {
            val deleted = directory.delete()
            if (deleted) {
                if (toast) snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
        }
    }

    fun purgeDownloads(type: MediaType) {
        val directory = getBaseDirectory(context, type)
        if (directory?.exists() == true) {
            val deleted = directory.delete()
            if (deleted) {
                snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
        }

        downloadsList.removeAll { it.type == type }
        saveDownloads()
    }

    companion object {
        private const val BASE_LOCATION = "Dantotsu"
        private const val MANGA_SUB_LOCATION = "Manga"
        private const val ANIME_SUB_LOCATION = "Anime"
        private const val NOVEL_SUB_LOCATION = "Novel"


        /**
         * Get and create a base directory for the given type
         * @param context the context
         * @param type the type of media
         * @return the base directory
         */
        @Synchronized
        private fun getBaseDirectory(context: Context, type: MediaType): DocumentFile? {
            val baseDirectory = Uri.parse(PrefManager.getVal<String>(PrefName.DownloadsDir))
            if (baseDirectory == Uri.EMPTY) return null
            var base = DocumentFile.fromTreeUri(context, baseDirectory) ?: return null
            base = base.findOrCreateFolder(BASE_LOCATION, false) ?: return null
            return when (type) {
                MediaType.MANGA -> {
                    base.findOrCreateFolder(MANGA_SUB_LOCATION, false)
                }

                MediaType.ANIME -> {
                    base.findOrCreateFolder(ANIME_SUB_LOCATION, false)
                }

                else -> {
                    base.findOrCreateFolder(NOVEL_SUB_LOCATION, false)
                }
            }
        }

        /**
         * Get and create a subdirectory for the given type
         * @param context the context
         * @param type the type of media
         * @param title the title of the media
         * @param chapter the chapter of the media
         * @return the subdirectory
         */
        @Synchronized
        fun getSubDirectory(
            context: Context,
            type: MediaType,
            overwrite: Boolean,
            title: String,
            chapter: String? = null
        ): DocumentFile? {
            val baseDirectory = getBaseDirectory(context, type) ?: return null
            val safeTitle = title.findValidName()
            val safeChapter = chapter?.findValidName()
            return if (safeChapter != null && safeChapter.isNotEmpty()) {
                baseDirectory.findOrCreateFolder(safeTitle, false)
                    ?.findOrCreateFolder(safeChapter, overwrite)
            } else {
                baseDirectory.findOrCreateFolder(safeTitle, overwrite)
            }
        }

        fun getDirSize(
            context: Context,
            type: MediaType,
            title: String,
            chapter: String? = null
        ): Long {
            val directory = getSubDirectory(context, type, false, title, chapter) ?: return 0
            var size = 0L
            directory.listFiles().forEach {
                size += it.length()
            }
            return size
        }

        fun addNoMedia(context: Context) {
            val baseDirectory = getBaseDirectory(context) ?: return
            if (baseDirectory.findFile(".nomedia") == null) {
                baseDirectory.createFile("application/octet-stream", ".nomedia")
            }
        }

        @Synchronized
        private fun getBaseDirectory(context: Context): DocumentFile? {
            val baseDirectory = Uri.parse(PrefManager.getVal<String>(PrefName.DownloadsDir))
            if (baseDirectory == Uri.EMPTY) return null
            val base = DocumentFile.fromTreeUri(context, baseDirectory) ?: return null
            return base.findOrCreateFolder(BASE_LOCATION, false)
        }

        private val lock = Any()

        private fun DocumentFile.findOrCreateFolder(
            name: String, overwrite: Boolean
        ): DocumentFile? {
            val validName = name.findValidName()
            synchronized(lock) {
                return if (overwrite) {
                    findFolder(validName)?.delete()
                    createDirectory(validName)
                } else {
                    val folder = findFolder(validName)
                    folder ?: createDirectory(validName)
                }
            }
        }

        private fun DocumentFile.findFolder(name: String): DocumentFile? {
            val validName = name.findValidName()
            val direct = findFile(validName)
            if (direct != null && direct.isDirectory) return direct
            val list = listFiles()
            val exact = list.find { it.isDirectory && it.name?.findValidName().equals(validName, ignoreCase = true) }
            if (exact != null) return exact

            val baseNameWithoutSuffix = validName.replace(Regex("\\s*\\(\\d+\\)$"), "")
            val withoutSuffixMatch = list.find {
                it.isDirectory && it.name?.findValidName()?.replace(Regex("\\s*\\(\\d+\\)$"), "").equals(baseNameWithoutSuffix, ignoreCase = true)
            }
            if (withoutSuffixMatch != null) return withoutSuffixMatch

            return list.find { it.isDirectory && it.name != null && validName.compareName(it.name!!) }
        }

        private const val RATIO_THRESHOLD = 95
        fun Media.compareName(name: String): Boolean {
            val mainName = mainName().findValidName().lowercase()
            val compareName = name.findValidName().lowercase()
            val ratio = FuzzySearch.ratio(mainName, compareName)
            return ratio > RATIO_THRESHOLD
        }

        fun String.compareName(name: String): Boolean {
            val mainName = findValidName().lowercase()
            val compareName = name.findValidName().lowercase()
            val ratio = FuzzySearch.ratio(mainName, compareName)
            return ratio > RATIO_THRESHOLD
        }

        fun buildResumableRequest(url: String, headers: okhttp3.Headers = okhttp3.Headers.Builder().build(), existingSize: Long = 0L): okhttp3.Request {
            return okhttp3.Request.Builder()
                .url(url)
                .headers(headers)
                .apply {
                    if (existingSize > 0) {
                        header("Range", "bytes=$existingSize-")
                    }
                }
                .build()
        }
    }
}

private const val RESERVED_CHARS = "|\\?*<\":>+[]/'"
fun String?.findValidName(): String {
    return this?.replace("/", "_")
        ?.filterNot { RESERVED_CHARS.contains(it) }
        ?.replace(Regex("\\s+"), " ")
        ?.trim() ?: ""
}

data class DownloadedType(
    private val pTitle: String?,
    private val pChapter: String?,
    val type: MediaType,
    @Deprecated("use pTitle instead")
    private val title: String? = null,
    @Deprecated("use pChapter instead")
    private val chapter: String? = null,
    var size: Double? = null,
    val scanlator: String = "Unknown"
) : Serializable {
    val titleName: String
        get() = title ?: pTitle.findValidName()
    val chapterName: String
        get() = chapter ?: pChapter.findValidName()
    val uniqueName: String
        get() = "$chapterName-${scanlator}"
}

private fun bytesToDouble(bytes: Long): Double {
    if (bytes <= 0) return 0.0
    val unit = 1000
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    return bytes / unit.toDouble().pow(exp.toDouble())
}
