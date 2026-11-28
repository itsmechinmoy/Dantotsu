package ani.dantotsu.media.anime.selector

/**
 * Pure functions that classify a candidate's quality text + URL + format into
 * presentation kind, transport, and (for RELEASE rows) parsed field values.
 *
 * No Android dependencies. No I/O. No persistence writes. No class state.
 *
 * All methods are stateless and pure. They are the single source of truth
 * for how the picker translates raw extension output into picker UI fields.
 *
 * Rules:
 *
 *   - Release group is the FIRST valid bracketed group token in the release
 *     title, NOT the value next to the cog emoji (`⚙`/📂). A quality prefix
 *     (e.g. `4k`, `1080p`, `2160p`) is allowed before the bracket.
 *   - Tracker/provenance is the value next to the `📂` emoji. It is NOT
 *     the release group and never appears in the card header.
 *   - Subtitle vs audio flag separation: only flags with line-local
 *     context (Sub/Subs/Multi-Subs for subtitles; Audio/Dual Audio/Dub for
 *     audio) become classified flags. Uncontextual flags stay unclassified.
 *   - NF / AMZN / CR mean Netflix / Amazon / Crunchyroll source service
 *     and are low-priority metadata, NOT top-row chips.
 *   - `2160p` displays as `4K` in the collapsed card; details show
 *     `4K (2160p)`. Internal value stays `2160p`.
 */
object SourceRowClassifier {

    // ---- Marker / token sets ---------------------------------------------

    private val MARKER_EMOJIS: Set<String> = setOf(
        "📂", "⚙", "💾", "👤", "🎯", "🎬", "🔗",
    )

    /**
     * Bracket tokens that must NEVER be treated as the release group. These
     * are codec/quality/HDR/audio/source-service/etc. markers.
     */
    private val KNOWN_NON_GROUP_BRACKETS: Set<String> = setOf(
        "HDR", "HEVC", "AVC", "H264", "H265", "X264", "X265",
        "AV1", "VP9", "XVID", "DIVX", "H.264", "H.265",
        "10BIT", "8BIT", "10-BIT", "8-BIT", "10BITS", "10 BITS",
        "1080P", "720P", "2160P", "480P", "1440P", "4K", "2K",
        "AAC", "AC3", "DTS", "FLAC", "OPUS", "MP3", "EAC3", "DDP", "DDP2",
        "DDP5", "DD", "ATMOS", "TRUEHD", "MKA", "M4A", "DDP2.0", "DDP5.1", "AAC2.0", "AAC5.1",
        "BATCH", "DUAL AUDIO", "MULTI AUDIO", "COMPLETE", "UNCENSORED",
        "HD", "SD", "HDMA", "HRA",
        "WEB-DL", "WEBDL", "WEBRIP", "BLURAY", "BDRIP", "HDTV", "DVDRIP", "REMUX",
        "AMZN", "NF", "CR", "DSNP", "HULU", "AT-X", "ATX", "WEB.10", "10BITS",
        "MULTISUBS", "MULTI SUBS", "MULTIPLE SUBTITLE", "MULTIPLE SUBTITLES",
        "MULTIPLE AUDIO", "HOURS", "2 HOURS SPECIAL", "SPECIAL",
        "COMPLETA", "COMPLETO", "COMPLET",
        "HDDVD", "PDTV", "AHDTV", "APDTV", "DSR", "ADSR", "MBLURAY",
        "WEB", "TELECINE", "WORKPRINT", "DCP", "MP2", "LPCM", "SDR",
        "REAL", "READNFO", "FINAL", "REMASTERED", "RESTORED",
        "THEATRICAL", "EXTENDED", "UNCUT", "HR",
        "1080P HEVC", "1080P AVC", "1080P X265", "1080P X264",
    )

    /**
     * 2- and 3-letter language codes (ISO 639-1 + 639-2). Used to reject
     * 2-3 letter bracketed tokens as the release group.
     */
    private val KNOWN_LANG_CODES: Set<String> = setOf(
        "AR", "DE", "EN", "ES", "FR", "IT", "JA", "JP", "KO", "PT", "RU", "ZH",
        "BS", "BG", "CA", "CS", "DA", "EL", "EO", "ET", "FA", "FI", "HE", "HI",
        "HR", "HU", "ID", "IS", "KK", "KM", "LT", "LV", "MK", "ML", "MN",
        "MR", "MS", "MY", "NL", "NO", "PL", "RO", "SH", "SI", "SK", "SL", "SR",
        "SV", "TA", "TE", "TH", "TL", "TR", "UK", "UR", "VI", "YI", "YO",
        "ARA", "BAQ", "BUL", "CAT", "CHI", "CZE", "DAN", "DUT", "ELL", "ENG",
        "EST", "FIN", "FRE", "GER", "GRE", "HEB", "HIN", "HRV", "HUN", "ICE",
        "IND", "ITA", "JPN", "KAN", "KOR", "LAV", "LIT", "MAC", "MAL", "MAR",
        "MAY", "MKD", "MNG", "NOR", "PER", "POL", "POR", "RUM", "RUS", "SCC",
        "SCR", "SIN", "SLO", "SLV", "SPA", "SRP", "SWE", "TAM", "TEL", "THA",
        "TIR", "TUR", "UKR", "URD", "VIE", "YID", "YOR", "ZHO",
        "CHT", "CHS", "PT-BR", "ZH-CN", "ZH-TW", "ZH-HK",
    )

    /** Source service aliases that must NOT become release-group tokens. */
    private val SOURCE_SERVICE_ALIASES: Map<String, String> = mapOf(
        "AMZN" to "Amazon",
        "NF" to "Netflix",
        "CR" to "Crunchyroll",
        "DSNP" to "Disney+",
        "HULU" to "Hulu",
        "AT-X" to "AT-X",
        "ATX" to "AT-X",
        "ATVP" to "Apple TV+",
        // iQiyi emits raw
        // `[IQIYI ...]` blocks. Additive alias only; the delimited
        // clean-title strip reuses the existing alias-key path.
        "IQIYI" to "iQIYI",
        // Anime simulcast services seen in anime torrent titles
        // (ADN/BILI/AMZN/CR variants of one release).
        "ADN" to "ADN",
        "BILI" to "Bilibili",
        "B-GLOBAL" to "Bilibili",
        "HIDI" to "HIDIVE",
        "HIDIVE" to "HIDIVE",
        "ABEMA" to "ABEMA",
        "UNEXT" to "U-NEXT",
        "U-NEXT" to "U-NEXT",
        "DSNY" to "Disney+",
    )

    // ---- Regexes ----------------------------------------------------------

    private val REGEX_RELEASE_FILENAME =
        Regex("""\[.+?]\s*.+?\.(mkv|mp4|avi|webm|ts|m4v|m2ts|mov|flv|rmvb)$""", RegexOption.IGNORE_CASE)

    private val REGEX_RESOLUTION = Regex("""\b(\d{3,4})p\b""")
    private val REGEX_RESOLUTION_WXH =
        Regex("""\b(\d{3,4})\s*[x×]\s*(\d{3,4})\b""")

    private val REGEX_INDEXER = Regex("""📂\s*([^|\n]+)""")

    /**
     * Strict cog value: the literal `⚙️` marker is required, pipe- and
     * newline-terminated. Replaces the old optional-marker cog line for
     * both tracker derivation and the legacy-⚙ group step (the optional
     * form could match an arbitrary line start and is retired).
     */
    private val REGEX_COG_VALUE = Regex("""⚙️\s*([^|\n]+)""")

    private val REGEX_SIZE = Regex("""💾\s*([\d.]+\s*[KMGT]?B)""", RegexOption.IGNORE_CASE)

    private val REGEX_SEEDERS = Regex("""👤\s*(\d+)""")

    private val REGEX_AUDIO = Regex("""🎬\s*([^\n(]+?)(?:\s*\(|\n|${'$'})""", RegexOption.MULTILINE)

    private val REGEX_HDR = Regex("""(?i)\b(HDR10\+|HDR10|HDR|DOLBY\s*VISION|DV)\b""")
    private val REGEX_HDR_SIMPLE = Regex("""(?i)[\[(]HDR[)\]]""")
    private val REGEX_HDR_PLUS = Regex("""(?i)[\[(]HDR10\+[)\]]""")
    private val REGEX_HDR10 = Regex("""(?i)[\[(]HDR10[)\]]""")
    private val REGEX_DV = Regex("""(?i)[\[(](DOLBY\s*VISION|DV)[)\]]""")
    private val REGEX_HI10P = Regex("""(?i)[\[(]HI10P[)\]]""")

