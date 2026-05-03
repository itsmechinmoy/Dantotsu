package ani.dantotsu.connections

import ani.dantotsu.connections.anilist.api.FuzzyDate
import kotlinx.serialization.Serializable

@Serializable
data class PendingProgressUpdate(
    val mediaId: Int,
    val idMAL: Int?,
    val isAnime: Boolean,
    val progress: Int,
    val status: String,
    val score: Int? = null,
    val rewatch: Int? = null,
    val notes: String? = null,
    val isPrivate: Boolean = false,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val customLists: List<String>? = null,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }
}
