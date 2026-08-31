package ani.dantotsu.forum

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityForumBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ForumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForumBinding
    private val adapter = GroupieAdapter()
    private var currentPage = 1
    private var hasNextPage = true
    private var isLoading = false
    private var selectedCategoryId: Int? = null
    private var currentSearch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityForumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.forumAppBar.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.forumRecyclerView.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        binding.forumBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.forumRecyclerView.adapter = adapter
        binding.forumRecyclerView.layoutManager = LinearLayoutManager(this)

        binding.forumRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                if (hasNextPage && !isLoading && layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                    loadThreads(false)
                }
            }
        })

        binding.forumSwipeRefresh.setOnRefreshListener {
            loadThreads(true)
        }

        binding.forumCategoryChips.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategoryId = when (checkedIds.firstOrNull()) {
                R.id.chipGeneral -> 1
                R.id.chipAnime -> 2
                R.id.chipManga -> 3
                R.id.chipRelease -> 4
                R.id.chipNews -> 5
                else -> null
            }
            loadThreads(true)
        }

        binding.forumSearch.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Search threads..."
                setText(currentSearch ?: "")
            }
            customAlertDialog().apply {
                setTitle(R.string.search)
                setCustomView(input)
                setPosButton(R.string.search) {
                    currentSearch = input.text.toString().trim().ifBlank { null }
                    loadThreads(true)
                }
                setNeutralButton("Clear") {
                    currentSearch = null
                    loadThreads(true)
                }
                setNegButton(R.string.cancel)
                show()
            }
        }

        binding.forumCreateThreadFab.visibility =
            if (Anilist.token != null) View.VISIBLE else View.GONE
        binding.forumCreateThreadFab.setOnClickListener {
            startActivity(
                Intent(this, ActivityMarkdownCreator::class.java).apply {
                    putExtra("type", "thread")
                }
            )
        }

        loadThreads(true)
    }

    override fun onResume() {
        super.onResume()
        if (adapter.itemCount == 0) {
            loadThreads(true)
        }
    }

    private fun loadThreads(refresh: Boolean) {
        if (isLoading) return
        isLoading = true
        if (refresh) {
            currentPage = 1
            hasNextPage = true
            binding.forumProgressBar.visibility = View.VISIBLE
            binding.forumEmptyText.visibility = View.GONE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val res = Anilist.query.getThreads(
                categoryId = selectedCategoryId,
                search = currentSearch,
                page = currentPage
            )

            withContext(Dispatchers.Main) {
                binding.forumProgressBar.visibility = View.GONE
                binding.forumSwipeRefresh.isRefreshing = false
                isLoading = false

                val threads = res?.data?.page?.threads ?: emptyList()
                hasNextPage = res?.data?.page?.pageInfo?.hasNextPage ?: false

                if (refresh) {
                    adapter.clear()
                }

                if (threads.isNotEmpty()) {
                    val items = threads.map { ForumThreadItem(it, this@ForumActivity) }
                    adapter.addAll(items)
                    currentPage++
                }

                binding.forumEmptyText.visibility =
                    if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        }
    }
}
