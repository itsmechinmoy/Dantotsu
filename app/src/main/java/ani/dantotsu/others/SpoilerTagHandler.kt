package ani.dantotsu.others

import android.text.Spannable
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.SimpleTagHandler

/**
 * Handles HTML spoiler tags per AniList November 2026 specifications:
 * - `<span class="markdown-spoiler" data-label="Label">...</span>`
 * - `<div class="markdown-spoiler" data-label="Label">...</div>`
 * - `<div rel="spoiler">...</div>`
 */
class SpoilerTagHandler : SimpleTagHandler() {
    override fun getSpans(
        configuration: MarkwonConfiguration,
        renderProps: RenderProps,
        tag: HtmlTag
    ): Any? {
        val attrs = tag.attributes()
        val isSpoiler = attrs["class"] == "markdown-spoiler" || attrs["rel"] == "spoiler"
        if (!isSpoiler) return null

        val spoilerSpan = SpoilerPlugin.RedditSpoilerSpan()
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                spoilerSpan.setRevealed(true)
                if (widget is TextView && widget.text is Spannable) {
                    (widget.text as Spannable).removeSpan(this)
                }
                widget.postInvalidateOnAnimation()
            }

            override fun updateDrawState(ds: TextPaint) {}
        }
        return arrayOf(spoilerSpan, clickableSpan)
    }

    override fun supportedTags(): Collection<String> = setOf("span", "div")
}
