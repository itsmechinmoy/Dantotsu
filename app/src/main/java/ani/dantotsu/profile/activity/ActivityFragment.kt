package ani.dantotsu.profile.activity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Activity
import ani.dantotsu.databinding.FragmentFeedBinding
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.util.ActivityMarkdownCreator
import com.xwray.groupie.GroupieAdapter
import eu.kanade.tachiyomi.util.system.getSerializableCompat
import kotlinx.coroutines.launch

class ActivityFragment : Fragment() {
    private lateinit var type: ActivityType
    private var userId: Int? = null
    private var activityId: Int? = null
    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private var adapter: GroupieAdapter = GroupieAdapter()
    private var page: Int = 1
    private var allActivities: MutableList<Activity> = mutableListOf()
    private var currentFilter: ActivityFilterType = ActivityFilterType.ALL
    private var hasMoreActivities: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.listRecyclerView?.adapter = null
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            type = it.getSerializableCompat<ActivityType>("type") as ActivityType
            userId = it.getInt("userId")
            activityId = it.getInt("activityId")
        }
        binding.titleBar.visibility =
            if (type != ActivityType.ONE) View.VISIBLE else View.GONE
        binding.titleText.text = when (type) {
            ActivityType.OTHER_USER -> if (userId == Anilist.userid) getString(R.string.create_new_activity) else getString(R.string.write_a_message)
            ActivityType.USER -> getString(R.string.create_new_activity)
            ActivityType.GLOBAL -> getString(R.string.filter_activity)
            ActivityType.ONE -> ""
        }
        binding.titleImage.visibility = when (type) {
            ActivityType.OTHER_USER -> View.VISIBLE
            ActivityType.USER -> if (Anilist.token != null) View.VISIBLE else View.GONE
            else -> View.GONE
        }
        
        // Set up filter icon visibility
        binding.filterButton.visibility = if (type != ActivityType.ONE) View.VISIBLE else View.GONE
        binding.filterButton.setOnClickListener {
            showFilterBottomSheet()
        }
        
        binding.titleImage.setOnClickListener { handleTitleImageClick() }
        binding.listRecyclerView.adapter = adapter
        binding.listRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.listProgressBar.isVisible = true

        binding.feedRefresh.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.emptyTextView.text = getString(R.string.nothing_here)
        viewLifecycleOwner.lifecycleScope.launch {
            getList()
            val currentBinding = _binding ?: return@launch
            if (adapter.itemCount == 0) {
                currentBinding.emptyTextView.isVisible = true
            }
            currentBinding.listProgressBar.isVisible = false
        }
        binding.feedSwipeRefresh.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                adapter.clear()
                allActivities.clear()
                page = 1
                hasMoreActivities = true
                getList()
                _binding?.feedSwipeRefresh?.isRefreshing = false
            }
        }
        binding.listRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (shouldLoadMore()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        _binding?.feedRefresh?.isVisible = true
                        getList()
                        _binding?.feedRefresh?.isVisible = false
                    }
                }
            }
        })
    }

    private fun showFilterBottomSheet() {
        ActivityFilterBottomSheet.newInstance(currentFilter) { filterType ->
            currentFilter = filterType
            viewLifecycleOwner.lifecycleScope.launch {
                _binding?.listProgressBar?.isVisible = true
                adapter.clear()
                allActivities.clear()
                page = 1
                hasMoreActivities = true
                getList()
                _binding?.listProgressBar?.isVisible = false
            }
        }.show(childFragmentManager, "ActivityFilterBottomSheet")
    }

    private fun applyFilter() {
        val filteredActivities = getFilteredActivities()
        
        adapter.clear()
        adapter.addAll(filteredActivities.map { ActivityItem(it, adapter, ::onActivityClick) })
        
        binding.emptyTextView.isVisible = filteredActivities.isEmpty()
        binding.emptyTextView.text = when (currentFilter) {
            ActivityFilterType.ALL -> getString(R.string.nothing_here)
            ActivityFilterType.TEXT -> getString(R.string.no_text_activities)
            ActivityFilterType.ANIME_PROGRESS -> getString(R.string.no_anime_progress)
            ActivityFilterType.MANGA_PROGRESS -> getString(R.string.no_manga_progress)
            ActivityFilterType.ALL_PROGRESS -> getString(R.string.no_all_progress)
            ActivityFilterType.MESSAGES -> getString(R.string.no_messages)
            ActivityFilterType.PINNED -> getString(R.string.no_pinned_activities)
            ActivityFilterType.SUBSCRIBED -> getString(R.string.no_subscribed_activities)
        }
    }

    private fun handleTitleImageClick() {
        val intent = Intent(context, ActivityMarkdownCreator::class.java).apply {
            putExtra("type", if (userId == null || userId == Anilist.userid) "activity" else "message")
            putExtra("userId", userId ?: Anilist.userid)
        }
        ContextCompat.startActivity(requireContext(), intent, null)
    }

    private suspend fun getList() {
        val maxPagesPerRequest = 10
        var pagesFetched = 0
        val initialFilteredCount = getFilteredActivities().size
        var currentFilteredCount = initialFilteredCount
        do {
            pagesFetched++
            val list = when (type) {
                ActivityType.GLOBAL -> getActivities(global = true)
                ActivityType.USER -> getActivities(filter = true)
                ActivityType.OTHER_USER -> getActivities(userId = userId)
                ActivityType.ONE -> getActivities(activityId = activityId)
            }
            allActivities.addAll(list)
            currentFilteredCount = getFilteredActivities().size
        } while (
            currentFilter != ActivityFilterType.ALL &&
            hasMoreActivities &&
            pagesFetched < maxPagesPerRequest &&
            currentFilteredCount == initialFilteredCount
        )
        applyFilter()
    }

    private fun getFilteredActivities(): List<Activity> {
        return when (currentFilter) {
            ActivityFilterType.ALL -> allActivities
            ActivityFilterType.TEXT -> allActivities.filter {
                it.typename == "TextActivity" || it.type == "TEXT"
            }
            ActivityFilterType.ANIME_PROGRESS -> allActivities.filter {
                it.type == "ANIME_LIST"
            }
            ActivityFilterType.MANGA_PROGRESS -> allActivities.filter {
                it.type == "MANGA_LIST"
            }
            ActivityFilterType.ALL_PROGRESS -> allActivities.filter {
                it.type == "ANIME_LIST" || it.type == "MANGA_LIST" || it.typename == "ListActivity"
            }
            ActivityFilterType.MESSAGES -> allActivities.filter {
                it.typename == "MessageActivity" || it.type == "MESSAGE"
            }
            ActivityFilterType.PINNED -> allActivities.filter {
                it.isPinned == true
            }
            ActivityFilterType.SUBSCRIBED -> allActivities.filter {
                it.isSubscribed == true
            }
        }
    }

    private suspend fun getActivities(
        global: Boolean = false,
        userId: Int? = null,
        activityId: Int? = null,
        filter: Boolean = false
    ): List<Activity> {
        val pageData = Anilist.query.getFeed(userId, global, page, activityId)?.data?.page
        val res = pageData?.activities
        hasMoreActivities = pageData?.pageInfo?.hasNextPage ?: false
        if (hasMoreActivities) {
            page += 1
        }
        return res
            ?.filter { if (Anilist.adult) true else it.media?.isAdult != true }
            ?.filterNot { it.recipient?.id != null && it.recipient.id != Anilist.userid && filter }
            ?: emptyList()
    }

    private fun shouldLoadMore(): Boolean {
        val layoutManager =
            (binding.listRecyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
        val adapter = binding.listRecyclerView.adapter
        return hasMoreActivities &&
                !binding.listRecyclerView.canScrollVertically(1) &&
                !binding.feedRefresh.isVisible && adapter?.itemCount != 0 &&
                layoutManager == (adapter!!.itemCount - 1)

    }

    private fun onActivityClick(id: Int, type: String) {
        val intent = when (type) {
            "USER" -> Intent(requireContext(), ProfileActivity::class.java).putExtra("userId", id)
            "MEDIA" -> Intent(
                requireContext(),
                MediaDetailsActivity::class.java
            ).putExtra("mediaId", id)

            else -> return
        }
        ContextCompat.startActivity(requireContext(), intent, null)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.root.requestLayout()
        }
    }

    companion object {
        enum class ActivityType { GLOBAL, USER, OTHER_USER, ONE }

        fun newInstance(
            type: ActivityType,
            userId: Int? = null,
            activityId: Int? = null
        ): ActivityFragment {
            return ActivityFragment().apply {
                arguments = Bundle().apply {
                    putSerializable("type", type)
                    userId?.let { putInt("userId", it) }
                    activityId?.let { putInt("activityId", it) }
                }
            }
        }
    }
}
