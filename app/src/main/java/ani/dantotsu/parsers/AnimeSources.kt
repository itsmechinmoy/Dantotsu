package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import ani.dantotsu.lazyList
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

object AnimeSources : WatchSources() {
    override var list: List<Lazier<BaseParser>> = emptyList()
    var pinnedAnimeSources: List<String> = emptyList()
    var isInitialized = false

    suspend fun init(fromExtensions: StateFlow<List<AnimeExtension.Installed>>) {
        pinnedAnimeSources =
            PrefManager.getNullableVal<List<String>>(PrefName.AnimeSourcesOrder, null)
                ?: emptyList()

        // Initialize with the first value from StateFlow
        val initialExtensions = fromExtensions.first()
        list = createParsersFromExtensions(initialExtensions) + listOf(
            Lazier({ LocalAnimeParser() }, "Local"),
            Lazier({ OfflineAnimeParser() }, "Downloaded")
        )
        isInitialized = true

        fromExtensions.collect { extensions ->
            list = sortPinnedAnimeSources(
                createParsersFromExtensions(extensions),
                pinnedAnimeSources
            ) + listOf(
                Lazier({ LocalAnimeParser() }, "Local"),
                Lazier({ OfflineAnimeParser() }, "Downloaded")
            )
        }
    }

    fun performReorderAnimeSources() {
        list = list.filter { it.name != "Local" && it.name != "Downloaded" }
        list = sortPinnedAnimeSources(list, pinnedAnimeSources) + listOf(
            Lazier({ LocalAnimeParser() }, "Local"),
            Lazier({ OfflineAnimeParser() }, "Downloaded")
        )
    }

    private fun createParsersFromExtensions(extensions: List<AnimeExtension.Installed>): List<Lazier<BaseParser>> {
        return extensions.map { extension ->
            val name = extension.name
            Lazier({ DynamicAnimeParser(extension) }, name)
        }
    }

    private fun sortPinnedAnimeSources(
        sources: List<Lazier<BaseParser>>,
        pinnedAnimeSources: List<String>
    ): List<Lazier<BaseParser>> {
        val pinnedSourcesMap = sources.filter { pinnedAnimeSources.contains(it.name) }
            .associateBy { it.name }
        val orderedPinnedSources = pinnedAnimeSources.mapNotNull { name ->
            pinnedSourcesMap[name]
        }
        val unpinnedSources = sources.filterNot { pinnedAnimeSources.contains(it.name) }
        return orderedPinnedSources + unpinnedSources
    }
}


object HAnimeSources : WatchSources() {
    private val aList: List<Lazier<BaseParser>> = lazyList(
    )

    override val list = listOf(aList, AnimeSources.list).flatten()
}
