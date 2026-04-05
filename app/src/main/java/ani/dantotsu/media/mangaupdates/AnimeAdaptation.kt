package ani.dantotsu.media.mangaupdates
import kotlinx.serialization.Serializable

@Serializable
data class AnimeAdaptation(
    val animeStart: String? = null,
    val animeEnd: String? = null,
    val hasAdaptation: Boolean = false,
    val error: String? = null
) {
    override fun toString(): String {
        if (error != null) return "Error: $error"
        return "Anime: ${animeStart ?: "Unknown"} - ${animeEnd ?: "Ongoing"}"
    }
}