package tachiyomi.source.local

import androidx.documentfile.provider.DocumentFile

object LocalSourceHelper {

    fun resolveTargetDir(baseDir: DocumentFile?, subfolder: String): DocumentFile? {
        if (baseDir == null) return null
        val localDir = baseDir.findFile("local") ?: baseDir
        return localDir.findFile(subfolder) ?: localDir
    }

    fun filterEntries(
        targetDir: DocumentFile?,
        lastModifiedLimit: Long,
        query: String
    ): List<DocumentFile> {
        if (targetDir == null) return emptyList()
        val files = targetDir.listFiles() ?: return emptyList()
        return files
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
