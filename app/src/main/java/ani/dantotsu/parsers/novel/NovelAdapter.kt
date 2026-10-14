package ani.dantotsu.parsers.novel

import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.NovelParser
import ani.dantotsu.parsers.ShowResponse
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelAdapter

class DynamicNovelParser(extension: NovelExtension.Installed) : NovelParser() {
    override val name: String get() = extension.name
    override val saveName: String get() {
        val src = extension.sources.firstOrNull()
        return try {
            val field = src?.javaClass?.getDeclaredField("saveName")
            field?.isAccessible = true
            (field?.get(src) as? String)?.takeIf { it.isNotBlank() } ?: extension.pkgName
        } catch (_: Exception) {
            extension.pkgName
        }
    }
    override val hostUrl: String get() {
        val src = extension.sources.firstOrNull()
        return try {
            val field = src?.javaClass?.getDeclaredField("currentHost")
            field?.isAccessible = true
            (field?.get(src) as? String)?.takeIf { it.isNotBlank() } ?: "https://${extension.pkgName}"
        } catch (_: Exception) {
            "https://${extension.pkgName}"
        }
    }
    override val icon: android.graphics.drawable.Drawable? get() = extension.icon

    override val volumeRegex =
        Regex("vol\\.? (\\d+(\\.\\d+)?)|volume (\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE)
    var extension: NovelExtension.Installed
    val client = Injekt.get<NetworkHelper>().requestClient

    init {
        this.extension = extension
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val source = extension.sources.firstOrNull()
        return if (source is NovelInterface) {
            source.search(query, client)
        } else {
            emptyList()
        }
    }

    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book {
        val source = extension.sources.firstOrNull()
        if (source is NovelInterface) {
            return source.loadBook(link, extra, client)
        } else {
            return Book("", "", "", emptyList())
        }
    }

}