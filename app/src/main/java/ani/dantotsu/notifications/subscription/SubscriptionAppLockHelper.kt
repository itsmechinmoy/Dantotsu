package ani.dantotsu.notifications.subscription

import ani.dantotsu.others.calc.CalcActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

object SubscriptionAppLockHelper {
    fun isAppLocked(): Boolean {
        return PrefManager.getVal<String>(PrefName.AppPassword).isNotEmpty() && !CalcActivity.hasPermission
    }
}