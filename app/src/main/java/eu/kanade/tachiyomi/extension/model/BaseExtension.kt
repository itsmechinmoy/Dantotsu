package eu.kanade.tachiyomi.extension.model

interface BaseExtension {
    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double
    val lang: String?
    val isNsfw: Boolean
    val hasReadme: Boolean
    val hasChangelog: Boolean
}
