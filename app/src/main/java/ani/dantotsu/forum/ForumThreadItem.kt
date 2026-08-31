package ani.dantotsu.forum

import android.content.Context
import android.content.Intent
import android.view.View
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.api.ForumThread
import ani.dantotsu.databinding.ItemForumThreadBinding
import ani.dantotsu.loadImage
import ani.dantotsu.profile.activity.ActivityItemBuilder
import com.xwray.groupie.viewbinding.BindableItem

class ForumThreadItem(
    val thread: ForumThread,
    val context: Context
) : BindableItem<ItemForumThreadBinding>() {

    override fun bind(viewBinding: ItemForumThreadBinding, position: Int) {
        viewBinding.threadAuthorAvatar.loadImage(thread.user?.avatar?.medium)
        viewBinding.threadAuthorName.text = thread.user?.name ?: "Unknown"
        viewBinding.threadTime.text = thread.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""
        val categoryName = thread.categories?.firstOrNull()?.name
            ?: thread.mediaCategories?.firstOrNull()?.title?.userPreferred
            ?: "General"
        viewBinding.threadCategory.text = categoryName

        viewBinding.threadTitle.text = thread.title ?: ""
        val previewText = thread.body
            ?.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)|img\\d*\\([^)]*\\)|youtube\\([^)]*\\)|webm\\([^)]*\\)"), "")
            ?.replace(Regex("<[^>]*>"), " ")
            ?.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            ?.replace(Regex("[*~_`#]"), "")
            ?.trim() ?: ""
        viewBinding.threadBodyPreview.text = previewText

        viewBinding.threadReplyCount.text = (thread.replyCount ?: 0).toString()
        viewBinding.threadViewCount.text = (thread.viewCount ?: 0).toString()
        viewBinding.threadLikeCount.text = (thread.likeCount ?: 0).toString()

        viewBinding.threadLockedBadge.visibility = if (thread.isLocked == true) View.VISIBLE else View.GONE
        viewBinding.threadPinnedBadge.visibility = if (thread.isSticky == true) View.VISIBLE else View.GONE

        viewBinding.root.setOnClickListener {
            context.startActivity(
                Intent(context, ThreadViewActivity::class.java).apply {
                    putExtra("threadId", thread.id)
                    putExtra("thread", thread)
                }
            )
        }
    }

    override fun getLayout(): Int = R.layout.item_forum_thread

    override fun initializeViewBinding(view: View): ItemForumThreadBinding =
        ItemForumThreadBinding.bind(view)
}
