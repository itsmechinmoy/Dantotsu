package ani.dantotsu.connections.discord.rpc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import ani.dantotsu.util.Logger
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcService
import java.util.concurrent.atomic.AtomicReference

/**
 * Rich Presence through the installed Discord app's RPC service (Discord Social SDK).
 * No login or token: Discord shows the activity on whatever account is signed in there.
 * Binder callbacks are moved to the main thread and handed to [DiscordRpcClientCoordinator].
 */
class DiscordRpcClient(
    private val context: Context,
    private val appId: Long,
) : DiscordRpcBindDelegate, DiscordRpcScheduler {

    private val handler = Handler(Looper.getMainLooper())
    private val logger = object : DiscordRpcLogger {
        override fun log(message: String) = Logger.log(message)
        override fun warn(message: String) = Logger.log(message)
    }
    private val coordinator = DiscordRpcClientCoordinator(
        pid = Process.myPid(),
        controller = DiscordRpcController(appId, logger),
        scheduler = this,
        logger = logger,
        binder = this,
    )

    private class Holder(val gen: Int, val conn: ServiceConnection)

    private val active = AtomicReference<Holder?>(null)

    fun setPresence(presence: DiscordPresence) = coordinator.requestPresence(presence)
    fun clearPresence() = coordinator.clearPresence()
    fun shutdown() = coordinator.shutdown()

    override fun bind(runGen: Int): Boolean {
        val app = context.applicationContext
        if (!isAvailable(app)) {
            logger.log("rpc: Discord app not installed")
            return false
        }
        val callback = object : IDiscordRpcCallback.Stub() {
            override fun onFrame(frame: String) {
                handler.post { coordinator.onFrame(runGen, frame) }
            }

            override fun onClose(code: Int, reason: String) {
                handler.post { coordinator.onClose(runGen) }
            }
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, svc: IBinder) {
                if (active.get()?.gen != runGen) return
                val rpc = runCatching {
                    IDiscordRpcService.Stub.asInterface(svc).connect(appId, "", callback)
                }.getOrNull()
                val wrapped = rpc?.let { c ->
                    object : DiscordRpcConnection {
                        override fun sendFrame(frame: String): Boolean =
                            runCatching { c.sendFrame(frame) }.isSuccess

                        override fun disconnect() {
                            runCatching { c.disconnect() }
                        }
                    }
                }
                handler.post { coordinator.onServiceConnected(runGen, wrapped) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                handler.post { coordinator.onServiceDisconnected(runGen) }
            }

            override fun onBindingDied(name: ComponentName) {
                handler.post { coordinator.onBindingDied(runGen) }
            }

            override fun onNullBinding(name: ComponentName) {
                handler.post { coordinator.onNullBinding(runGen) }
            }
        }
        val holder = Holder(runGen, conn)
        active.set(holder)
        val ok = runCatching { app.bindService(intent(), conn, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!ok) active.compareAndSet(holder, null)
        return ok
    }

    override fun unbind(runGen: Int) {
        val holder = active.get() ?: return
        if (holder.gen == runGen && active.compareAndSet(holder, null)) {
            runCatching { context.applicationContext.unbindService(holder.conn) }
        }
    }

    private class AndroidTask(val runnable: Runnable) : DiscordRpcScheduler.Task

    override fun postDelayed(delayMs: Long, block: () -> Unit): DiscordRpcScheduler.Task {
        val r = Runnable { block() }
        handler.postDelayed(r, delayMs)
        return AndroidTask(r)
    }

    override fun cancel(task: DiscordRpcScheduler.Task) {
        (task as? AndroidTask)?.let { handler.removeCallbacks(it.runnable) }
    }

    companion object {
        private const val ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
        private const val PACKAGE = "com.discord"
        private const val SERVICE = "com.discord.socialrpc.DiscordRpcService"

        private fun intent() = Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE))

        /** True if an installed Discord app exposes the RPC service. */
        fun isAvailable(context: Context): Boolean =
            context.packageManager.queryIntentServices(intent(), 0).isNotEmpty()
    }
}
