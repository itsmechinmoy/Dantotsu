package ani.dantotsu.torrent

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.torrentServer.model.FileStat
import eu.kanade.tachiyomi.data.torrentServer.model.Torrent
import org.libtorrent4j.*
import java.io.File

class TorrentServerManager(private val context: Context) {
    private val sessionManager = SessionManager()
    private var httpServer: TorrentHttpServer? = null
    var activeTorrentHash: String? = null
    var serverPort: Int = 8090
        private set

    fun start() {
        if (sessionManager.isRunning) return
        Logger.log("Starting built-in TorrentServerManager...")
        try {
            val settings = SettingsPack()
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)

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
            val wifiOnly = PrefManager.getVal<Boolean>(PrefName.TorrentWifiOnly)
            if (wifiOnly) {
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.outgoing_interfaces.swigValue(), "wlan0")
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

    fun isRunning(): Boolean {
        return sessionManager.isRunning
    }

    fun isAvailable(andEnabled: Boolean = true): Boolean {
        return if (andEnabled) {
            PrefManager.getVal(PrefName.TorrentEnabled)
        } else {
            true
        }
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

        if (url.startsWith("magnet:")) {
            sessionManager.download(url, cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            val tempFile = downloadTorrentFile(url)
            if (tempFile != null) {
                val ti = TorrentInfo(tempFile)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sessionManager.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sessionManager.find(ti.infoHash())
            }
        } else {
            val file = File(url)
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

            val fileOffset = fileStorage.fileOffset(fileIndex)
            val pieceLength = torrentInfo.pieceLength().toLong()
            val firstPiece = (fileOffset / pieceLength).toInt()

            Logger.log("TorrentServerManager: Pre-buffering piece $firstPiece for file $fileIndex")

            // Prioritize first piece
            handle.piecePriority(firstPiece, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(firstPiece, 1000)

            // Prioritize next piece too
            val secondPiece = firstPiece + 1
            if (secondPiece < torrentInfo.numPieces()) {
                handle.piecePriority(secondPiece, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(secondPiece, 2000)
            }

            // Wait up to 15 seconds for the first piece to complete
            var waitCount = 0
            while (!handle.havePiece(firstPiece) && waitCount < 150) {
                if (!sessionManager.isRunning || !handle.isValid) break
                Thread.sleep(100)
                waitCount++
            }
            val success = handle.havePiece(firstPiece)
            Logger.log("TorrentServerManager: Pre-buffering success = $success")
            return success
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
}
