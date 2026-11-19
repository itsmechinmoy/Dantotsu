package ani.dantotsu.settings.rows

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingMoreBinding
import ani.dantotsu.databinding.ItemSettingHeaderBinding
import ani.dantotsu.databinding.ItemSettingRowBinding
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** Draws a screen's [ScreenItem]s. Values are read from prefs on every bind. */
class RowAdapter(
    private val host: SettingsHost,
    /** False for screens whose rows never have icons (extension settings): no empty slot. */
    private val iconSlot: Boolean = true,
    /** Opens or closes the advanced rows of the section at this index. */
    private val onToggleSection: (Int) -> Unit = {},
) : ListAdapter<ScreenItem, RecyclerView.ViewHolder>(Diff) {

    /** Row id to flash once when it is first bound (search result). */
    var highlightId: String? = null

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is ScreenItem.Header -> TYPE_HEADER
        is ScreenItem.More -> TYPE_MORE
        is ScreenItem.RowItem -> (item.row.kind as? Kind.Custom)?.layout ?: TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = when (viewType) {
            TYPE_HEADER -> ItemSettingHeaderBinding.inflate(inflater, parent, false).root
            TYPE_MORE -> ItemSettingMoreBinding.inflate(inflater, parent, false).root
            TYPE_ROW -> ItemSettingRowBinding.inflate(inflater, parent, false).root
            else -> inflater.inflate(viewType, parent, false)
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ScreenItem.Header ->
                ItemSettingHeaderBinding.bind(holder.itemView).headerTitle.let { t ->
                    if (item.label != null) t.text = item.label else t.setText(item.title)
                }
            is ScreenItem.More -> {
                bindMore(ItemSettingMoreBinding.bind(holder.itemView), item)
                applyCard(holder.itemView, position)
            }
            is ScreenItem.RowItem -> {
                val row = item.row
                val kind = row.kind
                // A recycled view may still be mid-flash (search highlight); clear it.
                holder.itemView.animate().cancel()
                holder.itemView.alpha = 1f
                holder.itemView.isPressed = false
                if (kind is Kind.Custom) {
                    kind.bind(host, holder.itemView)
                    if (kind.inSection) {
                        applyCard(holder.itemView, position)
                    } else {
                        (holder.itemView as? com.google.android.material.card.MaterialCardView)
                            ?.let(SettingsCards::applyToCard)
                    }
                } else {
                    bindRow(ItemSettingRowBinding.bind(holder.itemView), row)
                    applyCard(holder.itemView, position)
                }
                if (row.id == highlightId) {
                    highlightId = null
                    flash(holder.itemView)
                }
            }
        }
    }

    /**
     * Neighbours decide the corners; rows rebind on every refresh (see Diff), so a row that
     * becomes first or last after a visibility change is redrawn. A section that follows a
     * standalone card (accounts) gets the same 8dp gap the cards keep between themselves.
     */
    private fun applyCard(view: View, position: Int) {
        val list = currentList
        val prev = list.getOrNull(position - 1)
        SettingsCards.apply(
            view,
            first = !SettingsCards.isCardRow(prev),
            last = !SettingsCards.isCardRow(list.getOrNull(position + 1)),
            topMarginDp = if (SettingsCards.isStandaloneCard(prev)) 8 else 0,
        )
    }

    private fun bindMore(b: ItemSettingMoreBinding, item: ScreenItem.More) {
        val context = b.root.context
        val label = if (item.expanded) {
            context.getString(R.string.settings_fewer)
        } else {
            context.resources.getQuantityString(R.plurals.settings_more, item.count, item.count)
        }
        b.moreText.text = label
        b.moreToggle.contentDescription = label
        // Turn the chevron when the state changes on the same view; set it outright on first bind.
        val target = if (item.expanded) 180f else 0f
        val shown = b.moreChevron.getTag(R.id.moreChevron) as? Boolean
        b.moreChevron.animate().cancel()
        if (shown != null && shown != item.expanded) {
            // ViewPropertyAnimator follows the system animator duration scale.
            b.moreChevron.animate().rotation(target).setDuration(200).start()
        } else {
            b.moreChevron.rotation = target
        }
        b.moreChevron.setTag(R.id.moreChevron, item.expanded)
        ViewCompat.setStateDescription(
            b.moreToggle,
            context.getString(if (item.expanded) R.string.expanded else R.string.collapsed)
        )
        b.moreToggle.setOnClickListener { onToggleSection(item.section) }
    }

    private fun bindRow(b: ItemSettingRowBinding, row: Row) {
        // The host activity, so value lambdas can reach screen state.
        val context: Context = host.activity
        val kind = row.kind
        val enabled = row.enabled()

        b.rowTitle.text = row.titleText(context)
        if (row.icon != null) {
            b.rowIcon.setImageResource(row.icon)
            b.rowIcon.visibility = View.VISIBLE
        } else {
            b.rowIcon.visibility = if (iconSlot) View.INVISIBLE else View.GONE
        }
        // The slider sits under the text; without the icon slot it lines up with the title.
        (b.rowSlider.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
            if (iconSlot) (28 * b.root.resources.displayMetrics.density).toInt() else 0
        setSummary(b, context, row)

        // Reset every optional part; each kind turns on what it uses.
        b.rowSwitch.isVisible = false
        b.rowChevron.isVisible = false
        b.rowValue.isVisible = false
        b.rowMinus.isVisible = false
        b.rowPlus.isVisible = false
        b.rowSlider.isVisible = false
        b.rowSegments.isVisible = false
        b.rowSlider.clearOnChangeListeners()
        b.rowSegments.clearOnButtonCheckedListeners()
        b.root.setOnClickListener(null)
        b.root.isClickable = false
        ViewCompat.setAccessibilityDelegate(b.root, null)

        val rowView = object : RowView {
            override fun setValue(text: CharSequence?) {
                b.rowSummary.text = text
                b.rowSummary.isVisible = !text.isNullOrBlank()
            }
            override val extra: ViewGroup get() = b.rowExtra
        }

        when (kind) {
            is Kind.Switch -> {
                b.rowSwitch.isVisible = true
                b.rowSwitch.isChecked = kind.get()
                b.root.setOnClickListener {
                    val now = !b.rowSwitch.isChecked
                    b.rowSwitch.isChecked = now
                    kind.set(host, now)
                    b.root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
                ViewCompat.setAccessibilityDelegate(b.root, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Switch::class.java.name
                        info.isCheckable = true
                        info.isChecked = b.rowSwitch.isChecked
                    }
                })
            }

            is Kind.Choice -> b.root.setOnClickListener {
                val labels = kind.labels(context)
                val details = kind.details?.invoke(context)
                ChoiceSheet.single(
                    context, row.titleText(context),
                    labels.mapIndexed { i, label -> ChoiceOption(label, details?.getOrNull(i)) },
                    kind.get(),
                ) { which ->
                    kind.set(host, which)
                    setSummary(b, context, row)
                }
            }

            is Kind.Slider -> {
                b.rowSlider.isVisible = true
                b.rowValue.isVisible = true
                b.rowSlider.valueFrom = kind.from
                b.rowSlider.valueTo = kind.to
                b.rowSlider.stepSize = kind.step
                val value = snap(kind.get(), kind.from, kind.to, kind.step)
                b.rowSlider.value = value
                b.rowValue.text = kind.format(context, value)
                b.rowSlider.contentDescription = row.titleText(context)
                // A drag saves once, on release (some sliders restart the app); keyboard
                // and accessibility steps save at once.
                var dragging = false
                b.rowSlider.clearOnSliderTouchListeners()
                b.rowSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                        dragging = true
                    }
                    override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                        dragging = false
                        if (slider.value != kind.get()) kind.set(host, slider.value)
                    }
                })
                b.rowSlider.addOnChangeListener { _, v, fromUser ->
                    b.rowValue.text = kind.format(context, v)
                    if (fromUser && !dragging) kind.set(host, v)
                }
            }

            is Kind.Stepper -> {
                b.rowMinus.isVisible = true
                b.rowPlus.isVisible = true
                b.rowValue.isVisible = true
                fun show() {
                    val v = kind.get()
                    b.rowValue.text = kind.format(context, v)
                    b.rowMinus.isEnabled = enabled && v > kind.from
                    b.rowPlus.isEnabled = enabled && v < kind.to
                    b.rowMinus.alpha = if (b.rowMinus.isEnabled) 1f else DISABLED_ALPHA
                    b.rowPlus.alpha = if (b.rowPlus.isEnabled) 1f else DISABLED_ALPHA
                }
                fun step(by: Float) {
                    val next = snap(kind.get() + by, kind.from, kind.to, kind.step)
                    kind.set(host, next)
                    show()
                }
                repeatOnHold(b.rowMinus) { step(-kind.step) }
                repeatOnHold(b.rowPlus) { step(kind.step) }
                show()
            }

            is Kind.Segmented -> {
                b.rowSegments.isVisible = true
                b.rowSegments.removeAllViews()
                kind.options.forEachIndexed { i, option ->
                    val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = View.generateViewId()
                        if (option.icon != null) {
                            setIconResource(option.icon)
                            iconPadding = 0
                            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                            contentDescription = option.text(context)
                        } else {
                            text = option.text(context)
                            isAllCaps = false
                        }
                        isEnabled = enabled
                        isCheckable = true
                        minWidth = 0
                        minimumWidth = 0
                        val pad = (12 * context.resources.displayMetrics.density).roundToInt()
                        setPadding(pad, paddingTop, pad, paddingBottom)
                        tag = i
                    }
                    // Equal segments across the row, as in the Material 3 segmented button.
                    b.rowSegments.addView(button, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (i == kind.get()) b.rowSegments.check(button.id)
                    markSegment(button, option.icon, button.isChecked)
                }
                b.rowSegments.addOnButtonCheckedListener { group, checkedId, isChecked ->
                    val checkedButton = group.findViewById<MaterialButton>(checkedId)
                    val option = (checkedButton?.tag as? Int)?.let { kind.options.getOrNull(it) }
                    if (checkedButton != null && option != null) markSegment(checkedButton, option.icon, isChecked)
                    if (!isChecked) return@addOnButtonCheckedListener
                    val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@addOnButtonCheckedListener
                    if (index != kind.get()) {
                        kind.set(host, index)
                        setSummary(b, context, row)
                    }
                }
            }

            is Kind.Action -> {
                b.rowChevron.isVisible = kind.opensScreen
                kind.onClick?.let { click -> b.root.setOnClickListener { click(host, rowView) } }
            }

            is Kind.Custom -> Unit
        }

        val longClick = row.onLongClick
        if (longClick != null) {
            b.root.setOnLongClickListener { longClick(host); true }
        } else {
            b.root.setOnLongClickListener(null)
            b.root.isLongClickable = false
        }

        val extra = row.extra
        b.rowExtra.removeAllViews()
        b.rowExtra.isVisible = extra != null
        extra?.invoke(host, b.rowExtra)

        val trailing = row.trailing
        b.rowTrailing.removeAllViews()
        b.rowTrailing.isVisible = trailing != null
        trailing?.invoke(host, b.rowTrailing)

        setEnabled(b, enabled)
    }

    private fun setSummary(b: ItemSettingRowBinding, context: Context, row: Row) {
        // Sliders and steppers show their value on the right; the second line keeps the summary.
        val value = when (row.kind) {
            is Kind.Slider, is Kind.Stepper -> null
            else -> row.kind.value(context)
        }
        val text = value ?: row.summaryText?.invoke() ?: row.summary?.let(context::getString)
        b.rowSummary.text = text
        b.rowSummary.isVisible = !text.isNullOrBlank()
    }

    private fun setEnabled(b: ItemSettingRowBinding, enabled: Boolean) {
        b.root.isEnabled = enabled
        b.rowSlider.isEnabled = enabled
        for (i in 0 until b.rowSegments.childCount) b.rowSegments.getChildAt(i).isEnabled = enabled
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        b.rowTitle.alpha = alpha
        b.rowIcon.alpha = alpha
        b.rowSwitch.alpha = alpha
        b.rowSlider.alpha = alpha
        b.rowSegments.alpha = alpha
        b.rowSummary.alpha = alpha
    }

    /** Dims the row and fades it back, once. Always ends at full alpha, not pressed. */
    private fun flash(view: View) {
        view.alpha = 0.3f
        view.isPressed = true
        view.animate().alpha(1f).setDuration(600)
            .withEndAction { view.alpha = 1f; view.isPressed = false }
            .start()
    }

    private object Diff : DiffUtil.ItemCallback<ScreenItem>() {
        override fun areItemsTheSame(old: ScreenItem, new: ScreenItem) = old.key == new.key
        override fun areContentsTheSame(old: ScreenItem, new: ScreenItem) = when {
            // Rows read their value on bind, so always rebind them on refresh.
            old is ScreenItem.RowItem || new is ScreenItem.RowItem -> false
            else -> old == new
        }
    }

    companion object {
        const val TYPE_HEADER = 1
        const val TYPE_ROW = 2
        const val TYPE_MORE = 3
        private const val DISABLED_ALPHA = 0.38f

        /** Clamp to the range and to a whole step, as the Slider requires. */
        fun snap(value: Float, from: Float, to: Float, step: Float): Float {
            if (step <= 0f) return value.coerceIn(from, to)
            val steps = ((value - from) / step).roundToInt()
            return (from + steps * step).coerceIn(from, to)
        }
    }
}

