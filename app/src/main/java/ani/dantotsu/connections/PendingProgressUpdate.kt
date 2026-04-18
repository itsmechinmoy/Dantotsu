package ani.dantotsu.connections

import kotlinx.serialization.Serializable

@Serializable
data class PendingProgressUpdate(
    val mediaId: Int,
    val idMAL: Int?,
    val isAnime: Boolean,
    val progress: Int,
    val status: String,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
