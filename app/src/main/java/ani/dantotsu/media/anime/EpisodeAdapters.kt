package ani.dantotsu.media.anime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.lifecycle.coroutineScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.databinding.ItemEpisodeGridBinding
import ani.dantotsu.databinding.ItemEpisodeListBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.setAnimation
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.util.SizeFormatter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.NumberPicker
import ani.dantotsu.currContext
import ani.dantotsu.download.anime.AnimeDownloader

fun handleProgress(cont: LinearLayout, bar: View, empty: View, mediaId: Int, ep: String) {
    val cleanNum = MediaNameAdapter.findChapterNumber(ep)?.let {
        if (it % 1 == 0f) it.toInt().toString() else it.toString()
    } ?: MediaNameAdapter.findEpisodeNumber(ep)?.let {
        if (it % 1 == 0f) it.toInt().toString() else it.toString()
    }
    val curr = PrefManager.getNullableCustomVal("${mediaId}_${ep}", null, Long::class.java)
        ?: cleanNum?.let { PrefManager.getNullableCustomVal("${mediaId}_${it}", null, Long::class.java) }
    val max = PrefManager.getNullableCustomVal("${mediaId}_${ep}_max", null, Long::class.java)
        ?: cleanNum?.let { PrefManager.getNullableCustomVal("${mediaId}_${it}_max", null, Long::class.java) }
    if (curr != null && max != null && max > 0L) {
        cont.visibility = View.VISIBLE
        val div = (curr.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val barParams = bar.layoutParams as LinearLayout.LayoutParams
        barParams.weight = div
        bar.layoutParams = barParams
        val params = empty.layoutParams as LinearLayout.LayoutParams
        params.weight = 1f - div
        empty.layoutParams = params
    } else {
        cont.visibility = View.GONE
    }
}

@OptIn(UnstableApi::class)
class EpisodeAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: AnimeWatchFragment,
    var arr: List<Episode> = arrayListOf(),
    var offlineMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val context = fragment.requireContext()

    companion object {
        /** Partial bind: update download status text only (no Glide / animation). */
        private const val PAYLOAD_PROGRESS = "download_progress"
        /** Partial bind: update download chrome (icon/state) without full card rebind. */
        private const val PAYLOAD_DOWNLOAD_STATE = "download_state"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return (when (viewType) {
            0 -> EpisodeListViewHolder(
                ItemEpisodeListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> EpisodeGridViewHolder(
                ItemEpisodeGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> EpisodeCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        })
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (position !in arr.indices) return
        val ep = arr[position]
        val listHolder = holder as? EpisodeListViewHolder ?: return

        when {
            payloads.contains(PAYLOAD_PROGRESS) -> {
                listHolder.bindProgressText(ep.downloadProgress)
            }
            payloads.contains(PAYLOAD_DOWNLOAD_STATE) -> {
                // Icon / downloaded / failed — still skip Glide + setAnimation
                listHolder.bind(ep.number, ep.downloadProgress, ep.desc)
            }
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ep = arr[position]
        val isTorrent = ep.extra?.containsKey("torrentHash") == true || ep.link?.contains("127.0.0.1:8090") == true
        val title = if (isTorrent) {
            ep.title ?: ep.sEpisode?.name ?: ep.number
        } else if (!ep.title.isNullOrEmpty() && ep.title != "null") {
            ep.title?.let { MediaNameAdapter.removeEpisodeNumber(it) }
        } else {
            ep.number
        } ?: ""

        when (holder) {
            is EpisodeListViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)

                val thumb = ep.thumb?.let {
                    if (it.url.isNotEmpty()) {
                        if (it.url.startsWith("content://") || it.url.startsWith("file://")) {
                            it.url
                        } else {
                            GlideUrl(it.url) { it.headers }
                        }
                    } else null
                }
                Glide.with(binding.itemMediaImage).load(thumb ?: media.cover).override(400, 0)
                    .into(binding.itemMediaImage)
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = if (ep.number == title) "Episode $title" else title

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }

                if (ep.rating != null) {
                    binding.itemEpisodeRating.visibility = View.VISIBLE
                    binding.itemEpisodeRating.text = "★ ${ep.rating}"
                } else {
                    binding.itemEpisodeRating.visibility = View.GONE
                }

                if (ep.date != null) {
                    binding.itemEpisodeDate.visibility = View.VISIBLE
                    binding.itemEpisodeDate.text = ep.date
                } else {
                    binding.itemEpisodeDate.visibility = View.GONE
                }

                binding.itemEpisodeDesc.isVisible = !ep.desc.isNullOrBlank()
                binding.itemEpisodeDesc.text = ep.desc ?: ""
                holder.bind(ep.number, ep.downloadProgress, ep.desc)

                val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
                if (media.userProgress != null) {
                    if (epNum <= media.userProgress!!.toFloat()) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, ep.number)
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }

                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeGridViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)

                val thumb = ep.thumb?.let {
                    if (it.url.isNotEmpty()) {
                        if (it.url.startsWith("content://") || it.url.startsWith("file://")) {
                            it.url
                        } else {
                            GlideUrl(it.url) { it.headers }
                        }
                    } else null
                }
                Glide.with(binding.itemMediaImage).load(thumb ?: media.cover).override(400, 0)
                    .into(binding.itemMediaImage)

                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeTitle.text = title

                if (ep.rating != null) {
                    binding.itemEpisodeRating.visibility = View.VISIBLE
                    binding.itemEpisodeRating.text = "★ ${ep.rating}"
                } else {
                    binding.itemEpisodeRating.visibility = View.GONE
                }

                if (ep.date != null) {
                    binding.itemEpisodeDate.visibility = View.VISIBLE
                    binding.itemEpisodeDate.text = ep.date
                } else {
                    binding.itemEpisodeDate.visibility = View.GONE
                }

                if (ep.filler) {
                    binding.itemEpisodeFiller.visibility = View.VISIBLE
                    binding.itemEpisodeFillerView.visibility = View.VISIBLE
                } else {
                    binding.itemEpisodeFiller.visibility = View.GONE
                    binding.itemEpisodeFillerView.visibility = View.GONE
                }
                val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
                if (media.userProgress != null) {
                    if (epNum <= media.userProgress!!.toFloat()) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, ep.number)
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }

            is EpisodeCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemEpisodeNumber.text = ep.number
                binding.itemEpisodeFillerView.isVisible = ep.filler
                val epNum = MediaNameAdapter.findEpisodeNumber(ep.number) ?: ep.number.toFloatOrNull() ?: 9999f
                if (media.userProgress != null) {
                    if (epNum <= media.userProgress!!.toFloat())
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                    else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(media, ep.number)
                            true
                        }
                    }
                }
                handleProgress(
                    binding.itemMediaProgressCont,
                    binding.itemMediaProgress,
                    binding.itemMediaProgressEmpty,
                    media.id,
                    ep.number
                )
            }
        }
    }

    override fun getItemCount(): Int = arr.size
    private val downloadedEpisodes = mutableSetOf<String>()

    fun clearAllDownloaded() {
        downloadedEpisodes.clear()
    }

    fun startDownload(episodeNumber: String) {
        if (downloadedEpisodes.contains(episodeNumber) ||
            AnimeDownloader.isDownloading(media.id, episodeNumber))
                return
        AnimeDownloader.startDownload(media.id, episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = ""
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    @OptIn(UnstableApi::class)
    fun addToDownloadedEpisodes(episodeNumber: String, size: Double) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.add(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Downloaded" + ": (${"%.1f".format(size)} MB)"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun deleteDownload(episodeNumber: String) {
        downloadedEpisodes.remove(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = null
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    /** User cancel: back to idle (not "Failed"). */
    fun clearDownloadState(episodeNumber: String) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.remove(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = null
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    /** Real download failure from the service. */
    fun purgeDownload(episodeNumber: String) {
        AnimeDownloader.stopDownload(media.id, episodeNumber)
        downloadedEpisodes.remove(episodeNumber)
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position != -1) {
            arr[position].downloadProgress = "Failed"
            notifyItemChanged(position, PAYLOAD_DOWNLOAD_STATE)
        }
    }

    fun updateDownloadProgress(episodeNumber: String, progress: Int) {
        updateDownloadProgress(episodeNumber, progress, -1L, -1L)
    }

    fun updateDownloadProgress(
        episodeNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        // Ignore stale progress after cancel (engine may still emit briefly)
        if (!AnimeDownloader.isDownloading(media.id, episodeNumber)) return
        val position = arr.indexOfFirst { it.number == episodeNumber }
        if (position == -1) return
        val text = buildDownloadProgressText(progress, downloadedBytes, estimatedTotalBytes)
        // Skip no-op updates (same label) to avoid any rebind work
        if (arr[position].downloadProgress == text) return
        arr[position].downloadProgress = text
        notifyItemChanged(position, PAYLOAD_PROGRESS)
    }

    private fun buildDownloadProgressText(
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ): String {
        val hasDownloaded = downloadedBytes > 0L
        val hasEstimatedTotal = estimatedTotalBytes > 0L
        return if (hasDownloaded && hasEstimatedTotal) {
            "Downloading: $progress% (${SizeFormatter.formatBytes(downloadedBytes)} / ${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else if (hasEstimatedTotal) {
            "Downloading: $progress% (~${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else {
            "Downloading: $progress%"
        }
    }


    inner class EpisodeCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition < arr.size && bindingAdapterPosition >= 0)
                    fragment.onEpisodeClick(arr[bindingAdapterPosition].number)
            }
        }
    }

    inner class EpisodeGridViewHolder(val binding: ItemEpisodeGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition < arr.size && bindingAdapterPosition >= 0)
                    fragment.onEpisodeClick(arr[bindingAdapterPosition].number)
            }
        }
    }

    inner class EpisodeListViewHolder(val binding: ItemEpisodeListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()

        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition < arr.size && bindingAdapterPosition >= 0)
                    fragment.onEpisodeClick(arr[bindingAdapterPosition].number)
            }
            binding.itemDownload.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val episodeNumber = arr[bindingAdapterPosition].number
                    if(AnimeDownloader.isDownloading(media.id, episodeNumber)){
                        fragment.onAnimeEpisodeStopDownloadClick(episodeNumber)
                        return@setOnClickListener
                    } else if (downloadedEpisodes.contains(episodeNumber)) {
                        binding.root.context.customAlertDialog().apply {
                            setTitle("Delete Episode")
                            setMessage("Are you sure you want to delete Episode $episodeNumber?")
                            setPosButton(R.string.yes) {
                                fragment.onAnimeEpisodeRemoveDownloadClick(episodeNumber)
                            }
                            setNegButton(R.string.no)
                        }.show()
                        return@setOnClickListener
                    } else {
                        fragment.onAnimeEpisodesDownload(arrayListOf(episodeNumber))
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val episodeNumber = arr[bindingAdapterPosition].number
                    if (downloadedEpisodes.contains(episodeNumber)) {
                        //fragment.fixDownload(episodeNumber)
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Episode Deleter")
                            setMessage("Enter the number of episodes to delete")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                binding.root.context.customAlertDialog().apply {
                                    setTitle("Delete Episodes")
                                    setMessage("Are you sure you want to delete Episodes $episodeNumber -> ${arr[bindingAdapterPosition + input.value - 1].number}?")
                                    setPosButton(R.string.yes) {
                                        fragment.multiDelete(episodeNumber, input.value)
                                    }
                                    setNegButton(R.string.no)
                                }.show()
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                    else {
                        fragment.requireContext().customAlertDialog().apply {
                            setTitle("Multi Episode Downloader")
                            setMessage("Enter the number of episodes to download")
                            val input = NumberPicker(currContext())
                            input.minValue = 1
                            input.maxValue = itemCount - bindingAdapterPosition
                            input.value = 1
                            setCustomView(input)
                            setPosButton(R.string.ok) {
                                fragment.multiDownload(episodeNumber, input.value)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                    }
                }

                true
            }
            binding.itemEpisodeDesc.setOnClickListener {
                if (binding.itemEpisodeDesc.maxLines == 3)
                    binding.itemEpisodeDesc.maxLines = 100
                else
                    binding.itemEpisodeDesc.maxLines = 3
            }
        }

        /** Stop spin animator so cancel doesn't leave the icon tilted. */
        private fun stopDownloadAnimation() {
            binding.itemDownload.animate().cancel()
            binding.itemDownload.rotation = 0f
        }

        /** Progress-only update: status text, no icon/animation/Glide side effects. */
        fun bindProgressText(progress: String?) {
            if (progress.isNullOrEmpty()) {
                binding.itemDownloadStatus.visibility = View.GONE
                return
            }
            binding.itemEpisodeDesc.visibility = View.GONE
            binding.itemDownloadStatus.visibility = View.VISIBLE
            binding.itemDownloadStatus.text = progress
        }

        fun bind(episodeNumber: String, progress: String?, desc: String?) {
            if (progress != null) {
                binding.itemEpisodeDesc.visibility = View.GONE
                if (progress == "")
                    binding.itemDownloadStatus.visibility = View.GONE
                else
                    binding.itemDownloadStatus.visibility = View.VISIBLE
                binding.itemDownloadStatus.text = progress
            } else {
                binding.itemDownloadStatus.visibility = View.GONE
                binding.itemDownloadStatus.text = ""
            }
            if (media.format == "LOCAL") {
                binding.itemDownload.visibility = View.GONE
            } else {
                binding.itemDownload.visibility = View.VISIBLE
                if (AnimeDownloader.isDownloading(media.id, episodeNumber)) {
                    stopDownloadAnimation()
                    binding.itemDownload.setImageResource(R.drawable.ic_sync)
                    startOrContinueRotation(episodeNumber)
                    binding.itemEpisodeDesc.visibility = View.GONE
                } else if (downloadedEpisodes.contains(episodeNumber)) {
                    stopDownloadAnimation()
                    binding.itemEpisodeDesc.visibility = View.GONE
                    binding.itemDownloadStatus.visibility = View.VISIBLE

                    binding.itemDownload.setImageResource(R.drawable.ic_circle_check)
                    binding.itemDownload.postDelayed({
                        binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                        stopDownloadAnimation()
                    }, 1000)
                } else {
                    stopDownloadAnimation()
                    binding.itemDownloadStatus.visibility = View.GONE
                    binding.itemEpisodeDesc.visibility =
                        if (desc != null && desc.trim(' ') != "") View.VISIBLE else View.GONE

                    binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                }
            }
        }

        private fun startOrContinueRotation(episodeNumber: String) {
            if (!isRotationCoroutineRunningFor(episodeNumber)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(episodeNumber)
                    try {
                        while (AnimeDownloader.isDownloading(media.id, episodeNumber)) {
                            binding.itemDownload.animate()
                                .rotationBy(360f)
                                .setDuration(1000)
                                .setInterpolator(LinearInterpolator())
                                .start()
                            delay(1000)
                        }
                    } finally {
                        activeCoroutines.remove(episodeNumber)
                        // Cancel in-flight ViewPropertyAnimator so icon isn't left mid-spin
                        stopDownloadAnimation()
                    }
                }
            }
        }

        private fun isRotationCoroutineRunningFor(episodeNumber: String): Boolean {
            return episodeNumber in activeCoroutines
        }
    }

    fun updateType(t: Int) {
        type = t
    }
}
