package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.databinding.ItemMediaPageBinding
import ani.dantotsu.databinding.ItemMediaPageSmallBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setAnimation
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import java.io.Serializable


class MediaAdaptor(
    var type: Int,
    private val mediaList: MutableList<Media>?,
    private val activity: FragmentActivity,
    private val matchParent: Boolean = false,
    private val viewPager: ViewPager2? = null,
    private val fav: Boolean = false,
    private val isOtherUser: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (type) {
            0 -> MediaViewHolder(
                ItemMediaCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> MediaLargeViewHolder(
                ItemMediaLargeBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> MediaPageViewHolder(
                ItemMediaPageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            3 -> MediaPageSmallViewHolder(
                ItemMediaPageSmallBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (type) {
            0 -> {
                val b = (holder as MediaViewHolder).binding
                setAnimation(activity, b.root)
                val media = mediaList?.getOrNull(position)
                if (media != null) {
                    val density = activity.resources.displayMetrics.density
                    if (media.id < 0) {
                        b.itemCompactTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            width = (108 * density).toInt()
                            height = (12 * density).toInt()
                            bottomMargin = (4 * density).toInt()
                            topMargin = (4 * density).toInt()
                        }
                        b.itemCompactTitle.background = ContextCompat.getDrawable(b.root.context, R.drawable.skeleton_text_placeholder)
                        b.itemCompactTitle.text = ""

                        b.itemCompactProgressContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            width = (54 * density).toInt()
                            height = (8 * density).toInt()
                            topMargin = (2 * density).toInt()
                        }
                        b.itemCompactProgressContainer.background = ContextCompat.getDrawable(b.root.context, R.drawable.skeleton_stats_placeholder)
                        b.itemCompactUserProgress.text = ""
                        b.itemCompactTotal.text = ""
                        b.itemCompactScoreBG.isVisible = false
                        b.itemCompactOngoing.isVisible = false
                        b.itemCompactType.isVisible = false
                        b.itemCompactImage.setImageResource(R.drawable.skeleton_cover_placeholder)
                    } else {
                        b.itemCompactTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            width = (108 * density).toInt()
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                            bottomMargin = 0
                            topMargin = 0
                        }
                        b.itemCompactTitle.background = null

                        b.itemCompactProgressContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                            topMargin = 0
                        }
                        b.itemCompactProgressContainer.background = null
                        b.itemCompactScoreBG.isVisible = true
                        b.itemCompactImage.loadImage(media.cover)
                        b.itemCompactOngoing.isVisible =
                            media.status == currActivity()!!.getString(R.string.status_releasing)
                        b.itemCompactTitle.text = media.userPreferredName
                        b.itemCompactScore.text =
                            ((if (media.userScore == 0) (media.meanScore
                                ?: 0) else media.userScore) / 10.0).toString()
                        b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                            b.root.context,
                            (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                        )
                        b.itemCompactUserProgress.text = (media.userProgress ?: "~").toString()
                        if (media.relation != null) {
                            b.itemCompactRelation.text = "${media.relation}  "
                            b.itemCompactType.visibility = View.VISIBLE

                            if (media.relation!!.contains("\n")) {
                                b.itemCompactRelation.apply {
                                    isSingleLine = false
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.START

                                    includeFontPadding = false
                                    setLineSpacing(0f, 0.9f)
                                }
                            } else {
                                b.itemCompactRelation.isSingleLine = true
                                b.itemCompactRelation.maxLines = 1
                            }
                        } else {
                            b.itemCompactType.visibility = View.GONE
                        }
                        
                        if (media.anime != null) {
                            if (media.relation != null) b.itemCompactTypeImage.setImageDrawable(
                                AppCompatResources.getDrawable(
                                    activity,
                                    R.drawable.ic_round_movie_filter_24
                                )
                            )
                            b.itemCompactTotal.text =
                                " | ${if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " | " + (media.anime.totalEpisodes ?: "~").toString()) else (media.anime.totalEpisodes ?: "~").toString()}"
                        } else if (media.manga != null) {
                            if (media.relation != null) b.itemCompactTypeImage.setImageDrawable(
                                AppCompatResources.getDrawable(
                                    activity,
                                    R.drawable.ic_round_import_contacts_24
                                )
                            )
                            b.itemCompactTotal.text = " | ${media.manga.totalChapters ?: "~"}"
                        }
                    }
                    b.itemCompactProgressContainer.visibility = if (fav) View.GONE else View.VISIBLE
                }
            }

            1 -> {
                val b = (holder as MediaLargeViewHolder).binding
                setAnimation(activity, b.root)
                val media = mediaList?.get(position)
                if (media != null) {
                    b.itemCompactImage.loadImage(media.cover)
                    blurImage(b.itemCompactBanner, media.banner ?: media.cover)
                    b.itemCompactOngoing.isVisible =
                        media.status == currActivity()!!.getString(R.string.status_releasing)
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    if (media.anime != null) {
                        val itemTotal = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural) else currActivity()!!.getString(
                            R.string.episode_singular
                        )
                        b.itemTotal.text = itemTotal
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    } else if (media.manga != null) {
                        val itemTotal = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.chapter_plural) else currActivity()!!.getString(
                            R.string.chapter_singular
                        )
                        b.itemTotal.text = itemTotal
                        b.itemCompactTotal.text = "${media.manga.totalChapters ?: "??"}"
                    }
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val start = mediaList.size
                        mediaList.addAll(mediaList)
                        val end = mediaList.size - start
                        notifyItemRangeInserted(start, end)
                    }
                }
            }

            2 -> {
                val b = (holder as MediaPageViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {

                    val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                    b.itemCompactImage.loadImage(media.cover)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed)) as Float)).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    blurImage(
                        if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen,
                        media.banner ?: media.cover
                    )
                    b.itemCompactOngoing.isVisible =
                        media.status == currActivity()!!.getString(R.string.status_releasing)
                    b.itemCompactTitle.text = media.userPreferredName
                    bindCarouselLogo(media, b.itemCompactTitle, b.itemCompactLogo)
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    if (media.anime != null) {
                        b.itemTotal.text = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural)
                        else currActivity()!!.getString(R.string.episode_singular)
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    } else if (media.manga != null) {
                        b.itemTotal.text = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.chapter_plural)
                        else currActivity()!!.getString(R.string.chapter_singular)
                        b.itemCompactTotal.text = "${media.manga.totalChapters ?: "??"}"
                    }
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }

            3 -> {
                val b = (holder as MediaPageSmallViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {
                    val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                    b.itemCompactImage.loadImage(media.cover)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed) as Float))).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    blurImage(
                        if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen,
                        media.banner ?: media.cover
                    )
                    b.itemCompactOngoing.isVisible =
                        media.status == currActivity()!!.getString(R.string.status_releasing)
                    b.itemCompactTitle.text = media.userPreferredName
                    bindCarouselLogo(
                        media,
                        b.itemCompactTitle,
                        b.itemCompactLogo,
                        b.itemCompactSynopsis,
                        b.itemCompactLogoContainer
                    )
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    if (media.genres.isNotEmpty()) {
                        b.itemCompactGenres.text = media.genres.joinToString(" • ")
                    }
                    b.itemCompactStatus.text = media.status ?: ""
                    if (media.anime != null) {
                        b.itemTotal.text = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural)
                        else currActivity()!!.getString(R.string.episode_singular)
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    } else if (media.manga != null) {
                        b.itemTotal.text = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.chapter_plural)
                        else currActivity()!!.getString(R.string.chapter_singular)
                        b.itemCompactTotal.text = "${media.manga.totalChapters ?: "??"}"
                    }
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }
        }
    }

    private fun bindCarouselLogo(
        media: Media,
        titleView: android.widget.TextView,
        logoView: ImageView,
        synopsisView: android.widget.TextView? = null,
        logoContainer: View? = null
    ) {
        val clearLogoEnabled: Boolean = PrefManager.getVal(PrefName.CarouselClearLogo)
        if (!clearLogoEnabled) {
            titleView.visibility = View.VISIBLE
            logoView.visibility = View.GONE
            logoContainer?.visibility = View.GONE
            synopsisView?.visibility = View.GONE
            return
        }

        val currentId = media.id
        logoView.tag = currentId

        fun showLogo(url: String) {
            logoView.loadImage(url)
            logoView.visibility = View.VISIBLE
            logoContainer?.visibility = View.VISIBLE
            titleView.visibility = View.GONE
            if (synopsisView != null) {
                val cleanDesc = media.description?.let { sanitizeDescription(it) }
                if (!cleanDesc.isNullOrBlank()) {
                    synopsisView.text = cleanDesc
                    synopsisView.visibility = View.VISIBLE
                } else {
                    synopsisView.visibility = View.GONE
                }
            }
        }

        fun showTitle() {
            titleView.visibility = View.VISIBLE
            logoView.visibility = View.GONE
            logoContainer?.visibility = View.GONE
            synopsisView?.visibility = View.GONE
        }

        if (!media.clearLogo.isNullOrBlank()) {
            showLogo(media.clearLogo!!)
        } else {
            showTitle()
            activity.lifecycleScope.launch(Dispatchers.Main) {
                val logo = CarouselLogoResolver.resolve(media)
                if (logoView.tag == currentId) {
                    if (!logo.isNullOrBlank()) {
                        media.clearLogo = logo
                        showLogo(logo)
                    } else {
                        showTitle()
                    }
                }
            }
        }
    }

    private fun sanitizeDescription(desc: String): String {
        return desc
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\n\\s*\\n"), "\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    override fun getItemCount() = mediaList!!.size

    override fun getItemViewType(position: Int): Int {
        return type
    }

    fun randomOptionClick() {
        val media = if (!mediaList.isNullOrEmpty()) {
            mediaList.random()
        } else {
            null
        }
        media?.let {
            val index = mediaList?.indexOf(it) ?: -1
            clicked(index, null)
        }
    }

    inner class MediaViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            if (matchParent) itemView.updateLayoutParams { width = -1 }
            itemView.setSafeOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    inner class MediaLargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setSafeOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageViewHolder(val binding: ItemMediaPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactImage.setSafeOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnTouchListener { _, _ -> true }
            binding.itemCompactImage.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageSmallViewHolder(val binding: ItemMediaPageSmallBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactImage.setSafeOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            binding.itemCompactTitleContainer.setSafeOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setSafeOnClickListener
                val media = mediaList?.getOrNull(pos)
                if (media == null || media.id < 0) return@setSafeOnClickListener
                clicked(
                    pos,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnTouchListener { _, _ -> true }
            binding.itemCompactImage.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    fun clicked(position: Int, itemCompactImage: ImageView?, bitmap: Bitmap? = null) {
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position) ?: return
            if (media.id < 0) return
            if (bitmap != null) MediaSingleton.bitmap = bitmap
            ContextCompat.startActivity(
                activity,
                Intent(activity, MediaDetailsActivity::class.java).putExtra(
                    "media",
                    media as Serializable
                ),
                if (itemCompactImage != null) {
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        itemCompactImage,
                        ViewCompat.getTransitionName(itemCompactImage)!!
                    ).toBundle()
                } else {
                    null
                }
            )
        }
    }


    fun longClicked(position: Int): Boolean {
        if (isOtherUser) return false
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position) ?: return false
            if (media.id < 0) return false
            if (activity.supportFragmentManager.findFragmentByTag("list") == null) {
                MediaListDialogSmallFragment.newInstance(media)
                    .show(activity.supportFragmentManager, "list")
                return true
            }
        }
        return false
    }

    fun getBitmapFromImageView(imageView: ImageView): Bitmap? {
        val drawable = imageView.drawable ?: return null

        // If the drawable is a BitmapDrawable, then just get the bitmap
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else imageView.width
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else imageView.height
        if (width <= 0 || height <= 0) return null

        val bitmap = try {
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )
        } catch (_: Exception) {
            return null
        }

        // Draw the drawable onto the bitmap
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    fun resizeBitmap(source: Bitmap?, maxDimension: Int): Bitmap? {
        if (source == null) return null
        val width = source.width
        val height = source.height
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxDimension
            newHeight = (height * (maxDimension.toFloat() / width)).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (width * (maxDimension.toFloat() / height)).toInt()
        }

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

}
