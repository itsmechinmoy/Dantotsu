package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import ani.dantotsu.parsers.novel.DynamicNovelParser
import ani.dantotsu.parsers.novel.LnReaderNovelParser
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

object NovelSources : NovelReadSources() {
    override var list: List<Lazier<BaseParser>> = emptyList()
    var pinnedNovelSources: List<String> = emptyList()
    suspend fun init(fromExtensions: Flow<List<NovelExtension>>) {
        pinnedNovelSources =
            PrefManager.getNullableVal<List<String>>(PrefName.NovelSourcesOrder, null)
                ?: emptyList()

        val initialExtensions = fromExtensions.first()
        list = createParsersFromExtensions(initialExtensions) + Lazier(
            { OfflineNovelParser() }, "Downloaded"
        )

        fromExtensions.collect { extensions ->
            list = sortPinnedNovelSources(
                createParsersFromExtensions(extensions),
                pinnedNovelSources
            ) + Lazier({ OfflineNovelParser() }, "Downloaded")
        }
    }

    fun performReorderNovelSources() {
        list = list.filter { it.name != "Downloaded" }
        list = sortPinnedNovelSources(list, pinnedNovelSources) + Lazier(
            { OfflineNovelParser() }, "Downloaded"
        )
    }

    private fun createParsersFromExtensions(
        extensions: List<NovelExtension>
    ): List<Lazier<BaseParser>> {
        Logger.log("NovelSources.createParsersFromExtensions: ${extensions.size} extensions")
        return extensions.mapNotNull { ext ->
            when (ext) {
                is NovelExtension.Installed -> {
                    val name = ext.name
                    Lazier({ DynamicNovelParser(ext) }, name)
                }
                is NovelExtension.JsPlugin -> {
                    val plugin = ext.plugin
                    Lazier({ LnReaderNovelParser(plugin) }, plugin.name)
                }
                is NovelExtension.Available -> null
            }
        }
    }

    private fun sortPinnedNovelSources(
        parsers: List<Lazier<BaseParser>>,
        pinnedSources: List<String>
    ): List<Lazier<BaseParser>> {
        val pinnedMap = parsers.filter { pinnedSources.contains(it.name) }
            .associateBy { it.name }
        val ordered  = pinnedSources.mapNotNull { pinnedMap[it] }
        val unpinned = parsers.filterNot { pinnedSources.contains(it.name) }
        return ordered + unpinned
    }
}
