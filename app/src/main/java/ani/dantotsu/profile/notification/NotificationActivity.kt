package ani.dantotsu.profile.notification

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityNotificationBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.COMMENT
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.MEDIA
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.ONE
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.SUBSCRIPTION
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.USER
import ani.dantotsu.profile.notification.NotificationFragment.Companion.newInstance
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import nl.joery.animatedbottombar.AnimatedBottomBar

class NotificationActivity : AppCompatActivity() {
    lateinit var binding: ActivityNotificationBinding
    private var selected: Int = 0
    lateinit var navBar: AnimatedBottomBar
    private val CommentsEnabled = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1
    private var userCount: Int = 0
    private var mediaCount: Int = 0
    private var subsCount: Int = 0
    private var commentCount: Int = 0
    private var userTab: AnimatedBottomBar.Tab? = null
    private var mediaTab: AnimatedBottomBar.Tab? = null
    private var subsTab: AnimatedBottomBar.Tab? = null
    private var commentTab: AnimatedBottomBar.Tab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.notificationTitle.text = getString(R.string.notifications)
        binding.notificationToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        navBar = binding.notificationNavBar
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        updateCounts()

        val tabs = mutableListOf(
            Pair(R.drawable.ic_round_person_24, "User"),
            Pair(R.drawable.ic_round_movie_filter_24, "Media"),
            Pair(R.drawable.ic_round_notifications_active_24, "Subs")
        )
        if (CommentsEnabled) {
            tabs.add(Pair(R.drawable.ic_round_comment_24, "Comments"))
        }

        tabs.forEachIndexed { index, (icon, title) ->
            val tab = navBar.createTab(icon, title)
            when (index) {
                0 -> {
                    userTab = tab
                    if (userCount > 0) tab.badge = AnimatedBottomBar.Badge("$userCount")
                }
                1 -> {
                    mediaTab = tab
                    if (mediaCount > 0) tab.badge = AnimatedBottomBar.Badge("$mediaCount")
                }
                2 -> {
                    subsTab = tab
                    if (subsCount > 0) tab.badge = AnimatedBottomBar.Badge("$subsCount")
                }
                3 -> {
                    commentTab = tab
                    if (commentCount > 0) tab.badge = AnimatedBottomBar.Badge("$commentCount")
                }
            }
            navBar.addTab(tab)
        }

        binding.notificationBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val getOne = intent.getIntExtra("activityId", -1)
        if (getOne != -1) navBar.isVisible = false
        binding.notificationViewPager.isUserInputEnabled = false
        binding.notificationViewPager.adapter =
            ViewPagerAdapter(supportFragmentManager, lifecycle, getOne, CommentsEnabled) { type, reset ->
                if (reset) {
                    when (type) {
                        USER -> {
                            userCount = 0
                            userTab?.badge = null
                        }
                        MEDIA -> {
                            mediaCount = 0
                            mediaTab?.badge = null
                        }
                        SUBSCRIPTION -> {
                            subsCount = 0
                            subsTab?.badge = null
                        }
                        COMMENT -> {
                            commentCount = 0
                            if (CommentsEnabled) commentTab?.badge = null
                        }
                        ONE -> {}
                    }
                    saveCounts()
                }
            }
        binding.notificationViewPager.setCurrentItem(selected, false)
        navBar.selectTabAt(selected)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                selected = newIndex
                binding.notificationViewPager.setCurrentItem(selected, false)
            }
        })
    }

    private fun updateCounts() {
        userCount = PrefManager.getVal(PrefName.UnreadUserNotifications, 0)
        mediaCount = PrefManager.getVal(PrefName.UnreadMediaNotifications, 0)
        subsCount = PrefManager.getVal(PrefName.UnreadSubscriptionNotifications, 0)
        commentCount = PrefManager.getVal(PrefName.UnreadCommentNotifications, 0)
    }

    private fun saveCounts() {
        PrefManager.setVal(PrefName.UnreadUserNotifications, userCount)
        PrefManager.setVal(PrefName.UnreadMediaNotifications, mediaCount)
        PrefManager.setVal(PrefName.UnreadSubscriptionNotifications, subsCount)
        PrefManager.setVal(PrefName.UnreadCommentNotifications, commentCount)
        Anilist.unreadNotificationCount = subsCount + commentCount
    }

    override fun onResume() {
        super.onResume()
        if (this::navBar.isInitialized) {
            updateCounts()
            if (userCount > 0) userTab?.badge = AnimatedBottomBar.Badge("$userCount") else userTab?.badge = null
            if (mediaCount > 0) mediaTab?.badge = AnimatedBottomBar.Badge("$mediaCount") else mediaTab?.badge = null
            if (subsCount > 0) subsTab?.badge = AnimatedBottomBar.Badge("$subsCount") else subsTab?.badge = null
            if (CommentsEnabled) {
                if (commentCount > 0) commentTab?.badge = AnimatedBottomBar.Badge("$commentCount") else commentTab?.badge = null
            }
            navBar.selectTabAt(selected)
        }
    }

    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        val id: Int = -1,
        val commentsEnabled: Boolean,
        private val countResetCallback: (NotificationFragment.Companion.NotificationType, Boolean) -> Unit
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {
        override fun getItemCount(): Int = if (id != -1) 1 else if (commentsEnabled) 4 else 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> newInstance(if (id != -1) ONE else USER, id, countResetCallback)
            1 -> newInstance(MEDIA, countResetCallback = countResetCallback)
            2 -> newInstance(SUBSCRIPTION, countResetCallback = countResetCallback)
            3 -> newInstance(COMMENT, countResetCallback = countResetCallback)
            else -> newInstance(MEDIA, countResetCallback = countResetCallback)
        }
    }
}