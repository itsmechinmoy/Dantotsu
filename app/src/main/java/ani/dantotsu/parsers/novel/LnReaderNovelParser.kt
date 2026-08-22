package ani.dantotsu.parsers.novel
import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.NovelParser
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class LnReaderNovelParser(
    private val plugin: LnReaderInstalledPlugin
) : NovelParser() {

    override val name: String    get() = plugin.name
    override val hostUrl: String get() = plugin.site
    override val saveName: String get() = "lnreader_${plugin.id}"
    override val iconUrl: String get() = plugin.iconUrl

    override val volumeRegex = Regex(
        "vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)",
        RegexOption.IGNORE_CASE
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String): List<ShowResponse> {
        return try {
            val raw = if (query.isNotBlank()) {
                try {
                    engineCall("searchNovels", """["${query.jsEscape()}", 1]""")
                } catch (e: Exception) {
                    Logger.log("LnReaderNovelParser[${plugin.id}] searchNovels failed, fallback to popular: ${e.message}")
                    engineCall("popularNovels", """[1, {}]""")
                }
            } else {
                engineCall("popularNovels", """[1, {"showLatestNovels": true}]""")
            }
            val jsonArr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
            jsonArr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rawCover = obj["cover"]?.jsonPrimitive?.contentOrNull ?: ""
                val cover = resolveUrl(rawCover)
                ShowResponse(
                    name     = name,
                    link     = path,
                    coverUrl = cover
                )
            }
        } catch (e: Exception) {
            Logger.log("LnReaderNovelParser[${plugin.id}].search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book {
        return try {
            val novelRaw = engineCall("parseNovel", """["${link.jsEscape()}"]""")
            val jsonElement = runCatching { Json.parseToJsonElement(novelRaw).jsonObject }.getOrNull()
                ?: throw IllegalStateException("Invalid JSON returned by parseNovel")

            val novelName = jsonElement["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown Novel"
            val rawCover = jsonElement["cover"]?.jsonPrimitive?.contentOrNull ?: ""
            val novelCover = resolveUrl(rawCover)
            val novelSummary = jsonElement["summary"]?.jsonPrimitive?.contentOrNull
            val totalPages = jsonElement["totalPages"]?.jsonPrimitive?.intOrNull
                ?: jsonElement["totalPages"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 1

            val chapters = mutableListOf<LnChapterItem>()

            fun extractChapters(element: kotlinx.serialization.json.JsonElement?) {
                val arr = element as? JsonArray ?: return
                for (item in arr) {
                    val obj = item as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
                    val releaseTime = obj["releaseTime"]?.jsonPrimitive?.contentOrNull
                    val chNum = obj["chapterNumber"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["chapterNumber"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    chapters.add(LnChapterItem(name = name, path = path, releaseTime = releaseTime, chapterNumber = chNum))
                }
            }

            extractChapters(jsonElement["chapters"])

            if (totalPages > 1) {
                for (p in 2..totalPages) {
                    try {
                        val pageRaw = engineCall("parsePage", """["${link.jsEscape()}", "$p"]""")
                        val pageObj = runCatching { Json.parseToJsonElement(pageRaw).jsonObject }.getOrNull()
                        extractChapters(pageObj?.get("chapters"))
                    } catch (e: Exception) {
                        Logger.log("LnReaderNovelParser[${plugin.id}] parsePage $p failed: ${e.message}")
                    }
                }
            } else if (chapters.isEmpty()) {
                try {
                    val pageRaw = engineCall("parsePage", """["${link.jsEscape()}", "1"]""")
                    val pageObj = runCatching { Json.parseToJsonElement(pageRaw).jsonObject }.getOrNull()
                    extractChapters(pageObj?.get("chapters"))
                } catch (_: Exception) {}
            }

            val links = chapters.map { ch ->
                val headers = mutableMapOf("X-Chapter-Name" to ch.name)
                ch.releaseTime?.let { headers["X-Release-Time"] = it }
                ch.chapterNumber?.let { headers["X-Chapter-Number"] = it.toString() }
                FileUrl(url = ch.path, headers = headers)
            }

            Book(
                name        = novelName,
                img         = FileUrl(novelCover),
                description = novelSummary,
                links       = links
            )
        } catch (e: Exception) {
            Logger.log("LnReaderNovelParser[${plugin.id}].loadBook error: ${e.message}")
            Book(name = "Error", img = FileUrl(""), description = e.message, links = emptyList())
        }
    }

    suspend fun loadChapterHtml(chapterPath: String): String {
        return try {
            val raw = engineCall("parseChapter", """["${chapterPath.jsEscape()}"]""")
            val decoded = runCatching { json.decodeFromString<String>(raw) }.getOrDefault(raw)
            if (decoded.isBlank() || decoded == "null") {
                "<html><body><p>No content available for this chapter.</p></body></html>"
            } else {
                decoded
            }
        } catch (e: Exception) {
            Logger.log("LnReaderNovelParser[${plugin.id}].loadChapterHtml error: ${e.message}")
            "<html><body><p>Failed to load chapter: ${e.message}</p></body></html>"
        }
    }
    
    private suspend fun engineCall(method: String, argsJson: String): String {
        val jsCode = File(plugin.jsFilePath).readText()
        return LnReaderJsEngine.call(
            pluginJs  = jsCode,
            pluginId  = plugin.id,
            method    = method,
            argsJson  = argsJson,
        )
    }
    
    private fun String.jsEscape(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("`", "\\`")

    private fun resolveUrl(path: String): String {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://") || path.startsWith("data:")) {
            return path
        }
        val site = plugin.site.trimEnd('/')
        val rel = path.trimStart('/')
        return "$site/$rel"
    }
}
