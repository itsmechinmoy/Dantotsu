package eu.kanade.tachiyomi.extension.api

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList()
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources = Resources(),
        @ProtoNumber(4) val extensionLib: String = "1.4",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    @Suppress("Unused")
    @Serializable
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }
}

@Serializable
data class NetworkLegacyExtensionRepo(
    @SerialName("index_v2")
    val indexV2: String? = null,
    val meta: Meta,
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String,
        val signingKeyFingerprint: String,
    )
}

@Serializable
data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<Source>? = null,
) {
    @Serializable
    data class Source(
        val id: Long,
        val lang: String,
        val name: String,
        val baseUrl: String,
    )
}
