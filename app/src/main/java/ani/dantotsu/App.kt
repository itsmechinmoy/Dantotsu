package ani.dantotsu

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RPC
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.others.DisabledReports
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.SettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.FinalExceptionHandler
import ani.dantotsu.util.Logger
import com.google.android.material.color.DynamicColors
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ani.dantotsu.core.metro.GraphProvider
import ani.dantotsu.di.AppGraph
import ani.dantotsu.di.injekt.MetroInteropModule
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get


@SuppressLint("StaticFieldLeak")
class App : Application(), GraphProvider<AppGraph> {

    override val graph: AppGraph by lazy {
        createGraphFactory<AppGraph.Factory>().create(context = this)
    }

    @Inject lateinit var interopModule: MetroInteropModule

    private lateinit var animeExtensionManager: AnimeExtensionManager
    private lateinit var mangaExtensionManager: MangaExtensionManager
    private lateinit var novelExtensionManager: NovelExtensionManager
    private lateinit var torrentServerManager: TorrentServerManager
    private lateinit var downloadAddonManager: DownloadAddonManager

    init {
        instance = this
    }

    val mFTActivityLifecycleCallbacks = FTActivityLifecycleCallbacks()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        PrefManager.init(this)
        graph.inject(this)
        Injekt.importModule(interopModule)

        val crashlytics =
            ani.dantotsu.connections.crashlytics.CrashlyticsFactory.createCrashlytics()
        Injekt.addSingletonFactory<CrashlyticsInterface> { crashlytics }
        crashlytics.initialize(this)
        Logger.init(this)
        Thread.setDefaultUncaughtExceptionHandler(FinalExceptionHandler())
        Logger.log(Log.WARN, "App: Logging started")


        val useMaterialYou: Boolean = PrefManager.getVal(PrefName.UseMaterialYou)
        if (useMaterialYou) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerActivityLifecycleCallbacks(mFTActivityLifecycleCallbacks)

        runCatching {
            leakcanary.LeakCanary.config = leakcanary.LeakCanary.config.copy(
                dumpHeap = false,
                dumpHeapWhenDebugging = false
            )
            leakcanary.AppWatcher.objectWatcher.addOnObjectRetainedListener {
                if (PrefManager.getVal<Boolean>(PrefName.TrackMemoryLeaks)) {
                    val count = PrefManager.getVal<Int>(PrefName.DailyLeakCount) + 1
                    PrefManager.setVal(PrefName.DailyLeakCount, count)
                    Logger.log("LEAK DETECTED: Object retained in memory. Total retained: ${leakcanary.AppWatcher.objectWatcher.retainedObjectCount}, daily count: $count")
                }
            }
        }

