package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scene-style names from text-only sources (Relayaddon), 2026-11-02 phone check. */
class SceneNameTest {

    private fun group(text: String) = SourceRowClassifier.parseRelease(text).releaseGroup

    @Test
    fun scene_group_before_notes() {
        assertEquals(
            "Grpdere-Raws",
            group("The Apothecary Diaries S03E05 SUBFRENCH 1080p CR WEB-DL AAC2.0 x264-Grpdere-Raws (VOSTFR, Kusuriya no Hitorigoto 3rd Season)\n👤 378 💾 1.4 GB ⚙️ Idxone"),
        )
        assertEquals(
            "GRPVY",
            group("The Apothecary Diaries S03E01 Locusts 1080p NF WEB-DL AAC2.0 H.264-GRPVY (Kusuriya no Hitorigoto 3rd Season, Multi-Subs)\n👤 94 💾 1.4 GB ⚙️ Idxone"),
        )
        assertEquals("NQb", group("Show.S01E01.1080p.WEB.H.264-NQb.mkv\n👤 5 💾 1 GB ⚙️ Idxone"))
    }

    @Test
    fun non_groups_stay_null() {
        assertNull(group("Show S01E01 1080p WEB-DL\n👤 5 💾 1 GB ⚙️ Idxone"))
        assertNull(group("Show S01E01 1080p WEB x264-Multi-Subs\n👤 5 💾 1 GB ⚙️ Idxone"))
    }

    @Test
    fun no_comma_left_inside_brackets() {
        assertEquals("The Apothecary Diaries S03E01 (Kusuriya no Hitorigoto)", ReleaseTitleNormalizer.normalize("The Apothecary Diaries S03E01 (Kusuriya no Hitorigoto, )"))
        assertEquals("Show", ReleaseTitleNormalizer.normalize("Show (, )"))
    }
}
