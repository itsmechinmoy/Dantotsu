package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseFamilyTest {

    private fun title(release: String, seeders: Int, tracker: String = "Tracker") =
        "$release\n👤 $seeders 💾 1.37 GB ⚙️ $tracker"

    private val ep1Erai = title("[Erai-raws] Kusuriya no Hitorigoto 3rd Season - 01v2 [1080p CR WEB-DL AVC AAC][MultiSub]", 171)

    private val ep2 = listOf(
        title("[SubsPlease] Kusuriya no Hitorigoto - 50 (1080p) [ABCDEF12].mkv", 488),
        title("[Erai-raws] Kusuriya no Hitorigoto 3rd Season - 02 [720p CR WEB-DL AVC AAC][MultiSub]", 90),
        title("[Erai-raws] Kusuriya no Hitorigoto 3rd Season - 02 [1080p CR WEB-DL AVC AAC][MultiSub]", 150),
    )

    @Test
    fun `next episode picks the same group at the same resolution`() {
        assertEquals(ep2[2], ReleaseFamily.pick(ep1Erai, ep2) { it })
    }

    @Test
    fun `same group at another resolution when the remembered one is missing`() {
        assertEquals(ep2[1], ReleaseFamily.pick(ep1Erai, ep2.take(2)) { it })
    }

    @Test
    fun `no release from the group means no pick`() {
        assertNull(ReleaseFamily.pick(ep1Erai, ep2.take(1)) { it })
    }

    @Test
    fun `plain stream servers have no family`() {
        assertNull(ReleaseFamily.of("HD-1"))
        assertNull(ReleaseFamily.pick("HD-1", listOf("HD-1", "HD-2")) { it })
    }

    @Test
    fun `dialog label is group and resolution`() {
        assertEquals("Erai-raws · 1080p", ReleaseFamily.label(ep1Erai))
    }
}
