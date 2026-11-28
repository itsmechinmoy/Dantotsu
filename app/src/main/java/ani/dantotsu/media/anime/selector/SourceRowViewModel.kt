package ani.dantotsu.media.anime.selector

/**
 * Icon identifiers for picker meta chips. Each maps to a drawable in
 * `res/drawable/`. Picker-local enum; no persistence/identity role.
 */
enum class SourceMetaIcon {
    SEEDERS,
    SIZE,
    INDEXER,
    HDR,
    /** Neutral text-only chip used for stream-row Sub/Dub track text. */
    TRACK,
}

/**
 * One chip rendered into the meta line of a picker row.
 *
 * @param text display text for the chip (e.g. "545 seeders", "1.48 GB", "IndexA", "HDR").
 */
data class SourceMetaChip(
    val icon: SourceMetaIcon,
    val text: String,
)

/**
 * One detail row in the expandable Details panel.
 *
 * @param label optional left-side label (e.g. "Provider / indexer"). May be null
 *              for raw value-only rows.
 * @param value value text. May be the empty string when a field is "not supplied".
 * @param isUnknownPlaceholder true when [value] is a "not supplied by extension"
 *              placeholder; rendered in muted style.
 */
data class SourceDetailLine(
    val label: String?,
    val value: String,
    val isUnknownPlaceholder: Boolean = false,
)

/**
 * One right-aligned pill rendered on the picker card (e.g. "1080p", "HEVC",
 * "10-bit", "AAC", "Dual Audio", "ENG"). Pills are color-tinted at render
 * time using theme tokens; this data class only carries the label string and
 * the pill's kind for the adapter to map to a drawable / tint.
 */
data class SourceRowPill(
    val text: String,
    val kind: SourcePillKind,
)

enum class SourcePillKind {
    QUALITY,    // e.g. "1080p", "720p", "2160p"
    CODEC,      // e.g. "HEVC", "AVC", "AV1"
    BIT_DEPTH,  // e.g. "10-bit", "8-bit"
    AUDIO_FMT,  // e.g. "AAC", "FLAC", "DDP2.0"
    SUB_LANG,   // flags ("🇬🇧") or fallback text ("Multi-subs")
    AUDIO_MODE, // e.g. "Sub", "Dub", "Dual Audio"
    HDR,        // plain "HDR"
    HDR10,      // "HDR10"
    HDR10_PLUS, // "HDR10+"
    DOLBY_VISION, // "Dolby Vision"
    BATCH,      // "Batch" pack marker (P3, last)
    CENSOR,     // "Uncensored" / "Censored"
    REVISION,   // "REPACK" / "v2"
    SERVICE,    // streaming source, e.g. "Amazon", "ADN", "Bilibili"
}

/**
 * Display-only view-model for a single picker row.
 *
 * NOT a persistence/identity carrier. Selection and default-storage continue
 * to use the existing `VideoServer.name` and `bindingAdapterPosition` contract
 * via the existing click handler. This model is rebuilt every bind and never
 * written to disk.
 *
 * @param displayLabel verbatim `VideoServer.name`. Used only for accessibility
 *              descriptions and error messages. NOT used to drive any
 *              persistence write.
 * @param presentation how the row should be visually formatted.
 * @param transport how the app will deliver bytes; surfaced only in Details.
 * @param header large release-group header (null when the candidate is STREAM
 *              or when the title carries no group token).
 * @param subtitle single-line release name; ellipsized in the collapsed
 *              row. Null for STREAM rows. Full name lives in [details].
 * @param pills right-aligned pills rendered at the end of the header row.
 *              Quality pill (e.g. "1080p") is always first when present.
 *              Complete uncapped model; the layout constrains display.
 * @param criticalPills P0 pills the layout MUST keep visible
 *              (resolution + subtitle/language). Rendered in fixed
 *              wrap_content slots ahead of the flexible strip, so they
 *              can never be displaced by optional pills.
 * @param optionalPills remaining pills in priority order, rendered in
 *              the flexible single-line strip (clipped, never wrapped).
 * @param meta meta chips rendered on the third line of the collapsed row
 *              (e.g. "545 seeders · 1.48 GB · IndexA").
 * @param details lines rendered in the expandable Details panel.
 */
data class SourceRowViewModel(
    val displayLabel: String,
    val presentation: SourcePresentationKind,
    val transport: SourceTransport,
    val header: String?,
    val cleanSubtitle: String?,
    val subtitle: String?,
    val pills: List<SourceRowPill>,
    val criticalPills: List<SourceRowPill>,
    val optionalPills: List<SourceRowPill>,
    val audioSummary: String?,
    val subtitleSummary: String?,
    val subtitleLanguages: List<String> = emptyList(),
    val subtitleFlags: List<String> = emptyList(),
    val audioLanguages: List<String> = emptyList(),
    val audioFlags: List<String> = emptyList(),
    val meta: List<SourceMetaChip>,
    val details: List<SourceDetailLine>,
    /** Tracker / provenance value (e.g. IndexA, 2448x). */
    val tracker: String? = null,
)
/**
 * Display label of a normalized codec. The stored value stays "AVC" (title stripping,
 * title stripping keys on it); people know it as H.264.
 */
object CodecLabel {
    fun of(codec: String): String = if (codec.equals("AVC", ignoreCase = true)) "H.264" else codec
}
