package exh.util

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

interface DataSaver {
    fun compress(imageUrl: String): String

    companion object {
        val NoOp = object : DataSaver {
            override fun compress(imageUrl: String): String = imageUrl
        }
    }
}

fun createDataSaver(): DataSaver {
    val dataSaverMode = PrefManager.getVal<Int>(PrefName.DataSaverMode)
    return when (dataSaverMode) {
        0 -> DataSaver.NoOp  // NONE
        1 -> BandwidthHeroDataSaver()  // BANDWIDTH_HERO
        2 -> WsrvNlDataSaver()  // WSRV_NL
        else -> DataSaver.NoOp
    }
}

private class BandwidthHeroDataSaver : DataSaver {
    private val dataSavedServer = PrefManager.getVal<String>(PrefName.DataSaverServer).trimEnd('/')
    private val ignoreJpg = PrefManager.getVal<Boolean>(PrefName.DataSaverIgnoreJpeg)
    private val ignoreGif = PrefManager.getVal<Boolean>(PrefName.DataSaverIgnoreGif)
    private val format = if (PrefManager.getVal<Boolean>(PrefName.DataSaverImageFormatJpeg)) "1" else "0"
    private val quality = PrefManager.getVal<Int>(PrefName.DataSaverImageQuality)
    private val colorBW = if (PrefManager.getVal<Boolean>(PrefName.DataSaverColorBW)) "1" else "0"

    override fun compress(imageUrl: String): String {
        return if (dataSavedServer.isNotBlank() && !imageUrl.contains(dataSavedServer)) {
            when {
                imageUrl.contains(".jpeg", true) || imageUrl.contains(".jpg", true) -> 
                    if (ignoreJpg) imageUrl else getUrl(imageUrl)
                imageUrl.contains(".gif", true) -> 
                    if (ignoreGif) imageUrl else getUrl(imageUrl)
                else -> getUrl(imageUrl)
            }
        } else {
            imageUrl
        }
    }

    private fun getUrl(imageUrl: String): String {
        return "$dataSavedServer/?jpg=$format&l=$quality&bw=$colorBW&url=$imageUrl"
    }
}

private class WsrvNlDataSaver : DataSaver {
    private val ignoreJpg = PrefManager.getVal<Boolean>(PrefName.DataSaverIgnoreJpeg)
    private val ignoreGif = PrefManager.getVal<Boolean>(PrefName.DataSaverIgnoreGif)
    private val format = PrefManager.getVal<Boolean>(PrefName.DataSaverImageFormatJpeg)
    private val quality = PrefManager.getVal<Int>(PrefName.DataSaverImageQuality)

    override fun compress(imageUrl: String): String {
        return when {
            imageUrl.contains(".jpeg", true) || imageUrl.contains(".jpg", true) -> 
                if (ignoreJpg) imageUrl else getUrl(imageUrl)
            imageUrl.contains(".gif", true) -> 
                if (ignoreGif) imageUrl else getUrl(imageUrl)
            else -> getUrl(imageUrl)
        }
    }

    private fun getUrl(imageUrl: String): String {
        return "https://wsrv.nl/?url=$imageUrl" +
            if (imageUrl.contains(".webp", true) || imageUrl.contains(".gif", true)) {
                if (!format) {
                    // Preserve output image extension for animated images(.webp and .gif)
                    "&q=$quality&n=-1"
                } else {
                    // Do not preserve output Extension if User asked to convert into Jpeg
                    "&output=jpg&q=$quality&n=-1"
                }
            } else {
                if (format) {
                    "&output=jpg&q=$quality"
                } else {
                    "&output=webp&q=$quality"
                }
            }
    }
}
