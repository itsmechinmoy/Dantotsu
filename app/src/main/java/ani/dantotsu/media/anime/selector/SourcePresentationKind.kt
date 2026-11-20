package ani.dantotsu.media.anime.selector

/**
 * How a picker row should be visually formatted.
 *
 * Decoupled from playback transport: a single `SourceTransport` (e.g. DIRECT_HTTP)
 * can carry either `RELEASE` or `STREAM` presentation, and a single presentation
 * (e.g. `RELEASE`) can be carried by any transport (e.g. magnet / TORRENT or
 * debrid HTTP / DIRECT_HTTP).
 *
 * Decision input: extension-supplied `quality` text shape ONLY.
 * Must never consult URL or transport signals.
 */
enum class SourcePresentationKind {
    /** Multi-line / marker-rich quality text that reads as a release. */
    RELEASE,

    /** Single-line host/track/resolution label. */
    STREAM,
}