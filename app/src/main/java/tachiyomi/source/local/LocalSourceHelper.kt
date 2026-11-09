package tachiyomi.source.local

import androidx.documentfile.provider.DocumentFile

object LocalSourceHelper {

    fun getFilteredDirectories(
        baseDir: DocumentFile?,
        subfolder: String,
        isLatest: Boolean,
        threshold: Long,
        query: String
    ): List<DocumentFile>? {
        if (baseDir == null) return null
        val limit = if (isLatest) System.currentTimeMillis() - threshold else 0L
        val dir = resolveTargetDir(baseDir, subfolder)
        return filterEntries(dir, limit, query)
    }

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