/**
 * Click steps once; holding repeats, faster after the first second, until the finger lifts
 * or the button turns disabled at the end of the range.
 */
private fun repeatOnHold(button: View, action: () -> Unit) {
    var repeat: Runnable? = null
    fun stop() {
        repeat?.let(button::removeCallbacks)
        repeat = null
    }
    button.setOnClickListener { action() }
    button.setOnLongClickListener {
        val r = object : Runnable {
            var count = 0
            override fun run() {
                if (!button.isEnabled) return stop()
                action()
                count++
                button.postDelayed(this, if (count < 8) 150L else 60L)
            }
        }
        repeat = r
        r.run()
        true
    }
    button.setOnTouchListener { _, event ->
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
            event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        ) stop()
        false
    }
}

/**
 * Material 3 segmented button: the selected segment leads with an 18dp check. A text
 * segment gains the check; an icon segment shows the check in place of its icon.
 */
internal fun markSegment(button: MaterialButton, icon: Int?, checked: Boolean) {
    val size = (18 * button.resources.displayMetrics.density).roundToInt()
    when {
        checked -> {
            button.setIconResource(R.drawable.ic_chip_check)
            button.iconSize = size
            button.iconPadding = if (icon == null) (8 * button.resources.displayMetrics.density).roundToInt() else 0
        }
        icon != null -> {
            button.setIconResource(icon)
            button.iconPadding = 0
        }
        else -> button.icon = null
    }
    button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
}
