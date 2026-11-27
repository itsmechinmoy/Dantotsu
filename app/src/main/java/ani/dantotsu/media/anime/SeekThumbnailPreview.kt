package ani.dantotsu.media.anime

import android.graphics.Bitmap
import ani.dantotsu.media.anime.mpv.DantotsuTimeBar

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
        timeBar: DantotsuTimeBar,
        onScrubMove: (positionMs: Long, tile: ThumbnailTileInfo?) -> Unit,
        onScrubStop: () -> Unit
    ) {
        timeBar.addListener(object : DantotsuTimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: DantotsuTimeBar, position: Long) {
                onScrubMove(position, getTileForPosition(position))
            }

            override fun onScrubMove(timeBar: DantotsuTimeBar, position: Long) {
                onScrubMove(position, getTileForPosition(position))
            }

            override fun onScrubStop(timeBar: DantotsuTimeBar, position: Long, canceled: Boolean) {
                onScrubStop()
            }
        })
    }
}
