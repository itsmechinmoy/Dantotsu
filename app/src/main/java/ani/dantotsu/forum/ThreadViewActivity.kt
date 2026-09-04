package ani.dantotsu.forum

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.ForumThread
import ani.dantotsu.databinding.ActivityThreadViewBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.AniMarkdown
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadViewBinding
    private val commentsAdapter = GroupieAdapter()
    private var threadId: Int = -1
    private var thread: ForumThread? = null
    private var currentPage = 1
    private var hasNextPage = true
    private var isSubscribed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityThreadViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.threadAppBar.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.threadReplyFab.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        threadId = intent.getIntExtra("threadId", -1)
        if (threadId == -1) {
            finish()
            return
        }

        binding.threadBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.commentsRecyclerView.adapter = commentsAdapter
        binding.commentsRecyclerView.layoutManager = LinearLayoutManager(this)

        binding.threadReplyFab.visibility =
            if (Anilist.token != null) View.VISIBLE else View.GONE
        binding.threadReplyFab.setOnClickListener {
            startActivity(
                Intent(this, ActivityMarkdownCreator::class.java).apply {
                    putExtra("type", "threadComment")
                    putExtra("threadId", threadId)
                }
            )
        }

        binding.threadSubscribe.setOnClickListener {
            val newSub = !isSubscribed
            lifecycleScope.launch(Dispatchers.IO) {
                val success = Anilist.mutation.toggleThreadSubscription(threadId, newSub)
                withContext(Dispatchers.Main) {
                    if (success) {
                        isSubscribed = newSub
                        binding.threadSubscribe.alpha = if (isSubscribed) 1.0f else 0.5f
                        snackString(if (isSubscribed) "Subscribed to thread" else "Unsubscribed from thread")
                    } else {
                        snackString("Failed to update thread subscription")
                    }
                }
            }
        }

        loadThreadDetails()
        loadComments(true)
    }

    override fun onResume() {
        super.onResume()
        if (thread != null) {
            loadComments(true)
        }
    }

    private fun loadThreadDetails() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = Anilist.query.getThreadDetails(threadId)
            val t = res?.data?.thread
            withContext(Dispatchers.Main) {
                if (t != null) {
                    thread = t
                    bindThread(t)
                }
            }
        }
    }

    private fun bindThread(t: ForumThread) {
        binding.threadBarTitle.text = t.title ?: ""
        binding.threadViewTitle.text = t.title ?: ""
        binding.threadAuthorAvatar.loadImage(t.user?.avatar?.medium)
        binding.threadAuthorName.text = t.user?.name ?: "Unknown"
        binding.threadTime.text = t.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""

        isSubscribed = t.isSubscribed ?: false
        binding.threadSubscribe.alpha = if (isSubscribed) 1.0f else 0.5f

        val likeColor = ContextCompat.getColor(this, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(this, R.color.bg_opp)
        binding.threadLikeIcon.setColorFilter(if (t.isLiked == true) likeColor else notLikeColor)
        binding.threadLikeCount.text = (t.likeCount ?: 0).toString()

        binding.threadLikeButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val success = Anilist.mutation.toggleLike(t.id, "THREAD") != null
                withContext(Dispatchers.Main) {
                    if (success) {
                        t.isLiked = !(t.isLiked ?: false)
                        val count = t.likeCount ?: 0
                        t.likeCount = if (t.isLiked == true) count + 1 else (count - 1).coerceAtLeast(0)
                        binding.threadLikeCount.text = (t.likeCount ?: 0).toString()
                        binding.threadLikeIcon.setColorFilter(if (t.isLiked == true) likeColor else notLikeColor)
                    }
                }
            }
        }

        if (t.userId == Anilist.userid) {
            binding.threadDelete.visibility = View.VISIBLE
            binding.threadDelete.setOnClickListener {
                customAlertDialog().apply {
                    setTitle(R.string.delete)
                    setMessage(R.string.delete_thread_confirm)
                    setPosButton(R.string.delete) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = Anilist.mutation.deleteThread(t.id)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    snackString("Thread deleted")
                                    finish()
                                } else {
                                    snackString("Failed to delete thread")
                                }
                            }
                        }
                    }
                    setNegButton(R.string.cancel)
                    show()
                }
            }
        }

        binding.threadWebView.settings.apply {
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        binding.threadWebView.setInitialScale(1)
        binding.threadWebView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                android.R.color.transparent
            )
        )
        binding.threadWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.threadWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.threadWebView.setBackgroundColor(
                    ContextCompat.getColor(
                        this@ThreadViewActivity,
                        android.R.color.transparent
                    )
                )
            }
        }
        val styledHtml = AniMarkdown.getFullAniHTML(
            t.body ?: "",
            ContextCompat.getColor(this, R.color.bg_opp)
        )
        binding.threadWebView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
    }

    private fun loadComments(refresh: Boolean) {
        if (refresh) {
            currentPage = 1
            binding.commentsProgressBar.visibility = View.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val res = Anilist.query.getThreadComments(threadId, currentPage)
            withContext(Dispatchers.Main) {
                binding.commentsProgressBar.visibility = View.GONE
                val comments = res?.data?.page?.threadComments ?: emptyList()
                hasNextPage = res?.data?.page?.pageInfo?.hasNextPage ?: false

                if (refresh) {
                    commentsAdapter.clear()
                }

                if (comments.isNotEmpty()) {
                    val items = comments.map {
                        ThreadCommentItem(it, threadId, this@ThreadViewActivity, commentsAdapter, lifecycleScope)
                    }
                    commentsAdapter.addAll(items)
                    currentPage++
                }
            }
        }
    }
}
