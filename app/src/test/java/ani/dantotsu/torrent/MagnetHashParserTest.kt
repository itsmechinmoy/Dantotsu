package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MagnetHashParserTest {

    @Test
    fun parsesLowercaseHexHash() {
        val url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Episode+01"
        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF01234567",
            TorrentServerManager.parseMagnetHash(url)
        )
    }

    @Test
    fun parsesUppercaseAndMixedCaseHexHash() {
        val upper = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567"
        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF01234567",
            TorrentServerManager.parseMagnetHash(upper)
        )

        val mixed = "magnet:?XT=URN:BTIH:aAbBcCdDeEfF00112233445566778899aAbBcCdD&tr=udp://example:80"
        assertEquals(
            "AABBCCDDEEFF00112233445566778899AABBCCDD",
            TorrentServerManager.parseMagnetHash(mixed)
        )
    }

    @Test
    fun parsesUppercaseAndLowercaseBase32Hash() {
        val expectedHex = "0123456789ABCDEFFEDC0123456789ABCDEFFEDC"
        val upperBase32 = "magnet:?xt=urn:btih:AERUKZ4JVPG677W4AERUKZ4JVPG677W4&dn=Episode"
        val lowerBase32 = "magnet:?xt=urn:btih:aerukz4jvpg677w4aerukz4jvpg677w4&dn=Episode"

        assertEquals(expectedHex, TorrentServerManager.parseMagnetHash(upperBase32))
        assertEquals(expectedHex, TorrentServerManager.parseMagnetHash(lowerBase32))
    }

    @Test
    fun parsesUrlEncodedXtValues() {
        val encodedHex = "magnet:?xt=urn%3Abtih%3A0123456789abcdef0123456789abcdef01234567&dn=Show"
        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF01234567",
            TorrentServerManager.parseMagnetHash(encodedHex)
        )

        val encodedBase32 = "magnet:?xt=urn%3Abtih%3Aaerukz4jvpg677w4aerukz4jvpg677w4&dn=Show"
        assertEquals(
            "0123456789ABCDEFFEDC0123456789ABCDEFFEDC",
            TorrentServerManager.parseMagnetHash(encodedBase32)
        )

        val withMalformedPercentElsewhere =
            "magnet:?dn=100%Ep&xt=urn%3Abtih%3A0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF01234567",
            TorrentServerManager.parseMagnetHash(withMalformedPercentElsewhere)
        )
    }

    @Test
    fun throwsWhenHashIsMissingOrInvalid() {
        assertThrows(IllegalArgumentException::class.java) {
            TorrentServerManager.parseMagnetHash("magnet:?dn=NoHashHere")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentServerManager.parseMagnetHash("magnet:?xt=urn:btih:&dn=Empty")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentServerManager.parseMagnetHash("magnet:?xt=urn:btih:12345")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentServerManager.parseMagnetHash("not-a-magnet")
        }
    }

    @Test
    fun parsesRelayLinkAndFileIndex() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val withFilename = TorrentRelayLink.parse("http://127.0.0.1:8090/stream/video.mkv?hash=$hash&index=3")
        assertEquals(TorrentRelayLink(hash.uppercase(), 3), withFilename)

        val bareStream = TorrentRelayLink.parse("http://127.0.0.1:8091/stream?hash=$hash&index=0")
        assertEquals(TorrentRelayLink(hash.uppercase(), 0), bareStream)

        assertNull(TorrentRelayLink.parse("https://example.com/stream?hash=$hash&index=0"))

        assertEquals(
            4,
            TorrentLinks.fileIndex("magnet:?xt=urn:btih:$hash&index=4&tr=udp://example:80")
        )
        assertEquals(0, TorrentLinks.fileIndex("magnet:?xt=urn:btih:$hash"))
    }
}
