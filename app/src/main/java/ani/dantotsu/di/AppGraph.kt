package ani.dantotsu.di

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import ani.dantotsu.App
import ani.dantotsu.MainActivity
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.di.injekt.MetroInteropModule
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.torrent.TorrentServerManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager

@OptIn(ExperimentalSerializationApi::class)
@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class],
)
interface AppGraph {
    fun inject(app: App)
    fun inject(mainActivity: MainActivity)

    val context: Context
    val application: Application

    val preferenceStore: PreferenceStore
    val sourcePreferences: SourcePreferences
    val basePreferences: BasePreferences

    val downloadsManager: DownloadsManager
    val networkHelper: NetworkHelper
    val javaScriptEngine: JavaScriptEngine
    val animeExtensionManager: AnimeExtensionManager
    val mangaExtensionManager: MangaExtensionManager
    val novelExtensionManager: NovelExtensionManager
    val torrentServerManager: TorrentServerManager
    val downloadAddonManager: DownloadAddonManager
    val animeSourceManager: AnimeSourceManager
    val mangaSourceManager: MangaSourceManager
    val mangaCache: MangaCache
    val json: Json
    val protoBuf: ProtoBuf
    val metroInteropModule: MetroInteropModule

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
