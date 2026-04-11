package ani.dantotsu.media.comments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GifPickerBottomDialog : BottomSheetDialogFragment() {

    private var onGifSelected: ((String) -> Unit)? = null
    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var poweredBy: TextView
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
        return inflater.inflate(R.layout.bottom_sheet_gif_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.gifRecycler)
        searchInput = view.findViewById(R.id.gifSearchInput)
        progressBar = view.findViewById(R.id.gifProgressBar)
        poweredBy = view.findViewById(R.id.gifPoweredBy)

        recycler.layoutManager = GridLayoutManager(context, 2)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(400)
                    if (query.isEmpty()) loadTrending() else searchGifs(query)
                }
            }
        })

        loadTrending()
    }

    private fun loadTrending() {
        progressBar.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        CoroutineScope(Dispatchers.IO).launch {
            val gifs = try {
                val response = client.get(
                    "$baseUrl/v2/featured?key=$apiKey&media_filter=tinygif&limit=20&contentfilter=high",
                    headers = mapOf("Content-Type" to "application/json")
                )
                parseGifs(response.text)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                recycler.adapter = GifAdapter(gifs) { url ->
                    onGifSelected?.invoke(url)
                    dismiss()
                }
            }
        }
    }

    private fun searchGifs(query: String) {
        progressBar.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        CoroutineScope(Dispatchers.IO).launch {
            val gifs = try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val response = client.get(
                    "$baseUrl/v2/search?key=$apiKey&q=$encodedQuery&media_filter=tinygif&limit=20&contentfilter=high",
                    headers = mapOf("Content-Type" to "application/json")
                )
                parseGifs(response.text)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                recycler.adapter = GifAdapter(gifs) { url ->
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    class GifAdapter(
        private val gifs: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<GifAdapter.GifViewHolder>() {

        inner class GifViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.gifImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gif, parent, false)
            return GifViewHolder(view)
        }

        override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
            val url = gifs[position]
            holder.image.loadImage(url)
            holder.itemView.setOnClickListener { onClick(url) }
        }

        override fun getItemCount() = gifs.size
    }

    companion object {
        fun newInstance(): GifPickerBottomDialog = GifPickerBottomDialog()
    }
}