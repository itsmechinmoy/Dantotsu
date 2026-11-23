package ani.dantotsu.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.media.Media
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.openSettings
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsNotificationActivity : SettingsScreenActivity() {
    override val screen get() = NotificationScreen
}

object NotificationScreen : SettingsScreen(
    "notifications", R.string.notifications, R.drawable.ic_round_notifications_none_24, R.string.notifications_desc,
) {

    override val activity get() = SettingsNotificationActivity::class.java

    private var isImportingLists = false
    private var isImportingStatuses = false

    private fun formatInterval(c: Context, minutes: Long): String {
        if (minutes <= 0) return c.getString(R.string.do_not_update)
        val mins = minutes % 60
        val hours = minutes / 60
        val hoursStr = if (hours > 0) "$hours hrs " else ""
        val minsStr = if (mins > 0) "$mins mins" else ""
        return "$hoursStr$minsStr"
    }

    private fun reschedule(host: SettingsHost) =
        TaskScheduler.create(host.activity, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(host.activity)

    override fun sections() = listOf(
        Section(R.string.subscriptions, listOf(
            choiceRow(PrefName.SubscriptionNotificationInterval, R.string.subscriptions_checking_time,
                R.drawable.ic_round_notifications_none_24, summary = R.string.subscriptions_info,
                labels = { c -> SubscriptionNotificationWorker.checkIntervals.map { formatInterval(c, it) } },
                onChange = { host, _ -> reschedule(host) }).withLongClick { reschedule(it) },
            actionRow("view_subscriptions", R.string.view_subscriptions, R.string.view_subscriptions_desc,
                R.drawable.ic_round_search_24,
                onClick = { host, _ ->
                    SubscriptionsBottomDialog.newInstance(SubscriptionHelper.getSubscriptions())
                        .show(host.activity.supportFragmentManager, "subscriptions")
                }),
            actionRow("import_lists", R.string.import_anilist_lists_to_subscriptions,
                R.string.import_anilist_lists_to_subscriptions_desc, R.drawable.ic_round_playlist_add_24,
                onClick = { host, _ -> importAnilistLists(host) }),
            actionRow("import_statuses", R.string.import_anilist_statuses_to_subscriptions,
                R.string.import_anilist_statuses_to_subscriptions_desc, R.drawable.ic_round_playlist_add_24,
                onClick = { host, _ -> importAnilistStatuses(host) }),
            switchRow(PrefName.SubscriptionCheckingNotifications, R.string.notification_for_checking_subscriptions,
                R.string.notification_for_checking_subscriptions_desc, R.drawable.ic_round_smart_button_24)
                .withLongClick { openSettings(it.activity, null) },
            switchRow(PrefName.SubscriptionPromptAtEnd, R.string.subscription_prompt_at_end,
                R.string.subscription_prompt_at_end_desc, R.drawable.ic_round_smart_button_24),
        )),
        Section(R.string.settings_section_anilist_comments, listOf(
            choiceRow(PrefName.AnilistNotificationInterval, R.string.anilist_notifications_interval,
                R.drawable.ic_round_notifications_none_24, summary = R.string.anilist_notifications_checking_time_desc,
                labels = { c -> AnilistNotificationWorker.checkIntervals.map { formatInterval(c, it) } },
                onChange = { host, _ -> reschedule(host) }),
            actionRow(PrefName.AnilistFilteredTypes.name, R.string.anilist_notification_filters,
                R.string.anilist_notification_filters_desc, R.drawable.ic_anilist,
                keys = listOf(PrefName.AnilistFilteredTypes), onClick = { host, _ -> editFilters(host) }),
            choiceRow(PrefName.CommentNotificationInterval, R.string.comment_notifications_interval,
                R.drawable.ic_round_notifications_none_24, summary = R.string.comment_notification_checking_time_desc,
                labels = { c -> CommentNotificationWorker.checkIntervals.map { formatInterval(c, it) } },
                onChange = { host, _ -> reschedule(host) }),
        )),
        Section(R.string.settings_section_reliability, listOf(
            Row(
                id = PrefName.UseAlarmManager.name, title = R.string.use_alarm_manager_reliable,
                summary = R.string.use_alarm_manager_reliable_desc, icon = R.drawable.ic_anilist,
                keys = listOf(PrefName.UseAlarmManager),
                kind = Kind.Switch(get = { PrefManager.getVal(PrefName.UseAlarmManager) }, set = ::setAlarmManager),
            ),
        )),
    )

    private fun Row.withLongClick(action: (SettingsHost) -> Unit) = Row(
        id = id, title = title, summary = summary, icon = icon, kind = kind, advanced = advanced,
        visible = visible, enabled = enabled, keywords = keywords, keys = keys, extra = extra,
        onLongClick = action,
    )

    /** Turning it on asks first and may open the exact-alarm page; cancel puts the switch back. */
    private fun setAlarmManager(host: SettingsHost, on: Boolean) = with(host.activity) {
        if (on) {
            customAlertDialog().apply {
                setTitle(R.string.use_alarm_manager)
                setMessage(R.string.use_alarm_manager_confirm)
                setPosButton(R.string.use) {
                    PrefManager.setVal(PrefName.UseAlarmManager, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !(getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                    ) {
                        startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                            android.net.Uri.parse("package:$packageName")))
                    }
                    host.refresh()
                }
                setNegButton(R.string.cancel) {
                    PrefManager.setVal(PrefName.UseAlarmManager, false)
                    host.refresh()
                }
                show()
            }
        } else {
            PrefManager.setVal(PrefName.UseAlarmManager, false)
            TaskScheduler.create(this, true).cancelAllTasks()
            TaskScheduler.create(this, false).scheduleAllTasks(this)
        }
    }

    private fun editFilters(host: SettingsHost) = with(host.activity) {
        val types = NotificationType.entries.map { it.name }
        val filteredTypes = PrefManager.getVal<Set<String>>(PrefName.AnilistFilteredTypes).toMutableSet()
        val selected = types.map { filteredTypes.contains(it) }.toBooleanArray()
        customAlertDialog().apply {
            setTitle(R.string.anilist_notification_filters)
            multiChoiceItems(
                types.map { name ->
                    name.replace("_", " ").lowercase().replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                    }
                }.toTypedArray(),
                selected
            ) { updatedSelected ->
                types.forEachIndexed { index, type ->
                    if (updatedSelected[index]) filteredTypes.add(type) else filteredTypes.remove(type)
                }
                PrefManager.setVal(PrefName.AnilistFilteredTypes, filteredTypes)
            }
            show()
        }
    }

    private fun importAnilistLists(host: SettingsHost): Unit = with(host.activity) {
        val context = this
        if (isImportingLists) {
            toast(getString(R.string.loading))
            return@with
        }
        isImportingLists = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userId = Anilist.userid
                    ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull()
                if (userId == null) {
                    withContext(Dispatchers.Main) { toast(getString(R.string.login_to_anilist_first)) }
                    return@launch
                }

                val animeLists = Anilist.query.getMediaLists(true, userId)
                val mangaLists = Anilist.query.getMediaLists(false, userId)

                val selectableLists = linkedMapOf<String, ArrayList<Media>>()
                fun addSelectableLists(prefix: String, lists: MutableMap<String, ArrayList<Media>>) {
                    lists.forEach { (name, media) ->
                        if (name != "All" && name != "Favourites" && media.isNotEmpty()) {
                            selectableLists["$prefix • $name"] = media
                        }
                    }
                }
                addSelectableLists(getString(R.string.anime), animeLists)
                addSelectableLists(getString(R.string.manga), mangaLists)

                if (selectableLists.isEmpty()) {
                    withContext(Dispatchers.Main) { toast(getString(R.string.no_lists_available_to_import)) }
                    return@launch
                }

                val titles = selectableLists.keys.toTypedArray()
                val selected = BooleanArray(titles.size) { false }
                withContext(Dispatchers.Main) {
                    context.customAlertDialog().apply {
                        setTitle(R.string.select_lists_to_import)
                        multiChoiceItems(titles, selected) {}
                        setPosButton(R.string.import_action) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val existingIds = SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                var importedCount = 0
                                titles.forEachIndexed { index, title ->
                                    if (selected[index]) {
                                        selectableLists[title]?.forEach { media ->
                                            if (!existingIds.contains(media.id)) {
                                                existingIds.add(media.id)
                                                importedCount++
                                                SubscriptionHelper.saveSubscription(media, true)
                                            }
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.imported_subscriptions_count, importedCount))
                                    isImportingLists = false
                                }
                            }
                        }
                        setNegButton(R.string.cancel) { isImportingLists = false }
                        show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isImportingLists = false }
            }
        }
    }

    private fun importAnilistStatuses(host: SettingsHost): Unit = with(host.activity) {
        val context = this
        if (isImportingStatuses) {
            toast(getString(R.string.loading))
            return@with
        }
        isImportingStatuses = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userId = Anilist.userid
                    ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull()
                if (userId == null) {
                    withContext(Dispatchers.Main) { toast(getString(R.string.login_to_anilist_first)) }
                    return@launch
                }

                val animeLists = Anilist.query.getMediaLists(true, userId)
                val mangaLists = Anilist.query.getMediaLists(false, userId)
                val animeAll = animeLists["All"] ?: arrayListOf()
                val mangaAll = mangaLists["All"] ?: arrayListOf()

                if (animeAll.isEmpty() && mangaAll.isEmpty()) {
                    withContext(Dispatchers.Main) { toast(getString(R.string.no_lists_available_to_import)) }
                    return@launch
                }

                val statusKeys = resources.getStringArray(R.array.status)
                val animeStatuses = resources.getStringArray(R.array.status_anime)
                val mangaStatuses = resources.getStringArray(R.array.status_manga)
                val count = minOf(statusKeys.size, animeStatuses.size, mangaStatuses.size)
                val titles = ArrayList<String>(count * 2)
                val statusMeta = ArrayList<Pair<Boolean, String>>(count * 2)
                repeat(count) { index ->
                    titles.add("${getString(R.string.anime)} • ${animeStatuses[index]}")
                    statusMeta.add(true to statusKeys[index])
                    titles.add("${getString(R.string.manga)} • ${mangaStatuses[index]}")
                    statusMeta.add(false to statusKeys[index])
                }
                val selected = BooleanArray(titles.size) { false }

                withContext(Dispatchers.Main) {
                    context.customAlertDialog().apply {
                        setTitle(R.string.select_statuses_to_import)
                        multiChoiceItems(titles.toTypedArray(), selected) {}
                        setPosButton(R.string.import_action) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val animeSelected = mutableSetOf<String>()
                                val mangaSelected = mutableSetOf<String>()
                                statusMeta.forEachIndexed { index, (isAnime, status) ->
                                    if (selected[index]) {
                                        if (isAnime) animeSelected.add(status)
                                        else mangaSelected.add(status)
                                    }
                                }
                                val existingIds = SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                var importedCount = 0
                                fun importMedia(list: List<Media>, statuses: Set<String>) {
                                    list.forEach { media ->
                                        if (media.userStatus != null && statuses.contains(media.userStatus)) {
                                            if (!existingIds.contains(media.id)) {
                                                existingIds.add(media.id)
                                                importedCount++
                                                SubscriptionHelper.saveSubscription(media, true)
                                            }
                                        }
                                    }
                                }
                                importMedia(animeAll, animeSelected)
                                importMedia(mangaAll, mangaSelected)
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.imported_subscriptions_count, importedCount))
                                    isImportingStatuses = false
                                }
                            }
                        }
                        setNegButton(R.string.cancel) { isImportingStatuses = false }
                        show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isImportingStatuses = false }
            }
        }
    }
}
