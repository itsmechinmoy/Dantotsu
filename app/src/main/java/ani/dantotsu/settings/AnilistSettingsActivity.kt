package ani.dantotsu.settings

import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.ChoiceOption
import android.content.Intent
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistMutations
import ani.dantotsu.connections.anilist.api.ScoreFormat
import ani.dantotsu.connections.anilist.api.UserStaffNameLanguage
import ani.dantotsu.connections.anilist.api.UserTitleLanguage
import ani.dantotsu.snackString
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/** Settings > Accounts > AniList settings: account settings stored on AniList. */
class AnilistSettingsActivity : SettingsScreenActivity() {
    override val screen get() = AnilistScreen

    /** Custom list names for anime or manga in an editor dialog; Save sends them to AniList. */
    internal fun editCustomLists(isAnime: Boolean) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(container)
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_round_add_circle_outline_24)
                contentDescription = getString(R.string.add_custom_list)
                val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                background = attrs.getDrawable(0)
                attrs.recycle()
                setOnClickListener { addCustomListItem("", container, isAnime) }
            }, LinearLayout.LayoutParams(pad * 3, pad * 3))
        }
        val current = if (isAnime) Anilist.animeCustomLists else Anilist.mangaCustomLists
        current?.forEach { addCustomListItem(it, container, isAnime) }
        customAlertDialog().apply {
            setTitle(getString(if (isAnime) R.string.custom_anime_list else R.string.custom_manga_list))
            setCustomView(content)
            setPosButton(getString(R.string.save)) { saveCustomLists(container, isAnime) }
            setNegButton(getString(R.string.cancel))
            show()
        }
    }

    private fun addCustomListItem(listName: String, container: LinearLayout, isAnime: Boolean) {
        val item = layoutInflater.inflate(R.layout.item_custom_list, container, false)
        val input = item.findViewById<TextInputLayout>(R.id.customListItem)
        val editText = input.editText as? TextInputEditText
        editText?.setText(listName)
        input.setEndIconOnClickListener {
            val name = editText?.text.toString()
            val saved = if (isAnime) Anilist.animeCustomLists else Anilist.mangaCustomLists
            if (name.isNotEmpty() && saved?.contains(name) == true) {
                customAlertDialog().apply {
                    setTitle(getString(R.string.delete_custom_list))
                    setMessage(getString(R.string.delete_custom_list_confirm, name))
                    setPosButton(getString(R.string.delete)) {
                        deleteCustomList(name, isAnime)
                        container.removeView(item)
                    }
                    setNegButton(getString(R.string.cancel))
                }.show()
            } else {
                container.removeView(item)
            }
        }
        container.addView(item)
    }

    private fun deleteCustomList(name: String, isAnime: Boolean) {
        lifecycleScope.launch {
            val type = if (isAnime) "ANIME" else "MANGA"
            if (AnilistMutations().deleteCustomList(name, type)) {
                if (isAnime) {
                    Anilist.animeCustomLists = Anilist.animeCustomLists?.filter { it != name }
                } else {
                    Anilist.mangaCustomLists = Anilist.mangaCustomLists?.filter { it != name }
                }
                toast("Custom list deleted")
                refresh()
            } else {
                toast("Failed to delete custom list")
            }
        }
    }

    /** AniList takes both lists in one call; the side not being edited is sent unchanged. */
    private fun saveCustomLists(container: LinearLayout, isAnime: Boolean) {
        val edited = container.children
            .mapNotNull { (it.findViewById<TextInputLayout>(R.id.customListItem).editText as? TextInputEditText)?.text?.toString() }
            .filter { it.isNotEmpty() }
            .toList()
        val anime = if (isAnime) edited else Anilist.animeCustomLists.orEmpty()
        val manga = if (isAnime) Anilist.mangaCustomLists.orEmpty() else edited
        lifecycleScope.launch {
            if (AnilistMutations().updateCustomLists(anime, manga)) {
                Anilist.animeCustomLists = anime
                Anilist.mangaCustomLists = manga
                toast("Custom lists saved")
                refresh()
            } else {
                toast("Failed to save custom lists")
            }
        }
    }
}

/** AniList account settings. Every change is sent to AniList, then kept locally. */
object AnilistScreen : SettingsScreen("anilist", R.string.anilist_settings, R.drawable.ic_anilist, R.string.alsettings_desc) {

    override val activity get() = AnilistSettingsActivity::class.java

    private val TITLE_KEYS = listOf("ENGLISH", "ROMAJI", "NATIVE")
    private val STAFF_KEYS = listOf("ROMAJI_WESTERN", "ROMAJI", "NATIVE")
    private val SCORE_KEYS = listOf("POINT_100", "POINT_10_DECIMAL", "POINT_10", "POINT_5", "POINT_3")

    /** Runs [save] (an AniList mutation plus the local copy), then confirms. */
    private fun saveThen(host: SettingsHost, save: suspend (AnilistMutations) -> Unit) {
        val activity = host.activity
        activity.lifecycleScope.launch {
            save(AnilistMutations())
            snackString(R.string.success)
            host.refresh()
        }
    }

    private fun accountChoice(
        id: String,
        title: Int,
        icon: Int,
        labels: (android.content.Context) -> List<String>,
        get: () -> Int,
        save: suspend (AnilistMutations, Int) -> Unit,
    ) = Row(
        id = id, title = title, icon = icon,
        kind = Kind.Choice(labels = labels, get = get, set = { host, i ->
            if (i != get()) saveThen(host) { save(it, i) }
        }),
    )

    private fun accountSwitch(id: String, title: Int, summary: Int, icon: Int, get: () -> Boolean,
                              save: suspend (AnilistMutations, Boolean) -> Unit) = Row(
        id = id, title = title, summary = summary, icon = icon,
        kind = Kind.Switch(get = get, set = { host, checked -> saveThen(host) { save(it, checked) } }),
    )

