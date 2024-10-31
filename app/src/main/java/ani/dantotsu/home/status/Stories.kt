package ani.dantotsu.home.status

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Activity
import ani.dantotsu.databinding.FragmentStatusBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.home.status.listener.StoriesCallback
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.profile.User
import ani.dantotsu.profile.UsersDialogFragment
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.profile.activity.RepliesBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.AniMarkdown
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs


class Stories @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), View.OnTouchListener {
    private lateinit var binding: FragmentStatusBinding
    private lateinit var activityList: List<Activity>
    private lateinit var storiesListener: StoriesCallback
    private var userClicked: Boolean = false
    private var storyIndex: Int = 1
    private var primaryColor: Int = 0
    private var onPrimaryColor: Int = 0
    private var storyDuration: Int = 6
    private val timer: StoryTimer = StoryTimer(secondsToMillis(storyDuration))

    init {
        initLayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initLayout() {
        val inflater: LayoutInflater = LayoutInflater.from(context)
        binding = FragmentStatusBinding.inflate(inflater, this, false)
        addView(binding.root)

        primaryColor = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        onPrimaryColor = context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)

        if (context is StoriesCallback) storiesListener = context as StoriesCallback

        binding.touchPanel.setOnTouchListener(this)
    }


    fun setStoriesList(
        activityList: List<Activity>, startIndex: Int = 1
    ) {
        this.activityList = activityList
        this.storyIndex = startIndex
        addLoadingViews(activityList)
    }

    private fun addLoadingViews(storiesList: List<Activity>) {
        var idCounter = 1
        for (story in storiesList) {
            binding.progressBarContainer.removeView(findViewWithTag<ProgressBar>("story${idCounter}"))
            val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            progressBar.visibility = View.VISIBLE
            progressBar.id = idCounter
            progressBar.tag = "story${idCounter++}"
            progressBar.progressBackgroundTintList = ColorStateList.valueOf(primaryColor)
            progressBar.progressTintList = ColorStateList.valueOf(onPrimaryColor)
            val params = LayoutParams(0, LayoutParams.WRAP_CONTENT)
            params.marginEnd = 5
            params.marginStart = 5
            binding.progressBarContainer.addView(progressBar, params)
        }

        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.progressBarContainer)

