package ani.dantotsu.download.novel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ani.dantotsu.media.novel.novelreader.NovelReaderActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object HtmlToEpubUtils {

    fun streamToReader(context: Context, title: String, html: String): Intent {
        val epubBytes = createEpub(title, html)
        val cacheDir = context.cacheDir
        val staleFiles = cacheDir.listFiles { _, name -> name.startsWith("stream_novel_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        staleFiles.forEachIndexed { index, file ->
            if (index >= 5 || System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000) {
                file.delete()
            }
        }
        val cacheFile = File(cacheDir, "stream_novel_${System.currentTimeMillis()}.epub")
        cacheFile.writeBytes(epubBytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", cacheFile)
        return Intent(context, NovelReaderActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/epub+zip")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    fun createEpub(title: String, htmlContent: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        
        // uncompress
        val mimeType = "application/epub+zip".toByteArray(Charsets.UTF_8)
        val mimeEntry = ZipEntry("mimetype")
        mimeEntry.method = ZipEntry.STORED
        mimeEntry.size = mimeType.size.toLong()
        mimeEntry.compressedSize = mimeType.size.toLong()
        val crc = CRC32()
        crc.update(mimeType)
        mimeEntry.crc = crc.value
        zos.putNextEntry(mimeEntry)
        zos.write(mimeType)
        zos.closeEntry()

        
        val containerXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry("META-INF/container.xml"))
        zos.write(containerXml)
        zos.closeEntry()

       
        val cleanTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$cleanTitle</dc:title>
                    <dc:creator>Unknown</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:identifier id="BookId">urn:uuid:12345</dc:identifier>
                    <dc:description>$cleanTitle</dc:description>
                    <dc:publisher>Dantotsu</dc:publisher>
                    <dc:date>2024-01-01</dc:date>
                    <dc:rights>All rights reserved</dc:rights>
                </metadata>
                <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="chapter1" href="chapter1.html" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx">
                    <itemref idref="chapter1"/>
                </spine>
            </package>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zos.write(opf)
        zos.closeEntry()

        
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <head>
                    <meta name="dtb:uid" content="urn:uuid:12345"/>
                    <meta name="dtb:depth" content="1"/>
                    <meta name="dtb:totalPageCount" content="0"/>
                    <meta name="dtb:maxPageNumber" content="0"/>
                </head>
                <docTitle><text>$cleanTitle</text></docTitle>
                <navMap>
                    <navPoint id="navPoint-1" playOrder="1">
                        <navLabel><text>$cleanTitle</text></navLabel>
                        <content src="chapter1.html"/>
                    </navPoint>
                </navMap>
            </ncx>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
        zos.write(ncx)
        zos.closeEntry()

      
        val htmlWrapper = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <title>$cleanTitle</title>
                <meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
            </head>
            <body>
                <h2>$cleanTitle</h2>
                $htmlContent
            </body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry("OEBPS/chapter1.html"))
        zos.write(htmlWrapper)
        zos.closeEntry()

        zos.close()
        return baos.toByteArray()
    }
}
