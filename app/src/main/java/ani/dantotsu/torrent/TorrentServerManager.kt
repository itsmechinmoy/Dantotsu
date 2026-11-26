package ani.dantotsu.torrent

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.torrentServer.model.FileStat
import eu.kanade.tachiyomi.data.torrentServer.model.Torrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.libtorrent4j.*
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import java.io.File
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class TorrentServerManager(private val context: Context) {
    private val sessionManager by lazy { SessionManager() }
    private var httpServer: TorrentHttpServer? = null
    private val prebufferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prebufferJob: Job? = null
    private var prebufferKey: Pair<String, Int>? = null
    private val sources = ConcurrentHashMap<String, String>()
    private val metadataCache by lazy {
        TorrentMetadataCache(File(context.cacheDir, "torrent_meta"))
    }
    private val resumeDir by lazy { File(context.cacheDir, "torrent_resume") }
    private val sessionStateStore by lazy {
        SessionStateStore(File(context.filesDir, "torrent/session.state"))
    }
    @Volatile
    var activeTorrentHash: String? = null

    /** Last time anything started, added or buffered; a closing player only stops an unused session. */
    @Volatile
    private var lastUsed = 0L
    var serverPort: Int = 8090
        private set

    companion object {
        // High-speed public anime & general trackers matching qBittorrent & Aniyomi
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "http://tracker.openbittorrent.com:80/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "udp://tracker.internetwarriors.net:1337/announce",
            "udp://tracker.openbts.com:6969/announce"
        )

        // Reliable DHT bootstrap routers matching qBittorrent
        const val DHT_BOOTSTRAP_NODES =
            "dht.libtorrent.org:25401,dht.transmissionbt.com:6881,router.bittorrent.com:6881,router.utorrent.com:6881,router.bt.ouinet.work:6881"

        private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val HEX_CHARS = "0123456789ABCDEF"
        private val HEX_HASH_REGEX = Regex("^[0-9A-F]{40}$")
        private const val REMOVE_RESUME_TIMEOUT_MS = 1_500L
        private const val STOP_RESUME_TIMEOUT_MS = 1_000L
        private const val MAX_RESUME_BYTES = 4L * 1024 * 1024

        private fun base32ToHex(base32: String): String {
            val clean = base32.uppercase(Locale.US).trim()
            var bits = 0L
            var bitCount = 0
            val out = StringBuilder(40)
            for (c in clean) {
                val valIndex = BASE32_CHARS.indexOf(c)
                if (valIndex == -1) throw IllegalArgumentException("Invalid base32 character: $c")
                bits = (bits shl 5) or valIndex.toLong()
                bitCount += 5
                if (bitCount >= 8) {
                    bitCount -= 8
                    val b = ((bits shr bitCount) and 0xFF).toInt()
                    out.append(HEX_CHARS[b ushr 4])
                    out.append(HEX_CHARS[b and 0x0F])
                }
            }
            return out.toString()
        }

        private fun normalizeBtih(raw: String, originalUrl: String): String {
            val hash = raw.trim().uppercase(Locale.US)
            if (hash.length == 40 && hash.all { it in HEX_CHARS }) {
                return hash
            }
            if (hash.length == 32 && hash.all { it in BASE32_CHARS }) {
                return base32ToHex(hash)
            }
            throw IllegalArgumentException("Invalid magnet link: $originalUrl")
        }

        fun parseMagnetHash(url: String): String {
            val candidates = buildList {
                runCatching { URLDecoder.decode(url, "UTF-8") }.getOrNull()?.let { add(it) }
                add(url)
            }
            for (candidate in candidates) {
                val xtIndex = candidate.indexOf("xt=urn:btih:", ignoreCase = true)
                if (xtIndex != -1) {
                    val rawHash = candidate.substring(xtIndex + 12).substringBefore('&')
                    return normalizeBtih(rawHash, url)
                }
            }
            // Fallback: decode individual query params if an unrelated param has invalid '%'
            val query = url.substringAfter('?', "")
            if (query.isNotEmpty()) {
                for (param in query.split('&')) {
                    val eq = param.indexOf('=')
                    if (eq <= 0) continue
                    if (!param.substring(0, eq).equals("xt", ignoreCase = true)) continue
                    val rawValue = param.substring(eq + 1)
                    val decodedValue = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
                    val prefix = "urn:btih:"
                    if (decodedValue.startsWith(prefix, ignoreCase = true)) {
                        return normalizeBtih(decodedValue.substring(prefix.length).substringBefore('&'), url)
                    }
                }
            }
            val clean = url.trim().uppercase(Locale.US)
            if (clean.length == 40 && clean.all { it in HEX_CHARS }) {
                return clean
            }
            if (clean.length == 32 && clean.all { it in BASE32_CHARS }) {
                return base32ToHex(clean)
            }
            throw IllegalArgumentException("Invalid magnet link: $url")
        }
    }

    // the service and the picker both start the server; without the lock both pass the check
    // and start the session and a second http server on another port
    @Synchronized
    fun start() {
        markUsed()
        if (sessionManager.isRunning) return
        Logger.log("Starting built-in TorrentServerManager with qBittorrent optimizations...")
        try {
            val settings = SettingsPack()

            configureSessionSettings(settings)

            // Resume saved DHT state when present; current settings always take precedence.
            val params = sessionStateStore.loadParsed(
                onCorrupt = { e ->
                    Logger.log("TorrentServerManager: saved session state unusable, starting clean: ${e.message}")
                }
            ) { bytes ->
                SessionParams(bytes).also { it.setSettings(settings) }
            } ?: SessionParams(settings)
            sessionManager.start(params)
            sessionManager.startDht()

            serverPort = findFreePort(8090)
            httpServer = TorrentHttpServer(serverPort, { hash ->
                try {
                    sessionManager.find(Sha1Hash.parseHex(hash))
                } catch (e: Exception) {
                    null
                }
            }, {
                getTorrentCacheDir().absolutePath
            })
            httpServer?.start()
            Logger.log("TorrentServerManager started. Port: $serverPort")
        } catch (e: Exception) {
            Logger.log("Failed to start TorrentServerManager: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun isBatteryLowAndNotCharging(): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.TorrentBatterySaving)) return false
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter) ?: return false

        val status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == android.os.BatteryManager.BATTERY_STATUS_FULL

        val level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = level / scale.toFloat()

        return !isCharging && batteryPct < 0.20f
    }

    fun stop() {
        Logger.log("Stopping built-in TorrentServerManager...")
        synchronized(this) {
            prebufferJob?.cancel()
            prebufferJob = null
            prebufferKey = null
        }
        httpServer?.stop()
        httpServer = null
        if (sessionManager.isRunning) {
            saveSessionState()
            activeTorrentHash?.let { hash ->
                try {
                    val norm = hash.uppercase(Locale.US)
                    val handle = sessionManager.find(Sha1Hash.parseHex(norm))
                    if (handle != null && handle.isValid) {
                        saveMetadataIfMissing(handle, norm)
                        saveResumeDataBlocking(handle, norm, STOP_RESUME_TIMEOUT_MS)
                    }
                } catch (_: Exception) {}
            }
            sessionManager.stop()
        }
    }

    private fun saveSessionState() {
        try {
            if (sessionManager.isRunning) {
                sessionStateStore.save(sessionManager.saveState())
            }
        } catch (e: Exception) {
            Logger.log("TorrentServerManager: saving session state failed: ${e.message}")
        }
    }

    private fun loadCachedTorrentInfo(infoHash: String): TorrentInfo? =
        metadataCache.loadParsed(
            infoHash,
            onCorrupt = { e ->
                Logger.log("TorrentServerManager: corrupt cached metadata for $infoHash, discarding: ${e.message}")
            }
        ) { bytes ->
            val ti = TorrentInfo(bytes)
            ti.takeIf { it.infoHash().toHex().equals(infoHash, ignoreCase = true) }
        }

    private fun saveMetadataIfMissing(handle: TorrentHandle, infoHash: String): Boolean {
        val norm = infoHash.uppercase(Locale.US)
        if (metadataCache.contains(norm)) return false
        return try {
            val span = handle.torrentFile()?.swig()?.get_info_section() ?: return false
            val n = span.size().toInt()
            if (n !in 1..TorrentMetadataCache.MAX_INFO_BYTES) return false
            val info = ByteArray(n) { span.at(it.toLong()) }
            val wrapped = TorrentMetadataCache.wrapInfoSection(info) ?: return false
            metadataCache.save(norm, wrapped)
        } catch (_: Throwable) {
            false
        }
    }

    private fun attachMagnetTrackers(handle: TorrentHandle, url: String) {
        TorrentMetadataCache.magnetTrackers(url).forEach { trk ->
            try { handle.addTracker(AnnounceEntry(trk)) } catch (_: Exception) {}
        }
        DEFAULT_TRACKERS.forEach { trk ->
            try { handle.addTracker(AnnounceEntry(trk)) } catch (_: Exception) {}
        }
        try { handle.forceReannounce() } catch (_: Exception) {}
    }

    private fun addFromTorrentInfo(
        ti: TorrentInfo,
        infoHash: String,
        cacheDir: File,
        prioritizeFileIndex: Int? = null
    ): TorrentHandle? {
        val sha1 = Sha1Hash.parseHex(infoHash)
        sessionManager.find(sha1)?.takeIf { it.isValid }?.let { return it }

        val priorities = Priority.array(Priority.IGNORE, ti.numFiles())
        if (prioritizeFileIndex != null && prioritizeFileIndex in 0 until ti.numFiles()) {
            priorities[prioritizeFileIndex] = Priority.TOP_PRIORITY
        }

        val resume = takeResumeFile(infoHash, ti, cacheDir)
        if (resume != null) {
            try {
                sessionManager.download(ti, cacheDir, resume, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                val handle = sessionManager.find(sha1)?.takeIf { it.isValid }
                if (handle != null) return handle
            } catch (t: Throwable) {
                Logger.log("TorrentServerManager: resume data unusable for $infoHash, retrying clean: ${t.message}")
            } finally {
                runCatching { resume.delete() }
            }
        }

        sessionManager.download(ti, cacheDir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        return sessionManager.find(sha1)
    }

    private fun resumeFileFor(infoHash: String): File? {
        val norm = infoHash.trim().uppercase(Locale.US)
        if (!HEX_HASH_REGEX.matches(norm)) return null
        return File(resumeDir, "$norm.fastresume")
    }

    private fun writeResumeFile(infoHash: String, bytes: ByteArray?) {
        val target = resumeFileFor(infoHash) ?: return
        if (bytes == null || bytes.size !in 1..MAX_RESUME_BYTES.toInt()) return
        val tmp = File(resumeDir, target.name + ".tmp")
        try {
            resumeDir.mkdirs()
            tmp.writeBytes(bytes)
            val ok = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
            if (!ok) runCatching { tmp.delete() }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Logger.log("TorrentServerManager: writing resume data failed: ${t.message}")
        }
    }

    /** Writes [handle]'s resume data with a bounded wait so removals and stops never hang. */
    private fun saveResumeDataBlocking(
        handle: TorrentHandle,
        infoHash: String,
        timeoutMs: Long = REMOVE_RESUME_TIMEOUT_MS
    ) {
        if (!sessionManager.isRunning) return
        val norm = infoHash.trim().uppercase(Locale.US)
        if (!HEX_HASH_REGEX.matches(norm)) return
        try {
            if (!handle.isValid || handle.torrentFile() == null) return
            if (handle.status().totalDone() <= 0L) return
        } catch (_: Exception) {
            return
        }
        val latch = CountDownLatch(1)
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.SAVE_RESUME_DATA.swig(),
                AlertType.SAVE_RESUME_DATA_FAILED.swig()
            )

            override fun alert(alert: Alert<*>) {
                val torrentAlert = alert as? TorrentAlert<*> ?: return
                val hash = try {
                    torrentAlert.handle().infoHash().toHex()
                } catch (_: Exception) {
                    return
                }
                if (!hash.equals(norm, ignoreCase = true)) return
                if (alert is SaveResumeDataAlert) {
                    try {
                        val bytes = AddTorrentParams.writeResumeDataBuf(alert.params())
                        writeResumeFile(norm, bytes)
                    } catch (t: Throwable) {
                        Logger.log("TorrentServerManager: writing resume data failed: ${t.message}")
                    }
                }
                latch.countDown()
            }
        }
        sessionManager.addListener(listener)
        try {
            if (!handle.isValid) return
            handle.saveResumeData()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            sessionManager.removeListener(listener)
        }
    }

    /** Returns a single-use temp copy of the resume file if payload files exist on disk, deleting the original. */
    private fun takeResumeFile(infoHash: String, ti: TorrentInfo, saveDir: File): File? {
        val norm = infoHash.trim().uppercase(Locale.US)
        val file = resumeFileFor(norm) ?: return null
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_RESUME_BYTES) {
            runCatching { file.delete() }
            return null
        }
        val payloadPresent = try {
            val fs = ti.files()
            (0 until fs.numFiles()).any { i ->
                val f = File(fs.filePath(i, saveDir.absolutePath))
                f.isFile && f.length() > 0L
            }
        } catch (_: Exception) {
            false
        }
        if (!payloadPresent) {
            runCatching { file.delete() }
            return null
        }
        val copy = File(resumeDir, "$norm.fastresume.using")
        return try {
            file.copyTo(copy, overwrite = true)
            file.delete()
            copy
        } catch (_: Exception) {
            runCatching { copy.delete() }
            runCatching { file.delete() }
            null
        }
    }

    fun isRunning(): Boolean = sessionManager.isRunning

    private fun markUsed() {
        lastUsed = android.os.SystemClock.elapsedRealtime()
    }

    /** True if the session was started or used after [elapsedRealtime]. */
    fun usedSince(elapsedRealtime: Long): Boolean = lastUsed > elapsedRealtime

    fun hasTorrent(torrentHash: String): Boolean = runCatching {
        sessionManager.isRunning && sessionManager.find(Sha1Hash.parseHex(torrentHash))?.isValid == true
    }.getOrDefault(false)

    fun isAvailable(andEnabled: Boolean = true): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        return true
    }

    /**
     * Injects popular public anime/general trackers into a magnet link if missing,
     * significantly accelerating metadata resolution and peer discovery.
     */
    fun enhanceMagnetUrl(url: String): String {
        if (!url.startsWith("magnet:", ignoreCase = true)) return url
        val builder = StringBuilder(url)
        for (tracker in DEFAULT_TRACKERS) {
            val encoded = try {
                URLEncoder.encode(tracker, "UTF-8")
            } catch (e: Exception) {
                tracker
            }
            if (!url.contains(encoded) && !url.contains(tracker)) {
                builder.append("&tr=").append(encoded)
            }
        }
        return builder.toString()
    }

    fun addTorrent(
        url: String,
        title: String,
        poster: String = "",
        data: String = "",
        save: Boolean = false
    ): Torrent {
        if (isBatteryLowAndNotCharging()) {
            throw Exception("Battery low and not charging")
        }
        start() // Ensure running
        markUsed()

        val cacheDir = getTorrentCacheDir()
        val handle = when {
            url.startsWith("magnet:", ignoreCase = true) -> addMagnet(url, cacheDir)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                addTorrentFromUrl(url, cacheDir)
            else -> addTorrentFromFile(url, cacheDir)
        } ?: throw Exception("Failed to add torrent: $url")

        // Explicitly resume to ensure downloading starts
        handle.resume()
        return describeTorrent(handle, url, title)
    }

    /** A magnet from cached metadata when there is some, else from the swarm. */
    private fun addMagnet(url: String, cacheDir: File): TorrentHandle? {
        val infoHash = parseMagnetHash(url)
        val cachedTi = loadCachedTorrentInfo(infoHash) ?: return addMagnetFromSwarm(url, infoHash, cacheDir)
        val handle = addFromTorrentInfo(cachedTi, infoHash, cacheDir)
        if (handle != null && handle.isValid) attachMagnetTrackers(handle, url)
        return handle
    }

    /**
     * Adds the magnet unless it is already loaded and waits up to 60 s for
     * its metadata. A torrent added here starts with every file ignored;
     * the stream prioritizes the file it plays.
     */
    private fun addMagnetFromSwarm(url: String, infoHash: String, cacheDir: File): TorrentHandle? {
        val sha1 = Sha1Hash.parseHex(infoHash)
        var handle = sessionManager.find(sha1)?.takeIf { it.isValid }
        val createdHere = handle == null
        if (handle == null) {
            sessionManager.download(enhanceMagnetUrl(url), cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle = sessionManager.find(sha1)
        }
        handle = awaitMagnetMetadata(sha1, handle)
        val torrentInfo = handle?.torrentFile() ?: return handle
        if (createdHere) {
            handle.prioritizeFiles(Priority.array(Priority.IGNORE, torrentInfo.numFiles()))
        }
        saveMetadataIfMissing(handle, infoHash)
        saveSessionState()
        return handle
    }

    /** Polls every 100 ms, up to 60 s, until [sha1] has metadata or the session stops. */
    private fun awaitMagnetMetadata(sha1: Sha1Hash, initial: TorrentHandle?): TorrentHandle? {
        var handle = initial
        var waitTime = 0
        while ((handle == null || handle.torrentFile() == null) && waitTime < 600) {
            if (!sessionManager.isRunning) break
            Thread.sleep(100)
            handle = sessionManager.find(sha1)
            waitTime++
        }
        return handle
    }

    private fun addTorrentFromUrl(url: String, cacheDir: File): TorrentHandle? {
        val tempFile = downloadTorrentFile(url) ?: return null
        return try {
            val ti = TorrentInfo(tempFile)
            addFromTorrentInfo(ti, ti.infoHash().toHex().uppercase(Locale.US), cacheDir)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun addTorrentFromFile(url: String, cacheDir: File): TorrentHandle? {
        val file = File(url.removePrefix("file://"))
        if (!file.exists()) return null
        val ti = TorrentInfo(file)
        return addFromTorrentInfo(ti, ti.infoHash().toHex().uppercase(Locale.US), cacheDir)
    }

    /** Remembers where [handle] came from and lists its files. */
    private fun describeTorrent(handle: TorrentHandle, url: String, title: String): Torrent {
        val infoHash = handle.infoHash().toHex()
        sources[infoHash.uppercase(Locale.US)] = url
        val fileStats = handle.torrentFile()?.files()?.let { fileStorage ->
            List(fileStorage.numFiles()) { i ->
                FileStat(
                    id = i,
                    path = fileStorage.filePath(i),
                    length = fileStorage.fileSize(i)
                )
            }
        } ?: emptyList()
        return Torrent(
            title = title,
            name = handle.getName() ?: title,
            hash = infoHash,
            torrent_size = handle.torrentFile()?.totalSize() ?: 0L,
            file_stats = fileStats
        )
    }

    /**
     * Starts streaming a magnet without blocking on metadata or initial pieces.
     * Swaps out any different active torrent, adds the magnet if not loaded, and kicks off
     * head+tail pre-buffering on IO.
     */
    @Synchronized
    fun startMagnetForStream(url: String, fileIndex: Int): String {
        if (isBatteryLowAndNotCharging()) {
            throw Exception("Battery low and not charging")
        }
        val infoHash = parseMagnetHash(url)
        start()
        markUsed()
        val previous = activeTorrentHash
        if (previous != null && !previous.equals(infoHash, ignoreCase = true)) {
            removeTorrent(previous)
        }
        val sha1 = Sha1Hash.parseHex(infoHash)
        if (sessionManager.find(sha1)?.isValid != true) {
            val cacheDir = getTorrentCacheDir()
            val cachedTi = loadCachedTorrentInfo(infoHash)
            if (cachedTi != null) {
                val handle = addFromTorrentInfo(cachedTi, infoHash, cacheDir, prioritizeFileIndex = fileIndex)
                if (handle != null && handle.isValid) {
                    attachMagnetTrackers(handle, url)
                }
            } else {
                sessionManager.download(enhanceMagnetUrl(url), cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
        }
        sessionManager.find(sha1)?.takeIf { it.isValid }?.resume()
        sources[infoHash.uppercase(Locale.US)] = url
        activeTorrentHash = infoHash
        prebufferAsync(infoHash, fileIndex)
        return infoHash
    }

    @Synchronized
    fun prebufferAsync(torrentHash: String, fileIndex: Int) {
        val key = Pair(torrentHash.uppercase(Locale.US), fileIndex)
        if (prebufferKey == key && prebufferJob?.isActive == true) return
        prebufferKey = key
        prebufferJob = prebufferScope.launch {
            prebuffer(torrentHash, fileIndex)
        }
    }

    /** Where [hash] was added from in this session, else a bare magnet for DHT re-add. */
    fun sourceFor(hash: String): String =
        sources[hash.uppercase(Locale.US)] ?: "magnet:?xt=urn:btih:${hash.uppercase(Locale.US)}"

    /**
     * Pre-buffers video file using qBittorrent's 1% Head + 1% Tail piece algorithm.
     * This guarantees container header and moov atom / Matroska Cues are downloaded
     * before ExoPlayer begins playback, preventing buffering freezes.
     */
    fun prebuffer(torrentHash: String, fileIndex: Int): Boolean {
        markUsed()
        try {
            val handle = awaitValidHandle(Sha1Hash.parseHex(torrentHash)) ?: return false
            val torrentInfo = awaitTorrentInfo(handle) ?: return false
            if (saveMetadataIfMissing(handle, torrentHash)) {
                saveSessionState()
            }
            val fileStorage = torrentInfo.files()
            if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) return false
            val priorities = Priority.array(Priority.IGNORE, fileStorage.numFiles())
            priorities[fileIndex] = Priority.TOP_PRIORITY
            handle.prioritizeFiles(priorities)

            val firstPiece = prioritizeFileEnds(handle, torrentInfo, fileIndex)
            // Quick check for initial readiness without freezing the player launch
            val success = awaitPiece(handle, firstPiece)
            Logger.log("TorrentServerManager: Pre-buffering initial piece check = $success (ready for instant playback)")
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /** Polls every 50 ms, up to 3 s, for a valid handle; null if none or the session stops. */
    private fun awaitValidHandle(sha1: Sha1Hash): TorrentHandle? {
        var handle = sessionManager.find(sha1)
        var waitHandle = 0
        while ((handle == null || !handle.isValid) && waitHandle < 60) {
            if (!sessionManager.isRunning) return null
            Thread.sleep(50)
            handle = sessionManager.find(sha1)
            waitHandle++
        }
        return handle?.takeIf { it.isValid }
    }

    /** Polls every 100 ms, up to 30 s, for metadata; null if the handle or session goes away. */
    private fun awaitTorrentInfo(handle: TorrentHandle): TorrentInfo? {
        var waitTime = 0
        while (handle.torrentFile() == null && waitTime < 300) {
            if (!sessionManager.isRunning || !handle.isValid) return null
            Thread.sleep(100)
            waitTime++
        }
        return handle.torrentFile()
    }

    /**
     * qBittorrent's 1% head + 1% tail (2 to 16 pieces each), so the container
     * header and moov atom / Matroska Cues come first. Returns the first piece.
     */
    private fun prioritizeFileEnds(handle: TorrentHandle, torrentInfo: TorrentInfo, fileIndex: Int): Int {
        val fileStorage = torrentInfo.files()
        val fileOffset = fileStorage.fileOffset(fileIndex)
        val fileSize = fileStorage.fileSize(fileIndex)
        val pieceLength = torrentInfo.pieceLength().toLong()
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = if (fileSize > 0) ((fileOffset + fileSize - 1) / pieceLength).toInt() else firstPiece
        val numPiecesOnePercent = if (fileSize > 0 && pieceLength > 0) {
            (fileSize * 0.01 / pieceLength).toInt().coerceIn(2, 16)
        } else 2
        Logger.log("TorrentServerManager: Pre-buffering $numPiecesOnePercent head pieces and $numPiecesOnePercent tail pieces for file $fileIndex")
        prioritizeHeadAndTailPieces(handle, firstPiece, lastPiece, torrentInfo.numPieces(), numPiecesOnePercent)
        return firstPiece
    }

    /** Waits up to 8 s for [piece]; true if it is there. */
    private fun awaitPiece(handle: TorrentHandle, piece: Int): Boolean {
        var waitCount = 0
        while (!handle.havePiece(piece) && waitCount < 80) {
            if (!sessionManager.isRunning || !handle.isValid) break
            Thread.sleep(100)
            waitCount++
        }
        return handle.havePiece(piece)
    }

    fun getLink(torrent: Torrent, fileIndex: Int): String {
        val fileName = try {
            val sha1 = Sha1Hash.parseHex(torrent.hash)
            val handle = sessionManager.find(sha1)
            handle?.torrentFile()?.files()?.filePath(fileIndex)?.substringAfterLast("/") ?: "video.mkv"
        } catch (_: Exception) { "video.mkv" }
        val encodedName = runCatching { java.net.URLEncoder.encode(fileName, "UTF-8") }.getOrDefault("video.mkv")
        return "http://127.0.0.1:$serverPort/stream/$encodedName?hash=${torrent.hash}&index=$fileIndex"
    }

    fun getLink(torrentHash: String, fileIndex: Int): String {
        val fileName = try {
            val sha1 = Sha1Hash.parseHex(torrentHash)
            val handle = sessionManager.find(sha1)
            handle?.torrentFile()?.files()?.filePath(fileIndex)?.substringAfterLast("/") ?: "video.mkv"
        } catch (_: Exception) { "video.mkv" }
        val encodedName = runCatching { java.net.URLEncoder.encode(fileName, "UTF-8") }.getOrDefault("video.mkv")
        return "http://127.0.0.1:$serverPort/stream/$encodedName?hash=$torrentHash&index=$fileIndex"
    }

    fun removeTorrent(torrentHash: String) {
        try {
            val normHash = torrentHash.uppercase(Locale.US)
            val sha1 = Sha1Hash.parseHex(normHash)
            val handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid) {
                saveMetadataIfMissing(handle, normHash)
                saveResumeDataBlocking(handle, normHash, REMOVE_RESUME_TIMEOUT_MS)
                sessionManager.remove(handle)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTorrentCacheDir(): File {
        val dir = File(context.cacheDir, "torrent_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun findFreePort(startPort: Int): Int {
        var port = startPort
        while (port < 65535) {
            try {
                java.net.ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).use {
                    return port
                }
            } catch (e: java.io.IOException) {
                port++
            }
        }
        return startPort
    }

    private fun downloadTorrentFile(url: String): File? {
        try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return null
                    val tempFile = File.createTempFile("temp", ".torrent", context.cacheDir)
                    tempFile.writeBytes(bytes)
                    return tempFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun pauseActiveTorrent() {
        try {
            val hash = activeTorrentHash ?: return
            val sha1 = Sha1Hash.parseHex(hash)
            val handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid) {
                handle.pause()
                Logger.log("TorrentServerManager: Paused active torrent $hash")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resumeActiveTorrent() {
        try {
            val hash = activeTorrentHash ?: return
            val sha1 = Sha1Hash.parseHex(hash)
            val handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid) {
                handle.resume()
                Logger.log("TorrentServerManager: Resumed active torrent $hash")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pruneCache(maxSizeBytes: Long = 4L * 1024L * 1024L * 1024L) {
        try {
            val cacheDir = getTorrentCacheDir()
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > maxSizeBytes) {
                val sortedFiles = files.sortedBy { it.lastModified() }
                for (file in sortedFiles) {
                    if (totalSize <= maxSizeBytes) break
                    val size = file.length()
                    if (file.deleteRecursively()) {
                        totalSize -= size
                        Logger.log("TorrentServerManager: Pruned old torrent cache file ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getActiveWifiInterface(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isUp && !intf.isLoopback) {
                    val name = intf.name.lowercase()
                    if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("swlan")) {
                        return intf.name
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    private fun configureSessionSettings(settings: SettingsPack) {
            // 1. Discovery & Protocols
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)
            settings.setString(org.libtorrent4j.swig.settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP_NODES)

            // Announce to all trackers across all tiers simultaneously (faster peer discovery)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)

            // 2. Mobile-Optimized Disk I/O & OS RAM Cache (Avoid OOM, reduce flash wear, coalesce writes)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_queued_disk_bytes.swigValue(), 16 * 1024 * 1024) // 16 MB write-behind queue
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.aio_threads.swigValue(), 4) // 4 background I/O threads
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.file_pool_size.swigValue(), 50) // Max 50 open FDs
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.checking_mem_usage.swigValue(), 1024) // 16 MB hash check buffer

            // Suggest read cache to peers for higher upload/download efficiency
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.suggest_mode.swigValue(), org.libtorrent4j.swig.settings_pack.suggest_mode_t.suggest_read_cache.swigValue())

            // Connection pacing for rapid swarm acquisition & high throughput streaming (qBittorrent parity)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connection_speed.swigValue(), 40) // 40 handshakes/sec
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_turnover.swigValue(), 4) // 4% turnover
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_turnover_cutoff.swigValue(), 90)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_turnover_interval.swigValue(), 60)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(), 1000)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 2000)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.unchoke_slots_limit.swigValue(), 12)

            // Disable UDP (uTP) if configured
            val disableUtp = PrefManager.getVal<Boolean>(PrefName.TorrentDisableUtp)
            if (disableUtp) {
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
            } else {
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            }

            // Strict Encryption Mode
            val encryption = PrefManager.getVal<Boolean>(PrefName.TorrentEncryption)
            if (encryption) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_forced.swigValue())
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_forced.swigValue())
            } else {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_enabled.swigValue())
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_enabled.swigValue())
            }

            // WiFi Only
            val wifiOnly = PrefManager.getVal<Boolean>(PrefName.TorrentWifiOnly) ||
                PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly)
            if (wifiOnly) {
                val wifiInterface = getActiveWifiInterface() ?: "wlan0"
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.outgoing_interfaces.swigValue(), wifiInterface)
            }

            // Download/Upload Limits (KB/s to B/s)
            val downloadSpeedLimit = PrefManager.getVal<Int>(PrefName.TorrentDownloadSpeedLimit)
            if (downloadSpeedLimit > 0) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.download_rate_limit.swigValue(), downloadSpeedLimit * 1024)
            }
            val uploadSpeedLimit = PrefManager.getVal<Int>(PrefName.TorrentUploadSpeedLimit)
            if (uploadSpeedLimit > 0) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.upload_rate_limit.swigValue(), uploadSpeedLimit * 1024)
            }

            // Connection Limit
            val maxConnections = PrefManager.getVal<Int>(PrefName.TorrentMaxConnections)
            if (maxConnections > 0) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), maxConnections)
            }

            // Port configuration
            val customPort = PrefManager.getVal<Int>(PrefName.TorrentPort)
            if (customPort > 0) {
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:$customPort,[::]:$customPort")
            } else {
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")
            }

            // Socks5 Proxy config
            if (PrefManager.getVal<Boolean>(PrefName.EnableSocks5Proxy)) {
                val proxyHost = PrefManager.getVal<String>(PrefName.Socks5ProxyHost)
                val proxyPortStr = PrefManager.getVal<String>(PrefName.Socks5ProxyPort)
                val proxyPort = proxyPortStr.toIntOrNull() ?: 1080

                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.proxy_hostname.swigValue(), proxyHost)
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_port.swigValue(), proxyPort)

                val authEnabled = PrefManager.getVal<Boolean>(PrefName.ProxyAuthEnabled)
                if (authEnabled) {
                    val proxyUsername = PrefManager.getVal<String>(PrefName.Socks5ProxyUsername)
                    val proxyPassword = PrefManager.getVal<String>(PrefName.Socks5ProxyPassword)
                    settings.setString(org.libtorrent4j.swig.settings_pack.string_types.proxy_username.swigValue(), proxyUsername)
                    settings.setString(org.libtorrent4j.swig.settings_pack.string_types.proxy_password.swigValue(), proxyPassword)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_type.swigValue(), org.libtorrent4j.swig.settings_pack.proxy_type_t.socks5_pw.swigValue())
                } else {
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_type.swigValue(), org.libtorrent4j.swig.settings_pack.proxy_type_t.socks5.swigValue())
                }
            } else {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_type.swigValue(), org.libtorrent4j.swig.settings_pack.proxy_type_t.none.swigValue())
            }

    }

    private fun prioritizeHeadAndTailPieces(
        handle: TorrentHandle,
        firstPiece: Int,
        lastPiece: Int,
        numPiecesTotal: Int,
        numPiecesOnePercent: Int
    ) {
            // 1. Prioritize Head Pieces (Container Headers & Video Start) with immediate top priority
            for (i in 0 until numPiecesOnePercent) {
                val p = firstPiece + i
                if (p <= lastPiece && p < numPiecesTotal) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, i * 250)
                }
            }

            // 2. Prioritize Tail Pieces (moov atom / Matroska Cues) with default priority to avoid splitting initial bandwidth
            for (i in 0 until numPiecesOnePercent) {
                val p = lastPiece - i
                if (p >= firstPiece && p < numPiecesTotal) {
                    handle.piecePriority(p, Priority.DEFAULT)
                    handle.setPieceDeadline(p, 3000 + (i * 500))
                }
            }

    }

}
