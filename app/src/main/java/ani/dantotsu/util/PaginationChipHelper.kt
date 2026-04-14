package ani.dantotsu.util

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.media.MediaNameAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

object PaginationChipHelper {
    fun buildChips(
        context: Context,
        chipGroup: ChipGroup,
        scrollView: HorizontalScrollView,
        limit: Int,
        names: Array<String>,
        arr: Array<Int>,
        selected: Int = 0,
        onChipClicked: (position: Int, start: Int, end: Int) -> Unit
    ) {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        var select: Chip? = null
        for (position in arr.indices) {
            val last = if (position + 1 == arr.size) names.size else (limit * (position + 1))
            val chip = ItemChipBinding.inflate(
                LayoutInflater.from(context),
                chipGroup,
                false
            ).root
            chip.isCheckable = true
            
            fun selectedChip() {
                chip.isChecked = true
                scrollView.smoothScrollTo(
                    (chip.left - screenWidth / 2) + (chip.width / 2),
                    0
                )
            }

            val startChapterString = MediaNameAdapter.findChapterNumber(names[limit * position])?.let { "Ch.%.1f".format(it) } ?: names[limit * position]
            val endChapterString = MediaNameAdapter.findChapterNumber(names[last - 1])?.let { "Ch.%.1f".format(it) } ?: names[last - 1]
            chip.text = "$startChapterString - $endChapterString"
            chip.setTextColor(
                ContextCompat.getColorStateList(
                    context,
                    R.color.chip_text_color
                )
            )

            chip.setOnClickListener {
                selectedChip()
                onChipClicked(position, limit * position, last - 1)
            }
            chipGroup.addView(chip)
            if (selected == position) {
                selectedChip()
                select = chip
            }
        }
        if (select != null) {
            scrollView.post {
                scrollView.scrollTo(
                    (select!!.left - screenWidth / 2) + (select!!.width / 2),
                    0
                )
            }
        }
    }
}
