package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoType

/**
 * Builds [SourceRowViewModel] instances from raw extension output.
 *
 * Pure: takes strings + format, returns the view-model. No I/O, no Android
 * types. Selection contract is not touched.
 */
object SourceRowSummarizer {

    /**
     * Language code/name -> flag glyph mapping (FLAGS, not
     * language-code text chips).
     *
     * Only unambiguous ISO 639 mappings are listed. Unknown codes,
     * stateless languages and `Multi-subs` (not a language) stay text —
     * a wrong flag is worse than a text chip. Audio Dual/Dub pills
     * stay text (track semantics, not subtitle languages).
     */
    object LanguageFlagMapper {
        private val CODE_TO_FLAG: Map<String, String> = mapOf(
            "EN" to "🇬🇧", "ENG" to "🇬🇧",
            "JA" to "🇯🇵", "JP" to "🇯🇵", "JPN" to "🇯🇵",
            "FR" to "🇫🇷", "FRE" to "🇫🇷", "FRA" to "🇫🇷",
            "DE" to "🇩🇪", "GER" to "🇩🇪", "DEU" to "🇩🇪",
            "IT" to "🇮🇹", "ITA" to "🇮🇹",
            "ES" to "🇪🇸", "SPA" to "🇪🇸", "ESP" to "🇪🇸",
            "PT" to "🇵🇹", "POR" to "🇵🇹",
            "BR" to "🇧🇷", "PT-BR" to "🇧🇷", "POR-BR" to "🇧🇷",
            "MX" to "🇲🇽", "ES-LA" to "🇲🇽", "SPA-LA" to "🇲🇽", "LATINO" to "🇲🇽",
            "RU" to "🇷🇺", "RUS" to "🇷🇺",
            "ZH" to "🇨🇳", "CHI" to "🇨🇳", "ZHO" to "🇨🇳", "CHS" to "🇨🇳",
            "CHT" to "🇹🇼",
            "KO" to "🇰🇷", "KOR" to "🇰🇷",
            "HI" to "🇮🇳", "HIN" to "🇮🇳", "TAM" to "🇮🇳", "TEL" to "🇮🇳",
            "KAN" to "🇮🇳", "MAR" to "🇮🇳",
            "PL" to "🇵🇱", "POL" to "🇵🇱",
            "TR" to "🇹🇷", "TUR" to "🇹🇷",
            "UK" to "🇺🇦", "UKR" to "🇺🇦",
            "VI" to "🇻🇳", "VIE" to "🇻🇳",
            "TH" to "🇹🇭", "THA" to "🇹🇭",
            "ID" to "🇮🇩",
            "NL" to "🇳🇱",
            "SV" to "🇸🇪", "SWE" to "🇸🇪",
            "NO" to "🇳🇴", "NOR" to "🇳🇴",
            "DA" to "🇩🇰", "DAN" to "🇩🇰",
            "FI" to "🇫🇮", "FIN" to "🇫🇮",
            "EL" to "🇬🇷", "GRE" to "🇬🇷", "ELL" to "🇬🇷",
            "HE" to "🇮🇱", "HEB" to "🇮🇱",
            "AR" to "🇸🇦", "ARA" to "🇸🇦",
            "RO" to "🇷🇴", "RUM" to "🇷🇴",
            "HU" to "🇭🇺", "HUN" to "🇭🇺",
            "CS" to "🇨🇿", "CZE" to "🇨🇿",
            "SK" to "🇸🇰",
            "HR" to "🇭🇷", "HRV" to "🇭🇷",
            "SR" to "🇷🇸", "SRP" to "🇷🇸",
            "BG" to "🇧🇬", "BUL" to "🇧🇬",
            "YO" to "🇳🇬", "YOR" to "🇳🇬",
        )

        /**
         * Map one language token to its flag, or null when unknown.
         * Lookup is case-insensitive; surrounding whitespace ignored.
         */
        fun flagFor(token: String): String? {
            return CODE_TO_FLAG[token.trim().uppercase()]
        }

