package ani.dantotsu.util

object VersionUtil {
    fun isOutdated(latestTag: String, currentVersion: String): Boolean {
        val latest = latestTag.removePrefix("v").trim()
        val current = currentVersion.removePrefix("v").trim()
        if (latest == current) return false

        val latestBase = latest.substringBefore("+").substringBefore("-").trim()
        val currentBase = current.substringBefore("+").substringBefore("-").trim()

        val lParts = latestBase.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = currentBase.split(".").mapNotNull { it.toIntOrNull() }

        if (lParts.isNotEmpty() && cParts.isNotEmpty()) {
            val maxLen = maxOf(lParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        }

        val latestHash = when {
            "+" in latest -> latest.substringAfter("+").substringBefore("-").trim()
            lParts.isEmpty() && latest.isNotBlank() -> latest.substringBefore("-").trim()
            else -> ""
        }
        val currentHash = when {
            "+" in current -> current.substringAfter("+").substringBefore("-").trim()
            cParts.isEmpty() && current.isNotBlank() -> current.substringBefore("-").trim()
            else -> ""
        }

        if (latestHash.isNotEmpty() && currentHash.isNotEmpty()) {
            return !(latestHash.startsWith(currentHash) || currentHash.startsWith(latestHash))
        }

        return latestHash.isNotEmpty() && currentHash.isEmpty()
    }
}
