package ani.dantotsu.util

object SizeFormatter {
    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.1f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            bytes >= kb -> "%.1f KB".format(bytes / kb)
            else -> "$bytes B"
        }
    }

    fun estimateTotalBytesByPercent(downloadedBytes: Long, percent: Int): Long {
        if (downloadedBytes <= 0L || percent <= 0) return -1L
        return downloadedBytes * 100L / percent
    }

    fun estimateTotalBytesByFraction(downloadedBytes: Long, completedParts: Int, totalParts: Int): Long {
        if (downloadedBytes <= 0L || completedParts <= 0 || totalParts <= 0) return -1L
        return downloadedBytes * totalParts.toLong() / completedParts.toLong()
    }
}
