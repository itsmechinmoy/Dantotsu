package ani.dantotsu.connections.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM tests for [AnilistRequestCoalescer]: deduplication of identical in-flight calls,
 * independent keys, error fan-out, partial waiter cancellation, and full cancellation.
 */
class AnilistRequestCoalescerTest {

    @Test
    fun `identical concurrent requests execute once and share the result`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = AnilistRequestCoalescer<String, String>(scope)
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val executions = AtomicInteger(0)

            val callers = (1..5).map {
                async {
                    coalescer.execute("same-key") {
                        executions.incrementAndGet()
                        started.complete(Unit)
                        gate.await()
                        "shared-payload"
                    }
                }
            }

            started.await()
            gate.complete(Unit)
            val results = callers.awaitAll()

            assertEquals(1, executions.get())
            assertEquals(List(5) { "shared-payload" }, results)
            assertEquals(0, coalescer.inFlightCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `different keys execute independently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = AnilistRequestCoalescer<String, String>(scope)
            val executions = AtomicInteger(0)

            val a = async {
                coalescer.execute("key-a") {
                    executions.incrementAndGet()
                    "A"
                }
            }
            val b = async {
                coalescer.execute("key-b") {
                    executions.incrementAndGet()
                    "B"
                }
            }

            assertEquals("A", a.await())
            assertEquals("B", b.await())
            assertEquals(2, executions.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `completed request is removed so subsequent call executes fresh`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = AnilistRequestCoalescer<String, Int>(scope)
            val counter = AtomicInteger(0)

            assertEquals(1, coalescer.execute("k") { counter.incrementAndGet() })
            assertEquals(2, coalescer.execute("k") { counter.incrementAndGet() })
            assertEquals(0, coalescer.inFlightCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failure fans out to joined callers and clears in-flight entry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = AnilistRequestCoalescer<String, String>(scope)
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)

            val a = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    coalescer.execute("fail-key") {
                        calls.incrementAndGet()
                        started.complete(Unit)
                        gate.await()
                        throw IOException("boom")
                    }
                }
            }
            started.await()
            val b = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    coalescer.execute("fail-key") {
                        calls.incrementAndGet()
                        "should-not-run"
                    }
                }
            }

            gate.complete(Unit)

            assertTrue(a.await().exceptionOrNull() is IOException)
            assertTrue(b.await().exceptionOrNull() is IOException)
            assertEquals(1, calls.get())
            assertEquals(0, coalescer.inFlightCount())

            assertEquals("recovered", coalescer.execute("fail-key") { "recovered" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling one waiter keeps shared request alive for remaining waiter`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = AnilistRequestCoalescer<String, String>(scope)
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var blockCancelled = false

            val waiterA = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("shared") {
                    started.complete(Unit)
                    try {
                        gate.await()
                        "ok"
                    } catch (e: CancellationException) {
                        blockCancelled = true
                        throw e
                    }
                }
            }
            started.await()
            val waiterB = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("shared") { "duplicate-not-run" }
            }

            waiterA.cancel()
            try {
                waiterA.await()
                fail("waiterA should be cancelled")
            } catch (_: CancellationException) {
            }

            gate.complete(Unit)
            assertEquals("ok", waiterB.await())
            assertFalse("Shared block must not cancel while waiterB is still waiting", blockCancelled)
            assertEquals(0, coalescer.inFlightCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling the sole waiter cancels the underlying request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = AnilistRequestCoalescer<String, String>(scope)
            val started = CompletableDeferred<Unit>()
            val blockCancelled = CompletableDeferred<Boolean>()

            val waiter = async {
                coalescer.execute("solo") {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<String>().await()
                    } catch (e: CancellationException) {
                        blockCancelled.complete(true)
                        throw e
                    }
                }
            }

            started.await()
            waiter.cancel()
            try {
                waiter.await()
                fail("sole waiter should be cancelled")
            } catch (_: CancellationException) {
            }

            assertTrue(blockCancelled.await())
            assertEquals(0, coalescer.inFlightCount())
        } finally {
            scope.cancel()
        }
    }
}
