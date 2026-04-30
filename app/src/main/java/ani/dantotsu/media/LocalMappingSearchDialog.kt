package ani.dantotsu.media

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.math.MathUtils.clamp
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.BottomSheetSourceSearchBinding
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class LocalMappingSearchDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourceSearchBinding? = null
    private val binding get() = _binding!!
    private var searched = false

    var folderName: String? = null
    var searchType: String = "ANIME" // ANIME or MANGA
    var searchFormat: String? = null // NOVEL for local novels
    var onMappingSelected: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSourceSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }

        val scope = viewLifecycleOwner.lifecycleScope
        val imm =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE
        binding.searchRecyclerView.visibility = View.GONE
        binding.searchProgress.visibility = View.VISIBLE

        binding.searchSourceTitle.text = "Map to AniList"
        binding.searchBarText.setText(folderName ?: "")

        fun search() {
            binding.searchBarText.clearFocus()
            imm.hideSoftInputFromWindow(binding.searchBarText.windowToken, 0)
            binding.searchRecyclerView.visibility = View.GONE
            binding.searchProgress.visibility = View.VISIBLE
            scope.launch {
                val results = withContext(Dispatchers.IO) {
                    tryWithSuspend {
                        Anilist.query.searchAniManga(
                            type = searchType,
                            search = binding.searchBarText.text.toString(),
                            format = searchFormat
                        )
                    }
                }
                if (results != null && results.results.isNotEmpty()) {
                    binding.searchRecyclerView.visibility = View.VISIBLE
                    binding.searchProgress.visibility = View.GONE
                    binding.searchRecyclerView.adapter =
                        LocalMappingResultAdapter(results.results) { selectedMedia ->
                            // Save the mapping
                            val mapKey = folderName ?: return@LocalMappingResultAdapter
                            PrefManager.setCustomVal("local_mapping_$mapKey", selectedMedia.id)
                            snackString("Mapped to: ${selectedMedia.userPreferredName}")
                            onMappingSelected?.invoke(selectedMedia.id)
                            dismiss()
                        }
                    binding.searchRecyclerView.layoutManager = GridLayoutManager(
                        requireActivity(),
                        clamp(
                            requireActivity().resources.displayMetrics.widthPixels / 124f.px,
                            1,
                            4
                        )
                    )
                } else {
                    binding.searchProgress.visibility = View.GONE
                    snackString("No results found")
                }
            }
        }

        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            return@setOnEditorActionListener when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    search()
                    true
                }
                else -> false
            }
        }
        binding.searchBar.setEndIconOnClickListener { search() }
        if (!searched) search()
        searched = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            folderName: String,
            isAnime: Boolean,
            isNovel: Boolean = false,
            onMappingSelected: (Int) -> Unit
        ): LocalMappingSearchDialog {
            return LocalMappingSearchDialog().apply {
                this.folderName = folderName
                this.searchType = if (isAnime) "ANIME" else "MANGA"
                this.searchFormat = if (isNovel) "NOVEL" else null
                this.onMappingSelected = onMappingSelected
            }
        }
    }
}


private class LocalMappingResultAdapter(
    private val results: List<Media>,
    private val onItemClick: (Media) -> Unit
) : RecyclerView.Adapter<LocalMappingResultAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount(): Int = results.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = results[position]
        holder.binding.itemCompactImage.loadImage(media.cover)
        holder.binding.itemCompactTitle.text = media.userPreferredName
        holder.binding.itemCompactScore.text = media.meanScore?.let { "$it%" } ?: ""
        holder.binding.root.setOnClickListener {
            onItemClick(media)
        }
    }
}
