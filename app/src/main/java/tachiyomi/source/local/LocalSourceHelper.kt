package tachiyomi.source.local

import com.hippo.unifile.UniFile

object LocalSourceHelper {

    fun resolveTargetDir(baseDir: UniFile, subfolder: String): UniFile {
        val localDir = baseDir.findFile("local") ?: baseDir
        return localDir.findFile(subfolder) ?: localDir
    }

    fun filterEntries(
        targetDir: UniFile,
        lastModifiedLimit: Long,
        query: String
    ): List<UniFile> {
        return targetDir.listFiles()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }
    }
}
