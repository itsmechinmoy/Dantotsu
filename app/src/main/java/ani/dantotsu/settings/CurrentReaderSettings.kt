package ani.dantotsu.settings

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.io.Serializable

data class CurrentReaderSettings(
    var direction: Directions = Directions[PrefManager.getVal(PrefName.Direction)]
        ?: Directions.TOP_TO_BOTTOM,
    var layout: Layouts = Layouts[PrefManager.getVal(PrefName.LayoutReader)]
        ?: Layouts.CONTINUOUS,
    var dualPageMode: DualPageModes = DualPageModes[PrefManager.getVal(PrefName.DualPageModeReader)]
        ?: DualPageModes.Automatic,
    var overScrollMode: Boolean = PrefManager.getVal(PrefName.OverScrollMode),
    var trueColors: Boolean = PrefManager.getVal(PrefName.TrueColors),
    var rotation: Boolean = PrefManager.getVal(PrefName.Rotation),
    var padding: Boolean = PrefManager.getVal(PrefName.Padding),
    var hideScrollBar: Boolean = PrefManager.getVal(PrefName.HideScrollBar),
    var hidePageNumbers: Boolean = PrefManager.getVal(PrefName.HidePageNumbers),
    var horizontalScrollBar: Boolean = PrefManager.getVal(PrefName.HorizontalScrollBar),
    var keepScreenOn: Boolean = PrefManager.getVal(PrefName.KeepScreenOn),
    var volumeButtons: Boolean = PrefManager.getVal(PrefName.VolumeButtonsReader),
    var wrapImages: Boolean = PrefManager.getVal(PrefName.WrapImages),
    var longClickImage: Boolean = PrefManager.getVal(PrefName.LongClickImage),
    var cropBorders: Boolean = PrefManager.getVal(PrefName.CropBorders),
    var cropBorderThreshold: Int = PrefManager.getVal(PrefName.CropBorderThreshold),
    var dataSaverMode: Int = PrefManager.getVal(PrefName.DataSaverMode),
    var dataSaverImageQuality: Int = PrefManager.getVal(PrefName.DataSaverImageQuality),
    var dataSaverImageFormatJpeg: Boolean = PrefManager.getVal(PrefName.DataSaverImageFormatJpeg),
    var dataSaverIgnoreJpeg: Boolean = PrefManager.getVal(PrefName.DataSaverIgnoreJpeg),
    var dataSaverIgnoreGif: Boolean = PrefManager.getVal(PrefName.DataSaverIgnoreGif),
    var dataSaverServer: String = PrefManager.getVal(PrefName.DataSaverServer),
    var dataSaverColorBW: Boolean = PrefManager.getVal(PrefName.DataSaverColorBW),
    var oneHandZoom: Boolean = PrefManager.getVal(PrefName.OneHandZoom),
    var autoScroll: Boolean = PrefManager.getVal(PrefName.AutoScroll),
    var autoScrollSpeed: Float = PrefManager.getVal(PrefName.AutoScrollSpeed),
    var backgroundColor: Int = PrefManager.getVal(PrefName.ReaderBackgroundColor),
    var defaultRotation: Int = PrefManager.getVal(PrefName.DefaultRotation),
    var continuousSidePadding: Int = PrefManager.getVal(PrefName.ContinuousSidePadding),
    var eInkFlash: Boolean = PrefManager.getVal(PrefName.EInkFlashPageChange),
    var imageQuality: ImageQuality = ImageQuality[PrefManager.getVal(PrefName.ImageQuality)]
        ?: ImageQuality.FAST,
    var webGpuRenderer: Boolean = PrefManager.getVal(PrefName.WebGpuRenderer),
    var preloadAmount: Int = PrefManager.getVal(PrefName.PagePreloadAmount),
    var alwaysShowChapterTransition: Boolean = PrefManager.getVal(PrefName.AlwaysShowChapterTransition)
) : Serializable {

    enum class Directions {
        TOP_TO_BOTTOM,
        RIGHT_TO_LEFT,
        BOTTOM_TO_TOP,
        LEFT_TO_RIGHT;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    enum class Layouts {
        PAGED,
        CONTINUOUS_PAGED,
        CONTINUOUS;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    enum class DualPageModes {
        No, Automatic, Force;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    enum class ImageQuality {
        /** Android bilinear (hardware-accelerated, fastest). */
        FAST,
        /** Two-pass box filter — noticeably sharper on large downscales. */
        BALANCED,
        /** Lanczos-3 sinc-windowed resampling — highest quality, best for line art. */
        LANCZOS;

        companion object {
            operator fun get(value: Int) = values().firstOrNull { it.ordinal == value }
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun readResolve(): Any {
        if (imageQuality == null) imageQuality = ImageQuality.FAST
        if (direction == null) direction = Directions.TOP_TO_BOTTOM
        if (layout == null) layout = Layouts.CONTINUOUS
        if (dualPageMode == null) dualPageMode = DualPageModes.Automatic
        return this
    }

    companion object {
        private const val serialVersionUID: Long = 2L

        fun applyWebtoon(settings: CurrentReaderSettings) {
            settings.apply {
                layout = Layouts.CONTINUOUS
                direction = Directions.TOP_TO_BOTTOM
                dualPageMode = DualPageModes.No
                padding = false
            }
        }
    }
}
