package ani.dantotsu.settings.rows

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChoiceGroupBinding
import ani.dantotsu.databinding.ItemChoiceOptionBinding
import ani.dantotsu.databinding.SheetChoiceBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

/**
 * One option of a [ChoiceSheet]. [detail] is a short second line that says what the option
 * does or costs; leave it null when it would only repeat the label. Options with the same
 * [group] are listed under one header (in list order); [groupDetail] is read from the first
 * option of the group.
 */
class ChoiceOption(
    val label: CharSequence,
    val detail: CharSequence? = null,
    val enabled: Boolean = true,
    val group: CharSequence? = null,
    val groupDetail: CharSequence? = null,
)

/**
 * The app's one pattern for "pick one" and "pick several": a bottom sheet with the options as
 * a grouped card. Single choice applies on tap and closes; multi choice applies every toggle,
 * or only on the confirm button when one is given.
 *
 * Not a fragment: like the dialogs it replaces it closes on rotation, so callers keep plain
 * lambdas.
 */
object ChoiceSheet {

    fun single(
        context: Context,
        title: CharSequence,
        options: List<ChoiceOption>,
        selected: Int,
        note: CharSequence? = null,
        /** Closed without a pick (single) or without the confirm / reset button (multi). */
        onCancel: (() -> Unit)? = null,
        onPick: (Int) -> Unit,
    ) {
        val state = BooleanArray(options.size) { it == selected }
        show(context, title, options, state, multi = false, note = note, onCancel = onCancel) { sheet, index ->
            sheet.confirmed = true
            if (index != selected) onPick(index)
            // Let the radio animate before the sheet goes.
            sheet.list.postDelayed({ sheet.dialog.dismiss() }, PICK_DISMISS_MS)
        }
    }

    /**
     * [checked] is updated in place. Without [confirm], [onChange] runs on every toggle; with
     * it, nothing is applied until the button is pressed (the sheet then closes).
     */
    fun multi(
        context: Context,
        title: CharSequence,
        options: List<ChoiceOption>,
        checked: BooleanArray,
        note: CharSequence? = null,
        confirm: Confirm? = null,
        onReset: (() -> Unit)? = null,
        /** Closed without a pick (single) or without the confirm / reset button (multi). */
        onCancel: (() -> Unit)? = null,
        onChange: (BooleanArray) -> Unit = {},
    ) {
        val sheet = show(context, title, options, checked, multi = true, note = note, onCancel = onCancel) { _, _ ->
            if (confirm == null) onChange(checked)
        } ?: return
        val b = sheet.binding
        if (confirm != null || onReset != null) {
            b.choiceActions.isVisible = true
            b.choiceConfirm.isVisible = confirm != null
            confirm?.let { c ->
                b.choiceConfirm.setText(c.label)
                b.choiceConfirm.setIconResource(c.icon)
                b.choiceConfirm.setOnClickListener {
                    sheet.confirmed = true
                    c.onConfirm(checked)
                    sheet.dialog.dismiss()
                }
            }
            b.choiceReset.isVisible = onReset != null
            onReset?.let { reset ->
                b.choiceReset.setOnClickListener {
                    sheet.confirmed = true
                    reset()
                    sheet.dialog.dismiss()
                }
            }
        }
    }

    /** The button of a multi choice that applies the selection (Import, Back up, OK). */
    class Confirm(@StringRes val label: Int, @DrawableRes val icon: Int = R.drawable.ic_check, val onConfirm: (BooleanArray) -> Unit)

    private const val PICK_DISMISS_MS = 150L

    private class Sheet(val dialog: BottomSheetDialog, val binding: SheetChoiceBinding) {
        val list: RecyclerView get() = binding.choiceList
        var confirmed = false
    }

