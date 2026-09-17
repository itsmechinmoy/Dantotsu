package ani.dantotsu.settings.data

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Logger.log("AutoBackupWorker: Starting auto-backup")
        PrefManager.init(applicationContext)

        val interval = PrefManager.getVal<Int>(PrefName.AutoBackupInterval)
        if (interval <= 0) {
            Logger.log("AutoBackupWorker: Auto-backup is disabled")
            return Result.success()
        }

        return try {
            val exportableLocations = Location.entries.filter { it.exportable }
            val serialized = PrefManager.exportAllPrefs(exportableLocations)

            val backupDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Dantotsu/Backups"
            ).apply { if (!exists()) mkdirs() }

            val timestampStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val backupFile = File(backupDir, "dantotsu_autobackup_${timestampStr}.ani")
            backupFile.writeText(serialized)
            android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(backupFile.absolutePath), null, null)

            PrefManager.setVal(PrefName.LastAutoBackupTimestamp, System.currentTimeMillis())
            Logger.log("AutoBackupWorker: Backup saved to ${backupFile.absolutePath}")

            // Pruning old auto-backups exceeding retention limit
            val maxCopies = PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies).coerceAtLeast(1)
            val autoBackups = backupDir.listFiles { file ->
                file.isFile && file.name.startsWith("dantotsu_autobackup_") && file.name.endsWith(".ani")
            }?.sortedBy { it.lastModified() } ?: emptyList()

            if (autoBackups.size > maxCopies) {
                val toDelete = autoBackups.take(autoBackups.size - maxCopies)
                toDelete.forEach { oldFile ->
                    Logger.log("AutoBackupWorker: Pruning old backup ${oldFile.name}")
                    oldFile.delete()
                }
            }

            Result.success()
        } catch (e: Exception) {
            Logger.log("AutoBackupWorker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ani.dantotsu.settings.data.AutoBackupWorker"

        fun getIntervalHours(index: Int): Long {
            return when (index) {
                1 -> 6L
                2 -> 12L
                3 -> 24L
                4 -> 48L
                5 -> 168L
                else -> 0L
            }
        }

        fun schedule(context: Context, intervalIndex: Int) {
            val workManager = WorkManager.getInstance(context)
            val hours = getIntervalHours(intervalIndex)
            if (hours <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
                Logger.log("AutoBackupWorker cancelled")
            } else {
                val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(hours, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                Logger.log("AutoBackupWorker scheduled every $hours hours")
            }
        }
    }
}
