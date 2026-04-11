package ani.dantotsu.media.comments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.client
import ani.dantotsu.databinding.BottomSheetGifPickerBinding
import ani.dantotsu.databinding.ItemGifBinding
import ani.dantotsu.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GifPickerBottomDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetGifPickerBinding? = null
    private val binding get() = _binding!!

    private var onGifSelected: ((String) -> Unit)? = null
    private var searchJob: Job? = null

    private val apiKey = "29Hflu9yLfJoQy9uVbrSg5pPkaGgIr7CDDpx0bCtVxQFBTnNWXb4moqYsR70Yzzv"
    private val baseUrl = "https://api.klipy.com"

    fun setOnGifSelectedListener(listener: (String) -> Unit) {
        onGifSelected = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetGifPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        binding.gifRecycler.layoutManager = GridLayoutManager(context, 2)

        binding.gifSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""

                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(800)
                    if (query.isEmpty()) loadTrending()
                    else searchGifs(query)
                }
            }
        })

        loadTrending()
    }

    private fun loadTrending() {
        binding.gifProgressBar.visibility = View.VISIBLE
        binding.gifRecycler.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val gifs = try {
                val response = client.get(
                    "$baseUrl/v2/featured?key=$apiKey&media_filter=tinygif&limit=20&contentfilter=high",
                    headers = mapOf("Content-Type" to "application/json")
                )
                parseGifs(response.text)
            } catch (_: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                binding.gifProgressBar.visibility = View.GONE
                binding.gifRecycler.visibility = View.VISIBLE

                binding.gifRecycler.adapter = GifAdapter(gifs) { url ->
                    onGifSelected?.invoke(url)
                    dismiss()
                }
            }
        }
    }

    private fun searchGifs(query: String) {
        binding.gifProgressBar.visibility = View.VISIBLE
        binding.gifRecycler.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val gifs = try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val response = client.get(
                    "$baseUrl/v2/search?key=$apiKey&q=$encodedQuery&media_filter=tinygif&limit=20&contentfilter=high",
                    headers = mapOf("Content-Type" to "application/json")
                )
                parseGifs(response.text)
            } catch (_: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                binding.gifProgressBar.visibility = View.GONE
                binding.gifRecycler.visibility = View.VISIBLE

                binding.gifRecycler.adapter = GifAdapter(gifs) { url ->
                    onGifSelected?.invoke(url)
                    dismiss()
                }
            }
        }
    }

    private fun parseGifs(json: String): List<String> {
        return try {
            val root = JSONObject(json)
            val results = root.getJSONArray("results")
            val urls = mutableListOf<String>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val formats = item.optJSONObject("media_formats")
                val tinygif = formats?.optJSONObject("tinygif")
                val url = tinygif?.optString("url") ?: item.optString("url")

                if (url.isNotEmpty()) urls.add(url)
            }

            urls
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class GifAdapter(
        private val gifs: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<GifAdapter.GifViewHolder>() {

        class GifViewHolder(val binding: ItemGifBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
            val binding = ItemGifBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return GifViewHolder(binding)
        }

        override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
            val url = gifs[position]

            holder.binding.gifImage.loadImage(url)
            holder.binding.root.setOnClickListener { onClick(url) }
        }

        override fun getItemCount() = gifs.size
    }

    companion object {
        fun newInstance(): GifPickerBottomDialog = GifPickerBottomDialog()
    }
}