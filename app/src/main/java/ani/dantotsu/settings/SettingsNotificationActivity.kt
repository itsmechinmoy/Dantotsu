package ani.dantotsu.settings

import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.databinding.ActivitySettingsNotificationsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.openSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsNotificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsNotificationsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {
            var curTime = PrefManager.getVal<Int>(PrefName.SubscriptionNotificationInterval)
            val timeNames = SubscriptionNotificationWorker.checkIntervals.map {
                val mins = it % 60
                val hours = it / 60
                if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                else getString(R.string.do_not_update)
            }.toTypedArray()
            settingsNotificationsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            notificationSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
            val aTimeNames = AnilistNotificationWorker.checkIntervals.map { it.toInt() }
            val aItems = aTimeNames.map {
                val mins = it % 60
                val hours = it / 60
                if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                else getString(R.string.do_not_update)
            }
            val cTimeNames = CommentNotificationWorker.checkIntervals.map { it.toInt() }
            val cItems = cTimeNames.map {
                val mins = it % 60
                val hours = it / 60
                if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                else getString(R.string.do_not_update)
            }
            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.subscriptions_checking_time_s,
                            timeNames[curTime]
                        ),
                        desc = getString(R.string.subscriptions_info),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.subscriptions_checking_time)
                                singleChoiceItems(timeNames, curTime) { i ->
                                    curTime = i
                                    it.settingsTitle.text = getString(R.string.subscriptions_checking_time_s, timeNames[i])
                                    PrefManager.setVal(PrefName.SubscriptionNotificationInterval, curTime)
                                    TaskScheduler.create(context, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(context)
                                }
                                show()
                            }
                        },
                        onLongClick = {
                            TaskScheduler.create(
                                context, PrefManager.getVal(PrefName.UseAlarmManager)
                            ).scheduleAllTasks(context)
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.view_subscriptions),
                        desc = getString(R.string.view_subscriptions_desc),
                        icon = R.drawable.ic_round_search_24,
                        onClick = {
                            val subscriptions = SubscriptionHelper.getSubscriptions()
                            SubscriptionsBottomDialog.newInstance(subscriptions).show(
                                supportFragmentManager,
                                "subscriptions"
                            )
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.import_anilist_lists_to_subscriptions),
                        desc = getString(R.string.import_anilist_lists_to_subscriptions_desc),
                        icon = R.drawable.ic_round_playlist_add_24,
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val userId = (Anilist.userid
                                    ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull())
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
                                                    toast(
                                                        getString(
                                                            R.string.imported_subscriptions_count,
                                                            importedCount
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        setNegButton(R.string.cancel)
                                        show()
                                    }
                                }
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.import_anilist_statuses_to_subscriptions),
                        desc = getString(R.string.import_anilist_statuses_to_subscriptions_desc),
                        icon = R.drawable.ic_round_playlist_add_24,
                        onClick = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val userId = (Anilist.userid
                                    ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull())
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
                                val count = minOf(
                                    statusKeys.size,
                                    animeStatuses.size,
                                    mangaStatuses.size
                                )
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
                                                val existingIds =
                                                    SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                                var importedCount = 0
                                                fun importMedia(
                                                    list: List<Media>,
                                                    statuses: Set<String>
                                                ) {
                                                    list.forEach { media ->
                                                        if (media.userStatus != null &&
                                                            statuses.contains(media.userStatus)
                                                        ) {
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
                                                    toast(
                                                        getString(
                                                            R.string.imported_subscriptions_count,
                                                            importedCount
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        setNegButton(R.string.cancel)
                                        show()
                                    }
                                }
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.anilist_notification_filters),
                        desc = getString(R.string.anilist_notification_filters_desc),
                        icon = R.drawable.ic_anilist,
                        onClick = {
                            val types = NotificationType.entries.map { it.name }
                            val filteredTypes =
                                PrefManager.getVal<Set<String>>(PrefName.AnilistFilteredTypes)
                                    .toMutableSet()
                            val selected = types.map { filteredTypes.contains(it) }.toBooleanArray()
                            context.customAlertDialog().apply {
                                 setTitle(R.string.anilist_notification_filters)
                                 multiChoiceItems(
                                    types.map { name ->
                                        name.replace("_", " ").lowercase().replaceFirstChar {
                                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                                    } }.toTypedArray(),
                                    selected
                                ) { updatedSelected ->
                                types.forEachIndexed { index, type ->
                                    if (updatedSelected[index]) {
                                        filteredTypes.add(type)
                                    } else {
                                        filteredTypes.remove(type)
                                    }
                                }
                                    PrefManager.setVal(PrefName.AnilistFilteredTypes, filteredTypes)
                                }
                                show()
                            }
                        }

                    ),
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.anilist_notifications_checking_time,
                            aItems[PrefManager.getVal(PrefName.AnilistNotificationInterval)]
                        ),
                        desc = getString(R.string.anilist_notifications_checking_time_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                 setTitle(R.string.subscriptions_checking_time)
                                 singleChoiceItems(
                                    aItems.toTypedArray(),
                                    PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval)
                                ) { i ->
                                    PrefManager.setVal(PrefName.AnilistNotificationInterval, i)
                                    it.settingsTitle.text =
                                        getString(
                                            R.string.anilist_notifications_checking_time,
                                            aItems[i]
                                        )
                                    TaskScheduler.create(
                                        context, PrefManager.getVal(PrefName.UseAlarmManager)
                                    ).scheduleAllTasks(context)
                                }
                                show()
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.comment_notification_checking_time,
                            cItems[PrefManager.getVal(PrefName.CommentNotificationInterval)]
                        ),
                        desc = getString(R.string.comment_notification_checking_time_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                 setTitle(R.string.subscriptions_checking_time)
                                 singleChoiceItems(
                                    cItems.toTypedArray(),
                                    PrefManager.getVal<Int>(PrefName.CommentNotificationInterval)
                                ) {  i ->
                                    PrefManager.setVal(PrefName.CommentNotificationInterval, i)
                                    it.settingsTitle.text =
                                        getString(
                                            R.string.comment_notification_checking_time,
                                            cItems[i]
                                        )
                                    TaskScheduler.create(
                                        context, PrefManager.getVal(PrefName.UseAlarmManager)
                                    ).scheduleAllTasks(context)
                                }
                                show()
                            }
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.notification_for_checking_subscriptions),
                        desc = getString(R.string.notification_for_checking_subscriptions_desc),
                        icon = R.drawable.ic_round_smart_button_24,
                        isChecked = PrefManager.getVal(PrefName.SubscriptionCheckingNotifications),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(
                                PrefName.SubscriptionCheckingNotifications,
                                isChecked
                            )
                        },
                        onLongClick = {
                            openSettings(context, null)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.use_alarm_manager_reliable),
                        desc = getString(R.string.use_alarm_manager_reliable_desc),
                        icon = R.drawable.ic_anilist,
                        isChecked = PrefManager.getVal(PrefName.UseAlarmManager),
                        switch = { isChecked, view ->
                            if (isChecked) {
                                context.customAlertDialog().apply {
                                     setTitle(R.string.use_alarm_manager)
                                     setMessage(R.string.use_alarm_manager_confirm)
                                     setPosButton(R.string.use) {
                                        PrefManager.setVal(PrefName.UseAlarmManager, true)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            if (!(getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
                                                val intent =
                                                    Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                                                startActivity(intent)
                                                view.settingsButton.isChecked = true
                                            }
                                        }
                                    }
                                    setNegButton(R.string.cancel) {
                                        view.settingsButton.isChecked = false
                                        PrefManager.setVal(PrefName.UseAlarmManager, false)
                                    }
                                    show()
                                }
                            } else {
                                PrefManager.setVal(PrefName.UseAlarmManager, false)
                                TaskScheduler.create(context, true).cancelAllTasks()
                                TaskScheduler.create(context, false)
                                    .scheduleAllTasks(context)
                            }
                        },
                    ),
                )
            )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
        }
    }
}