package ani.dantotsu.settings.data

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anggrayudi.storage.file.getAbsolutePath
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingsStorageBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.media.MediaType
import ani.dantotsu.restartApp
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.customRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.toast
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsDataActivity : SettingsScreenActivity() {
    override val screen get() = DataScreen

    internal lateinit var launcher: LauncherWrapper
    internal lateinit var autoBackupDirLauncher: ActivityResultLauncher<Uri?>
    internal lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    /** App cache size in bytes; -1 while it is being measured. */
    internal var cacheSize: Long = -1L

    override fun onScreenCreate(savedInstanceState: Bundle?) {
        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

        autoBackupDirLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    PrefManager.setVal(PrefName.AutoBackupDir, uri.toString())
                    refresh()
                    toast(getString(R.string.success))
                }
            }

        openDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val jsonString =
                            contentResolver.openInputStream(uri)?.readBytes()
                                ?: throw Exception("Error reading file")
                        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
                        if (name.endsWith(".ani")) {
                            val decryptedJson = jsonString.toString(Charsets.UTF_8)
                            if (PreferencePackager.unpack(decryptedJson)) restartApp()
                        } else {
                            toast(getString(R.string.unknown_file_type))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast(getString(R.string.error_importing_settings))
                    }
                }
            }

        calculateCacheSize()
    }

    /** Fills the storage card: device usage and the downloads folder. */
    internal fun bindStorage(b: ItemSettingsStorageBinding) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = totalBytes - availableBytes
            b.storageProgressBar.progress = if (totalBytes > 0) (usedBytes * 100 / totalBytes).toInt() else 0
            b.storageAvailableText.text = getString(
                R.string.storage_available_info,
                Formatter.formatFileSize(this, availableBytes),
                Formatter.formatFileSize(this, totalBytes),
            )
            val displayPath = getReadableDownloadPath(PrefManager.getVal(PrefName.DownloadsDir))
            b.storageDirectoryText.text = getString(R.string.storage_downloads_path, displayPath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getReadableDownloadPath(uriString: String): String {
        val defaultPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path}/Dantotsu"
        if (uriString.isBlank()) return defaultPath

        return try {
            val uri = Uri.parse(uriString)

            val docFile = DocumentFile.fromTreeUri(this, uri)
            val resolvedPath = runCatching { docFile?.getAbsolutePath(this) }.getOrNull()
            if (!resolvedPath.isNullOrBlank() && !resolvedPath.contains("/tree/") && !resolvedPath.contains("msd:")) {
                return resolvedPath
            }

            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: uri.lastPathSegment ?: ""
            resolveFromMediaStore(docId, uriString) ?: resolveFromDocId(docId) ?: defaultPath
        } catch (e: Exception) {
            defaultPath
        }
    }

    private fun extractMediaId(docId: String, uriString: String): String? {
        if (!docId.startsWith("msd:") && !uriString.contains("msd:")) return null
        val mediaId = docId.substringAfterLast(":")
        return if (mediaId.isNotEmpty() && mediaId.all { it.isDigit() }) mediaId else null
    }

    private fun resolveFallbackPathFromCursor(cursor: Cursor): String? {
        val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val rel = if (relIdx != -1) cursor.getString(relIdx) else null
        val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
        return when {
            !rel.isNullOrBlank() -> "/storage/emulated/0/${rel.trimEnd('/')}"
            !name.isNullOrBlank() -> "/storage/emulated/0/Download/$name"
            else -> null
        }
    }

    private fun extractPathFromCursor(cursor: Cursor): String? {
        if (!cursor.moveToFirst()) return null
        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        if (dataIdx != -1) {
            val path = cursor.getString(dataIdx)
            if (!path.isNullOrBlank()) return path
        }
        return resolveFallbackPathFromCursor(cursor)
    }

    private fun resolveFromMediaStore(docId: String, uriString: String): String? {
        val mediaId = extractMediaId(docId, uriString) ?: return null
        val contentUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        return contentResolver.query(
            contentUri,
            projection,
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(mediaId),
            null
        )?.use { extractPathFromCursor(it) }
    }

    private fun resolveFromDocId(docId: String): String? {
        return if (docId.startsWith("primary:")) {
            val subPath = docId.substringAfter("primary:")
            "/storage/emulated/0/$subPath"
        } else if (docId.contains(":")) {
            val split = docId.split(":")
            if (split.size >= 2) "/storage/${split[0]}/${split[1]}" else null
        } else null
    }

    private fun calculateCacheSize() {
        lifecycleScope.launch(Dispatchers.IO) {
            var size = 0L
            cacheDir?.let { size += getFolderSize(it) }
            externalCacheDir?.let { size += getFolderSize(it) }
            cacheSize = size
            withContext(Dispatchers.Main) {
                refresh()
            }
        }
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    internal fun clearAppCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sizeToClear = cacheSize
            try {
                cacheDir?.deleteRecursively()
                externalCacheDir?.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            cacheSize = 0L
            withContext(Dispatchers.Main) {
                val formatted = Formatter.formatFileSize(this@SettingsDataActivity, sizeToClear)
                toast(getString(R.string.cache_cleared, formatted))
                refresh()
                calculateCacheSize()
                refresh()
            }
        }
    }
}

