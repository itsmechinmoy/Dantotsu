package ani.dantotsu.media.novel

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemCharacterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.ShowResponse

class NovelSearchResultAdapter(
    private val results: List<ShowResponse>,
    private val onClick: (ShowResponse) -> Unit
) : RecyclerView.Adapter<NovelSearchResultAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                if (bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    onClick(results[bindingAdapterPosition])
                }
            }
            var expanded = true
            itemView.setOnLongClickListener {
                expanded = !expanded
                binding.itemCompactTitle.isSingleLine = expanded
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]
        holder.binding.itemCompactImage.loadImage(item.coverUrl, 200)
        holder.binding.itemCompactTitle.isSelected = true
        holder.binding.itemCompactTitle.text = item.name
    }

    override fun getItemCount(): Int = results.size
}