        val lastSummary = PrefManager.getVal<Long>(PrefName.LastLeakSummaryTimestamp)
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        if (lastSummary == 0L) {
            PrefManager.setVal(PrefName.LastLeakSummaryTimestamp, now)
        } else if (now - lastSummary >= oneDayMs) {
            val dailyLeaks = PrefManager.getVal<Int>(PrefName.DailyLeakCount)
            if (dailyLeaks > 0 && PrefManager.getVal<Boolean>(PrefName.TrackMemoryLeaks)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val msg = getString(R.string.daily_leaks_summary, dailyLeaks)
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                }, 3000)
            }
            PrefManager.setVal(PrefName.DailyLeakCount, 0)
            PrefManager.setVal(PrefName.LastLeakSummaryTimestamp, now)
        }

        crashlytics.setCrashlyticsCollectionEnabled(!DisabledReports)
        (PrefManager.getVal(PrefName.SharedUserID) as Boolean).let {
            if (!it) return@let
            val dUsername = PrefManager.getVal(PrefName.DiscordUserName, null as String?)
            val aUsername = PrefManager.getVal(PrefName.AnilistUserName, null as String?)
            if (dUsername != null) {
                crashlytics.setCustomKey("dUsername", dUsername)
            }
            if (aUsername != null) {
                crashlytics.setCustomKey("aUsername", aUsername)
            }
        }
        crashlytics.setCustomKey("device Info", SettingsActivity.getDeviceInfo())

        initializeNetwork()

        setupNotificationChannels()
        if (!LogcatLogger.isInstalled) {
            LogcatLogger.install(AndroidLogcatLogger(LogPriority.VERBOSE))
        }

        if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 0) {
            if (BuildConfig.FLAVOR.contains("fdroid")) {
                PrefManager.setVal(PrefName.CommentsEnabled, 2)
            } else {
                PrefManager.setVal(PrefName.CommentsEnabled, 1)
            }
        }

        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        applicationScope.launch(Dispatchers.IO) {
            animeExtensionManager = Injekt.get()
            launch {
                delay(1500)
                animeExtensionManager.findAvailableExtensions()
            }
            AnimeSources.init(animeExtensionManager.installedExtensionsFlow, animeExtensionManager)
        }
        applicationScope.launch(Dispatchers.IO) {
            mangaExtensionManager = Injekt.get()
            launch {
                delay(1500)
                mangaExtensionManager.findAvailableExtensions()
            }
            MangaSources.init(mangaExtensionManager.installedExtensionsFlow, mangaExtensionManager)
        }
        applicationScope.launch(Dispatchers.IO) {
            novelExtensionManager = Injekt.get()
            launch {
                delay(1500)
                novelExtensionManager.findAvailableExtensions()
            }
            NovelSources.init(novelExtensionManager.allInstalledExtensionsFlow)
        }
        applicationScope.launch(Dispatchers.IO) {
            torrentServerManager = Injekt.get()
            downloadAddonManager = Injekt.get()
            if (torrentServerManager.isAvailable()) {
                torrentServerManager.start()
            }
            downloadAddonManager.init()
            if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) {
                CommentsAPI.fetchAuthToken(this@App)
            }

            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            val scheduler = TaskScheduler.create(this@App, useAlarmManager)
            try {
                scheduler.scheduleAllTasks(this@App)
            } catch (e: IllegalStateException) {
                Logger.log("Failed to schedule tasks")
                Logger.log(e)
            }
        }
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            Logger.log("Failed to modify notification channels")
            Logger.log(e)
        }
    }

    fun updateDiscordPresence(activity: Activity) {
        val rpcEnabled = PrefManager.getVal(PrefName.rpcEnabled, true)
        if (!rpcEnabled || Discord.token == null) {
            return
        }

        val incognito = PrefManager.getVal(PrefName.Incognito, false)
        if (incognito) {
            return
        }

        val activityName = activity.javaClass.simpleName
        if (activityName == "ExoplayerView" || activityName == "MangaReaderActivity" || activityName == "NovelReaderActivity") {
            // These activities manage their own Discord presence
            return
        }

        var details = "Browsing"
        var state = "In Menus"
        var largeImage: RPC.Link? = RPC.Link("Dantotsu", Discord.small_Image)

        when {
            activityName == "MainActivity" -> {
                details = "Browsing Home"
            }
            activityName == "MediaDetailsActivity" -> {
                details = "Browsing Info"
                runCatching {
                    val mediaDetailsActivity = activity as? ani.dantotsu.media.MediaDetailsActivity
                    val media = mediaDetailsActivity?.media
                    if (media != null) {
                        details = if (media.anime != null) "Browsing Anime Info" else "Browsing Manga Info"
                        state = media.userPreferredName
                        media.cover?.let {
                            if (it.isNotEmpty()) {
                                largeImage = RPC.Link(media.userPreferredName, it)
                            }
                        }
                    }
                }
            }
            activityName == "SearchActivity" -> {
                details = "Searching Media"
            }
            activityName == "ProfileActivity" -> {
                details = "Viewing Profile"
            }
            activityName.contains("Settings", ignoreCase = true) -> {
                details = "Adjusting Settings"
            }
            else -> {
                details = "Browsing"
            }
        }

        // Otherwise, we are browsing!
        val rpcData = RPC.Companion.RPCData(
            applicationId = Discord.application_Id,
            type = RPC.Type.PLAYING,
            activityName = "Dantotsu",
            details = details,
            state = state,
            largeImage = largeImage,
            buttons = mutableListOf()
        )
        RPCManager.setPresence(activity, rpcData)
    }

    inner class FTActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
        var currentActivity: Activity? = null
        var lastActivity: String? = null
        private var startedActivityCount = 0

        override fun onActivityCreated(p0: Activity, p1: Bundle?) {
            lastActivity = p0.javaClass.simpleName
        }

        override fun onActivityStarted(p0: Activity) {
            currentActivity = p0
            startedActivityCount++
            updateDiscordPresence(p0)
        }

        override fun onActivityResumed(p0: Activity) {
            currentActivity = p0
            updateDiscordPresence(p0)
            applySystemFont(p0)
        }

        override fun onActivityPaused(p0: Activity) {}

        override fun onActivityStopped(p0: Activity) {
            startedActivityCount--
            if (startedActivityCount == 0) {
                RPCManager.clearPresence(p0)
            }
        }

        override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
        override fun onActivityDestroyed(p0: Activity) {
            if (currentActivity === p0) {
                currentActivity = null
            }
        }
    }

    companion object {
        var instance: App? = null

        /** Reference to the application context.
         *
         * USE WITH EXTREME CAUTION!**/
        var context: Context? = null
        fun currentContext(): Context? {
            return instance?.mFTActivityLifecycleCallbacks?.currentActivity ?: context
        }

        fun currentActivity(): Activity? {
            return instance?.mFTActivityLifecycleCallbacks?.currentActivity
        }
    }
}
