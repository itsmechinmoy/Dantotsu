package ani.dantotsu.profile

import android.text.SpannableString
import android.view.View
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.User
import ani.dantotsu.databinding.ItemFollowerBinding
import ani.dantotsu.databinding.ItemFollowerGridBinding
import ani.dantotsu.loadImage
import ani.dantotsu.snackString
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FollowerItem(
    private val grid: Boolean,
    private val user: User,
    private val scope: CoroutineScope,
    val clickCallback: (Int) -> Unit
) : BindableItem<ViewBinding>() {

    override fun bind(viewBinding: ViewBinding, position: Int) {
        val username = SpannableString(user.name ?: "Unknown")

        if (grid) {
            val binding = viewBinding as ItemFollowerGridBinding

            binding.profileUserName.text = username
            user.avatar?.medium?.let { binding.profileUserAvatar.loadImage(it) }

            //setupFollowButton(binding.followStatusChip)

            binding.root.setOnClickListener { clickCallback(user.id) }

        } else {
            val binding = viewBinding as ItemFollowerBinding

            binding.profileUserName.text = username
            user.avatar?.medium?.let { binding.profileUserAvatar.loadImage(it) }
            blurImage(binding.profileBannerImage, user.bannerImage ?: user.avatar?.medium)

            setupFollowButton(binding.followStatusChip)

            binding.root.setOnClickListener { clickCallback(user.id) }
        }
    }

    private fun setupFollowButton(followButton: View) {
        val button = followButton as? com.google.android.material.chip.Chip ?: return

        button.isGone = user.id == Anilist.userid || Anilist.userid == null || user.isFollowing == null || user.isFollower == null

        fun followText(): String {
            return button.context.getString(
                when {
                    user.isFollowing == true && user.isFollower == true -> R.string.mutual
                    user.isFollowing == true -> R.string.unfollow
                    user.isFollower == true -> R.string.follows_you
                    else -> R.string.follow
                }
            )
        }

        button.text = followText()

        button.setOnClickListener {

           scope.launch(Dispatchers.IO) {
                val res = Anilist.mutation.toggleFollow(user.id)
                if (res?.data?.toggleFollow != null) {
                    withContext(Dispatchers.Main) {
                        snackString(R.string.success)

                        user.isFollowing = res.data.toggleFollow.isFollowing
                        button.text = followText()
                    }
                }
            }
        }
    }

    override fun getLayout(): Int {
        return if (grid) R.layout.item_follower_grid else R.layout.item_follower
    }

    override fun initializeViewBinding(view: View): ViewBinding {
        return if (grid) {
            ItemFollowerGridBinding.bind(view)
        } else {
            ItemFollowerBinding.bind(view)
        }
    }
}