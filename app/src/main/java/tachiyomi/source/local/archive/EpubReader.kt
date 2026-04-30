package tachiyomi.source.local.archive

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.Closeable
import java.io.File
import java.io.InputStream


class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    // path seperator
    private val pathSeparator = getPathSeparator()

    
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    // path for allimages
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    
  
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", org.jsoup.parser.Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

   
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", org.jsoup.parser.Parser.xmlParser()) }
    }

   
    private fun getPagesFromDocument(document: Document): List<Pair<String, String>> {
        val pages = document.select("manifest > item")
            .associateBy { it.attr("id") }

        val spine = document.select("spine > itemref").map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") to it.attr("media-type") }
    }

  
    private fun getImagesFromPages(pages: List<Pair<String, String>>, packageHref: String): List<String> {
        val result = mutableListOf<String>()
        val basePath = getParentDirectory(packageHref)
        pages.forEach { (page, mediaType) ->
            val entryPath = resolveZipPath(basePath, page)
            if (mediaType.startsWith("image/")) {
                // Direct images
                result.add(entryPath)
            } else {
                // HTML/XHTML page, extract images
                getInputStream(entryPath)?.use { stream ->
                    val document = Jsoup.parse(stream, null, "")
                    val imageBasePath = getParentDirectory(entryPath)
                    document.allElements.forEach {
                        when (it.tagName()) {
                            "img" -> result.add(resolveZipPath(imageBasePath, it.attr("src")))
                            "image" -> result.add(resolveZipPath(imageBasePath, it.attr("xlink:href")))
                        }
                    }
                }
            }
        }

        return result.distinct()
    }

    // Returns path separator
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    // zip path resolve
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, java.io.File.separator)
        if (!fixedBasePath.startsWith(java.io.File.separator)) {
            fixedBasePath = "${java.io.File.separator}$fixedBasePath"
        }

        val decodedRelativePath = java.net.URLDecoder.decode(relativePath, "UTF-8")
        val fixedRelativePath = decodedRelativePath.replace(pathSeparator, java.io.File.separator)
        val resolvedPath = java.io.File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(java.io.File.separator, pathSeparator).substring(1)
    }

    // Gets the parent dir of a path
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }
}

fun DocumentFile.epubReader(context: Context) = EpubReader(archiveReader(context))