        /**
         * Map a `+`-joined language summary (e.g. `ENG+POR-BR`) to a
         * space-joined flag string (`🇬🇧 🇧🇷`). Returns null unless EVERY
         * part maps — mixed/unknown input keeps its original text so no
         * wrong flag is ever shown. A hyphenated part with no whole
         * mapping (e.g. `ESP-ENG`) maps piece-wise when both sides map.
         */
        fun mapJoined(summary: String): String? {
            val parts = summary.split("+").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val mapped = parts.map { part ->
                flagFor(part) ?: mapHyphenated(part) ?: mapHyphenatedSub(part) ?: return null
            }
            return mapped.joinToString(" ")
        }

        private fun mapHyphenated(part: String): String? {
            val sides = part.split("-").map { it.trim() }.filter { it.isNotEmpty() }
            if (sides.size != 2) return null
            // Whole-token mappings (POR-BR, PT-BR, ES-LA, ...) were
            // already tried by the caller; only split when both sides
            // are independently known languages.
            return sides.map { flagFor(it) ?: return null }.joinToString(" ")
        }

        private fun mapHyphenatedSub(part: String): String? {
            val sides = part.split("-").map { it.trim() }.filter { it.isNotEmpty() }
            if (sides.size != 2) return null
            if (!sides[1].matches(Regex("""(?i)^(subs?|subbed|subtitles?)$"""))) return null
            return flagFor(sides[0])
        }
    }

    fun build(
        displayLabel: String,
        qualityText: String,
        format: VideoType?,
        url: String,
        fileName: String? = null,
    ): SourceRowViewModel {
        val presentation = SourceRowClassifier.classifyPresentation(qualityText)
        val formatName = format?.name ?: ""
        val transport = SourceRowClassifier.classifyTransport(
            url = url,
            formatIsContainer = format == VideoType.CONTAINER,
            formatName = formatName,
        )
        return when (presentation) {
            SourcePresentationKind.RELEASE -> buildRelease(displayLabel, transport, url, fileName)
            SourcePresentationKind.STREAM -> buildStream(displayLabel, qualityText, transport, formatName)
        }
    }

    fun build(
        displayLabel: String,
        qualityText: String,
        formatName: String?,
        url: String,
        fileName: String? = null,
    ): SourceRowViewModel {
        val presentation = SourceRowClassifier.classifyPresentation(qualityText)
        val effectiveName = formatName ?: ""
        val transport = SourceRowClassifier.classifyTransport(
            url = url,
            formatIsContainer = effectiveName.equals("CONTAINER", ignoreCase = true),
            formatName = effectiveName,
        )
        return when (presentation) {
            SourcePresentationKind.RELEASE -> buildRelease(displayLabel, transport, url, fileName)
            SourcePresentationKind.STREAM -> buildStream(displayLabel, qualityText, transport, effectiveName)
        }
    }

    private fun buildRelease(
        displayLabel: String,
        transport: SourceTransport,
        url: String,
        fileName: String? = null,
    ): SourceRowViewModel {
        val fields = SourceRowClassifier.parseRelease(displayLabel, fileName)
        val pills = buildPills(fields)
        val meta = buildReleaseMeta(fields)
        val details = buildReleaseDetails(fields, transport)
        // Build a UI-only clean title that removes tokens already shown
        // elsewhere (group, resolution, codec, bit depth, HDR variant,
        // audio duplicates, batch markers, source service, checksums,
        // container). The exact raw release name is preserved in
        // details for identity.
        val cleanTitle = SourceRowClassifier.cleanDisplayTitle(
            releaseName = fields.releaseName,
            releaseGroup = fields.releaseGroup,
            resolution = fields.resolution,
            codec = fields.codec,
            bitDepth = fields.bitDepth,
            sourceService = fields.sourceService,
            hdrVariant = fields.hdrVariant,
            audioSummary = fields.audioSummary,
            batch = fields.batch,
            audioFormat = fields.audioFormat,
            subtitleSummary = fields.subtitleSummary,
            subtitleLanguages = fields.subtitleLanguages,
            sourceClass = fields.sourceClass,
        )
        // when no release group exists, the clean title becomes the
        // visual primary label (never invent a group from provenance).
        // The binding hides the title line when it equals the header.
        val header = fields.releaseGroup ?: cleanTitle.takeIf { it.isNotBlank() }
        // Critical/optional seam: P0 resolution + subtitle/language
        // pills are split out for fixed layout slots; everything else
        // flows to the flexible strip in priority order.
        val criticalPills = pills.filter {
            it.kind == SourcePillKind.QUALITY || it.kind == SourcePillKind.SUB_LANG
        }
        val optionalPills = pills.filter {
            it.kind != SourcePillKind.QUALITY && it.kind != SourcePillKind.SUB_LANG
        }
        return SourceRowViewModel(
            displayLabel = displayLabel,
            presentation = SourcePresentationKind.RELEASE,
            transport = transport,
            header = header,
            cleanSubtitle = cleanTitle,
            subtitle = fields.releaseName,
            pills = pills,
            criticalPills = criticalPills,
            optionalPills = optionalPills,
            audioSummary = fields.audioSummary,
            subtitleSummary = fields.subtitleSummary,
            subtitleLanguages = fields.subtitleLanguages,
            subtitleFlags = fields.subtitleFlags,
            audioLanguages = fields.audioLanguages,
            audioFlags = fields.audioFlags,
            meta = meta,
            details = details,
            tracker = fields.tracker,
        )
    }

