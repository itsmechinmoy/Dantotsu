package ani.dantotsu.parsers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTypeDetectorTest {

    private val hash = "3d2f718f3d2f718f3d2f718f3d2f718f3d2f718f"

    @Test
    fun relayLinkOnAnyPort() {
        assertTrue(StreamTypeDetector.isTorrentStream("127.0.0.1", 8091, "/stream/Show%20-%2001.mkv", "hash=$hash&index=0"))
        assertTrue(StreamTypeDetector.isTorrentStream("127.0.0.1", 41234, "/stream/x", "index=3&hash=$hash"))
    }

    @Test
    fun otherLocalhostStreamsStayNonTorrent() {
        assertFalse(StreamTypeDetector.isTorrentStream("127.0.0.1", 8091, "/proxy/master.m3u8", "url=abc"))
        assertFalse(StreamTypeDetector.isTorrentStream("127.0.0.1", 8091, "/stream", "hash=short"))
        assertFalse(StreamTypeDetector.isTorrentStream("example.com", 443, "/stream/x", "hash=$hash"))
    }
}
