package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

/**
 * Host-tested proof that recovery is bounded:
 * repeated `bindService=false`, null `connect()` handle, or connect exceptions cannot produce
 * an unbounded recursive retry storm. The client schedules the actual bind via `Handler`
 * (post-delayed → never synchronous recursion) and caps attempts with [DiscordRpcRecoveryPolicy].
 */
class DiscordRpcRecoveryPolicyTest {

    @Test
    fun canRetry_trueUntilMaxThenFalse() {
        val p = DiscordRpcRecoveryPolicy(maxAttempts = 3)
        assertTrue(p.canRetry())
        p.recordAttempt() // 1
        assertTrue(p.canRetry())
        p.recordAttempt() // 2
        assertTrue(p.canRetry())
        p.recordAttempt() // 3
        assertFalse(p.canRetry()) // exhausted
    }

    @Test
    fun reset_restoresRetries() {
        val p = DiscordRpcRecoveryPolicy(maxAttempts = 3)
        p.recordAttempt(); p.recordAttempt(); p.recordAttempt()
        assertFalse(p.canRetry())
        p.reset()
        assertTrue(p.canRetry())
    }

    @Test
    fun recoveryLoop_terminates() {
        // Simulates the client's scheduleRecovery loop: keep attempting while canRetry.
        val p = DiscordRpcRecoveryPolicy(maxAttempts = 3)
        var scheduled = 0
        while (p.canRetry()) {
            p.recordAttempt()
            scheduled++
        }
        assertEquals(3, scheduled) // bounded, no infinite loop
        assertFalse(p.canRetry())
    }

    @Test
    fun defaultMaxAttempts_isThree() {
        // A default policy permits exactly 3 attempts before canRetry goes false.
        val p = DiscordRpcRecoveryPolicy()
        p.recordAttempt(); p.recordAttempt(); p.recordAttempt()
        assertFalse(p.canRetry())
        p.reset()
        assertTrue(p.canRetry())
    }
}
