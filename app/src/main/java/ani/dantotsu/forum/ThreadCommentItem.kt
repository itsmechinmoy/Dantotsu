package ani.dantotsu.forum

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.ThreadComment
import ani.dantotsu.databinding.ItemThreadCommentBinding
import ani.dantotsu.loadImage
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.snackString
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.AniMarkdown
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadCommentItem(
    val comment: ThreadComment,
    val threadId: Int,
    val context: Context,
    val parentAdapter: GroupieAdapter,
    val scope: CoroutineScope
) : BindableItem<ItemThreadCommentBinding>() {

    override fun bind(viewBinding: ItemThreadCommentBinding, position: Int) {
        viewBinding.commentAvatar.loadImage(comment.user?.avatar?.medium)
        viewBinding.commentAuthor.text = comment.user?.name ?: "Unknown"
        viewBinding.commentTime.text = comment.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""
        val markwon = buildMarkwon(context, false, anilist = true)
        val html = AniMarkdown.getBasicAniHTML(comment.comment ?: "")
        markwon.setMarkdown(viewBinding.commentBody, html)

        val likeColor = ContextCompat.getColor(context, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(context, R.color.bg_opp)
        viewBinding.commentLikeIcon.setColorFilter(if (comment.isLiked == true) likeColor else notLikeColor)
        viewBinding.commentLikeCount.text = (comment.likeCount ?: 0).toString()

        viewBinding.commentLikeButton.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val success = Anilist.mutation.toggleLike(comment.id, "THREAD_COMMENT") != null
                withContext(Dispatchers.Main) {
                    if (success) {
                        comment.isLiked = !(comment.isLiked ?: false)
                        val count = comment.likeCount ?: 0
                        comment.likeCount = if (comment.isLiked == true) count + 1 else (count - 1).coerceAtLeast(0)
                        viewBinding.commentLikeCount.text = (comment.likeCount ?: 0).toString()
                        viewBinding.commentLikeIcon.setColorFilter(if (comment.isLiked == true) likeColor else notLikeColor)
                    }
                }
            }
        }

        viewBinding.commentDelete.visibility = if (comment.userId == Anilist.userid) View.VISIBLE else View.GONE
        viewBinding.commentDelete.setOnClickListener {
            context.customAlertDialog().apply {
                setTitle(R.string.delete)
                setMessage(R.string.delete_comment_confirm)
                setPosButton(R.string.delete) {
                    scope.launch(Dispatchers.IO) {
                        val success = Anilist.mutation.deleteThreadComment(comment.id)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                snackString("Comment deleted")
                                parentAdapter.remove(this@ThreadCommentItem)
                            } else {
                                snackString("Failed to delete comment")
                            }
                        }
                    }
                }
                setNegButton(R.string.cancel)
                show()
            }
        }

        viewBinding.commentReplyButton.setOnClickListener {
            context.startActivity(
                Intent(context, ActivityMarkdownCreator::class.java).apply {
                    putExtra("type", "threadComment")
                    putExtra("threadId", threadId)
                    putExtra("parentId", comment.id)
                    putExtra("other", "@${comment.user?.name} ")
                }
            )
        }
    }

    override fun getLayout(): Int = R.layout.item_thread_comment

    override fun initializeViewBinding(view: View): ItemThreadCommentBinding =
        ItemThreadCommentBinding.bind(view)
}
