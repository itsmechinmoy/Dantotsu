package ani.dantotsu.media

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.initActivity
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaListViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaListViewBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }

        setContentView(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val secondaryTextColor = getThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        val mediaList =
            passedMedia ?: intent.getSerialized("media") as? ArrayList<Media> ?: ArrayList()
        if (passedMedia != null) passedMedia = null
        val view = PrefManager.getCustomVal("mediaView", 0)
        var mediaView: View = when (view) {
            1 -> binding.mediaList
            0 -> binding.mediaGrid
            else -> binding.mediaGrid
        }
        mediaView.alpha = 1f
        fun changeView(mode: Int, current: View) {
            mediaView.alpha = 0.33f
            mediaView = current
            current.alpha = 1f
            PrefManager.setCustomVal("mediaView", mode)
            binding.mediaRecyclerView.adapter = MediaAdaptor(mode, mediaList, this)
            binding.mediaRecyclerView.layoutManager = GridLayoutManager(
                this,
                if (mode == 1) 1 else (screenWidth / 120f).toInt()
            )
        }
        binding.mediaList.setOnClickListener {
            changeView(1, binding.mediaList)
        }
        binding.mediaGrid.setOnClickListener {
            changeView(0, binding.mediaGrid)
        }
        val text = "${intent.getStringExtra("title")} (${mediaList.count()})"
        binding.listTitle.text = text
        binding.mediaRecyclerView.adapter = MediaAdaptor(view, mediaList, this)
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(
            this,
            if (view == 1) 1 else (screenWidth / 120f).toInt()
        )

        val isRecommended = intent.getStringExtra("type") == "RECOMMENDED" ||
                intent.getStringExtra("title") == getString(R.string.recommended)
        if (isRecommended && !PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
            var page = intent.getIntExtra("page", 1)
            var loading = false
            var hasNextPage = true

            binding.mediaRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                    if (!v.canScrollVertically(1)) {
                        if (hasNextPage && !loading) {
                            loading = true
                            binding.mediaListProgressBar.visibility = View.VISIBLE
                            lifecycleScope.launch(Dispatchers.IO) {
                                var curPage = page
                                val addedMedia = arrayListOf<Media>()
                                var attempts = 0
                                while (addedMedia.isEmpty() && hasNextPage && attempts < 3) {
                                    attempts++
                                    curPage++
                                    try {
                                        val (newMedia, hasNext) = Anilist.query.getRecommendations(curPage)
                                        hasNextPage = hasNext
                                        page = curPage
                                        val existingIds = mediaList.map { it.id }.toSet()
                                        val uniqueNew = newMedia.filter { it.id !in existingIds }
                                        if (uniqueNew.isNotEmpty()) {
                                            addedMedia.addAll(uniqueNew)
                                        }
                                    } catch (e: Exception) {
                                        Logger.log("Failed to load more recommendations: ${e.message}")
                                        break
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    if (addedMedia.isNotEmpty()) {
                                        val startPos = mediaList.size
                                        mediaList.addAll(addedMedia)
                                        binding.mediaRecyclerView.adapter?.notifyItemRangeInserted(startPos, addedMedia.size)
                                        val title = intent.getStringExtra("title") ?: getString(R.string.recommended)
                                        binding.listTitle.text = "$title (${mediaList.size})"
                                    }
                                    loading = false
                                    binding.mediaListProgressBar.visibility = View.GONE
                                }
                            }
                        }
                    }
                    super.onScrolled(v, dx, dy)
                }
            })
        }
    }

    companion object {
        var passedMedia: ArrayList<Media>? = null
    }
}
