package ani.dantotsu.settings

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetRecyclerBinding
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.parsers.novel.NovelExtensionManager
import com.xwray.groupie.GroupieAdapter
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SubscriptionsBottomDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetRecyclerBinding? = null
    private val binding get() = _binding!!
    private val adapter: GroupieAdapter = GroupieAdapter()
    private var subscriptions: Map<Int, SubscriptionHelper.Companion.SubscribeMedia> = mapOf()
    private var groupedSubscriptions:
        MutableMap<String, MutableList<SubscriptionHelper.Companion.SubscribeMedia>> = mutableMapOf()
    private var currentFilter: String? = null
    private val animeExtension: AnimeExtensionManager = Injekt.get()
    private val mangaExtensions: MangaExtensionManager = Injekt.get()
    private val novelExtensions: NovelExtensionManager = Injekt.get()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = BottomSheetRecyclerBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.repliesRecyclerView.adapter = adapter
        binding.repliesRecyclerView.layoutManager = LinearLayoutManager(
            context,
            LinearLayoutManager.VERTICAL,
            false
        )
        val context = requireContext()
        binding.title.text = context.getString(R.string.subscriptions)
        binding.replyButton.apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.ic_round_filter_list_24)
            contentDescription = context.getString(R.string.filter)
            setOnClickListener { showFilterMenu() }
        }

        groupedSubscriptions = subscriptions.values.groupBy {
            if (it.isAnime) SubscriptionHelper.getAnimeParser(it.id).name
            else SubscriptionHelper.getMangaParser(it.id).name
        }.mapValues { it.value.toMutableList() }.toMutableMap()

        updateAdapter()
    }

    private fun updateAdapter() {
        adapter.clear()
        val visibleSubscriptions = if (currentFilter == null) {
            groupedSubscriptions
        } else {
            groupedSubscriptions.filterKeys { it == currentFilter }
        }

        visibleSubscriptions.forEach { (parserName, mediaList) ->
            adapter.add(SubscriptionSource(
                parserName,
                mediaList,
                adapter,
                getParserIcon(parserName)
            ) { group ->
                adapter.remove(group)
                groupedSubscriptions.remove(parserName)
            })
        }
    }

    private fun showFilterMenu() {
        val context = requireContext()
        val popup = PopupMenu(context, binding.replyButton)
        popup.menu.add(context.getString(R.string.all))
        groupedSubscriptions.keys.sorted().forEach { parserName ->
            popup.menu.add(parserName)
        }
        popup.setOnMenuItemClickListener { item ->
            currentFilter = if (item.title == context.getString(R.string.all)) {
                null
            } else {
                item.title.toString()
            }
            updateAdapter()
            true
        }
        popup.show()
    }

    private fun getParserIcon(parserName: String): Drawable? {
        return when {
            animeExtension.installedExtensionsFlow.value.any { it.name == parserName } ->
                animeExtension.installedExtensionsFlow.value.find { it.name == parserName }?.icon

            mangaExtensions.installedExtensionsFlow.value.any { it.name == parserName } ->
                mangaExtensions.installedExtensionsFlow.value.find { it.name == parserName }?.icon

            novelExtensions.installedExtensionsFlow.value.any { it.name == parserName } ->
                novelExtensions.installedExtensionsFlow.value.find { it.name == parserName }?.icon

            else -> null
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(subscriptions: Map<Int, SubscriptionHelper.Companion.SubscribeMedia>): SubscriptionsBottomDialog {
            val dialog = SubscriptionsBottomDialog()
            dialog.subscriptions = subscriptions
            return dialog
        }
    }
}