object DataScreen : SettingsScreen(
    "data", R.string.label_data_storage, R.drawable.ic_round_folder_24, R.string.label_data_storage_desc,
) {

    override val activity get() = SettingsDataActivity::class.java

    private val COPY_COUNTS = listOf(1, 2, 3, 5)

    private fun data(host: SettingsHost) = host.activity as SettingsDataActivity

    private fun intervals(c: android.content.Context) = listOf(
        c.getString(R.string.auto_backup_interval_off),
        c.getString(R.string.auto_backup_interval_6h),
        c.getString(R.string.auto_backup_interval_12h),
        c.getString(R.string.auto_backup_interval_24h),
        c.getString(R.string.auto_backup_interval_48h),
        c.getString(R.string.auto_backup_interval_weekly),
    )

    override fun sections() = listOf(
        Section(0, listOf(
            customRow("storage_usage", R.string.storage_usage, R.layout.item_settings_storage, inSection = true) { host, view ->
                data(host).bindStorage(ItemSettingsStorageBinding.bind(view))
            },
            actionRow("clear_cache", R.string.clear_cache, icon = R.drawable.ic_round_delete_24,
                current = { c ->
                    val size = (c as? SettingsDataActivity)?.cacheSize ?: -1L
                    c.getString(R.string.clear_cache_desc,
                        if (size > 0) Formatter.formatFileSize(c, size) else c.getString(R.string.cache_calculating))
                },
                onClick = { host, _ ->
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.clear_cache)
                        setMessage(R.string.clear_cache)
                        setPosButton(R.string.ok) { data(host).clearAppCache() }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }),
            choiceRow(PrefName.AutoBackupInterval, R.string.auto_backup_interval, R.drawable.ic_round_auto_awesome_24,
                labels = ::intervals,
                onChange = { host, which -> AutoBackupWorker.schedule(host.activity, which) }),
            actionRow(PrefName.AutoBackupDir.name, R.string.auto_backup_directory, icon = R.drawable.ic_round_folder_24,
                keys = listOf(PrefName.AutoBackupDir),
                current = { c ->
                    val uriStr = PrefManager.getVal<String>(PrefName.AutoBackupDir)
                    if (uriStr.isNotBlank()) Uri.parse(uriStr).path ?: uriStr
                    else c.getString(R.string.auto_backup_dir_default)
                },
                onClick = { host, _ -> data(host).autoBackupDirLauncher.launch(null) }),
            Row(
                id = PrefName.AutoBackupMaxCopies.name, title = R.string.auto_backup_max_copies,
                icon = R.drawable.backup_restore, keys = listOf(PrefName.AutoBackupMaxCopies),
                kind = Kind.Choice(
                    labels = { c -> COPY_COUNTS.map { c.getString(R.string.backups_to_retain_count, it) } },
                    get = { COPY_COUNTS.indexOf(PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)).coerceAtLeast(0) },
                    set = { _, i -> PrefManager.setVal(PrefName.AutoBackupMaxCopies, COPY_COUNTS[i]) },
                ),
            ),
            actionRow("last_auto_backup", R.string.auto_backup, icon = R.drawable.backup_restore,
                keys = listOf(PrefName.LastAutoBackupTimestamp),
                current = { c ->
                    val timestamp = PrefManager.getVal<Long>(PrefName.LastAutoBackupTimestamp)
                    if (timestamp > 0) {
                        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
                        c.getString(R.string.last_auto_backup, formattedDate)
                    } else {
                        c.getString(R.string.last_auto_backup_never)
                    }
                },
                onClick = null),
            actionRow("backup_now", R.string.backup_now, R.string.backup_now_desc, R.drawable.backup_restore,
                onClick = { host, _ ->
                    val context = host.activity
                    StoragePermissions.downloadsPermission(context)
                    val exportableLocations = Location.entries.filter { it.exportable }
                    val timestampStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                    savePrefsToDownloads(
                        "Dantotsu_${timestampStr}",
                        PrefManager.exportAllPrefs(exportableLocations),
                        context
                    )
                }),
            actionRow("restore_backup", R.string.restore_backup, R.string.restore_backup_desc, R.drawable.backup_restore,
                onClick = { host, _ ->
                    StoragePermissions.downloadsPermission(host.activity)
                    data(host).openDocumentLauncher.launch(arrayOf("*/*"))
                }),
            actionRow("change_download_directory", R.string.change_download_directory,
                R.string.change_download_directory_desc, R.drawable.ic_round_folder_24,
                keys = listOf(PrefName.DownloadsDir),
                onClick = { host, _ -> changeDownloadDirectory(host) }),
            actionRow("data_rebuild_download_index", R.string.rebuild_download_index, R.string.rebuild_download_index_desc,
                R.drawable.ic_download_24,
                onClick = { host, _ -> rebuildIndex(host) }),
            actionRow("purge_downloads", R.string.purge_downloads, R.string.purge_downloads_desc,
                R.drawable.ic_round_delete_24,
                onClick = { host, _ ->
                    val c = host.activity
                    c.customAlertDialog().apply {
                        setTitle(R.string.purge_downloads)
                        setMessage(R.string.purge_confirm, "${c.getString(R.string.anime)} & ${c.getString(R.string.manga)}")
                        setPosButton(R.string.yes) {
                            val downloadsManager = Injekt.get<DownloadsManager>()
                            downloadsManager.purgeDownloads(MediaType.ANIME)
                            downloadsManager.purgeDownloads(MediaType.MANGA)
                            host.refresh()
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }),
        )),
    )

    private fun changeDownloadDirectory(host: SettingsHost) = with(data(host)) {
        val context = this
        val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
        customAlertDialog().apply {
            setTitle(R.string.change_download_directory)
            setMessage(R.string.change_download_directory_confirm)
            setPosButton(R.string.ok) {
                launcher.registerForCallback { success ->
                    if (success) {
                        toast(getString(R.string.please_wait))
                        val newUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                        lifecycleScope.launch(Dispatchers.IO) {
                            Injekt.get<DownloadsManager>().moveDownloadsDir(
                                context,
                                Uri.parse(oldUri),
                                Uri.parse(newUri),
                            ) { finished, message ->
                                if (finished) {
                                    toast(getString(R.string.success))
                                    runOnUiThread { refresh() }
                                } else {
                                    toast(message)
                                }
                            }
                        }
                    } else {
                        toast(getString(R.string.error))
                    }
                }
                launcher.launch()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun rebuildIndex(host: SettingsHost) = with(data(host)) {
        customAlertDialog().apply {
            setTitle(R.string.rebuild_download_index)
            setMessage(R.string.rebuild_download_index_msg)
            setPosButton(R.string.ok) {
                toast(getString(R.string.please_wait))
                lifecycleScope.launch(Dispatchers.IO) {
                    val recovered = Injekt.get<DownloadsManager>().rebuildIndexFromDisk()
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.rebuild_download_index_done, recovered))
                        refresh()
                    }
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
