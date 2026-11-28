package ani.dantotsu.media

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.api.ExternalLinkType
import ani.dantotsu.connections.anilist.api.MediaExternalLink
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.openLinkInCustomTab
import ani.dantotsu.toPx
import com.google.android.material.card.MaterialCardView

object OfficialStreamsUi {
    private val OFFICIAL_STREAM_SITES = setOf(
        "crunchyroll", "netflix", "hulu", "amazon", "prime video", "amazon prime video",
        "disney plus", "disney+", "hidive", "max", "hbo max", "tubi", "tubi tv",
        "bilibili", "bilibili tv", "youtube", "ani-one asia", "muse asia", "adn", "iqiyi"
    )

    private val FALLBACK_SITE_COLORS = mapOf(
        "crunchyroll" to "#F47521",
        "netflix" to "#E50914",
        "hulu" to "#1CE783",
        "hidive" to "#00BAFF",
        "youtube" to "#FF0000",
        "amazon" to "#00A8E1",
        "prime" to "#00A8E1",
        "disney" to "#113CCF",
        "bilibili" to "#00A1D6"
    )

    fun filterStreamingLinks(links: List<MediaExternalLink>): List<MediaExternalLink> {
        return links.filter { link ->
            !link.url.isNullOrBlank() && (
                link.type == ExternalLinkType.STREAMING ||
                    OFFICIAL_STREAM_SITES.any { site -> link.site.contains(site, ignoreCase = true) }
                )
        }.distinctBy { it.site.lowercase() }
    }

    fun bindCircularStreamLogos(
        container: LinearLayout,
        media: Media,
        maxIcons: Int = 4,
        iconSizeDp: Int = 22
    ) {
        val links = filterStreamingLinks(media.externalLinks.orEmpty()).take(maxIcons)
        container.removeAllViews()
        container.isVisible = links.isNotEmpty()
        if (links.isEmpty()) return

        val ctx = container.context
        val sizePx = iconSizeDp.toPx
        val marginPx = 4.toPx
        val padPx = (iconSizeDp / 5).coerceAtLeast(3).toPx

        links.forEach { link ->
            val brandColor = resolveBrandColor(link)
                ?: ctx.getThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)

            val card = MaterialCardView(ctx).apply {
                radius = sizePx / 2f
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(brandColor)
                contentDescription = link.site
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    link.url?.let { url -> openLinkInCustomTab(url) }
                }
            }

            if (!link.icon.isNullOrBlank()) {
                val img = ImageView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setPadding(padPx, padPx, padPx, padPx)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    loadImage(link.icon)
                }
                card.addView(img)
            } else {
                val badgeText = TextView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    gravity = Gravity.CENTER
                    text = link.site.trim().firstOrNull()?.uppercase() ?: "▶"
                    setTextColor(Color.WHITE)
                    textSize = (iconSizeDp * 0.45f).coerceAtLeast(9f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                card.addView(badgeText)
            }

            container.addView(card)
        }
    }

    private fun resolveBrandColor(link: MediaExternalLink): Int? {
        link.color?.let { raw ->
            runCatching { Color.parseColor(raw) }.getOrNull()?.let { return it }
        }
        val siteLower = link.site.lowercase()
        val hex = FALLBACK_SITE_COLORS.entries.firstOrNull { siteLower.contains(it.key) }?.value
        return hex?.let { runCatching { Color.parseColor(it) }.getOrNull() }
    }
}
