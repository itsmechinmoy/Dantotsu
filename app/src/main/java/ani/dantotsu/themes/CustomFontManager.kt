package ani.dantotsu.themes

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CustomFontManager {
    private const val TAG = "CustomFontManager"
    private const val CSS_UA = "curl/8.0"
    private val TTF_URL_REGEX = Regex("""url\((https://fonts\.gstatic\.com/[^)]+\.(?:ttf|otf))\)""", RegexOption.IGNORE_CASE)

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val popularGoogleFonts: List<String> = listOf(
        "Poppins", "Inter", "Roboto", "Open Sans", "Lato", "Montserrat", "Nunito",
        "Plus Jakarta Sans", "DM Sans", "Space Grotesk", "Rubik", "Outfit", "Quicksand",
        "Raleway", "Oswald", "Playfair Display", "Merriweather", "Lora", "Noto Sans",
        "Noto Serif", "JetBrains Mono", "Fira Code", "Source Sans 3", "Ubuntu",
        "Work Sans", "Manrope", "Lexend", "Sora", "Comfortaa", "Josefin Sans",
        "Bitter", "Crimson Pro", "Bebas Neue", "Pacifico", "Caveat", "Dancing Script",
        "Karla", "Mulish", "Barlow", "PT Sans", "PT Serif", "Inconsolata", "Arvo",
        "Titillium Web", "Heebo", "IBM Plex Sans", "IBM Plex Mono", "Figtree",
        "Onest", "Urbanist", "Albert Sans", "Bricolage Grotesque", "Geist"
    ).sorted()

    fun fontsDir(context: Context): File {
        val dir = File(context.filesDir, "fonts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSavedFonts(context: Context): List<File> {
        val files = fontsDir(context).listFiles()?.filter { file ->
            val ext = file.extension.lowercase()
            file.isFile && (ext == "ttf" || ext == "otf")
        }.orEmpty()
        return files.sortedBy { it.name.lowercase() }
    }

    fun getActiveCustomFontFile(): File? {
        val path = PrefManager.getVal<String>(PrefName.CustomFontPath)
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.isFile }
    }

    fun getActiveTypeface(): Typeface? {
        val file = getActiveCustomFontFile() ?: return null
        return typefaceCache[file.absolutePath] ?: runCatching {
            Typeface.createFromFile(file).also { tf ->
                typefaceCache[file.absolutePath] = tf
            }
        }.getOrNull()
    }

    fun activateCustomFont(file: File): Boolean {
        val loaded = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return false
        typefaceCache[file.absolutePath] = loaded
        PrefManager.setVal(PrefName.CustomFontPath, file.absolutePath)
        PrefManager.setVal(PrefName.UseSystemFont, false)
        return true
    }

    fun clearCustomFont() {
        PrefManager.setVal(PrefName.CustomFontPath, "")
    }

    fun deleteSavedFont(file: File) {
        val activePath = PrefManager.getVal<String>(PrefName.CustomFontPath)
        if (activePath == file.absolutePath) {
            clearCustomFont()
        }
        typefaceCache.remove(file.absolutePath)
        if (file.exists()) {
            file.delete()
        }
    }

    fun displayNameForActiveFont(): String? {
        return getActiveCustomFontFile()?.nameWithoutExtension
    }

    suspend fun importFontFromUri(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(context, uri) ?: "custom_font.ttf"
        val ext = displayName.substringAfterLast('.', "ttf").lowercase().let {
            if (it == "otf") "otf" else "ttf"
        }
        val stem = displayName.substringBeforeLast('.').ifBlank { "custom_font" }
        val target = uniqueTargetFile(fontsDir(context), stem, ext)

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Typeface.createFromFile(target)
        }
        if (copied.isFailure || !target.exists() || target.length() == 0L) {
            target.delete()
            return@withContext null
        }
        target
    }

    suspend fun downloadGoogleFont(context: Context, familyName: String): File? = withContext(Dispatchers.IO) {
        val cleanFamily = familyName.trim()
        if (cleanFamily.isBlank()) return@withContext null
        val encoded = URLEncoder.encode(cleanFamily, "UTF-8")
        val cssUrl = "https://fonts.googleapis.com/css2?family=$encoded&display=swap"

        val ttfUrl = runCatching {
            val req = Request.Builder()
                .url(cssUrl)
                .header("User-Agent", CSS_UA)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                TTF_URL_REGEX.find(body)?.groupValues?.getOrNull(1)
            }
        }.getOrElse {
            Logger.log("$TAG: CSS2 request failed for $cleanFamily: ${it.message}")
            null
        } ?: return@withContext null

        val safeStem = cleanFamily.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dest = File(fontsDir(context), "$safeStem.ttf")
        val temp = File(fontsDir(context), "$safeStem.tmp")

        val ok = runCatching {
            val req = Request.Builder().url(ttfUrl).header("User-Agent", CSS_UA).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                if (bytes.isEmpty()) return@use false
                temp.writeBytes(bytes)
                Typeface.createFromFile(temp)
                if (dest.exists()) dest.delete()
                temp.renameTo(dest)
            }
        }.getOrElse {
            Logger.log("$TAG: TTF download failed for $cleanFamily: ${it.message}")
            temp.delete()
            false
        }
        if (!ok) {
            temp.delete()
            return@withContext null
        }
        dest
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun uniqueTargetFile(dir: File, stem: String, ext: String): File {
        val cleanStem = stem.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var candidate = File(dir, "$cleanStem.$ext")
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "$cleanStem ($idx).$ext")
            idx++
        }
        return candidate
    }
}