    /**
     * Bare/dot-delimited HDR grammar (TRaSH
     * cross-check): `EAC3.Atmos.5.1.DV.HDR10Plus.h265` must resolve the
     * same variants as the bracketed forms. Delimiters are start/end,
     * whitespace, dots, hyphens, underscores and brackets. The
     * trailing lookahead (never a letter/digit/`+`) keeps substrings
     * inside ordinary words false (`ADV`, `DVDRip`, `HDRip`).
     */
    private val HDR_DELIM = """(?:^|[\s.\-_\[(])"""
    private val HDR_END = """(?=$|[\s.\-_\])])"""
    private val REGEX_HDR10P_BARE = Regex(HDR_DELIM + """HDR10\+""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_HDR10PLUS_BARE =
        Regex(HDR_DELIM + """HDR10(?:PLUS|P)""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_HDR10_BARE =
        Regex(HDR_DELIM + """HDR10(?!\+)""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_DV_BARE =
        Regex(HDR_DELIM + """DV""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_DOLBY_VISION_BARE = Regex(
        HDR_DELIM + """DOLBY[\s.\-_]*VISION""" + HDR_END,
        RegexOption.IGNORE_CASE,
    )
    private val REGEX_HDR_BARE =
        Regex(HDR_DELIM + """HDR(?![\d+])""" + HDR_END, RegexOption.IGNORE_CASE)

    private val REGEX_BIT_DEPTH = Regex("""(?i)\b(10[- ]?BITS?|10[- ]?BIT|8[- ]?BITS?|8[- ]?BIT|HI10P)\b""")

    private val REGEX_CODEC_BRACKETED =
        Regex("""[\[(](HEVC\s*10[- ]?BIT|HEVC|H\.?264|H\.?265|AVC|AV1|VP9|X\.?264|X\.?265|XVID|DIVX)[\])]""",
            RegexOption.IGNORE_CASE)
    private val REGEX_CODEC_BARE =
        Regex("""(?i)\b(x\.?264|x\.?265|hevc|h\.?264|h\.?265|avc|av1|vp9|xvid|divx)\b""")

    // `DD+` lives outside the trailing `\b`: a
    // `\b` after `+` can never match, so bare `DD+` uses a lookahead
    // end instead (end of string, separators, or a media-extension dot
    // such as `DD+.mkv`). Plain `PCM` is a first-class format like `LPCM`.
    // Dolby is explicit full-phrase families only (v2.2 Fix C): the old
    // generic `DOLBY...` catch-all mislabeled `Dolby Vision` as audio
    // and reduced `Dolby Digital Plus 2.0` to `DOLBY DIGITAL`. Bare
    // `Dolby` alone matches nothing (Vision/Atmos/Digital?).
    // The DTS tail repeats a bounded `separator + known token` group
    //: `DTS-HD MA 5.1` keeps `MA` and
    // channels, while `DTS-HD.X265-IQHD` still stops before codec/group
    // text. The lone `X` token carries an end-guard (same lesson as
    // `DD+`: a bare `\b`-style boundary fails at dots, so extension
    // dots like `DTS:X.mkv` are explicitly allowed while `.X265`
    // is not).
    private val AUDIO_FORMAT_REGEX = Regex(
        """(?i)\b(DDP(?:[\s._\-]*\d+\.?\d*)?|DTS((?:[\s\-.:]+(?:HDMA|HRA|HD|MA|HR|X(?=$|[\s\-_\])]|\.[a-zA-Z]{2,4}($|[\s\]]))|ES|EX|TRUEHD|ATMOS|\d\.\d))+)?|DOLBY\s+DIGITAL\s+PLUS(?:\s+\d\.\d)?|DOLBY\s+DIGITAL(?!\s*PLUS)(?:\s+\d\.\d)?|DOLBY\s+TRUEHD(?:\s+\d\.\d)?|E-?A-?C-?3(?:[\s._\-]*\d+\.?\d*)?|A-?C-?3(?:[\s._\-]*\d+\.?\d*)?|FLAC(?:[\s._\-]*\d+\.?\d*)?|AAC(?:[\s._\-]*\d+\.?\d*)?|OPUS(?:[\s._\-]*\d+\.?\d*)?|MP3(?:[\s._\-]*\d+\.?\d*)?|MP2|LPCM|PCM(?:[\s._\-]*\d+\.?\d*)?|DD\+[\s._\-]*\d+\.?\d*|DD[\s._\-]*\d+\.?\d*|DD(?![\w+])|TRUEHD(?:[\s._\-]*\d+\.?\d*)?|ATMOS)\b|\bDD\+(?=$|[\s\-_\])]|\.[a-zA-Z]{2,4}$)""",
    )

    private val REGEX_LANGUAGE_TAG =
        Regex("""\[\s*((?:[A-Za-z]{2,3})(?:[-+](?:[A-Za-z0-9]{2,4}))*)\s*]""")

    // Allowed bounded technical prefixes before the first release-group
    // bracket. Anything else stops the prefix scan.
    private val ALLOWED_PREFIX_TOKENS = setOf(
        "4k", "2160p", "1080p", "720p", "480p", "1440p",
    )

    /**
     * Generalized quality-prefix token: uncommon
     * heights such as 800p/528p/540p/360p occur in real provider rows
     * (`800p [GrpBlue]`, `528p [GrplMini]`).
     */
    private val REGEX_QUALITY_PREFIX_TOKEN =
        Regex("""(?i)^(\d{3,4}p|4k|2k|8k)$""")

    private fun isQualityPrefixToken(token: String): Boolean {
        if (token.lowercase() in ALLOWED_PREFIX_TOKENS) return true
        return REGEX_QUALITY_PREFIX_TOKEN.matches(token)
    }

    // Subtitle context markers. Hyphen-joined (`Multi-Subs`) and
    // unseparated (`Multisubs`) forms count as subtitle context, same
    // as the spaced form. Adds the French
    // (`vostfr`), Polish (`napisy`), Portuguese (`legendado`) and
    // Spanish (`subtitulado`) scene markers so flags on those lines
    // classify as subtitle flags.
    private val SUB_CONTEXT_REGEX = Regex(
        """(?i)\b(subs?|subtitles?|multi[\s\-]*subs?|multiple\s*subtitles?|vostfr?|napisy|legendad[oa]s?|subtitulad[oa]s?)\b""",
    )

    // Audio context markers. The 🎬 emoji is the audio-line
    // marker and indicates an audio context. Adds
    // the French (`french`), Spanish (`castellano`, `latino`) and
    // Portuguese (`dublado`) scene markers so flags on those lines
    // classify as audio flags.
    private val AUDIO_CONTEXT_REGEX = Regex(
        """(?i)(\b(audio|dual\s*audio|multi\s*audio|multidubs?|dub|dubbed|french|castellano|latino|dublado?s?)\b|🎬)""",
    )

    // Bare dub/sub track-kind markers in the release title. These fire
    // only when no higher-confidence explicit signal (🎬 audio line /
    // subtitle flags / language codes) already populated the field, and
    // they are matched against the release title only, never against
    // the provider/tracker name.
    private val REGEX_DUAL_WORD = Regex("""(?i)\bdual\b""")
    private val REGEX_MULTI_AUDIO = Regex("""(?i)\bmulti[\s._-]?audio\b""")
    private val REGEX_DUBBED_WORD = Regex("""(?i)\bdubbed\b""")
    private val REGEX_ENG_DUB =
        Regex("""(?i)\beng(?:lish)?[\s._\-]*dubs?\b""")
    private val REGEX_FUNI_DUB = Regex("""(?i)\bfuni(?:mation)?[\s._\-]*dubs?\b""")
    private val REGEX_MULTI_SUBS_WORD =
        Regex("""(?i)\bmulti[\s\-]*subs?\b|\bmultiple\s*subtitles?\b""")

    /**
     * Explicit dual-audio separators (TRaSH
     * Anime Dual Audio grammar): `Dual Audio`, `Dual-Audio`,
     * `Dual_Audio`, `Dual.Audio`. Underscore is a word character, so
     * the plain `\bdual\b` word rule misses `Dual_Audio`; this form
     * does not.
     */
    private val REGEX_DUAL_AUDIO_SEP =
        Regex("""(?i)\bdual[\s._\-]+audio\b""")

    /**
     * Dual-audio language pairs: `JA+EN`,
     * `EN+JA`, `Japanese + English` and reverse forms with common
     * `. _ + & -` separators. At least one side is English; bare
     * space-only adjacency (`JA EN`) is excluded as too ambiguous.
     * Group/provider names alone never trigger this (no pair shape).
     */
    private val REGEX_LANG_PAIR_DUAL = Regex(
        """(?i)\b((ja|jpn?|zh|chi|ko|kor)\s*[.+&\-]\s*(en|eng)|(en|eng)\s*[.+&\-]\s*(ja|jpn?|zh|chi|ko|kor))\b""",
    )
    private val REGEX_LANG_PAIR_WORDS_DUAL = Regex(
        """(?i)\b((japanese|chinese|korean)(?:\s*[.+&\-_]\s*|\s+)(english)|(english)(?:\s*[.+&\-_]\s*|\s+)(japanese|chinese|korean))\b""",
    )

    /**
     * Dub vocabulary (French / Spanish / Portuguese
     * scenes). All word-boundary matched against the release title
     * only, never the provider/tracker name (`PlumbingSubs`,
     * `MicoLeaoDublado` do not match: no boundary inside the word).
     */
    private val REGEX_FRENCH_DUB = Regex("""(?i)\bfrench\b""")
    private val REGEX_CASTELLANO_DUB = Regex("""(?i)\bcastellano\b""")
    private val REGEX_LATINO_DUB = Regex("""(?i)\blatino\b""")
    private val REGEX_DUBLADO_DUB = Regex("""(?i)\bdublado?s?\b""")
    private val REGEX_MULTIDUB = Regex("""(?i)\bmultidubs?\b""")
    private val REGEX_ENGLISH_AUDIO_DUB = Regex("""(?i)\benglish\s+audio\b""")
    private val REGEX_SLASHED_AUDIO_DUAL =
        Regex("""(?i)\b[a-z]{2,4}(/[a-z]{2,4})+\s+audio\b""")

    /**
     * Subtitle vocabulary. Bare `sub(s)` /
     * `subbed` / `subtitled` / `napisy` (PL) / `legendado` (PT) /
     * `subtitulado` (ES) markers map to the existing `Multi-subs`
     * fallback string; `VOSTFR` keeps its precise scene label.
     */
    private val REGEX_VOSTFR = Regex("""(?i)\bvostfr?\b""")
    private val REGEX_SUBS_WORD =
        Regex("""(?i)\bsubtitles?\b|\bsubs?\b|\bsubbed\b""")
    private val REGEX_NAPISY = Regex("""(?i)\bnapisy\b""")
    private val REGEX_LEGENDADO = Regex("""(?i)\blegendad[oa]s?\b""")
    private val REGEX_SUBTITULADO =
        Regex("""(?i)\bsubtitulad[oa]s?\b|\bsubtitulos?\b""")

    /**
     * Neutral language words: `espanol` and the trash-guides full
     * language names resolve through nearby context (sub words ->
     * subs, dub words -> dub), otherwise null. `japanese` is
     * deliberately excluded: for anime it is the original language,
     * not a dub signal.
     */
    private val REGEX_ESPANOL = Regex("""(?i)\bespa[nñ]ol\b""")
    private val REGEX_FULL_LANGUAGE = Regex(
        """(?i)\b(german|italian|russian|portuguese|polish|dutch|korean|chinese|arabic|hindi|turkish)\b""",
    )
    private val REGEX_SUB_CONTEXT_WORD =
        Regex("""(?i)subtitles?|subs?|subbed|vostfr?|napisy|legendad|subtitulad""")
    private val REGEX_DUB_CONTEXT_WORD =
        Regex("""(?i)dubbed|dub\b|dual|audio|doblaje|dublado|castellano|latino""")

    /**
     * Bracket tokens led by an audio/sub marker are content signals,
     * not release groups (e.g. `[DUAL AAC2.0]`, `[Multi-Subs]`, `[Dub]`).
     * Whole-token technical forms (`DUAL AUDIO`, `MULTI SUBS`) are
     * already covered by [KNOWN_NON_GROUP_BRACKETS]; the patterns below
     * cover the hyphen/space/none-joined variants that the fixed list
     * cannot enumerate.
     *
     * Real groups survive: the audio continuation set is bounded, so
     * `Dubs-Grpire`, `SubsPlume`, `DualAudio`, `Subzone`, `Multicolor`
     * and `Dubious` do not match.
     */
    private val REGEX_AUDIO_SUB_WHOLE_BRACKET = Regex(
        """(?i)^(dual|dubs?|dubbed|subs?|subbed|subtitles?|multisubs?|multi-subs|dual-audio)$""",
    )
    private val REGEX_AUDIO_SUB_LED_BRACKET = Regex(
        """(?i)^(dual|dubbed|dub|multi)\b[\s\-]+(audio|aac[\d.]*|ac-?3[\d.]*|dts[\w\-]*|flac[\d.]*|opus[\d.]*|mp3|e-?ac-?3[\d.]*|ddp[\d.]*|truehd|atmos|subs?|subbed|subtitles?)\b""",
    )

    /**
     * Bracket tokens starting with an audio-format word are technical
     * metadata, never groups (`[AC3 5.1 Castellano]`, `[AAC 2.0]`,
     * `[LPCM]`, `[DTS-HD MA]`).
     * Whole-token forms are already denied; this covers the spaced /
     * channeled variants.
     */
    private val REGEX_AUDIO_FORMAT_LED_BRACKET = Regex(
        """(?i)^(aac|ac-?3|dts(\s*hd)?|dts-?hd|dts-?x|e-?ac-?3|ddp|dd\+?|flac|opus|mp3|mp2|pcm|lpcm|truehd|atmos)\b""",
    )

    /**
     * Season / episode / volume / version-shaped bracket tokens are
     * structural markers, never release groups (+
     * trash-guides/scenerules cross-check: `[S01]`, `[S01-04 + OVA]`,
     * `[E01]`, `[S01E01]`, `[S01E01-E02]`, `[S02E01A]`, `[0001-0130]`,
     * `[Cap.101]`, `[01x01]`, `[Part 1]`, `[2020.01.15]`, `[OVA 01]`,
     * `[Specials]`, `[v2]`).
     */
    private val REGEX_SEASON_EPISODE_BRACKET = Regex(
        """(?i)^(?:s\d{1,4}([\-+]\d{1,4})?(\s*\+\s*[a-z]+)?|e\d{1,4}|s\d{1,2}e\d{1,3}([\-+]e?\d{1,3})?[a-z]?|ep?\d{1,4}|cap\.?\d+([_\-]\d+)?|\d{1,4}[x×]\d{1,4}|\d{1,4}[\-+~]\d{1,4}|part[\s.\-]*\d{1,3}|\d{4}\.\d{1,2}\.\d{1,2}|ova(\s+\d{1,4})?|oad(\s+\d{1,4})?|specials|special\s+\d{1,4}|v\d+[a-z]?)$""",
    )

    /**
     * URL-shaped bracket tokens are provenance links, never release
     * groups (`[www.example2020.org]`).
     */
    private val REGEX_URL_LIKE_BRACKET = Regex(
        """(?i)(?:^www\.|\.(org|com|net|info|tv|io|me|to|cc|ru|pl|es|fr|it|de|nl|se)$)""",
    )

    // Suffix-style release group: a trailing `-GROUP` on the filename
    // (scene-style `...-GRPA`, `...-Grpb`) when no authoritative
    // `[Group]` tag exists. The media extension is stripped first so
    // `Title-GRPVY.mkv` still resolves.
    private val REGEX_STRIP_MEDIA_EXTENSION =
        Regex("""\.(mkv|mp4|avi|webm|ts|m4v|m2ts|mov|flv|rmvb)$""", RegexOption.IGNORE_CASE)
    private val REGEX_TRAILING_SUFFIX_GROUP =
        Regex("""-([A-Za-z][A-Za-z0-9-]{1,29})\s*$""")

    /** notes after a scene group: "H.264-GRPVY (Show, Multi-Subs)", "x265-GRP[site]" */
    private val REGEX_TRAILING_NOTES = Regex("""(?:\s*[\[(][^\[\]()]*[\])])+\s*$""")

    /**
     * Tokens that must never be promoted from a `-SUFFIX` position to
     * a release group: codec / bit-depth / audio / resolution-ish /
     * source-class / tag / container tokens. Compared uppercase.
     */
    private val SUFFIX_GROUP_DENY: Set<String> = setOf(
        "X264", "X265", "H264", "H265", "HEVC", "AVC", "AV1", "VP9",
        "XVID", "DIVX",
        "8BIT", "10BIT", "HI10P",
        "AAC", "AC3", "DTS", "EAC3", "DDP", "DD", "FLAC", "OPUS", "MP3",
        "TRUEHD", "ATMOS", "MKA", "M4A",
        "DUAL", "DUB", "DUBS", "DUBBED", "SUB", "SUBS", "SUBBED",
        "AUDIO",
        "2160P", "1080P", "720P", "480P", "1440P", "4K", "2K", "8K",
        "UHD", "FHD", "HD", "SD",
        "HDMA", "HRA",
        "WEBDL", "WEBRIP", "BLURAY", "BDRIP", "HDTV", "DVDRIP",
        "REMUX", "COMPLETE", "COMPLETA", "COMPLETO", "COMPLET",
        "BATCH", "UNCENSORED",
        "REPACK", "PROPER", "RERIP", "RATED", "UNRATED", "LIMITED",
        "INTERNAL", "RETAIL", "REAL", "READNFO", "FINAL", "REMASTERED",
        "RESTORED", "THEATRICAL", "EXTENDED", "UNCUT",
        "HDDVD", "PDTV", "AHDTV", "APDTV", "DSR", "ADSR", "MBLURAY",
        "WEB", "TELECINE", "WORKPRINT", "DCP", "MP2", "LPCM", "SDR", "HR",
        "CAM", "SCR", "TC", "R5", "TELESYNC", "HDCAM", "HDTS",
        "DVDSCR", "SCREENER", "HQ", "NFO",
        "MKV", "MP4", "AVI", "WEBM", "TS", "M4V", "M2TS", "MOV",
        "FLV", "RMVB",
    )

    // ---- Helpers ----------------------------------------------------------

    private fun isLikelyLanguageTag(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.contains(' ')) return false
        if (KNOWN_NON_GROUP_BRACKETS.any { it.equals(token, ignoreCase = true) }) return false
        if (SOURCE_SERVICE_ALIASES.containsKey(token.uppercase())) return false
        // Plain 2-3 letter tokens (e.g. "ENG", "JPN", "POR") need to be in
        // the known-codes list.
        if (!token.contains('-') && !token.contains('+')) {
            if (token.length > 4) return false
            if (!KNOWN_LANG_CODES.any { it.equals(token, ignoreCase = true) }) return false
            return token.all { it.isLetter() }
        }
        // Hyphenated regional codes (e.g. ESP-ENG, POR-BR, EN-US): each
        // segment must be 2-3 letters, all alphabetic.
        val segments = token.split('-', '+')
        for (s in segments) {
            if (s.length !in 2..3) return false
            if (!s.all { it.isLetter() }) return false
        }
        return true
    }

    private fun isGroupCandidate(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.length > 20) return false
        if (KNOWN_NON_GROUP_BRACKETS.any { it.equals(token, ignoreCase = true) }) return false
        if (SOURCE_SERVICE_ALIASES.containsKey(token.uppercase())) return false
        if (isLikelyLanguageTag(token)) return false
        // Audio/sub-led bracket tokens are content signals, never
        // groups ( so real
        // hyphenated groups such as Dubs-Grpire survive).
        if (REGEX_AUDIO_SUB_WHOLE_BRACKET.matches(token)) return false
        if (REGEX_AUDIO_SUB_LED_BRACKET.containsMatchIn(token)) return false
        if (REGEX_AUDIO_FORMAT_LED_BRACKET.containsMatchIn(token)) return false
        // Season / episode / version / URL-shaped brackets are
        // structural markers, never groups.
        if (REGEX_SEASON_EPISODE_BRACKET.matches(token)) return false
        if (REGEX_URL_LIKE_BRACKET.containsMatchIn(token)) return false
        // A real release group is one or more word-chars (allows dashes for
        // groups like Grpai-Sensei) but does not contain only digits.
        if (token.all { it.isDigit() }) return false
        return true
    }

    // ---- Presentation -----------------------------------------------------

    fun classifyPresentation(qualityText: String): SourcePresentationKind {
        val lines = qualityText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val hasMarker = lines.any { line -> MARKER_EMOJIS.any { line.contains(it) } }
        val hasReleaseFilename = lines.any { REGEX_RELEASE_FILENAME.containsMatchIn(it) }
        return if (hasMarker || hasReleaseFilename) SourcePresentationKind.RELEASE
        else SourcePresentationKind.STREAM
    }

    // ---- Transport ---------------------------------------------------------

