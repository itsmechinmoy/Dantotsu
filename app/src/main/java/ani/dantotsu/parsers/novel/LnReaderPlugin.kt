package ani.dantotsu.parsers.novel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LnReaderPluginItem(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String,
    val customJS: String? = null,
    val customCSS: String? = null,
    val hasUpdate: Boolean = false,
    val hasSettings: Boolean = false,
)

@Serializable
data class LnReaderInstalledPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val iconUrl: String,
    val jsFilePath: String,
    val hasUpdate: Boolean = false,
)

@Serializable
data class LnNovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
)

@Serializable
data class LnChapterItem(
    val name: String,
    val path: String,
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
    val page: String? = null,
)

@Serializable
data class LnSourceNovel(
    val name: String,
    val path: String,
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val rating: Double? = null,
    val chapters: List<LnChapterItem>? = null,
)

@Serializable
data class LnSourcePage(
    val chapters: List<LnChapterItem>? = null,
)
