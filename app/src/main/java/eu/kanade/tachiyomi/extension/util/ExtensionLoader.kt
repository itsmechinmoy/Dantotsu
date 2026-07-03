package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelLoadResult
import ani.dantotsu.util.Logger
import dalvik.system.PathClassLoader
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.lang.Hash
import android.content.pm.ApplicationInfo
import eu.kanade.tachiyomi.util.system.getApplicationIcon
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import java.io.File

/**
 * Class that handles the loading of the extensions. Supports two kinds of extensions:
 *
 * 1. Shared extension: This extension is installed to the system with package
 * installer, so other variants of Tachiyomi and its forks can also use this extension.
 *
 * 2. Private extension: This extension is put inside private data directory of the
 * running app, so this extension can only be used by the running app and not shared
 * with other apps.
 *
 * When both kinds of extensions are installed with a same package name, shared
 * extension will be used unless the version codes are different. In that case the
 * one with higher version code will be used.
 */
internal object ExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val loadNsfwSource by lazy {
        preferences.showNsfwSource().get()
    }

    private const val ANIME_PACKAGE = "tachiyomi.animeextension"
    private const val MANGA_PACKAGE = "tachiyomi.extension"

    private const val XX_METADATA_SOURCE_CLASS = ".class"
    private const val XX_METADATA_SOURCE_FACTORY = ".factory"
    private const val XX_METADATA_NSFW = "n.nsfw"
    private const val XX_METADATA_HAS_README = ".hasReadme"
    private const val XX_METADATA_HAS_CHANGELOG = ".hasChangelog"
    const val ANIME_LIB_VERSION_MIN = 12
    const val ANIME_LIB_VERSION_MAX = 16

    const val MANGA_LIB_VERSION_MIN = 1.4
    const val MANGA_LIB_VERSION_MAX = 1.6

    val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    private fun File.copyAndSetReadOnlyTo(target: File): File {
        if (!this.exists()) {
            throw NoSuchFileException(file = this, reason = "The source file doesn't exist.")
        }
        if (target.exists()) {
            if (!target.delete()) {
                throw FileAlreadyExistsException(
                    file = this,
                    other = target,
                    reason = "Tried to overwrite the destination, but failed to delete it.",
                )
            }
        }
        target.parentFile?.mkdirs()
        this.inputStream().use { input ->
            target.outputStream().use { output ->
                target.setReadOnly()
                input.copyTo(output)
            }
        }
        return target
    }

    fun installPrivateExtensionFile(context: Context, file: File, type: MediaType): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(type, it) } ?: return false
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName, type)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                Logger.log("Installed extension version is higher. Downgrading is not allowed.")
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                Logger.log("Extension to be installed is not signed.")
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                Logger.log("Installed extension signature is not matched.")
                return false
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        return try {
            target.delete()
            file.copyAndSetReadOnlyTo(target)
            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
            } else {
                ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
            }
            true
        } catch (e: Exception) {
            Logger.log("Failed to install private extension: $e")
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        val file = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        if (file.exists()) {
            file.delete()
        }
    }

    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String, type: MediaType): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(type, it) }
                ?.let {
                    it.applicationInfo!!.fixBasePaths(privateExtensionFile.absolutePath)
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(type, it) }
                ?.let {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String, type: MediaType): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName, type)?.packageInfo
    }

    /**
     * Return a list of all the installed extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadAnimeExtensions(context: Context): List<AnimeLoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(MediaType.ANIME, it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                if (it.canWrite()) {
                    it.setReadOnly()
                }
                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.apply { applicationInfo!!.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(MediaType.ANIME, it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            .distinctBy { it.packageInfo.packageName }
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            val deferred = extPkgs.map {
                async { loadAnimeExtension(context, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadMangaExtensions(context: Context): List<MangaLoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(MediaType.MANGA, it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                if (it.canWrite()) {
                    it.setReadOnly()
                }
                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.apply { applicationInfo!!.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(MediaType.MANGA, it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            .distinctBy { it.packageInfo.packageName }
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            val deferred = extPkgs.map {
                async { loadMangaExtension(context, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadNovelExtensions(context: Context): List<NovelLoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(MediaType.NOVEL, it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                if (it.canWrite()) {
                    it.setReadOnly()
                }
                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.apply { applicationInfo!!.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(MediaType.NOVEL, it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            .distinctBy { it.packageInfo.packageName }
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            val deferred = extPkgs.map {
                async { loadNovelExtension(context, it) }
            }
            deferred.map { it.await() }
        }
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    fun loadAnimeExtensionFromPkgName(context: Context, pkgName: String): AnimeLoadResult {
        val extensionInfo = getExtensionInfoFromPkgName(context, pkgName, MediaType.ANIME)
            ?: return AnimeLoadResult.Error
        return loadAnimeExtension(context, extensionInfo)
    }

    fun loadMangaExtensionFromPkgName(context: Context, pkgName: String): MangaLoadResult {
        val extensionInfo = getExtensionInfoFromPkgName(context, pkgName, MediaType.MANGA)
            ?: return MangaLoadResult.Error
        return loadMangaExtension(context, extensionInfo)
    }

    fun loadNovelExtensionFromPkgName(context: Context, pkgName: String): NovelLoadResult {
        val extensionInfo = getExtensionInfoFromPkgName(context, pkgName, MediaType.NOVEL)
            ?: return NovelLoadResult.Error(Exception("Extension info not found"))
        return loadNovelExtension(context, extensionInfo)
    }

    /**
     * Loads an extension given its package name.
     *
     * @param context The application context.
     */
    private fun loadAnimeExtension(
        context: Context,
        extensionInfo: ExtensionInfo
    ): AnimeLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName
        val pkgManager = context.packageManager

        val appInfo = try {
            pkgManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
        } catch (error: PackageManager.NameNotFoundException) {
            // Unlikely, but the package may have been uninstalled at this point
            Logger.log(error)
            return AnimeLoadResult.Error
        }

        if (!extensionInfo.isShared) {
            val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName = pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Aniyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            Logger.log("Missing versionName for extension $extName")
            return AnimeLoadResult.Error
        }

        // Validate lib version
        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion < ANIME_LIB_VERSION_MIN || libVersion > ANIME_LIB_VERSION_MAX) {
            Logger.log(
                "Lib version is $libVersion, while only versions " +
                        "$ANIME_LIB_VERSION_MIN to $ANIME_LIB_VERSION_MAX are allowed"
            )
            return AnimeLoadResult.Error
        }

        val isNsfw = appInfo.metaData.getInt("$ANIME_PACKAGE$XX_METADATA_NSFW") == 1
        if (!loadNsfwSource && isNsfw) {
            Logger.log("NSFW extension $pkgName not allowed")
            return AnimeLoadResult.Error
        }

        val hasReadme = appInfo.metaData.getInt("$ANIME_PACKAGE$XX_METADATA_HAS_README", 0) == 1
        val hasChangelog =
            appInfo.metaData.getInt("$ANIME_PACKAGE$XX_METADATA_HAS_CHANGELOG", 0) == 1

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            Injekt.get<CrashlyticsInterface>().logException(e)
            return AnimeLoadResult.Error
        }

        val sources = appInfo.metaData.getString("$ANIME_PACKAGE$XX_METADATA_SOURCE_CLASS")!!
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor()
                        .newInstance()) {
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type! ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    Logger.log("Extension load error: $extName ($it)")
                    return AnimeLoadResult.Error
                }
            }

        val langs = sources.filterIsInstance<AnimeCatalogueSource>()
            .map { it.lang }
            .toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = AnimeExtension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            hasReadme = hasReadme,
            hasChangelog = hasChangelog,
            sources = sources,
            pkgFactory = appInfo.metaData.getString("$ANIME_PACKAGE$XX_METADATA_SOURCE_FACTORY"),
            isUnofficial = true,
            icon = context.getApplicationIcon(pkgName),
        )
        return AnimeLoadResult.Success(extension)
    }

    private fun loadMangaExtension(
        context: Context,
        extensionInfo: ExtensionInfo
    ): MangaLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName
        val pkgManager = context.packageManager

        val appInfo = try {
            pkgManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
        } catch (error: PackageManager.NameNotFoundException) {
            // Unlikely, but the package may have been uninstalled at this point
            Logger.log(error)
            return MangaLoadResult.Error
        }

        if (!extensionInfo.isShared) {
            val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName =
            pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            Logger.log("Missing versionName for extension $extName")
            return MangaLoadResult.Error
        }

        // Validate lib version
        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion < MANGA_LIB_VERSION_MIN || libVersion > MANGA_LIB_VERSION_MAX) {
            Logger.log(
                "Lib version is $libVersion, while only versions " +
                        "$MANGA_LIB_VERSION_MIN to $MANGA_LIB_VERSION_MAX are allowed"
            )
            return MangaLoadResult.Error
        }

        val isNsfw = appInfo.metaData.getInt("$MANGA_PACKAGE$XX_METADATA_NSFW") == 1
        if (!loadNsfwSource && isNsfw) {
            Logger.log("NSFW extension $pkgName not allowed")
            return MangaLoadResult.Error
        }

        val hasReadme = appInfo.metaData.getInt("$MANGA_PACKAGE$XX_METADATA_HAS_README", 0) == 1
        val hasChangelog =
            appInfo.metaData.getInt("$MANGA_PACKAGE$XX_METADATA_HAS_CHANGELOG", 0) == 1

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            Injekt.get<CrashlyticsInterface>().logException(e)
            return MangaLoadResult.Error
        }

        val sources = appInfo.metaData.getString("$MANGA_PACKAGE$XX_METADATA_SOURCE_CLASS")!!
            .split(";")
            .map {
                val sourceClass = it.trim()
                if (sourceClass.startsWith(".")) {
                    pkgInfo.packageName + sourceClass
                } else {
                    sourceClass
                }
            }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader)
                        .getDeclaredConstructor().newInstance()) {
                        is MangaSource -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type! ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    Logger.log("Extension load error: $extName ($it)")
                    return MangaLoadResult.Error
                }
            }

        val langs = sources.filterIsInstance<CatalogueSource>()
            .map { it.lang }
            .toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        val extension = MangaExtension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            isNsfw = isNsfw,
            hasReadme = hasReadme,
            hasChangelog = hasChangelog,
            sources = sources,
            pkgFactory = appInfo.metaData.getString("$MANGA_PACKAGE$XX_METADATA_SOURCE_FACTORY"),
            isUnofficial = true,
            icon = context.getApplicationIcon(pkgName),
        )
        return MangaLoadResult.Success(extension)
    }

    private fun loadNovelExtension(
        context: Context,
        extensionInfo: ExtensionInfo
    ): NovelLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName
        val pkgManager = context.packageManager

        val appInfo = try {
            pkgManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
        } catch (error: PackageManager.NameNotFoundException) {
            // Unlikely, but the package may have been uninstalled at this point
            Logger.log(error)
            return NovelLoadResult.Error(error)
        }

        if (!extensionInfo.isShared) {
            val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName =
            pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            Logger.log("Missing versionName for extension $extName")
            return NovelLoadResult.Error(Exception("Missing versionName for extension $extName"))
        }

        val classLoader = ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        val novelInterfaceInstance = try {
            val className = appInfo.loadLabel(context.packageManager).toString()
            val extensionClassName =
                "some.random.novelextensions.${className.lowercase(Locale.getDefault())}.$className"
            val loadedClass = classLoader.loadClass(extensionClassName)
            val instance = loadedClass.getDeclaredConstructor().newInstance()
            instance as? NovelInterface
        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            return NovelLoadResult.Error(e as Exception)
        }

        val extension = NovelExtension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            sources = listOfNotNull(novelInterfaceInstance),
            isUnofficial = true,
            icon = context.getApplicationIcon(pkgName),
        )
        return NovelLoadResult.Success(extension)
    }


    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(type: MediaType, pkgInfo: PackageInfo): Boolean {

        return if (type == MediaType.NOVEL) {
            pkgInfo.packageName.startsWith("some.random")
        } else {
            pkgInfo.reqFeatures.orEmpty().any {
                it.name == when (type) {
                    MediaType.ANIME -> ANIME_PACKAGE
                    MediaType.MANGA -> MANGA_PACKAGE
                    else -> ""
                }
            }
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}
