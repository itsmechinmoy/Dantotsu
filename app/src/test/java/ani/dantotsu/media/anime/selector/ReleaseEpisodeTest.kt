package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseEpisodeTest {
    @Test
    fun readsPlainEpisodeNames() {
        assertEquals(2, ReleaseEpisode.number("[ToonsHub] The Apothecary Diaries S03E02 1080p NF WEB-DL AAC2.0 H.264 (Multi-Subs)"))
        assertEquals(14, ReleaseEpisode.number("[SubsPlease] Show - 14 (1080p) [ABCD1234].mkv"))
        assertEquals(3, ReleaseEpisode.number("[Group] Show - 03v2 [1080p]"))
    }

    @Test
    fun batchesAndUnclearNamesSayNothing() {
        assertNull(ReleaseEpisode.number("[Group] Show S03 1080p Batch"))
        assertNull(ReleaseEpisode.number("[Group] Show - 01-12 [1080p]"))
        assertNull(ReleaseEpisode.number("Show S01E01-E12 1080p"))
        assertNull(ReleaseEpisode.number("Server 1"))
    }

    @Test
    fun rejectsOnlyAnotherEpisode() {
        val ep2 = "[ToonsHub] The Apothecary Diaries S03E02 1080p NF WEB-DL"
        assertFalse(ReleaseEpisode.fits(ep2, "3"))
        assertTrue(ReleaseEpisode.fits(ep2, "2"))
        assertTrue(ReleaseEpisode.fits("[Group] Show S03 Batch", "3"))
        assertTrue(ReleaseEpisode.fits(ep2, null))
    }
}
