package ani.dantotsu.notifications.comment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.dantotsu.notifications.AlarmManagerScheduler
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.runBlocking

class CommentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("CommentNotificationReceiver: onReceive")
        runBlocking {
            CommentNotificationTask().execute(context)
        }
        val commentInterval =
            CommentNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.CommentNotificationInterval)]
        AlarmManagerScheduler(context).scheduleRepeatingTask(
            TaskScheduler.TaskType.COMMENT_NOTIFICATION,
            commentInterval
        )
    }
}