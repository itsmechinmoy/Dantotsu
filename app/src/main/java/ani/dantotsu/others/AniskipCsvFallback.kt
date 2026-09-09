package ani.dantotsu.others

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local SQLite fallback for AniSkip skip times, automatically synced in the background
 * every 7 days from the public dump at:
 * https://raw.githubusercontent.com/aniskip/sanitize_db_dump/refs/heads/main/skip_times_public.csv
 */
class AniskipCsvFallback(context: Context) {

    companion object {
        private const val TAG = "AniskipCsvFallback"
        private const val CSV_URL =
            "https://raw.githubusercontent.com/aniskip/sanitize_db_dump/refs/heads/main/skip_times_public.csv"

        private const val DB_NAME = "aniskip_fallback.db"
        private const val DB_VERSION = 1

        private const val TABLE_LIVE = "skip_times"
        private const val TABLE_STAGING = "skip_times_staging"

        private const val COL_ANIME_ID = "anime_id"
        private const val COL_EPISODE = "episode_number"
        private const val COL_TYPE = "skip_type"
        private const val COL_START = "start_time"
        private const val COL_END = "end_time"

        private const val BATCH_SIZE = 2000
        private const val PREF_LAST_UPDATED = "aniskip_csv_last_updated"
        private const val SYNC_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        private val VALID_TYPES = setOf("op", "ed", "recap", "mixed-op", "mixed-ed")

        @Volatile
        private var instance: AniskipCsvFallback? = null
        private val isSyncing = AtomicBoolean(false)

        fun getInstance(context: Context): AniskipCsvFallback =
            instance ?: synchronized(this) {
                instance ?: AniskipCsvFallback(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext

    private inner class DatabaseHelper : SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createLiveTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LIVE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_STAGING")
            createLiveTable(db)
        }
    }

    private fun createLiveTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LIVE (
                $COL_ANIME_ID INTEGER NOT NULL,
                $COL_EPISODE  INTEGER NOT NULL,
                $COL_TYPE     TEXT    NOT NULL,
                $COL_START    REAL    NOT NULL,
                $COL_END      REAL    NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_${TABLE_LIVE}_lookup " +
                    "ON $TABLE_LIVE ($COL_ANIME_ID, $COL_EPISODE)"
        )
    }

    fun getLastUpdated(): Long =
        PrefManager.getNullableCustomVal(PREF_LAST_UPDATED, 0L, Long::class.java) ?: 0L

    fun isUpdateNeeded(): Boolean {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (!dbFile.exists() || dbFile.length() == 0L) return true
        val lastUpdated = getLastUpdated()
        return (System.currentTimeMillis() - lastUpdated) > SYNC_INTERVAL_MS
    }