    /**
     * Build the right-aligned pill list, deduplicated and priority-ordered.
     *
     * Priority (explicit; subtitle/language visibility outranks
     * codec/bit-depth):
     *   1. resolution (always first)
     *   2. subtitle/language visibility (flags; never dropped)
     *   3. HDR variant (HDR10+ > HDR10 > Dolby Vision > plain HDR)
     *   4. audio mode (Dual / Dub)
     *   5. codec + bit-depth as space permits
     *   6. audio format
     *   7. batch marker (P3, last)
     *
     * There is NO magic take(N) information cap: the full
     * deterministic list is returned and the UI constrains the row
     * (single clipped line at header height). Language/subtitle data
     * therefore can never be cut by codec pills.
     */
    private fun buildPills(fields: SourceRowClassifier.ReleaseFields): List<SourceRowPill> {
        val out = mutableListOf<SourceRowPill>()

        // 1. Resolution (with 2160p -> 4K display alias).
        fields.resolution?.let { res ->
            val alias = SourceRowClassifier.resolutionDisplayAlias(res) ?: res
            val label = if (fields.hdr) "$alias HDR" else alias
            out += SourceRowPill(label, SourcePillKind.QUALITY)
        }

        // 2. Subtitle/language visibility (flags; mapped from codes).
        //    Emitted before codec so dense rows never drop it.
        if (fields.subtitleFlags.isNotEmpty() || fields.subtitleLanguages.isNotEmpty() ||
            fields.subtitleSummary != null
        ) {
            val label = buildSubtitlePillLabel(fields)
            out += SourceRowPill(label, SourcePillKind.SUB_LANG)
        }

        // 3. HDR variant (one pill). The plain "HDR" pill is suppressed
        // when the resolution pill already says "1080p HDR" so we do not
        // duplicate the signal. The precise variants (HDR10, HDR10+,
        // Dolby Vision) are always emitted.
        fields.hdrVariant?.let { variant ->
            when (variant) {
                "HDR10+" -> out += SourceRowPill("HDR10+", SourcePillKind.HDR10_PLUS)
                "HDR10" -> out += SourceRowPill("HDR10", SourcePillKind.HDR10)
                "Dolby Vision" -> out += SourceRowPill("DV", SourcePillKind.DOLBY_VISION)
                "HDR" -> {
                    if (fields.resolution == null || !fields.hdr) {
                        out += SourceRowPill("HDR", SourcePillKind.HDR)
                    }
                }
            }
        }
        // Bit depth is P2 with codec (emitted only when distinct from
        // HDR); both read after audio mode.
        val bitDepthPill = if (fields.hdrVariant == null) {
            fields.bitDepth?.let { SourceRowPill(it, SourcePillKind.BIT_DEPTH) }
        } else {
            null
        }

        // 4. Audio mode (Dual / Dub track kind).
        fields.audioSummary?.let {
            // Skip if the audio summary IS the same as the audio format
            // (e.g. "AAC 2.0" is a format, not a mode).
            if (!it.equals(fields.audioFormat, ignoreCase = true)) {
                out += SourceRowPill(it, SourcePillKind.AUDIO_MODE)
            }
        }

        // 5. Codec (one pill), then bit depth.
        fields.codec?.let { out += SourceRowPill(CodecLabel.of(it), SourcePillKind.CODEC) }
        bitDepthPill?.let { out += it }

        // 6. Audio format.
        fields.audioFormat?.let { out += SourceRowPill(it, SourcePillKind.AUDIO_FMT) }

        // 7. What tells otherwise identical releases apart (one group often
        //    ships several of the same episode): censorship, re-release,
        //    original-video and the streaming source.
        fields.censorship?.let { out += SourceRowPill(it, SourcePillKind.CENSOR) }
        fields.revision?.let { out += SourceRowPill(it, SourcePillKind.REVISION) }
        if (fields.originalVideo) out += SourceRowPill("OV", SourcePillKind.SERVICE)
        fields.sourceService?.let { out += SourceRowPill(it, SourcePillKind.SERVICE) }
        // 8. Batch marker (P3, last).
        if (fields.batch) {
            out += SourceRowPill("Batch", SourcePillKind.BATCH)
        }

        return out
    }

