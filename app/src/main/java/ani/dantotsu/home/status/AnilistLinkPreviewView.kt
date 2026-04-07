package ani.dantotsu.home.status

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import ani.dantotsu.databinding.ItemAnilistLinkPreviewBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity

class AnilistLinkPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var previewBinding: ItemAnilistLinkPreviewBinding? = null

    init {
        showEmpty()
    }

    private fun showEmpty() {
        removeAllViews()
        previewBinding = null
    }

    fun setMediaData(media: Media) {
        showPreview(media)
    }

    private fun showPreview(media: Media) {
        removeAllViews()
        previewBinding = ItemAnilistLinkPreviewBinding.inflate(
            LayoutInflater.from(context),
            this,
            true
        )

        previewBinding?.apply {
            media.cover?.let { coverUrl ->
                previewCoverImage.loadImage(coverUrl)
            }

            previewTitle.text = media.userPreferredName

            val statusValue = media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
            val format = media.format?.replace("_", " ") ?: ""
            previewFormatStatus.text = if (format.isNotEmpty() && statusValue.isNotEmpty()) {
                "$format · $statusValue"
            } else {
                format.ifEmpty { statusValue }
            }

            val seasonYear = buildSeasonYearString(media)
            val score = media.meanScore?.let { "${it}%" } ?: ""
            previewSeasonScore.text = if (seasonYear.isNotEmpty() && score.isNotEmpty()) {
                "$seasonYear · $score"
            } else seasonYear.ifEmpty {
                score.ifEmpty {
                    previewSeasonScore.visibility = GONE
                    ""
                }
            }
            val animeData = media.anime
            val mangaData = media.manga
            val episodesOrChapters = when {
                animeData != null && animeData.totalEpisodes != null -> {
                    val eps = animeData.totalEpisodes
                    if (eps == 0) null else "$eps Episodes"
                }
                mangaData != null && mangaData.totalChapters != null -> {
                    val chaps = mangaData.totalChapters
                    if (chaps == 0) null else "$chaps Chapters"
                }
                else -> null
            }

            if (episodesOrChapters != null) {
                previewEpisodes.text = episodesOrChapters
                previewEpisodes.isVisible = true
            } else {
                previewEpisodes.isVisible = false
            }

            previewCard.setOnClickListener {
                val intent = Intent(context, MediaDetailsActivity::class.java).apply {
                    putExtra("mediaId", media.id)
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
            }
        }
    }

    private fun buildSeasonYearString(media: Media): String {
        val season = media.anime?.season?.lowercase()?.replaceFirstChar { it.uppercase() }
        val year = media.anime?.seasonYear ?: media.startDate?.year

        return listOfNotNull(season, year).joinToString(" ")
    }
}
