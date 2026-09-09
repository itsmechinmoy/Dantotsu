package ani.dantotsu.download.video

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.player.WrappedDrmCallback
import ani.dantotsu.parsers.OfflineDrmInfo
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a DRM-protected DASH stream into the episode's normal download folder,
 * alongside a persistent license.
 *
 * The media stays CENC-encrypted: a plain file would need the content key, which the
 * CDM never releases. What makes it play offline is the keySetId written to
 * [DRM_SIDECAR], which is bound to this device's CDM. Source-agnostic - everything
 * needed arrives in [OfflineDrmInfo].
 */
@UnstableApi
object DrmDownloader {

    private const val TAG = "DrmDownloader"

    // Logger writes to a file, which is awkward to reach over adb.
    private fun log(message: String) {
        Log.d(TAG, message)
        Logger.log(message)
    }

    const val DRM_SIDECAR = "drm.json"
    const val VIDEO_FILE = "video.mp4"
    const val AUDIO_FILE = "audio.mp4"

    class Result(val keySetId: ByteArray, val licenseSeconds: Long, val playbackSeconds: Long)

    /** [onProgress] reports 0..100 across every segment of every track. */
    suspend fun download(
        context: Context,
        drm: OfflineDrmInfo,
        scheme: String,
        outputDir: DocumentFile,
        onProgress: (Int) -> Unit,
    ): Result {
        val factory = dataSourceFactory(drm.headers)
        val manifest = DashUtil.loadManifest(factory.createDataSource(), drm.manifestUrl.toUri())
        require(manifest.periodCount > 0) { "manifest has no periods" }
        val period = manifest.getPeriod(0)
        val periodDurationUs = manifest.getPeriodDurationUs(0)

        val video = bestRepresentation(period, C.TRACK_TYPE_VIDEO)
            ?: throw IllegalStateException("manifest has no video track")
        val audio = bestRepresentation(period, C.TRACK_TYPE_AUDIO)

        // Most likely step to fail; no point writing a gigabyte we cannot play.
        val result = acquireLicense(factory, period, drm, scheme)
        log(
            "DRM download: license acquired, valid ${result.licenseSeconds}s " +
                "(playback window ${result.playbackSeconds}s)"
        )

        val tracks = buildList {
            add(video to VIDEO_FILE)
            audio?.let { add(it to AUDIO_FILE) }
        }
        val totalSegments = tracks.sumOf { (rep, _) ->
            rep.index?.getSegmentCount(periodDurationUs)?.coerceAtLeast(0L) ?: 0L
        }.coerceAtLeast(1L)

        try {
            var done = 0L
            for ((representation, name) in tracks) {
                done = writeTrack(
                    context, factory, representation, periodDurationUs, outputDir, name,
                    done, totalSegments, onProgress,
                )
            }
            writeSidecar(context, outputDir, drm, scheme, result)
        } catch (e: Throwable) {
            // Without a sidecar the leftovers would look like an ordinary download and
            // fail to play, so a half-written episode is removed rather than kept.
            Log.e(TAG, "download failed for ${outputDir.name}", e)
            discardPartial(outputDir)
            throw e
        }
        onProgress(100)
        return result
    }

    private fun discardPartial(outputDir: DocumentFile) {
        listOf(VIDEO_FILE, AUDIO_FILE, DRM_SIDECAR).forEach { name ->
            runCatching { outputDir.findFile(name)?.delete() }
        }
    }