    private fun customListsRow(id: String, title: Int, isAnime: Boolean) = actionRow(
        id, title, icon = R.drawable.format_list_bulleted_24,
        current = { c ->
            val lists = if (isAnime) Anilist.animeCustomLists else Anilist.mangaCustomLists
            if (lists.isNullOrEmpty()) c.getString(R.string.none) else lists.joinToString(", ")
        },
    ) { host, _ -> (host.activity as? AnilistSettingsActivity)?.editCustomLists(isAnime) }

    override fun sections() = listOf(
        Section(R.string.anime_manga, listOf(
            accountChoice("anilist_title_language", R.string.selected_title_language, R.drawable.ic_round_translate_24,
                labels = { Anilist.titleLang },
                get = {
                    (UserTitleLanguage.entries.firstOrNull { it.name == Anilist.titleLanguage }
                        ?: UserTitleLanguage.ENGLISH).ordinal
                }) { m, i ->
                m.updateSettings(titleLanguage = TITLE_KEYS[i])
                Anilist.titleLanguage = TITLE_KEYS[i]
            },
            accountChoice("anilist_staff_language", R.string.selected_staff_language, R.drawable.ic_round_person_24,
                labels = { Anilist.staffNameLang },
                get = {
                    (UserStaffNameLanguage.entries.firstOrNull { it.name == Anilist.staffNameLanguage }
                        ?: UserStaffNameLanguage.ROMAJI_WESTERN).ordinal
                }) { m, i ->
                m.updateSettings(staffNameLanguage = STAFF_KEYS[i])
                Anilist.staffNameLanguage = STAFF_KEYS[i]
            },
            accountChoice("anilist_merge_time", R.string.selected_merge_time, R.drawable.ic_round_notifications_active_24,
                labels = { Anilist.activityMergeTimeMap.keys.toList() },
                get = { Anilist.activityMergeTimeMap.values.indexOf(Anilist.activityMergeTime).coerceAtLeast(0) }) { m, i ->
                val minutes = Anilist.activityMergeTimeMap.values.toList()[i]
                m.updateSettings(activityMergeTime = minutes)
                Anilist.activityMergeTime = minutes
            },
            accountSwitch("anilist_airing_notifications", R.string.airing_notifications,
                R.string.airing_notifications_desc, R.drawable.ic_round_notifications_active_24,
                { Anilist.airingNotifications }) { m, on ->
                m.updateSettings(airingNotifications = on)
                Anilist.airingNotifications = on
            },
            accountSwitch("anilist_adult", R.string.display_adult_content, R.string.display_adult_content_desc,
                R.drawable.ic_round_nsfw_24, { Anilist.adult }) { m, on ->
                m.updateSettings(displayAdultContent = on)
                Anilist.adult = on
            },
        )),
        Section(R.string.list_settings, listOf(
            accountChoice("anilist_score_format", R.string.selected_score_format, R.drawable.ic_round_star_24,
                labels = { Anilist.scoreFormats },
                get = {
                    (ScoreFormat.entries.firstOrNull { it.name == Anilist.scoreFormat } ?: ScoreFormat.POINT_100).ordinal
                }) { m, i ->
                m.updateSettings(scoreFormat = SCORE_KEYS[i])
                Anilist.scoreFormat = SCORE_KEYS[i]
            },
            accountChoice("anilist_row_order", R.string.selected_row_order, R.drawable.ic_round_sort_24,
                labels = { Anilist.rowOrderMap.keys.toList() },
                get = { Anilist.rowOrderMap.values.indexOf(Anilist.rowOrder).coerceAtLeast(0) }) { m, i ->
                val order = Anilist.rowOrderMap.values.toList()[i]
                m.updateSettings(rowOrder = order)
                Anilist.rowOrder = order
            },
            customListsRow("anilist_custom_anime_lists", R.string.custom_anime_list, isAnime = true),
            customListsRow("anilist_custom_manga_lists", R.string.custom_manga_list, isAnime = false),
        )),
        Section(R.string.other, listOf(
            actionRow("anilist_timezone", R.string.selected_time_zone, icon = R.drawable.ic_globe_24,
                current = { c ->
                    Anilist.timezone?.let { Anilist.getDisplayTimezone(it, c) } ?: c.getString(R.string.selected_no_time_zone)
                },
            ) { host, _ ->
                val c = host.activity
                val now = Anilist.timezone?.let { tz -> Anilist.timeZone.indexOfFirst { Anilist.getApiTimezone(it) == tz } } ?: -1
                ChoiceSheet.single(
                    c, c.getString(R.string.selected_time_zone), Anilist.timeZone.map { ChoiceOption(it) }, now,
                ) { i ->
                    val tz = Anilist.getApiTimezone(Anilist.timeZone[i])
                    if (tz != Anilist.timezone) saveThen(host) {
                        it.updateSettings(timezone = tz)
                        Anilist.timezone = tz
                    }
                }
            },
            actionRow("anilist_edit_bio", R.string.edit_bio, R.string.edit_bio_desc, R.drawable.ic_round_edit_note_24) { host, _ ->
                host.activity.startActivity(
                    Intent(host.activity, ani.dantotsu.util.ActivityMarkdownCreator::class.java).putExtra("type", "bio")
                )
            },
            accountSwitch("anilist_restrict_messages", R.string.restrict_messages, R.string.restrict_messages_desc,
                R.drawable.ic_round_lock_open_24, { Anilist.restrictMessagesToFollowing }) { m, on ->
                m.updateSettings(restrictMessagesToFollowing = on)
                Anilist.restrictMessagesToFollowing = on
            },
        )),
    )
}
