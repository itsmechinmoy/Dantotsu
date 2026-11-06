package ani.dantotsu.others

import android.graphics.Color
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.utils.ColorUtils
import java.util.regex.Pattern

class SpoilerPlugin(private val anilist: Boolean = false) : AbstractMarkwonPlugin() {
    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        val spannable = markdown as? Spannable ?: return
        if (anilist) {
            applySpoilerSpans(spannable, ARE)
        } else {
            applySpoilerSpans(spannable, RE)
            applySpoilerSpans(spannable, ARE)
        }
    }

    class RedditSpoilerSpan : CharacterStyle() {
        var revealed = false
            private set

        override fun updateDrawState(tp: TextPaint) {
            if (!revealed) {
                tp.bgColor = Color.DKGRAY
                tp.color = Color.DKGRAY
            } else {
                tp.bgColor = ColorUtils.applyAlpha(Color.DKGRAY, 25)
            }
        }

        fun setRevealed(revealed: Boolean) {
            this.revealed = revealed
        }
    }

    private class HideSpoilerSyntaxSpan : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            tp.color = 0
        }
    }

    companion object {
        private val RE = Pattern.compile("\\|\\|.+?\\|\\|", Pattern.DOTALL)
        // Matches ~! text !~ and ~!{Label} text !~ with multiline and closest pairing support
        private val ARE = Pattern.compile("~!(?:\\{([^}\r\n<>]*)\\})?(.+?)!~", Pattern.DOTALL)

        private fun applySpoilerSpans(spannable: Spannable, regex: Pattern = RE) {
            val text = spannable.toString()
            val matcher = regex.matcher(text)
            while (matcher.find()) {
                val spoilerSpan = RedditSpoilerSpan()
                val clickableSpan: ClickableSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        spoilerSpan.setRevealed(true)
                        if (widget is TextView && widget.text is Spannable) {
                            (widget.text as Spannable).removeSpan(this)
                        }
                        widget.postInvalidateOnAnimation()
                    }

                    override fun updateDrawState(ds: TextPaint) {}
                }
                val s = matcher.start()
                val e = matcher.end()
                spannable.setSpan(spoilerSpan, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(clickableSpan, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                // Calculate opening syntax length: ~! (2) or ~!{Label} (2 + label.length + 2)
                val openerEnd = if (matcher.groupCount() >= 1 && matcher.group(1) != null) {
                    matcher.end(1) + 1
                } else {
                    s + 2
                }

                // Hide opening syntax (~!, ||, or ~!{Label})
                spannable.setSpan(
                    HideSpoilerSyntaxSpan(),
                    s,
                    openerEnd.coerceAtMost(e),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // Hide closing syntax (!~ or ||)
                spannable.setSpan(
                    HideSpoilerSyntaxSpan(),
                    (e - 2).coerceAtLeast(s),
                    e,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