    fun classifyTransport(url: String, formatIsContainer: Boolean, formatName: String): SourceTransport {
        if (url.startsWith("magnet:")) return SourceTransport.TORRENT
        val canonicalPath = canonicalUrlPath(url)
        if (canonicalPath.endsWith(".torrent", ignoreCase = true)) return SourceTransport.TORRENT
        when (formatName.uppercase()) {
            "M3U8" -> return SourceTransport.HLS
            "DASH" -> return SourceTransport.DASH
            "CONTAINER" -> {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return SourceTransport.DIRECT_HTTP
                }
                return SourceTransport.OTHER
            }
        }
        if (canonicalPath.endsWith(".m3u8", ignoreCase = true)) return SourceTransport.HLS
        if (canonicalPath.endsWith(".mpd", ignoreCase = true)) return SourceTransport.DASH
        return SourceTransport.OTHER
    }

    fun canonicalUrlPath(url: String): String {
        val noQuery = url.substringBefore('?')
        return noQuery.substringBefore('#')
    }

    /**
     * Batch/pack markers: an explicit `Batch` word or a
     * `Complete <pack-noun>` / `Season Pack` phrase. Bare `COMPLETE`
     * alone is NOT enough (ambiguous adjective in many languages).
     */
    private val REGEX_BATCH_MARKER = Regex(
        """(?i)\b(batch|complete[\s\-]+(series|pack|collection|set|movies?)|season[\s\-]+packs?)\b""",
    )

    /**
     * Checksum/CRC-like bracket tokens (`[AAA94036]`, `[F02B9CEE]`,
     * `(AB12CD34)`): 6+ hex chars, stripped from the clean display
     * title (the exact raw name keeps them for identity/details).
     */
    private val REGEX_CHECKSUM_BRACKET = Regex("""[\[(][A-Fa-f0-9]{6,}[\])]""")

    // ---- Release-name extraction ----------------------------------------

    /**
     * The release (torrent) name: all non-metadata lines except the
     * file-name line of a pack ([fileNameLine]), which is parsed
     * separately and never shown in the title.
     */
    fun extractReleaseName(qualityText: String): String {
        val titleLines = titleLines(qualityText)
        if (titleLines.isEmpty()) return qualityText.trim()
        val fileIdx = fileNameLineIndex(titleLines)
        val kept = if (fileIdx > 0) titleLines.filterIndexed { i, _ -> i != fileIdx } else titleLines
        return kept.joinToString(" ")
    }

    /**
     * File-name line of a multi-file (pack) row: a non-metadata line after
     * the release name that ends in a video extension. Sources add it
     * only for packs. Returned as written (a path inside the torrent is
     * kept; [parseRelease] drops it before parsing). Null when absent.
     */
    fun fileNameLine(qualityText: String): String? {
        val titleLines = titleLines(qualityText)
        val idx = fileNameLineIndex(titleLines)
        if (idx <= 0) return null
        return titleLines[idx]
    }

    private fun titleLines(qualityText: String): List<String> {
        val lines = qualityText.lines().map { it.trim() }.filter { it.isNotEmpty() && !isMetadataMarkerLine(it) }
        // A source may print its own name as the first line of a release row
        // ("<Name>\n<release>\n👤 ..."): a single word of letters, never a release name.
        return if (lines.size >= 2 && lines[0].matches(REGEX_SOURCE_NAME_LINE) &&
            classifyPresentation(qualityText) == SourcePresentationKind.RELEASE
        ) lines.drop(1) else lines
    }

    private val REGEX_SOURCE_NAME_LINE = Regex("""\p{L}{3,30}""")

    private fun fileNameLineIndex(titleLines: List<String>): Int {
        if (titleLines.size < 2) return -1
        for (i in 1 until titleLines.size) {
            if (REGEX_VIDEO_FILE_LINE.containsMatchIn(titleLines[i])) return i
        }
        return -1
    }

    private val REGEX_VIDEO_FILE_LINE = Regex("""(?i)\.(mkv|mp4|avi|m2ts|ts|webm|m4v|mov)\s*$""")

    private fun isMetadataMarkerLine(line: String): Boolean {
        // Some sources prepend "<tracker> - <quality>" (the hoster name and
        // a quality label) as a line of its own, e.g. "IndexA - 1080p" or
        // "IndexA - 4k DV | HDR". It is provenance:
        // the tracker is parsed from the ⚙ line, the resolution from the
        // title, so it must not become part of the release name.
        if (line.matches(REGEX_PROVIDER_QUALITY_LINE)) return true
        if (isLanguageLine(line)) return true
        if (line.firstOrNull()?.let { MARKER_EMOJIS.any { e -> line.startsWith(e) } } == true) return true
        if (line.matches(REGEX_METADATA_MARKER_LINE)) return true
        return false
    }

    // ---- Resolution parsing (UI only) -----------------------------------

    // Match an unambiguous "4k" / "4K" token that means 2160p. Only
    // matched when the surrounding context is a quality prefix (i.e. the
    // token is at the very start of the line, or surrounded by
    // whitespace, or follows a [brackets] before the title).
    private val REGEX_RESOLUTION_4K =
        Regex("""(?i)(?:^|\s|\])4k(?:\s|$)""")

    fun parseResolution(qualityText: String): String? {
        // Explicit 4k (2160p) is a recognised resolution marker in the
        // current torrent-source naming convention. Prefer it over later
        // bracketed technical-resolution metadata.
        if (REGEX_RESOLUTION_4K.containsMatchIn(qualityText)) return "2160p"
        REGEX_RESOLUTION.find(qualityText)?.value?.let { return it }
        val wxh = REGEX_RESOLUTION_WXH.find(qualityText) ?: return null
        val height = wxh.groupValues[2].toIntOrNull() ?: return null
        return "${height}p"
    }

    /**
     * Display alias for the collapsed card. 2160p -> "4K"
     * feedback. Internal value stays "2160p".
     */
    fun resolutionDisplayAlias(resolution: String?): String? {
        if (resolution == null) return null
        if (resolution == "2160p") return "4K"
        return resolution
    }

    // ---- Codec / bit-depth / HDR / DV normalisation ---------------------

    fun normaliseCodec(raw: String): String? {
        val t = raw.uppercase().replace(REGEX_WS_DOT, "")
        return when {
            t.startsWith("X264") || t.startsWith("H264") -> "AVC"
            t.startsWith("X265") || t.startsWith("H265") || t == "HEVC" -> "HEVC"
            t == "AVC" -> "AVC"
            t == "AV1" -> "AV1"
            t == "VP9" -> "VP9"
            t == "XVID" -> "XVID"
            t == "DIVX" -> "DIVX"
            else -> null
        }
    }

    fun normaliseBitDepth(raw: String): String? {
        val t = raw.lowercase().replace(REGEX_WS_SINGLE, "")
        return when {
            t == "10-bit" || t == "10bit" || t == "10-bits" || t == "10bits" -> "10-bit"
            t == "8-bit" || t == "8bit" || t == "8-bits" || t == "8bits" -> "8-bit"
            t == "hi10p" -> "10-bit"
            else -> null
        }
    }

    fun normaliseHdr(raw: String): String? {
        val t = raw.lowercase().replace(REGEX_WS_RUN, " ").trim()
        return when {
            t == "hdr10+" || t == "hdr10plus" -> "HDR10+"
            t == "hdr10" -> "HDR10"
            t == "hdr" -> "HDR"
            t == "dolby vision" || t == "dolbyvision" -> "Dolby Vision"
            else -> null
        }
    }

    // ---- Audio / language flag extraction -------------------------------

    /**
     * Unicode regional-indicator flag pair: two consecutive regional
     * indicator codepoints form a flag glyph. Each regional indicator is
     * U+1F1E6..U+1F1FF, encoded as a UTF-16 surrogate pair (high
     * U+D83C + low U+DDE6..DDFF). Manual code-point scanner because
     * Java/Kotlin regex on a lone high surrogate is unreliable across JVM
     * versions.
     */
    private fun extractFlags(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val cps = text.codePoints().toArray()
        var k = 0
        while (k < cps.size - 1) {
            val a = cps[k]
            val b = cps[k + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                out.add(String(Character.toChars(a)) + String(Character.toChars(b)))
                k += 2
            } else {
                k += 1
            }
        }
        return out.toList()
    }

    /**
     * Bracketed language codes [ENG], [POR-BR], [ESP-ENG] etc. Always
     * in the input string (the bracket syntax is the marker).
     */
    private fun extractBracketedLanguages(text: String): List<String> {
        val out = LinkedHashSet<String>()
        REGEX_LANGUAGE_TAG.findAll(text).forEach { match ->
            val raw = match.groupValues[1]
            raw.split("+").forEach { part ->
                if (isLikelyLanguageTag(part) ||
                    part.matches(REGEX_LANG_PAIR_TOKEN)
                ) {
                    out.add(part.uppercase())
                }
            }
        }
        return out.toList()
    }

    data class FlagSets(
        val subtitleFlags: List<String>,
        val audioFlags: List<String>,
        val subtitleLanguages: List<String>,
        val audioLanguages: List<String>,
    )

    /**
     * Extract flags and bracketed language codes with line-local context.
     * A flag or bracketed language only counts toward subtitle/audio if it
     * appears on a line with the matching context marker.
     */
    private fun extractFlagsAndLanguages(text: String): FlagSets {
        val subFlags = LinkedHashSet<String>()
        val audFlags = LinkedHashSet<String>()
        val subLangs = LinkedHashSet<String>()
        val audLangs = LinkedHashSet<String>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val isSubCtx = SUB_CONTEXT_REGEX.containsMatchIn(trimmed)
            val isAudCtx = AUDIO_CONTEXT_REGEX.containsMatchIn(trimmed) ||
                trimmed.startsWith("🎬")
            if (isSubCtx) {
                subFlags.addAll(extractFlags(trimmed))
                subLangs.addAll(extractBracketedLanguages(trimmed))
            }
            if (isAudCtx) {
                audFlags.addAll(extractFlags(trimmed))
                audLangs.addAll(extractBracketedLanguages(trimmed))
            }
        }
        return FlagSets(
            subtitleFlags = subFlags.toList(),
            audioFlags = audFlags.toList(),
            subtitleLanguages = subLangs.toList(),
            audioLanguages = audLangs.toList(),
        )
    }

    // ---- Source service detection (NF / AMZN / CR) ---------------------

    fun detectSourceService(releaseName: String): String? {
        // 1. Bracketed token in the title (the common form).
        val bracketToken = REGEX_SERVICE_BRACKET_TOKEN
            .findAll(releaseName)
            .map { it.groupValues[1].uppercase() }
            .firstOrNull { SOURCE_SERVICE_ALIASES.containsKey(it) }
        if (bracketToken != null) return SOURCE_SERVICE_ALIASES[bracketToken]
        // 2. Standalone, clearly delimited alias derived from the
        //    authoritative visual map, so every
        //    high-confidence entry works — not just NF/AMZN/CR.
        //    Delimiters are start/end, whitespace, dots or brackets
        //    (parenthesized tech blocks like `(CR WEB 480p …)`
        //    carry the alias after `(`). Precompiled once: the
        //    alias map is static, so the pattern never changes.
        SOURCE_SERVICE_STANDALONE.find(releaseName)?.let { match ->
            val alias = match.value.trim().trim('.', '(', ')', '[', ']').uppercase()
            if (SOURCE_SERVICE_ALIASES.containsKey(alias)) {
                return SOURCE_SERVICE_ALIASES[alias]
            }
        }
        return null
    }

    // ---- Source class detection (WEB-DL / BluRay / etc.) -----------------

    fun detectSourceClass(releaseName: String): String? {
        val candidates = listOf(
            "WEB-DL", "WEBDL", "WEBRip", "BluRay", "BDRip", "REMUX",
            "HDTV", "DVDRip",
        )
        for (c in candidates) {
            if (releaseName.contains(c, ignoreCase = true)) return c.uppercase()
        }
        // bare `BD` is a real
        // source-type token, but `contains` would overmatch ordinary
        // words (`Abducted`, `Bdubs`), so it needs strict delimited
        // matching. Ordered AFTER `BDRip` so `BDRip` keeps its precise
        // label (`BDRip` never matches: `R` follows `BD`). Canonical
        // value is `BD`, matching the existing strip vocabulary.
        if (SOURCE_CLASS_BD_DELIMITED.containsMatchIn(releaseName)) return "BD"
        return null
    }

    // ---- Release-group extraction -------------------------

    /**
     * Release-group rule (v2.2 current-format compatibility).
     *
     * The returned 784-row corpus contains zero `📂` rows: current
     * torrent-source payloads carry the provider/indexer in
     * `⚙️`, so a lone `⚙️` value is provenance — never a group.
     *
     * Precedence (strongest first; a weaker guess never overwrites a
     * higher-confidence explicit tag):
     *   1. title bracket (preferred)
     *   2. legacy `⚙` group, ONLY in the dedicated-`📂` two-marker
     *      shape and only when the `⚙` value is distinct from the
     *      `📂` tracker
     *   3. trailing `-GROUP` filename suffix (weak guess, scanned over
     *      the raw `qualityText` title/file lines)
     *   4. null (correct when no group is present; never fabricate
     *      from a provider name)
     *
     * The `⚙` value and the title bracket can disagree in real source
     * output. Prefer the title bracket; the `⚙`
     * value is corroborating only. If both are present and disagree,
     * return the title bracket and record the discrepancy.
     */
    fun extractReleaseGroup(qualityText: String, releaseName: String): String? {
        // Dedicated 📂 tracker (may be absent in current payloads).
        val dedicatedTracker = REGEX_INDEXER
            .find(qualityText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Effective tracker for prefix corroboration: 📂 wins, lone ⚙
        // is provenance.
        val effectiveTracker = dedicatedTracker ?: cogValue(qualityText)

        // 1. Title bracket (preferred) — but only if corroborated by the
        //    effective tracker (or no tracker at all).
        titleBracketGroup(releaseName, effectiveTracker)?.let { return it }

        // 2. Legacy cog group: only in the dedicated-📂 two-marker
        //    shape, and only when distinct from that tracker. The
        //    literal ⚙️ marker is required (strict match).
        if (dedicatedTracker != null) {
            cogValue(qualityText)?.let { cog ->
                if (!cog.equals(dedicatedTracker, ignoreCase = true) &&
                    isGroupCandidate(cog)
                ) {
                    return cog
                }
            }
        }

        // 2b. File-name bracket: `[Group] Title - 01 (...).mkv`. Covers
        //     releases whose title starts with free text before the
        //     first bracket ("4k Show: Local Title / ... [Group-Raws] ...").
        fileNameBracketGroup(qualityText)?.let { return it }

        // 3. Trailing `-GROUP` filename suffix (weak guess), scanned
        //    over the raw qualityText title/file lines (Fix B), so an
        //    explicit tag always wins over the suffix guess.
        trailingSuffixGroupFromText(qualityText)?.let { return it }

        // 4. Neither title bracket, legacy cog, nor suffix yielded a
        //    group. Tracker/provenance is never the group.
        return null
    }

    /**
     * Strict `⚙️` value (literal marker required), trimmed, or null.
     */
    private fun cogValue(qualityText: String): String? {
        return REGEX_COG_VALUE
            .find(qualityText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Effective tracker/provenance (v2.2 current-format compatibility):
     * the dedicated `📂` value when present, otherwise the lone `⚙`
     * provider value. Null when neither exists.
     */
    fun parseTracker(qualityText: String): String? {
        REGEX_INDEXER
            .find(qualityText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return cogValue(qualityText)
    }

    /**
     * Suffix-style group extraction over raw `qualityText` lines
     * (v2.2 Fix B).
     *
     * `extractReleaseName` flattens title/file lines for display, so
     * scanning the flattened release name loses the filename line
     * whenever a non-marker language/audio line follows it
     * (`...-Grpro.mkv` + `Dubbed / Multi Audio`). This scans the
     * ORIGINAL line structure instead, skipping marker lines
     * (`👤`/`💾`/`⚙️`/`📂`/emoji-led and source-name header lines —
     * same filter as the display name), and returns the first valid
     * trailing `-GROUP`. Display `releaseName` behavior is unchanged.
     * Guards unchanged (fail conservative -> null).
     */
    private fun trailingSuffixGroupFromText(qualityText: String): String? {
        for (rawLine in qualityText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (isMetadataMarkerLine(line)) continue
            val noExt = REGEX_STRIP_MEDIA_EXTENSION.replace(line, "")
                .replace(REGEX_TRAILING_NOTES, "").trimEnd()
            val token = REGEX_TRAILING_SUFFIX_GROUP.find(noExt)
                ?.groupValues?.getOrNull(1)?.trimEnd('-') ?: continue
            if (!isSuffixGroupCandidate(token)) continue
            return token
        }
        return null
    }

    private fun isSuffixGroupCandidate(token: String): Boolean {
        // hyphenated groups ("Grpdere-Raws") may be longer than plain ones
        if (token.length !in 2..(if ('-' in token) 30 else 12)) return false
        // Episode/version shaped: S01E01, E01, S1, 01, v2.
        if (token.matches(REGEX_EPISODE_SHAPE_TOKEN)) return false
        if (token.matches(REGEX_VERSION_SHAPE_TOKEN)) return false
        // Resolution shaped: 1080p (4K/8K are in the deny set).
        if (token.matches(REGEX_RES_SHAPE_TOKEN)) return false
        val upper = token.uppercase()
        if (upper in SUFFIX_GROUP_DENY) return false
        // "WEB-DL" -> "DL", "x264-Multi-Subs": a part that is a known tag means it isn't a group
        if (upper == "DL" || upper == "RIP") return false
        if ('-' in upper && upper.split('-').any { it in SUFFIX_GROUP_DENY || it == "MULTI" || it == "DL" }) return false
        if (SOURCE_SERVICE_ALIASES.containsKey(upper)) return false
        if (KNOWN_LANG_CODES.any { it.equals(upper, ignoreCase = true) }) {
            return false
        }
        return true
    }

    /**
     * Find the first valid bracketed group token in the release title,
     * corroborated against the parsed `📂` tracker (if any).
     *
     * Allowed prefix shapes:
     *   - "" (no prefix; the first bracket is the group)
     *   - "<quality> [...]":           e.g. "1080p [...]"
     *   - "<parsed-tracker> [...]":     e.g. "IndexA [...]"
     *   - "<quality> <parsed-tracker> [...]" or vice-versa
     *
     * If `parsedTracker` is non-null, the prefix's tracker-shaped token
     * MUST match the parsed tracker (case-preserved) exactly. Generic
     * title words like "Title" or "S01E01" are never accepted as
     * trackers. If no tracker was parsed from the `📂` line, the prefix
     * is allowed to be empty or to be a single quality token only.
     */
    private fun titleBracketGroup(
        releaseName: String,
        parsedTracker: String?,
    ): String? {
        for (rawLine in releaseName.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val firstBracket = line.indexOf('[')
            if (firstBracket < 0) continue
            val prefix = line.substring(0, firstBracket).trim()
            if (!isGroupPositionPrefix(prefix, parsedTracker)) continue
            val match = REGEX_TITLE_BRACKET.find(line.substring(firstBracket)) ?: continue
            val token = match.groupValues[1].trim()
            if (!isGroupCandidate(token)) continue
            if ('.' in token) {
                // Dotted scene compounds: resolve the trailing group
                // when the head is technical (`HDTV.x264-zql` -> `zql`);
                // reject the line when the head is technical but the
                // tail is not a group (`SD.H265.JAP.AC3.SUB.ITA`);
                // accept whole dotted names that are not technical
                // compounds (`grpe4life.`).
                val compound = dottedCompoundGroup(token)
                if (compound != null) return compound
                if (hasTechnicalDotHead(token)) continue
            }
            return token
        }
        return null
    }

    /**
     * Check that the prefix before the first bracket is a valid
     * group-position prefix, optionally corroborated by the parsed tracker.
     */
    private fun isGroupPositionPrefix(
        prefix: String,
        parsedTracker: String?,
    ): Boolean {
        if (prefix.isEmpty()) return true
        val tokens = prefix.split(REGEX_WS_RUN)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when (tokens.size) {
            0 -> true
            1 -> isSingleGroupPrefix(tokens[0], parsedTracker)
            2 -> isPairGroupPrefix(tokens[0], tokens[1], parsedTracker)
            else -> false
        }
    }

    /**
     * One prefix token: a quality token, or the parsed tracker itself. With
     * no parsed tracker, no title word may pass as one.
     */
    private fun isSingleGroupPrefix(token: String, parsedTracker: String?): Boolean =
        isQualityPrefixToken(token) || (parsedTracker != null && token == parsedTracker)

    /**
     * Two prefix tokens. A technical second token (HDR / codec / bit-depth)
     * is metadata shown elsewhere (`1080p HDR [Grpsali]` accepts Grpsali),
     * bounded by the deny-list that rejects technical brackets as groups.
     * A tracker slot must equal the parsed tracker (case-preserved).
     */
    private fun isPairGroupPrefix(first: String, second: String, parsedTracker: String?): Boolean {
        val firstIsQuality = isQualityPrefixToken(first)
        val secondIsQuality = isQualityPrefixToken(second)
        val secondIsTechnical = KNOWN_NON_GROUP_BRACKETS.any { it.equals(second, ignoreCase = true) }
        // Without a tracker: "1080p 720p" is nonsense, "1080p HDR" is fine.
        if (parsedTracker == null) return firstIsQuality && !secondIsQuality && secondIsTechnical
        // "<quality> <tracker>"
        val qualityThenTracker = firstIsQuality && isTrackerLikeToken(second) && second == parsedTracker
        // "<tracker> <quality>"
        val trackerThenQuality = isTrackerLikeToken(first) && first == parsedTracker && secondIsQuality
        return qualityThenTracker || trackerThenQuality || (firstIsQuality && secondIsTechnical)
    }

    /**
     * Technical dot-segments for dotted-compound brackets
     * (`HDTV.x264-zql`, `SD.H265.JAP.AC3.SUB.ITA`): resolution, codec,
     * source-class, audio, bit-depth, HDR, subtitle/audio words,
     * language codes and SD/HD/VFR markers.
     */
    private fun isTechnicalDotSegment(segment: String): Boolean {
        val upper = segment.uppercase()
        if (upper in KNOWN_NON_GROUP_BRACKETS) return true
        if (upper in COMPOUND_TECHNICAL_EXTRA) return true
        if (SOURCE_SERVICE_ALIASES.containsKey(upper)) return true
        if (KNOWN_LANG_CODES.any { it.equals(upper, ignoreCase = true) }) return true
        if (segment.matches(REGEX_TECH_DOT_RES)) return true
        if (segment.matches(REGEX_TECH_DOT_CODEC)) return true
        if (segment.matches(REGEX_TECH_DOT_AUDIO)) return true
        if (segment.matches(REGEX_TECH_DOT_DEPTH_HDR)) return true
        if (segment.matches(REGEX_TECH_DOT_CLASS)) return true
        if (segment.matches(REGEX_TECH_DOT_SUBDUB)) return true
        return false
    }

    private val COMPOUND_TECHNICAL_EXTRA: Set<String> = setOf(
        "SD", "HD", "VFR", "COMPLETA", "COMPLETO", "COMPLET",
        // Common non-ISO scene language shorthands (ISO codes are in
        // KNOWN_LANG_CODES already).
        "JAP", "ENG", "ESP", "POR", "FRE", "GER", "RUS", "CHI",
        "KOR", "ARA",
    )

    /**
     * Dotted-compound bracket groups: scene-style
     * `[HDTV.x264-zql]` carries the group after the technical head, so
     * it resolves to `zql`. Returns null when the compound is
     * undecidable or the tail is itself technical
     * (`[SD.H265.JAP.AC3.SUB.ITA]` -> tail `ITA` is a language code).
     */
    private fun dottedCompoundGroup(token: String): String? {
        if ('.' !in token) return null
        val segments = token.split('.')
        if (segments.size < 2) return null
        if (!segments.dropLast(1).all { isTechnicalDotSegment(it) }) return null
        val tail = segments.last().substringAfterLast('-')
        if (!isGroupCandidate(tail)) return null
        return tail
    }

    private fun hasTechnicalDotHead(token: String): Boolean {
        if ('.' !in token) return false
        val segments = token.split('.')
        if (segments.size < 2) return false
        return segments.dropLast(1).all { isTechnicalDotSegment(it) }
    }

    private fun isTrackerLikeToken(token: String): Boolean {
        // Trackers are short alpha tokens (sometimes alphanumeric like
        // 2448x), without dot/colon/path markers.
        if (token.length !in 1..30) return false
        if (token.any { it == '.' || it == '/' || it == '\\' || it == ':' }) return false
        return token.any { it.isLetter() }
    }

    /**
     * True if the prefix before the first bracket is empty or only
     * contains known technical tokens (resolution, HDR, audio mode).
     *
     * Note: this is retained for any caller that needs to filter, but
     * the current [titleBracketGroup] implementation does not require
     * the prefix to be bounded — the real source format uses both
     * `tracker [GROUP]` and `quality [GROUP]` patterns.
     */
    @Suppress("unused")
    private fun isAllowedPrefix(prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        val tokens = prefix.split(REGEX_WS_RUN).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        for (t in tokens) {
            val lower = t.lowercase()
            if (lower in ALLOWED_PREFIX_TOKENS) continue
            if (lower in setOf("hdr", "hdr10", "hdr10+", "10bit", "10-bit", "8bit", "8-bit",
                "dual", "audio", "dub", "sub", "subs")) continue
            return false
        }
        return true
    }

    // ---- Release field parsing ------------------------------------------

    data class ReleaseFields(
        val resolution: String?,
        val hdr: Boolean,
        val releaseName: String,
        val releaseGroup: String?,
        val seeders: Int?,
        val sizeText: String?,
        val tracker: String?,
        val indexer: String?,
        val audioSummary: String?,
        val audioFormat: String?,
        val codec: String?,
        val bitDepth: String?,
        val hdrVariant: String?,
        val subtitleSummary: String?,
        val subtitleFlags: List<String>,
        val audioFlags: List<String>,
        val subtitleLanguages: List<String>,
        val audioLanguages: List<String>,
        val sourceService: String?,
        val sourceClass: String?,
        val batch: Boolean = false,
        /** "Uncensored" / "Censored" when the title says so. */
        val censorship: String? = null,
        /** "REPACK", "v2", ... when the release is a re-release. */
        val revision: String? = null,
        /** True for "OV" (original video, often English-subs-only) releases. */
        val originalVideo: Boolean = false,
    )

    /**
     * Parse a row. The release (torrent) name and the file name are parsed
     * separately and merged ([mergeReleaseFields]): the release name wins
     * for single values, the file name fills gaps, lists are combined.
     * [fileName] is an explicit file name (e.g. from a source's
     * metadata); otherwise the pack file-name line of [qualityText] is
     * used. Without a usable file name the result is the text-only parse.
     */
    fun parseRelease(
        qualityText: String,
        fileName: String? = null,
    ): ReleaseFields {
        val primary = parseReleaseText(qualityText)
        val file = (fileName?.takeIf { it.isNotBlank() } ?: fileNameLine(qualityText))?.let { cleanFileName(it) }
            ?.takeIf { isUsableFileName(it, primary.releaseName) }
            ?: return primary
        return mergeReleaseFields(primary, parseReleaseText(file))
    }

    private fun cleanFileName(raw: String): String? =
        raw.substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotEmpty() }

    private fun isUsableFileName(file: String, releaseName: String): Boolean {
        fun norm(s: String) = REGEX_STRIP_MEDIA_EXTENSION.replace(s.trim(), "")
            .replace(REGEX_NAME_SEPARATORS, " ").trim().lowercase()
        return file.isNotBlank() && norm(file) != norm(releaseName)
    }

    private val REGEX_NAME_SEPARATORS = Regex("""[\s._]+""")

    /** Release name wins for single values; file name fills gaps; lists are combined in order. */
    fun mergeReleaseFields(primary: ReleaseFields, file: ReleaseFields): ReleaseFields {
        fun <T> union(a: List<T>, b: List<T>): List<T> = (a + b).distinct()
        return primary.copy(
            resolution = primary.resolution ?: file.resolution,
            hdr = primary.hdr || file.hdr,
            releaseGroup = primary.releaseGroup ?: file.releaseGroup,
            audioSummary = primary.audioSummary ?: file.audioSummary,
            audioFormat = primary.audioFormat ?: file.audioFormat,
            codec = primary.codec ?: file.codec,
            bitDepth = primary.bitDepth ?: file.bitDepth,
            hdrVariant = primary.hdrVariant ?: file.hdrVariant,
            subtitleSummary = primary.subtitleSummary ?: file.subtitleSummary,
            subtitleFlags = union(primary.subtitleFlags, file.subtitleFlags),
            audioFlags = union(primary.audioFlags, file.audioFlags),
            subtitleLanguages = union(primary.subtitleLanguages, file.subtitleLanguages),
            audioLanguages = union(primary.audioLanguages, file.audioLanguages),
            sourceService = primary.sourceService ?: file.sourceService,
            sourceClass = primary.sourceClass ?: file.sourceClass,
            batch = primary.batch || file.batch,
            censorship = primary.censorship ?: file.censorship,
            revision = primary.revision ?: file.revision,
            originalVideo = primary.originalVideo || file.originalVideo,
        )
    }

    private fun parseReleaseText(qualityText: String): ReleaseFields {
        val resolution = parseResolution(qualityText)
        val hdr = REGEX_HDR_SIMPLE.containsMatchIn(qualityText) ||
            REGEX_HDR_BARE.containsMatchIn(qualityText)
        val releaseName = extractReleaseName(qualityText)
        val releaseGroup = extractReleaseGroup(qualityText, releaseName)
        val seeders = REGEX_SEEDERS.find(qualityText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val sizeText = REGEX_SIZE.find(qualityText)?.groupValues?.getOrNull(1)?.trim()
        // Tracker/provenance: the dedicated 📂 value when present,
        // otherwise the lone ⚙ provider value (v2.2 current-format
        // compatibility — the 784-row corpus has zero 📂 rows).
        val tracker = parseTracker(qualityText)
        // Kept for backwards compatibility with older render paths.
        val indexer = tracker
        // Explicit 🎬 audio line wins; bare dub markers (DUAL /
        // Dubbed / Eng Dub / FRENCH / Castellano / ...) in the title
        // only fill the field when no explicit audio signal exists.
        // Bare MULTI is audio only when no multi-subs marker claims
        // the title (French-scene MULTI = FR+JP audio). Never inferred
        // from the provider/tracker name: detection scans the release
        // title.
        val audioSummaryExplicit =
            REGEX_AUDIO.find(qualityText)?.groupValues?.getOrNull(1)?.trim()
        val langLine = languageLineText(qualityText)
        val audioText = if (langLine.isEmpty()) releaseName else "$releaseName $langLine"
        val allowBareMulti =
            !REGEX_MULTI_SUBS_WORD.containsMatchIn(audioText)
        val audioSummary = audioSummaryExplicit
            ?: detectAudioTrackKind(audioText, allowBareMulti)
        val audioFormat = extractAudioFormat(qualityText, releaseName, audioSummary)
        val codec = extractCodec(qualityText, releaseName)
        val bitDepth = extractBitDepth(qualityText, releaseName)
        val hdrVariant = extractHdrVariant(qualityText, releaseName)
        val flagSets = extractFlagsAndLanguages(qualityText)
        val hasExplicitSubs = flagSets.subtitleFlags.isNotEmpty() ||
            flagSets.subtitleLanguages.isNotEmpty()
        // VOSTFR (original audio + French subs, by definition) materializes
        // as a French flag when no parsed flags exist, so the row carries
        // a flag chip like every other subtitled row instead of a text
        // label. Explicit parsed languages still win over it.
        val subtitleFlags = flagSets.subtitleFlags.ifEmpty {
            if (flagSets.subtitleLanguages.isEmpty() &&
                REGEX_VOSTFR.containsMatchIn(releaseName)
            ) {
                listOf("🇫🇷")
            } else {
                emptyList()
            }
        }
        // Bare title markers (VOSTFR / Multi-Subs / ...).
        val markedSubs = if (!hasExplicitSubs) {
            detectMultiSubsSummary(audioText)
        } else {
            null
        }
        val subtitleLanguages = flagSets.subtitleLanguages
        // Subtitle summary prefers flags, falls back to language codes,
        // then bare title markers.
        val subtitleSummary = buildSubtitleSummary(subtitleFlags,
            subtitleLanguages)
            ?: markedSubs
        val sourceService = detectSourceService(releaseName)
        val sourceClass = detectSourceClass(releaseName)
        val batch = REGEX_BATCH_MARKER.containsMatchIn(releaseName)
        return ReleaseFields(
            resolution, hdr, releaseName, releaseGroup, seeders, sizeText,
            tracker, indexer, audioSummary, audioFormat, codec, bitDepth, hdrVariant,
            subtitleSummary, subtitleFlags, flagSets.audioFlags,
            subtitleLanguages, flagSets.audioLanguages,
            sourceService, sourceClass, batch,
            censorship = detectCensorship(releaseName),
            revision = detectRevision(releaseName),
            originalVideo = REGEX_OV.containsMatchIn(releaseName),
        )
    }

    /** "Uncensored" wins when both words appear (e.g. "Censored ... Uncensored" lists). */
    fun detectCensorship(releaseName: String): String? = when {
        REGEX_UNCENSORED.containsMatchIn(releaseName) -> "Uncensored"
        REGEX_CENSORED.containsMatchIn(releaseName) -> "Censored"
        else -> null
    }

    /** REPACK / PROPER / vN (episode version) markers, or null. */
    fun detectRevision(releaseName: String): String? {
        REGEX_REPACK.find(releaseName)?.let { return it.groupValues[1].uppercase() }
        REGEX_EPISODE_VERSION.find(releaseName)?.let { return "v" + it.groupValues[1] }
        return null
    }

    private val REGEX_UNCENSORED = Regex("""(?i)\buncensored\b""")
    private val REGEX_CENSORED = Regex("""(?i)\bcensored\b""")
    private val REGEX_REPACK = Regex("""(?i)\b(repack\d?|proper)\b""")
    /** "S01E01v2", "- 01v2", "E05 v3", "S02 v2" (batch): a version digit after an episode/season number. */
    private val REGEX_EPISODE_VERSION = Regex("""(?i)(?:e\d{1,4}|\bs\d{1,4}|\s-\s\d{1,4}|\b\d{2,4})\s?v([2-9])\b""")
    private val REGEX_OV = Regex("""\bOV\b""")

    private fun buildSubtitleSummary(
        flags: List<String>,
        langs: List<String>,
    ): String? {
        if (flags.isNotEmpty()) {
            return if (flags.size <= 3) flags.joinToString(" ")
            else "${flags.take(3).joinToString(" ")} +${flags.size - 3}"
        }
        return when {
            langs.isEmpty() -> null
            langs.size == 1 -> langs.single()
            langs.size in 2..3 -> langs.joinToString("+")
            else -> "Multi-subs"
        }
    }

    /**
     * Bare dub track-kind in the release title (extended
     * by with French / Spanish / Portuguese scene
     * vocabulary.
     *
     * Returns `Dual` for standalone DUAL / MULTIDUB / bare MULTI /
     * slashed `Jpn/Eng/Ger Audio` forms, `Dub` for Dubbed / English
     * Dub / Eng Dub / English Audio / FRENCH / Castellano / Latino /
     * Dublado, else null. Neutral `espanol` resolves through nearby
     * sub/dub context words. Word-boundary matched, so `individual`,
     * `gradual`, `redubbed`, `Japanese`, `PlumbingSubs` or
     * `MicoLeaoDublado` never fire. Matched against the release title
     * only, never the provider or tracker name.
     *
     * Bare MULTI is audio only when no multi-subs marker is present
     * (French-scene MULTI = FR+JP audio.
     */
    private fun detectAudioTrackKind(releaseName: String, allowBareMulti: Boolean = true): String? {
        audioKindRules.firstOrNull { it.first.containsMatchIn(releaseName) }?.let { return it.second }
        if (allowBareMulti && REGEX_BARE_MULTI.containsMatchIn(releaseName)) return "Dual"
        // Espanol / a full language name count as a dub only next to a dub word.
        val spanishOrLanguage = REGEX_ESPANOL.containsMatchIn(releaseName) ||
            REGEX_FULL_LANGUAGE.containsMatchIn(releaseName)
        if (spanishOrLanguage && REGEX_DUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Dub"
        return null
    }

    /**
     * Audio-mode rules in priority order. "Dubbed / Multi Audio" on the
     * language line is a multi-audio release, so dual, not dub-only. Lazy
     * because the regexes are declared further down this object.
     */
    private val audioKindRules: List<Pair<Regex, String>> by lazy {
        listOf(
            REGEX_DUAL_WORD to "Dual",
            REGEX_DUAL_AUDIO_SEP to "Dual",
            REGEX_MULTI_AUDIO to "Dual",
            REGEX_LANG_PAIR_DUAL to "Dual",
            REGEX_LANG_PAIR_WORDS_DUAL to "Dual",
            REGEX_DUBBED_WORD to "Dub",
            REGEX_ENG_DUB to "Dub",
            REGEX_FUNI_DUB to "Dub",
            REGEX_ENGLISH_AUDIO_DUB to "Dub",
            REGEX_FRENCH_DUB to "Dub",
            REGEX_CASTELLANO_DUB to "Dub",
            REGEX_LATINO_DUB to "Dub",
            REGEX_DUBLADO_DUB to "Dub",
            REGEX_MULTIDUB to "Dual",
            REGEX_SLASHED_AUDIO_DUAL to "Dual",
        )
    }

    private val REGEX_BARE_MULTI = Regex("""(?i)\bmulti\b""")

    /**
     * Bare subtitle markers in the release title (
     *). VOSTFR is handled upstream by
     * materializing a 🇫🇷 flag, so it is not listed here.
     *
     * Returns `Multi-subs` (the existing fallback string) for multi /
     * subs / subbed / subtitled / napisy / legendado / subtitulado
     * forms and context-resolved `espanol`, else null.
     */
    private fun detectMultiSubsSummary(releaseName: String): String? {
        if (REGEX_MULTI_SUBS_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_SUBS_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_NAPISY.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_LEGENDADO.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_SUBTITULADO.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_ESPANOL.containsMatchIn(releaseName) &&
            REGEX_SUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_FULL_LANGUAGE.containsMatchIn(releaseName) &&
            REGEX_SUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        return null
    }

    private fun extractAudioFormat(
        qualityText: String,
        releaseName: String,
        audioSummary: String?,
    ): String? {
        // NOTE: whole-match `value` (not group 1): the trailing DD+
        // alternative sits outside the numbered group, and an
        // unmatched group would read back as "".
        audioSummary?.let { line ->
            AUDIO_FORMAT_REGEX.find(line)?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { return normaliseAudioFormat(it) }
        }
        AUDIO_FORMAT_REGEX.find(releaseName)?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return normaliseAudioFormat(it) }
        return AUDIO_FORMAT_REGEX.find(qualityText)?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { normaliseAudioFormat(it) }
    }

    private fun normaliseAudioFormat(raw: String): String {
        // Collapse internal whitespace, then expand common codec
        // abbreviations to their canonical display form. A trailing
        // stereo `2.0` channel count is dropped: stereo is the default
        // these days, so `AAC 2.0` displays as `AAC`;
        // real multichannel (`5.1`, `7.1`) is always kept. Smashed scene
        // shorthands (`DDP5`, `AC351`) expand to their spaced forms.
        //
        // Dolby families stay separate:
        // plain `DD` is basic Dolby Digital and must never upgrade to
        // the `DDP`/`DD+`/`EAC3` (E-AC-3 / Dolby Digital Plus) family.
        // All E-AC-3 spellings (`EAC3`, `E-AC3`, `EAC-3`, `E-AC-3`)
        // canonicalize before channel rules, so the generic
        // letters+digit rewrite can never emit `EAC 3`.
        val collapsed = raw.replace(REGEX_WS_RUN, " ").trim()
        // Channel-separator normalization (v2.2 Fix E): bounded
        // `FORMAT<dot/underscore/hyphen>channels` spellings become
        // `FORMAT channels` before the existing pipeline, so `DD.5.1`,
        // `EAC3.5.1`, `FLAC.5.1` and `PCM.5.1` resolve exactly like
        // their spaced forms. Family words are explicit; version-like
        // `MP3.320` stays descriptive via fallthrough.
        val chanNorm = collapsed.replace(
            REGEX_CHAN_NORM,
            "$1 $2",
        )
        // Early E-AC-3 canonicalization on the channel-normalized form,
        // before the letters+digit rewrite below can split `EAC3` into
        // `EAC 3` (which no later rule could rejoin).
        val eacEarly = chanNorm.replace(
            REGEX_EAC_PREFIX,
            "E-AC-3",
        )
        val withSpace = eacEarly.replace(REGEX_LETTERS_DIGIT_GLUE, "$1 $2")
        val stereoStripped = withSpace
            .replace(REGEX_STEREO_TAIL, "")
            .trim()
        // Canonical E-AC-3 prefix: every spelling (`EAC3`, `E-AC3`,
        // `EAC-3`, `E-AC-3`, with optional channels) normalizes before
        // channel rules, so the generic letters+digit rewrite can never
        // emit `EAC 3`.
        val eacNorm = stereoStripped.replace(
            REGEX_EAC_PREFIX,
            "E-AC-3",
        )
        // Canonical DTS-HD tails (,
        // completed in v2.2): separator spellings collapse to `DTS-HD
        // MA` / `DTS-HD HRA` with channels preserved; joined `HDMA`
        // keeps the family with stereo `2.0` collapsed and 5.1/7.1
        // preserved (`DTS-HDMA.2.0` -> `DTS-HD MA`).
        val dtsNorm = normaliseDtsHd(eacNorm)
        // Explicit Dolby families (v2.2 Fix C): full-phrase match on
        // the spaced form; stereo already collapsed above, multichannel
        // (5.1/6.1/7.1) preserved. `Dolby Vision` never reaches here
        // (no audio alternative matches it).
        dolbyFamilyFormat(dtsNorm.uppercase())?.let { return it }
        // Spaceless, trailing-dot-tolerant compare key so `AC 3`,
        // `AC351.` and `DDP 5` all hit their canonical branches.
        // Smashed multichannel (`DD61`, `EAC361`) is re-dotted first so
        // one dotted branch set covers every spelling.
        val key = dtsNorm.uppercase()
            .replace(REGEX_WS_RUN, "")
            .replace(REGEX_TRAIL_DOTS, "")
            .replace(
                REGEX_SMASHED_CHANNEL,
                "$1$2.$3",
            )
        return when (key) {
            "DD" -> "DD"
            "DD2.0", "DD2" -> "DD"
            "DD5.1", "DD5" -> "DD 5.1"
            "DD6.1", "DD7.1" -> key.take(2) + " " + key.drop(2)
            "DDP2.0", "DDP2", "DDP", "DD+", "DD+2.0", "DD+2" -> "E-AC-3 / DDP"
            "DDP5.1", "DDP5", "DD+5.1", "DD+5" -> "E-AC-3 / DDP 5.1"
            "DDP6.1", "DDP7.1", "DD+6.1", "DD+7.1" -> "E-AC-3 / DDP " + key.takeLast(3)
            "AC-3", "AC3" -> "AC-3"
            "AC-35.1", "AC35.1", "AC351" -> "AC-3 5.1"
            "AC-36.1", "AC36.1", "AC-37.1", "AC37.1" -> "AC-3 " + key.takeLast(3)
            "E-AC-3" -> "E-AC-3"
            "E-AC-35.1" -> "E-AC-3 5.1"
            "EAC36.1" -> "E-AC-3 6.1"
            "EAC37.1" -> "E-AC-3 7.1"
            "E-AC-36.1", "E-AC-37.1" -> "E-AC-3 " + key.takeLast(3)
            "AAC5.1", "AAC51" -> "AAC 5.1"
            "AAC6.1", "AAC61", "AAC7.1", "AAC71" -> "AAC " + key.takeLast(3)
            "FLAC6.1", "FLAC7.1" -> "FLAC " + key.takeLast(3)
            "TRUEHD6.1", "TRUEHD7.1" -> "TrueHD " + key.takeLast(3)
            "DTS" -> "DTS"
            "TRUEHD" -> "TrueHD"
            "ATMOS" -> "Atmos"
            else -> dtsNorm.uppercase()
        }
    }

    /** "DTS-HD MA"/"DTS-HD HRA" tails; stereo "2.0" drops, 5.1/7.1 stay. */
    private fun normaliseDtsHd(text: String): String = text
        .replace(REGEX_DTS_HD_SPACED) { m ->
            val ch = m.groupValues[3]
            "DTS-HD ${m.groupValues[1].uppercase()}${if (ch.isEmpty() || ch == "2.0") "" else " $ch"}"
        }
        .replace(REGEX_DTS_HDMA_JOINED) { m ->
            val ch = m.groupValues[1]
            if (ch.isEmpty() || ch == "2.0") "DTS-HD MA" else "DTS-HD MA $ch"
        }

    /** Spelled-out Dolby families ("Dolby Digital Plus 5.1"), else null. */
    private fun dolbyFamilyFormat(upSpaced: String): String? {
        val family = when {
            upSpaced.startsWith("DOLBY DIGITAL PLUS") -> "E-AC-3 / DDP"
            upSpaced.startsWith("DOLBY DIGITAL") -> "DD"
            upSpaced.startsWith("DOLBY TRUEHD") -> "TrueHD"
            else -> return null
        }
        val ch = REGEX_DOLBY_CH_TAIL.find(upSpaced.trim())?.groupValues?.get(1)
        return if (ch != null) "$family $ch.1" else family
    }

    private fun extractCodec(qualityText: String, releaseName: String): String? {
        REGEX_CODEC_BRACKETED.find(releaseName)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        REGEX_CODEC_BRACKETED.find(qualityText)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        REGEX_CODEC_BARE.find(releaseName)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        REGEX_CODEC_BARE.find(qualityText)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        return null
    }

    private fun extractBitDepth(qualityText: String, releaseName: String): String? {
        REGEX_BIT_DEPTH.find(releaseName)?.let {
            val n = normaliseBitDepth(it.value); if (n != null) return n
        }
        REGEX_BIT_DEPTH.find(qualityText)?.let {
            val n = normaliseBitDepth(it.value); if (n != null) return n
        }
        return null
    }

    private fun extractHdrVariant(qualityText: String, releaseName: String): String? {
        REGEX_HDR_PLUS.find(releaseName)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR_PLUS.find(qualityText)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10P_BARE.find(releaseName)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10P_BARE.find(qualityText)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10PLUS_BARE.find(releaseName)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10PLUS_BARE.find(qualityText)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10.find(releaseName)?.let { return normaliseHdr("hdr10") }
        REGEX_HDR10.find(qualityText)?.let { return normaliseHdr("hdr10") }
        REGEX_HDR10_BARE.find(releaseName)?.let { return normaliseHdr("hdr10") }
        REGEX_HDR10_BARE.find(qualityText)?.let { return normaliseHdr("hdr10") }
        REGEX_DV.find(releaseName)?.let { return normaliseHdr("dolby vision") }
        REGEX_DV.find(qualityText)?.let { return normaliseHdr("dolby vision") }
        REGEX_DV_BARE.find(releaseName)?.let { return normaliseHdr("dolby vision") }
        REGEX_DV_BARE.find(qualityText)?.let { return normaliseHdr("dolby vision") }
        REGEX_DOLBY_VISION_BARE.find(releaseName)?.let { return normaliseHdr("dolby vision") }
        REGEX_DOLBY_VISION_BARE.find(qualityText)?.let { return normaliseHdr("dolby vision") }
        if (REGEX_HDR_SIMPLE.containsMatchIn(releaseName) ||
            REGEX_HDR_SIMPLE.containsMatchIn(qualityText) ||
            REGEX_HDR_BARE.containsMatchIn(releaseName) ||
            REGEX_HDR_BARE.containsMatchIn(qualityText)) {
            if (!REGEX_HDR10.containsMatchIn(releaseName) &&
                !REGEX_HDR10.containsMatchIn(qualityText) &&
                !REGEX_HDR_PLUS.containsMatchIn(releaseName) &&
                !REGEX_HDR_PLUS.containsMatchIn(qualityText) &&
                !REGEX_HDR10P_BARE.containsMatchIn(releaseName) &&
                !REGEX_HDR10P_BARE.containsMatchIn(qualityText) &&
                !REGEX_HDR10PLUS_BARE.containsMatchIn(releaseName) &&
                !REGEX_HDR10PLUS_BARE.containsMatchIn(qualityText) &&
                !REGEX_HDR10_BARE.containsMatchIn(releaseName) &&
                !REGEX_HDR10_BARE.containsMatchIn(qualityText) &&
                !REGEX_DV.containsMatchIn(releaseName) &&
                !REGEX_DV.containsMatchIn(qualityText) &&
                !REGEX_DV_BARE.containsMatchIn(releaseName) &&
                !REGEX_DV_BARE.containsMatchIn(qualityText) &&
                !REGEX_DOLBY_VISION_BARE.containsMatchIn(releaseName) &&
                !REGEX_DOLBY_VISION_BARE.containsMatchIn(qualityText)) {
                return "HDR"
            }
        }
        return null
    }

    // ---- Clean display title ------------------------------------

    /**
     * Delimiter class shared by aggressive clean-title stripping: a
     * technical token counts as standalone when bounded by
     * start/end, whitespace, dots, underscores, hyphens, brackets,
     * parens or commas. Letters/digits on either side protect real
     * words (`AVC1`, `SAV1`, `2160p60`, `PlumbingSubs` never match).
     */
    private const val CLEAN_DELIM_CLASS = """[\s._\-()\[\],]"""

    /**
     * Bare-`BD` source-type detector. Strictly
     * delimited: start/end, whitespace, dots, underscores, hyphens,
     * brackets, parens or commas on BOTH sides, so `Abducted`,
     * `Bdubs` and `BDRip` never match while `S1 - BD (1080p)`,
     * `[BD]` and trailing `BD` do.
     */
    private val SOURCE_CLASS_BD_DELIMITED =
        Regex("""(^|$CLEAN_DELIM_CLASS)BD(${"$"}|$CLEAN_DELIM_CLASS)""", RegexOption.IGNORE_CASE)

    // ---- precompiled hot-path patterns --------------------------
    // Textually identical to the inline `Regex(...)` literals they
    // replace; hoisting only removes per-bind `Pattern.compile` work
    // (host-measured: `cleanDisplayTitle` dominated the bind at ~2.3ms
    // on a checksum row, `parseRelease` ~0.9ms). No matching semantics
    // change. Only static patterns hoist; runtime-interpolated ones
    // (release group, service alias, subtitle languages) stay local,
    // except through the bounded [delimitedPattern] cache below.
    private val REGEX_WS_RUN = Regex("""\s+""")
    private val REGEX_WS_DOT = Regex("""[\s.]""")
    private val REGEX_WS_SINGLE = Regex("""\s""")
    /**
     * A source's language line: " / "-separated audio/sub words and flag
     * emoji, e.g. "Dubbed / Multi Subs / Dual Audio / 🇬🇧 / 🇷🇺". Its flags are
     * read from the whole text and its audio words are fed to audio
     * detection separately, so it is never part of the release name.
     */
    fun isLanguageLine(line: String): Boolean {
        val parts = line.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { p -> p.matches(REGEX_LANG_LINE_WORD) || p.all { c -> !c.isLetterOrDigit() } }
    }
    private val REGEX_LANG_LINE_WORD = Regex(
        """(?i)(dubbed|dual audio|multi audio|multi subs?|subbed|subs?|raw)"""
    )
    /** Audio/sub words of the language line(s), or "" when there is none. */
    fun languageLineText(qualityText: String): String =
        qualityText.lines().map { it.trim() }.filter { it.isNotEmpty() && isLanguageLine(it) }
            .joinToString(" ")
    private val REGEX_PROVIDER_QUALITY_LINE =
        Regex("""^[A-Za-z0-9][\w.+-]{1,24} - (?:4k|\d{3,4}p|sd|hd|unknown)\b[^\[\]]*$""", RegexOption.IGNORE_CASE)
    private val REGEX_METADATA_MARKER_LINE =
        Regex("""^(seeders?|size|indexer|provider|language|audio)\s*[:=].+""", RegexOption.IGNORE_CASE)
    private val REGEX_LANG_PAIR_TOKEN = Regex("""[A-Za-z]{2,3}-[A-Za-z0-9]{2,4}""")
    private val REGEX_EPISODE_SHAPE_TOKEN = Regex("""(?i)^([se]?\d+)+[a-z]?$""")
    private val REGEX_VERSION_SHAPE_TOKEN = Regex("""(?i)^v\d+[a-z]?$""")
    private val REGEX_RES_SHAPE_TOKEN = Regex("""(?i)^\d+p$""")
    private val REGEX_TITLE_BRACKET = Regex("""\[([^\]]+)]""")

    /** `[Group]` right before a file name ending in a video extension, with no bracket after it. */
    private val REGEX_FILE_NAME_BRACKET =
        Regex("""(?:^|/|]\s*)\[([^\]]+)]\s*[^\[\]/]+\.(?:mkv|mp4|avi|m2ts|webm)\s*$""", RegexOption.IGNORE_CASE)

    private fun fileNameBracketGroup(qualityText: String): String? {
        for (rawLine in qualityText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || isMetadataMarkerLine(line)) continue
            val token = REGEX_FILE_NAME_BRACKET.find(line)?.groupValues?.get(1)?.trim() ?: continue
            if (isGroupCandidate(token) && '.' !in token) return token
        }
        return null
    }
    private val REGEX_TECH_DOT_RES = Regex("""(?i)^(\d{3,4}p|4k|2k|8k)$""")
    private val REGEX_TECH_DOT_CODEC = Regex("""(?i)^((x|h)\.?26[45]|hevc|avc|av1|vp9|xvid|divx)$""")
    private val REGEX_TECH_DOT_AUDIO =
        Regex("""(?i)^(aac|ac-?3|dts|e-?ac-?3|ddp|flac|opus|mp3|truehd|atmos)[\d.]*$""")
    private val REGEX_TECH_DOT_DEPTH_HDR =
        Regex("""(?i)^((8|10)-?bits?|hi10p|hdr\d*\+?|dolby(vision)?|dv)$""")
    private val REGEX_TECH_DOT_CLASS =
        Regex("""(?i)^(web-?dl|webrip|bluray|bdrip|hdtv|dvdrip|remux)$""")
    private val REGEX_TECH_DOT_SUBDUB =
        Regex("""(?i)^(subs?|subbed|subtitles?|dubs?|dubbed|dual|multi|audio)$""")
    private val REGEX_SERVICE_BRACKET_TOKEN = Regex("""\[([A-Za-z0-9\-]+)]""")
    private val SOURCE_SERVICE_STANDALONE: Regex by lazy {
        val aliases = SOURCE_SERVICE_ALIASES.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex(
            """(?:^|[\s.()\[\]])(?:$aliases)(?:[\s.()\[\]]|$)""",
            RegexOption.IGNORE_CASE,
        )
    }
    private val REGEX_CHANNEL_TAIL_TRIM = Regex("""\s+\d\.\d$""")
    private val REGEX_CHAN_NORM =
        Regex("""(?i)\b(DDP?|DD\+|AC-?3|E-?A-?C-?3|FLAC|AAC|OPUS|MP3|PCM|LPCM)\s*[._\-]+\s*(\d)""")
    private val REGEX_EAC_PREFIX = Regex("""(?i)^E-?A-?C-?3\b""")
    private val REGEX_LETTERS_DIGIT_GLUE = Regex("""^([A-Za-z]+)(\d)""")
    private val REGEX_STEREO_TAIL = Regex("""\s+2\.0$""")
    private val REGEX_DTS_HD_SPACED =
        Regex("""(?i)^DTS[\s.\-_]+HD[\s.\-_]+(MA|HRA)([\s.]+(\d\.\d))?$""")
    private val REGEX_DTS_HDMA_JOINED =
        Regex("""(?i)^DTS[\s.\-_]*HDMA(?:[\s.]+(\d\.\d))?$""")
    private val REGEX_DOLBY_CH_TAIL = Regex("""([567])\.1$""")
    private val REGEX_TRAIL_DOTS = Regex("""\.+$""")
    private val REGEX_SMASHED_CHANNEL =
        Regex("""^((?:DDP?|DD\+|AC-?3|E-?A-?C-?3|AAC|FLAC|TRUEHD))(\d)(\d)$""")
    private val CLEAN_PAREN_RES =
        Regex("""\(\s*(?:\d{3,4}p|4k|2160p)\s*\)""", RegexOption.IGNORE_CASE)
    private val CLEAN_PAREN_HDR =
        Regex("""\(\s*(?:HDR10\+|HDR10PLUS|HDR10|HDR|DV|DOLBY\s+VISION)\s*\)""", RegexOption.IGNORE_CASE)
    private val CLEAN_BATCH_BRACKET =
        Regex("""\[(?:batch|complete\b[^\]]*)\]""", RegexOption.IGNORE_CASE)
    private val CLEAN_LEADING_TECH =
        Regex(
            """^(?:\s*(?:4k|2160p|1080p|720p|480p|1440p|hdr10\+|hdr10plus|hdr10|hdr|dv)\b)+""",
            RegexOption.IGNORE_CASE,
        )
    private val CLEAN_AUDIO_MARKER = Regex(
        """\[\s*audio(?:[\s._\-]*(?:\d\.\d))?\s*]""" +
            """|(?:^|$CLEAN_DELIM_CLASS)audio[\s._\-]+\d\.\d(?=${'$'}|$CLEAN_DELIM_CLASS)""",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAN_LANG_CLUSTER = Regex(
        """(^|$CLEAN_DELIM_CLASS)(?:[A-Z]{2,4}(?:\+[A-Z]{2,4})+)(${'$'}|$CLEAN_DELIM_CLASS)""",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAN_PACK_PHRASE = Regex(
        """(^|$CLEAN_DELIM_CLASS)(?:complete[\s._\-]+(?:series|packs?|collections?|sets?|movies?|box[\s._\-]*sets?)(?:[\s._\-]*\+[\s._\-]*\w+)?)(${'$'}|$CLEAN_DELIM_CLASS)""",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAN_WXH = Regex(
        """(^|$CLEAN_DELIM_CLASS)(?:\d{3,4}x\d{3,4})(${'$'}|$CLEAN_DELIM_CLASS)""",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAN_TRAILING_TAG = Regex(
        """\[([A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)?)\]\s*${'$'}""",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAN_VERSION_TAG = Regex("""^[vV]\d+$""")
    private val CLEAN_FPS_PAREN = Regex(
        """\(\s*\d+(?:\.\d+)?\s*FPS\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val CLEAN_CHAPTER_BRACKET = Regex("""\[\s*chapters?\s*]""", RegexOption.IGNORE_CASE)
    private val CLEAN_EMPTY_BRACKET = Regex("""\[\s*]""")
    private val CLEAN_EMPTY_PAREN = Regex("""\(\s*\)""")
    private val CLEAN_DOT_SEP = Regex("""(?<=\d)\.(?!\d)|(?<=[^\d\s])\.(?=\S)""")
    private val CLEAN_DASH_RUN = Regex("""\s*[\-–—](?:\s*[\-–—])+\s*""")
    private val CLEAN_EDGE_LEAD = Regex("""^[\-–—,\s]+""")
    private val CLEAN_DOT_LEAD = Regex("""^\.\s+""")
    private val CLEAN_EDGE_TRAIL = Regex("""[\-–—,\s]+${'$'}""")
    private val CLEAN_DIGIT_DOT_TRAIL = Regex("""(?<=\d)\.\s*${'$'}""")
    private val CLEAN_DOT_TRAIL = Regex("""\s+\.${'$'}""")

    /**
     * Bounded compiled-pattern cache for [delimitedPattern] bodies
     *. Every strip helper funnels through
     * [delimitedPattern] with runtime-built bodies (spellings,
     * variants, channel tails), so without a cache each of the
     * dozens of strip calls per clean compiles its own `Pattern`
     * on every pass. The cache maps body text to the compiled
     * pattern; identical bodies (the closed per-row vocabulary)
     * compile once.
     *
     * Bound: hard cap of 512 entries with best-effort eviction
     * (clear on overflow) — never unbounded. Thread safety via
     * `ConcurrentHashMap`: concurrent misses only recompile the
     * same equal pattern, so results stay deterministic. Pure
     * speed optimization: a miss behaves exactly like the uncached path.
     */
    private val delimitedPatternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * Raw (un-normalized) spellings behind one normalized audio
     * format, longest first. The parser normalizes (`DDP2.0` ->
     * `E-AC-3 / DDP`) but titles print raw forms, so stripping must
     * try raw spellings; channel tails are handled by the compound
     * pattern in [stripAudioFormatToken].
     *
     * Single source of truth with [AUDIO_FORMAT_KNOWN_BASES] /
     * [ALL_AUDIO_FORMAT_SPELLINGS]: every normalized base the parser
     * can emit (see `normaliseAudioFormat` branches + fallthrough
     * families) has a branch here, and the clean-title universe is
     * derived from this table — never a second hand-maintained list —
     * so parser and cleaner cannot silently drift again (
     * `PCM` / `LPCM` / `MP2` were missing from the universe while the
     * parser emits them first-class).
     */
    private fun audioFormatRawSpellings(format: String): List<String> {
        val base = format.uppercase().replace(REGEX_CHANNEL_TAIL_TRIM, "").trim()
        val raws = when (base) {
            "E-AC-3 / DDP" -> listOf(
                "DOLBY DIGITAL PLUS", "E-AC-3", "EAC3", "DDP", "DD+",
            )
            "DD" -> listOf("DOLBY DIGITAL", "DD")
            "AC-3" -> listOf("AC-3", "AC3")
            "E-AC-3" -> listOf("E-AC-3", "EAC3")
            "AAC" -> listOf("AAC")
            "FLAC" -> listOf("FLAC")
            "TRUEHD" -> listOf("DOLBY TRUEHD", "TRUE-HD", "TRUEHD", "THD")
            "DTS-HD MA", "DTS-HD HRA" -> listOf(
                "DTS-HD MA", "DTS-HD HRA", "DTS-HDMA", "DTSHDMA",
                "DTS-HD", "DTSHD", "DTS",
            )
            "DTS" -> listOf("DTS")
            "ATMOS" -> listOf("DOLBY ATMOS", "ATMOS")
            "MP3" -> listOf("MP3")
            "OPUS" -> listOf("OPUS")
            "MP2" -> listOf("MP2")
            "LPCM" -> listOf("LPCM")
            "PCM" -> listOf("PCM")
            else -> listOf(format.trim())
        }
        return raws.sortedByDescending { it.length }
    }

    /** Raw spellings behind one normalized codec (empty when none). */
    private fun codecRawSpellings(codec: String): List<String> = when (codec.uppercase()) {
        "HEVC" -> listOf("X.265", "H.265", "X265", "H265")
        "AVC" -> listOf("X.264", "H.264", "X264", "H264")
        else -> emptyList()
    }

    /**
     * Separator variants of one token (`10-bit` -> `10bit`,
     * `10 bit`, `10-bit`; `Dual Audio` -> `DUALAUDIO`,
     * `DUAL-AUDIO`, ...), so unbracketed titles print in any
     * separator style and still strip.
     */
    private fun tokenSeparatorVariants(token: String): Set<String> {
        val t = token.trim()
        if (t.isEmpty()) return emptySet()
        return setOf(
            t,
            t.replace(REGEX_WS_RUN, ""),
            t.replace(REGEX_WS_RUN, "-"),
            t.replace(REGEX_WS_RUN, "_"),
            t.replace("-", ""),
            t.replace("-", " "),
        ).filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Keep a consumed paren/bracket boundary, drop anything else.
     * Shared by every delimited replacement below.
     */
    private fun keepBracketBoundary(s: String): String =
        if (s == "(" || s == "[") s
        else if (s == ")" || s == "]") s
        else " "

    /**
     * Replace a [MatchResult] of a delimited pattern (groups 1+2 are
     * the boundaries) keeping paren/bracket edges.
     */
    private fun keptBoundaries(m: MatchResult): String =
        keepBracketBoundary(m.groupValues[1]) + keepBracketBoundary(m.groupValues[2])

    /**
     * Delimited pattern with capturable boundaries around [body].
     * [leadExtra] appends an alternative leading shape (used for the
     * digit-glued subtitle tags). Compiled patterns are held in the
     * bounded [delimitedPatternCache]; a miss compiles exactly the
     * plain pattern, so behavior is unchanged.
     */
    private fun delimitedPattern(body: String, leadExtra: String = ""): Regex {
        val key = if (leadExtra.isEmpty()) body else leadExtra + "\n" + body
        return delimitedPatternCache.getOrPut(key) {
            Regex(
                "(^|" + CLEAN_DELIM_CLASS + leadExtra + ")(?:" + body + ")(\$|" + CLEAN_DELIM_CLASS + ")",
                RegexOption.IGNORE_CASE,
            )
        }.also {
            if (delimitedPatternCache.size > 512) delimitedPatternCache.clear()
        }
    }

    /**
     * Core delimited strip: [body] matches with boundary sharing, but
     * consumed paren/bracket edges are preserved, so stripping `480p`
     * from `(480p AAC)` leaves `( AAC)` (which later collapses to
     * nothing) instead of stranding `)`. Spaces/dots stay consumed so
     * adjacent tokens (`HEVC 10-bit`) keep stripping in one pass.
     */
    private fun stripDelimitedBody(text: String, body: String, leadExtra: String = ""): String =
        delimitedPattern(body, leadExtra).replace(text, ::keptBoundaries)

    /**
     * Remove every delimited occurrence of [token] (case-insensitive).
     * Returns the input unchanged when nothing matches.
     */
    private fun stripDelimitedToken(text: String, token: String): String {
        if (token.isBlank()) return text
        return stripDelimitedBody(text, Regex.escape(token.trim()))
    }

    /**
     * Every known codec spelling (normalized + raw), for universe
     * stripping when ANY codec was parsed. Delimited boundaries keep
     * `AVC1` / `2160p60`-style compounds safe.
     */
    private val ALL_CODEC_SPELLINGS = listOf(
        "HEVC", "X265", "H265", "X.265", "H.265",
        "AVC", "X264", "H264", "X.264", "H.264",
        "AV1", "VP9", "XVID", "DIVX",
    )

    /**
     * Closed subtitle-marker word set for aggressive stripping. The
     * full English word `SUBTITLES` is deliberately excluded: `X with
     * Subtitles` reads as title content , while the
     * abbreviated/marker forms below are unambiguous release
     * metadata. Bare `SUB` and lone language codes are excluded too.
     */
    private val SUBTITLE_MARKER_WORDS = listOf(
        "MULTI SUBS", "MULTI SUB", "SUBBED", "SUBS",
        "SUBTITULADO", "VOSTFR", "NAPISY PL", "NAPISY", "LEGENDADO",
    )

    /**
     * Bracketed subtitle-file formats (`[TTML]`, `[ASS]`). Stripped
     * only when subs were parsed; unbracketed uses are left alone.
     */
    private val SUBTITLE_FORMAT_BRACKETS = listOf(
        "SRT", "ASS", "SSA", "PGS", "SUP", "VTT", "TTML", "SMI", "IDX",
    )

    /**
     * Compound source-class tokens. Bare `WEB` is handled separately
     * (resolution context required) since it can be a title word.
     */
    private val SOURCE_CLASS_STRIP_TOKENS = listOf(
        "WEB-DL", "WEBDL", "WEBRip", "WEB-RIP", "BLURAY", "BLU-RAY",
        "BDRIP", "DVDRIP", "HDTV", "PDTV", "SDTV", "TVRIP", "REMUX",
        "DVD", "BD",
    )

    /**
     * Bounded normalized audio-format bases the parser can emit (see
     * `normaliseAudioFormat` branches + fallthrough families).
     * Together with [audioFormatRawSpellings] this is the one source
     * of truth the clean-title universe is derived from.
     */
    private val AUDIO_FORMAT_KNOWN_BASES = listOf(
        "E-AC-3 / DDP", "DD", "AC-3", "E-AC-3", "AAC", "FLAC",
        "TRUEHD", "DTS-HD MA", "DTS-HD HRA", "DTS", "ATMOS",
        "MP3", "OPUS", "MP2", "LPCM", "PCM",
    )

    /**
     * Every raw audio spelling in the universe, longest first. Derived
     * from [audioFormatRawSpellings] over [AUDIO_FORMAT_KNOWN_BASES] —
     * never hand-maintained separately. Used when ANY audio format was
     * parsed: format tokens are never title content, and multi-format
     * rows (`TrueHD … FLAC`) must strip every one.
     */
    private val ALL_AUDIO_FORMAT_SPELLINGS: List<String> by lazy {
        AUDIO_FORMAT_KNOWN_BASES
            .flatMap { audioFormatRawSpellings(it) }
            .distinct()
            .sortedByDescending { it.length }
    }
    /**
     * Remove delimited occurrences of [token] where a digit may also
     * lead (episode-glued subtitle tags like `E01VOSTFR`). Used only
     * for the closed subtitle-marker set, only when subs were parsed.
     */
    private fun stripDelimitedSubWord(text: String, token: String): String {
        if (token.isBlank()) return text
        return stripDelimitedBody(text, Regex.escape(token.trim()), "|(?<=\\d)")
    }

    /**
     * Remove delimited occurrences of [token] EXCEPT a leading one: a
     * bare `Dual`/`Dub`/`Dubbed` as the title's first token may be the
     * title's own first word, so only non-first occurrences strip.
     * Paren/bracket edges are preserved like everywhere else.
     */
    private fun stripDelimitedTokenNonLeading(text: String, token: String): String {
        if (token.isBlank()) return text
        val body = Regex.escape(token.trim())
        val first = delimitedPattern(body).find(text) ?: return text
        if (text.substring(0, first.range.first).isNotBlank()) {
            return stripDelimitedBody(text, body)
        }
        val head = text.substring(0, first.range.last + 1)
        val tail = text.substring(first.range.last + 1)
        return head + stripDelimitedBody(tail, body)
    }

    /**
     * Remove one raw audio-format spelling plus an optional glued or
     * spaced channel tail (`FLAC`, `TrueHD 7.1`, `AAC 2.0`, `DDP5.1`,
     * `PCM20`, broken `DDP2 0`). The tail only ever attaches directly
     * to the format — and a following digit blocks the match, so years
     * (`FLAC 2024`) can never be eaten — mirroring the parser's own
     * smashed-channel key expansion (`DD61`, `EAC361`, `PCM51`).
     */
    private fun stripAudioFormatToken(text: String, spelling: String): String {
        if (spelling.isBlank()) return text
        val body = Regex.escape(spelling.trim()) +
            """(?:[\s._\-]*(?:[567]\.1|2\.0|1\.0|\d{1,2}(?:\s+\d{1,2})?))?"""
        return stripDelimitedBody(text, body)
    }

    /**
     * Produce a cleaned display title that removes tokens already
     * confidently represented elsewhere (quality, codec, bit depth,
     * source service, release group). The exact raw title is preserved
     * separately for identity/selection.
     *
     * Aggressive rules (all UI-only, raw preserved): bracketed HDR
     * variant, bracketed audio-mode/format duplicates, batch markers,
     * checksum brackets, container extension, parenthesized resolution
     * and leading HDR-ish tokens are also removed — but only from
     * confidently parsed values passed in by the caller. Bare
     * audio-mode/language words mid-title are never touched, and
     * season/episode identity is preserved.
     *
     * The same gate extends to UNBRACKETED tokens: resolution
     * (+`4K` alias), codec raw spellings (`x264`/`x265`/`H.264`),
     * bit-depth separator forms, HDR variants, audio-format raw
     * spellings with channel tails (`TrueHD 7.1`, `AAC 2.0`,
     * `DDP5.1`), audio-mode words (phrases anywhere; bare
     * `Dual`/`Dub`/`Dubbed`/`Multidub` never in first-token position),
     * subtitle markers incl. episode-glued `E01VOSTFR` and `ENG+JPN`
     * clusters, `Batch` + complete-pack phrases, dotted/underscored
     * titles re-spaced, and a trailing `-GROUP` matching the header.
     * Season/episode tokens (`S01`/`E01`/`EP01`/decimals like `5.1`)
     * are never stripped.
     */
    fun cleanDisplayTitle(
        releaseName: String,
        releaseGroup: String?,
        resolution: String?,
        codec: String?,
        bitDepth: String?,
        sourceService: String?,
        hdrVariant: String? = null,
        audioSummary: String? = null,
        batch: Boolean = false,
        audioFormat: String? = null,
        subtitleSummary: String? = null,
        subtitleLanguages: List<String> = emptyList(),
        sourceClass: String? = null,
    ): String {
        val spec = CleanSpec(
            releaseGroup = releaseGroup,
            resolution = resolution,
            codec = codec,
            bitDepth = bitDepth,
            sourceService = sourceService,
            hdrVariant = hdrVariant,
            audioSummary = audioSummary,
            batch = batch,
            audioFormat = audioFormat,
            subtitleSummary = subtitleSummary,
            subtitleLanguages = subtitleLanguages,
            sourceClass = sourceClass,
        )
        // Up to 8 passes; a pass is a pure function of the text, so a pass
        // that changes nothing is a fixed point and the rest are skipped.
        var cleaned = releaseName
        for (pass in 0 until 8) {
            val next = cleanPass(cleaned, spec)
            if (next == cleaned) break
            cleaned = next
        }
        return finishCleanTitle(cleaned, hdrVariant)
    }

    /** Everything one clean pass needs, built once per [cleanDisplayTitle] call. */
    private class CleanSpec(
        val releaseGroup: String?,
        val resolution: String?,
        val codec: String?,
        val bitDepth: String?,
        val sourceService: String?,
        val hdrVariant: String?,
        val audioSummary: String?,
        val batch: Boolean,
        val audioFormat: String?,
        val subtitleSummary: String?,
        val subtitleLanguages: List<String>,
        val sourceClass: String?,
    ) {
        val hasSubs = subtitleSummary != null || subtitleLanguages.isNotEmpty()
        val bracketPatterns: List<Regex> = removableBrackets(this).map(::bracketTokenPattern)
        val delimitedValues: Set<String> = delimitedValues(this)
        val groupPattern = releaseGroup?.let {
            Regex("""(?:^|\s)\[\Q$it\E]\s*""", RegexOption.IGNORE_CASE)
        }
        val leadingGroupPattern = releaseGroup?.let {
            Regex("""^\[\Q$it\E]\s+""", RegexOption.IGNORE_CASE)
        }
        val suffixGroupPattern = releaseGroup?.let {
            // also before trailing notes: "x264-Grpdere-Raws (VOSTFR, ...)"
            Regex(
                """[\s._\-]+""" + Regex.escape(it) +
                    """(?=\s*(?:[\[(][^\[\]()]*[\])]\s*)*${'$'})""",
                RegexOption.IGNORE_CASE,
            )
        }
        // The ALIAS KEY (NF / AMZN / CR) is what sources emit; sourceService
        // is the expanded form (Netflix / Amazon / Crunchyroll).
        val sourceServicePattern = sourceService?.let { service ->
            val alias = SOURCE_SERVICE_ALIASES.entries.firstOrNull { it.value == service }?.key ?: service
            Regex(
                """(^|$CLEAN_DELIM_CLASS)\Q$alias\E(${'$'}|$CLEAN_DELIM_CLASS)""",
                RegexOption.IGNORE_CASE,
            )
        }
    }

    /** Extra spellings of an HDR variant ("HDR10+" also matches "HDR10PLUS"). */
    private fun hdrAliases(variant: String, delimited: Boolean): List<String> = when (variant) {
        "HDR10+" -> if (delimited) {
            listOf("HDR10PLUS", "HDR10 PLUS", "HDR10-PLUS", "HDR10_PLUS")
        } else {
            listOf("HDR10PLUS", "HDR10 PLUS")
        }
        "Dolby Vision" -> listOf("DOLBY VISION", "DOLBYVISION", "DV")
        "HDR10" -> listOf("HDR10")
        "HDR" -> listOf("HDR")
        else -> emptyList()
    }

    /** Technical tokens whose bracketed form ("[HEVC]") is removed. */
    private fun removableBrackets(s: CleanSpec): Set<String> {
        val tokens = linkedSetOf<String>()
        listOfNotNull(s.codec, s.bitDepth, s.resolution).forEach { tokens.add(it) }
        s.hdrVariant?.let { variant ->
            tokens.add(variant)
            tokens.addAll(hdrAliases(variant, delimited = false))
        }
        // Bracketed audio-mode duplicates (e.g. "[Dual Audio]"); bare
        // language codes are protected.
        s.audioSummary?.let { summary ->
            val upper = summary.uppercase()
            val bareCode = upper.length in 2..4 && upper.all { it.isLetter() }
            if (!bareCode) tokens.add(summary)
        }
        if (s.batch) {
            tokens.add("BATCH")
            tokens.add("COMPLETE")
        }
        // The source-service ALIAS KEY (NF, AMZN, CR) is the form in titles.
        s.sourceService?.let { service ->
            SOURCE_SERVICE_ALIASES.entries.firstOrNull { it.value == service }?.key?.let { tokens.add(it) }
        }
        return tokens
    }

    /** A bracketed token as-is, without hyphens ("[10bit]") or without spaces. */
    private fun bracketTokenPattern(token: String): Regex {
        val noHyphen = token.replace("-", "")
        val noSpace = token.replace(REGEX_WS_RUN, "")
        return Regex(
            """\[(?:\Q$token\E|\Q$noHyphen\E|\Q$noSpace\E)\]""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** Parsed pill values stripped in any separator style. */
    private fun delimitedValues(s: CleanSpec): Set<String> {
        val values = linkedSetOf<String>()
        s.resolution?.let {
            values.add(it)
            if (it.equals("2160p", ignoreCase = true)) values.add("4K")
        }
        s.bitDepth?.let { values.addAll(tokenSeparatorVariants(it)) }
        s.hdrVariant?.let { variant ->
            values.add(variant)
            values.addAll(hdrAliases(variant, delimited = true))
            values.addAll(tokenSeparatorVariants(variant))
        }
        return values
    }

    /** One clean pass: each step strips one kind of duplicate or tag. */
    private fun cleanPass(text: String, s: CleanSpec): String {
        var t = stripBracketTokens(text, s)
        t = stripGroupAndLeadingTech(t, s)
        t = s.sourceServicePattern?.replace(t, ::keptBoundaries) ?: t
        t = stripCodecSpellings(t, s)
        t = stripDelimitedValues(t, s)
        t = stripAudioFormats(t, s)
        t = stripAudioModes(t, s)
        t = stripSubtitleMarkers(t, s)
        t = stripBatchPhrases(t, s)
        t = stripSourceClass(t, s)
        t = stripResolutionExtras(t, s)
        t = stripFpsChapterAndSubFormats(t, s)
        return s.suffixGroupPattern?.replace(t, "") ?: t
    }

    private fun stripBracketTokens(text: String, s: CleanSpec): String {
        var t = text
        for (pattern in s.bracketPatterns) t = pattern.replace(t, "")
        // Checksum/CRC brackets ("[AAA94036]"), then a container extension.
        t = REGEX_CHECKSUM_BRACKET.replace(t, "")
        t = REGEX_STRIP_MEDIA_EXTENSION.replace(t, "")
        // Parenthesized resolution ("(1080p)") and HDR markers ("(HDR)").
        t = CLEAN_PAREN_RES.replace(t, "")
        if (s.hdrVariant != null) t = CLEAN_PAREN_HDR.replace(t, "")
        if (s.batch) t = CLEAN_BATCH_BRACKET.replace(t, "")
        return t
    }

    /** "[GROUP]" shown in the header, and leading "1080p"/"4k"/"HDR" tokens. */
    private fun stripGroupAndLeadingTech(text: String, s: CleanSpec): String {
        var t = s.groupPattern?.replace(text, " ") ?: text
        t = CLEAN_LEADING_TECH.replace(t, "")
        return s.leadingGroupPattern?.replace(t, "") ?: t
    }

    /**
     * Codec spellings: the parsed value, its raw alternates and every known
     * spelling, each with an optional glued letters-only scene suffix
     * ("x264-PQW"), so "S01E01"/"10bit" can never be eaten.
     */
    private fun stripCodecSpellings(text: String, s: CleanSpec): String {
        val codec = s.codec ?: return text
        var t = text
        val spellings = (listOf(codec) + codecRawSpellings(codec) + ALL_CODEC_SPELLINGS).distinct()
        for (spelling in spellings) {
            for (variant in tokenSeparatorVariants(spelling)) {
                t = stripDelimitedBody(t, Regex.escape(variant) + """(?:-(?:[A-Za-z]{2,6}))?""")
            }
        }
        return t
    }

    private fun stripDelimitedValues(text: String, s: CleanSpec): String {
        var t = text
        for (token in s.delimitedValues) {
            for (variant in tokenSeparatorVariants(token)) t = stripDelimitedToken(t, variant)
        }
        return t
    }

    /**
     * Audio-format spellings with optional channel tails ("TrueHD 7.1",
     * "AAC 2.0") and generic "[Audio 5.1]" markers, once any format parsed.
     */
    private fun stripAudioFormats(text: String, s: CleanSpec): String {
        if (s.audioFormat == null) return text
        var t = text
        for (spelling in ALL_AUDIO_FORMAT_SPELLINGS) t = stripAudioFormatToken(t, spelling)
        return CLEAN_AUDIO_MARKER.replace(t, " ")
    }

    /**
     * Dual/Dub duplicates: phrases strip anywhere; bare words never in first
     * position (a leading Dual/Dub may be the title's own first word).
     */
    private fun stripAudioModes(text: String, s: CleanSpec): String {
        var t = text
        if (s.audioSummary.equals("Dual", ignoreCase = true)) {
            for (phrase in listOf("DUAL AUDIO", "MULTI AUDIO")) {
                for (variant in tokenSeparatorVariants(phrase)) t = stripDelimitedToken(t, variant)
            }
            for (bare in listOf("MULTIDUB", "DUAL")) t = stripDelimitedTokenNonLeading(t, bare)
            // Bare MULTI is audio only when no subtitle signal claims it.
            if (!s.hasSubs) t = stripDelimitedTokenNonLeading(t, "MULTI")
        }
        if (s.audioSummary.equals("Dub", ignoreCase = true)) {
            for (bare in listOf("DUBBED", "DUB")) t = stripDelimitedTokenNonLeading(t, bare)
        }
        return t
    }

    /** Subtitle marker words, "ENG+JPN" clusters and bracketed language codes. */
    private fun stripSubtitleMarkers(text: String, s: CleanSpec): String {
        if (!s.hasSubs) return text
        var t = text
        for (word in SUBTITLE_MARKER_WORDS) {
            for (variant in tokenSeparatorVariants(word)) t = stripDelimitedSubWord(t, variant)
        }
        t = CLEAN_LANG_CLUSTER.replace(t, ::keptBoundaries)
        for (lang in s.subtitleLanguages) {
            if (lang.isBlank()) continue
            t = Regex("""\[\s*""" + Regex.escape(lang.trim()) + """\s*]""", RegexOption.IGNORE_CASE)
                .replace(t, " ")
        }
        return t
    }

    /** Unbracketed "Batch" and full pack phrases; a bare "Complete" stays. */
    private fun stripBatchPhrases(text: String, s: CleanSpec): String {
        if (!s.batch) return text
        val t = stripDelimitedToken(text, "Batch")
        return CLEAN_PACK_PHRASE.replace(t, ::keptBoundaries)
    }

    /** "BluRay", "REMUX", "WEB-DL" before the bare "WEB" rule, so "WEB-DL" goes whole. */
    private fun stripSourceClass(text: String, s: CleanSpec): String {
        if (s.sourceClass == null) return text
        var t = text
        for (token in SOURCE_CLASS_STRIP_TOKENS) {
            for (variant in tokenSeparatorVariants(token)) t = stripDelimitedToken(t, variant)
        }
        return t
    }

    /** "1920x1080", bare "WEB", and an unparsed trailing group tag, with a resolution only. */
    private fun stripResolutionExtras(text: String, s: CleanSpec): String {
        if (s.resolution == null) return text
        var t = CLEAN_WXH.replace(text, ::keptBoundaries)
        t = stripDelimitedToken(t, "WEB")
        return if (s.releaseGroup == null) stripUnparsedTrailingTag(t) else t
    }

    /**
     * A trailing group-shape bracket ("[Gira-Fansub]") when no group was
     * parsed. Episode, season, version, checksum and language shapes stay.
     */
    private fun stripUnparsedTrailingTag(text: String): String {
        val tag = CLEAN_TRAILING_TAG.find(text)?.groupValues?.get(1) ?: return text
        val keep = REGEX_SEASON_EPISODE_BRACKET.matches(tag) ||
            CLEAN_VERSION_TAG.matches(tag) ||
            KNOWN_LANG_CODES.any { it.equals(tag.uppercase(), ignoreCase = true) } ||
            REGEX_CHECKSUM_BRACKET.containsMatchIn("[$tag]")
        return if (keep) text else CLEAN_TRAILING_TAG.replace(text, " ")
    }

    /** "(23.976FPS)", "[Chapter]", and subtitle-file brackets when subs parsed. */
    private fun stripFpsChapterAndSubFormats(text: String, s: CleanSpec): String {
        var t = CLEAN_FPS_PAREN.replace(text, " ")
        t = CLEAN_CHAPTER_BRACKET.replace(t, " ")
        if (!s.hasSubs) return t
        for (fmt in SUBTITLE_FORMAT_BRACKETS) {
            t = Regex("""\[\s*""" + Regex.escape(fmt) + """\s*]""", RegexOption.IGNORE_CASE).replace(t, " ")
        }
        return t
    }

    /**
     * Final cleanup: empty brackets, dots re-spaced (decimals and glued
     * letter-dots stay), underscores, dash runs, stray edge separators, then
     * leftover tag words, HDR spellings and French/multi language notes.
     */
    private fun finishCleanTitle(cleaned: String, hdrVariant: String?): String {
        val tidy = cleaned
            .replace(CLEAN_EMPTY_BRACKET, "")
            .replace(CLEAN_EMPTY_PAREN, "")
            .replace(CLEAN_DOT_SEP, " ")
            .replace("_", " ")
            .replace(CLEAN_DASH_RUN, " ")
            .replace(REGEX_WS_RUN, " ")
            .replace(CLEAN_EDGE_LEAD, "")
            .replace(CLEAN_DOT_LEAD, "")
            .replace(CLEAN_EDGE_TRAIL, "")
            .replace(CLEAN_DIGIT_DOT_TRAIL, "")
            .replace(CLEAN_DOT_TRAIL, "")
        var out = stripReleaseTagWords(tidy.trim())
        if (hdrVariant != null) out = out.replace(CLEAN_HDR_ANY_TOKEN, " ")
        out = out.replace(CLEAN_LANG_NOTE_TOKEN, " ")
        return ReleaseTitleNormalizer.normalize(out)
    }

    /** upper-case only, so title words ("The French Chef") stay */
    private val CLEAN_LANG_NOTE_TOKEN = Regex(
        """(?<=^|[\s.\-_\[(,])(?:SUBFRENCH|VOSTFR|VOSTA|TRUEFRENCH|FRENCH|VFF|VFQ|VFI|VF|MULTi|MULTI)(?=$|[\s.\-_\]),])""",
    )

    private val CLEAN_HDR_ANY_TOKEN = Regex(
        """(?<=^|[\s.\-_\[(])(?:HDR10\+|HDR10PLUS|HDR10P|HDR10|HDR|DV|DOVI|DOLBY[\s.\-_]*VISION)(?=$|[\s.\-_\])])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words now shown as their own tags (censorship, re-release, OV) and
     * container leftovers, removed from the visible title. The raw release
     * name in details keeps them.
     */
    fun stripReleaseTagWords(title: String): String = title
        .replace(TAG_CENSOR_PHRASE, " ")
        .replace(TAG_REVISION_WORD, " ")
        .replace(TAG_OV_WORD, " ")
        .replace(TAG_CONTAINER_WORD, " ")
        .replace(CLEAN_EMPTY_PAREN_LOOSE, " ")
        .replace(CLEAN_EMPTY_BRACKET_LOOSE, " ")
        .replace(REGEX_WS_RUN, " ")
        .replace(TAG_DANGLING_SEP, "")
        .trim()

    private val TAG_CENSOR_PHRASE = Regex("""(?i)\b(un)?censored(\s+version)?\b""")
    private val TAG_REVISION_WORD = Regex("""(?i)\b(repack\d?|proper)\b""")
    private val TAG_OV_WORD = Regex("""\bOV\b""")
    private val TAG_CONTAINER_WORD = Regex("""(?i)\.?\b(mkv|mp4)\b""")
    private val CLEAN_EMPTY_PAREN_LOOSE = Regex("""\(\s*\)""")
    private val CLEAN_EMPTY_BRACKET_LOOSE = Regex("""\[\s*]""")
    private val TAG_DANGLING_SEP = Regex("""[\s|/-]+$""")
}