    private fun buildSubtitlePillLabel(fields: SourceRowClassifier.ReleaseFields): String {
        // Prefer flag glyphs when present (keep the original glyph). Fall
        // back to the compact summary otherwise, mapping language codes to
        // flags; unmapped input keeps its text, never a wrong flag.
        if (fields.subtitleFlags.isNotEmpty()) {
            return if (fields.subtitleFlags.size <= 3) {
                fields.subtitleFlags.joinToString(" ")
            } else {
                "${fields.subtitleFlags.take(3).joinToString(" ")} +${fields.subtitleFlags.size - 3}"
            }
        }
        val summary = fields.subtitleSummary ?: "Multi-subs"
        return LanguageFlagMapper.mapJoined(summary) ?: summary
    }

    /**
     * Build the third-line meta (seeders · size · indexer). The seeder
     * number is the most important signal; the indexer (e.g. IndexA) is
     * intentionally demoted to secondary metadata.
     */
    private fun buildReleaseMeta(fields: SourceRowClassifier.ReleaseFields): List<SourceMetaChip> {
        val out = mutableListOf<SourceMetaChip>()
        fields.seeders?.let { out += SourceMetaChip(SourceMetaIcon.SEEDERS, "$it seeders") }
        fields.sizeText?.let { out += SourceMetaChip(SourceMetaIcon.SIZE, it) }
        fields.indexer?.let { out += SourceMetaChip(SourceMetaIcon.INDEXER, it) }
        return out
    }

    private fun buildStream(
        displayLabel: String,
        qualityText: String,
        transport: SourceTransport,
        formatName: String,
    ): SourceRowViewModel {
        val firstLine = qualityText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: qualityText.trim()
        val splitParts = firstLine.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        val host = splitParts.firstOrNull() ?: firstLine
        val resolution = splitParts.lastOrNull()
            ?.takeIf { SourceRowClassifier.parseResolution(it) != null }
            ?.let { SourceRowClassifier.parseResolution(it) }
        val trackKind = splitParts.getOrNull(1)?.takeIf { it in setOf("Sub", "Dub", "Dual") }
        val audioSummary = trackKind
        val pills = mutableListOf<SourceRowPill>()
        resolution?.let { pills += SourceRowPill(it, SourcePillKind.QUALITY) }
        trackKind?.let { pills += SourceRowPill(it, SourcePillKind.AUDIO_MODE) }
        val meta = if (trackKind != null) listOf(SourceMetaChip(SourceMetaIcon.TRACK, trackKind))
        else emptyList()
        val details = buildStreamDetails(host, resolution, trackKind, transport, formatName, qualityText)
        return SourceRowViewModel(
            displayLabel = displayLabel,
            presentation = SourcePresentationKind.STREAM,
            transport = transport,
            header = host,
            cleanSubtitle = null,
            subtitle = null,
            pills = pills,
            // STREAM inline card renders pills itself; no header
            // critical/optional seam applies (empty by construction).
            criticalPills = emptyList(),
            optionalPills = emptyList(),
            audioSummary = audioSummary,
            subtitleSummary = null,
            subtitleLanguages = emptyList(),
            subtitleFlags = emptyList(),
            audioLanguages = emptyList(),
            audioFlags = emptyList(),
            meta = meta,
            details = details,
            tracker = null,
        )
    }

