package ani.dantotsu

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateInterpolator
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mapping.IdMapper
import ani.dantotsu.connections.anilist.AnilistHomeViewModel
import ani.dantotsu.databinding.ActivityMainBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.databinding.SplashScreenBinding
import ani.dantotsu.home.AnimeFragment
import ani.dantotsu.home.HomeFragment
import ani.dantotsu.home.LoginFragment
import ani.dantotsu.home.MangaFragment
import ani.dantotsu.home.NoInternet
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.others.calc.CalcActivity
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.profile.activity.FeedActivity
import ani.dantotsu.profile.notification.NotificationActivity
import ani.dantotsu.settings.AddRepositoryBottomSheet
import ani.dantotsu.settings.ExtensionsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefManager.asLiveBool
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.SharedPreferenceBooleanLiveData
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.AudioHelper
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import eu.kanade.domain.source.service.SourcePreferences
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var incognitoLiveData: SharedPreferenceBooleanLiveData
    private val scope = lifecycleScope
    private var load = false


    @kotlin.OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    @OptIn(UnstableApi::class)
    private fun checkLaunchPreconditions(): Boolean {
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
            finish()
            return false
        }
        return true
    }

    private fun checkCalculatorLock() {
        if (!CalcActivity.hasPermission) {
            if (PrefManager.getVal<Boolean>(PrefName.Calculator)) {
                startActivity(Intent(this, CalcActivity::class.java))
                finish()
            }
        }
        if (Intent.ACTION_VIEW == intent.action) {
            CalcActivity.hasPermission = false
        }
    }

    private fun setupBottomNavBarBackground(): Int {
        val bottomNavBar = findViewById<AnimatedBottomBar>(R.id.navbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val backgroundDrawable = bottomNavBar.background as GradientDrawable
            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                backgroundDrawable.setColor(ContextCompat.getColor(this, R.color.nav_dark))
            } else {
                backgroundDrawable.setColor(ContextCompat.getColor(this, R.color.nav_light))
            }
        }
        return try {
            (resources.getDimension(R.dimen.navbar_height) / resources.displayMetrics.density).toInt()
        } catch (_: Exception) {
            48
        }
    }

    private fun setupIncognitoAndRescueIcons(offset: Int) {
        val layoutParams = binding.incognito.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.bottomMargin = navBarHeight + offset
        binding.incognito.layoutParams = layoutParams

        val rescueLayoutParams = binding.rescueModeIcon.layoutParams as ViewGroup.MarginLayoutParams
        rescueLayoutParams.bottomMargin = navBarHeight + offset
        binding.rescueModeIcon.layoutParams = rescueLayoutParams

        fun syncRescueIconMargin(incognitoOn: Boolean) {
            val p = binding.rescueModeIcon.layoutParams as ViewGroup.MarginLayoutParams
            p.marginEnd = ((if (incognitoOn) 44 else 12) * resources.displayMetrics.density).toInt()
            binding.rescueModeIcon.layoutParams = p
        }
        syncRescueIconMargin(PrefManager.getVal(PrefName.Incognito))

        incognitoLiveData = PrefManager.getLiveVal(
            PrefName.Incognito,
            false
        )
        incognitoLiveData.observe(this) {
            syncRescueIconMargin(it)
            if (it) {
                val slideDownAnim = ObjectAnimator.ofFloat(
                    binding.incognito,
                    "translationY",
                    -100f,
                    0f
                )
                slideDownAnim.duration = 500
                slideDownAnim.start()
                binding.incognito.visibility = View.VISIBLE
            } else {
                val slideDownAnim = ObjectAnimator.ofFloat(
                    binding.incognito,
                    "translationY",
                    0f,
                    -100f
                )
                slideDownAnim.duration = 500
                slideDownAnim.start()
                binding.incognito.visibility = View.GONE
            }
        }

        val rescueModeLiveData = PrefManager.getLiveVal(PrefName.RescueMode, false).asLiveBool()
        rescueModeLiveData.observe(this) {
            if (it) {
                syncRescueIconMargin(PrefManager.getVal(PrefName.Incognito))
                val slideDownAnim = ObjectAnimator.ofFloat(
                    binding.rescueModeIcon,
                    "translationY",
                    -100f,
                    0f
                )
                slideDownAnim.duration = 500
                slideDownAnim.start()
                binding.rescueModeIcon.visibility = View.VISIBLE
            } else {
                val slideDownAnim = ObjectAnimator.ofFloat(
                    binding.rescueModeIcon,
                    "translationY",
                    0f,
                    -100f
                )
                slideDownAnim.duration = 500
                slideDownAnim.start()
                binding.rescueModeIcon.visibility = View.GONE
            }
        }

        binding.incognito.setOnClickListener {
            PrefManager.setVal(PrefName.Incognito, false)
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(0, 0)
            finish()
            overridePendingTransition(0, 0)
        }
        incognitoNotification(this)
    }

    private fun setupSplashAnimation() {
        var splash: ActivitySplashBinding? = null
        if (savedInstanceState == null) {
            splash = ActivitySplashBinding.inflate(layoutInflater)
            splash.root.findViewById<ImageView>(R.id.splashImage).apply {
                val splashImages = listOf(
                    R.drawable.splash_0,
                    R.drawable.splash_1,
                    R.drawable.splash_2,
                    R.drawable.splash_3,
                    R.drawable.splash_4,
                    R.drawable.splash_5,
                    R.drawable.splash_6,
                    R.drawable.splash_7,
                    R.drawable.splash_8,
                    R.drawable.splash_9,
                )
                setImageResource(splashImages.random())
            }
            splash.root.findViewById<ImageView>(R.id.splashIcon).apply {
                (drawable as Animatable).start()
            }
            binding.root.addView(splash.root)
            splash.root.updatePadding(
                top = statusBarHeight,
                bottom = navBarHeight
            )
        }

        fun removeSplash() {
            if (splash != null) {
                ObjectAnimator.ofFloat(
                    splash.root,
                    View.TRANSLATION_Y,
                    0f,
                    -splash.root.height.toFloat()
                ).apply {
                    interpolator = AnticipateInterpolator()
                    duration = 200L
                    doOnEnd { binding.root.removeView(splash.root) }
                    start()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                splashScreenView.remove()
                removeSplash()
            }
        } else {
            removeSplash()
        }

        val checkReview = PrefManager.getVal<Boolean>(PrefName.ExtensionsAutoUpdate) &&
                PrefManager.getVal<Boolean>(PrefName.ExtensionsAutoUpdateCheck)
        if (checkReview) {
            snack(
                view = binding.root,
                message = R.string.extensions_auto_update_review,
                anchor = binding.navbar
            )
                ?.setDuration(Snackbar.LENGTH_SHORT)
                ?.setAction(R.string.review) {
                    startActivity(Intent(this, ExtensionsActivity::class.java))
                }
        }
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    private fun setupMainViewPager(fragment: String?, offset: Int) {
        val navbar = findViewById<AnimatedBottomBar>(R.id.navbar)
        val mainViewPager = findViewById<ViewPager2>(R.id.mainViewPager)
        mainViewPager.isUserInputEnabled = false
        mainViewPager.offscreenPageLimit = 4
        mainViewPager.adapter = MainViewPagerAdapter(this)

        navbar.setPadding(0, 0, 0, navBarHeight + offset)
        navbar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                navbar.animate().translationZ(12f).setDuration(200).start()
                selectedOption = newIndex
                mainViewPager.setCurrentItem(newIndex, false)
            }
        })

        val tab = when (fragment) {
            "ANIME" -> 0
            "MANGA" -> 2
            "SETTINGS" -> 3
            else -> 1
        }
        navbar.selectTabAt(tab)
        mainViewPager.setCurrentItem(tab, false)
    }

    private fun handleStartupIntents() {
        val short = intent.getStringExtra("shortcut")
        val navbar = findViewById<AnimatedBottomBar>(R.id.navbar)
        val mainViewPager = findViewById<ViewPager2>(R.id.mainViewPager)
        if (short != null) {
            val tab = when (short) {
                "anime" -> 0
                "manga" -> 2
                "settings" -> 3
                else -> 1
            }
            navbar.selectTabAt(tab)
            mainViewPager.setCurrentItem(tab, false)
        } else {
            val activityId = intent.getIntExtra("activityId", 0)
            if (activityId != 0) {
                if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                    val feedIntent = Intent(this, FeedActivity::class.java).apply {
                        putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
                        putExtra("activityId", activityId)
                    }
                    launched = true
                    startActivity(feedIntent)
                }
            } else {
                handleMediaIntent()
            }
        }
    }

    private fun handleMediaIntent() {
        val offlineMode: Boolean = PrefManager.getVal(PrefName.OfflineMode)
        if (!isOnline(this)) {
            if (!offlineMode) {
                PrefManager.setVal(PrefName.OfflineMode, true)
                snackString(getString(R.string.no_internet_offline_mode))
            }
        } else {
            if (offlineMode) {
                snackString(getString(R.string.offline_mode_active))
            }
        }

        if (intent.data != null) {
            val data = intent.data
            var id: Int? = null
            var authorId: Int? = null
            var characterId: Int? = null
            if (data?.pathSegments?.size ?: 0 > 1) {
                id = data?.pathSegments?.get(1)?.toIntOrNull()
            }
            when (data?.pathSegments?.get(0)) {
                "anime" -> {
                    if (id != null) {
                        val mediaIntent = Intent(this, MediaDetailsActivity::class.java)
                        mediaIntent.putExtra("mediaId", id)
                        mediaIntent.putExtra("anime", true)
                        startActivity(mediaIntent)
                    }
                }
                "manga" -> {
                    if (id != null) {
                        val mediaIntent = Intent(this, MediaDetailsActivity::class.java)
                        mediaIntent.putExtra("mediaId", id)
                        mediaIntent.putExtra("anime", false)
                        startActivity(mediaIntent)
                    }
                }
                "staff" -> {
                    authorId = id
                }
                "character" -> {
                    characterId = id
                }
            }
            if (characterId != null) {
                val charIntent = Intent(this, CharacterDetailsActivity::class.java)
                charIntent.putExtra("characterId", characterId)
                startActivity(charIntent)
            }
            if (authorId != null) {
                val authorIntent = Intent(this, AuthorActivity::class.java)
                authorIntent.putExtra("authorId", authorId)
                startActivity(authorIntent)
            }
        } else {
            lifecycleScope.launch {
                val id = intent.extras?.getString("media")?.toIntOrNull()
                    ?: intent.extras?.getInt("media", 0) ?: 0
                val isMAL = intent.extras?.getBoolean("mal") ?: false
                val cont = intent.extras?.getBoolean("continue") ?: false
                val mediaType = intent.extras?.getString("mediaType")
                if (id != 0) {
                    val media = withContext(Dispatchers.IO) {
                        if (isMAL) {
                            if (mediaType == "MANGA") MAL.getManga(id) else MAL.getAnime(id)
                        } else {
                            Anilist.query.getMedia(id)
                        }
                    }
                    if (media != null) {
                        val mediaIntent = Intent(this@MainActivity, MediaDetailsActivity::class.java)
                        mediaIntent.putExtra("media", media)
                        mediaIntent.putExtra("continue", cont)
                        startActivity(mediaIntent)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(PrefManager.getVal(PrefName.AllowOpeningLinks) as Boolean)) {
                CustomBottomDialog.newInstance().apply {
                    title = "Allow Dantotsu to automatically open Anilist & MAL Links?"
                    val appName = getString(R.string.app_name)
                    val message = "Dantotsu can automatically open links from Anilist & MyAnimeList." +
                            " To enable this feature, please follow the steps below:" +
                            "\n\n1. Tap 'Settings' below." +
                            "\n2. In the settings screen, tap 'Open by default'." +
                            "\n3. Enable 'Open supported links'." +
                            "\n4. Tap 'Supported web addresses' and enable all links." +
                            "\n\nNote: If you have official Anilist or MyAnimeList apps installed," +
                            " you may need to disable 'Open supported links' in their respective settings."
                    val spannable = SpannableStringBuilder(message)
                    val boldSpan = StyleSpan(Typeface.BOLD)
                    spannable.setSpan(
                        boldSpan,
                        message.indexOf(appName),
                        message.indexOf(appName) + appName.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    val regex = Regex("(?<=\\n\\d\\.\\s)[^\\n]+")
                    val matches = regex.findAll(message)
                    for (match in matches) {
                        val start = match.range.first
                        val end = match.range.last + 1
                        spannable.setSpan(
                            StyleSpan(Typeface.BOLD),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    val noteStart = message.indexOf("Note:")
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        noteStart,
                        noteStart + 5,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    this.message = spannable
                    setNegativeButton("Don't ask again") {
                        PrefManager.setVal(PrefName.AllowOpeningLinks, true)
                        dismiss()
                    }
                    setPositiveButton("Settings") {
                        PrefManager.setVal(PrefName.AllowOpeningLinks, true)
                        val intent = Intent(
                            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                        dismiss()
                    }
                }.show(supportFragmentManager, "dialog")
            }
        }
    }

    private fun initPostStartupServices() {
        if (PrefManager.getVal(PrefName.OC)) {
            AudioHelper.run(this, R.raw.audio)
            PrefManager.setVal(PrefName.OC, false)
        }
        val torrentManager = Injekt.get<TorrentServerManager>()
        if (torrentManager.isAvailable() && PrefManager.getVal(PrefName.TorrentEnabled)) {
            launchIO {
                if (!TorrentServerService.isRunning()) {
                    TorrentServerService.start()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!checkLaunchPreconditions()) return

        ThemeManager(this).applyTheme()
        val fragment = intent.getStringExtra("FRAGMENT_CLASS_NAME")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkCalculatorLock()
        val offset = setupBottomNavBarBackground()
        setupIncognitoAndRescueIcons(offset)
        setupSplashAnimation()
        setupMainViewPager(fragment, offset)
        handleStartupIntents()
        initPostStartupServices()
    }

    override fun onRestart() {
        super.onRestart()
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val margin = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 8 else 32
        val params: ViewGroup.MarginLayoutParams =
            binding.includedNavbar.navbar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(bottom = margin.toPx)
    }

    private fun handleViewIntent(intent: Intent) {
        val uri: Uri? = intent.data
        try {
            if (uri == null) {
                throw Exception("Uri is null")
            }
            val isRepoHost = uri.host == "add-repo" || uri.host == "extension-store"
            val schemeLower = uri.scheme?.lowercase() ?: ""
            if (schemeLower == "magnet" || uri.toString().endsWith(".torrent", ignoreCase = true) || intent.type == "application/x-bittorrent") {
                ani.dantotsu.torrent.DirectTorrentBottomSheet.newInstance(uri.toString())
                    .show(supportFragmentManager, "DirectTorrentBottomSheet")
                return
            }
            if (isRepoHost && (schemeLower == "tachiyomi" || schemeLower == "aniyomi" || schemeLower == "novelyomi" || schemeLower == "mihon" || schemeLower == "dantotsu")) {
                val url = uri.getQueryParameter("url") ?: throw Exception("No url for repo import")
                val (prefName, name) = when (schemeLower) {
                    "tachiyomi", "mihon" -> PrefName.MangaExtensionRepos to "Manga"
                    "aniyomi" -> PrefName.AnimeExtensionRepos to "Anime"
                    "novelyomi" -> PrefName.NovelExtensionRepos to "Novel"
                    "dantotsu" -> {
                        val type = uri.getQueryParameter("type")?.lowercase()
                        when (type) {
                            "anime" -> PrefName.AnimeExtensionRepos to "Anime"
                            "novel" -> PrefName.NovelExtensionRepos to "Novel"
                            else -> PrefName.MangaExtensionRepos to "Manga"
                        }
                    }
                    else -> throw Exception("Invalid scheme")
                }
                val savedRepos: Set<String> = PrefManager.getVal(prefName)
                val newRepos = savedRepos.toMutableSet()
                AddRepositoryBottomSheet.addRepoWarning(this) {
                    newRepos.add(url)
                    PrefManager.setVal(prefName, newRepos)
                    toast("$name Extension Repo added")
                }
                return
            }
            if (uri.scheme.equals("dantotsu", ignoreCase = true)) {
                startActivity(Intent(this, ani.dantotsu.connections.anilist.UrlMedia::class.java).setData(uri))
                return
            }
            if (intent.type == null) return
            val jsonString =
                contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Error reading file")
            val name =
                DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
            //.sani is encrypted, .ani is not
            if (name.endsWith(".sani")) {
                passwordAlertDialog { password ->
                    if (password != null) {
                        val salt = jsonString.copyOfRange(0, 16)
                        val encrypted = jsonString.copyOfRange(16, jsonString.size)
                        val decryptedJson = try {
                            PreferenceKeystore.decryptWithPassword(
                                password,
                                encrypted,
                                salt
                            )
                        } catch (e: Exception) {
                            toast("Incorrect password")
                            return@passwordAlertDialog
                        }
                        if (PreferencePackager.unpack(decryptedJson)) {
                            val newIntent = Intent(this, this.javaClass)
                            this.finish()
                            startActivity(newIntent)
                        }
                    } else {
                        toast("Password cannot be empty")
                    }
                }
            } else if (name.endsWith(".ani")) {
                val decryptedJson = jsonString.toString(Charsets.UTF_8)
                if (PreferencePackager.unpack(decryptedJson)) {
                    val newIntent = Intent(this, this.javaClass)
                    this.finish()
                    startActivity(newIntent)
                }
            } else {
                toast("Invalid file type")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast("Error importing settings")
        }
    }

    private fun passwordAlertDialog(callback: (CharArray?) -> Unit) {
        val password = CharArray(16).apply { fill('0') }

        // Inflate the dialog layout
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater).apply {
            userAgentTextBox.hint = "Password"
            subtitle.visibility = View.VISIBLE
            subtitle.text = getString(R.string.enter_password_to_decrypt_file)
        }
        customAlertDialog().apply {
            setTitle("Enter Password")
            setCustomView(dialogView.root)
            setPosButton(R.string.yes) {
                val editText = dialogView.userAgentTextBox
                if (editText.text?.isNotBlank() == true) {
                    editText.text?.toString()?.trim()?.toCharArray(password)
                    callback(password)
                } else {
                    toast("Password cannot be empty")
                }
            }
            setNegButton(R.string.cancel) {
                password.fill('0')
                callback(null)
            }
            show()
        }
    }

    //ViewPager
    private class ViewPagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            val rescueMode = PrefManager.getVal<Boolean>(PrefName.RescueMode)
            when (position) {
                0 -> return AnimeFragment()
                1 -> return if (rescueMode) {
                    val hasMalLogin = PrefManager.getVal(PrefName.MALUserName, null as String?).let { !it.isNullOrBlank() }
                    if (hasMalLogin) HomeFragment() else LoginFragment()
                } else {
                    if (Anilist.token != null) HomeFragment() else LoginFragment()
                }
                2 -> return MangaFragment()
            }
            return LoginFragment()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("goToHome", false)) {
            selectedOption = 1
            binding.includedNavbar.navbar.selectTabAt(1)
            binding.viewpager.setCurrentItem(1, false)
        }
        if (Intent.ACTION_VIEW == intent.action) {
            handleViewIntent(intent)
        }
        handleIncomingExtras(intent)
    }

    private fun handleIncomingExtras(targetIntent: Intent) {
        targetIntent.extras?.let { extras ->
            val fragmentToLoad = extras.getString("FRAGMENT_TO_LOAD")
            val mediaId = extras.getInt("mediaId", -1)
            val commentId = extras.getInt("commentId", -1)
            val activityId = extras.getInt("activityId", -1)

            if (fragmentToLoad != null && mediaId != -1 && commentId != -1) {
                val detailIntent = Intent(this, MediaDetailsActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", fragmentToLoad)
                    putExtra("mediaId", mediaId)
                    putExtra("commentId", commentId)
                }
                startActivity(detailIntent)
                return
            } else if (fragmentToLoad == "FEED" && activityId != -1) {
                if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                    val feedIntent = Intent(this, FeedActivity::class.java).apply {
                        putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
                        putExtra("activityId", activityId)
                    }
                    startActivity(feedIntent)
                    return
                }
            } else if (fragmentToLoad == "NOTIFICATIONS" && activityId != -1) {
                if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                    val notificationIntent = Intent(this, NotificationActivity::class.java).apply {
                        putExtra("activityId", activityId)
                    }
                    startActivity(notificationIntent)
                    return
                }
            }

            val id = extras.getInt("mediaId", 0).takeIf { it != 0 }
                ?: extras.getInt("media", 0)
            val isMAL = extras.getBoolean("mal", false)
            val cont = extras.getBoolean("continue", false)
            val mediaType = extras.getString("mediaType")
            if (id > 0) {
                scope.launch(Dispatchers.IO) {
                    val media = Anilist.query.getMedia(id, isMAL, mediaType)
                    if (media != null) {
                        media.cameFromContinue = cont
                        startActivity(
                            Intent(this@MainActivity, MediaDetailsActivity::class.java)
                                .putExtra("media", media as java.io.Serializable)
                        )
                    } else {
                        snackString(this@MainActivity.getString(R.string.anilist_not_found))
                    }
                }
            }
            val username = extras.getString("username")
            if (username != null) {
                val nameInt = username.toIntOrNull()
                if (nameInt != null) {
                    startActivity(
                        Intent(this@MainActivity, ProfileActivity::class.java)
                            .putExtra("userId", nameInt)
                    )
                } else {
                    startActivity(
                        Intent(this@MainActivity, ProfileActivity::class.java)
                            .putExtra("username", username)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) {
            binding.viewpager.adapter = null
        }
        clearBottomBar()
    }
}
