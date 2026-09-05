package ani.dantotsu.notifications.subscription

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("SubscriptionNotificationReceiver: onReceive")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SubscriptionNotificationTask().execute(context)
            } catch (e: Exception) {
                Logger.log(e)
            } finally {
                try {
                    val subscriptionInterval =
                        SubscriptionNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.SubscriptionNotificationInterval)]
                    AlarmManagerScheduler(context).scheduleRepeatingTask(
                        TaskScheduler.TaskType.SUBSCRIPTION_NOTIFICATION,
                        subscriptionInterval
                    )
                } catch (e: Exception) {
                    Logger.log(e)
                }
                pendingResult.finish()
            }
        }
    }
}
