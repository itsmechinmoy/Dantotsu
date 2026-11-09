package ani.dantotsu.settings.data

import android.annotation.SuppressLint
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anggrayudi.storage.file.getAbsolutePath
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsDataBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.restartApp
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.SettingsAdapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
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

class SettingsDataActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsDataBinding
    private lateinit var launcher: LauncherWrapper
    private var cacheSize: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val context = this
        binding.settingsDataLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.dataSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

        val openDocumentLauncher =
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

        updateStorageInfo()
        setupRecyclerView(openDocumentLauncher)
        calculateCacheSize()
    }

    private fun updateStorageInfo() {
        try {
            val path = Environment.getDataDirectory().path
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - availableBytes

            val progress = if (totalBytes > 0) (usedBytes * 100 / totalBytes).toInt() else 0
            binding.storageProgressBar.progress = progress

            val freeFormatted = Formatter.formatFileSize(this, availableBytes)
            val totalFormatted = Formatter.formatFileSize(this, totalBytes)
            binding.storageAvailableText.text =
                getString(R.string.storage_available_info, freeFormatted, totalFormatted)

            val downloadsDirUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
            val displayPath = getReadableDownloadPath(downloadsDirUri)
            binding.storageDirectoryText.text = "Downloads: $displayPath"
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
                binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
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

    private fun clearAppCache() {
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
                binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                calculateCacheSize()
                updateStorageInfo()
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun setupRecyclerView(openDocumentLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        val context = this
        val intervals = arrayOf(
            getString(R.string.auto_backup_interval_off),
            getString(R.string.auto_backup_interval_6h),
            getString(R.string.auto_backup_interval_12h),
            getString(R.string.auto_backup_interval_24h),
            getString(R.string.auto_backup_interval_48h),
            getString(R.string.auto_backup_interval_weekly)
        )
        val copyCounts = arrayOf("1", "2", "3", "5")

        val settingsList = arrayListOf(
            // Clear Cache
            Settings(
                type = 1,
                name = getString(R.string.clear_cache),
                desc = getString(
                    R.string.clear_cache_desc,
                    if (cacheSize > 0) Formatter.formatFileSize(context, cacheSize) else getString(R.string.cache_calculating)
                ),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.clear_cache)
                        setMessage(R.string.clear_cache)
                        setPosButton(R.string.ok) {
                            clearAppCache()
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            // Auto Backup Interval
            Settings(
                type = 1,
                name = getString(R.string.auto_backup_interval),
                desc = intervals.getOrElse(PrefManager.getVal(PrefName.AutoBackupInterval)) { intervals[0] },
                icon = R.drawable.ic_round_auto_awesome_24,
                onClick = {
                    val current = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
                    context.customAlertDialog().apply {
                        setTitle(R.string.auto_backup_interval)
                        singleChoiceItems(intervals, current) { which ->
                            PrefManager.setVal(PrefName.AutoBackupInterval, which)
                            AutoBackupWorker.schedule(context, which)
                            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                        }
                        setPosButton(R.string.ok)
                        show()
                    }
                }
            ),

            // Auto Backup Max Copies
            Settings(
                type = 1,
                name = getString(R.string.auto_backup_max_copies),
                desc = "${PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)} backups",
                icon = R.drawable.backup_restore,
                onClick = {
                    val current = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)
                    val currentIndex = copyCounts.indexOf(current.toString()).coerceAtLeast(0)
                    context.customAlertDialog().apply {
                        setTitle(R.string.auto_backup_max_copies)
                        singleChoiceItems(copyCounts, currentIndex) { which ->
                            val copies = copyCounts[which].toIntOrNull() ?: 3
                            PrefManager.setVal(PrefName.AutoBackupMaxCopies, copies)
                            binding.dataSettingsRecyclerView.adapter?.notifyDataSetChanged()
                        }
                        setPosButton(R.string.ok)
                        show()
                    }
                }
            ),

            // Last Auto-Backup info
            Settings(
                type = 1,
                name = getString(R.string.auto_backup),
                desc = run {
                    val timestamp = PrefManager.getVal<Long>(PrefName.LastAutoBackupTimestamp)
                    if (timestamp > 0) {
                        val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
                        getString(R.string.last_auto_backup, formattedDate)
                    } else {
                        getString(R.string.last_auto_backup_never)
                    }
                },
                icon = R.drawable.backup_restore,
                onClick = {}
            ),

            // Manual Backup Now
            Settings(
                type = 1,
                name = getString(R.string.backup_now),
                desc = getString(R.string.backup_now_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    StoragePermissions.downloadsPermission(context)
                    val exportableLocations = Location.entries.filter { it.exportable }
                    val timestampStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                    savePrefsToDownloads(
                        "Dantotsu_${timestampStr}",
                        PrefManager.exportAllPrefs(exportableLocations),
                        context
                    )
                }
            ),

            // Restore Backup
            Settings(
                type = 1,
                name = getString(R.string.restore_backup),
                desc = getString(R.string.restore_backup_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    StoragePermissions.downloadsPermission(context)
                    openDocumentLauncher.launch(arrayOf("*/*"))
                }
            ),

            // Change Download Directory
            Settings(
                type = 1,
                name = getString(R.string.change_download_directory),
                desc = getString(R.string.change_download_directory_desc),
                icon = R.drawable.ic_round_folder_24,
                onClick = {
                    val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                    context.customAlertDialog().apply {
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
                                                updateStorageInfo()
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
            ),

            // Rebuild Download Index
            Settings(
                type = 1,
                name = getString(R.string.rebuild_download_index),
                desc = getString(R.string.rebuild_download_index_desc),
                icon = R.drawable.ic_download_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.rebuild_download_index)
                        setMessage(R.string.rebuild_download_index_msg)
                        setPosButton(R.string.ok) {
                            toast(getString(R.string.please_wait))
                            lifecycleScope.launch(Dispatchers.IO) {
                                val recovered = Injekt.get<DownloadsManager>().rebuildIndexFromDisk()
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.rebuild_download_index_done, recovered))
                                    updateStorageInfo()
                                }
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }
            ),

            // Purge Downloads
            Settings(
                type = 1,
                name = getString(R.string.purge_downloads),
                desc = getString(R.string.purge_downloads_desc),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.purge_downloads)
                        setMessage(R.string.purge_confirm, "${getString(R.string.anime)} & ${getString(R.string.manga)}")
                        setPosButton(R.string.yes) {
                            val downloadsManager = Injekt.get<DownloadsManager>()
                            downloadsManager.purgeDownloads(MediaType.ANIME)
                            downloadsManager.purgeDownloads(MediaType.MANGA)
                            updateStorageInfo()
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
            )
        )

        binding.dataSettingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = SettingsAdapter(settingsList)
            setHasFixedSize(true)
        }
    }
}
