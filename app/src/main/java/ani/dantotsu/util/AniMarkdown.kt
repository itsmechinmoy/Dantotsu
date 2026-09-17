package ani.dantotsu.util

import ani.dantotsu.getYoutubeId
import ani.dantotsu.util.ColorEditor.Companion.toCssColor

class AniMarkdown { //istg anilist has the worst api
    companion object {
        private fun String.convertNestedImageToHtml(): String {
            val regex = """\[!\[(.*?)]\((.*?)\)]\((.*?)\)""".toRegex()
            return regex.replace(this) { matchResult ->
                val altText = matchResult.groupValues[1]
                val imageUrl = matchResult.groupValues[2]
                val linkUrl = matchResult.groupValues[3]
                """<a href="$linkUrl"><img src="$imageUrl" alt="$altText"></a>"""
            }
        }

        private fun String.convertImageToHtml(): String {
            val regex = """!\[(.*?)]\((.*?)\)""".toRegex()
            val anilistRegex = """img\d*\((.*?)\)""".toRegex()
            val markdownImage = regex.replace(this) { matchResult ->
                val altText = matchResult.groupValues[1]
                val imageUrl = matchResult.groupValues[2]
                """<img src="$imageUrl" alt="$altText">"""
            }
            return anilistRegex.replace(markdownImage) { matchResult ->
                val imageUrl = matchResult.groupValues[1]
                """<img src="$imageUrl" alt="Image">"""
            }
        }

        private fun String.convertLinkToHtml(): String {
            val regex = """\[(.*?)]\((.*?)\)""".toRegex()
            return regex.replace(this) { matchResult ->
                val linkText = matchResult.groupValues[1]
                val linkUrl = matchResult.groupValues[2]
                """<a href="$linkUrl">$linkText</a>"""
            }
        }

        private fun String.convertYoutubeToHtml(interactive: Boolean = false): String {
            val divRegex = """<div class='youtube' id='(.*?)'></div>""".toRegex()
            val markdownRegex = """youtube\((.*?)\)""".toRegex()

            fun getYoutubeEmbedHtml(url: String): String {
                val id = getYoutubeId(url)
                return if (id.isNotEmpty()) {
                    if (interactive) {
                        """<div class="yt-embed" data-id="$id" onclick="this.innerHTML='<iframe src=\'https://www.youtube-nocookie.com/embed/$id?autoplay=1&amp;rel=0&amp;modestbranding=1&amp;controls=1\' allow=\'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture\' allowfullscreen frameborder=\'0\'></iframe>';"><img src="https://img.youtube.com/vi/$id/hqdefault.jpg" alt="$url"><div class="yt-play-btn"><div class="yt-play-icon"></div></div></div>""".trimIndent()
                    } else {
                        """<div>
                        <a href="https://www.youtube.com/watch?v=$id"><img src="https://i3.ytimg.com/vi/$id/maxresdefault.jpg" alt="$url"></a>
                        <align center>
                        <a href="https://www.youtube.com/watch?v=$id">Youtube Link</a>
                        </align>
                        </div>""".trimIndent()
                    }
                } else {
                    """<a href="$url">Youtube Video</a>"""
                }
            }

            val step1 = markdownRegex.replace(this) { matchResult ->
                getYoutubeEmbedHtml(matchResult.groupValues[1])
            }
            return divRegex.replace(step1) { matchResult ->
                getYoutubeEmbedHtml(matchResult.groupValues[1])
            }
        }

        private fun String.convertWebmToHtml(): String {
            val regex = """webm\((.*?)\)""".toRegex()
            return regex.replace(this) { matchResult ->
                val videoUrl = matchResult.groupValues[1]
                """<video src="$videoUrl" controls loop muted autoplay></video>"""
            }
        }

        private fun String.replaceLeftovers(): String {
            return this.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("<pre>", "")
                .replace("`", "")
                .replace("~", "")
                .replace(">\n<", "><")
                .replace("\n", "<br>")
        }

        private fun String.underlineToHtml(): String {
            return this.replace("(?s)___(.*?)___".toRegex(), "<br><em><strong>$1</strong></em><br>")
                .replace("(?s)__(.*?)__".toRegex(), "<br><strong>$1</strong><br>")
                .replace("(?s)\\s+_([^_]+)_\\s+".toRegex(), "<em>$1</em>")
        }

        private fun String.convertCenterToHtml(): String {
            val regex = """~~~(.*?)~~~""".toRegex()
            return regex.replace(this) { matchResult ->
                val centerText = matchResult.groupValues[1]
                """<align center>$centerText</align>"""
            }
        }

        fun getBasicAniHTML(html: String): String {
            return html
                .convertNestedImageToHtml()
                .convertImageToHtml()
                .convertLinkToHtml()
                .convertYoutubeToHtml()
                .convertWebmToHtml()
                .convertCenterToHtml()
                .replaceLeftovers()
                .underlineToHtml()
        }

        fun getFullAniHTML(html: String, textColor: Int): String {
            val contentHtml = html
                .convertNestedImageToHtml()
                .convertImageToHtml()
                .convertLinkToHtml()
                .convertYoutubeToHtml(interactive = true)
                .convertWebmToHtml()
                .convertCenterToHtml()
                .replaceLeftovers()
                .underlineToHtml()

            val returnHtml = """
            <html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, charset=UTF-8">
        <style>
            body {
                background-color: transparent;
                color: ${textColor.toCssColor()};
                margin: 0;
                padding: 0;
                max-width: 100%;
                overflow-x: hidden; /* Prevent horizontal scrolling */
            }
            img {
                max-width: 100%;
                height: auto; /* Maintain aspect ratio */
            }
            video {
                max-width: 100%;
                height: auto; /* Maintain aspect ratio */
            }
            a {
                color: ${textColor.toCssColor()};
            }
            .yt-embed {
                position: relative;
                width: 100%;
                aspect-ratio: 16 / 9;
                background: #000;
                border-radius: 12px;
                overflow: hidden;
                cursor: pointer;
                display: flex;
                align-items: center;
                justify-content: center;
                margin: 12px 0;
                -webkit-tap-highlight-color: transparent;
            }
            .yt-embed img {
                width: 100%;
                height: 100%;
                object-fit: cover;
            }
            .yt-play-btn {
                position: absolute;
                width: 68px;
                height: 48px;
                background: rgba(255, 0, 0, 0.85);
                border-radius: 12px;
                display: flex;
                align-items: center;
                justify-content: center;
                box-shadow: 0 4px 12px rgba(0,0,0,0.5);
                pointer-events: none;
            }
            .yt-play-icon {
                width: 0;
                height: 0;
                border-left: 20px solid white;
                border-top: 12px solid transparent;
                border-bottom: 12px solid transparent;
                margin-left: 4px;
            }
            .yt-embed iframe {
                position: absolute;
                top: 0;
                left: 0;
                width: 100%;
                height: 100%;
                border: none;
            }
        </style>
</head>
<body>$contentHtml</body>
</html>
    """.trimIndent()
            return returnHtml
        }
    }
}
