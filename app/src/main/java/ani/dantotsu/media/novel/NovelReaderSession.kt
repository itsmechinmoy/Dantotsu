package ani.dantotsu.media.novel

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.novel.LnReaderNovelParser

object NovelReaderSession {
    var chapters: List<FileUrl> = emptyList()
    var currentIndex: Int = 0
    var parser: LnReaderNovelParser? = null

    fun isActive(): Boolean = chapters.isNotEmpty() && parser != null

    fun clear() {
        chapters = emptyList()
        currentIndex = 0
        parser = null
    }

    fun hasNext(): Boolean = currentIndex < chapters.size - 1
    fun hasPrev(): Boolean = currentIndex > 0

    fun currentChapter(): FileUrl? = chapters.getOrNull(currentIndex)
    fun nextChapter(): FileUrl? = if (hasNext()) chapters.getOrNull(++currentIndex) else null
    fun prevChapter(): FileUrl? = if (hasPrev()) chapters.getOrNull(--currentIndex) else null
}
