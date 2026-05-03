package ani.dantotsu.connections

import kotlinx.serialization.Serializable


@Serializable
data class PendingDeletion(
    val mediaId: Int,
    val idMAL: Int?,
    val isAnime: Boolean,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
