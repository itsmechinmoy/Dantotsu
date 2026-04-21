package ani.dantotsu.profile.notification

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Notification
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.databinding.ItemNotificationBinding
import ani.dantotsu.loadImage
import ani.dantotsu.notifications.comment.CommentStore
import ani.dantotsu.notifications.subscription.SubscriptionStore
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationClickType
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.COMMENT
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.SUBSCRIPTION
import ani.dantotsu.setAnimation
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toPx
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationItem(
    private val notification: Notification,
    val type: NotificationFragment.Companion.NotificationType,
    val parentAdapter: GroupieAdapter,
    val clickCallback: (Int, Int?, NotificationClickType) -> Unit,

    ) : BindableItem<ItemNotificationBinding>() {
    private lateinit var binding: ItemNotificationBinding
    override fun bind(viewBinding: ItemNotificationBinding, position: Int) {
        binding = viewBinding
        setAnimation(binding.root.context, binding.root)
        setBinding()
    }

    fun dialog() {
        val notificationType = NotificationType.entries.find { it.value == notification.notificationType } ?: return
        val canDeleteLocal = type == COMMENT || type == SUBSCRIPTION
        val canUnsubscribeActivity =
            notificationType == NotificationType.ACTIVITY_REPLY_SUBSCRIBED && notification.activityId != null

        if (!canDeleteLocal && !canUnsubscribeActivity) return

        binding.root.context.customAlertDialog().apply {
            if (canDeleteLocal) {
                setTitle(R.string.delete)
            } else {
                setTitle(R.string.activity_subscription)
            }
            setMessage(ActivityItemBuilder.getContent(notification))
            if (canDeleteLocal) {
                setPosButton(R.string.yes) {
                    if (type == COMMENT) {
                        val list = PrefManager.getNullableVal<List<CommentStore>>(
                            PrefName.CommentNotificationStore,
                            null
                        ) ?: listOf()
                        val newList = list.filter { it.commentId != notification.commentId }
                        PrefManager.setVal(PrefName.CommentNotificationStore, newList)
                        parentAdapter.remove(this@NotificationItem)
                    } else if (type == SUBSCRIPTION) {
                        val list = PrefManager.getNullableVal<List<SubscriptionStore>>(
                            PrefName.SubscriptionNotificationStore,
                            null
                        ) ?: listOf()
                        val newList =
                            list.filter { (it.time / 1000L).toInt() != notification.createdAt }
                        PrefManager.setVal(PrefName.SubscriptionNotificationStore, newList)
                        parentAdapter.remove(this@NotificationItem)
                    }
                }
            }
            if (canUnsubscribeActivity) {
                val unsubscribeAction = {
                    val activityId = notification.activityId
                    if (activityId == null) {
                        snackString(binding.root.context.getString(R.string.activity_unsubscribe_failed))
                    } else {
                        val lifecycleOwner = binding.root.findViewTreeLifecycleOwner()
                        if (lifecycleOwner == null) {
                            snackString(binding.root.context.getString(R.string.activity_unsubscribe_unavailable))
                        } else {
                            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                val success = runCatching {
                                    Anilist.mutation.toggleActivitySubscription(
                                        activityId,
                                        false
                                    )
                                }.onFailure {
                                    Logger.log("Failed to unsubscribe from activity: ${it.message}")
                                }.getOrDefault(false)
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        snackString(binding.root.context.getString(R.string.activity_unsubscribed))
                                    } else {
                                        snackString(binding.root.context.getString(R.string.activity_unsubscribe_failed))
                                    }
                                }
                            }
                        }
                    }
                }
                if (canDeleteLocal) {
                    setNeutralButton(R.string.unsubscribe) { unsubscribeAction() }
                } else {
                    setPosButton(R.string.unsubscribe) { unsubscribeAction() }
                }
            }
            setNegButton(R.string.no)
            show()
        }

    }

    override fun getLayout(): Int {
        return R.layout.item_notification
    }

    override fun initializeViewBinding(view: View): ItemNotificationBinding {
        return ItemNotificationBinding.bind(view)
    }

    private fun getNotificationTypeIcon(notificationType: NotificationType): Int {
        return when (notificationType) {
            NotificationType.ACTIVITY_LIKE,
            NotificationType.ACTIVITY_REPLY_LIKE,
            NotificationType.THREAD_LIKE,
            NotificationType.THREAD_COMMENT_LIKE -> R.drawable.ic_round_favorite_24

            NotificationType.ACTIVITY_REPLY,
            NotificationType.THREAD_COMMENT_REPLY,
            NotificationType.COMMENT_REPLY -> R.drawable.ic_round_reply_24

            NotificationType.ACTIVITY_MESSAGE -> R.drawable.ic_round_remove_red_eye_24

            NotificationType.FOLLOWING -> R.drawable.ic_round_person_24
            
            NotificationType.ACTIVITY_MENTION,
            NotificationType.THREAD_COMMENT_MENTION -> R.drawable.ic_round_comment_24

            NotificationType.AIRING,
            NotificationType.SUBSCRIPTION,
            NotificationType.THREAD_SUBSCRIBED,
            NotificationType.ACTIVITY_REPLY_SUBSCRIBED -> R.drawable.ic_round_notifications_active_24

            NotificationType.RELATED_MEDIA_ADDITION -> R.drawable.ic_round_play_arrow_24

            NotificationType.MEDIA_DATA_CHANGE,
            NotificationType.MEDIA_MERGE -> R.drawable.ic_round_notifications_none_24

            NotificationType.MEDIA_DELETION -> R.drawable.ic_round_delete_24

            NotificationType.COMMENT_WARNING -> R.drawable.ic_round_notifications_active_24

            NotificationType.DANTOTSU_UPDATE -> R.drawable.ic_round_notifications_active_24
        }
    }

    private fun image(
        user: Boolean = false,
        commentNotification: Boolean = false,
        newRelease: Boolean = false
    ) {

        val cover = if (user) notification.user?.bannerImage
            ?: notification.user?.avatar?.medium else notification.media?.bannerImage
            ?: notification.media?.coverImage?.large
        blurImage(binding.notificationBannerImage, if (newRelease) notification.banner else cover)

        val defaultHeight = 153.toPx

        val userHeight = 90.toPx

        val textMarginStart = 125.toPx

        if (user) {
            val iconParams = binding.notificationTypeIcon.layoutParams as FrameLayout.LayoutParams
            iconParams.gravity = Gravity.TOP or Gravity.END
            iconParams.topMargin = 8.toPx
            iconParams.marginEnd = 12.toPx
            binding.notificationTypeIcon.layoutParams = iconParams
            
            binding.notificationCover.visibility = View.GONE
            binding.notificationCoverUser.visibility = View.VISIBLE
            binding.notificationCoverUserContainer.visibility = View.VISIBLE
            if (commentNotification) {
                binding.notificationCoverUser.setImageResource(R.drawable.ic_dantotsu_round)
                binding.notificationCoverUser.scaleX = 1.4f
                binding.notificationCoverUser.scaleY = 1.4f
            } else {
                binding.notificationCoverUser.loadImage(notification.user?.avatar?.large)
            }
            binding.notificationBannerImage.layoutParams.height = userHeight
            binding.notificationGradiant.layoutParams.height = userHeight

            val textParams = binding.notificationTextContainer.layoutParams as ViewGroup.MarginLayoutParams
            textParams.marginStart = userHeight
            textParams.marginEnd = 48.toPx
            binding.notificationTextContainer.layoutParams = textParams
        } else {
            val iconParams = binding.notificationTypeIcon.layoutParams as FrameLayout.LayoutParams
            iconParams.gravity = Gravity.TOP or Gravity.END
            iconParams.topMargin = 16.toPx
            iconParams.marginEnd = 16.toPx
            binding.notificationTypeIcon.layoutParams = iconParams
            
            binding.notificationCover.visibility = View.VISIBLE
            binding.notificationCoverUser.visibility = View.VISIBLE
            binding.notificationCoverUserContainer.visibility = View.GONE
            binding.notificationCover.loadImage(if (newRelease) notification.image else notification.media?.coverImage?.large)
            binding.notificationBannerImage.layoutParams.height = defaultHeight
            binding.notificationGradiant.layoutParams.height = defaultHeight
            (binding.notificationTextContainer.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                textMarginStart
        }
    }

    private fun setBinding() {
        val notificationType: NotificationType =
            NotificationType.valueOf(notification.notificationType)
        binding.notificationText.text = ActivityItemBuilder.getContent(notification)
        binding.notificationDate.text = ActivityItemBuilder.getDateTime(notification.createdAt)
        binding.notificationTypeIcon.setImageResource(getNotificationTypeIcon(notificationType))

        when (notificationType) {
            NotificationType.ACTIVITY_MESSAGE -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.activityId ?: 0, null, NotificationClickType.ACTIVITY
                    )
                }
            }

            NotificationType.ACTIVITY_REPLY -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.activityId ?: 0, null, NotificationClickType.ACTIVITY
                    )
                }
            }

            NotificationType.FOLLOWING -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.userId ?: 0, null, NotificationClickType.USER
                    )
                }
            }

            NotificationType.ACTIVITY_MENTION -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.activityId ?: 0, null, NotificationClickType.ACTIVITY
                    )
                }
            }

            NotificationType.THREAD_COMMENT_MENTION -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
            }

            NotificationType.THREAD_SUBSCRIBED -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
            }

            NotificationType.THREAD_COMMENT_REPLY -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
            }

            NotificationType.AIRING -> {
                binding.notificationCover.loadImage(notification.media?.coverImage?.large)
                image()
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.media?.id ?: 0, null, NotificationClickType.MEDIA
                    )
                }
            }

            NotificationType.ACTIVITY_LIKE -> {
                image(true)
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.activityId ?: 0, null, NotificationClickType.ACTIVITY
                    )
                }
            }

            NotificationType.ACTIVITY_REPLY_LIKE -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.activityId ?: 0, null, NotificationClickType.ACTIVITY
                    )
                }
            }

            NotificationType.THREAD_LIKE -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
            }

            NotificationType.THREAD_COMMENT_LIKE -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
            }

            NotificationType.ACTIVITY_REPLY_SUBSCRIBED -> {
                binding.notificationCover.loadImage(notification.user?.avatar?.large)
                image(true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.user?.id ?: 0, null, NotificationClickType.USER
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.activityId ?: 0, null, NotificationClickType.ACTIVITY
                    )
                }
            }

            NotificationType.RELATED_MEDIA_ADDITION -> {
                binding.notificationCover.loadImage(notification.media?.coverImage?.large)
                image()
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.media?.id ?: 0, null, NotificationClickType.MEDIA
                    )
                }
            }

            NotificationType.MEDIA_DATA_CHANGE -> {
                binding.notificationCover.loadImage(notification.media?.coverImage?.large)
                image()
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.media?.id ?: 0, null, NotificationClickType.MEDIA
                    )
                }
            }

            NotificationType.MEDIA_MERGE -> {
                binding.notificationCover.loadImage(notification.media?.coverImage?.large)
                image()
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.media?.id ?: 0, null, NotificationClickType.MEDIA
                    )
                }
            }

            NotificationType.MEDIA_DELETION -> {
                binding.notificationCover.visibility = View.GONE
            }

            NotificationType.COMMENT_REPLY -> {
                image(user = true, commentNotification = true)
                if (notification.commentId != null && notification.mediaId != null) {
                    binding.notificationBannerImage.setOnClickListener {
                        clickCallback(
                            notification.mediaId,
                            notification.commentId,
                            NotificationClickType.COMMENT
                        )
                    }
                }
            }

            NotificationType.COMMENT_WARNING -> {
                image(user = true, commentNotification = true)
                if (notification.commentId != null && notification.mediaId != null) {
                    binding.notificationBannerImage.setOnClickListener {
                        clickCallback(
                            notification.mediaId,
                            notification.commentId,
                            NotificationClickType.COMMENT
                        )
                    }
                }
            }

            NotificationType.DANTOTSU_UPDATE -> {
                image(user = true)
            }

            NotificationType.SUBSCRIPTION -> {
                image(newRelease = true)
                binding.notificationCoverUser.setOnClickListener {
                    clickCallback(
                        notification.mediaId ?: 0, null, NotificationClickType.MEDIA
                    )
                }
                binding.notificationBannerImage.setOnClickListener {
                    clickCallback(
                        notification.mediaId ?: 0, null, NotificationClickType.MEDIA
                    )
                }
            }
        }
        binding.notificationCoverUser.setOnLongClickListener {
            dialog()
            true
        }
        binding.notificationBannerImage.setOnLongClickListener {
            dialog()
            true
        }
    }

}
