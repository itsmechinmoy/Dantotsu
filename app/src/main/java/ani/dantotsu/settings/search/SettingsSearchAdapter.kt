package ani.dantotsu.settings.search

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSettingsSearchResultBinding
import ani.dantotsu.setAnimation

class SettingsSearchAdapter(
    private var results: List<SearchableSetting>,
    private val onItemClick: ((SearchableSetting) -> Unit)? = null
) : RecyclerView.Adapter<SettingsSearchAdapter.SearchResultViewHolder>() {

    companion object {
        const val EXTRA_HIGHLIGHT_KEY = "extra_highlight_key"
    }

    inner class SearchResultViewHolder(val binding: ItemSettingsSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val binding = ItemSettingsSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SearchResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        val item = results[position]
        val binding = holder.binding
        val context = binding.root.context

        setAnimation(context, binding.root)

        binding.searchResultTitle.text = item.title

        if (!item.desc.isNullOrBlank()) {
            binding.searchResultDesc.visibility = View.VISIBLE
            binding.searchResultDesc.text = item.desc
        } else {
            binding.searchResultDesc.visibility = View.GONE
        }

        if (item.breadcrumbs.isNotBlank()) {
            binding.searchResultBreadcrumbs.visibility = View.VISIBLE
            binding.searchResultBreadcrumbs.text = item.breadcrumbs
        } else {
            binding.searchResultBreadcrumbs.visibility = View.GONE
        }

        try {
            binding.searchResultIcon.setImageDrawable(
                ContextCompat.getDrawable(context, item.icon)
            )
        } catch (_: Exception) {
            // fallback
        }

        binding.searchResultLayout.setOnClickListener {
            if (onItemClick != null) {
                onItemClick.invoke(item)
            } else {
                launchSetting(context, item)
            }
        }
    }

    override fun getItemCount(): Int = results.size

    fun updateResults(newResults: List<SearchableSetting>) {
        results = newResults
        notifyDataSetChanged()
    }

    private fun launchSetting(context: Context, item: SearchableSetting) {
        val intent = Intent(context, item.targetActivity).apply {
            putExtra(EXTRA_HIGHLIGHT_KEY, item.highlightKey)
        }
        context.startActivity(intent)
    }
}