    /**
     * Tops up the persistent license of an existing download, leaving the media files
     * untouched. Needs a live token, so it only does anything when the episode is
     * opened with a connection.
     *
     * @return true when a new license was stored.
     */
    suspend fun refreshIfStale(
        context: Context,
        drm: OfflineDrmInfo,
        scheme: String,
        directory: DocumentFile,
        thresholdSeconds: Long = TimeUnit.DAYS.toSeconds(2),
    ): Boolean = withContext(Dispatchers.IO) {
        val sidecar = readSidecar(context, directory) ?: return@withContext false
        val stored = runCatching {
            Base64.decode(sidecar.optString("keySetId"), Base64.NO_WRAP)
        }.getOrNull()
        if (stored == null || stored.isEmpty()) return@withContext false

        // Querying the CDM means opening a DRM session, so skip it entirely while the
        // recorded expiry is still comfortably far off.
        val expiresAt = sidecar.optLong("acquiredAt") +
            TimeUnit.SECONDS.toMillis(sidecar.optLong("licenseSeconds"))
        if (System.currentTimeMillis() < expiresAt - TimeUnit.SECONDS.toMillis(thresholdSeconds)) {
            return@withContext false
        }

        val factory = dataSourceFactory(drm.headers)
        val remaining = runCatching { remainingSeconds(factory, drm, scheme, stored) }
            .getOrElse {
                log("DRM refresh: could not read license duration (${it.message})")
                0L
            }
        if (remaining > thresholdSeconds) {
            log("DRM refresh: license still valid for ${remaining}s, leaving it alone")
            return@withContext false
        }

        log("DRM refresh: ${remaining}s left, acquiring a new license")
        val manifest = DashUtil.loadManifest(factory.createDataSource(), drm.manifestUrl.toUri())
        val result = acquireLicense(factory, manifest.getPeriod(0), drm, scheme)
        writeSidecar(context, directory, drm, scheme, result)
        log("DRM refresh: renewed, valid ${result.licenseSeconds}s")
        true
    }

    private fun remainingSeconds(
        factory: DataSource.Factory,
        drm: OfflineDrmInfo,
        scheme: String,
        keySetId: ByteArray,
    ): Long {
        val http = HttpMediaDrmCallback(drm.licenseUrl, true, factory)
        drm.licenseHeaders.forEach { (name, value) -> http.setKeyRequestProperty(name, value) }
        val uuid = when (scheme.lowercase()) {
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> C.WIDEVINE_UUID
        }
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(WrappedDrmCallback(http))
        val helper = OfflineLicenseHelper(manager, DrmSessionEventListener.EventDispatcher())
        return try {
            helper.getLicenseDurationRemainingSec(keySetId).first ?: 0L
        } finally {
            helper.release()
        }
    }

