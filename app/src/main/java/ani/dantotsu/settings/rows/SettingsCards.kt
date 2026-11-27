package ani.dantotsu.settings.rows

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Settings sections are grouped cards: one rounded card per section, rows 2dp apart, big
 * corners on the first and last row and small ones between (same shape as the AnimeThemes
 * list). The fill is the theme's surfaceVariant, so every app theme, Material You and the
 * custom colour tint the cards.
 */
internal object SettingsCards {
    private const val OUTER_DP = 20f
    private const val INNER_DP = 4f
    private const val SIDE_DP = 16
    private const val GAP_DP = 2

    fun fill(view: View) = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant)

    /** Card background and ripple for one row at its place in the section; [fillColor] replaces the card fill (selected option). */
    fun apply(view: View, first: Boolean, last: Boolean, topMarginDp: Int = 0, fillColor: Int? = null) {
        val d = view.resources.displayMetrics.density
        val big = OUTER_DP * d
        val small = INNER_DP * d
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (first) big else small)
            .setTopRightCornerSize(if (first) big else small)
            .setBottomLeftCornerSize(if (last) big else small)
            .setBottomRightCornerSize(if (last) big else small)
            .build()
        val color = fillColor ?: fill(view)
        val content = MaterialShapeDrawable(shape).apply { setFillColor(ColorStateList.valueOf(color)) }
        val mask = MaterialShapeDrawable(shape).apply { setFillColor(ColorStateList.valueOf(0xFFFFFFFF.toInt())) }
        val ripple = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)
        view.background = RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
        margins(view, top = if (first) topMarginDp else GAP_DP)
    }

    /** Custom rows that are already a card (storage) get the same fill and side margins. */
    fun applyToCard(card: MaterialCardView) {
        card.setCardBackgroundColor(fill(card))
        card.radius = OUTER_DP * card.resources.displayMetrics.density
        card.strokeWidth = 0
        margins(card, top = 8)
    }

    private fun margins(view: View, top: Int) {
        val d = view.resources.displayMetrics.density
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val side = (SIDE_DP * d).toInt()
        val t = (top * d).toInt()
        if (lp.marginStart != side || lp.marginEnd != side || lp.topMargin != t || lp.bottomMargin != 0) {
            lp.marginStart = side
            lp.marginEnd = side
            lp.topMargin = t
            lp.bottomMargin = 0
            view.layoutParams = lp
        }
    }

    /** True when [item] is drawn as part of a section card. */
    fun isCardRow(item: ScreenItem?) = item is ScreenItem.More ||
        item is ScreenItem.RowItem && (item.row.kind as? Kind.Custom)?.inSection != false

    /** True when [item] is a custom row drawn as its own card (accounts). */
    fun isStandaloneCard(item: ScreenItem?) =
        item is ScreenItem.RowItem && (item.row.kind as? Kind.Custom)?.inSection == false
}
