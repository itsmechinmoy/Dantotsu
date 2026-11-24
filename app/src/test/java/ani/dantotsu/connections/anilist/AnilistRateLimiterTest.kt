package ani.dantotsu.connections.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM tests for [AnilistRateLimiter]: header parsing, pacing, 429 retry bounds,
 * mutation non-retry safety, external gate fast-fail, and cancellation.
 */
class AnilistRateLimiterTest {

    @Test
    fun `isGraphqlMutation detects mutations and ignores queries`() {
        assertTrue(isGraphqlMutation("mutation { SaveTextActivity(text: \"hi\") { id } }"))
        assertTrue(isGraphqlMutation("  \n# comment\n  mutation (\$id: Int) { DeleteMediaListEntry(id: \$id) { deleted } }"))
        assertTrue(isGraphqlMutation("mutation Like { ToggleLikeV2(id: 1, type: ACTIVITY) { __typename } }"))
        assertFalse(isGraphqlMutation("{ Viewer { id } }"))
        assertFalse(isGraphqlMutation("query (\$page: Int) { Page(page: \$page) { media { id } } }"))
        assertFalse(isGraphqlMutation("{ mutationField { id } }"))
    }

    @Test
    fun `concurrency cap never exceeds maxConcurrency`() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val limiter = AnilistRateLimiter(
            maxConcurrency = 2,
            minIntervalMs = 0L,
            jitterMs = { 0L },
            sleeper = { },
        )

