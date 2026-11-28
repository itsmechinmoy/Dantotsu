package eu.kanade.tachiyomi.extension.model

enum class ExtensionContentWarning {
    SAFE,
    MIXED,
    NSFW;

    val isRestricted: Boolean
        get() = this != SAFE

    fun badgeLabel(): String = when (this) {
        SAFE -> ""
        MIXED -> "(Mixed)"
        NSFW -> "(18+)"
    }
}

interface BaseExtension {
    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double
    val lang: String?
    val isNsfw: Boolean
    val contentWarning: ExtensionContentWarning
        get() = if (isNsfw) ExtensionContentWarning.NSFW else ExtensionContentWarning.SAFE
    val hasReadme: Boolean
    val hasChangelog: Boolean
}
