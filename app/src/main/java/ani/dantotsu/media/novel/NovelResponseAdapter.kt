package ani.dantotsu.media.novel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.databinding.ItemNovelResponseBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.setAnimation
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog

class NovelResponseAdapter(
    val fragment: NovelReadFragment,
    val downloadTriggerCallback: DownloadTriggerCallback,
    val downloadedCheckCallback: DownloadedCheckCallback
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val list: MutableList<ShowResponse> = mutableListOf()

    // 0 = List, 1 = Compact, 2 = Cover (default)
    private var type: Int = 2

    inner class CoverViewHolder(val binding: ItemNovelResponseBinding) :
        RecyclerView.ViewHolder(binding.root) {
        @Suppress("ComplexMethod", "LongMethod")
        fun bind(novel: ShowResponse, position: Int) {
            setAnimation(fragment.requireContext(), binding.root)
            binding.itemMediaImage.loadImage(novel.coverUrl, 400, 0)

            val color = fragment.requireContext()
                .getThemeColor(com.google.android.material.R.attr.colorOnBackground)
            binding.itemEpisodeTitle.text = novel.name
            binding.itemEpisodeFiller.text =
                if (fragment.media.format == "LOCAL" || fragment.media.format == "LOCAL_NOVEL") {
                    ""
                } else if (downloadedCheckCallback.downloadedCheck(novel)) {
                    "Downloaded"
                } else {
                    novel.extra?.get("0") ?: ""
                }
            if (binding.itemEpisodeFiller.text.contains("Downloading")) {
                binding.itemEpisodeFiller.setTextColor(
                    ContextCompat.getColor(fragment.requireContext(), android.R.color.holo_blue_light)
                )
            } else if (binding.itemEpisodeFiller.text.contains("Downloaded")) {
                binding.itemEpisodeFiller.setTextColor(
                    ContextCompat.getColor(fragment.requireContext(), android.R.color.holo_green_light)
                )
            } else {
                binding.itemEpisodeFiller.setTextColor(color)
            }
            binding.itemEpisodeDesc2.text = novel.extra?.get("1") ?: ""
            val desc = novel.extra?.get("2")
            binding.itemEpisodeDesc.isVisible = !desc.isNullOrBlank()
            binding.itemEpisodeDesc.text = desc ?: ""

            setupClickListeners(binding.root, novel)
        }
    }

    inner class ListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        private val activeCoroutines = mutableSetOf<String>()

        fun bind(novel: ShowResponse, position: Int) {
            binding.itemChapterNumber.text = novel.name
            updateDownloadButtonState(novel)
            binding.itemDownload.setOnClickListener { handleSingleDownloadClick(novel, it) }
            binding.itemDownload.setOnLongClickListener { handleMultiChapterLongClick(it) }
            bindChapterExtra(novel)
            binding.itemEpisodeViewed.visibility = View.GONE
            setupClickListeners(binding.root, novel)
        }

        private fun updateDownloadButtonState(novel: ShowResponse) {
            if (fragment.media.format == "LOCAL" || fragment.media.format == "LOCAL_NOVEL") {
                binding.itemDownload.visibility = View.GONE
                return
            }
            binding.itemDownload.visibility = View.VISIBLE
            if (activeDownloads.contains(novel.link)) {
                binding.itemDownload.setImageResource(R.drawable.ic_sync)
                startOrContinueRotation(novel.link) {
                    binding.itemDownload.rotation = 0f
                }
            } else if (downloadedCheckCallback.downloadedCheck(novel) || downloadedChapters.contains(novel.link)) {
                binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                binding.itemDownload.rotation = 0f
            } else {
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }
        }

        private fun handleSingleDownloadClick(novel: ShowResponse, view: View) {
            if (activeDownloads.contains(novel.link)) return
            if (downloadedCheckCallback.downloadedCheck(novel) || downloadedChapters.contains(novel.link)) {
                view.context.customAlertDialog().apply {
                    setTitle("Delete Chapter")
                    setMessage("Are you sure you want to delete ${novel.name}?")
                    setPosButton(R.string.delete) {
                        fragment.deleteDownload(novel)
                        deleteDownload(novel.link)
                    }
                    setNegButton(R.string.cancel)
                    show()
                }
            } else {
                downloadTriggerCallback.downloadTrigger(
                    ani.dantotsu.media.novel.NovelDownloadPackage(
                        novel.link,
                        novel.coverUrl.url,
                        novel.name,
                        novel.link
                    )
                )
            }
        }

        private fun handleMultiChapterLongClick(view: View): Boolean {
            if (bindingAdapterPosition !in 0 until list.size) return true
            val currentNovel = list[bindingAdapterPosition]
            val isDownloadedOrActive = activeDownloads.contains(currentNovel.link) ||
                    downloadedCheckCallback.downloadedCheck(currentNovel) ||
                    downloadedChapters.contains(currentNovel.link)

            if (isDownloadedOrActive) {
                showMultiDeleteDialog(view, bindingAdapterPosition)
            } else {
                showMultiDownloadDialog(view, bindingAdapterPosition)
            }
            return true
        }

        private fun showMultiDeleteDialog(view: View, position: Int) {
            view.context.customAlertDialog().apply {
                setTitle("Multi Chapter Deleter")
                setMessage("Enter the number of chapters to delete")
                val input = android.widget.NumberPicker(ani.dantotsu.currContext()).apply {
                    minValue = 1
                    maxValue = itemCount - position
                    value = 1
                }
                setCustomView(input)
                setPosButton(R.string.ok) {
                    binding.root.context.customAlertDialog().apply {
                        setTitle("Delete Chapters")
                        setMessage("Are you sure you want to delete the next ${input.value} chapters?")
                        setPosButton(R.string.yes) {
                            deleteNChaptersFrom(position, input.value)
                        }
                        setNegButton(R.string.no)
                    }.show()
                }
                setNegButton(R.string.cancel)
                show()
            }
        }

        private fun showMultiDownloadDialog(view: View, position: Int) {
            view.context.customAlertDialog().apply {
                setTitle("Multi Chapter Downloader")
                setMessage("Enter the number of chapters to download")
                val input = android.widget.NumberPicker(ani.dantotsu.currContext()).apply {
                    minValue = 1
                    maxValue = itemCount - position
                    value = 1
                }
                setCustomView(input)
                setPosButton("OK") {
                    downloadNChaptersFrom(position, input.value)
                }
                setNegButton("Cancel")
                show()
            }
        }

        private fun bindChapterExtra(novel: ShowResponse) {
            val releaseTime = novel.extra?.get("releaseTime")
            val sourceName = novel.extra?.get("sourceName")
            val hasReleaseTime = !releaseTime.isNullOrBlank()
            val hasSourceName = !sourceName.isNullOrBlank()

            if (hasReleaseTime || hasSourceName) {
                binding.itemChapterDateLayout.visibility = View.VISIBLE
                binding.itemChapterDate.visibility = if (hasReleaseTime) View.VISIBLE else View.GONE
                if (hasReleaseTime) binding.itemChapterDate.text = releaseTime

                binding.itemChapterScan.visibility = if (hasSourceName) View.VISIBLE else View.GONE
                if (hasSourceName) binding.itemChapterScan.text = sourceName

                binding.itemChapterDateDivider.visibility =
                    if (hasReleaseTime && hasSourceName) View.VISIBLE else View.GONE
            } else {
                binding.itemChapterDateLayout.visibility = View.GONE
            }
        }

        private fun startOrContinueRotation(link: String, resetRotation: () -> Unit) {
            if (!activeCoroutines.contains(link)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(link)
                    while (activeDownloads.contains(link)) {
                        binding.itemDownload.animate().rotationBy(360f).setDuration(1000)
                            .setInterpolator(android.view.animation.LinearInterpolator()).start()
                        delay(1000)
                    }
                    activeCoroutines.remove(link)
                    resetRotation()
                }
            }
        }
    }

    inner class CompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        @Suppress("ComplexMethod", "LongMethod")
        fun bind(novel: ShowResponse, position: Int) {
            val chapterNumStr = novel.extra?.get("chapterNumber") ?: novel.name
            val parsedNumber = ani.dantotsu.media.MediaNameAdapter.findChapterNumber(chapterNumStr)
            
            val label = if (parsedNumber != null) {
                if (parsedNumber == parsedNumber.toLong().toFloat()) parsedNumber.toLong().toString()
                else parsedNumber.toString()
            } else {
                (position + 1).toString()
            }
            binding.itemEpisodeNumber.text = label
            setupClickListeners(binding.root, novel)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> ListViewHolder(
                ItemChapterListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            1 -> CompactViewHolder(
                ItemEpisodeCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> CoverViewHolder(
                ItemNovelResponseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun getItemViewType(position: Int): Int = type

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val novel = list[position]

        when (holder) {
            is CoverViewHolder -> holder.bind(novel, position)
            is ListViewHolder -> holder.bind(novel, position)
            is CompactViewHolder -> holder.bind(novel, position)
        }
    }

    private fun setupClickListeners(root: View, novel: ShowResponse) {
        root.setOnClickListener {
            fragment.onNovelClick(novel)
        }

        root.setOnLongClickListener {
            it.context.customAlertDialog().apply {
                setTitle("Delete ${novel.name}?")
                setMessage("Are you sure you want to delete ${novel.name}?")
                setPosButton(R.string.yes) {
                    downloadedCheckCallback.deleteDownload(novel)
                    deleteDownload(novel.link)
                    snackString("Deleted ${novel.name}")
                }
                setNegButton(R.string.no)
                show()
            }
            true
        }
    }

    fun updateType(newType: Int) {
        if (type != newType) {
            type = newType
            notifyDataSetChanged()
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    
    fun isDownloading(link: String): Boolean = activeDownloads.contains(link)

    private val downloadedChapters = mutableSetOf<String>()

    fun startDownload(link: String) {
        activeDownloads.add(link)
        val position = list.indexOfFirst { it.link == link }
        if (position != -1) {
            list[position].extra?.remove("0")
            list[position].extra?.set("0", "Downloading: 0%")
            notifyItemChanged(position)
        }

    }

    fun stopDownload(link: String) {
        activeDownloads.remove(link)
        downloadedChapters.add(link)
        val position = list.indexOfFirst { it.link == link }
        if (position != -1) {
            list[position].extra?.remove("0")
            list[position].extra?.set("0", "Downloaded")
            notifyItemChanged(position)
        }
    }

    fun deleteDownload(link: String) {
        activeDownloads.remove(link)
        downloadedChapters.remove(link)
        val position = list.indexOfFirst { it.link == link }
        if (position != -1) {
            list[position].extra?.remove("0")
            notifyItemChanged(position)
        }
    }
    
    fun downloadNChaptersFrom(position: Int, n: Int) {
        if (position < 0 || position >= list.size) return
        for (i in 0 until n) {
            if (position + i < list.size) {
                val novel = list[position + i]
                if (activeDownloads.contains(novel.link)) {
                    continue
                } else if (downloadedCheckCallback.downloadedCheck(novel) || downloadedChapters.contains(novel.link)) {
                    continue
                } else {
                    downloadTriggerCallback.downloadTrigger(
                        ani.dantotsu.media.novel.NovelDownloadPackage(novel.link, novel.coverUrl.url, novel.name, novel.link)
                    )
                    startDownload(novel.link)
                }
            }
        }
    }

    fun deleteNChaptersFrom(position: Int, n: Int) {
        if (position < 0 || position >= list.size) return
        for (i in 0 until n) {
            if (position + i < list.size) {
                val novel = list[position + i]
                if (activeDownloads.contains(novel.link)) {
                    // Ignore active downloads
                } else if (downloadedCheckCallback.downloadedCheck(novel) || downloadedChapters.contains(novel.link)) {
                    fragment.deleteDownload(novel)
                    deleteDownload(novel.link)
                }
            }
        }
    }

    fun purgeDownload(link: String) {
        activeDownloads.remove(link)
        downloadedChapters.remove(link)
        val position = list.indexOfFirst { it.link == link }
        if (position != -1) {
            list[position].extra?.remove("0")
            list[position].extra?.set("0", "Failed")
            notifyItemChanged(position)
        }
    }

    fun updateDownloadProgress(link: String, progress: Int) {
        if (!activeDownloads.contains(link)) {
            activeDownloads.add(link)
        }
        val position = list.indexOfFirst { it.link == link }
        if (position != -1) {
            list[position].extra?.remove("0")
            list[position].extra?.set("0", "Downloading: $progress%")
            Logger.log("updateDownloadProgress: $progress, position: $position")
            notifyItemChanged(position)
        }
    }

    fun submitList(it: List<ShowResponse>) {
        val old = list.size
        list.addAll(it)
        notifyItemRangeInserted(old, it.size)
    }

    fun clear() {
        val size = list.size
        list.clear()
        notifyItemRangeRemoved(0, size)
    }
}

data class NovelDownloadPackage(
    val link: String,
    val coverUrl: String,
    val novelName: String,
    val originalLink: String
)