package ani.dantotsu.media.novel.novelreader

import android.webkit.WebView

object NovelCssInjector {

    data class CssSettings(
        val fontSizePx: Int = 0,
        val letterSpacingEm: Float = 0f,
        val wordSpacingPx: Int = 0,
        val paragraphSpacingPx: Int = 0,
        val textAlignment: TextAlign = TextAlign.INHERIT,
        val horizontalPaddingPx: Int = 0,
    )

    enum class TextAlign { INHERIT, LEFT, CENTER, JUSTIFY }
    fun inject(webView: WebView, settings: CssSettings) {
        val css = buildCss(settings)
        val js = """
            (function() {
                var existing = document.getElementById('dantotsu_reader_css');
                if (existing) { existing.parentNode.removeChild(existing); }
                var style = document.createElement('style');
                style.id = 'dantotsu_reader_css';
                style.textContent = ${css.toJsString()};
                document.head.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun buildCss(s: CssSettings): String {
        val sb = StringBuilder()
        val bodyRules = buildString {
            if (s.horizontalPaddingPx > 0) append("padding-left:${s.horizontalPaddingPx}px !important;padding-right:${s.horizontalPaddingPx}px !important;")
            if (s.fontSizePx > 0) append("font-size:${s.fontSizePx}px !important;")
            if (s.letterSpacingEm != 0f) append("letter-spacing:${s.letterSpacingEm}em !important;")
            if (s.wordSpacingPx != 0) append("word-spacing:${s.wordSpacingPx}px !important;")
            if (s.textAlignment != TextAlign.INHERIT) append("text-align:${s.textAlignment.name.lowercase()} !important;")
        }
        if (bodyRules.isNotEmpty()) sb.append("body,p,span,div{$bodyRules}")
        if (s.paragraphSpacingPx > 0) {
            sb.append("p{margin-bottom:${s.paragraphSpacingPx}px !important;}")
        }

        return sb.toString()
    }

    private fun String.toJsString(): String {
        val escaped = this
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        return "'$escaped'"
    }
}