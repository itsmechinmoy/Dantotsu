package ani.dantotsu.parsers

import ani.dantotsu.parsers.TorrentAnimeParser.Companion.episodeNumberFromFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentEpisodeNumberTest {

    private fun ep(name: String) = episodeNumberFromFileName(name)

    @Test
    fun bracketedHashIsNotTheEpisode() {
        assertEquals(5f, ep("[Group] Show Name - 05 (1080p) [486689AB].mkv"))
        assertEquals(5f, ep("[Group] Show Name - 05 [486689].mkv"))
        assertEquals(12f, ep("[Group] Show Name - 12v2 [1080p HEVC][A1B2C3D4].mkv"))
    }

    @Test
    fun seasonEpisodeForms() {
        assertEquals(7f, ep("Show.Name.S02E07.1080p.WEB-DL.x264-GRP.mkv"))
        assertEquals(7f, ep("Show Name S2 E07 1080p.mkv"))
        assertEquals(3f, ep("Show Name 1x03.mkv"))
        assertEquals(11f, ep("Show Name Episode 11 [720p].mp4"))
        assertEquals(4f, ep("Show_Name_EP04_BD_1080p.mkv"))
    }

    @Test
    fun numbersInTitlesAndNoise() {
        assertEquals(1f, ep("Mob Psycho 100 01 [BD 1080p x265 10bit FLAC].mkv"))
        assertEquals(6f, ep("86 - Eighty Six - 06.mkv"))
        assertEquals(13.5f, ep("[Group] Show - 13.5 [1080p].mkv"))
        assertEquals(2f, ep("Show Name (2019) 02 1080p.mkv"))
    }

    @Test
    fun nothingToFind() {
        assertNull(ep("[Group] Show Name [1080p][486689AB].mkv"))
        assertNull(ep("Movie.Name.2019.1080p.BluRay.x264.mkv"))
    }
}