    private fun dataSourceFactory(extra: Map<String, String>): DataSource.Factory {
        val client = Injekt.get<NetworkHelper>().client
        // The source's own headers win: a manifest behind auth 401s without them.
        val merged = defaultHeaders + extra
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(merged)
            merged["User-Agent"]?.let { setUserAgent(it) }
        }
    }

    private fun bestRepresentation(period: Period, type: Int): Representation? =
        period.adaptationSets
            .filter { it.type == type }
            .flatMap { it.representations }
            .maxByOrNull { rep ->
                rep.format.bitrate.takeIf { it != Format.NO_VALUE } ?: 0
            }

    private fun acquireLicense(
        factory: DataSource.Factory,
        period: Period,
        drm: OfflineDrmInfo,
        scheme: String,
    ): Result {
        val format = DashUtil.loadFormatWithDrmInitData(factory.createDataSource(), period)
            ?: throw IllegalStateException("no DRM init data in the manifest")

        // newWidevineInstance() would install a plain HttpMediaDrmCallback, which
        // cannot cope with a wrapped license response.
        val http = HttpMediaDrmCallback(drm.licenseUrl, true, factory)
        drm.licenseHeaders.forEach { (name, value) -> http.setKeyRequestProperty(name, value) }
        val uuid = when (scheme.lowercase()) {
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> C.WIDEVINE_UUID
        }
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(WrappedDrmCallback(http))

        val helper = OfflineLicenseHelper(manager, DrmSessionEventListener.EventDispatcher())
        return try {
            val keySetId = helper.downloadLicense(format)
                ?: throw IllegalStateException("license server returned no key set")
            val remaining = helper.getLicenseDurationRemainingSec(keySetId)
            Result(keySetId, remaining.first ?: 0L, remaining.second ?: 0L)
        } finally {
            helper.release()
        }
    }

    /** Concatenates the init segment and every media segment into one fragmented MP4. */
    private suspend fun writeTrack(
        context: Context,
        factory: DataSource.Factory,
        representation: Representation,
        periodDurationUs: Long,
        outputDir: DocumentFile,
        fileName: String,
        segmentsDoneBefore: Long,
        totalSegments: Long,
        onProgress: (Int) -> Unit,
    ): Long {
        outputDir.findFile(fileName)?.delete()
        val target = outputDir.createFile("video/mp4", fileName)
            ?: throw IllegalStateException("could not create $fileName")

        val index = representation.index
            ?: throw IllegalStateException("$fileName has no segment index")
        val first = index.firstSegmentNum
        val count = index.getSegmentCount(periodDurationUs)
        check(count != DashSegmentIndex.INDEX_UNBOUNDED.toLong()) {
            "$fileName is a live stream and cannot be downloaded"
        }

        var done = segmentsDoneBefore
        context.contentResolver.openOutputStream(target.uri, "w")?.use { out ->
            representation.initializationUri?.let { copySegment(factory, representation, it, out) }
            for (i in first until first + count) {
                currentCoroutineContext().ensureActive()
                copySegment(factory, representation, index.getSegmentUrl(i), out)
                done++
                onProgress((done * 100 / totalSegments).toInt().coerceIn(0, 99))
            }
            out.flush()
        } ?: throw IllegalStateException("could not open $fileName for writing")

        log("DRM download: wrote $fileName ($count segments)")
        return done
    }

    private fun copySegment(
        factory: DataSource.Factory,
        representation: Representation,
        uri: RangedUri,
        out: OutputStream,
    ) {
        val baseUrl = representation.baseUrls.firstOrNull()?.url.orEmpty()
        val spec = DashUtil.buildDataSpec(representation, baseUrl, uri, 0)
        DataSourceInputStream(factory.createDataSource(), spec).use { input ->
            input.open()
            input.copyTo(out, DEFAULT_BUFFER_SIZE)
        }
    }

    private fun writeSidecar(
        context: Context,
        outputDir: DocumentFile,
        drm: OfflineDrmInfo,
        scheme: String,
        result: Result,
    ) {
        val json = JSONObject().apply {
            put("scheme", scheme)
            put("keySetId", Base64.encodeToString(result.keySetId, Base64.NO_WRAP))
            put("licenseUrl", drm.licenseUrl)
            put("acquiredAt", System.currentTimeMillis())
            put("licenseSeconds", result.licenseSeconds)
            put("playbackSeconds", result.playbackSeconds)
            put("video", VIDEO_FILE)
            put("audio", AUDIO_FILE)
        }
        outputDir.findFile(DRM_SIDECAR)?.delete()
        val file = outputDir.createFile("application/json", DRM_SIDECAR)
            ?: throw IllegalStateException("could not create $DRM_SIDECAR")
        context.contentResolver.openOutputStream(file.uri, "w")?.use {
            it.write(json.toString().toByteArray())
        }
    }

    /**
     * Builds the [MediaItem] for a downloaded encrypted episode, paired with the separate
     * audio file when one was written. The stored keySetId lets the CDM restore the
     * license with no network. Null when this is an ordinary download.
     */
    fun offlineMediaItem(context: Context, directory: DocumentFile): Pair<MediaItem, Uri?>? {
        val sidecar = readSidecar(context, directory) ?: return null
        val video = directory.findFile(sidecar.optString("video", VIDEO_FILE)) ?: return null
        val keySetId = runCatching {
            Base64.decode(sidecar.optString("keySetId"), Base64.NO_WRAP)
        }.getOrNull()
        if (keySetId == null || keySetId.isEmpty()) {
            log("DRM playback: $DRM_SIDECAR has no key set id")
            return null
        }
        val uuid = when (sidecar.optString("scheme").lowercase()) {
            "playready" -> C.PLAYREADY_UUID
            "clearkey" -> C.CLEARKEY_UUID
            else -> C.WIDEVINE_UUID
        }
        val item = MediaItem.Builder()
            .setUri(video.uri)
            .setMimeType(MimeTypes.APPLICATION_MP4)
            .setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(sidecar.optString("licenseUrl"))
                    .setKeySetId(keySetId)
                    .build()
            )
            .build()
        val audio = directory.findFile(sidecar.optString("audio", AUDIO_FILE))?.uri
        log("DRM playback: restoring offline license for ${video.name}")
        return item to audio
    }

    /** Null when this is not a DRM download. */
    fun readSidecar(context: Context, directory: DocumentFile): JSONObject? {
        val file = directory.findFile(DRM_SIDECAR) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.use {
                JSONObject(it.readBytes().decodeToString())
            }
        }.getOrNull()
    }
}
