package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordRpcClientCoordinatorTest {

    companion object {
        const val APP_ID = 1163925779692912771L
        val READY = """{"evt":"READY"}"""
    }

    private class RecordingLogger : DiscordRpcLogger {
        val logs = mutableListOf<String>()
        override fun log(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
    }

    private enum class ConnectMode { OK, NULL }

    private class FakeBindDelegate : DiscordRpcBindDelegate {
        var bindCalls = 0
        var unbindCalls = 0
        var shouldBind = true
        var connectMode: ConnectMode = ConnectMode.OK
        var sendReturns = true
        val sentFrames = mutableListOf<String>()
        val sentByGen = mutableMapOf<Int, MutableList<String>>()
        var disconnectCalls = 0
        val closedConnections = mutableListOf<DiscordRpcConnection>()

        internal inner class FakeConnection(val runGen: Int) : DiscordRpcConnection {
            val sent = mutableListOf<String>()
            var disconnects = 0
            override fun sendFrame(frame: String): Boolean {
                sent.add(frame)
                sentFrames.add(frame)
                sentByGen.getOrPut(runGen) { mutableListOf() }.add(frame)
                return sendReturns
            }
            override fun disconnect() { disconnects++; disconnectCalls++; closedConnections.add(this) }
        }

        override fun bind(runGen: Int): Boolean { bindCalls++; return shouldBind }
        override fun unbind(runGen: Int) { unbindCalls++ }
        // (connectRpc is no longer on the delegate; the test hands a connection to onServiceConnected)
        fun makeConnection(runGen: Int): DiscordRpcConnection? = when (connectMode) {
            ConnectMode.OK -> FakeConnection(runGen)
            ConnectMode.NULL -> null
        }
    }

    private class FakeScheduler : DiscordRpcScheduler {
        data class Task(val block: () -> Unit) : DiscordRpcScheduler.Task
        val pending = mutableListOf<Task>()
        var cancelled = 0
        override fun postDelayed(delayMs: Long, block: () -> Unit): Task {
            val t = Task(block); pending.add(t); return t
        }
        override fun cancel(task: DiscordRpcScheduler.Task) { if (pending.remove(task)) cancelled++ }
        fun runNext(): Boolean { if (pending.isEmpty()) return false; pending.removeAt(0).block(); return true }
        fun runAll() { while (pending.isNotEmpty()) runNext() }
        fun pendingCount() = pending.size
    }

    private data class Fixture(
        val coord: DiscordRpcClientCoordinator,
        val binder: FakeBindDelegate,
        val scheduler: FakeScheduler,
        val controller: DiscordRpcController,
        val logger: RecordingLogger,
    )

    private fun presence(name: String = "Test") = DiscordPresence(name = name, type = DiscordActivityType.WATCHING)

    private fun make(
        shouldBind: Boolean = true,
        connectMode: ConnectMode = ConnectMode.OK,
        sendReturns: Boolean = true,
    ): Fixture {
        val binder = FakeBindDelegate().apply { this.shouldBind = shouldBind; this.connectMode = connectMode; this.sendReturns = sendReturns }
        val scheduler = FakeScheduler()
        val logger = RecordingLogger()
        val controller = DiscordRpcController(appId = APP_ID, logger = logger)
        val coord = DiscordRpcClientCoordinator(
            pid = 1234, controller = controller,
            scheduler = scheduler, logger = logger, binder = binder,
        )
        return Fixture(coord, binder, scheduler, controller, logger)
    }

    // ── per-run registration ───────────────────────────────────────────────

    @Test
    fun successfulBindRegistration_beforeConnectionCallback() {
        val (c, b, _, ctrl) = make()
        c.requestPresence(presence())
        assertEquals(1, b.bindCalls)
        c.onServiceConnected(1, b.makeConnection(1))
        c.onFrame(1, READY)
        assertEquals(DiscordRpcState.READY, ctrl.getState())
    }

    @Test
    fun shutdownBeforeConnected_unbindsExactlyOnce() {
        val (c, b, s, _) = make()
        c.requestPresence(presence()) // bind registered (bindService==true), no onServiceConnected yet
        c.shutdown()
        assertEquals(1, b.unbindCalls) // registered connection torn down even without onServiceConnected
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun nullBinding_unbindsExactlyOnce() {
        val (c, b, _, _) = make()
        c.requestPresence(presence())
        c.onNullBinding(1)
        assertEquals(1, b.unbindCalls)
    }

    @Test
    fun establishmentTimeout_unbindsAndResetsState() {
        val (c, b, _, ctrl) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1))
        c.onFrame(1, READY)
        c.onEstablishmentTimeout(1)
        assertEquals(0, b.unbindCalls)
        val (c2, b2, _, ctrl2) = make()
        c2.requestPresence(presence())
        c2.onServiceConnected(1, b2.makeConnection(1))
        c2.onEstablishmentTimeout(1)
        assertEquals(1, b2.unbindCalls)
        assertEquals(DiscordRpcState.BINDING, ctrl2.getState())
    }

    @Test
    fun bindServiceFalse_boundedRetry() {
        val (c, b, s, ctrl) = make(shouldBind = false)
        c.requestPresence(presence())
        s.runAll()
        assertEquals(4, b.bindCalls) // 1 initial + 3 recovery attempts, then gives up
        assertEquals(0, s.pendingCount())
        assertEquals(DiscordRpcState.IDLE, ctrl.getState())
    }

    @Test
    fun nullConnectHandle_boundedRetry() {
        val (c, b, s, _) = make(connectMode = ConnectMode.NULL)
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)) // null -> loss
        s.runAll()
        assertEquals(4, b.bindCalls) // 1 initial + 3 recovery attempts, then gives up
        assertEquals(0, s.pendingCount())
    }

    // ── ordinary setPresence updates must NOT start a new run ────────────────

    @Test
    fun ordinaryUpdate_whileReady_doesNotRebind() {
        val (c, b, _, _) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.requestPresence(presence("B")) // ordinary update
        assertEquals(1, b.bindCalls)      // no rebind
        assertTrue(b.sentFrames.any { it.contains("\"name\":\"B\"") })
    }

    @Test
    fun secondPresenceWhileBinding_noSecondBind() {
        val (c, b, _, _) = make()
        c.requestPresence(presence("A"))
        c.requestPresence(presence("B")) // while BINDING, before connect
        assertEquals(1, b.bindCalls)
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        assertTrue(b.sentFrames.any { it.contains("\"name\":\"B\"") })
        assertFalse(b.sentFrames.any { it.contains("\"name\":\"A\"") })
    }

    @Test
    fun rapidUpdatesBeforeReady_onlyFlushesLatest() {
        val (c, b, _, _) = make()
        c.requestPresence(presence("A"))
        c.requestPresence(presence("B"))
        c.requestPresence(presence("C"))
        assertEquals(1, b.bindCalls)
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        assertTrue(b.sentFrames.any { it.contains("\"name\":\"C\"") })
        assertFalse(b.sentFrames.any { it.contains("\"name\":\"A\"") })
    }

    @Test
    fun ordinaryReadyUpdate_noUnbindRebind() {
        val (c, b, _, _) = make()
        c.requestPresence(presence()); c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.requestPresence(presence("B"))
        assertEquals(1, b.bindCalls)
        assertEquals(0, b.unbindCalls)
    }

    // ── cancellable recovery ────────────────────────────────────────────────

    @Test
    fun recoveryCancelledOnClear_beforeBackoff() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.onBindingDied(1); assertTrue(s.pendingCount() >= 1)
        c.clearPresence()
        assertEquals(0, s.pendingCount())
        s.runAll()
        assertEquals(1, b.bindCalls)
    }

    @Test
    fun recoveryCancelledOnShutdown_beforeBackoff() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.onBindingDied(1)
        c.shutdown()
        assertEquals(0, s.pendingCount())
        s.runAll()
        assertEquals(1, b.bindCalls)
    }

    @Test
    fun staleRecoveryRunnable_isNoOp_afterNewerRun() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())
        c.onBindingDied(1) // schedule recovery (gen G1)
        val stale = s.pending.last() // the recovery task (a timeout may precede it)
        c.requestPresence(presence()) // newer run: cancels recovery, binds again (startBind since activeRun null)
        assertEquals(2, b.bindCalls)
        stale.block()
        assertEquals(2, b.bindCalls) // stale task did NOT start a new bind
    }

    @Test
    fun onlyOneRecoveryTaskPending() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())
        c.onBindingDied(1)
        c.onBindingDied(1)
        assertEquals(1, s.pendingCount())
    }

    // ── per-run RPC handle ownership (no global slot) ────────────────────────

    @Test
    fun staleOnServiceConnected_ignored() {
        val (c, b, _, _) = make()
        c.requestPresence(presence())
        c.onServiceConnected(2, b.makeConnection(2)) // wrong gen, no active run 2 -> ignored
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.onServiceConnected(9, b.makeConnection(9)) // stale, ignored
        assertTrue(b.sentByGen[1]!!.isNotEmpty())
        assertFalse(b.sentByGen.containsKey(9))
    }

    @Test
    fun reorderedServiceConnected_keepsCurrentHandle() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())                 // A bind (gen1)
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY) // A active
        c.onBindingDied(1)                            // A lost -> recovery
        s.runNext()                                   // recovery bind (gen2 = B)
        c.onServiceConnected(2, b.makeConnection(2))   // B connects
        c.onFrame(2, READY)                           // B active
        c.onServiceConnected(1, b.makeConnection(1))   // late A -> stale, ignored
        c.requestPresence(presence("X"))              // update over current run B
        assertTrue(b.sentByGen[2]!!.any { it.contains("\"name\":\"X\"") })
        assertFalse(b.sentByGen[1]!!.any { it.contains("\"name\":\"X\"") })
    }

    // ── stale/rejected RPC connection must be disposed fail-closed ─────────

    @Test
    fun lateStaleConnection_isDisconnectedExactlyOnce() {
        val (c, b, _, _) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY) // active run 1
        val stale = b.makeConnection(5) as FakeBindDelegate.FakeConnection // a connection for a retired/unknown gen
        c.onServiceConnected(5, stale) // coordinator fail-closed: disconnect, never attach
        assertEquals(1, stale.disconnects)
        assertEquals(1, b.bindCalls) // no new run started
    }

    @Test
    fun lateStaleConnection_cannotReplaceCurrentBHandle() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())                 // A
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.onBindingDied(1); while (b.bindCalls < 2 && s.pendingCount() > 0) s.runNext() // recover B
        c.onServiceConnected(2, b.makeConnection(2)); c.onFrame(2, READY) // B active
        val staleA = b.makeConnection(1)
        c.onServiceConnected(1, staleA)               // stale A -> disconnected, B untouched
        assertTrue(b.closedConnections.contains(staleA))
        c.requestPresence(presence("X"))              // update over B
        assertTrue(b.sentByGen[2]!!.any { it.contains("\"name\":\"X\"") })
        assertFalse(b.sentByGen[1]!!.any { it.contains("\"name\":\"X\"") })
    }

    @Test
    fun connectionDeliveredAfterShutdown_isDisconnected() {
        val (c, b, _, _) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.shutdown() // no active run remains
        val conn = b.makeConnection(1)
        c.onServiceConnected(1, conn) // delivered after shutdown -> fail-closed disconnect
        assertTrue(b.closedConnections.contains(conn))
    }

    @Test
    fun duplicateConnectionDelivery_sameGen_noLeak() {
        val (c, b, _, _) = make()
        c.requestPresence(presence())
        val first = b.makeConnection(1) as FakeBindDelegate.FakeConnection
        c.onServiceConnected(1, first); c.onFrame(1, READY) // first active
        val second = b.makeConnection(1) as FakeBindDelegate.FakeConnection
        c.onServiceConnected(1, second)                // duplicate for same gen -> replace, dispose first
        c.onFrame(1, READY)                            // re-establish READY on the new handle
        assertEquals(1, first.disconnects)
        c.requestPresence(presence("X"))               // update goes through the new (second) handle
        assertTrue(second.sent.any { it.contains("\"name\":\"X\"") })
        assertFalse(first.sent.any { it.contains("\"name\":\"X\"") })
    }

    // ── stale callbacks cannot affect the current run ──────────────────

    @Test
    fun runA_staleRpcCallback_cannotAffectRunB() {
        val (c, b, s, ctrl) = make()
        c.requestPresence(presence())                  // A
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.onBindingDied(1)                             // A lost -> recovery
        s.runNext()
        c.onServiceConnected(2, b.makeConnection(2)); c.onFrame(2, READY) // B active
        c.onFrame(1, """{"evt":"ERROR","data":{"code":7}}""") // stale A frame ignored
        c.onBindingDied(1)                             // stale A loss ignored
        assertEquals(DiscordRpcState.READY, ctrl.getState())
        assertEquals(2, b.bindCalls)
    }

    @Test
    fun runA_cleanup_cannotConsumeRunB_registration() {
        val (c, b, s, _) = make()
        c.requestPresence(presence())                  // A
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        c.onBindingDied(1); s.runNext()                // A lost, recovery -> B bind (gen2)
        c.onServiceConnected(2, b.makeConnection(2)); c.onFrame(2, READY) // B active
        c.onBindingDied(1)                             // stale A loss -> ignored
        assertEquals(1, b.unbindCalls)                 // only A's unbind happened
        c.onBindingDied(2)                             // now B lost
        assertEquals(2, b.unbindCalls)
    }

    // ── real send failure propagates as false (nonce not outstanding) ────────

    @Test
    fun underlyingSendFailure_propagatesAsFailure() {
        val (c, b, _, ctrl, logger) = make(sendReturns = false)
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        val nonce = DiscordRpcCodec.parseInbound(b.sentFrames.last()).nonce!!
        c.onFrame(1, """{"cmd":"SET_ACTIVITY","nonce":"$nonce"}""")
        assertFalse(logger.logs.any { it.contains("acknowledged") })
        assertTrue(logger.logs.any { it.contains("unmatched") })
    }

    // ── recovery budget resets only on a valid current READY ──────────────────

    @Test
    fun recoveryBudgetResetsOnHealthyReady() {
        val (c, b, s, _) = make(shouldBind = true)
        c.requestPresence(presence())                  // bind1
        c.onServiceConnected(1, b.makeConnection(1))
        c.onBindingDied(1)                             // loss -> recovery attempt (budget 1)
        while (b.bindCalls < 2 && s.pendingCount() > 0) s.runNext() // drain to recovery bind2
        assertEquals(2, b.bindCalls)
        c.onServiceConnected(2, b.makeConnection(2))
        c.onFrame(2, READY)                            // healthy READY resets budget (back to 0)
        b.shouldBind = false
        c.onBindingDied(2)                             // later loss -> full 3 attempts available
        s.runAll()
        assertEquals(5, b.bindCalls)                   // 1 + 1 + 3 (reset gave a full budget again)
    }

    @Test
    fun outOfOrderReady_doesNotResetBudgetOrState() {
        val (c, b, _, ctrl) = make()
        c.onFrame(7, READY)                            // stale READY, no active run -> ignored
        assertEquals(DiscordRpcState.IDLE, ctrl.getState())
    }

    // ── nonce bookkeeping ───────────────────────────────────────────────────

    @Test
    fun binderLoss_clearsOldRunNonceState() {
        val (c, b, _, ctrl, logger) = make()
        c.requestPresence(presence())
        c.onServiceConnected(1, b.makeConnection(1)); c.onFrame(1, READY)
        val oldNonce = DiscordRpcCodec.parseInbound(b.sentFrames.last()).nonce!!
        c.onBindingDied(1)
        c.requestPresence(presence("B"))
        c.onServiceConnected(2, b.makeConnection(2)); c.onFrame(2, READY)
        c.onFrame(2, """{"cmd":"SET_ACTIVITY","nonce":"$oldNonce"}""")
        assertTrue(b.sentFrames.any { it.contains("\"name\":\"B\"") })
        assertFalse(logger.logs.any { it.contains("acknowledged") })
        assertTrue(logger.logs.any { it.contains("unmatched") })
    }
}
