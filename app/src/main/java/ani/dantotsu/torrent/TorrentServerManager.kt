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
import org.libtorrent4j.*
import java.io.File
import java.net.URLEncoder

@Inject
@SingleIn(AppScope::class)
class TorrentServerManager(private val context: Context) {
    private val sessionManager by lazy { SessionManager() }
    private var httpServer: TorrentHttpServer? = null
    var activeTorrentHash: String? = null
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
    }

    fun start() {
        if (sessionManager.isRunning) return
        Logger.log("Starting built-in TorrentServerManager with qBittorrent optimizations...")
        try {
            val settings = SettingsPack()

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

            val params = SessionParams(settings)
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
        httpServer?.stop()
        httpServer = null
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
    }

    fun isRunning(): Boolean = sessionManager.isRunning

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

        val cacheDir = getTorrentCacheDir()
        var handle: TorrentHandle? = null

        if (url.startsWith("magnet:", ignoreCase = true)) {
            val enhancedUrl = enhanceMagnetUrl(url)
            sessionManager.download(enhancedUrl, cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val infoHash = parseMagnetHash(url)
            val sha1 = Sha1Hash.parseHex(infoHash)
            handle = sessionManager.find(sha1)

            // Wait for metadata (up to 60 seconds)
            var waitTime = 0
            while ((handle == null || handle.torrentFile() == null) && waitTime < 600) {
                Thread.sleep(100)
                handle = sessionManager.find(sha1)
                waitTime++
            }
            if (handle != null && handle.torrentFile() != null) {
                val numFiles = handle.torrentFile()!!.numFiles()
                val priorities = Priority.array(Priority.IGNORE, numFiles)
                handle.prioritizeFiles(priorities)
            }
        } else if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val tempFile = downloadTorrentFile(url)
            if (tempFile != null) {
                val ti = TorrentInfo(tempFile)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sessionManager.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sessionManager.find(ti.infoHash())
            }
        } else {
            val path = url.removePrefix("file://")
            val file = File(path)
            if (file.exists()) {
                val ti = TorrentInfo(file)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sessionManager.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sessionManager.find(ti.infoHash())
            }
        }

        if (handle == null) {
            throw Exception("Failed to add torrent: $url")
        }

        // Explicitly resume to ensure downloading starts
        handle.resume()

        val infoHash = handle.infoHash().toHex()
        val name = handle.getName() ?: title
        val size = handle.torrentFile()?.totalSize() ?: 0L

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
            name = name,
            hash = infoHash,
            torrent_size = size,
            file_stats = fileStats
        )
    }

    /**
     * Pre-buffers video file using qBittorrent's 1% Head + 1% Tail piece algorithm.
     * This guarantees container header and moov atom / Matroska Cues are downloaded
     * before ExoPlayer begins playback, preventing buffering freezes.
     */
    fun prebuffer(torrentHash: String, fileIndex: Int): Boolean {
        try {
            val sha1 = Sha1Hash.parseHex(torrentHash)
            val handle = sessionManager.find(sha1) ?: return false

            // Wait for metadata if not loaded yet
            var waitTime = 0
            while (handle.torrentFile() == null && waitTime < 300) {
                Thread.sleep(100)
                waitTime++
            }

            val torrentInfo = handle.torrentFile() ?: return false
            val fileStorage = torrentInfo.files()

            if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) return false
            handle.filePriority(fileIndex, Priority.TOP_PRIORITY)

            val fileOffset = fileStorage.fileOffset(fileIndex)
            val fileSize = fileStorage.fileSize(fileIndex)
            val pieceLength = torrentInfo.pieceLength().toLong()
            val numPiecesTotal = torrentInfo.numPieces()

            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = if (fileSize > 0) ((fileOffset + fileSize - 1) / pieceLength).toInt() else firstPiece

            // Calculate 1% of total file size in pieces (min 2, max 16 pieces, matching qBittorrent formula)
            val numPiecesOnePercent = if (fileSize > 0 && pieceLength > 0) {
                ((fileSize * 0.01) / pieceLength).toInt().coerceIn(2, 16)
            } else 2

            Logger.log("TorrentServerManager: Pre-buffering $numPiecesOnePercent head pieces and $numPiecesOnePercent tail pieces for file $fileIndex")

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

            // 3. Quick check for initial readiness without freezing the player launch
            var waitCount = 0
            while (!handle.havePiece(firstPiece) && waitCount < 30) {
                if (!sessionManager.isRunning || !handle.isValid) break
                Thread.sleep(100)
                waitCount++
            }
            val success = handle.havePiece(firstPiece)
            Logger.log("TorrentServerManager: Pre-buffering initial piece check = $success (ready for instant playback)")
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun getLink(torrent: Torrent, fileIndex: Int): String {
        return "http://127.0.0.1:$serverPort/stream?hash=${torrent.hash}&index=$fileIndex"
    }

    fun getLink(torrentHash: String, fileIndex: Int): String {
        return "http://127.0.0.1:$serverPort/stream?hash=$torrentHash&index=$fileIndex"
    }

    fun removeTorrent(torrentHash: String) {
        try {
            val sha1 = Sha1Hash.parseHex(torrentHash)
            val handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid) {
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
                java.net.ServerSocket(port).use {
                    return port
                }
            } catch (e: java.io.IOException) {
                port++
            }
        }
        return startPort
    }

    private fun parseMagnetHash(url: String): String {
        val xtIndex = url.indexOf("xt=urn:btih:")
        if (xtIndex != -1) {
            var hash = url.substring(xtIndex + 12)
            val ampersandIndex = hash.indexOf("&")
            if (ampersandIndex != -1) {
                hash = hash.substring(0, ampersandIndex)
            }
            return hash.uppercase()
        }
        throw IllegalArgumentException("Invalid magnet link")
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
}