    private fun buildReleaseDetails(
        fields: SourceRowClassifier.ReleaseFields,
        transport: SourceTransport,
    ): List<SourceDetailLine> {
        val out = mutableListOf<SourceDetailLine>()
        // Semantic order: structural fields first, raw title at the end.
        // Hide unknown rows; never repeat "Not supplied by extension".
        fields.releaseGroup?.let {
            out += SourceDetailLine(label = "Release group", value = it)
        }
        fields.tracker?.let {
            out += SourceDetailLine(label = "Tracker", value = it)
        }
        fields.resolution?.let { res ->
            // Display: 2160p -> "4K (2160p)"; otherwise the raw value.
            val display = if (res == "2160p") "4K (2160p)" else res
            out += SourceDetailLine(label = "Resolution", value = display)
        }
        fields.codec?.let {
            val parts = listOfNotNull(it, fields.bitDepth)
            out += SourceDetailLine(label = "Codec", value = parts.joinToString(" "))
        }
        if (fields.hdrVariant != null) {
            // never echo label as value (`HDR: HDR`).
            // A plain-HDR signal reads as a presence marker; precise
            // variants keep their exact value.
            val hdrValue = if (fields.hdrVariant == "HDR") "Yes" else fields.hdrVariant
            out += SourceDetailLine(label = "HDR", value = hdrValue)
        } else if (fields.hdr) {
            out += SourceDetailLine(label = "HDR", value = "Yes")
        }
        fields.audioFormat?.let {
            out += SourceDetailLine(label = "Audio format", value = it)
        }
        fields.audioSummary?.let { summary ->
            // Skip if the summary is the same as the audio format
            // (e.g. "AAC 2.0" appears as both).
            if (!summary.equals(fields.audioFormat, ignoreCase = true)) {
                out += SourceDetailLine(label = "Audio", value = summary)
            }
        }
        if (fields.audioLanguages.isNotEmpty()) {
            out += SourceDetailLine(
                label = "Audio languages",
                value = fields.audioLanguages.joinToString(" + "),
            )
        }
        if (fields.subtitleFlags.isNotEmpty()) {
            out += SourceDetailLine(
                label = "Subtitles",
                value = fields.subtitleFlags.joinToString(" "),
            )
        } else if (fields.subtitleLanguages.isNotEmpty()) {
            out += SourceDetailLine(
                label = "Subtitle languages",
                value = fields.subtitleLanguages.joinToString(" + "),
            )
        }
        fields.sourceService?.let {
            out += SourceDetailLine(label = "Source service", value = it)
        }
        fields.sourceClass?.let {
            // user-facing label is `Source type`
            // (parser/model concept stays `sourceClass`).
            out += SourceDetailLine(label = "Source type", value = it)
        }
        out += SourceDetailLine(label = "Transport", value = transportLabelString(transport))
        out += SourceDetailLine(
            label = "Raw release name",
            value = fields.releaseName,
            isUnknownPlaceholder = false,
        )
        return out
    }

    private fun buildStreamDetails(
        host: String,
        resolution: String?,
        trackKind: String?,
        transport: SourceTransport,
        formatName: String,
        rawLabel: String,
    ): List<SourceDetailLine> {
        val out = mutableListOf<SourceDetailLine>()
        out += SourceDetailLine(label = "Host", value = host)
        resolution?.let { out += SourceDetailLine(label = "Resolution", value = it) }
        trackKind?.let { out += SourceDetailLine(label = "Track", value = it) }
        out += SourceDetailLine(label = "Transport", value = transportLabelString(transport))
        out += SourceDetailLine(label = "Raw label", value = rawLabel)
        return out
    }

    private fun transportLabelString(transport: SourceTransport): String = when (transport) {
        SourceTransport.TORRENT -> "Torrent"
        SourceTransport.HLS -> "HLS"
        SourceTransport.DASH -> "DASH"
        SourceTransport.DIRECT_HTTP -> "Direct"
        SourceTransport.OTHER -> "Other"
    }
}