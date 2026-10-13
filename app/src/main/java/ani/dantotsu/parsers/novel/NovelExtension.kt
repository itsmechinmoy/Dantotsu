package ani.dantotsu.parsers.novel

import android.graphics.drawable.Drawable
import ani.dantotsu.parsers.NovelInterface

sealed class NovelExtension {
    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        val sources: List<NovelInterface>,
        val icon: Drawable?,
        val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        val isUnofficial: Boolean = false,
        val lang: String = "all",
        val repository: String? = null,
        val repoName: String? = null,
    ) : NovelExtension()

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        var repository: String,
        val sources: List<AvailableNovelSources>,
        val iconUrl: String,
        val lang: String = "all",
        var repoName: String? = null,
    ) : NovelExtension()

    data class JsPlugin(
        val plugin: LnReaderInstalledPlugin,
    ) : NovelExtension() {
        override val name: String       get() = plugin.name
        override val pkgName: String    get() = plugin.id
        override val versionName: String get() = plugin.version
        override val versionCode: Long   get() = 0L
        val hasUpdate: Boolean           get() = plugin.hasUpdate
        val iconUrl: String              get() = plugin.iconUrl
    }
}

data class AvailableNovelSources(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
) {
    fun toNovelSourceData(): NovelSourceData {
        return NovelSourceData(
            id   = this.id,
            lang = this.lang,
            name = this.name,
        )
    }
}

data class NovelSourceData(
    val id: Long,
    val lang: String,
    val name: String,
) {
    val isMissingInfo: Boolean = name.isBlank() || lang.isBlank()
}

fun isSameNovelExtension(pkg1: String, name1: String, pkg2: String, name2: String): Boolean {
    val p1 = pkg1.trim().lowercase()
    val p2 = pkg2.trim().lowercase()

    // 1. Exact package name match
    if (p1.isNotBlank() && p1 == p2) return true

    val n1 = name1.trim().lowercase()
    val n2 = name2.trim().lowercase()

    // 2. Extension name match
    if (n1.isNotBlank() && n1 == n2) return true

    // 3. Normalized name match (ignoring punctuation, hyphens, and whitespace)
    val norm1 = n1.replace("'", "").replace(" ", "").replace("-", "").replace(".", "")
    val norm2 = n2.replace("'", "").replace(" ", "").replace("-", "").replace(".", "")
    if (norm1.isNotBlank() && norm1 == norm2) return true

    // 4. Package suffix match (e.g. ani.dantotsu.extension.novel.xyz)
    val s1 = p1.substringAfterLast('.')
    val s2 = p2.substringAfterLast('.')
    if (s1.isNotBlank() && s1 == s2 && s1.length > 3) return true

    // 5. Cross-match: package suffix against normalized name
    if (s1.isNotBlank() && norm2.isNotBlank() && s1 == norm2) return true
    if (s2.isNotBlank() && norm1.isNotBlank() && s2 == norm1) return true

    return false
}