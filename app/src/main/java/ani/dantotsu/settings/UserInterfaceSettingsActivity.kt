package ani.dantotsu.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityUserInterfaceSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.HOME_LAYOUT_FIXED_INDEX
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class UserInterfaceSettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivityUserInterfaceSettingsBinding
    private val ui = "ui_settings"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityUserInterfaceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.uiSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.uiSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.uiSettingsHomeLayout.setOnClickListener {
            val currentVisibility = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout).toMutableList()
            var currentOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder).toMutableList()
            val views = resources.getStringArray(R.array.home_layouts)
            val fixedIndex = HOME_LAYOUT_FIXED_INDEX

            if (currentVisibility.size < views.size) {
                repeat(views.size - currentVisibility.size) { currentVisibility.add(true) }
            } else if (currentVisibility.size > views.size) {
                currentVisibility.subList(views.size, currentVisibility.size).clear()
            }

            val reorderable = views.indices.filter { it != fixedIndex }
            if (currentOrder.isEmpty()) {
                currentOrder = reorderable.toMutableList()
            } else {
                val sanitizedOrder = currentOrder.filter { it in reorderable }.distinct().toMutableList()
                val missing = reorderable.filterNot { it in sanitizedOrder }
                sanitizedOrder.addAll(missing)
                currentOrder = sanitizedOrder
            }

            val displayList = mutableListOf(fixedIndex)
            displayList.addAll(currentOrder.filter { it != fixedIndex })

            val recyclerView = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@UserInterfaceSettingsActivity)
                setPadding(0, 32, 0, 0)
                clipToPadding = false
            }
            val adapter = HomeLayoutAdapter(displayList, views, currentVisibility)
            recyclerView.adapter = adapter

            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition

                    if (fromPos == 0 || toPos == 0) return false

                    val item = displayList.removeAt(fromPos)
                    displayList.add(toPos, item)
                    adapter.notifyItemMoved(fromPos, toPos)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.elevation = 0f
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.elevation = 8f
                    }
                }
            })
            itemTouchHelper.attachToRecyclerView(recyclerView)

            customAlertDialog().apply {
                setTitle(getString(R.string.home_layout_show))
                setCustomView(recyclerView)
                setPosButton(R.string.ok) {
                    PrefManager.setVal(PrefName.HomeLayout, currentVisibility)
                    PrefManager.setVal(PrefName.HomeLayoutOrder, displayList.drop(1))
                    restartApp()
                }
                setNegButton(R.string.cancel, null)
                show()
            }
        }

        binding.uiSettingsSmallView.isChecked = PrefManager.getVal(PrefName.SmallView)
        binding.uiSettingsSmallView.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.SmallView, isChecked)
            restartApp()
        }

        binding.uiSettingsImmersive.isChecked = PrefManager.getVal(PrefName.ImmersiveMode)
        binding.uiSettingsImmersive.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ImmersiveMode, isChecked)
            restartApp()
        }
        binding.uiSettingsHideRedDot.isChecked =
            !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot)
        binding.uiSettingsHideRedDot.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ShowNotificationRedDot, !isChecked)
        }
        binding.uiSettingsBannerAnimation.isChecked = PrefManager.getVal(PrefName.BannerAnimations)
        binding.uiSettingsBannerAnimation.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.BannerAnimations, isChecked)
            restartApp()
        }

        binding.uiSettingsLayoutAnimation.isChecked = PrefManager.getVal(PrefName.LayoutAnimations)
        binding.uiSettingsLayoutAnimation.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.LayoutAnimations, isChecked)
            restartApp()
        }

        binding.uiSettingsTrendingScroller.isChecked = PrefManager.getVal(PrefName.TrendingScroller)
        binding.uiSettingsTrendingScroller.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.TrendingScroller, isChecked)
        }

        val map = mapOf(
            2f to 0.5f,
            1.75f to 0.625f,
            1.5f to 0.75f,
            1.25f to 0.875f,
            1f to 1f,
            0.75f to 1.25f,
            0.5f to 1.5f,
            0.25f to 1.75f,
            0f to 0f
        )
        val mapReverse = map.map { it.value to it.key }.toMap()
        binding.uiSettingsAnimationSpeed.value =
            mapReverse[PrefManager.getVal(PrefName.AnimationSpeed)] ?: 1f
        binding.uiSettingsAnimationSpeed.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.AnimationSpeed, map[value] ?: 1f)
            restartApp()
        }
        binding.uiSettingsBlurBanners.isChecked = PrefManager.getVal(PrefName.BlurBanners)
        binding.uiSettingsBlurBanners.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.BlurBanners, isChecked)
            restartApp()
        }
        binding.uiSettingsBlurRadius.value = (PrefManager.getVal(PrefName.BlurRadius) as Float)
        binding.uiSettingsBlurRadius.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.BlurRadius, value)
            restartApp()
        }
        binding.uiSettingsBlurSampling.value = (PrefManager.getVal(PrefName.BlurSampling) as Float)
        binding.uiSettingsBlurSampling.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.BlurSampling, value)
            restartApp()
        }
    }

    inner class HomeLayoutAdapter(
        private val displayList: MutableList<Int>,
        private val views: Array<String>,
        private val currentVisibility: MutableList<Boolean>
    ) : RecyclerView.Adapter<HomeLayoutAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: ImageView = view.findViewById(R.id.itemHomeLayoutDragHandle)
            val title: TextView = view.findViewById(R.id.itemHomeLayoutTitle)
            val switch: MaterialSwitch = view.findViewById(R.id.itemHomeLayoutSwitch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_layout, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val originalIndex = displayList[position]
            holder.title.text = views[originalIndex]
            
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = currentVisibility[originalIndex]
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                currentVisibility[originalIndex] = isChecked
            }

            if (position == 0) {
                holder.dragHandle.visibility = View.INVISIBLE
            } else {
                holder.dragHandle.visibility = View.VISIBLE
            }
        }

        override fun getItemCount(): Int = displayList.size
    }
}