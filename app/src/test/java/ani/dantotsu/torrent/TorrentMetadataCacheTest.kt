package ani.dantotsu.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorrentMetadataCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hash = "6E901AC2055019A3905E80F44383C7F39443AC15"

    @Test
    fun savesAndLoadsByHashCaseInsensitively() {
        val dir = tmp.newFolder("meta")
        val cache = TorrentMetadataCache(dir)
        val bytes = "d4:infod4:name1:aee".toByteArray()

        assertTrue(cache.save(hash.lowercase(), bytes))
        assertTrue(cache.contains(hash))
        assertArrayEquals(bytes, cache.load(hash))
        assertFalse(File(dir, "$hash.torrent.tmp").exists())
    }

    @Test
    fun rejectsBadHashesAndInvalidData() {
        val cache = TorrentMetadataCache(tmp.newFolder("meta"))
        val valid = "d4:infod4:name1:aee".toByteArray()

        assertFalse(cache.save("../../etc", valid))
        assertFalse(cache.save("nothex", valid))
        assertFalse(cache.save(hash, null))
        assertFalse(cache.save(hash, ByteArray(0)))
        assertFalse(cache.save(hash, "not-a-dict".toByteArray()))
        assertNull(cache.load("nothex"))
        assertNull(cache.load(hash))
    }

    @Test
    fun evictsOldestEntriesPastCapAndRefreshesOnLoad() {
        val dir = tmp.newFolder("meta")
        val cache = TorrentMetadataCache(dir, maxEntries = 2)
        val a = "A".repeat(40)
        val b = "B".repeat(40)
        val c = "C".repeat(40)
        val d = "D".repeat(40)

        assertTrue(cache.save(a, "d1:xi1ee".toByteArray()))
        File(dir, "$a.torrent").setLastModified(1_000_000L)
        assertTrue(cache.save(b, "d1:xi2ee".toByteArray()))
        File(dir, "$b.torrent").setLastModified(2_000_000L)

        // Third entry evicts oldest (a).
        assertTrue(cache.save(c, "d1:xi3ee".toByteArray()))
        assertFalse(File(dir, "$a.torrent").exists())
        assertTrue(File(dir, "$b.torrent").exists())
        assertTrue(File(dir, "$c.torrent").exists())

        // Touching b via load refreshes its timestamp, so adding d evicts c instead.
        File(dir, "$b.torrent").setLastModified(2_000_000L)
        File(dir, "$c.torrent").setLastModified(3_000_000L)
        assertArrayEquals("d1:xi2ee".toByteArray(), cache.load(b))
        assertTrue(cache.save(d, "d1:xi4ee".toByteArray()))
        assertTrue(File(dir, "$b.torrent").exists())
        assertFalse(File(dir, "$c.torrent").exists())
        assertTrue(File(dir, "$d.torrent").exists())
    }

    @Test
    fun deletesAndIgnoresCorruptEntries() {
        val dir = tmp.newFolder("meta")
        val cache = TorrentMetadataCache(dir)
        val file = File(dir, "$hash.torrent")

        // Empty file is deleted and ignored.
        file.writeBytes(ByteArray(0))
        assertNull(cache.load(hash))
        assertFalse(file.exists())

        // Non-bencoded garbage is deleted and ignored.
        file.writeBytes("corrupt-bytes".toByteArray())
        assertNull(cache.load(hash))
        assertFalse(file.exists())

        // Parse exception inside loadParsed deletes the file and notifies callback.
        assertTrue(cache.save(hash, "d4:junk1:xe".toByteArray()))
        var sawError = false
        val failed = cache.loadParsed(hash, onCorrupt = { sawError = true }) {
            throw IllegalArgumentException("bad torrent")
        }
        assertNull(failed)
        assertTrue(sawError)
        assertFalse(file.exists())

        // Null parse result (e.g. hash mismatch) also deletes the file.
        assertTrue(cache.save(hash, "d4:infod4:name1:aee".toByteArray()))
        val mismatched = cache.loadParsed<String>(hash) { null }
        assertNull(mismatched)
        assertFalse(file.exists())
    }

    @Test
    fun wrapsInfoSectionAndExtractsMagnetTrackers() {
        val info = "d6:lengthi5e4:name1:ae".toByteArray()
        assertEquals(
            "d4:infod6:lengthi5e4:name1:aee",
            String(TorrentMetadataCache.wrapInfoSection(info)!!)
        )
        assertNull(TorrentMetadataCache.wrapInfoSection(ByteArray(0)))
        assertNull(TorrentMetadataCache.wrapInfoSection("i5e".toByteArray()))

        val magnet =
            "magnet:?xt=urn:btih:$hash&dn=Test" +
                "&tr=http%3A%2F%2Ftracker.example.org%3A6969%2Fannounce" +
                "&tr=udp%3A%2F%2Fa.example%3A80%2Fannounce" +
                "&tr=udp%3A%2F%2Fa.example%3A80%2Fannounce"
        assertEquals(
            listOf(
                "http://tracker.example.org:6969/announce",
                "udp://a.example:80/announce"
            ),
            TorrentMetadataCache.magnetTrackers(magnet)
        )
        assertTrue(TorrentMetadataCache.magnetTrackers("https://example.org/file.torrent").isEmpty())
    }
}
