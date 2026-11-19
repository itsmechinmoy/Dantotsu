package ani.dantotsu.settings.rows

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * One settings row. Every screen is drawn from these, and the search index is built from
 * the same objects, so a row on a screen is always in search.
 *
 * Definitions must not read prefs or a Context when they are created: everything that
 * depends on state goes in a lambda ([visible], [enabled], the kind's getters). The
 * index and the unit tests build every screen without an Android runtime.
 */
class Row(
    /** Stable id: the pref key name when the row edits one pref, else a fixed slug. Search highlights by it. */
    val id: String,
    @StringRes val title: Int,
    /** Fixed second line. Rows with a current value show that instead (see [Kind.value]). */
    @StringRes val summary: Int? = null,
    @DrawableRes val icon: Int? = null,
    val kind: Kind,
    /** Hidden until its section's "Show N more" row is opened. */
    val advanced: Boolean = false,
    val visible: () -> Boolean = { true },
    val enabled: () -> Boolean = { true },
    /** Strings search should also match (other names, rows on sub-screens). */
    val keywords: List<Int> = emptyList(),
    /** Every pref this row writes; checked by the backup and key tests. */
    val keys: List<PrefName> = emptyList(),
    /** Content bound under the row's text (repo list, preview, buttons). */
    val extra: ((SettingsHost, ViewGroup) -> Unit)? = null,
    /** Small icon buttons bound on the right of the row, before the switch or chevron. */
    val trailing: ((SettingsHost, ViewGroup) -> Unit)? = null,
    val onLongClick: ((SettingsHost) -> Unit)? = null,
    /** Title as text, for rows built at run time (extension settings); wins over [title]. */
    val label: CharSequence? = null,
    /** Second line read on every bind; after the kind's value, before [summary]. */
    val summaryText: (() -> CharSequence?)? = null,
)

/** Rows under one header; [title] 0 (and no [label]) draws no header. */
class Section(@StringRes val title: Int, val rows: List<Row>, val label: CharSequence? = null)

/** The row's title, from [Row.label] or its string resource. */
fun Row.titleText(context: Context): CharSequence = label ?: context.getString(title)

sealed interface Kind {
    /** Current value shown as the second line, or null to show [Row.summary]. [context] is the host activity. */
    fun value(context: Context): String? = null

    class Switch(
        val get: () -> Boolean,
        val set: (SettingsHost, Boolean) -> Unit,
    ) : Kind

    /**
     * Pick one of [labels] in a [ChoiceSheet]; the stored value is the index. [details] gives
     * each option an optional second line (same order as [labels]).
     */
    class Choice(
        val labels: (Context) -> List<String>,
        val get: () -> Int,
        val set: (SettingsHost, Int) -> Unit,
        val details: ((Context) -> List<String?>)? = null,
    ) : Kind {
        override fun value(context: Context) = labels(context).let { it.getOrNull(get()) ?: it.firstOrNull() }
    }

    class Slider(
        val from: Float,
        val to: Float,
        val step: Float,
        val get: () -> Float,
        val set: (SettingsHost, Float) -> Unit,
        val format: (Context, Float) -> String,
    ) : Kind

    class Stepper(
        val from: Float,
        val to: Float,
        val step: Float,
        val get: () -> Float,
        val set: (SettingsHost, Float) -> Unit,
        val format: (Context, Float) -> String,
    ) : Kind

    /** Icon buttons, one selected. The stored value is the index into [options]. */
    class Segmented(
        val options: List<Segment>,
        val get: () -> Int,
        val set: (SettingsHost, Int) -> Unit,
    ) : Kind {
        // Icon segments name the pick on the second line; text segments already show it.
        override fun value(context: Context) =
            options.getOrNull(get())?.takeIf { it.icon != null }?.text(context)
    }

    class Action(
        val current: ((Context) -> String?)? = null,
        /** Shows a chevron: the row opens another screen. */
        val opensScreen: Boolean = false,
        /** Null for a row that only shows a value. */
        val onClick: ((SettingsHost, RowView) -> Unit)?,
    ) : Kind {
        override fun value(context: Context) = current?.invoke(context)
    }

    /** A row drawn by its own layout (account cards, theme picker). Title is used by search only. */
    class Custom(
        @LayoutRes val layout: Int,
        val bind: (SettingsHost, View) -> Unit,
        /** Drawn as a row of its section card; false for layouts that are a card themselves. */
        val inSection: Boolean = false,
    ) : Kind
}

/**
 * One button of a [Kind.Segmented] row: an icon with [label] as its description, or (no icon)
 * a short text, [label] or [labelText]. Text segments suit 2-4 options of a few characters.
 */
class Segment(
    @DrawableRes val icon: Int?,
    @StringRes val label: Int = 0,
    private val labelText: ((Context) -> String)? = null,
) {
    fun text(context: Context): String = labelText?.invoke(context) ?: context.getString(label)
}

fun textSegment(@StringRes label: Int) = Segment(null, label)
fun textSegment(text: (Context) -> String) = Segment(null, labelText = text)

/** What an action can change on its own row after it runs. */
interface RowView {
    fun setValue(text: CharSequence?)
    val extra: ViewGroup
}

/** The screen a row lives on; actions use it for dialogs, fragments and refresh. */
interface SettingsHost {
    val activity: androidx.appcompat.app.AppCompatActivity
    /** Re-reads every row's visibility, enabled state and value. */
    fun refresh()
}

// Builders. Screens use these so each row reads as one declaration.

fun switchRow(
    key: PrefName,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    /** Refresh the screen after a change (other rows depend on this one). */
    refresh: Boolean = false,
    trailing: ((SettingsHost, ViewGroup) -> Unit)? = null,
    onChange: (SettingsHost, Boolean) -> Unit = { _, _ -> },
) = Row(
    id = key.name, title = title, summary = summary, icon = icon, advanced = advanced,
    visible = visible, enabled = enabled, keywords = keywords, keys = listOf(key), trailing = trailing,
    kind = Kind.Switch(
        get = { PrefManager.getVal(key) },
        set = { host, value ->
            PrefManager.setVal(key, value)
            onChange(host, value)
            if (refresh) host.refresh()
        },
    ),
)

/** Int pref holding an index into [labels]. */
fun choiceRow(
    key: PrefName,
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    /** Shown only when the value is empty; search always reads it. */
    @StringRes summary: Int? = null,
    labels: (Context) -> List<String>,
    details: ((Context) -> List<String?>)? = null,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    refresh: Boolean = false,
    onChange: (SettingsHost, Int) -> Unit = { _, _ -> },
) = Row(
    id = key.name, title = title, summary = summary, icon = icon, advanced = advanced, visible = visible,
    enabled = enabled, keywords = keywords, keys = listOf(key),
    kind = Kind.Choice(
        labels = labels,
        details = details,
        get = { PrefManager.getVal(key) },
        set = { host, value ->
            PrefManager.setVal(key, value)
            onChange(host, value)
            if (refresh) host.refresh()
        },
    ),
)

/** Float or Int pref on a slider; [toPref] converts the slider value to the stored type. */
fun sliderRow(
    key: PrefName,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    from: Float,
    to: Float,
    step: Float,
    format: (Context, Float) -> String,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    onChange: (SettingsHost, Float) -> Unit = { _, _ -> },
) = Row(
    id = key.name, title = title, summary = summary, icon = icon, advanced = advanced,
    visible = visible, enabled = enabled, keywords = keywords, keys = listOf(key),
    kind = Kind.Slider(
        from = from, to = to, step = step, format = format,
        get = { readNumber(key) },
        set = { host, value -> writeNumber(key, value); onChange(host, value) },
    ),
)

fun stepperRow(
    key: PrefName,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    from: Float,
    to: Float,
    step: Float,
    format: (Context, Float) -> String,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    onChange: (SettingsHost, Float) -> Unit = { _, _ -> },
    /** Maps a stored value into the range, for prefs where several stored values mean the same. */
    read: (Float) -> Float = { it },
) = Row(
    id = key.name, title = title, summary = summary, icon = icon, advanced = advanced,
    visible = visible, enabled = enabled, keywords = keywords, keys = listOf(key),
    kind = Kind.Stepper(
        from = from, to = to, step = step, format = format,
        get = { read(readNumber(key)) },
        set = { host, value -> writeNumber(key, value); onChange(host, value) },
    ),
)

/**
 * A stepper over a fixed list of values (speeds, sizes): [get] and [set] use the index,
 * [label] names a value. For prefs that store the value itself, not an index.
 */
fun stepsRow(
    id: String,
    @StringRes title: Int,
    count: Int,
    label: (Context, Int) -> String,
    get: () -> Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    keys: List<PrefName> = emptyList(),
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
    set: (SettingsHost, Int) -> Unit,
) = Row(
    id = id, title = title, summary = summary, icon = icon, advanced = advanced,
    visible = visible, enabled = enabled, keys = keys,
    kind = Kind.Stepper(
        from = 0f, to = (count - 1).toFloat(), step = 1f,
        get = { get().coerceIn(0, count - 1).toFloat() },
        set = { host, v -> set(host, v.toInt()) },
        format = { c, v -> label(c, v.toInt()) },
    ),
)

/** A segmented row whose value is not a plain index pref (strings, value lists). */
fun segmentsRow(
    id: String,
    @StringRes title: Int,
    options: List<Segment>,
    get: () -> Int,
    @DrawableRes icon: Int? = null,
    keys: List<PrefName> = emptyList(),
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    set: (SettingsHost, Int) -> Unit,
) = Row(
    id = id, title = title, icon = icon, advanced = advanced, visible = visible, keys = keys,
    kind = Kind.Segmented(options = options, get = get, set = set),
)

/** Int pref holding an index into [options]. */
fun segmentedRow(
    key: PrefName,
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    options: List<Segment>,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    onChange: (SettingsHost, Int) -> Unit = { _, _ -> },
) = Row(
    id = key.name, title = title, icon = icon, advanced = advanced, visible = visible,
    keywords = keywords, keys = listOf(key),
    kind = Kind.Segmented(
        options = options,
        get = { PrefManager.getVal(key) },
        set = { host, value -> PrefManager.setVal(key, value); onChange(host, value) },
    ),
)

fun actionRow(
    id: String,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    current: ((Context) -> String?)? = null,
    opensScreen: Boolean = false,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    enabled: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    keys: List<PrefName> = emptyList(),
    extra: ((SettingsHost, ViewGroup) -> Unit)? = null,
    onLongClick: ((SettingsHost) -> Unit)? = null,
    onClick: ((SettingsHost, RowView) -> Unit)?,
) = Row(
    id = id, title = title, summary = summary, icon = icon, advanced = advanced,
    visible = visible, enabled = enabled, keywords = keywords, keys = keys, extra = extra,
    onLongClick = onLongClick,
    kind = Kind.Action(current = current, opensScreen = opensScreen, onClick = onClick),
)

fun customRow(
    id: String,
    @StringRes title: Int,
    @LayoutRes layout: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    advanced: Boolean = false,
    visible: () -> Boolean = { true },
    keywords: List<Int> = emptyList(),
    keys: List<PrefName> = emptyList(),
    inSection: Boolean = false,
    bind: (SettingsHost, View) -> Unit,
) = Row(
    id = id, title = title, summary = summary, icon = icon, advanced = advanced,
    visible = visible, keywords = keywords, keys = keys,
    kind = Kind.Custom(layout, bind, inSection),
)

private fun readNumber(key: PrefName): Float = when (key.data.type) {
    Int::class -> PrefManager.getVal<Int>(key).toFloat()
    Long::class -> PrefManager.getVal<Long>(key).toFloat()
    else -> PrefManager.getVal(key)
}

private fun writeNumber(key: PrefName, value: Float) = when (key.data.type) {
    Int::class -> PrefManager.setVal(key, value.toInt())
    Long::class -> PrefManager.setVal(key, value.toLong())
    else -> PrefManager.setVal(key, value)
}
