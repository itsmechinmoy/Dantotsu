package ani.dantotsu.media.anime.selector

/**
 * How the app will deliver bytes to the player.
 *
 * Decoupled from presentation: a debrid HTTP result is
 * `RELEASE` presentation + `DIRECT_HTTP` transport. A magnet result is
 * `RELEASE` presentation + `TORRENT` transport.
 *
 * Decision inputs: URL scheme + `VideoType` ONLY.
 * Must never consult quality text.
 */
enum class SourceTransport {
    /** magnet: or .torrent URL -> built-in torrent backend. */
    TORRENT,

    /** M3U8 HLS manifest. */
    HLS,

    /** MPD DASH manifest. */
    DASH,

    /** Direct HTTP progressive file (mp4, mkv, etc.). */
    DIRECT_HTTP,

    /** Anything else (rare; offline; unknown). */
    OTHER,
}