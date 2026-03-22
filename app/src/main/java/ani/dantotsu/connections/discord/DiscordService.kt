package ani.dantotsu.connections.discord

import android.app.Service
import android.content.Intent
import android.os.IBinder
import ani.dantotsu.util.Logger

class DiscordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.log("DiscordService: Started to monitor app lifecycle for RPC cleanup.")
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.log("DiscordService: App swiped from Recents. Clearing RPC immediately.")
        
        RPCManager.clearPresenceOnKill(this)
        
        // DO NOT call stopSelf() here. Calling stopSelf() signals to Android that we are done,
        // causing it to instantly SIGKILL the process before our network request has a chance to execute!
    }
}
