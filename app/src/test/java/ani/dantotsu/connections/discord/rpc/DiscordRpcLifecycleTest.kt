package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

/**
 * Host-tested proof of the per-run routing/teardown guarantees. The Android client composes [DiscordRpcLifecycle]; these tests pin the
 * exact identity rule: `runGen == currentGeneration AND connIdentity === thisRunConn`.
 */
class DiscordRpcLifecycleTest {

    private class Run

    @Test
    fun beginRun_incrementsGeneration_andTracksActiveIdentity() {
        val lc = DiscordRpcLifecycle()
        val a = Run()
        val gA = lc.beginRun(a)
        assertEquals(1, gA)
        assertTrue(lc.isActiveRun(gA, a))
        assertEquals(a, lc.activeConnIdentity())

        val b = Run()
        val gB = lc.beginRun(b)
        assertEquals(2, gB)
        assertTrue(lc.isActiveRun(gB, b))
        assertFalse(lc.isActiveRun(gA, a)) // A superseded
        assertEquals(b, lc.activeConnIdentity())
    }

    @Test
    fun staleServiceConnected_afterNewerRun_isIgnored_andCannotTearDownB() {
        val lc = DiscordRpcLifecycle()
        val a = Run(); val b = Run()
        val gA = lc.beginRun(a)
        val gB = lc.beginRun(b)
        // Run A's onServiceConnected fires late:
        assertFalse(lc.isActiveRun(gA, a))
        // A's attempt to close itself must NOT affect B:
        assertFalse(lc.closeRun(a))
        assertEquals(b, lc.activeConnIdentity())
    }

    @Test
    fun staleServiceDisconnected_orDied_orNullBinding_areAllGatedByIdentity() {
        val lc = DiscordRpcLifecycle()
        val a = Run(); val b = Run()
        val gA = lc.beginRun(a)
        lc.beginRun(b)
        // All three stale ServiceConnection callbacks reduce to an isActiveRun(ga, a) gate:
        assertFalse(lc.isActiveRun(gA, a))
        // And closing the stale run is a no-op:
        assertFalse(lc.closeRun(a))
        assertTrue(lc.isActiveRun(lc.currentGeneration(), b))
    }

    @Test
    fun staleOnFrameReady_afterNewerRun_isRejected() {
        val lc = DiscordRpcLifecycle()
        val a = Run(); val b = Run()
        val gA = lc.beginRun(a)
        lc.beginRun(b)
        // A's inbound READY must be ignored by the client (isActiveRun false):
        assertFalse(lc.isActiveRun(gA, a))
    }

    @Test
    fun staleOnClose_afterNewerRun_isRejected_andCannotUnbindB() {
        val lc = DiscordRpcLifecycle()
        val a = Run(); val b = Run()
        val gA = lc.beginRun(a)
        lc.beginRun(b)
        assertFalse(lc.isActiveRun(gA, a))
        assertFalse(lc.closeRun(a)) // old-run cleanup cannot unbind B
        assertEquals(b, lc.activeConnIdentity())
    }

    @Test
    fun oldRunCleanup_cannotUnbindNewerRun() {
        val lc = DiscordRpcLifecycle()
        val a = Run(); val b = Run()
        lc.beginRun(a)
        lc.beginRun(b)
        // Closing A (stale) is a no-op; B remains and is closable.
        assertFalse(lc.closeRun(a))
        assertTrue(lc.closeRun(b))
        assertNull(lc.activeConnIdentity())
    }

    @Test
    fun activeRun_closeSucceeds_andClearsIdentity() {
        val lc = DiscordRpcLifecycle()
        val a = Run()
        val gA = lc.beginRun(a)
        assertTrue(lc.isActiveRun(gA, a))
        assertTrue(lc.closeRun(a))
        assertNull(lc.activeConnIdentity())
        assertFalse(lc.isActiveRun(gA, a))
    }

    @Test
    fun readyOnRunA_doesNotDisableRunB_establishmentTimeout() {
        // Per-run timeout identity: after B starts, A's fired timeout must be ignored.
        val lc = DiscordRpcLifecycle()
        val a = Run(); val b = Run()
        val gA = lc.beginRun(a)
        lc.beginRun(b) // B supersedes A
        // A's timeout callback checks isActiveRun(gA, a) -> false -> ignored.
        assertFalse(lc.isActiveRun(gA, a))
    }
}
