package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordRpcControllerTest {

    private class FakeTransport : DiscordRpcTransport {
        val sent = mutableListOf<String>()
        var returns = true
        override fun send(frame: String): Boolean {
            sent.add(frame)
            return returns
        }
    }

    private class RecordingLogger : DiscordRpcLogger {
        val logs = mutableListOf<String>()
        override fun log(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
    }

    private fun presence(name: String = "Test") = DiscordPresence(name = name, type = DiscordActivityType.WATCHING)

    private fun controller(appId: Long = 1163925779692912771L, logger: DiscordRpcLogger = NoopDiscordRpcLogger) =
        DiscordRpcController(appId = appId, logger = logger)

    @Test
    fun setPresenceWhileIdle_startsBindIntent() {
        val c = controller()
        assertEquals(DiscordRpcState.IDLE, c.getState())
        c.requestPresence(presence())
        assertEquals(DiscordRpcState.BINDING, c.getState())
    }

    @Test
    fun readyGatesSend_andSendsLatestOnce() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence()) // pending while BINDING
        c.onBinderConnected(42)
        assertEquals(DiscordRpcState.CONNECTED, c.getState())
        assertEquals(0, t.sent.size) // not sent before READY
        c.onFrame("""{"evt":"READY","cmd":"DISPATCH","data":{}}""")
        assertEquals(DiscordRpcState.READY, c.getState())
        assertEquals(1, t.sent.size)
        assertTrue(t.sent[0].contains("\"cmd\":\"SET_ACTIVITY\""))
    }

    @Test
    fun updatesAfterReadyReplace() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence("A"))
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.requestPresence(presence("B")) // replace
        assertEquals(2, t.sent.size)
        assertTrue(t.sent[1].contains("\"name\":\"B\""))
    }

    @Test
    fun clearBeforeReadyCancels() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.clearPresence() // before READY
        c.onFrame("""{"evt":"READY"}""")
        assertEquals(0, t.sent.size) // no SET_ACTIVITY sent
    }

    @Test
    fun clearAfterReadySendsClear() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.clearPresence()
        assertEquals(2, t.sent.size)
        assertTrue(t.sent[1].contains("\"activity\":null"))
    }

    @Test
    fun binderLoss_returnsRecoveryDecision_noSynchronousRebind() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""") // sends SET (1)
        val needRebind = c.onBinderLost() // pending still set -> returns true
        assertTrue(needRebind)
        assertEquals(DiscordRpcState.BINDING, c.getState())
        // The controller must NOT have triggered a rebind itself (recovery is the client's job).
        assertEquals(1, t.sent.size)
        // Simulate the client's bounded async recovery: re-bind + READY resends.
        c.attachTransport(t)
        c.onBinderConnected(2)
        c.onFrame("""{"evt":"READY"}""")
        assertEquals(2, t.sent.size)
    }

    @Test
    fun binderLossWhileIdle_returnsFalse() {
        val c = controller()
        assertEquals(DiscordRpcState.IDLE, c.getState())
        assertFalse(c.onBinderLost())
    }

    @Test
    fun establishmentTimeoutResetsToIdle() {
        val c = controller()
        c.attachTransport(FakeTransport())
        c.requestPresence(presence()) // BINDING
        c.onBinderConnected(1) // CONNECTED
        c.onEstablishmentTimeout() // no READY -> IDLE
        assertEquals(DiscordRpcState.IDLE, c.getState())
    }

    @Test
    fun noAutoClearAfterSend() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        assertEquals(1, t.sent.size)
        assertTrue(t.sent.none { it.contains("\"activity\":null") })
    }

    @Test
    fun shutdownWhileReadySendsClear() {
        val t = FakeTransport()
        val c = controller()
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.shutdown()
        assertEquals(2, t.sent.size)
        assertTrue(t.sent[1].contains("\"activity\":null"))
        assertEquals(DiscordRpcState.CLOSING, c.getState())
    }

    @Test
    fun shutdown_isIdempotent() {
        val c = controller()
        c.attachTransport(FakeTransport())
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.shutdown()
        c.shutdown()
        assertEquals(DiscordRpcState.CLOSING, c.getState())
    }

    @Test
    fun outOfOrderReady_whileIdleOrClosing_ignored() {
        val c = controller()
        c.onFrame("""{"evt":"READY"}""") // while IDLE
        assertEquals(DiscordRpcState.IDLE, c.getState())
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.shutdown() // CLOSING
        c.onFrame("""{"evt":"READY"}""") // ignored
        assertEquals(DiscordRpcState.CLOSING, c.getState())
    }

    // ── ACK = SET_ACTIVITY response with matching nonce ────────────────────

    @Test
    fun setActivityResponse_withMatchingNonce_isAcknowledged() {
        val t = FakeTransport()
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        val nonce = DiscordRpcCodec.parseInbound(t.sent.last()).nonce!!
        c.onFrame("""{"cmd":"SET_ACTIVITY","nonce":"$nonce"}""")
        assertTrue(logger.logs.any { it.contains("acknowledged") })
    }

    @Test
    fun setActivityResponse_withUnmatchedNonce_isIgnored() {
        val t = FakeTransport()
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.onFrame("""{"cmd":"SET_ACTIVITY","nonce":"deadbeef"}""")
        assertTrue(logger.logs.any { it.contains("unmatched") })
        assertFalse(logger.logs.any { it.contains("acknowledged") })
    }

    @Test
    fun clearResponse_withMatchingNonce_isAcknowledged() {
        val t = FakeTransport()
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        c.clearPresence()
        val nonce = DiscordRpcCodec.parseInbound(t.sent.last()).nonce!!
        c.onFrame("""{"cmd":"SET_ACTIVITY","nonce":"$nonce"}""")
        assertTrue(logger.logs.any { it.contains("acknowledged") })
    }

    // ── a rejected SET_ACTIVITY must NOT be logged as acknowledged ────────────

    @Test
    fun setActivityError_withMatchingNonce_isRejectedNotAcknowledged() {
        val t = FakeTransport()
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        val nonce = DiscordRpcCodec.parseInbound(t.sent.last()).nonce!!
        c.onFrame("""{"cmd":"SET_ACTIVITY","evt":"ERROR","nonce":"$nonce","data":{"code":123,"message":"rejected"}}""")
        assertTrue(logger.logs.any { it.contains("rpc: ERROR") })
        assertFalse(logger.logs.any { it.contains("acknowledged") })
    }

    @Test
    fun setActivityError_retiresNonce_soReplayIsNeverAcknowledged() {
        val t = FakeTransport()
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        val nonce = DiscordRpcCodec.parseInbound(t.sent.last()).nonce!!
        // Discord rejects the SET_ACTIVITY and echoes our nonce: ERRor keeps the nonce, never ACKs it.
        c.onFrame("""{"cmd":"SET_ACTIVITY","evt":"ERROR","nonce":"$nonce","data":{"code":123}}""")
        // A later replay (or a fake success) using the same failed nonce must remain unmatched.
        c.onFrame("""{"cmd":"SET_ACTIVITY","nonce":"$nonce"}""")
        assertFalse(logger.logs.any { it.contains("acknowledged") })
        assertTrue(logger.logs.any { it.contains("unmatched") })
    }

    @Test
    fun setActivityError_withUnmatchedNonce_keepsOtherOutstandingNonceValid() {
        val t = FakeTransport()
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""")
        val goodNonce = DiscordRpcCodec.parseInbound(t.sent.last()).nonce!!
        // An ERROR for an unrelated nonce must not retire our outstanding `goodNonce`.
        c.onFrame("""{"cmd":"SET_ACTIVITY","evt":"ERROR","nonce":"other","data":{"code":9}}""")
        assertFalse(logger.logs.any { it.contains("acknowledged") })
        // The genuine response for our outstanding nonce is still acknowledged.
        c.onFrame("""{"cmd":"SET_ACTIVITY","nonce":"$goodNonce"}""")
        assertTrue(logger.logs.any { it.contains("acknowledged") })
    }

    // ── nonce only tracked after a successful send ────────────────────────

    @Test
    fun sendFailure_doesNotLeaveNonceOutstanding() {
        val t = FakeTransport().apply { returns = false }
        val logger = RecordingLogger()
        val c = controller(logger = logger)
        c.attachTransport(t)
        c.requestPresence(presence())
        c.onBinderConnected(1)
        c.onFrame("""{"evt":"READY"}""") // SET attempted but send fails
        val nonce = DiscordRpcCodec.parseInbound(t.sent.last()).nonce!!
        // Replaying that nonce must NOT be acknowledged (it was never outstanding).
        c.onFrame("""{"cmd":"SET_ACTIVITY","nonce":"$nonce"}""")
        assertFalse(logger.logs.any { it.contains("acknowledged") })
        assertTrue(logger.logs.any { it.contains("unmatched") })
    }
}
