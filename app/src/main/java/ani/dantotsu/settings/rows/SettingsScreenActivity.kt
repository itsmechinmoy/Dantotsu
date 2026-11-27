package ani.dantotsu.settings.rows

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import ani.dantotsu.databinding.ActivitySettingScreenBinding
import ani.dantotsu.databinding.ItemSettingScreenTitleBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager

/**
 * Shows one [SettingsScreen]. The group activities (PlayerSettingsActivity, ...) are thin
 * subclasses so intents and the manifest keep their names.
 */
abstract class SettingsScreenActivity : AppCompatActivity(), SettingsHost {

    abstract val screen: SettingsScreen

    override val activity: AppCompatActivity get() = this

    private lateinit var binding: ActivitySettingScreenBinding
    private lateinit var rows: RowAdapter

    /** Sections whose advanced rows are open; shared per screen, see [openSections]. */
    private val expanded: MutableSet<Int> get() = openSections.getOrPut(screen.id) { HashSet() }

    /** Runs before the first draw, e.g. to read intent extras. */
    protected open fun onScreenCreate(savedInstanceState: Bundle?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onScreenCreate(savedInstanceState)

        val list = binding.settingScreenList
        list.setPadding(0, statusBarHeight, 0, navBarHeight)
        list.layoutManager = LinearLayoutManager(this)
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        val highlight = intent.getStringExtra(EXTRA_ROW)
        val highlighted = highlight?.let { id -> screen.rows().firstOrNull { it.id == id } }
        savedInstanceState?.getIntArray(STATE_EXPANDED)?.let { expanded.addAll(it.toList()) }
        if (savedInstanceState == null) intent.getIntArrayExtra(EXTRA_OPEN_SECTIONS)?.let { expanded.addAll(it.toList()) }
        if (highlighted?.advanced == true) {
            expanded += ScreenItems.sectionOf(screen.sections(), highlighted.id)
        }

        rows = RowAdapter(this) { section ->
            if (!expanded.remove(section)) expanded += section
            refresh()
        }
        if (savedInstanceState == null) rows.highlightId = highlighted?.id
        list.adapter = ConcatAdapter(TitleAdapter(), rows)
        refresh()

        if (savedInstanceState == null && highlighted != null) {
            list.post {
                val index = rows.currentList.indexOfFirst { it.key == highlighted.id }
                if (index >= 0) {
                    // +1 for the title item; leave a little of the row above in view.
                    (list.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(index + 1, list.height / 4)
                }
            }
        }
    }

    override fun refresh() {
        rows.submitList(ScreenItems.build(screen.sections(), expanded))
    }

    /** This screen with its open sections, for [ani.dantotsu.settings.data.ProcessRestart]. */
    fun reopenIntent(): Intent = Intent(this, this::class.java).putExtra(EXTRA_OPEN_SECTIONS, expanded.toIntArray())

    override fun onResume() {
        super.onResume()
        // Values can change on screens and dialogs opened from here.
        if (::rows.isInitialized) refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_EXPANDED, expanded.toIntArray())
    }

    private inner class TitleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val b = ItemSettingScreenTitleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return object : RecyclerView.ViewHolder(b.root) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val b = ItemSettingScreenTitleBinding.bind(holder.itemView)
            b.screenTitle.setText(screen.title)
            b.screenIcon.setImageResource(screen.icon)
            b.screenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        override fun getItemCount() = 1
    }

    companion object {
        /** Row id to scroll to and flash (from search). */
        const val EXTRA_ROW = "settings_row"
        private const val STATE_EXPANDED = "settings_open_sections"
        private const val EXTRA_OPEN_SECTIONS = "settings_reopen_sections"

        /**
         * Open advanced sections by screen id, for the life of the process. Rows that restart
         * the process (DNS, proxy) pass the open sections along in [reopenIntent].
         */
        private val openSections = HashMap<String, MutableSet<Int>>()

        /** Tests start every screen with its sections closed. */
        @androidx.annotation.VisibleForTesting
        internal fun closeAllSections() = openSections.clear()

        fun intent(context: Context, screen: SettingsScreen, rowId: String? = null) =
            Intent(context, screen.activity).apply { rowId?.let { putExtra(EXTRA_ROW, it) } }
    }
}
