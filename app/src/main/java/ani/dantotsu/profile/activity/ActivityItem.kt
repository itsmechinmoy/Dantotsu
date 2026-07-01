package ani.dantotsu.profile.activity

import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Activity
import ani.dantotsu.databinding.ItemActivityBinding
import ani.dantotsu.home.status.AnilistLinkPreviewView
import ani.dantotsu.loadImage
import ani.dantotsu.profile.User
import ani.dantotsu.profile.UsersDialogFragment
import ani.dantotsu.setAnimation
import ani.dantotsu.snackString
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.AniMarkdown.Companion.getBasicAniHTML
import ani.dantotsu.util.AnilistLinkParser
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.isNotEmpty

class ActivityItem(
    private val activity: Activity,
    private val parentAdapter: GroupieAdapter,
    val clickCallback: (Int, type: String) -> Unit,
) : BindableItem<ItemActivityBinding>() {
    private lateinit var binding: ItemActivityBinding
    private var previewJob: Job? = null

    override fun bind(viewBinding: ItemActivityBinding, position: Int) {
        binding = viewBinding
        // Cancel any in-flight preview fetch from a previous bind (recycled view)
        previewJob?.cancel()
        previewJob = null
        binding.activityLinkPreviewContainer.removeAllViews()
        binding.activityLinkPreviewContainer.visibility = View.GONE
        val context = binding.root.context
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        setAnimation(binding.root.context, binding.root)
        binding.activityUserName.text = activity.user?.name ?: activity.messenger?.name
        binding.activityUserAvatar.loadImage(
            activity.user?.avatar?.medium ?: activity.messenger?.avatar?.medium
        )
        binding.activityTime.text = ActivityItemBuilder.getDateTime(activity.createdAt)
        val likeColor = ContextCompat.getColor(binding.root.context, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(binding.root.context, R.color.bg_opp)
        binding.activityLike.setColorFilter(if (activity.isLiked == true) likeColor else notLikeColor)
        val userList = arrayListOf<User>()
        activity.likes?.forEach { i ->
            userList.add(User(i.id, i.name.toString(), i.avatar?.medium, i.bannerImage, isFollowing = i.isFollowing, isFollower = i.isFollower))
        }
        binding.activityRepliesContainer.setOnClickListener {
            RepliesBottomDialog.newInstance(activity.id)
                .show((context as FragmentActivity).supportFragmentManager, "replies")
        }
        binding.replyCount.text = activity.replyCount.toString()
        binding.activityReplies.setColorFilter(
            ContextCompat.getColor(
                binding.root.context,
                R.color.bg_opp
            )
        )
        binding.activityLikeContainer.setOnLongClickListener {
            UsersDialogFragment().apply {
                userList(userList)
                show((context as FragmentActivity).supportFragmentManager, "dialog")
            }
            true
        }
        binding.activityLikeCount.text = (activity.likeCount ?: 0).toString()
        binding.activityLikeContainer.setOnClickListener {
            scope.launch {
                val res = Anilist.mutation.toggleLike(activity.id, "ACTIVITY")
                withContext(Dispatchers.Main) {
                    if (res != null) {
                        if (activity.isLiked == true) {
                            activity.likeCount = activity.likeCount?.minus(1)
                        } else {
                            activity.likeCount = activity.likeCount?.plus(1)
                        }
                        binding.activityLikeCount.text = (activity.likeCount ?: 0).toString()
                        activity.isLiked = !activity.isLiked!!
                        binding.activityLike.setColorFilter(if (activity.isLiked == true) likeColor else notLikeColor)

                    } else {
                        snackString("Failed to like activity")
                    }
                }
            }
        }
        binding.activityDelete.isVisible =
            activity.userId == Anilist.userid || activity.messenger?.id == Anilist.userid
        binding.activityDelete.setOnClickListener {
            scope.launch {
                val res = Anilist.mutation.deleteActivity(activity.id)
                withContext(Dispatchers.Main) {
                    if (res) {
                        snackString("Deleted activity")
                        parentAdapter.remove(this@ActivityItem)
                    } else {
                        snackString("Failed to delete activity")
                    }
                }
            }
        }
        when (activity.typename) {
            "ListActivity" -> {
                val cover = activity.media?.coverImage?.large
                val banner = activity.media?.bannerImage
                binding.activityContent.visibility = View.GONE
                binding.activityBannerContainer.visibility = View.VISIBLE
                binding.activityPrivate.visibility = View.GONE
                binding.activityMediaName.text = activity.media?.title?.userPreferred
                val activityText = "${activity.user!!.name} ${activity.status} ${
                    activity.progress
                        ?: activity.media?.title?.userPreferred
                }"
                binding.activityText.text = activityText
                binding.activityCover.loadImage(cover)
                blurImage(binding.activityBannerImage, banner ?: cover)
                binding.activityAvatarContainer.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityUserName.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityCoverContainer.setOnClickListener {
                    clickCallback(activity.media?.id ?: -1, "MEDIA")
                }
                binding.activityMediaName.setOnClickListener {
                    clickCallback(activity.media?.id ?: -1, "MEDIA")
                }
                binding.activityEdit.isVisible = false
            }

            "TextActivity" -> {
                binding.activityBannerContainer.visibility = View.GONE
                binding.activityContent.visibility = View.VISIBLE
                binding.activityPrivate.visibility = View.GONE
                if (!(context as android.app.Activity).isDestroyed) {
                    val rawText = activity.text ?: ""
                    val anilistLinks = AnilistLinkParser.extractAnilistLinks(rawText)
                    val html = getBasicAniHTML(rawText)
                    val cleanedHtml = AnilistLinkParser.removeAnilistUrlsFromHtml(html)
                    val markwon = buildMarkwon(context, false)
                    markwon.setMarkdown(binding.activityContent, cleanedHtml)
                    addLinkPreviews(anilistLinks, rawText)
                }
                binding.activityAvatarContainer.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityUserName.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityEdit.isVisible = activity.userId == Anilist.userid
                binding.activityEdit.setOnClickListener {
                    context.startActivity(
                        Intent(context, ActivityMarkdownCreator::class.java).apply {
                            putExtra("type","activity")
                            putExtra("other",activity.text)
                            putExtra("edit",activity.id)
                        }
                    )
                }
            }

            "MessageActivity" -> {
                binding.activityBannerContainer.visibility = View.GONE
                binding.activityContent.visibility = View.VISIBLE
                binding.activityPrivate.visibility =
                    if (activity.isPrivate == true) View.VISIBLE else View.GONE
                if (!(context as android.app.Activity).isDestroyed) {
                    val rawMessage = activity.message ?: ""
                    val anilistLinks = AnilistLinkParser.extractAnilistLinks(rawMessage)
                    val html = getBasicAniHTML(rawMessage)
                    val cleanedHtml = AnilistLinkParser.removeAnilistUrlsFromHtml(html)
                    val markwon = buildMarkwon(context, false)
                    markwon.setMarkdown(binding.activityContent, cleanedHtml)
                    addLinkPreviews(anilistLinks, rawMessage)
                }
                binding.activityAvatarContainer.setOnClickListener {
                    clickCallback(activity.messengerId ?: -1, "USER")
                }
                binding.activityUserName.setOnClickListener {
                    clickCallback(activity.messengerId ?: -1, "USER")
                }
                binding.activityEdit.isVisible = false
                binding.activityEdit.isVisible = activity.messenger?.id == Anilist.userid
                binding.activityEdit.setOnClickListener {
                    context.startActivity(
                        Intent(context, ActivityMarkdownCreator::class.java).apply{
                            putExtra("type","message")
                            putExtra("other",activity.message)
                            putExtra("edit",activity.id)
                            putExtra("userId",activity.recipientId)
                        }
                    )
                }
            }
        }
    }

    private fun addLinkPreviews(links: List<AnilistLinkParser.AnilistLink>, originalText: String) {
        binding.activityLinkPreviewContainer.removeAllViews()
        if (links.isEmpty()) {
            binding.activityLinkPreviewContainer.visibility = View.GONE
            return
        }
        binding.activityLinkPreviewContainer.visibility = View.VISIBLE
        val mediaIds = links.map { it.id }.distinct()
        val fragmentActivity = binding.root.context as? FragmentActivity ?: return
        previewJob = fragmentActivity.lifecycleScope.launch {
            val mediaList = withContext(Dispatchers.IO) {
                Anilist.query.getMediaList(mediaIds)
            }
            // Check that the view hasn't been recycled since we started
            if (!fragmentActivity.isDestroyed) {
                val mediaMap = mediaList?.associateBy { it.id } ?: emptyMap()
                // Re-render the text view: replace AniList URLs with media titles
                if (mediaMap.isNotEmpty()) {
                    val titleMap = mediaMap.mapValues { (_, media) -> media.userPreferredName }
                    val htmlWithTitles = AnilistLinkParser.replaceAnilistUrlsInHtml(
                        getBasicAniHTML(originalText), titleMap
                    )
                    val markwon = buildMarkwon(fragmentActivity, false)
                    markwon.setMarkdown(binding.activityContent, htmlWithTitles)
                }
                // Add preview cards
                binding.activityLinkPreviewContainer.removeAllViews()
                links.forEach { link ->
                    val media = mediaMap[link.id]
                    if (media != null) {
                        val previewView = AnilistLinkPreviewView(fragmentActivity)
                        previewView.setMediaData(media)
                        binding.activityLinkPreviewContainer.addView(previewView)
                    }
                }
                binding.activityLinkPreviewContainer.visibility =
                    if (binding.activityLinkPreviewContainer.isNotEmpty()) View.VISIBLE
                    else View.GONE
            }
        }
    }

    override fun getLayout(): Int {
        return R.layout.item_activity
    }

    override fun initializeViewBinding(view: View): ItemActivityBinding {
        return ItemActivityBinding.bind(view)
    }
}