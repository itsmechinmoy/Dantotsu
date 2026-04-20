package ani.dantotsu.notifications.subscription

import android.content.Context
import ani.dantotsu.others.calc.CalcActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

object SubscriptionAppLockState {
    fun isAppLocked(context: Context): Boolean {
        PrefManager.init(context)
        return PrefManager.getVal<String>(PrefName.AppPassword).isNotEmpty() && !CalcActivity.hasPermission
    }
}
