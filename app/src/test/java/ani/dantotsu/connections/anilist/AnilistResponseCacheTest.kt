package ani.dantotsu.connections.anilist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure JVM tests for [AnilistResponseCache]: fresh TTL, stale-while-revalidate window,
 * expiration, category and per-media invalidation, LRU eviction, and disk persistence.
 */
class AnilistResponseCacheTest {

    private var tempDir: File? = null

    private fun newTempDir(): File {
        val dir = Files.createTempDirectory("anilist_cache_test").toFile()
        tempDir = dir
        return dir
    }

    @After
    fun tearDown() {
        tempDir?.deleteRecursively()
        tempDir = null
    }

    @Test
    fun `returns fresh then stale then miss as clock advances`() {
        var now = 1_000_000L
        val cache = AnilistResponseCache(nowMs = { now })
        val spec = AnilistCacheSpec(
            category = AnilistCacheCategory.CATALOG,
            freshTtlMs = 5 * 60_000L,
            maxStaleTtlMs = 30 * 60_000L,
        )

        cache.put("anime-tab", """{"data":"v1"}""", spec)

        now += 4 * 60_000L
        val fresh = cache.get("anime-tab")
        assertTrue(fresh is CacheLookup.Fresh)
        assertEquals("""{"data":"v1"}""", (fresh as CacheLookup.Fresh).value)

        now += 6 * 60_000L
        val stale = cache.get("anime-tab")
        assertTrue(stale is CacheLookup.Stale)
        assertEquals("""{"data":"v1"}""", (stale as CacheLookup.Stale).value)

        now += 21 * 60_000L
        assertEquals(CacheLookup.Miss, cache.get("anime-tab"))
        assertEquals(0, cache.memorySize())
    }

    @Test
    fun `category invalidation drops only target category`() {
        val cache = AnilistResponseCache(nowMs = { 1_000L })
        cache.put("home-1", "home-data", AnilistCacheSpec(AnilistCacheCategory.HOME, 60_000L))
        cache.put("cat-1", "catalog-data", AnilistCacheSpec(AnilistCacheCategory.CATALOG, 60_000L))
        cache.put("fav-1", "fav-data", AnilistCacheSpec(AnilistCacheCategory.USER_FAVS, 60_000L))

        cache.invalidateCategory(AnilistCacheCategory.HOME)

        assertEquals(CacheLookup.Miss, cache.get("home-1"))
        assertTrue(cache.get("cat-1") is CacheLookup.Fresh)
        assertTrue(cache.get("fav-1") is CacheLookup.Fresh)
    }

    @Test
    fun `media invalidation drops only matching mediaId`() {
        val cache = AnilistResponseCache(nowMs = { 1_000L })
        cache.put("m-101", "show-101", AnilistCacheSpec(AnilistCacheCategory.MEDIA_DETAILS, 60_000L, mediaId = 101))
        cache.put("m-202", "show-202", AnilistCacheSpec(AnilistCacheCategory.MEDIA_DETAILS, 60_000L, mediaId = 202))

        cache.invalidateMedia(101)

        assertEquals(CacheLookup.Miss, cache.get("m-101"))
        val kept = cache.get("m-202")
        assertTrue(kept is CacheLookup.Fresh)
        assertEquals("show-202", (kept as CacheLookup.Fresh).value)
    }

    @Test
    fun `memory cache evicts least recently used entries past capacity`() {
        val cache = AnilistResponseCache(maxMemoryEntries = 2, nowMs = { 1_000L })
        val spec = AnilistCacheSpec(AnilistCacheCategory.SEARCH, 60_000L)

        cache.put("k1", "v1", spec)
        cache.put("k2", "v2", spec)
        assertTrue(cache.get("k1") is CacheLookup.Fresh)
        cache.put("k3", "v3", spec)

        assertEquals(2, cache.memorySize())
        assertTrue(cache.get("k1") is CacheLookup.Fresh)
        assertEquals(CacheLookup.Miss, cache.get("k2"))
        assertTrue(cache.get("k3") is CacheLookup.Fresh)
    }

    @Test
    fun `disk persistence survives cold restart and respects TTL and invalidation`() {
        val dir = newTempDir()
        var now = 10_000_000L
        val spec = AnilistCacheSpec(
            category = AnilistCacheCategory.CATALOG,
            freshTtlMs = 5 * 60_000L,
            maxStaleTtlMs = 30 * 60_000L,
            persistToDisk = true,
        )

        val writer = AnilistResponseCache(nowMs = { now }, diskDirProvider = { dir })
        writer.put("manga-tab", """{"data":{"trending":"ok"}}""", spec)

        val reader = AnilistResponseCache(nowMs = { now }, diskDirProvider = { dir })
        now += 2 * 60_000L
        val diskFresh = reader.get("manga-tab", AnilistCacheCategory.CATALOG, checkDisk = true)
        assertTrue(diskFresh is CacheLookup.Fresh)
        assertEquals("""{"data":{"trending":"ok"}}""", (diskFresh as CacheLookup.Fresh).value)

        val reader2 = AnilistResponseCache(nowMs = { now }, diskDirProvider = { dir })
        now += 10 * 60_000L
        val diskStale = reader2.get("manga-tab", AnilistCacheCategory.CATALOG, checkDisk = true)
        assertTrue(diskStale is CacheLookup.Stale)

        reader2.invalidateCategory(AnilistCacheCategory.CATALOG)
        val reader3 = AnilistResponseCache(nowMs = { now }, diskDirProvider = { dir })
        assertEquals(CacheLookup.Miss, reader3.get("manga-tab", AnilistCacheCategory.CATALOG, checkDisk = true))

        reader3.put("manga-tab", """{"data":"v2"}""", spec)
        reader3.clearAll()
        val reader4 = AnilistResponseCache(nowMs = { now }, diskDirProvider = { dir })
        assertEquals(CacheLookup.Miss, reader4.get("manga-tab", AnilistCacheCategory.CATALOG, checkDisk = true))
    }
}