    private fun show(
        context: Context,
        title: CharSequence,
        options: List<ChoiceOption>,
        state: BooleanArray,
        multi: Boolean,
        note: CharSequence?,
        onCancel: (() -> Unit)?,
        onToggle: (Sheet, Int) -> Unit,
    ): Sheet? {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return null
        val dialog = BottomSheetDialog(context)
        val b = SheetChoiceBinding.inflate(LayoutInflater.from(context))
        val sheet = Sheet(dialog, b)
        b.choiceTitle.text = title
        b.choiceNote.isVisible = !note.isNullOrBlank()
        b.choiceNoteIcon.isVisible = !note.isNullOrBlank()
        b.choiceNoteText.text = note

        val items = buildItems(options)
        val adapter = Adapter(items, state, multi) { index ->
            onToggle(sheet, index)
        }
        b.choiceList.layoutManager = LinearLayoutManager(context)
        b.choiceList.adapter = adapter
        b.choiceList.itemAnimator = null

        dialog.setContentView(b.root)
        // Keep the last option and the buttons above the gesture bar.
        val basePadding = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = basePadding + nav)
            insets
        }
        // TalkBack announces the window title when the sheet opens.
        dialog.setTitle(title)
        dialog.setOnDismissListener { if (!sheet.confirmed) onCancel?.invoke() }

        val metrics = context.resources.displayMetrics
        dialog.behavior.apply {
            // Short lists open whole; long ones open at 60% and drag up to full height.
            peekHeight = (metrics.heightPixels * 0.6f).toInt()
            skipCollapsed = false
            this.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        // Put the current choice in view (long lists: languages, time zones).
        val first = state.indexOfFirst { it }
        if (first > 0) {
            val position = items.indexOfFirst { it is Item.Option && it.index == first }
            (b.choiceList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(position, (metrics.heightPixels * 0.15f).toInt())
        }
        dialog.show()
        // The default sheet surface is a tinted container; use the theme's surface so OLED and
        // custom themes match the screen behind it.
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface)
            )
        ViewCompat.requestApplyInsets(b.root)
        return sheet
    }

    private sealed interface Item {
        class Group(val title: CharSequence, val detail: CharSequence?) : Item
        class Option(val index: Int, val option: ChoiceOption) : Item
    }

    private fun buildItems(options: List<ChoiceOption>): List<Item> {
        val out = ArrayList<Item>(options.size + 2)
        var group: CharSequence? = null
        options.forEachIndexed { i, option ->
            if (option.group != null && option.group != group) {
                out += Item.Group(option.group, option.groupDetail)
            }
            group = option.group
            out += Item.Option(i, option)
        }
        return out
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private class Adapter(
        val items: List<Item>,
        val state: BooleanArray,
        val multi: Boolean,
        val onToggle: (Int) -> Unit,
    ) : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Group) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            return Holder(
                if (viewType == 0) ItemChoiceGroupBinding.inflate(inflater, parent, false).root
                else ItemChoiceOptionBinding.inflate(inflater, parent, false).root
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val item = items[position]) {
                is Item.Group -> {
                    val g = ItemChoiceGroupBinding.bind(holder.itemView)
                    g.groupTitle.text = item.title
                    g.groupDetail.text = item.detail
                    g.groupDetail.isVisible = !item.detail.isNullOrBlank()
                }
                is Item.Option -> bindOption(ItemChoiceOptionBinding.bind(holder.itemView), item, position)
            }
        }

        private fun bindOption(o: ItemChoiceOptionBinding, item: Item.Option, position: Int) {
            val option = item.option
            val on = state[item.index]
            val view = o.root
            o.optionLabel.text = option.label
            o.optionDetail.text = option.detail
            o.optionDetail.isVisible = !option.detail.isNullOrBlank()
            o.optionRadio.isVisible = !multi
            o.optionCheck.isVisible = multi
            o.optionRadio.isChecked = on
            o.optionCheck.isChecked = on

            // The chosen option takes the secondary container, so it reads at a glance and
            // not only by the small radio. Corners follow the neighbours like settings rows.
            val selectedFill = if (on && !multi) {
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorSecondaryContainer)
            } else null
            val onFill = if (on && !multi) {
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSecondaryContainer)
            } else MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
            o.optionLabel.setTextColor(onFill)
            SettingsCards.apply(
                view,
                first = items.getOrNull(position - 1) !is Item.Option,
                last = items.getOrNull(position + 1) !is Item.Option,
                fillColor = selectedFill,
            )

            view.isEnabled = option.enabled
            val alpha = if (option.enabled) 1f else DISABLED_ALPHA
            o.optionLabel.alpha = alpha
            o.optionDetail.alpha = alpha
            o.optionRadio.alpha = alpha
            o.optionCheck.alpha = alpha
            view.setOnClickListener {
                if (!option.enabled) return@setOnClickListener
                if (multi) {
                    state[item.index] = !state[item.index]
                    notifyItemChanged(position)
                } else {
                    val old = state.indexOfFirst { it }
                    state.fill(false)
                    state[item.index] = true
                    if (old >= 0) items.indexOfFirst { it is Item.Option && it.index == old }
                        .takeIf { it >= 0 }?.let(::notifyItemChanged)
                    notifyItemChanged(position)
                }
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                onToggle(item.index)
            }
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = if (multi) android.widget.CheckBox::class.java.name
                    else android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = state[item.index]
                    info.isEnabled = option.enabled
                }
            })
        }
    }

    private const val DISABLED_ALPHA = 0.38f
}
