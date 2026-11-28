package ani.dantotsu.media.manga.mangareader

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View

object MangaColorFilterManager {

    const val MODE_DEFAULT = 0
    const val MODE_GRAYSCALE = 1
    const val MODE_INVERTED = 2
    const val MODE_SEPIA = 3
    const val MODE_CUSTOM = 4

    fun applyColorFilter(
        view: View,
        colorFilterMode: Int,
        brightness: Float = 0f,
        contrast: Float = 0f,
        saturation: Float = 1f,
        temperature: Float = 0f
    ) {
        if (colorFilterMode == MODE_DEFAULT &&
            brightness == 0f &&
            contrast == 0f &&
            (saturation == 1f || saturation == 0f) &&
            temperature == 0f
        ) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }

        val cm = ColorMatrix()

        when (colorFilterMode) {
            MODE_GRAYSCALE -> {
                cm.setSaturation(0f)
            }
            MODE_INVERTED -> {
                cm.set(
                    floatArrayOf(
                        -1f,  0f,  0f, 0f, 255f,
                         0f, -1f,  0f, 0f, 255f,
                         0f,  0f, -1f, 0f, 255f,
                         0f,  0f,  0f, 1f,   0f
                    )
                )
            }
            MODE_SEPIA -> {
                val sepiaMatrix = ColorMatrix(
                    floatArrayOf(
                        0.393f, 0.769f, 0.189f, 0f, 0f,
                        0.349f, 0.686f, 0.168f, 0f, 0f,
                        0.272f, 0.534f, 0.131f, 0f, 0f,
                        0f,     0f,     0f,     1f, 0f
                    )
                )
                cm.set(sepiaMatrix)
            }
            MODE_CUSTOM -> {
                if (saturation != 1f && saturation > 0f) {
                    val satMatrix = ColorMatrix()
                    satMatrix.setSaturation(saturation.coerceIn(0f, 2f))
                    cm.postConcat(satMatrix)
                }

                if (contrast != 0f) {
                    val scale = 1f + contrast.coerceIn(-0.9f, 2f)
                    val translate = (-0.5f * scale + 0.5f) * 255f
                    cm.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                scale, 0f, 0f, 0f, translate,
                                0f, scale, 0f, 0f, translate,
                                0f, 0f, scale, 0f, translate,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }

                if (brightness != 0f) {
                    val bOffset = brightness.coerceIn(-1f, 1f) * 100f
                    cm.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                1f, 0f, 0f, 0f, bOffset,
                                0f, 1f, 0f, 0f, bOffset,
                                0f, 0f, 1f, 0f, bOffset,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }

                if (temperature != 0f) {
                    val tempOffset = temperature.coerceIn(-1f, 1f) * 35f
                    cm.postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                1f, 0f, 0f, 0f, tempOffset,
                                0f, 1f, 0f, 0f, tempOffset * 0.4f,
                                0f, 0f, 1f, 0f, -tempOffset,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            }
        }

        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(cm)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }
}
