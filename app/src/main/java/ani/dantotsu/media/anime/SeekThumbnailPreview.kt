package ani.dantotsu.media.anime

import android.graphics.Bitmap
import androidx.media3.ui.TimeBar

data class ThumbnailTileInfo(
    val timeMs: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap?
)

class SeekThumbnailPreview {
    private var tiles = mutableListOf<ThumbnailTileInfo>()

    fun setTiles(tileList: List<ThumbnailTileInfo>) {
        tiles.clear()
        tiles.addAll(tileList)
    }

    fun getTileForPosition(positionMs: Long): ThumbnailTileInfo? {
        return tiles.lastOrNull { it.timeMs <= positionMs }
    }

    fun attachToTimeBar(
        timeBar: TimeBar,
        onScrubMove: (positionMs: Long, tile: ThumbnailTileInfo?) -> Unit,
        onScrubStop: () -> Unit
    ) {
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                onScrubMove(position, getTileForPosition(position))
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                onScrubMove(position, getTileForPosition(position))
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                onScrubStop()
            }
        })
    }
}
