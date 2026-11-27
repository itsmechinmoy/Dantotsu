package ani.dantotsu.connections.discord.rpc

/** IDLE -> BINDING -> CONNECTED -> READY, CLOSING on shutdown. */
enum class DiscordRpcState { IDLE, BINDING, CONNECTED, READY, CLOSING }

/** Sends one frame on the live connection; false if nothing accepted it. */
fun interface DiscordRpcTransport {
    fun send(frame: String): Boolean
}

/**
 * Connection state and presence dispatch, free of Android so it can be unit tested.
 * SET_ACTIVITY goes out only after READY; until then only the latest presence is kept.
 * [onBinderLost] only reports whether a rebind is wanted, the caller schedules it.
 */
class DiscordRpcController(
    private val appId: Long,
    private val logger: DiscordRpcLogger = NoopDiscordRpcLogger,
    private val onStateChanged: (DiscordRpcState) -> Unit = {},
) {
    @Volatile private var transport: DiscordRpcTransport? = null
    private val lock = Any()

    @Volatile private var state = DiscordRpcState.IDLE
    private var pid = 0

    // Latest wanted presence; pendingClear means "show nothing".
    private var pending: DiscordPresence? = null
    private var pendingClear = false

    // Nonces of frames sent, matched against SET_ACTIVITY responses.
    private val outstandingNonces = mutableSetOf<String>()

    fun getState(): DiscordRpcState = state

    fun requestPresence(presence: DiscordPresence) = synchronized(lock) {
        pending = presence
        pendingClear = false
        when (state) {
            DiscordRpcState.IDLE -> setState(DiscordRpcState.BINDING)
            DiscordRpcState.READY -> sendSetActivity(presence)
            else -> Unit // sent on READY
        }
    }

    fun clearPresence() = synchronized(lock) {
        pending = null
        pendingClear = true
        if (state == DiscordRpcState.READY) sendClear()
    }

    fun shutdown() = synchronized(lock) {
        if (state == DiscordRpcState.READY) sendClear()
        pending = null
        pendingClear = true
        setState(DiscordRpcState.CLOSING)
    }

    fun onEstablishmentTimeout() = synchronized(lock) {
        if (state == DiscordRpcState.CONNECTED || state == DiscordRpcState.BINDING) {
            logger.warn("rpc: no READY in time, giving up this attempt")
            resetLocked()
        }
    }

    fun reset() = synchronized(lock) { resetLocked() }

    fun attachTransport(t: DiscordRpcTransport) = synchronized(lock) { transport = t }

    fun onBinderConnected(pid: Int) = synchronized(lock) {
        if (state == DiscordRpcState.CLOSING) return@synchronized
        this.pid = pid
        outstandingNonces.clear()
        setState(DiscordRpcState.CONNECTED)
    }

    /** True if a presence is still wanted and the caller should try one more bind. */
    fun onBinderLost(): Boolean = synchronized(lock) {
        transport = null
        outstandingNonces.clear()
        if (state == DiscordRpcState.CLOSING || state == DiscordRpcState.IDLE) return@synchronized false
        val needRebind = pending != null && !pendingClear
        if (needRebind) setState(DiscordRpcState.BINDING) else resetLocked()
        needRebind
    }

    fun onFrame(frame: String) {
        val f = DiscordRpcCodec.parseInbound(frame)
        synchronized(lock) {
            when {
                f.isReady -> {
                    if (state != DiscordRpcState.CONNECTED) {
                        logger.warn("rpc: READY while $state, ignored")
                        return
                    }
                    setState(DiscordRpcState.READY)
                    val p = pending
                    if (!pendingClear && p != null) sendSetActivity(p)
                }

                f.isError -> {
                    // Retire the nonce so a replayed response can't count as success.
                    f.nonce?.let { outstandingNonces.remove(it) }
                    logger.warn("rpc: ERROR ${f.errorCode} ${f.errorMessage.orEmpty()}")
                }

                f.isActivityResponse -> {
                    if (f.nonce != null && outstandingNonces.remove(f.nonce)) {
                        logger.log("rpc: SET_ACTIVITY acknowledged")
                    } else {
                        logger.log("rpc: SET_ACTIVITY response with unmatched nonce, ignored")
                    }
                }
            }
        }
    }

    private fun setState(s: DiscordRpcState) {
        state = s
        onStateChanged(s)
    }

    private fun resetLocked() {
        transport = null
        pending = null
        pendingClear = false
        outstandingNonces.clear()
        setState(DiscordRpcState.IDLE)
    }

    private fun sendSetActivity(p: DiscordPresence) =
        send(DiscordRpcCodec.encodeSetActivity(p, pid, appId), "SET_ACTIVITY")

    private fun sendClear() = send(DiscordRpcCodec.encodeClear(pid), "CLEAR")

    private fun send(frame: String, what: String) {
        // Track the nonce only after a successful send, so a failed one isn't left outstanding.
        if (transport?.send(frame) != true) {
            logger.warn("rpc: $what dropped (no connection)")
            return
        }
        DiscordRpcCodec.parseInbound(frame).nonce?.let {
            if (outstandingNonces.size >= MAX_OUTSTANDING_NONCES) {
                outstandingNonces.remove(outstandingNonces.first())
            }
            outstandingNonces.add(it)
        }
        logger.log("rpc: $what sent")
    }

    private companion object {
        const val MAX_OUTSTANDING_NONCES = 64
    }
}
