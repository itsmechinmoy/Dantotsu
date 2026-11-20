package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit evidence for v3.3 finding 7 (fast-scroll regression).
 *
 * Proves that a pure recycled bind of an unchanged source row does
 * not rerun classification/parsing: the builder runs exactly once
 * per distinct content key, hits never rebuild, and the cache is
 * bounded and explicitly invalidatable.
 */
class SourceRowViewModelCacheTest {

    private fun modelFor(tag: String): SourceRowViewModel =
        SourceRowViewModel(
            displayLabel = tag,
            presentation = SourcePresentationKind.RELEASE,
            transport = SourceTransport.TORRENT,
            header = tag,
            cleanSubtitle = tag,
            subtitle = tag,
            pills = emptyList(),
            criticalPills = emptyList(),
            optionalPills = emptyList(),
            audioSummary = null,
            subtitleSummary = null,
            meta = emptyList(),
            details = emptyList(),
        )

    @Test
    fun recycledBindOfUnchangedRowDoesNotRebuild() {
        val cache = SourceRowViewModelCache()
        var builds = 0
        val key = SourceRowViewModelCache.rowKey("IndexA", "CONTAINER", "magnet:?xt=urn:btih:AAA&dn=x")
        val first = cache.getOrBuild(key) { builds++; modelFor("row") }
        // Simulate 50 recycled binds of the same unchanged row.
        repeat(50) {
            val hit = cache.getOrBuild(key) { builds++; modelFor("row") }
            assertSame("hit must be the identical model instance", first, hit)
        }
        assertEquals("builder runs exactly once per distinct key", 1, builds)
    }

    @Test
    fun changedContentRebuilds() {
        val cache = SourceRowViewModelCache()
        var builds = 0
        val a = cache.getOrBuild(SourceRowViewModelCache.rowKey("S", "CONTAINER", "url-a")) {
            builds++; modelFor("a")
        }
        val b = cache.getOrBuild(SourceRowViewModelCache.rowKey("S", "CONTAINER", "url-b")) {
            builds++; modelFor("b")
        }
        assertEquals(2, builds)
        assertEquals("a", a.header)
        assertEquals("b", b.header)
    }

    @Test
    fun rowKeyCoversEveryBuildInput() {
        // Any single input change must produce a different key, so a
        // hit can never display another row's model (stale-proof).
        val base = SourceRowViewModelCache.rowKey("S", "CONTAINER", "url")
        assertTrue(base != SourceRowViewModelCache.rowKey("T", "CONTAINER", "url"))
        assertTrue(base != SourceRowViewModelCache.rowKey("S", "M3U8", "url"))
        assertTrue(base != SourceRowViewModelCache.rowKey("S", null, "url"))
        assertTrue(base != SourceRowViewModelCache.rowKey("S", "CONTAINER", "url2"))
    }

    @Test
    fun cacheIsBounded() {
        val cache = SourceRowViewModelCache(maxEntries = 4)
        repeat(10) { i ->
            cache.getOrBuild("key-$i") { modelFor("row-$i") }
        }
        assertTrue("LRU cap respected, size=${cache.size()}", cache.size() <= 4)
    }

    @Test
    fun clearInvalidatesEverything() {
        val cache = SourceRowViewModelCache()
        var builds = 0
        val key = SourceRowViewModelCache.rowKey("S", "CONTAINER", "url")
        cache.getOrBuild(key) { builds++; modelFor("row") }
        cache.clear()
        cache.getOrBuild(key) { builds++; modelFor("row") }
        assertEquals(2, builds)
    }

    @Test
    fun endToEndSummarizerModelsCacheByContent() {
        // Full purity proof on sample row content: identical inputs
        // rebuild zero times through the cache, and equal models.
        val cache = SourceRowViewModelCache()
        var builds = 0
        val raw = "Relayaddon\n📂 IndexA\n1080p [Grpsali] Show S1 - BD (1080p) (HDR) [Dual Audio]"
        val key = SourceRowViewModelCache.rowKey(raw, "CONTAINER", "magnet:?xt=urn:btih:AAA&dn=x")
        fun build() = SourceRowSummarizer.build(raw, raw, "CONTAINER", "magnet:?xt=urn:btih:AAA&dn=x")
        val first = cache.getOrBuild(key) { builds++; build() }
        repeat(10) {
            assertEquals(first, cache.getOrBuild(key) { builds++; build() })
        }
        assertEquals(1, builds)
    }
}