        var counter = storiesList.size
        for (story in storiesList) {
            val progressBar = findViewWithTag<ProgressBar>("story${counter}")
            if (progressBar != null) {
                if (storiesList.size > 1) {
                    when (counter) {
                        storiesList.size -> {
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.END,
                                LayoutParams.PARENT_ID,
                                ConstraintSet.END
                            )
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.TOP,
                                LayoutParams.PARENT_ID,
                                ConstraintSet.TOP
                            )
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.START,
                                getId("story${counter - 1}"),
                                ConstraintSet.END
                            )
                        }

                        1 -> {
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.TOP,
                                LayoutParams.PARENT_ID,
                                ConstraintSet.TOP
                            )
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.START,
                                LayoutParams.PARENT_ID,
                                ConstraintSet.START
                            )
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.END,
                                getId("story${counter + 1}"),
                                ConstraintSet.START
                            )
                        }

                        else -> {
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.TOP,
                                LayoutParams.PARENT_ID,
                                ConstraintSet.TOP
                            )
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.START,
                                getId("story${counter - 1}"),
                                ConstraintSet.END
                            )
                            constraintSet.connect(
                                getId("story${counter}"),
                                ConstraintSet.END,
                                getId("story${counter + 1}"),
                                ConstraintSet.START
                            )
                        }
                    }
                } else {
                    constraintSet.connect(
                        getId("story${counter}"),
                        ConstraintSet.END,
                        LayoutParams.PARENT_ID,
                        ConstraintSet.END
                    )
                    constraintSet.connect(
                        getId("story${counter}"),
                        ConstraintSet.TOP,
                        LayoutParams.PARENT_ID,
                        ConstraintSet.TOP
                    )
                    constraintSet.connect(
                        getId("story${counter}"),
                        ConstraintSet.START,
                        LayoutParams.PARENT_ID,
                        ConstraintSet.START
                    )
                }
            }
            counter--
        }
        constraintSet.applyTo(binding.progressBarContainer)
        startShowContent()
    }

    private fun startShowContent() {
        showStory()
    }

    private fun showStory() {
        if (storyIndex > 1) {
            completeProgressBar(storyIndex - 1)
        }
        val progressBar = findViewWithTag<ProgressBar>("story${storyIndex}")
        binding.androidStoriesLoadingView.visibility = View.VISIBLE
        timer.setOnTimerCompletedListener {
            Logger.log("onAnimationEnd: $storyIndex")
            if (storyIndex - 1 <= activityList.size) {
                Logger.log("userNotClicked: $storyIndex")
                if (storyIndex < activityList.size) {
                    storyIndex += 1
                    showStory()
                } else {
                    // on stories end
                    binding.androidStoriesLoadingView.visibility = View.GONE
                    onStoriesCompleted()
                }
            } else {
                // on stories end
                binding.androidStoriesLoadingView.visibility = View.GONE
                onStoriesCompleted()
            }
        }
        timer.setOnPercentTickListener {
            progressBar.progress = it
        }
        loadStory(activityList[storyIndex - 1])
    }

    private fun getId(tag: String): Int {
        return findViewWithTag<ProgressBar>(tag).id
    }

    private fun secondsToMillis(seconds: Int): Long {
        return seconds.toLong().times(1000)
    }

    private fun resetProgressBar(storyIndex: Int) {
        for (i in storyIndex until activityList.size + 1) {
            val progressBar = findViewWithTag<ProgressBar>("story${i}")
            progressBar?.let {
                it.progress = 0
            }
        }
    }

    private fun completeProgressBar(storyIndex: Int) {
        for (i in 1 until storyIndex + 1) {
            val progressBar = findViewWithTag<ProgressBar>("story${i}")
            progressBar?.let {
                it.progress = 100
            }
        }
    }

    private fun rightPanelTouch() {
        Logger.log("rightPanelTouch: $storyIndex")
        if (storyIndex == activityList.size) {
            completeProgressBar(storyIndex)
            onStoriesCompleted()
            return
        }
        userClicked = true
        timer.cancel()
        if (storyIndex <= activityList.size) storyIndex += 1
        showStory()
    }

    private fun leftPanelTouch() {
        Logger.log("leftPanelTouch: $storyIndex")
        if (storyIndex == 1) {
            onStoriesPrevious()
            return
        }
        userClicked = true
        timer.cancel()
        resetProgressBar(storyIndex)
        if (storyIndex > 1) storyIndex -= 1
        showStory()
    }

    private fun onStoriesCompleted() {
        Logger.log("onStoriesCompleted")
        if (::storiesListener.isInitialized) {
            storyIndex = 1
            storiesListener.onStoriesEnd()
            resetProgressBar(storyIndex)
        }
    }

    private fun onStoriesPrevious() {
        if (::storiesListener.isInitialized) {
            storyIndex = 1
            storiesListener.onStoriesStart()
            resetProgressBar(storyIndex)
        }
    }

    fun pause() {
        timer.pause()
    }

    fun resume() {
        timer.resume()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun loadStory(story: Activity) {
        updateStoryPreferences(story)
        setupUserInfo(story)
        setupTouchListeners()
    
        when (story.typename) {
            "ListActivity" -> handleListActivity(story)
            "TextActivity" -> handleTextActivity(story)
            "MessageActivity" -> handleMessageActivity(story)
        }
    
        setupLikesAndReplies(story)
        binding.androidStoriesLoadingView.visibility = View.GONE
        timer.start()
    }
    
    private fun updateStoryPreferences(story: Activity) {
        val key = "activities"
        val set = PrefManager.getCustomVal<Set<Int>>(key, setOf()).plus(story.id)
        val newList = set.sorted().takeLast(200).toSet()
        PrefManager.setCustomVal(key, newList)
    }
    
    private fun setupUserInfo(story: Activity) {
        binding.statusUserAvatar.loadImage(story.user?.avatar?.large)
        binding.statusUserName.text = story.user?.name
        binding.statusUserTime.text = ActivityItemBuilder.getDateTime(story.createdAt)
    
        binding.statusUserContainer.setOnClickListener {
            ContextCompat.startActivity(
                context,
                Intent(context, ProfileActivity::class.java).putExtra("userId", story.userId),
                null
            )
        }
    }
    
    private fun setupTouchListeners() {
        val touchListener = { v: View, event: MotionEvent ->
            onTouchView(v, event, true)
            v.onTouchEvent(event)
        }
        binding.textActivity.setOnTouchListener(touchListener)
        binding.textActivityContainer.setOnTouchListener(touchListener)
    }
    
    private fun handleListActivity(story: Activity) {
        visible(true)
        val text = "${story.status?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} ${story.progress ?: story.media?.title?.userPreferred} " +
            if (story.status?.contains("completed") == false && !story.status.contains("plans") &&
                !story.status.contains("repeating") && !story.status.contains("paused") &&
                !story.status.contains("dropped")) {
                "of ${story.media?.title?.userPreferred}"
            } else {
                ""
            }
        binding.infoText.text = text
        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        blurImage(
            if (bannerAnimations) binding.contentImageViewKen else binding.contentImageView,
            story.media?.bannerImage ?: story.media?.coverImage?.extraLarge
        )
        binding.coverImage.loadImage(story.media?.coverImage?.extraLarge)
        binding.coverImage.setOnClickListener {
            ContextCompat.startActivity(
                context,
                Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", story.media?.id),
                ActivityOptionsCompat.makeSceneTransitionAnimation(
                    (it.context as FragmentActivity),
                    binding.coverImage,
                    ViewCompat.getTransitionName(binding.coverImage)!!
                ).toBundle()
            )
        }
    }
    
    private fun handleTextActivity(story: Activity) {
        visible(false)
        if (!(context as android.app.Activity).isDestroyed) {
            val markwon = buildMarkwon(context, false)
            markwon.setMarkdown(binding.textActivity, AniMarkdown.getBasicAniHTML(story.text ?: ""))
        }
    }
    
    private fun handleMessageActivity(story: Activity) {
        visible(false)
        if (!(context as android.app.Activity).isDestroyed) {
            val markwon = buildMarkwon(context, false)
            markwon.setMarkdown(binding.textActivity, AniMarkdown.getBasicAniHTML(story.message ?: ""))
        }
    }
    
    private fun setupLikesAndReplies(story: Activity) {
        val userList = story.likes?.map { User(it.id, it.name.toString(), it.avatar?.medium, it.bannerImage) } ?: emptyList()
        val likeColor = ContextCompat.getColor(context, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(context, R.color.bg_opp)
        binding.replyCount.text = story.replyCount.toString()
        binding.activityReplies.setColorFilter(ContextCompat.getColor(context, R.color.bg_opp))
        binding.activityRepliesContainer.setOnClickListener {
            RepliesBottomDialog.newInstance(story.id)
                .show((it.context as FragmentActivity).supportFragmentManager, "replies")
        }
        binding.activityLike.setColorFilter(if (story.isLiked == true) likeColor else notLikeColor)
        binding.activityLikeCount.text = story.likeCount.toString()
        binding.activityLikeContainer.setOnClickListener { like() }
        binding.activityLikeContainer.setOnLongClickListener {
            UsersDialogFragment().apply {
                userList(userList)
                show((it.context as FragmentActivity).supportFragmentManager, "dialog")
            }
            true
        }
    }

    fun like() {
        val story = activityList[storyIndex - 1]
        val likeColor = ContextCompat.getColor(context, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(context, R.color.bg_opp)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val res = Anilist.mutation.toggleLike(story.id, "ACTIVITY")
            withContext(Dispatchers.Main) {
                if (res != null) {
                    if (story.isLiked == true) {
                        story.likeCount = story.likeCount?.minus(1)
                    } else {
                        story.likeCount = story.likeCount?.plus(1)
                    }
                    binding.activityLikeCount.text = (story.likeCount ?: 0).toString()
                    story.isLiked = !story.isLiked!!
                    binding.activityLike.setColorFilter(if (story.isLiked == true) likeColor else notLikeColor)

                } else {
                    snackString("Failed to like activity")
                }
            }
        }
    }
    private var startClickTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var isLongPress = false
    private val swipeThreshold = 100
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        onTouchView(view, event)
        return true
    }
    private fun onTouchView(view: View, event: MotionEvent, isText: Boolean = false) {
    val maxClickDuration = 200
    val screenWidth = view.width
    val leftHalf = screenWidth / 2
    val leftQuarter = screenWidth * 0.15
    val rightQuarter = screenWidth * 0.85
    
    when (event.action) {
        MotionEvent.ACTION_DOWN -> handleActionDown(event)
        MotionEvent.ACTION_MOVE -> handleActionMove(event)
        MotionEvent.ACTION_UP -> handleActionUp(event, isText, maxClickDuration, leftHalf, leftQuarter, rightQuarter)
    }
}

private fun handleActionDown(event: MotionEvent) {
    startX = event.x
    startY = event.y
    startClickTime = Calendar.getInstance().timeInMillis
    pause()
    isLongPress = false
}

private fun handleActionMove(event: MotionEvent) {
    val deltaX = event.x - startX
    val deltaY = event.y - startY
    if (!isLongPress && (abs(deltaX) > swipeThreshold || abs(deltaY) > swipeThreshold)) {
        isLongPress = true
    }
}

private fun handleActionUp(event: MotionEvent, isText: Boolean, maxClickDuration: Int, leftHalf: Int, leftQuarter: Float, rightQuarter: Float) {
    val clickDuration = Calendar.getInstance().timeInMillis - startClickTime
    
    if (clickDuration < maxClickDuration && !isLongPress) {
        if (isText) {
            handleTextAction(event, leftQuarter, rightQuarter)
        } else {
            handleNonTextAction(event, leftHalf)
        }
    } else {
        resume()
    }
    
    handleSwipe(event)
}

private fun handleTextAction(event: MotionEvent, leftQuarter: Float, rightQuarter: Float) {
    when {
        event.x < leftQuarter -> leftPanelTouch()
        event.x > rightQuarter -> rightPanelTouch()
        else -> resume()
    }
}

private fun handleNonTextAction(event: MotionEvent, leftHalf: Int) {
    if (event.x < leftHalf) {
        leftPanelTouch()
    } else {
        rightPanelTouch()
    }
}

private fun handleSwipe(event: MotionEvent) {
    val deltaX = event.x - startX
    val deltaY = event.y - startY
    if (abs(deltaX) > swipeThreshold && abs(deltaY) <= 10) {
        if (deltaX > 0) {
            onStoriesPrevious()
        } else {
            onStoriesCompleted()
        }
    }
}
