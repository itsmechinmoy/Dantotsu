package ani.dantotsu.media

import android.annotation.SuppressLint
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.core.view.isVisible
import ani.dantotsu.R

class TrailerBannerHelper(
    private val webView: WebView,
    private val controlsContainer: View,
    private val playPauseButton: ImageButton,
    private val muteButton: ImageButton,
    private val onFallbackToImageBanner: () -> Unit,
    private val onTrailerReadyUi: () -> Unit
) {
    private var loadedVideoId: String? = null
    private var isPlaying = true
    private var isMuted = true
    private var hasErrored = false

    @SuppressLint("SetJavaScriptEnabled")
    fun loadTrailer(rawTrailerId: String?) {
        val videoId = extractYoutubeVideoId(rawTrailerId) ?: run {
            hideAndFallback()
            return
        }
        if (loadedVideoId == videoId && !hasErrored) {
            resume()
            return
        }
        loadedVideoId = videoId
        hasErrored = false
        isPlaying = true
        isMuted = true

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.setBackgroundColor(0x00000000)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }
        webView.removeJavascriptInterface("TrailerBridge")
        webView.addJavascriptInterface(TrailerBridge(), "TrailerBridge")

        updateIcons()
        playPauseButton.setOnClickListener { togglePlayPause() }
        muteButton.setOnClickListener { toggleMute() }

        val html = buildPlayerHtml(videoId)
        webView.loadDataWithBaseURL(
            "https://www.youtube-nocookie.com",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    fun togglePlayPause() {
        if (hasErrored || loadedVideoId == null) return
        if (isPlaying) pause() else resume()
    }

    fun toggleMute() {
        if (hasErrored || loadedVideoId == null) return
        isMuted = !isMuted
        val fn = if (isMuted) "mute" else "unMute"
        webView.evaluateJavascript("window.__trailerCmd && window.__trailerCmd('$fn');", null)
        updateIcons()
    }

    fun pause() {
        if (hasErrored || loadedVideoId == null) return
        isPlaying = false
        webView.evaluateJavascript("window.__trailerCmd && window.__trailerCmd('pauseVideo');", null)
        updateIcons()
    }

    fun resume() {
        if (hasErrored || loadedVideoId == null) return
        isPlaying = true
        webView.evaluateJavascript("window.__trailerCmd && window.__trailerCmd('playVideo');", null)
        updateIcons()
    }

    fun hideAndFallback() {
        loadedVideoId = null
        controlsContainer.isVisible = false
        webView.isVisible = false
        runCatching { webView.loadUrl("about:blank") }
        onFallbackToImageBanner()
    }

    fun release() {
        loadedVideoId = null
        controlsContainer.isVisible = false
        webView.isVisible = false
        runCatching {
            webView.removeJavascriptInterface("TrailerBridge")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    private fun updateIcons() {
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
        )
        muteButton.setImageResource(
            if (isMuted) R.drawable.ic_round_volume_off_24 else R.drawable.ic_round_volume_up_24
        )
    }

    private inner class TrailerBridge {
        @JavascriptInterface
        fun onReady() {
            webView.post {
                if (hasErrored) return@post
                webView.isVisible = true
                controlsContainer.isVisible = true
                onTrailerReadyUi()
            }
        }

        @JavascriptInterface
        fun onStateChanged(state: Int) {
            webView.post {
                when (state) {
                    1 -> isPlaying = true
                    2 -> isPlaying = false
                    0 -> {
                        // Seamless Netflix-style loop
                        webView.evaluateJavascript("window.__trailerCmd && window.__trailerCmd('playVideo');", null)
                        isPlaying = true
                    }
                }
                updateIcons()
            }
        }

        @JavascriptInterface
        fun onError() {
            webView.post {
                hasErrored = true
                hideAndFallback()
            }
        }
    }

    private fun buildPlayerHtml(videoId: String): String {
        val safeId = videoId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
              <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
                .wrap { position: relative; width: 100%; height: 100%; overflow: hidden; }
                #player {
                  position: absolute;
                  top: 50%; left: 50%;
                  width: 177.78vh; height: 100vh;
                  min-width: 100%; min-height: 56.25vw;
                  transform: translate(-50%, -50%) scale(1.12);
                  pointer-events: none;
                }
              </style>
            </head>
            <body>
              <div class="wrap"><div id="player"></div></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                var player = null;
                window.__trailerCmd = function(fn) {
                  try { if (player && typeof player[fn] === 'function') player[fn](); } catch (e) {}
                };
                function onYouTubeIframeAPIReady() {
                  player = new YT.Player('player', {
                    host: 'https://www.youtube-nocookie.com',
                    videoId: '$safeId',
                    playerVars: {
                      autoplay: 1,
                      mute: 1,
                      controls: 0,
                      playsinline: 1,
                      rel: 0,
                      modestbranding: 1,
                      iv_load_policy: 3,
                      loop: 1,
                      playlist: '$safeId'
                    },
                    events: {
                      onReady: function(e) {
                        try {
                          e.target.mute();
                          e.target.playVideo();
                          TrailerBridge.onReady();
                        } catch (err) { TrailerBridge.onError(); }
                      },
                      onStateChange: function(e) {
                        TrailerBridge.onStateChanged(e.data);
                      },
                      onError: function() {
                        TrailerBridge.onError();
                      }
                    }
                  });
                }
                setTimeout(function() {
                  if (!player) TrailerBridge.onError();
                }, 10000);
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        fun extractYoutubeVideoId(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (!trimmed.contains("/") && !trimmed.contains("?") && !trimmed.contains("=")) {
                return trimmed.takeIf { it.matches(Regex("^[a-zA-Z0-9_-]{6,20}$")) }
            }
            val regex = Regex("""(?:youtube\.com/(?:watch\?v=|embed/|shorts/)|youtu\.be/)([a-zA-Z0-9_-]{6,20})""")
            return regex.find(trimmed)?.groupValues?.getOrNull(1)
        }
    }
}
