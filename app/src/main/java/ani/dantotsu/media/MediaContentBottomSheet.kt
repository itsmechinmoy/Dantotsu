package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetRecyclerBinding
import ani.dantotsu.databinding.ItemMediaContentBinding
import ani.dantotsu.databinding.ItemWatchOrderBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.others.getSerialized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser

class MediaContentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRecyclerBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRecyclerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mode = arguments?.getString(ARG_MODE) ?: MODE_WATCH_ORDER
        val defaultTitle = if (mode == MODE_WATCH_ORDER) {
            getString(R.string.watch_order)
        } else {
            getString(R.string.news)
        }
        val title = arguments?.getString(ARG_TITLE)?.ifEmpty { defaultTitle } ?: defaultTitle
        val media: Media? = arguments?.getSerialized(ARG_MEDIA) ?: model.getMedia().value

        binding.title.text = title
        binding.subscribeButton.visibility = View.GONE
        binding.replyButton.visibility = View.GONE

        binding.repliesRecyclerView.layoutManager = LinearLayoutManager(context)

        // If items were already provided, display immediately
        val passedWatchOrderItems: ArrayList<MediaDetailsViewModel.WatchOrderItem>? =
            arguments?.getSerialized(ARG_ITEMS)
        val passedNewsItems: ArrayList<MediaDetailsViewModel.NewsItem>? =
            arguments?.getSerialized(ARG_ITEMS)

        if (mode == MODE_WATCH_ORDER && !passedWatchOrderItems.isNullOrEmpty()) {
            displayWatchOrder(passedWatchOrderItems, media)
            return
        } else if (mode == MODE_NEWS && !passedNewsItems.isNullOrEmpty()) {
            displayNews(passedNewsItems)
            return
        }

        // Show loading spinner and fetch in background
        binding.repliesRefresh.visibility = View.VISIBLE
        binding.repliesRecyclerView.visibility = View.GONE
        binding.repliesEmpty.visibility = View.GONE

        if (media == null) {
            binding.repliesRefresh.visibility = View.GONE
            showEmptyState(mode)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            if (mode == MODE_WATCH_ORDER) {
                val items = withContext(Dispatchers.IO) {
                    model.getWatchOrder(media)
                }
                if (_binding == null) return@launch
                binding.repliesRefresh.visibility = View.GONE
                if (items.isEmpty()) {
                    showEmptyState(mode)
                } else {
                    displayWatchOrder(items, media)
                }
            } else {
                val isAnime = media.anime != null || media.format in listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
                val items = withContext(Dispatchers.IO) {
                    if (isAnime) {
                        model.getAnimeNews(media)
                    } else {
                        model.getMangaNovelNews(media)
                    }
                }
                if (_binding == null) return@launch
                binding.repliesRefresh.visibility = View.GONE
                if (items.isEmpty()) {
                    showEmptyState(mode)
                } else {
                    displayNews(items)
                }
            }
        }
    }

    private fun displayWatchOrder(
        items: List<MediaDetailsViewModel.WatchOrderItem>,
        media: Media?
    ) {
        binding.repliesEmpty.visibility = View.GONE
        binding.repliesRecyclerView.visibility = View.VISIBLE
        val adapter = WatchOrderAdapter(items, media)
        binding.repliesRecyclerView.adapter = adapter
        val currentIndex = items.indexOfFirst { it.isCurrent }
        if (currentIndex != -1) {
            val layoutManager = binding.repliesRecyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.scrollToPositionWithOffset(currentIndex, 0)
            binding.repliesRecyclerView.post {
                if (_binding == null) return@post
                (binding.repliesRecyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    private fun displayNews(items: List<MediaDetailsViewModel.NewsItem>) {
        binding.repliesEmpty.visibility = View.GONE
        binding.repliesRecyclerView.visibility = View.VISIBLE
        binding.repliesRecyclerView.adapter = NewsAdapter(items)
    }

    private fun showEmptyState(mode: String) {
        binding.repliesRecyclerView.visibility = View.GONE
        binding.repliesEmpty.visibility = View.VISIBLE
        binding.repliesEmptyText.text = if (mode == MODE_WATCH_ORDER) {
            getString(R.string.no_watch_order_found)
        } else {
            getString(R.string.no_news_found)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class WatchOrderAdapter(
        private val items: List<MediaDetailsViewModel.WatchOrderItem>,
        private val currentMedia: Media?
    ) : RecyclerView.Adapter<WatchOrderAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemWatchOrderBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemWatchOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.binding.root.context

            // Title
            holder.binding.watchOrderTitle.text = item.name

            // Cover Image
            val imageUrl = if (item.image.isNotEmpty()) item.image else currentMedia?.cover
            holder.binding.watchOrderImage.loadImage(imageUrl)

            // Relation Badge
            holder.binding.watchOrderRelation.text = item.relationType.ifEmpty { "Entry" }

            // Current Highlight & Badge
            if (item.isCurrent) {
                holder.binding.watchOrderCurrentBadge.visibility = View.VISIBLE
                val density = context.resources.displayMetrics.density
                holder.binding.watchOrderCard.strokeWidth = (2 * density).toInt()
                val primaryColor = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
                holder.binding.watchOrderCard.strokeColor = primaryColor
                holder.binding.watchOrderCard.setCardBackgroundColor(
                    ColorUtils.setAlphaComponent(primaryColor, 35)
                )
            } else {
                holder.binding.watchOrderCurrentBadge.visibility = View.GONE
                val density = context.resources.displayMetrics.density
                holder.binding.watchOrderCard.strokeWidth = (0.5f * density).toInt()
                holder.binding.watchOrderCard.strokeColor =
                    context.getThemeColor(com.google.android.material.R.attr.colorOutline)
                holder.binding.watchOrderCard.setCardBackgroundColor(
                    context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
                )
            }

            // Metadata: Format
            val format = item.mediaType.ifEmpty { "Anime" }
            holder.binding.watchOrderFormat.text = format

            // Metadata: Air Date / Year
            if (item.airDate.isNotEmpty()) {
                holder.binding.watchOrderAirDate.visibility = View.VISIBLE
                holder.binding.watchOrderDot1.visibility = View.VISIBLE
                holder.binding.watchOrderAirDate.text = item.airDate
            } else {
                holder.binding.watchOrderAirDate.visibility = View.GONE
                holder.binding.watchOrderDot1.visibility = View.GONE
            }

            // Metadata: Episodes
            if (item.episodes.isNotEmpty()) {
                holder.binding.watchOrderEpisodes.visibility = View.VISIBLE
                holder.binding.watchOrderDot2.visibility = View.VISIBLE
                holder.binding.watchOrderEpisodes.text = item.episodes
            } else {
                holder.binding.watchOrderEpisodes.visibility = View.GONE
                holder.binding.watchOrderDot2.visibility = View.GONE
            }



            // Click listener
            holder.binding.watchOrderCard.setOnClickListener {
                if (item.isCurrent) {
                    dismissAllowingStateLoss()
                    return@setOnClickListener
                }
                val anilistId = item.anilistId.toIntOrNull()
                if (anilistId != null && anilistId != 0) {
                    val intent = Intent(requireContext(), MediaDetailsActivity::class.java).apply {
                        putExtra("mediaId", anilistId)
                    }
                    startActivity(intent)
                    dismissAllowingStateLoss()
                } else {
                    val intent = Intent(requireContext(), SearchActivity::class.java).apply {
                        putExtra("type", "ANIME")
                        putExtra("query", item.name)
                        putExtra("search", true)
                    }
                    startActivity(intent)
                    dismissAllowingStateLoss()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class NewsAdapter(
        private val items: List<MediaDetailsViewModel.NewsItem>
    ) : RecyclerView.Adapter<NewsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMediaContentBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemMediaContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val decodedTitle = Parser.unescapeEntities(item.title, false)
            holder.binding.contentTitle.text = decodedTitle

            val timeAgo = item.date?.let { formatTimeAgo(it.time) }
            val readArticle = getString(R.string.read_article)
            holder.binding.contentSubtitle.text = if (timeAgo != null) {
                "$timeAgo • $readArticle"
            } else {
                readArticle
            }
            holder.binding.contentIcon.setImageResource(R.drawable.ic_round_notifications_active_24)

            holder.binding.contentCard.setOnClickListener {
                if (item.url.isNotEmpty()) {
                    runCatching {
                        val uri = Uri.parse(item.url)
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        private fun formatTimeAgo(timeMs: Long): String {
            val diff = System.currentTimeMillis() - timeMs
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                days > 0 -> "${days}d ago"
                hours > 0 -> "${hours}h ago"
                minutes > 0 -> "${minutes}m ago"
                else -> "Just now"
            }
        }
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MODE = "arg_mode"
        private const val ARG_ITEMS = "arg_items"
        private const val ARG_MEDIA = "arg_media"

        const val MODE_WATCH_ORDER = "watch_order"
        const val MODE_NEWS = "news"

        fun newWatchOrderInstance(media: Media): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, MODE_WATCH_ORDER)
                    putSerializable(ARG_MEDIA, media)
                }
            }
        }

        fun newNewsInstance(media: Media): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, MODE_NEWS)
                    putSerializable(ARG_MEDIA, media)
                }
            }
        }

        fun newWatchOrderInstance(
            title: String,
            items: List<MediaDetailsViewModel.WatchOrderItem>
        ): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MODE, MODE_WATCH_ORDER)
                    putSerializable(ARG_ITEMS, ArrayList(items))
                }
            }
        }

        fun newNewsInstance(
            title: String,
            items: List<MediaDetailsViewModel.NewsItem>
        ): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MODE, MODE_NEWS)
                    putSerializable(ARG_ITEMS, ArrayList(items))
                }
            }
        }
    }
}