        val jobs = (1..6).map { i ->
            async {
                limiter.execute(isMutation = false) {
                    val cur = active.incrementAndGet()
                    peak.updateAndGet { prev -> maxOf(prev, cur) }
                    delay(20)
                    active.decrementAndGet()
                    AnilistHttpResponse(code = 200, text = """{"id":$i}""", rateLimitRemaining = 25)
                }
            }
        }
        val results = jobs.awaitAll()
        assertEquals(6, results.size)
        assertTrue("Expected peak concurrency <= 2, was ${peak.get()}", peak.get() <= 2)
    }

    @Test
    fun `retries read query on 429 honoring Retry-After and clears waiting state on success`() = runBlocking {
        var now = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val attempts = AtomicInteger(0)
        val limiter = AnilistRateLimiter(
            maxConcurrency = 2,
            maxRetriesForReads = 2,
            minIntervalMs = 0L,
            jitterMs = { 0L },
            nowMs = { now },
            sleeper = { ms ->
                sleeps.add(ms)
                now += ms
            },
        )

        val response = limiter.execute(isMutation = false) {
            if (attempts.getAndIncrement() == 0) {
                AnilistHttpResponse(code = 429, text = "Too Many Requests", retryAfterSeconds = 7)
            } else {
                AnilistHttpResponse(code = 200, text = """{"ok":true}""", rateLimitRemaining = 28)
            }
        }

        assertEquals(200, response.code)
        assertEquals(2, attempts.get())
        assertEquals(listOf(7_000L), sleeps)
        assertFalse(limiter.state.value.waiting)
        assertFalse(limiter.state.value.exhausted)
    }

    @Test
    fun `429 falls back to X-RateLimit-Reset in seconds and milliseconds`() = runBlocking {
        var now = 1_760_000_000_000L
        val sleeps = mutableListOf<Long>()
        val attempts = AtomicInteger(0)
        val limiter = AnilistRateLimiter(
            maxRetriesForReads = 2,
            minIntervalMs = 0L,
            jitterMs = { 0L },
            nowMs = { now },
            sleeper = { ms ->
                sleeps.add(ms)
                now += ms
            },
        )

        val response = limiter.execute(isMutation = false) {
            when (attempts.getAndIncrement()) {
                0 -> AnilistHttpResponse(code = 429, text = "", rateLimitResetRaw = 1_760_000_012L)
                1 -> AnilistHttpResponse(code = 429, text = "", rateLimitResetRaw = now + 5_000L)
                else -> AnilistHttpResponse(code = 200, text = "{}")
            }
        }

        assertEquals(200, response.code)
        assertEquals(3, attempts.get())
        assertEquals(listOf(12_000L, 5_000L), sleeps)
    }

    @Test
    fun `429 retries are bounded and never tight-loop without headers`() = runBlocking {
        var now = 5_000_000L
        val sleeps = mutableListOf<Long>()
        val attempts = AtomicInteger(0)
        val limiter = AnilistRateLimiter(
            maxRetriesForReads = 2,
            minIntervalMs = 0L,
            minRetryWaitMs = 1_000L,
            jitterMs = { 0L },
            nowMs = { now },
            sleeper = { ms ->
                sleeps.add(ms)
                now += ms
            },
        )

        try {
            limiter.execute(isMutation = false) {
                attempts.incrementAndGet()
                AnilistHttpResponse(code = 429, text = "Too Many Requests")
            }
            fail("Expected AnilistRateLimitException after exhausting retries")
        } catch (e: AnilistRateLimitException) {
            assertTrue(e.waitSeconds >= 1L)
        }

        assertEquals(3, attempts.get())
        assertEquals(listOf(15_000L, 30_000L), sleeps)
        assertTrue(limiter.state.value.exhausted)
        assertFalse(limiter.state.value.waiting)
    }

    @Test
    fun `mutations are never retried on 429 and still record shared cooldown`() = runBlocking {
        var now = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val attempts = AtomicInteger(0)
        val limiter = AnilistRateLimiter(
            maxRetriesForReads = 2,
            minIntervalMs = 0L,
            jitterMs = { 0L },
            nowMs = { now },
            sleeper = { ms ->
                sleeps.add(ms)
                now += ms
            },
        )

        try {
            limiter.execute(isMutation = true) {
                attempts.incrementAndGet()
                AnilistHttpResponse(code = 429, text = "", retryAfterSeconds = 10)
            }
            fail("Expected AnilistRateLimitException for rate-limited mutation")
        } catch (e: AnilistRateLimitException) {
            assertEquals(10L, e.waitSeconds)
        }

        assertEquals("Mutation must execute at most once", 1, attempts.get())
        assertTrue("Mutation must not sleep/retry after 429", sleeps.isEmpty())
        assertEquals(now + 10_000L, limiter.currentBlockedUntilMs())
    }

    @Test
    fun `proactive pacing slows down when X-RateLimit-Remaining is low and waits on zero`() = runBlocking {
        var now = 10_000_000L
        val sleeps = mutableListOf<Long>()
        val limiter = AnilistRateLimiter(
            minIntervalMs = 250L,
            jitterMs = { 0L },
            nowMs = { now },
            sleeper = { ms ->
                sleeps.add(ms)
                now += ms
            },
        )

        limiter.execute(isMutation = false) {
            AnilistHttpResponse(
                code = 200,
                text = "{}",
                rateLimitRemaining = 4,
                rateLimitResetRaw = (now + 30_000L) / 1000L,
            )
        }
        limiter.execute(isMutation = false) {
            AnilistHttpResponse(
                code = 200,
                text = "{}",
                rateLimitRemaining = 0,
                rateLimitResetRaw = (now + 10_000L) / 1000L,
            )
        }
        limiter.execute(isMutation = false) {
            AnilistHttpResponse(code = 200, text = "{}", rateLimitRemaining = 30)
        }

        assertEquals(2, sleeps.size)
        assertEquals(6_000L, sleeps[0])
        assertEquals(10_000L, sleeps[1])
    }

    @Test
    fun `stale zero remaining after reset timestamp does not block`() {
        val now = 2_000_000L
        val delayMs = computePacingDelayMs(
            nowMs = now,
            remaining = 0,
            resetAtMs = now - 1_000L,
            minIntervalMs = 250L,
            lastDispatchMs = now - 5_000L,
        )
        assertEquals(0L, delayMs)
    }

    @Test
    fun `long external rate-limit gate fails fast without sleeping or calling network`() = runBlocking {
        val now = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val calls = AtomicInteger(0)
        val limiter = AnilistRateLimiter(
            maxRetryWaitMs = 95_000L,
            nowMs = { now },
            sleeper = { sleeps.add(it) },
        )
        limiter.externalBlockedUntilMs = now + 3_600_000L

        try {
            limiter.execute(isMutation = false) {
                calls.incrementAndGet()
                AnilistHttpResponse(code = 200, text = "{}")
            }
            fail("Expected fast-fail AnilistRateLimitException")
        } catch (e: AnilistRateLimitException) {
            assertEquals(3600L, e.waitSeconds)
        }

        assertEquals(0, calls.get())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `cancelling a waiting request propagates CancellationException`() = runBlocking {
        val now = 1_000_000L
        val enteredSleep = CompletableDeferred<Unit>()
        val limiter = AnilistRateLimiter(
            nowMs = { now },
            sleeper = {
                enteredSleep.complete(Unit)
                delay(60_000L)
            },
        )
        limiter.externalBlockedUntilMs = now + 10_000L

        var caughtCancel = false
        val job = launch {
            try {
                limiter.execute(isMutation = false) {
                    AnilistHttpResponse(code = 200, text = "{}")
                }
            } catch (_: CancellationException) {
                caughtCancel = true
            }
        }
        enteredSleep.await()
        job.cancel()
        job.join()
        assertTrue(caughtCancel)
    }
}
