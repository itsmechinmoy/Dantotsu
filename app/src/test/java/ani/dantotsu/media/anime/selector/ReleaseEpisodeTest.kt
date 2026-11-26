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
        assertEquals(3, ReleaseEpisode.number("[Group] Show S03E03v2 [1080p]"))
    }

    @Test
    fun batchesAndUnclearNamesSayNothing() {
        assertNull(ReleaseEpisode.number("[Group] Show S03 1080p Batch"))
        assertNull(ReleaseEpisode.number("[Group] Show - 01-12 [1080p]"))
        assertNull(ReleaseEpisode.number("Show S01E01-E12 1080p"))
        assertNull(ReleaseEpisode.number("Server 1"))
        // absolute numbering: S3 episode 3 of a show with 48 earlier episodes
        assertNull(ReleaseEpisode.number("[ASW] The Apothecary Diaries - 51 [1080p HEVC x265 10Bit][AAC]"))
    }

    @Test
    fun rejectsOnlyAnotherEpisode() {
        val ep2 = "[ToonsHub] The Apothecary Diaries S03E02 1080p NF WEB-DL"
        assertFalse(ReleaseEpisode.fits(ep2, "3"))
        assertTrue(ReleaseEpisode.fits(ep2, "2"))
        assertTrue(ReleaseEpisode.fits("[Group] Show S03 Batch", "3"))
        assertTrue(ReleaseEpisode.fits("[ASW] The Apothecary Diaries - 51 [1080p]", "3"))
        assertTrue(ReleaseEpisode.fits(ep2, null))
    }
}
