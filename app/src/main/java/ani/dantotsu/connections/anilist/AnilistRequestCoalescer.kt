package ani.dantotsu.connections.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coalesces identical in-flight read requests so concurrent callers share a single
 * network execution and response.
 *
 * Cancellation semantics:
 * - Cancelling one waiter throws [CancellationException] in that waiter immediately while
 *   keeping the shared request alive for any remaining waiters.
 * - When the last active waiter cancels, the shared request is cancelled and removed.
 */
class AnilistRequestCoalescer<K : Any, V>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry<V>(
        val deferred: Deferred<V>,
        val waiters: AtomicInteger = AtomicInteger(0),
    )

    private val lock = Any()
    private val inFlight = HashMap<K, Entry<V>>()

    suspend fun execute(key: K, block: suspend () -> V): V {
        val entry: Entry<V> = synchronized(lock) {
            val existing = inFlight[key]
            if (existing != null && !existing.deferred.isCompleted && existing.waiters.get() > 0) {
                existing.waiters.incrementAndGet()
                existing
            } else {
                lateinit var created: Entry<V>
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        block()
                    } finally {
                        synchronized(lock) {
                            if (inFlight[key] === created) {
                                inFlight.remove(key)
                            }
                        }
                    }
                }
                created = Entry(deferred, AtomicInteger(1))
                inFlight[key] = created
                created
            }
        }

        entry.deferred.start()
        var decremented = false
        return try {
            entry.deferred.await()
        } catch (e: CancellationException) {
            decremented = true
            if (entry.waiters.decrementAndGet() <= 0) {
                synchronized(lock) {
                    if (inFlight[key] === entry) {
                        inFlight.remove(key)
                    }
                }
                if (!entry.deferred.isCompleted) {
                    entry.deferred.cancel(e)
                }
            }
            throw e
        } finally {
            if (!decremented) {
                entry.waiters.decrementAndGet()
            }
        }
    }

    fun inFlightCount(): Int = synchronized(lock) { inFlight.size }
}