    suspend fun syncIfNeeded() = withContext(Dispatchers.IO) {
        if (!isUpdateNeeded()) return@withContext
        if (!isSyncing.compareAndSet(false, true)) return@withContext

        Logger.log("$TAG: Starting background sync of AniSkip CSV dump...")
        try {
            val okClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(CSV_URL).build()
            val response = okClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("$TAG: Failed to download CSV, HTTP ${response.code}")
                response.close()
                return@withContext
            }

            val body = response.body ?: run {
                Logger.log("$TAG: Empty response body")
                response.close()
                return@withContext
            }

            val helper = DatabaseHelper()
            try {
                val db = helper.writableDatabase
                db.execSQL("DROP TABLE IF EXISTS $TABLE_STAGING")
                db.execSQL(
                    """
                    CREATE TABLE $TABLE_STAGING (
                        $COL_ANIME_ID INTEGER NOT NULL,
                        $COL_EPISODE  INTEGER NOT NULL,
                        $COL_TYPE     TEXT    NOT NULL,
                        $COL_START    REAL    NOT NULL,
                        $COL_END      REAL    NOT NULL
                    )
                    """.trimIndent()
                )

                var totalRows = 0
                var isFirstLine = true

                val reader = java.io.BufferedReader(java.io.InputStreamReader(body.byteStream()))
                try {
                    val insertSql =
                        "INSERT INTO $TABLE_STAGING ($COL_ANIME_ID, $COL_EPISODE, $COL_TYPE, $COL_START, $COL_END) VALUES (?, ?, ?, ?, ?)"
                    val statement = db.compileStatement(insertSql)
                    var rowsInBatch = 0
                    var line: String?

                    db.beginTransaction()
                    try {
                        while (true) {
                            line = reader.readLine() ?: break
                            if (isFirstLine) {
                                isFirstLine = false
                                continue
                            }
                            if (line.isNullOrBlank()) continue

                            val cols = line.split(",")
                            if (cols.size < 7) continue

                            val animeId = cols[0].toLongOrNull() ?: continue
                            val episode = cols[1].toIntOrNull() ?: continue
                            val skipType = cols[3].trim()
                            val startTime = cols[5].toDoubleOrNull() ?: continue
                            val endTime = cols[6].toDoubleOrNull() ?: continue

                            if (skipType !in VALID_TYPES) continue

                            statement.clearBindings()
                            statement.bindLong(1, animeId)
                            statement.bindLong(2, episode.toLong())
                            statement.bindString(3, skipType)
                            statement.bindDouble(4, startTime)
                            statement.bindDouble(5, endTime)
                            statement.executeInsert()

                            totalRows++
                            rowsInBatch++

                            if (rowsInBatch >= BATCH_SIZE) {
                                db.setTransactionSuccessful()
                                db.endTransaction()
                                db.beginTransaction()
                                rowsInBatch = 0
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        if (db.inTransaction()) db.endTransaction()
                        statement.close()
                    }
                } finally {
                    reader.close()
                }

                db.beginTransaction()
                try {
                    db.execSQL("DROP TABLE IF EXISTS $TABLE_LIVE")
                    db.execSQL("ALTER TABLE $TABLE_STAGING RENAME TO $TABLE_LIVE")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_${TABLE_LIVE}_lookup " +
                            "ON $TABLE_LIVE ($COL_ANIME_ID, $COL_EPISODE)"
                )

                PrefManager.setCustomVal(PREF_LAST_UPDATED, System.currentTimeMillis())
                Logger.log("$TAG: Successfully imported $totalRows AniSkip timestamps into local database.")
            } finally {
                helper.close()
                response.close()
            }
        } catch (e: Exception) {
            Logger.log("$TAG: Sync failed: ${e.message}")
        } finally {
            isSyncing.set(false)
        }
    }

    suspend fun lookup(malId: Int, episodeNumber: Int): List<AniSkip.Stamp>? = withContext(Dispatchers.IO) {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (!dbFile.exists() || dbFile.length() == 0L) return@withContext null

        val helper = DatabaseHelper()
        try {
            helper.readableDatabase.use { db ->
                db.query(
                    TABLE_LIVE,
                    arrayOf(COL_TYPE, COL_START, COL_END),
                    "$COL_ANIME_ID = ? AND $COL_EPISODE = ?",
                    arrayOf(malId.toString(), episodeNumber.toString()),
                    null, null, null
                ).use { cursor ->
                    val stamps = mutableListOf<AniSkip.Stamp>()
                    while (cursor.moveToNext()) {
                        val type = cursor.getString(0)
                        val start = cursor.getDouble(1)
                        val end = cursor.getDouble(2)
                        stamps.add(
                            AniSkip.Stamp(
                                interval = AniSkip.AniSkipInterval(startTime = start, endTime = end),
                                skipType = type,
                                skipId = "csv_${type}_${malId}_$episodeNumber",
                                episodeLength = 0.0
                            )
                        )
                    }
                    if (stamps.isNotEmpty()) stamps else null
                }
            }
        } catch (e: Exception) {
            Logger.log("$TAG: Lookup error: ${e.message}")
            null
        } finally {
            helper.close()
        }
    }
}
