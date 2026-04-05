package ani.dantotsu.media.mangaupdates
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.util.Date

@Serializable
data class NextRelease(
    @Contextual val nextReleaseDate: Date? = null,
    val averageIntervalDays: Int? = null,
    val latestChapter: String? = null,
    val nextChapter: String? = null,
    val error: String? = null
) {
    override fun toString(): String {
        if (error != null) return "Error: $error"
        return "Next: $nextChapter on $nextReleaseDate"
    }
}