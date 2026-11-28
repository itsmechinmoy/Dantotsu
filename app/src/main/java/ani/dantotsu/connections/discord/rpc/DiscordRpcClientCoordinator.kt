package ani.dantotsu.connections.discord.rpc

/** A live RPC connection (wraps the AIDL one so this layer is testable). */
interface DiscordRpcConnection {
    /** False when the binder is dead. */
    fun sendFrame(frame: String): Boolean
    fun disconnect()
}

/** Binds and unbinds the Discord service for a given run. */
interface DiscordRpcBindDelegate {
    /** True if bindService accepted the registration. */
    fun bind(runGen: Int): Boolean
    fun unbind(runGen: Int)
}

interface DiscordRpcScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit): Task
    fun cancel(task: Task)
    interface Task
}

/**
 * Owns bind, connect, run identity and bounded recovery for [DiscordRpcClient].
 * Every callback carries the generation of the run it belongs to; callbacks from an old run
 * are ignored, and a connection delivered to an old run is disconnected right away.
 * Each successful bind is paired with exactly one unbind.
 */
class DiscordRpcClientCoordinator(
    private val pid: Int,
    private val controller: DiscordRpcController,
    private val scheduler: DiscordRpcScheduler,
    private val logger: DiscordRpcLogger,
    private val binder: DiscordRpcBindDelegate,
) {
    private val lifecycle = DiscordRpcLifecycle()
    private val recovery = DiscordRpcRecoveryPolicy()

    private class RunState {
        var gen = 0
        var bindRegistered = false
        var connection: DiscordRpcConnection? = null
    }

    private val lock = Any()
    private var activeRun: RunState? = null
    private var recoveryGen = 0
    private var pendingRecovery: DiscordRpcScheduler.Task? = null
    private var pendingTimeout: DiscordRpcScheduler.Task? = null

    fun requestPresence(presence: DiscordPresence) = synchronized(lock) {
        cancelRecoveryLocked()
        if (activeRun == null) recovery.reset()
        controller.requestPresence(presence)
        // Updates reuse the current connection; bind only when there is none.
        if (activeRun == null) startBindLocked()
    }

    fun clearPresence() = synchronized(lock) {
        cancelRecoveryLocked()
        controller.clearPresence()
    }

    fun shutdown() = synchronized(lock) {
        cancelRecoveryLocked()
        controller.shutdown()
        teardownActiveRunLocked()
    }

    /** [connection] is null when connect() failed, which counts as a loss. */
    fun onServiceConnected(gen: Int, connection: DiscordRpcConnection?) = synchronized(lock) {
        val run = activeRun
        if (run == null || !lifecycle.isActiveRun(gen, run)) {
            connection?.let { runCatching { it.disconnect() } }
            return@synchronized
        }
        if (connection == null) {
            logger.warn("rpc: connect() failed")
            handleLossLocked(gen)
            return@synchronized
        }
        run.connection?.let { runCatching { it.disconnect() } }
        run.connection = connection
        controller.attachTransport { connection.sendFrame(it) }
        controller.onBinderConnected(pid)
        pendingTimeout?.let { scheduler.cancel(it) }
        pendingTimeout = scheduler.postDelayed(ESTABLISH_TIMEOUT_MS) { onEstablishmentTimeout(gen) }
    }

    fun onServiceDisconnected(gen: Int) = handleLoss(gen)
    fun onBindingDied(gen: Int) = handleLoss(gen)
    fun onNullBinding(gen: Int) = handleLoss(gen)
    fun onClose(gen: Int) = handleLoss(gen)

    fun onFrame(gen: Int, frame: String) = synchronized(lock) {
        val run = activeRun ?: return@synchronized
        if (!lifecycle.isActiveRun(gen, run)) return@synchronized
        val wasConnected = controller.getState() == DiscordRpcState.CONNECTED
        controller.onFrame(frame)
        if (wasConnected && controller.getState() == DiscordRpcState.READY) {
            recovery.reset()
            pendingTimeout?.let { scheduler.cancel(it); pendingTimeout = null }
        }
    }

    fun onEstablishmentTimeout(gen: Int) = synchronized(lock) {
        val run = activeRun ?: return@synchronized
        if (!lifecycle.isActiveRun(gen, run)) return@synchronized
        if (controller.getState() == DiscordRpcState.READY) return@synchronized
        handleLossLocked(gen)
    }

    private fun handleLoss(gen: Int) = synchronized(lock) { handleLossLocked(gen) }

    private fun handleLossLocked(gen: Int) {
        val run = activeRun ?: return
        if (!lifecycle.isActiveRun(gen, run)) return
        val needRebind = controller.onBinderLost()
        if (run.bindRegistered) {
            runCatching { binder.unbind(gen) }
            run.bindRegistered = false
        }
        run.connection?.let { runCatching { it.disconnect() } }
        lifecycle.closeRun(run)
        activeRun = null
        pendingTimeout?.let { scheduler.cancel(it); pendingTimeout = null }
        if (needRebind) scheduleRecoveryLocked()
    }

    private fun startBindLocked() {
        if (controller.getState() == DiscordRpcState.CLOSING) return
        val run = RunState()
        run.gen = lifecycle.beginRun(run)
        activeRun = run
        run.bindRegistered = runCatching { binder.bind(run.gen) }.getOrDefault(false)
        if (!run.bindRegistered) {
            handleLossLocked(run.gen)
            return
        }
        pendingTimeout = scheduler.postDelayed(ESTABLISH_TIMEOUT_MS) { onEstablishmentTimeout(run.gen) }
    }

    private fun teardownActiveRunLocked() {
        activeRun?.let { run ->
            if (run.bindRegistered) {
                runCatching { binder.unbind(run.gen) }
                run.bindRegistered = false
            }
            run.connection?.let { runCatching { it.disconnect() } }
            lifecycle.closeRun(run)
            activeRun = null
        }
        pendingTimeout?.let { scheduler.cancel(it); pendingTimeout = null }
        pendingRecovery?.let { scheduler.cancel(it); pendingRecovery = null }
    }

    private fun cancelRecoveryLocked() {
        pendingRecovery?.let { scheduler.cancel(it); pendingRecovery = null }
        recoveryGen += 1
    }

    private fun scheduleRecoveryLocked() {
        if (controller.getState() != DiscordRpcState.BINDING) return
        pendingRecovery?.let { scheduler.cancel(it); pendingRecovery = null }
        if (!recovery.canRetry()) {
            logger.warn("rpc: giving up after ${recovery.attempts()} reconnects")
            controller.reset()
            return
        }
        val myGen = ++recoveryGen
        pendingRecovery = scheduler.postDelayed(RECOVERY_BACKOFF_MS) { runRecovery(myGen) }
    }

    private fun runRecovery(myGen: Int) = synchronized(lock) {
        if (myGen != recoveryGen) return@synchronized
        pendingRecovery = null
        if (controller.getState() != DiscordRpcState.BINDING || activeRun != null) return@synchronized
        recovery.recordAttempt()
        startBindLocked()
    }

    companion object {
        const val ESTABLISH_TIMEOUT_MS = 15_000L
        const val RECOVERY_BACKOFF_MS = 2_000L
    }
}

/** Generation counter plus the identity of the active run; a stale run can't close a newer one. */
class DiscordRpcLifecycle {
    private var generation = 0
    private var activeConn: Any? = null

    @Synchronized
    fun beginRun(connIdentity: Any): Int {
        generation += 1
        activeConn = connIdentity
        return generation
    }

    @Synchronized
    fun currentGeneration(): Int = generation

    @Synchronized
    fun activeConnIdentity(): Any? = activeConn

    @Synchronized
    fun isActiveRun(runGen: Int, connIdentity: Any): Boolean =
        runGen == generation && activeConn === connIdentity

    /** True if this call closed the active run, false if it was already replaced. */
    @Synchronized
    fun closeRun(connIdentity: Any): Boolean {
        if (activeConn !== connIdentity) return false
        activeConn = null
        return true
    }
}

/** Caps reconnect attempts; reset once a connection reaches READY. */
class DiscordRpcRecoveryPolicy(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {
    private var attempts = 0

    @Synchronized
    fun canRetry(): Boolean = attempts < maxAttempts

    @Synchronized
    fun recordAttempt() {
        attempts += 1
    }

    @Synchronized
    fun reset() {
        attempts = 0
    }

    @Synchronized
    fun attempts(): Int = attempts

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
