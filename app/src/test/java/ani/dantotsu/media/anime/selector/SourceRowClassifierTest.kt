package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SourceRowClassifier] and [SourceRowSummarizer].
 *
 * Pure JVM tests. No Android dependencies.
 *
 * v3.1 priorities:
 *   1. Release group comes from the first valid bracketed title token
 *      (with a bounded technical prefix allowed), NOT from the
 *      📂 tracker line. The ⚙ line is corroborating only.
 *   2. NF / AMZN / CR are detected as source service, never as group.
 *   3. 2160p -> 4K display alias; internal value stays 2160p.
 *   4. Subtitle vs audio flags must be contextually separated.
 *   5. Clean title removes tokens already shown elsewhere (group,
 *      resolution, codec, bit depth, source service).
 *   6. Audio formats: AAC2.0 -> AAC (stereo default, channels dropped),
 *      DDP2.0 -> E-AC-3 / DDP, DDP5.1 kept, etc.
 */
class SourceRowClassifierTest {

    // ---- Sample release-row fixtures (made-up names, current format) -

    // Each fixture is a multi-line Relayaddon payload that includes BOTH
    // a 📂 tracker line and a leading [GROUP] token in the title. The
    // tests below assert group + tracker on the SAME parsed object.

    private val fixtureGrpcladIdxOne =
        """
        Relayaddon
        📂 IdxOne
        1080p [Grpclad] Yani Neko - S01E04 [WEB.10...mkv
        """.trim()
    private val fixtureGrpsHubbIdxOne =
        """
        Relayaddon
        📂 IdxOne
        1080p [GrpsHubb] Chainsmoker Cat S01E04 [1080p][HEVC].mkv
        """.trim()
    private val fixtureDqbIdxOne =
        """
        Relayaddon
        📂 IdxOne
        1080p [DQB] Yani Neko - S01E04 [1080p][HE...mkv
        """.trim()
    private val fixtureGrpvyIdxTW =
        """
        Relayaddon
        📂 idxTW
        1080p [GRPVY] Chainsmoker Cat S01E04 1080...mkv
        """.trim()
    private val fixture4kGrpaiSensei =
        """
        Relayaddon
        📂 2448x
        4k [Grpai-Sensei] Sousou no Frieren - 01 [1080p][HEVC].mkv
        """.trim()
    private val fixture1080pSubsPlumeIdxOne =
        "1080p [SubsPlume] Sousou no Frieren - 01 (1080p) [F02B9CEE].mkv"

    // Sample metadata-block fixture (legacy two-marker shape:
    // v2-era 📂 + ⚙️ lines; current rows use single-⚙️ provenance).
    private val fixtureRelayaddonMetadataBlock =
        """
        Relayaddon
        📂 IdxOne
        ⚙️ SubsPlume
        💾 1.48 GB
        👤 545
        🎯 1080p
        🎬 Dual Audio
        🔗 Batch
        [SubsPlume] Sousou no Frieren - 01v2 (1080p) [AAA94036].mkv
        """.trim()

    // ---- Negative release-group fixtures (must NOT fabricate a group)

    private val fixtureNoGroup_crc =
        "Title S01E01 [ABC12345].mkv"
    private val fixtureNoGroup_unknownTech =
        "1080p Title [SomeUnknownTechnicalToken].mkv"

    // ---- Release group: title bracket (preferred) --------------------
    //
    // Each of these tests uses a two-marker (📂 + title with leading
    // [GROUP]) payload. That is the LEGACY compatibility shape; current
    // Relayaddon rows carry single-⚙️ provenance instead (covered by the
    // current_* tests). Both assert group + tracker on one parse result.

    @Test
    fun releaseGroup_Grpclad_titleBracket_withTrackerIdxOne() {
        val f = SourceRowClassifier.parseRelease(fixtureGrpcladIdxOne)
        assertEquals("Grpclad", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun releaseGroup_GrpsHubb_titleBracket_withTrackerIdxOne() {
        val f = SourceRowClassifier.parseRelease(fixtureGrpsHubbIdxOne)
        assertEquals("GrpsHubb", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun releaseGroup_DQB_titleBracket_withTrackerIdxOne() {
        val f = SourceRowClassifier.parseRelease(fixtureDqbIdxOne)
        assertEquals("DQB", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun releaseGroup_GRPVY_titleBracket_withTrackerIdxTW() {
        val f = SourceRowClassifier.parseRelease(fixtureGrpvyIdxTW)
        assertEquals("GRPVY", f.releaseGroup)
        assertEquals("idxTW", f.tracker)
    }

    @Test
    fun releaseGroup_4kGrpaiSensei_titleBracket_withTracker2448x() {
        val f = SourceRowClassifier.parseRelease(fixture4kGrpaiSensei)
        // Quality prefix "4k" before the bracket must be allowed.
        assertEquals("Grpai-Sensei", f.releaseGroup)
        assertEquals("2448x", f.tracker)
    }

    @Test
    fun releaseGroup_SubsPlumeFromBracketOrMetadata() {
        val f = SourceRowClassifier.parseRelease(fixture1080pSubsPlumeIdxOne)
        // Title bracket and ⚙ line both say SubsPlume; either is acceptable.
        assertEquals("SubsPlume", f.releaseGroup)
    }

    @Test
    fun releaseGroup_metadataBlock_usesCogLine() {
        val f = SourceRowClassifier.parseRelease(fixtureRelayaddonMetadataBlock)
        // No [BracketedToken] in the title line, so we fall back to the
        // ⚙ line. ⚙ value "SubsPlume" is used. The 📂 tracker value
        // is "IdxOne" and is stored separately.
        assertEquals("SubsPlume", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    // ---- Tracker is NEVER treated as release group ------------------

    @Test
    fun releaseGroup_IdxOne_indexer_isNotGroup() {
        // The v3.4 brief forbids a generic "looks like a tracker" prefix
        // when no 📂 tracker line corroborates it. Add the 📂 line so the
        // "IdxOne" prefix is the actual parsed tracker.
        val f = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 IdxOne
            IdxOne [SomeGroup] Title.mkv
            """.trimIndent()
        )
        assertEquals("SomeGroup", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun releaseGroup_IdxOne_tracker_doesNotReplaceGroup() {
        // The 📂 tracker value (IdxOne) must not replace the title bracket
        // group (Grpclad). This is the explicit "do not silently prefer
        // 📂" rule from the v3.3 brief.
        val f = SourceRowClassifier.parseRelease(fixtureRelayaddonMetadataBlock)
        // Title line: "[SubsPlume] Sousou no Frieren ..."
        // Cog line: "⚙️ SubsPlume"  -> both agree, so group is SubsPlume.
        assertEquals("SubsPlume", f.releaseGroup)
        // 📂 line is "IdxOne", so tracker is set.
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun releaseGroup_rejectsTechnicalBracketsAsGroup() {
        // [HEVC] is technical metadata, NOT a release group.
        val f = SourceRowClassifier.parseRelease("1080p [HEVC] Title S01E01.mkv")
        assertNull("HEVC must not be a release group", f.releaseGroup)
    }

    @Test
    fun releaseGroup_rejectsLanguageAsGroup() {
        // [ENG] is a language code, not a release group.
        val f = SourceRowClassifier.parseRelease("1080p [ENG] Title S01E01.mkv")
        assertNull("ENG must not be a release group", f.releaseGroup)
    }

    @Test
    fun releaseGroup_rejectsResolutionAsGroup() {
        val f = SourceRowClassifier.parseRelease("[1080p] Title.mkv")
        assertNull("1080p must not be a release group", f.releaseGroup)
    }

    @Test
    fun releaseGroup_rejectsBatchAsGroup() {
        val f = SourceRowClassifier.parseRelease("1080p [Batch] Title.mkv")
        assertNull("Batch must not be a release group", f.releaseGroup)
    }

    // ---- NF / AMZN / CR are detected as source service ---------------

    @Test
    fun sourceService_NF() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title [NF] S01E01.mkv")
        assertEquals("Netflix", f.sourceService)
    }

    @Test
    fun sourceService_AMZN() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title [AMZN] S01E01.mkv")
        assertEquals("Amazon", f.sourceService)
    }

    @Test
    fun sourceService_CR() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title [CR] S01E01.mkv")
        assertEquals("Crunchyroll", f.sourceService)
    }

    @Test
    fun sourceService_NFIsNotReleaseGroup() {
        val f = SourceRowClassifier.parseRelease("[NF] Title S01E01.mkv")
        // "NF" must be detected as source service, not as the release
        // group. The leading bracket is the only candidate; group is null.
        assertNull(f.releaseGroup)
        assertEquals("Netflix", f.sourceService)
    }

    // ---- Source class detection (WEB-DL / BluRay / etc.) ------------

    @Test
    fun sourceClass_WebDL() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title WEB-DL S01E01.mkv")
        assertEquals("WEB-DL", f.sourceClass)
    }

    @Test
    fun sourceClass_Bluray() {
        val f = SourceRowClassifier.parseRelease("1080p [Group] Title BluRay S01E01.mkv")
        assertEquals("BLURAY", f.sourceClass)
    }

    // ---- 4K alias ---------------------------------------------------

    @Test
    fun resolutionDisplayAlias_2160p_is4K() {
        val f = SourceRowClassifier.parseRelease("2160p [Group] Title S01E01.mkv")
        assertEquals("2160p", f.resolution)
        assertEquals("4K", SourceRowClassifier.resolutionDisplayAlias(f.resolution))
    }

    @Test
    fun resolutionDisplayAlias_1080p_stays1080p() {
        assertEquals("1080p",
            SourceRowClassifier.resolutionDisplayAlias("1080p"))
    }

    @Test
    fun resolutionDisplayAlias_1440p_isNot2K() {
        // Per the v3.3 brief: do NOT display 1440p as "2K".
        assertEquals("1440p",
            SourceRowClassifier.resolutionDisplayAlias("1440p"))
    }

    // ---- Codec / bit depth / HDR --------------------------------

    @Test
    fun codec_hevcBracketNormalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [HEVC].mkv")
        assertEquals("HEVC", f.codec)
    }

    @Test
    fun codec_h265Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [H.265].mkv")
        assertEquals("HEVC", f.codec)
    }

    @Test
    fun codec_x265Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title x265.mkv")
        assertEquals("HEVC", f.codec)
    }

    @Test
    fun codec_avcNormalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [AVC].mkv")
        assertEquals("AVC", f.codec)
    }

    @Test
    fun codec_x264Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title x264.mkv")
        assertEquals("AVC", f.codec)
    }

    @Test
    fun codec_av1Normalised() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [AV1].mkv")
        assertEquals("AV1", f.codec)
    }

    @Test
    fun bitDepth_10bit() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [10bit].mkv")
        assertEquals("10-bit", f.bitDepth)
    }

    @Test
    fun bitDepth_10Bit() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [10 Bit].mkv")
        assertEquals("10-bit", f.bitDepth)
    }

    @Test
    fun bitDepth_Hi10P() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [Hi10P].mkv")
        assertEquals("10-bit", f.bitDepth)
    }

    @Test
    fun hdr_HDR10() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [HDR10].mkv")
        assertEquals("HDR10", f.hdrVariant)
    }

    @Test
    fun hdr_HDR10Plus() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [HDR10+].mkv")
        assertEquals("HDR10+", f.hdrVariant)
    }

    @Test
    fun hdr_DolbyVision() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [Dolby Vision].mkv")
        assertEquals("Dolby Vision", f.hdrVariant)
    }

    @Test
    fun hdr_DVTokenMappedToDolbyVision() {
        val f = SourceRowClassifier.parseRelease("[Group] Title [DV].mkv")
        assertEquals("Dolby Vision", f.hdrVariant)
    }

    // ---- Audio formats ------------------------------------------

    @Test
    fun audioFormat_AAC2_0() {
        // Stereo is the default: channel count is dropped.
        val f = SourceRowClassifier.parseRelease("🎬 Japanese (AAC 2.0) [Group] Title.mkv")
        assertEquals("AAC", f.audioFormat)
    }

    @Test
    fun audioFormat_DDP2_0() {
        val f = SourceRowClassifier.parseRelease("[GRPEM] Title DDP2.0 x264-GRPEM.mkv")
        // v3.3 display normalisation, stereo collapsed: DDP 2.0 -> E-AC-3 / DDP.
        assertEquals("E-AC-3 / DDP", f.audioFormat)
    }

    @Test
    fun audioFormat_DDP5_1() {
        val f = SourceRowClassifier.parseRelease("[Group] Title DDP5.1.mkv")
        // Multichannel is always kept.
        assertEquals("E-AC-3 / DDP 5.1", f.audioFormat)
    }

    @Test
    fun audioFormat_FLAC() {
        val f = SourceRowClassifier.parseRelease("[Group] Title FLAC 2.0.mkv")
        assertEquals("FLAC", f.audioFormat)
    }

    @Test
    fun audioFormat_stereoCollapsed_multichannelKept() {
        assertEquals("OPUS",
            SourceRowClassifier.parseRelease("[Group] Title OPUS 2.0.mkv").audioFormat)
        assertEquals("AAC 5.1",
            SourceRowClassifier.parseRelease("[Group] Title AAC 5.1.mkv").audioFormat)
        assertEquals("AC-3",
            SourceRowClassifier.parseRelease("[Group] Title AC-3.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title E-AC-3 5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP5.mkv").audioFormat)
        assertEquals("AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title AC3 5.1.mkv").audioFormat)
    }

    @Test
    fun audioFormat_tailsDoNotSwallowCodecAndGroup() {
        // The DTS detail tail is bounded: codec/group text after it
        // must not become part of the format.
        assertEquals("DTS-HD",
            SourceRowClassifier.parseRelease("Title DTS-HD.X265-IQHD.mkv").audioFormat)
        assertEquals("DTS",
            SourceRowClassifier.parseRelease("Title DTS.mkv").audioFormat)
        // Bare Dolby is ambiguous (Vision/Atmos/Digital?) and matches
        // nothing since the generic catch-all was removed (v2.2 Fix C).
        assertNull(SourceRowClassifier.parseRelease("[Group] Title DOLBY.mkv").audioFormat)
        // Dolby Vision is HDR metadata, never an audio format.
        assertNull(SourceRowClassifier.parseRelease("[Group] Title Dolby Vision.mkv").audioFormat)
        assertNull(SourceRowClassifier.parseRelease("[Group] Title Dolby.Vision.mkv").audioFormat)
    }

    // ---- Subtitle / audio flag separation ------------------------

    @Test
    fun flags_dualAudioContext_AreAudioFlags() {
        val f = SourceRowClassifier.parseRelease(
            "🎬 Japanese 🇯🇵[Group] Title.mkv",
        )
        // The flag on the audio line should be classified as an audio
        // flag, NOT a subtitle flag.
        assertTrue("audio flag should be detected", f.audioFlags.isNotEmpty())
        assertEquals("🇯🇵", f.audioFlags.firstOrNull())
        assertTrue("subtitle flag should NOT include the audio flag",
            f.subtitleFlags.isEmpty())
    }

    @Test
    fun flags_multiSubsContext_AreSubtitleFlags() {
        val f = SourceRowClassifier.parseRelease(
            "[Group] Title [Multi-Subs] 🇬🇧 🇵🇱 🇪🇸.mkv",
        )
        assertEquals(3, f.subtitleFlags.size)
        assertTrue("audio flag should NOT include the subtitle flags",
            f.audioFlags.isEmpty())
    }

    @Test
    fun flags_uncontextualFlag_isNotClassified() {
        val f = SourceRowClassifier.parseRelease(
            "[Group] Title 🇯🇵 S01E01 [JP] [HEVC].mkv",
        )
        // The flag has no Sub / Audio / Multi-Subs context. It must NOT
        // be classified as a subtitle or audio flag.
        assertTrue("uncontextual flag should not be classified as subtitle",
            f.subtitleFlags.isEmpty())
        assertTrue("uncontextual flag should not be classified as audio",
            f.audioFlags.isEmpty())
    }

    @Test
    fun subtitleLanguages_ENGonly() {
        val f = SourceRowClassifier.parseRelease("[Group] Title S01E01 [Subs] [ENG].mkv")
        assertTrue("ENG should be detected", "ENG" in f.subtitleLanguages)
    }

    @Test
    fun subtitleLanguages_REGIONAL_CODES() {
        // Fixture has subtitle context (Multi-Subs) so the bracketed
        // language codes are collected into subtitleLanguages.
        val f = SourceRowClassifier.parseRelease(
            "[GrpeEsp] Title S01E01 [Multi-Subs] [ESP-ENG][mkv]",
        )
        assertTrue("ESP-ENG should be detected as a single regional code",
            "ESP-ENG" in f.subtitleLanguages)
    }

    // ---- Clean display title -------------------------------------

    @Test
    fun cleanTitle_removesGroupResolutionCodec() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "1080p [Grpclad] Yani Neko - S01E04 [1080p][HEVC][10bit].mkv",
            releaseGroup = "Grpclad",
            resolution = "1080p",
            codec = "HEVC",
            bitDepth = "10-bit",
            sourceService = null,
        )
        // Group, leading resolution, and bracketed codec / bit-depth / res
        // should be removed; the title text remains.
        assertFalse("clean title should not contain [Grpclad]",
            cleaned.contains("[Grpclad]"))
        assertFalse("clean title should not contain [HEVC]",
            cleaned.contains("[HEVC]"))
        assertFalse("clean title should not contain [10bit]",
            cleaned.contains("[10bit]"))
        assertTrue("clean title should keep the release description",
            cleaned.contains("Yani Neko"))
    }

    @Test
    fun cleanTitle_removesSourceService() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "1080p [Grpclad] Title NF S01E01.mkv",
            releaseGroup = "Grpclad",
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = "Netflix",
        )
        assertFalse("clean title should not contain NF",
            cleaned.contains("NF"))
    }

    @Test
    fun cleanTitle_keepsRawTitleForIdentity() {
        val raw = "1080p [Grpclad] Yani Neko - S01E04 [1080p][HEVC].mkv"
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = raw,
            releaseGroup = "Grpclad",
            resolution = "1080p",
            codec = "HEVC",
            bitDepth = null,
            sourceService = null,
        )
        // The exact raw title is preserved in the parseRelease result
        // for identity / selection. cleanDisplayTitle is the UI-only
        // variant.
        val fields = SourceRowClassifier.parseRelease(raw)
        assertEquals(raw, fields.releaseName)
        assertTrue(cleaned.length < raw.length)
    }

    // ---- Summarizer end-to-end -----------------------------------

    @Test
    fun summarize_GrpcladIdxOne_headerAndTracker() {
        // Use the v2-era fixture that has a metadata block including
        // the 📂 tracker line and the ⚙ group line; group is the title
        // bracket (SubsPlume); tracker is IdxOne.
        val row = SourceRowSummarizer.build(
            displayLabel = fixtureRelayaddonMetadataBlock,
            qualityText = fixtureRelayaddonMetadataBlock,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals("SubsPlume", row.header)
        assertEquals("IdxOne", row.tracker)
    }

    @Test
    fun summarize_4kGrpaiSensei_cleanTitleStripsPrefix() {
        val row = SourceRowSummarizer.build(
            displayLabel = fixture4kGrpaiSensei,
            qualityText = fixture4kGrpaiSensei,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals("Grpai-Sensei", row.header)
        // cleanSubtitle should not contain the leading "4k" or the group
        // bracket.
        assertFalse("cleanSubtitle should not start with '4k'",
            row.cleanSubtitle?.startsWith("4k") == true)
    }

    @Test
    fun summarize_NFAmznCR_NotInPills() {
        // Source-service aliases must NOT be top-row chips per the v3.3
        // brief. They live in details instead.
        val quality = "1080p [Group] Title [NF] S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val nfInPills = row.pills.any { it.text.equals("NF", ignoreCase = true) }
        assertFalse("NF must not be a top-row pill", nfInPills)
        val nfInDetails = row.details.any { it.label == "Source service" && it.value == "Netflix" }
        assertTrue("NF must appear in details", nfInDetails)
    }

    // ---- negative release-group fixtures -------------------------

    @Test
    fun releaseGroup_negative_crc_doesNotFabricate() {
        // [ABC12345] is a CRC-like bracket token, NOT a release group.
        val f = SourceRowClassifier.parseRelease(fixtureNoGroup_crc)
        assertNull("CRC-like bracket must not become the release group",
            f.releaseGroup)
    }

    @Test
    fun releaseGroup_negative_unknownTechToken_doesNotFabricate() {
        // [SomeUnknownTechnicalToken] is bracketed but is not a known
        // group. Must not be promoted.
        val f = SourceRowClassifier.parseRelease(fixtureNoGroup_unknownTech)
        assertNull("Unknown technical bracket must not become the release group",
            f.releaseGroup)
    }

    // ---- 4k -> canonical 2160p ----

    @Test
    fun parseResolution_4k_isCanonical2160p() {
        val f = SourceRowClassifier.parseRelease(
            "4k [Group] Title S01E01 [1080p][HEVC].mkv",
        )
        assertEquals("2160p", f.resolution)
        assertEquals("4K",
            SourceRowClassifier.resolutionDisplayAlias(f.resolution))
    }

    @Test
    fun parseResolution_4k_winsOverLater1080pTechnical() {
        // The leading 4k is the quality. A later bracketed [1080p] is
        // technical metadata and must NOT replace the leading resolution.
        val f = SourceRowClassifier.parseRelease(
            "4k [Group] Title S01E01 [1080p][HEVC].mkv",
        )
        assertEquals("2160p", f.resolution)
    }

    // ---- standalone NF/AMZN/CR detection ----

    @Test
    fun sourceService_standalone_NF() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Chainsmoker Cat S01E04 NF.mkv",
        )
        assertEquals("Netflix", f.sourceService)
    }

    @Test
    fun sourceService_standalone_AMZN() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title AMZN S01E01.mkv",
        )
        assertEquals("Amazon", f.sourceService)
    }

    @Test
    fun sourceService_standalone_CR() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title CR S01E01.mkv",
        )
        assertEquals("Crunchyroll", f.sourceService)
    }

    @Test
    fun sourceService_doesNotSubstringMatchInArbitraryWords() {
        // "knife" contains "nf" but must not be misread as Netflix.
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title knife S01E01.mkv",
        )
        assertNull(f.sourceService)
    }

    // ---- pills use the agreed priority ----

    @Test
    fun pills_priorityIsResolutionThenSubsThenHdrThenAudioThenCodec() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title S01E01 [HEVC] [10bit] [HDR10+] [AAC 2.0] [Japanese].mkv",
        )
        val row = SourceRowSummarizer.build(
            displayLabel = f.releaseName,
            qualityText = f.releaseName,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        // v3 priority (intentional change):
        // resolution, subtitle/language visibility, HDR variant, audio
        // mode, codec, bit depth, audio format. Subtitle pills outrank
        // codec so dense rows never drop language visibility.
        // First pill: resolution.
        assertEquals(SourcePillKind.QUALITY, row.pills[0].kind)
        // Second pill: HDR variant (more informative than bit depth).
        assertEquals(SourcePillKind.HDR10_PLUS, row.pills[1].kind)
        // Third pill: codec (no subtitle/audio-mode signal on this row).
        assertEquals(SourcePillKind.CODEC, row.pills[2].kind)
        // Fourth pill: audio format.
        assertEquals(SourcePillKind.AUDIO_FMT, row.pills[3].kind)
    }

    // ---- additional regressions -----------

    @Test
    fun releaseGroup_negative_1080pTitleFoo_doesNotFabricate() {
        // Short token "Title" is not a tracker, not a quality. The bracket
        // cannot become the release group without corroboration from a
        // 📂 tracker line.
        val f = SourceRowClassifier.parseRelease("1080p Title [Foo].mkv")
        assertNull("1080p Title [Foo] must not fabricate a release group",
            f.releaseGroup)
    }

    @Test
    fun releaseGroup_negative_1080pTitleUnknown_doesNotFabricate() {
        val f = SourceRowClassifier.parseRelease("1080p Title [Unknown].mkv")
        assertNull("1080p Title [Unknown] must not fabricate a release group",
            f.releaseGroup)
    }

    @Test
    fun releaseGroup_positive_corroboratedIdxOne_withTracker() {
        // The 📂 line provides the canonical tracker. The "IdxOne" token
        // matches the parsed tracker exactly (case-preserved), so the
        // bracketed group is accepted.
        val f = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 IdxOne
            IdxOne 1080p [Grpclad] Title S01E01.mkv
            """.trimIndent()
        )
        assertEquals("Grpclad", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun releaseGroup_QualityAndTracker_OrderIndependent() {
        // Either order (quality then tracker, or tracker then quality)
        // must work as long as the tracker token matches the parsed
        // tracker.
        val f1 = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 IdxOne
            1080p IdxOne [Grpclad] Title.mkv
            """.trimIndent()
        )
        val f2 = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 IdxOne
            IdxOne 1080p [Grpclad] Title.mkv
            """.trimIndent()
        )
        assertEquals("Grpclad", f1.releaseGroup)
        assertEquals("Grpclad", f2.releaseGroup)
    }

    @Test
    fun releaseGroup_TrackerInTitleNotMatchingParsedTracker_doesNotFabricate() {
        // The 📂 line says IdxOne, but the prefix token says "Other".
        // Because the prefix token does not match the parsed tracker,
        // the bracketed token is rejected.
        val f = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 IdxOne
            Other 1080p [Grpclad] Title.mkv
            """.trimIndent()
        )
        assertNull("Mismatched tracker prefix must not fabricate a group",
            f.releaseGroup)
    }

    // ---- visual hierarchy tests -----------------------------

    @Test
    fun v35_pillsCappedToFour() {
        // v3.5 requires max 4 visible chips. The summarizer already
        // enforces this; verify it explicitly.
        val quality = "1080p [Group] Title [HEVC] [10bit] [HDR10+] [AAC 2.0] [Japanese].mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertTrue("pills must be capped at 4, was ${row.pills.size}",
            row.pills.size <= 4)
    }

    @Test
    fun v35_trackerSetOnlyWhenParsed() {
        // Tracker is exposed only when the 📂 line is parsed. A
        // title-only fixture does not have a tracker.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("Group", row.header)
        assertNull("tracker must be null when not parsed", row.tracker)
    }

    @Test
    fun v35_trackerSetWhenParsed() {
        val quality = """
            Relayaddon
            📂 IdxOne
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("Group", row.header)
        assertEquals("IdxOne", row.tracker)
    }

    @Test
    fun v35_cleanTitlePreservedOnExpandedDisplay() {
        val quality = "1080p [Group] Title S01E01 [1080p][HEVC].mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        // cleanSubtitle must remove the [Group] bracket and the
        // trailing [1080p][HEVC] technical metadata; the descriptive
        // title "Title S01E01" remains.
        assertNotNull(row.cleanSubtitle)
        assertTrue("cleanSubtitle must not include [Group]",
            !row.cleanSubtitle!!.contains("[Group]"))
        assertTrue("cleanSubtitle must not include the bracketed codec tag",
            !row.cleanSubtitle!!.contains("[HEVC]"))
        assertTrue("cleanSubtitle must contain the title body",
            row.cleanSubtitle!!.contains("Title S01E01"))
    }

    // ---- narrow tracker-cue tests ---------------------------

    @Test
    fun v36_viewModel_carriesTrackerField() {
        // The view-model still exposes the parsed tracker for the bind code
        // to set on the small icon cue.
        val quality = """
            Relayaddon
            📂 IdxOne
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("IdxOne", row.tracker)
    }

    @Test
    fun v36_viewModel_trackerAbsentWithoutParsedLine() {
        // No 📂 line -> tracker is null. The bind code hides the icon cue.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertNull("tracker must be null without a parsed 📂 line", row.tracker)
    }

    @Test
    fun v36_textualTrackerStillInLowerMetadataRow() {
        // The lower metadata row still contains the tracker as TEXT.
        // The new icon cue is in the header and does NOT remove the
        // textual appearance of the tracker here.
        val quality = """
            Relayaddon
            📂 IdxOne
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        val metaText = row.meta.joinToString(" · ") { it.text }
        assertTrue(
            "lower metadata row must still contain textual tracker; was '$metaText'",
            metaText.contains("IdxOne"),
        )
    }

    @Test
    fun v36_cleanTitleDoesNotInferTracker() {
        // Title alone (no 📂 line) must not produce a fake tracker.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertNull(row.tracker)
        // No fake "verified" indicator is exposed by the view-model.
        // (No field exists; the layout's icon cue is hidden when
        // tracker is null.)
    }

    // ---- non-action ImageView tests -------------------------
    //
    // The view-model is unchanged in v3.7; only the layout
    // semantics are corrected (ImageButton -> ImageView with
    // clickable=false focusable=false). The unit tests assert the
    // view-model still provides the fields the new ImageView binds
    // to.

    @Test
    fun v37_viewModelProvidesTrackerForCue() {
        // The v3.7 ImageView is a non-action image that takes the
        // parsed tracker string as contentDescription and a visibility
        // signal. The view-model must expose the parsed tracker so
        // the bind code can set the cue.
        val quality = """
            Relayaddon
            📂 IdxOne
            1080p [Group] Title S01E01.mkv
        """.trimIndent()
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertEquals("IdxOne", row.tracker)
    }

    @Test
    fun v37_viewModelTrackerAbsentForCue() {
        // No 📂 line -> the cue must remain hidden.
        val quality = "1080p [Group] Title S01E01.mkv"
        val row = SourceRowSummarizer.build(
            displayLabel = quality,
            qualityText = quality,
            formatName = "CONTAINER",
            url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
        )
        assertNull("tracker must be null when no parsed 📂 line is present",
            row.tracker)
    }
    // ---- Dub/Sub label correctness --------------------
    //
    // Bare dub/sub markers in the release title surface information
    // already present in real torrent release names. Explicit signals
    // (🎬 audio line / subtitle flags / language codes) always win;
    // the provider/tracker name is never a signal source.

    @Test
    fun followups_dualBareWord_isAudioKind() {
        val f = SourceRowClassifier.parseRelease(
            "[Grpa-raws] Sousou no Frieren - 01 [1080p][HEVC][DUAL AAC2.0].mkv",
        )
        assertEquals("Grpa-raws", f.releaseGroup)
        assertEquals("AAC", f.audioFormat)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun followups_dualLedBracket_isNotGroup() {
        // An audio-led bracket is a content signal, not a group.
        val f = SourceRowClassifier.parseRelease("[DUAL AAC2.0] Some Title S01E01.mkv")
        assertNull("DUAL AAC2.0 must not be a release group", f.releaseGroup)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun followups_dubbed_isAudioKind() {
        val f = SourceRowClassifier.parseRelease(
            "Sousou no Frieren S02E01 Dubbed 1080p AAC.mkv",
        )
        assertEquals("Dub", f.audioSummary)
        assertNull(f.releaseGroup)
    }

    @Test
    fun followups_engDub_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("[Group] Title S01E01 Eng Dub [1080p].mkv")
        assertEquals("Dub", f.audioSummary)
        assertEquals("Group", f.releaseGroup)
    }

    @Test
    fun followups_englishDub_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("Title S01E01 English Dub 1080p.mkv")
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun followups_dualAudioBare_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("[Group] Title Dual Audio 1080p.mkv")
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun followups_multiSubs_isSubtitleSummary() {
        val f = SourceRowClassifier.parseRelease("[Group] Title S01E01 Multi Subs 1080p.mkv")
        assertEquals("Multi-subs", f.subtitleSummary)
        assertEquals("Group", f.releaseGroup)
    }

    @Test
    fun followups_multiHyphenSubs_isSubtitleSummary_notGroup() {
        val f = SourceRowClassifier.parseRelease("[Multi-Subs] Title S01E01.mkv")
        assertNull("Multi-Subs must not be a release group", f.releaseGroup)
        assertEquals("Multi-subs", f.subtitleSummary)
    }

    @Test
    fun followups_multisubOneWord_isSubtitleSummary() {
        val f = SourceRowClassifier.parseRelease("Title S01E01 Multisub 1080p.mkv")
        assertEquals("Multi-subs", f.subtitleSummary)
    }

    @Test
    fun followups_explicitAudioLine_winsOverDubGuess() {
        // The 🎬 line is higher-confidence than a bare title marker.
        val f = SourceRowClassifier.parseRelease(
            "🎬 Japanese\n[Group] Title Dubbed S01E01.mkv",
        )
        assertEquals("Japanese", f.audioSummary)
    }

    @Test
    fun followups_explicitSubFlags_winOverMultiGuess() {
        // Classified flags beat the bare multi-sub guess.
        val f = SourceRowClassifier.parseRelease("[Group] Title [Multi-Subs] 🇬🇧 🇵🇱.mkv")
        assertEquals("🇬🇧 🇵🇱", f.subtitleSummary)
    }

    @Test
    fun followups_providerNameAlone_doesNotInferDub() {
        // A "dub" substring in the tracker name must not fabricate an
        // audio kind; the title here is neutral.
        val f = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 DubTorrents
            1080p [Group] Title S01E01.mkv
            """.trimIndent(),
        )
        assertNull("tracker name must not infer dub", f.audioSummary)
        assertNull("tracker name must not infer subs", f.subtitleSummary)
        assertEquals("Group", f.releaseGroup)
    }

    @Test
    fun followups_japaneseDoesNotTriggerDub() {
        // "Japanese" contains no dub word-boundary match.
        val f = SourceRowClassifier.parseRelease("🎬 Japanese (AAC 2.0) [Group] Title.mkv")
        assertEquals("AAC", f.audioFormat)
    }

    // ---- suffix-style -GROUP extraction --------------

    @Test
    fun followups_suffixGrpvy_extracted() {
        val f = SourceRowClassifier.parseRelease(
            "Sousou no Frieren S02E01 [1080p][HEVC]-GRPVY.mkv",
        )
        assertEquals("GRPVY", f.releaseGroup)
    }

    @Test
    fun followups_suffixGrpta_extracted_preservesCase() {
        val f = SourceRowClassifier.parseRelease("Frieren S02E01 1080p HEVC-Grpta.mkv")
        assertEquals("Grpta", f.releaseGroup)
    }

    @Test
    fun followups_suffixWithoutExtension_extracted() {
        val f = SourceRowClassifier.parseRelease("Frieren S02E01 1080p-GRPVY")
        assertEquals("GRPVY", f.releaseGroup)
    }

    @Test
    fun followups_bracketWinsOverSuffix() {
        // An authoritative [Group] tag beats any suffix guess.
        val f = SourceRowClassifier.parseRelease("[SubsPlume] Title S01E01 1080p-GRPVY.mkv")
        assertEquals("SubsPlume", f.releaseGroup)
    }

    @Test
    fun followups_cogWinsOverSuffix() {
        // The explicit ⚙ line beats the weaker suffix guess.
        val f = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 IdxOne
            ⚙️ SubsPlume
            Title S01E01 1080p-GRPVY.mkv
            """.trimIndent(),
        )
        assertEquals("SubsPlume", f.releaseGroup)
    }

    @Test
    fun followups_suffixRejectsCodec() {
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-x264.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-HEVC.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsResolution() {
        assertNull(SourceRowClassifier.parseRelease("Title-1080p.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-4K.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsAudio() {
        assertNull(SourceRowClassifier.parseRelease("Title-AAC.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-FLAC.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsEpisodeAndVersion() {
        assertNull(SourceRowClassifier.parseRelease("Show-S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Show-E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-v2.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsTagsAndLanguages() {
        assertNull(SourceRowClassifier.parseRelease("Title-WEBDL.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-COMPLETE.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-JP.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title-NF.mkv").releaseGroup)
    }

    @Test
    fun followups_suffixRejectsExtensionAndSpacedForms() {
        // The extension itself is never the group.
        assertNull(SourceRowClassifier.parseRelease("Title.mkv").releaseGroup)
        // A spaced hyphen does not form a suffix group.
        assertNull(SourceRowClassifier.parseRelease("Title - 01.mkv").releaseGroup)
        // No trailing hyphen token at all.
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 1080p.mkv").releaseGroup)
    }

    // ---- Dub/Sub pills end-to-end ---------------------

    @Test
    fun followups_dualRelease_pillsCarryAudioMode() {
        val row = SourceRowSummarizer.build(
            displayLabel = "[Grpa-raws] Title - 01 [1080p][DUAL AAC2.0].mkv",
            qualityText = "[Grpa-raws] Title - 01 [1080p][DUAL AAC2.0].mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals("Grpa-raws", row.header)
        val mode = row.pills.firstOrNull { it.kind == SourcePillKind.AUDIO_MODE }
        assertNotNull("DUAL row must carry an audio-mode pill", mode)
        assertEquals("Dual", mode!!.text)
    }

    @Test
    fun followups_multiSubsRelease_pillsCarrySubLang() {
        val row = SourceRowSummarizer.build(
            displayLabel = "[Group] Title S01E01 Multi Subs 1080p.mkv",
            qualityText = "[Group] Title S01E01 Multi Subs 1080p.mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val sub = row.pills.firstOrNull { it.kind == SourcePillKind.SUB_LANG }
        assertNotNull("Multi-subs row must carry a subtitle pill", sub)
        assertEquals("Multi-subs", sub!!.text)
    }


    // ---- pipe-terminated tracker/cog --------

    @Test
    fun dataset_cogLine_stopsAtPipeSegment() {
        // Relayaddon metadata uses ` | ` separators: the ⚙️ value is
        // `Torrent9`, not `Torrent9 | 🇫🇷`. Under v2.2 current-format
        // semantics a lone ⚙️ is provenance (tracker), never a group.
        val f = SourceRowClassifier.parseRelease(
            "L'Attaque des Titans Saison 1 FRENCH HDTV\n" +
                "UBT-L.Attaque.Des.Titans.VOL01.ep01.avi\n" +
                "👤 5 💾 186.25 MB ⚙️ Torrent9 | 🇫🇷",
        )
        assertEquals("Torrent9", f.tracker)
        assertNull("lone ⚙️ provider must not be a group", f.releaseGroup)
    }

    @Test
    fun dataset_cogFallback_rescuedByPipeTermination() {
        // Under v2.2 semantics the lone ⚙️ value is the tracker; with
        // no title bracket and no filename suffix the group is null
        // (correct: null when absent). The row carries both Dubbed and
        // Dual Audio signals; DUAL wins (more specific).
        val f = SourceRowClassifier.parseRelease(
            "1080p Jujutsu Kaisen S01 (2020) (1080p x265 10bit BD Dual Audio FLAC 2.0) [Prof]\n" +
                "S01E01 - Ryoumen Sukuna.mkv\n" +
                "👤 1 💾 1001.08 MB ⚙️ IndexCee | Dubbed / Dual Audio / 🇬🇧",
        )
        assertEquals("IndexCee", f.tracker)
        assertNull("lone ⚙️ provider must not be a group", f.releaseGroup)
        assertEquals("Dual", f.audioSummary)
    }

    // ---- generalized quality prefix ----------

    @Test
    fun dataset_uncommonResolutionPrefix_isAllowed() {
        val ice = SourceRowClassifier.parseRelease("800p [GrpBlue] Naruto (Season 1) - 01.mkv")
        assertEquals("GrpBlue", ice.releaseGroup)
        val evil = SourceRowClassifier.parseRelease("528p [GrplMini] Naruto - 001.mkv")
        assertEquals("GrplMini", evil.releaseGroup)
    }

    @Test
    fun dataset_technicalSecondPrefix_isAllowed() {
        // `1080p HDR` is quality + technical metadata, not a title.
        val f = SourceRowClassifier.parseRelease("1080p HDR [Grpsali] Title S01 BD.mkv")
        assertEquals("Grpsali", f.releaseGroup)
    }

    @Test
    fun dataset_qualityTitlePrefix_stillRejected() {
        // Non-technical second token still blocks fabrication.
        val f = SourceRowClassifier.parseRelease("1080p Title [Foo].mkv")
        assertNull(f.releaseGroup)
    }

    // ---- hyphenated real groups survive ------

    @Test
    fun dataset_dubsEmpire_isGroup_notAudioMarker() {
        val f = SourceRowClassifier.parseRelease(
            "4k [Dubs-Grpire] Jujutsu Kaisen (2020) S01 v3 [2160p].mkv",
        )
        assertEquals("Dubs-Grpire", f.releaseGroup)
    }

    @Test
    fun dataset_dualLedBracket_stillRejected() {
        val f = SourceRowClassifier.parseRelease("[DUAL AAC2.0] Some Title S01E01.mkv")
        assertNull(f.releaseGroup)
    }

    @Test
    fun dataset_multiAudioSubsBracket_rejected() {
        val f = SourceRowClassifier.parseRelease("[Multi-Audio-Subs] Title S01E01.mkv")
        assertNull(f.releaseGroup)
    }

    // ---- structural brackets -----------------

    @Test
    fun dataset_seasonEpisodeBrackets_rejected() {
        assertNull(SourceRowClassifier.parseRelease("[S01] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title [S01-04 + OVA].mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Show E01 [E01].mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 [01x01].mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[Cap.101] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[OVA 01] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[Specials] Title.mkv").releaseGroup)
    }

    @Test
    fun dataset_versionBracket_rejected() {
        // A `[v2]`-first title previously fabricated group "v2".
        assertNull(SourceRowClassifier.parseRelease("[v2] Title S01E01.mkv").releaseGroup)
    }

    @Test
    fun dataset_urlBracket_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title [www.example2020.org].mkv").releaseGroup)
    }

    @Test
    fun dataset_completaBracket_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title (2022) [COMPLETA] [576p].mkv").releaseGroup)
    }

    // ---- dotted compounds --------------------

    @Test
    fun dataset_dottedCompound_extractsTrailingGroup() {
        // Scene-style compound with a valid quality prefix.
        val f = SourceRowClassifier.parseRelease("1080p [HDTV.x264-zql] Title S01E01.mkv")
        assertEquals("zql", f.releaseGroup)
    }

    @Test
    fun dataset_dottedCompound_liveRow_groupAndSubs() {
        // Real BestTorrents row: technical-first bracket blocks the
        // title path; under v2.2 semantics the lone ⚙️ value is the
        // tracker (not a group) and the Napisy marker still surfaces
        // subtitles.
        val f = SourceRowClassifier.parseRelease(
            "Naruto 2002-2007 Complet [HDTV.x264-zql][Napisy PL][Grpsia]\n" +
                "Naruto - 001.PLSUB.mkv\n" +
                "👤 1 💾 172.32 MB ⚙️ BestTorrents | 🇵🇱",
        )
        assertEquals("BestTorrents", f.tracker)
        assertNull("lone ⚙️ provider must not be a group", f.releaseGroup)
        assertEquals("Multi-subs", f.subtitleSummary)
    }

    @Test
    fun dataset_dottedCompound_technicalTail_rejected() {
        val f = SourceRowClassifier.parseRelease("[JAP.AC3.SUB.ITA] Title.mkv")
        assertNull(f.releaseGroup)
    }

    @Test
    fun dataset_dottedNonTechnicalName_acceptedWhole() {
        val f = SourceRowClassifier.parseRelease("[grpe4life.] Title S01E01.mkv")
        assertEquals("grpe4life.", f.releaseGroup)
    }

    // ---- per-line suffix + extensions --------

    @Test
    fun dataset_suffixFoundOnFilenameLine_belowTitle() {
        // No cog line: the per-line suffix scan reaches the filename
        // line even though metadata-style lines are absent here.
        val f = SourceRowClassifier.parseRelease(
            "L'attaque des Titans (Shingeki No Kyojin) Saison 1 MULTI 1080p HDTV\n" +
                "Shingeki.No.Kyojin.S01E01.MULTi.1080p.BluRay.REMUX.AVC.DTS-HDMA.2.0-Grpro.mkv",
        )
        assertEquals("Grpro", f.releaseGroup)
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_explicitCogBeatsFilenameSuffix() {
        // v2.2 current-format semantics on the full regional row: the
        // lone ⚙️ value is the tracker, and the filename suffix wins
        // the group (this is the handoff's mandatory Grpro fixture).
        val f = SourceRowClassifier.parseRelease(
            "L'attaque des Titans (Shingeki No Kyojin) Saison 1 MULTI 1080p HDTV\n" +
                "Shingeki.No.Kyojin.S01E01.MULTi.1080p.BluRay.REMUX.AVC.DTS-HDMA.2.0-Grpro.mkv\n" +
                "👤 1 💾 4.86 GB ⚙️ Torrent9\n" +
                "Dubbed / Multi Audio / 🇫🇷",
        )
        assertEquals("Grpro", f.releaseGroup)
        assertEquals("Torrent9", f.tracker)
        // Relayaddon "Multi Audio" = original + dub = Dual (picker-v3-fixes).
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_tsExtension_strippedForSuffix() {
        val f = SourceRowClassifier.parseRelease("One Piece English Dub 214.ts")
        assertNull(f.releaseGroup)
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_camSuffix_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-CAM.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-INTERNAL.mkv").releaseGroup)
    }

    // ---- dub/sub vocabulary ------------------

    @Test
    fun dataset_vostfr_isSubtitleKind() {
        // VOSTFR materializes as a French flag chip, consistent with
        // the flag system (VOSTFR = original audio + French subs).
        val f = SourceRowClassifier.parseRelease(
            "Shingeki No Kyojin S01 BDRIP 1080p X265 10bit Vostfr.mkv",
        )
        assertEquals("🇫🇷", f.subtitleSummary)
        assertEquals(listOf("🇫🇷"), f.subtitleFlags)
    }

    @Test
    fun dataset_french_isAudioKind() {
        val f = SourceRowClassifier.parseRelease("Title Saison 1 FRENCH HDTV.mkv")
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_bareMulti_isDualAudioKind() {
        // French-scene MULTI = FR+JP audio.
        val f = SourceRowClassifier.parseRelease("Title Saison 1 MULTI 1080p HDTV.mkv")
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_bareMulti_yieldsToMultiSubs() {
        // An explicit multi-subs marker wins over bare MULTI audio.
        val f = SourceRowClassifier.parseRelease("Title MULTI SUBS 1080p.mkv")
        assertEquals("Multi-subs", f.subtitleSummary)
        assertNull(f.audioSummary)
    }

    @Test
    fun dataset_castellanoLatinoDublado_areDubKind() {
        // Audio-format-led bracket with a VALID prefix: still not a group.
        val cast = SourceRowClassifier.parseRelease("1080p [AC3 5.1 Castellano] Title.mkv")
        assertNull("audio-led bracket must not be a group", cast.releaseGroup)
        assertEquals("Dub", cast.audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Latino 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Dublado 720p.mkv").audioSummary)
    }

    @Test
    fun dataset_napisyLegendado_areSubtitleKind() {
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title [Napisy PL].mkv").subtitleSummary)
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title Legendado.mkv").subtitleSummary)
    }

    @Test
    fun dataset_bareSubsWord_isSubtitleKind() {
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title Ita Eng Jap SubS.mkv").subtitleSummary)
    }

    @Test
    fun dataset_espanol_resolvesThroughContext() {
        // Sub context -> subs ...
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title Sub Espanol.mkv").subtitleSummary)
        // ... dub context -> dub ...
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Audio Espanol.mkv").audioSummary)
        // ... otherwise neutral.
        val neutral = SourceRowClassifier.parseRelease("[Group] Title Espanol S01E01.mkv")
        assertNull(neutral.audioSummary)
        assertNull(neutral.subtitleSummary)
    }

    @Test
    fun dataset_slashedAudio_isDualKind() {
        val f = SourceRowClassifier.parseRelease("Title (Season 1) [1080p][Jpn/Eng/Ger Audio].mkv")
        assertEquals("Dual", f.audioSummary)
    }

    @Test
    fun dataset_englishAudio_isDubKind() {
        val f = SourceRowClassifier.parseRelease("Title S01E01 English Audio 1080p.mkv")
        assertEquals("Dub", f.audioSummary)
    }

    @Test
    fun dataset_providerNames_doNotLeakIntoKinds() {        // Tracker/provider-flavored words must not fabricate kinds.
        val f = SourceRowClassifier.parseRelease(
            """
            Relayaddon
            📂 PlumbingSubs
            1080p [Group] Title S01E01.mkv
            """.trimIndent(),
        )
        assertNull(f.audioSummary)
        assertNull(f.subtitleSummary)
    }

    @Test
    fun dataset_subsPlumeName_isNotSubsWord() {
        // No word boundary inside "SubsPlume", so the subs-word rule
        // does not fire.
        val f = SourceRowClassifier.parseRelease("1080p [SubsPlume] Title S01E01.mkv")
        assertEquals("SubsPlume", f.releaseGroup)
        assertNull(f.subtitleSummary)
        // A neutral group with no markers stays unlabeled.
        val neutral = SourceRowClassifier.parseRelease("1080p [Group] Title S01E01.mkv")
        assertNull(neutral.subtitleSummary)
    }


    // ---- trash-guides/scenerules cross-check -------------------------
    //
    // Scene-standard shapes from trash-guides + scenerules reference:
    // SxxExx multi-episode/part-suffixed ranges, numeric ranges,
    // Part markers, daily dates, source/audio tags, DD/LPCM audio.

    @Test
    fun trash_sceneEpisodeShapes_rejected() {
        assertNull(SourceRowClassifier.parseRelease("[S01E01-E02] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("1080p [0001-0130] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[Part 1] Title.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[2020.01.15] Title.mkv").releaseGroup)
    }

    @Test
    fun trash_sourceTags_rejected() {
        assertNull(SourceRowClassifier.parseRelease("[WEB] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[PDTV] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[SDR] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("[LPCM] Title S01E01.mkv").releaseGroup)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01-REAL.mkv").releaseGroup)
    }

    @Test
    fun trash_audioFormats_detected() {
        // DD5.1 is basic Dolby Digital 5.1, NOT in the DDP family
        //.
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD5.1.mkv").audioFormat)
        assertEquals("LPCM",
            SourceRowClassifier.parseRelease("[Group] Title LPCM.mkv").audioFormat)
    }

    @Test
    fun trash_fullLanguageNames_resolveThroughContext() {
        // Dub context -> dub (reachable bare forms) ...
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title German Audio 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title Italian Dub 1080p.mkv").audioSummary)
        // ... sub context -> subs (outcome via subs-word path) ...
        assertEquals("Multi-subs",
            SourceRowClassifier.parseRelease("Title German Subs 1080p.mkv").subtitleSummary)
        // ... otherwise neutral ...
        val neutral = SourceRowClassifier.parseRelease("[Group] Title Italian S01E01.mkv")
        assertNull(neutral.audioSummary)
        assertNull(neutral.subtitleSummary)
        // ... and Japanese is never a dub signal (original language).
        val jp = SourceRowClassifier.parseRelease("Title Japanese Audio 1080p.mkv")
        assertNull(jp.audioSummary)
    }


    // ---- human naming -------------------------------
    //
    // Stereo collapse (AAC 2.0 -> AAC): stereo is the default, channels
    // shown only when multichannel.
    @Test
    fun naming_impliedSubs_yieldsToExplicit() {
        // Explicit flags win ...
        val flags = SourceRowClassifier.parseRelease(
            "[SubsPlume] Title [Multi-Subs] 🇬🇧 🇵🇱.mkv",
        )
        assertEquals("🇬🇧 🇵🇱", flags.subtitleSummary)
        // ... and so do bare title markers (VOSTFR materializes 🇫🇷).
        val marked = SourceRowClassifier.parseRelease("[SubsPlume] Title VOSTFR.mkv")
        assertEquals("🇫🇷", marked.subtitleSummary)
    }

    // ---- bare HDR grammar ------------------------
    //
    // scene-guide P2P/scene forms: bare and dot-delimited HDR / HDR10 /
    // HDR10+ / HDR10Plus / DV / Dolby Vision. Pill priority unchanged
    // (HDR10+ > HDR10 > DV > HDR); raw title/details keep everything.

    @Test
    fun standards_trashFixture_fullParse() {
        // Mandatory scene-guide fixture.
        val f = SourceRowClassifier.parseRelease(
            "The.Series.Title's!.2010.S01E01.Episode.Title.1.ATVP.WEBDL-2160p.EAC3.Atmos.5.1.DV.HDR10Plus.h265-RlsGrp",
        )
        assertEquals("RlsGrp", f.releaseGroup)
        assertEquals("2160p", f.resolution)
        assertEquals("HEVC", f.codec)
        assertEquals("HDR10+", f.hdrVariant)
        assertEquals("Apple TV+", f.sourceService)
        assertEquals("WEBDL", f.sourceClass)
        // Deterministic priority: HDR10+ wins over co-present DV; the
        // raw name is preserved for details.
        assertEquals(
            "The.Series.Title's!.2010.S01E01.Episode.Title.1.ATVP.WEBDL-2160p.EAC3.Atmos.5.1.DV.HDR10Plus.h265-RlsGrp",
            f.releaseName,
        )
    }

    @Test
    fun standards_bareHdrForms_detected() {
        assertEquals("HDR10+",
            SourceRowClassifier.parseRelease("Title S01E01 HDR10+.mkv").hdrVariant)
        assertEquals("HDR10+",
            SourceRowClassifier.parseRelease("Title.S01E01.HDR10Plus.mkv").hdrVariant)
        assertEquals("HDR10",
            SourceRowClassifier.parseRelease("Title S01E01 HDR10.mkv").hdrVariant)
        assertEquals("Dolby Vision",
            SourceRowClassifier.parseRelease("Title.S01E01.DV.mkv").hdrVariant)
        assertEquals("Dolby Vision",
            SourceRowClassifier.parseRelease("Title S01E01 Dolby Vision.mkv").hdrVariant)
        assertEquals("HDR",
            SourceRowClassifier.parseRelease("Title S01E01 HDR.mkv").hdrVariant)
    }

    @Test
    fun standards_hdrSubstringsInWords_rejected() {
        // ADV Films, DVDRip, HDRip must not trigger HDR/DV.
        assertNull(SourceRowClassifier.parseRelease("[ADV] Title S01E01.mkv").hdrVariant)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 DVDRip.mkv").hdrVariant)
        assertNull(SourceRowClassifier.parseRelease("Title S01E01 HDRip.mkv").hdrVariant)
        // HDR10 must not be shadowed by a bare-HDR read of its prefix.
        assertEquals("HDR10",
            SourceRowClassifier.parseRelease("Title HDR10.mkv").hdrVariant)
    }

    // ---- dual-audio grammar ----------------------

    @Test
    fun standards_dualAudioSeparators_detected() {
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Dual Audio 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Dual-Audio 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Dual_Audio 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title.Dual.Audio.1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("[Dual] Title S01E01.mkv").audioSummary)
    }

    @Test
    fun standards_langPairs_detected() {
        for (pair in listOf("JA+EN", "ZH+EN", "KO+EN", "EN+JA", "EN+ZH", "EN+KO")) {            assertEquals("Dual ($pair)",
                "Dual",
                SourceRowClassifier.parseRelease("Title S01E01 $pair 1080p.mkv").audioSummary)
        }
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Japanese + English 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title Korean-English 1080p.mkv").audioSummary)
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("Title English + Korean 1080p.mkv").audioSummary)
        // JP shorthand pair from the live corpus ([JP-EN] rows).
        assertEquals("Dual",
            SourceRowClassifier.parseRelease("[JP-EN] Title S01 [2020] 1080p.mkv").audioSummary)
    }

    @Test
    fun standards_dualNegatives_rejected() {
        // Substrings inside ordinary words ...
        assertNull(SourceRowClassifier.parseRelease("Title individual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title gradual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title dualism S01E01.mkv").audioSummary)
        // ... Japanese-only text, lone EN token, provider name.
        assertNull(SourceRowClassifier.parseRelease("Title Japanese S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("[Group] Title S01E01 [ENG].mkv").audioSummary)
    }

    // ---- underscore dub grammar ------------------

    @Test
    fun standards_underscoreDubs_detected() {
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title eng_dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title english_dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title funi_dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title English.Dub 1080p.mkv").audioSummary)
        assertEquals("Dub",
            SourceRowClassifier.parseRelease("Title English-Dub 1080p.mkv").audioSummary)
    }

    @Test
    fun standards_redubbed_rejected() {
        assertNull(SourceRowClassifier.parseRelease("Title redubbed 1080p.mkv").audioSummary)
    }

    // ---- DD+ / PCM / DTS:X -----------------------

    @Test
    fun standards_ddPlus_normalised() {
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 2.0.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 5.1.mkv").audioFormat)
        // DD+ family retained, not silently reduced to Atmos.
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+ Atmos.mkv").audioFormat)
    }

    @Test
    fun standards_pcm_normalised() {
        assertEquals("PCM",
            SourceRowClassifier.parseRelease("[Group] Title PCM.mkv").audioFormat)
        assertEquals("PCM",
            SourceRowClassifier.parseRelease("[Group] Title PCM 2.0.mkv").audioFormat)
        assertEquals("LPCM",
            SourceRowClassifier.parseRelease("[Group] Title LPCM 2.0.mkv").audioFormat)
    }

    @Test
    fun standards_dtsX_preserved() {
        assertEquals("DTS X",
            SourceRowClassifier.parseRelease("[Group] Title DTS X.mkv").audioFormat)
        assertEquals("DTS-X",
            SourceRowClassifier.parseRelease("[Group] Title DTS-X.mkv").audioFormat)
        assertEquals("DTS:X",
            SourceRowClassifier.parseRelease("[Group] Title DTS:X.mkv").audioFormat)
    }

    // ---- service aliases -------------------------

    @Test
    fun standards_serviceStandalone_allMapEntries() {
        // Bracketed ...
        for ((alias, service) in mapOf(
            "NF" to "Netflix", "AMZN" to "Amazon", "CR" to "Crunchyroll",
            "DSNP" to "Disney+", "HULU" to "Hulu", "AT-X" to "AT-X",
            "ATVP" to "Apple TV+",
        )) {
            assertEquals("$alias bracket", service,
                SourceRowClassifier.parseRelease("1080p [Group] Title [$alias] S01E01.mkv").sourceService)
            // ... spaced ...
            assertEquals("$alias spaced", service,
                SourceRowClassifier.parseRelease("1080p [Group] Title $alias S01E01.mkv").sourceService)
            // ... and dot-delimited.
            assertEquals("$alias dotted", service,
                SourceRowClassifier.parseRelease("Title.S01E01.$alias.WEBDL.mkv").sourceService)
        }
    }

    @Test
    fun standards_serviceAliases_neverGroupsOrPills() {
        val f = SourceRowClassifier.parseRelease("[ATVP] Title S01E01.mkv")
        assertNull(f.releaseGroup)
        assertEquals("Apple TV+", f.sourceService)
    }

    // ---- extension-set consistency ---------------

    @Test
    fun standards_releaseFilename_allKnownExtensions() {
        for (ext in listOf(
            "mkv", "mp4", "avi", "webm", "ts", "m4v",
            "m2ts", "mov", "flv", "rmvb",
        )) {
            val row = SourceRowSummarizer.build(
                displayLabel = "Relayaddon\n[Group] Title S01E01.$ext",
                qualityText = "Relayaddon\n[Group] Title S01E01.$ext",
                formatName = "CONTAINER",
                url = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567",
            )
            assertEquals("RELEASE for .$ext", SourcePresentationKind.RELEASE, row.presentation)
        }
        // A bare name with none of the known extensions stays STREAM.
        val stream = SourceRowSummarizer.build(
            displayLabel = "Hostone - Sub - 1080p",
            qualityText = "Hostone - Sub - 1080p",
            formatName = "M3U8",
            url = "https://example.com/hostone/playlist.m3u8",
        )
        assertEquals(SourcePresentationKind.STREAM, stream.presentation)
    }


    // ---- full-language separators --------------

    @Test
    fun standards21_fullLanguageUnderscoreAndSpace() {
        for (title in listOf(
            "Title Japanese_English 1080p.mkv",
            "Title Chinese_English 1080p.mkv",
            "Title Korean_English 1080p.mkv",
            "Title English_Japanese 1080p.mkv",
            "Title English_Chinese 1080p.mkv",
            "Title English_Korean 1080p.mkv",
            "Title Japanese English 1080p.mkv",
            "Title Chinese English 1080p.mkv",
            "Title Korean English 1080p.mkv",
            "Title English Japanese 1080p.mkv",
            "Title English Chinese 1080p.mkv",
            "Title English Korean 1080p.mkv",
        )) {
            assertEquals("Dual ($title)", "Dual",
                SourceRowClassifier.parseRelease(title).audioSummary)
        }
    }

    @Test
    fun standards21_singleLanguageWords_stayNull() {
        assertNull(SourceRowClassifier.parseRelease("Title Japanese S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title English S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title individual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title gradual S01E01.mkv").audioSummary)
        assertNull(SourceRowClassifier.parseRelease("Title dualism S01E01.mkv").audioSummary)
    }

    // ---- DD vs DD+ separation ------------------

    @Test
    fun standards21_plainDd_staysDd() {
        assertEquals("DD", SourceRowClassifier.parseRelease("[Group] Title DD.mkv").audioFormat)
        assertEquals("DD", SourceRowClassifier.parseRelease("[Group] Title DD 2.0.mkv").audioFormat)
        assertEquals("DD", SourceRowClassifier.parseRelease("[Group] Title DD2.0.mkv").audioFormat)
        assertEquals("DD 5.1", SourceRowClassifier.parseRelease("[Group] Title DD 5.1.mkv").audioFormat)
        assertEquals("DD 5.1", SourceRowClassifier.parseRelease("[Group] Title DD5.1.mkv").audioFormat)
    }

    @Test
    fun standards21_ac3Channels_preserved() {
        assertEquals("AC-3", SourceRowClassifier.parseRelease("[Group] Title AC3.mkv").audioFormat)
        assertEquals("AC-3", SourceRowClassifier.parseRelease("[Group] Title AC3 2.0.mkv").audioFormat)
        assertEquals("AC-3 5.1", SourceRowClassifier.parseRelease("[Group] Title AC3 5.1.mkv").audioFormat)
    }

    @Test
    fun standards21_ddpFamily_keptSeparate() {
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DDP.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP 5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 2.0.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD+ 5.1.mkv").audioFormat)
    }

    // ---- canonical E-AC-3 -----------------------

    @Test
    fun standards21_eac3Spellings_canonical() {
        for (spell in listOf("EAC3", "E-AC3", "EAC-3", "E-AC-3")) {
            assertEquals("E-AC-3 ($spell)", "E-AC-3",
                SourceRowClassifier.parseRelease("[Group] Title $spell.mkv").audioFormat)
        }
        assertEquals("E-AC-3",
            SourceRowClassifier.parseRelease("[Group] Title EAC3 2.0.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title EAC3 5.1.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title E-AC3 5.1.mkv").audioFormat)
    }

    // ---- DTS-HD tails --------------------------

    @Test
    fun standards21_dtsHdMa_canonical() {
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD MA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS.HD.MA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS HD MA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HDMA.mkv").audioFormat)
        assertEquals("DTS-HD MA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HDMA.2.0.mkv").audioFormat)
        assertEquals("DTS-HD MA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD MA 5.1.mkv").audioFormat)
        assertEquals("DTS-HD MA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS.HD.MA.5.1.mkv").audioFormat)
        assertEquals("DTS-HD MA 7.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD MA 7.1.mkv").audioFormat)
    }

    @Test
    fun standards21_dtsHdHra_canonical() {
        assertEquals("DTS-HD HRA",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD HRA.mkv").audioFormat)
        assertEquals("DTS-HD HRA",
            SourceRowClassifier.parseRelease("[Group] Title DTS HD HRA.mkv").audioFormat)
        assertEquals("DTS-HD HRA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS-HD HRA 5.1.mkv").audioFormat)
    }

    @Test
    fun standards21_dtsGuard_staysNonGreedy() {
        // The old safety case: codec/group text must not be swallowed.
        assertEquals("DTS-HD",
            SourceRowClassifier.parseRelease("Title DTS-HD.X265-IQHD.mkv").audioFormat)
        // Accepted DTS:X forms stay green.
        assertEquals("DTS:X",
            SourceRowClassifier.parseRelease("[Group] Title DTS:X.mkv").audioFormat)
        assertEquals("DTS",
            SourceRowClassifier.parseRelease("[Group] Title DTS.mkv").audioFormat)
    }


    // ---- current single-⚙ semantics -----------
    //
    // The 784-row corpus has zero 📂 rows: current Relayaddon payloads
    // carry the provider in ⚙️, so lone ⚙️ is tracker/provenance and
    // NEVER a release group. Legacy two-marker (📂+⚙️) coverage stays.

    @Test
    fun current_bracketGroupWithProvider() {
        val f = SourceRowClassifier.parseRelease(
            "1080p\n" +
                "[SubsPlume] Some Anime - 01 [1080p].mkv\n" +
                "👤 10 💾 1.2 GB ⚙️ IdxOne",
        )
        assertEquals("SubsPlume", f.releaseGroup)
        assertEquals("IdxOne", f.tracker)
    }

    @Test
    fun current_providerWithNoGroup() {
        val f = SourceRowClassifier.parseRelease(
            "1080p\n" +
                "Some.Show.S01E01.1080p.x265.mkv\n" +
                "👤 10 💾 1.2 GB ⚙️ IndexCee",
        )
        assertNull("no group present: null is correct", f.releaseGroup)
        assertEquals("IndexCee", f.tracker)
    }

    @Test
    fun current_suffixGroupWithProvider() {
        // Real French-row shape from the returned corpus.
        val f = SourceRowClassifier.parseRelease(
            "1080p\n" +
                "L'attaque des Titans (Shingeki No Kyojin) Saison 1 MULTI 1080p HDTV\n" +
                "Shingeki.No.Kyojin.S01E01.MULTi.1080p.BluRay.REMUX.AVC.DTS-HDMA.2.0-Grpro.mkv\n" +
                "👤 1 💾 4.86 GB ⚙️ Torrent9\n" +
                "Dubbed / Multi Audio / 🇫🇷",
        )
        assertEquals("Grpro", f.releaseGroup)
        assertEquals("Torrent9", f.tracker)
    }

    @Test
    fun legacy_twoMarkerShape_preserved() {
        // Legacy/two-marker compatibility fixture (NOT current format).
        val f = SourceRowClassifier.parseRelease(
            "📂 IdxOne\n" +
                "⚙️ SubsPlume\n" +
                "Title.mkv",
        )
        assertEquals("IdxOne", f.tracker)
        assertEquals("SubsPlume", f.releaseGroup)
    }

    @Test
    fun legacy_sameTrackerAndCog_noGroupFabrication() {
        val f = SourceRowClassifier.parseRelease(
            "📂 IdxOne\n" +
                "⚙️ IdxOne\n" +
                "Title.mkv",
        )
        assertEquals("IdxOne", f.tracker)
        assertNull("cog equal to tracker must not become a group", f.releaseGroup)
    }


    // ---- bounded channel delimiters ---------

    @Test
    fun standards22_channelDelimiters_normalised() {
        // Dot/underscore/hyphen channel separators resolve exactly
        // like spaced forms; 2.0 collapses, 5.1+ preserved.
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD.5.1.mkv").audioFormat)
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD-5.1.mkv").audioFormat)
        assertEquals("DD 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD_5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP.5.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DD+.5.1.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title EAC3.5.1.mkv").audioFormat)
        assertEquals("E-AC-3 5.1",
            SourceRowClassifier.parseRelease("[Group] Title E-AC3.5.1.mkv").audioFormat)
        assertEquals("FLAC 5.1",
            SourceRowClassifier.parseRelease("[Group] Title FLAC.5.1.mkv").audioFormat)
        assertEquals("PCM 5.1",
            SourceRowClassifier.parseRelease("[Group] Title PCM.5.1.mkv").audioFormat)
        assertEquals("DTS-HD MA 5.1",
            SourceRowClassifier.parseRelease("[Group] Title DTS.HD.MA.5.1.mkv").audioFormat)
        assertEquals("DD 6.1",
            SourceRowClassifier.parseRelease("[Group] Title DD 6.1.mkv").audioFormat)
        assertEquals("E-AC-3 / DDP 7.1",
            SourceRowClassifier.parseRelease("[Group] Title DDP 7.1.mkv").audioFormat)
    }


    // ---- flags, priority, clean titles ---------------
    //
    // FLAGS (not text) for subtitle languages; deterministic priority
    // with subtitle visibility outranking codec; aggressive clean
    // titles driven by confidently parsed tokens; raw names preserved.

    @Test
    fun v3_flagMapping_codesBecomeFlags() {
        val mapper = SourceRowSummarizer.LanguageFlagMapper
        assertEquals("🇬🇧", mapper.flagFor("ENG"))
        assertEquals("🇧🇷", mapper.flagFor("POR-BR"))
        assertEquals("🇲🇽", mapper.flagFor("SPA-LA"))
        assertEquals("🇫🇷", mapper.flagFor("FRE"))
        assertEquals("🇯🇵", mapper.flagFor("JPN"))
        assertEquals("🇬🇧 🇧🇷", mapper.mapJoined("ENG+POR-BR"))
        assertEquals("🇪🇸 🇬🇧", mapper.mapJoined("ESP-ENG"))
    }

    @Test
    fun v3_flagMapping_langSubSuffix() {
        // `ENG-SUB` is English subtitles (live JPN+ENG-SUB rows).
        assertEquals("🇬🇧", SourceRowSummarizer.LanguageFlagMapper.mapJoined("ENG-SUB"))
    }

    @Test
    fun v3_flagMapping_unknownStaysText() {
        val mapper = SourceRowSummarizer.LanguageFlagMapper
        // Stateless/ambiguous codes must never show a wrong flag.
        assertNull(mapper.flagFor("YID"))
        assertNull(mapper.flagFor("MAL"))
        assertNull(mapper.flagFor("XYZ"))
        assertNull(mapper.mapJoined("ENG+XYZ"))
        // Multi-subs is not a language; it stays text.
        assertNull(mapper.mapJoined("Multi-subs"))
    }

    @Test
    fun v3_vostfrBecomesFlagPill() {
        val row = SourceRowSummarizer.build(
            displayLabel = "[Group] Title S01 BDRIP 1080p X265 10bit Vostfr.mkv",
            qualityText = "[Group] Title S01 BDRIP 1080p X265 10bit Vostfr.mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val sub = row.pills.firstOrNull { it.kind == SourcePillKind.SUB_LANG }
        assertNotNull(sub)
        assertEquals("🇫🇷", sub!!.text)
    }

    @Test
    fun v3_priorityNeverDropsSubs() {
        // Grpta-shaped dense row: res + codec + depth + audio + subs
        // must keep the subtitle pill (uncapped model, subs before codec).
        val f = SourceRowClassifier.parseRelease(
            "[Grpta] Title S01E01 [1080p][HEVC][10bit][DUAL AAC2.0][Multi-Subs].mkv",
        )
        val row = SourceRowSummarizer.build(
            displayLabel = f.releaseName,
            qualityText = f.releaseName,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val kinds = row.pills.map { it.kind }
        assertTrue("subs pill must survive dense rows",
            SourcePillKind.SUB_LANG in kinds)
        assertEquals("resolution stays first", SourcePillKind.QUALITY, kinds.first())
        val subIdx = kinds.indexOf(SourcePillKind.SUB_LANG)
        val codecIdx = kinds.indexOf(SourcePillKind.CODEC)
        assertTrue("subs outrank codec", subIdx < codecIdx)
    }

    @Test
    fun v3_hdrDuplicationRemovedFromCleanTitle() {
        // Live Grpsali shape (with its real metadata lines, as served):
        // leading HDR already shown as a chip.
        val qualityText =
            "HDR Frieren - Beyond Journey's End S1 - BD (1080p) (HDR) [Dual Audio].mkv\n" +
                "👤 49 💾 3.28 GB ⚙️ IdxOne"
        val f = SourceRowClassifier.parseRelease(qualityText)
        val row = SourceRowSummarizer.build(
            displayLabel = qualityText,
            qualityText = qualityText,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val clean = row.cleanSubtitle ?: ""
        assertFalse("leading HDR must not repeat the chip", clean.startsWith("HDR "))
        assertFalse("bracketed HDR must not repeat", clean.contains("[HDR]") || clean.contains("(HDR)"))
        // Raw name preserved for details/identity.
        assertEquals(f.releaseName, row.subtitle)
        assertTrue(row.details.any { it.label == "Raw release name" })
    }

    @Test
    fun v3_resolutionDuplicationRemoved() {
        val f = SourceRowClassifier.parseRelease(
            "1080p [Group] Title S01E01 [1080p][HEVC].mkv",
        )
        val clean = SourceRowSummarizer.build(
            displayLabel = f.releaseName,
            qualityText = f.releaseName,
            formatName = "CONTAINER",
            url = magnetUrl,
        ).cleanSubtitle ?: ""
        assertFalse(clean.contains("1080p"))
    }

    @Test
    fun v3_codecDuplicationRemovedOnlyWhenParsed() {
        // Parsed codec is stripped ...
        val parsed = SourceRowClassifier.parseRelease("[Group] Title [HEVC].mkv")
        val clean = SourceRowSummarizer.build(
            displayLabel = parsed.releaseName,
            qualityText = parsed.releaseName,
            formatName = "CONTAINER",
            url = magnetUrl,
        ).cleanSubtitle ?: ""
        assertFalse(clean.contains("[HEVC]"))
        // ... but ordinary title words are never eaten.
        val plain = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "My Special Title S01E01.mkv",
            releaseGroup = null,
            resolution = null,
            codec = null,
            bitDepth = null,
            sourceService = null,
        )
        assertTrue(plain.contains("Special"))
    }

    @Test
    fun v3_aggressiveTitleStripsCrcAndExtension() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "1080p [SubsPlume] Sousou no Frieren - 01 (1080p) [F02B9CEE].mkv",
            releaseGroup = "SubsPlume",
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
        )
        assertFalse(cleaned.contains("F02B9CEE"))
        assertFalse(cleaned.contains(".mkv"))
        assertFalse(cleaned.contains("(1080p)"))
        assertTrue(cleaned.contains("Sousou no Frieren - 01"))
    }

    @Test
    fun v3_batchPillAndStrip() {
        val f = SourceRowClassifier.parseRelease(
            "[Group] Title Complete Series + Movies 1080p.mkv",
        )
        assertTrue(f.batch)
        val row = SourceRowSummarizer.build(
            displayLabel = f.releaseName,
            qualityText = f.releaseName,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        val batch = row.pills.firstOrNull { it.kind == SourcePillKind.BATCH }
        assertNotNull("batch rows carry a Batch pill", batch)
        assertEquals("Batch", batch!!.text)
    }

    @Test
    fun v3_nullGroupFallsBackToCleanTitleHeader() {
        // No invented provider-as-header: the clean title becomes the
        // visual primary label. displayLabel carries the full server
        // name (with metadata lines), exactly as the adapter passes it.
        val qualityText =
            "Some.Show.S01E01.1080p.x265.mkv\n👤 10 💾 1.2 GB ⚙️ IndexCee"
        val row = SourceRowSummarizer.build(
            displayLabel = qualityText,
            qualityText = qualityText,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertNull(row.header?.takeIf { it == "IndexCee" })
        assertEquals("IndexCee", row.tracker)
        assertFalse("header must not be blank", row.header.isNullOrBlank())
    }

    @Test
    fun v3_groupProviderSemanticsUnchanged() {
        // Spot checks that v2.2 semantics survived the redesign.
        val bracket = SourceRowClassifier.parseRelease("1080p [SubsPlume] Title S01E01.mkv")
        assertEquals("SubsPlume", bracket.releaseGroup)
        val suffix = SourceRowClassifier.parseRelease("Title S01E01 1080p-GRPVY.mkv")
        assertEquals("GRPVY", suffix.releaseGroup)
        val legacy = SourceRowClassifier.parseRelease("📂 IdxOne\n⚙️ SubsPlume\nTitle.mkv")
        assertEquals("SubsPlume", legacy.releaseGroup)
        assertEquals("IdxOne", legacy.tracker)
    }

    @Test
    fun v3_streamUnaffectedByTorrentPresentation() {
        // STREAM rows keep exactly quality + track pills; the "Sub"
        // track label is NOT flag-mapped (track semantics, UTF text).
        val row = SourceRowSummarizer.build(
            displayLabel = "Hostone - Sub - 1080p",
            qualityText = "Hostone - Sub - 1080p",
            formatName = "M3U8",
            url = "https://example.com/hostone/playlist.m3u8",
        )
        assertEquals(SourcePresentationKind.STREAM, row.presentation)
        assertEquals(
            listOf(SourcePillKind.QUALITY, SourcePillKind.AUDIO_MODE),
            row.pills.map { it.kind },
        )
        assertEquals("Sub", row.pills[1].text)
        assertNull("no tracker cue on streams", row.tracker)
    }

    @Test
    fun v3_layoutPolicyData_noBlanksNoNewlines() {
        // Header-row policy data: every pill label is single-line and
        // non-blank so the single clipped Flexbox line never breaks.
        val f = SourceRowClassifier.parseRelease(
            "[Grpta] Title S01E01 [1080p][HEVC][10bit][DUAL AAC2.0][Multi-Subs][GRPVY].mkv",
        )
        val row = SourceRowSummarizer.build(
            displayLabel = f.releaseName,
            qualityText = f.releaseName,
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertTrue(row.pills.isNotEmpty())
        for (pill in row.pills) {
            assertTrue("blank pill label", pill.text.isNotBlank())
            assertFalse("newline in pill", pill.text.contains("\n"))
        }
        assertFalse("header must not be blank", row.header.isNullOrBlank())
        assertFalse("header must be single-line", row.header!!.contains("\n"))
    }


    // ---- aggressive clean title + critical/optional seam ----

    @Test
    fun cleanTitleV31_stripsUnbracketedCodecDepthRes() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "[GRPEM] Kaijuu 8 Gou S02E05 V3 2160p HEVC 10-bit AAC",
            releaseGroup = "GRPEM",
            resolution = "2160p",
            codec = "HEVC",
            bitDepth = "10-bit",
            sourceService = null,
            audioFormat = "AAC",
        )
        assertEquals("Kaijuu 8 Gou S02E05 V3", cleaned)
    }

    @Test
    fun cleanTitleV31_strips4kAliasFor2160p() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Title 4K 2160p S01E01",
            releaseGroup = null,
            resolution = "2160p",
            codec = null,
            bitDepth = null,
            sourceService = null,
        )
        assertEquals("Title S01E01", cleaned)
    }

    @Test
    fun cleanTitleV31_stripsAudioFormatUniverseAndMarker() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "[GQM] Frieren S01E01 [TrueHD] [Audio 5.1] [FLAC] [1080p]",
            releaseGroup = "GQM",
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            audioFormat = "TrueHD",
        )
        assertEquals("Frieren S01E01", cleaned)
    }

    @Test
    fun cleanTitleV31_stripsAudioCompoundChannels() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Isekai S01 (480p AAC 2.0 x264)",
            releaseGroup = null,
            resolution = "480p",
            codec = "AVC",
            bitDepth = null,
            sourceService = null,
            audioFormat = "AAC",
        )
        assertEquals("Isekai S01", cleaned)
    }

    @Test
    fun cleanTitleV31_stripsHdrUnbracketed() {
        val plus = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Title HDR10+ 1080p S01E01",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            hdrVariant = "HDR10+",
        )
        assertEquals("Title S01E01", plus)
        val dv = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Title DV 1080p S01E01",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            hdrVariant = "Dolby Vision",
        )
        assertEquals("Title S01E01", dv)
    }

    @Test
    fun cleanTitleV31_dubNonLeadingStripsButLeadingStays() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Dub Chronicles S01E01 DUBBED",
            releaseGroup = null,
            resolution = null,
            codec = null,
            bitDepth = null,
            sourceService = null,
            audioSummary = "Dub",
        )
        assertEquals("Dub Chronicles S01E01", cleaned)
        val mid = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Show DUB S01E01",
            releaseGroup = null,
            resolution = null,
            codec = null,
            bitDepth = null,
            sourceService = null,
            audioSummary = "Dub",
        )
        assertEquals("Show S01E01", mid)
    }

    @Test
    fun cleanTitleV31_stripsSubMarkersButKeepsSingleCode() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Sousou no Frieren - 12 [1080p] [Multi-Subs] ENG+JPN",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            subtitleSummary = "Multi-subs",
            subtitleLanguages = listOf("ENG", "JPN"),
        )
        assertEquals("Sousou no Frieren - 12", cleaned)
        // A lone code is never a cluster/bracket strip target.
        val kept = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Title ENG 1080p S01E01",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            subtitleLanguages = listOf("ENG"),
        )
        assertTrue("lone ENG code must stay", kept.contains("ENG"))
    }

    @Test
    fun cleanTitleV31_stripsEpisodeGluedVostfr() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Frieren S02 E01VOSTFR VOSTFR",
            releaseGroup = null,
            resolution = null,
            codec = null,
            bitDepth = null,
            sourceService = null,
            subtitleSummary = "Multi-subs",
        )
        assertEquals("Frieren S02 E01", cleaned)
    }

    @Test
    fun cleanTitleV31_stripsBatchPackPhrase() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Re:Creators - Batch - Complete Series + Movies (1080p)",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            batch = true,
        )
        assertEquals("Re:Creators", cleaned)
    }

    @Test
    fun cleanTitleV31_bareCompleteTitleWordStays() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Complete Monster S01 1080p",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            batch = true,
        )
        assertEquals("Complete Monster S01", cleaned)
    }

    @Test
    fun cleanTitleV31_dottedTitleRespaced() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Kaijuu.8.Gou.S02E05.V3.1080p.FLAC",
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            audioFormat = "FLAC",
        )
        assertEquals("Kaijuu 8 Gou S02E05 V3", cleaned)
    }

    @Test
    fun cleanTitleV31_keepsEpisodeIdentityAndCompounds() {
        // S01/E01/EP-shapes, version tags, glued quality+fps and
        // single-letter-adjacent codec lookalikes are never stripped.
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Title S01E01 EP12 01v2 AVC1 2160p60",
            releaseGroup = null,
            resolution = "2160p",
            codec = "AVC",
            bitDepth = null,
            sourceService = null,
        )
        assertEquals("Title S01E01 EP12 01v2 AVC1 2160p60", cleaned)
        val subsKept = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "PlumbingSubs Title S01E01",
            releaseGroup = null,
            resolution = null,
            codec = null,
            bitDepth = null,
            sourceService = null,
            subtitleSummary = "Multi-subs",
        )
        assertTrue("PlumbingSubs must stay", subsKept.contains("PlumbingSubs"))
    }

    @Test
    fun cleanTitleV31_stripsTrailingSuffixGroup() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Link Click S2 -Grpro",
            releaseGroup = "Grpro",
            resolution = null,
            codec = null,
            bitDepth = null,
            sourceService = null,
        )
        assertEquals("Link Click S2", cleaned)
    }

    @Test
    fun cleanTitleV31_stripsWxHAndParenTechBlock() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Kaijuu 8-gou S2 - 05 (CR 1920x1080 x264 AAC) [5D17D446]",
            releaseGroup = null,
            resolution = "1080p",
            codec = "AVC",
            bitDepth = null,
            sourceService = "Crunchyroll",
            audioFormat = "AAC",
        )
        assertEquals("Kaijuu 8-gou S2 - 05", cleaned)
    }

    @Test
    fun cleanTitleV31_stripsFpsChapterTtmlAndDualAudio() {
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "[GQM] Sousou no Frieren S01E28 V2 (23.976FPS) " +
                "[TrueHD] [Audio 5.1] [TTML] Multi-Sub [FLAC] [Chapter] [1080p]",
            releaseGroup = "GQM",
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
            audioFormat = "TrueHD",
            subtitleSummary = "Multi-subs",
        )
        assertEquals("Sousou no Frieren S01E28 V2", cleaned)
    }

    @Test
    fun cleanTitleV31_pipelineDolbyRemuxEndToEnd() {
        // Full parseRelease -> cleanDisplayTitle pipeline, mirroring
        // buildRelease: every expectation is parser-driven, no
        // hand-passed values.
        val raw = "[RQEVN] Kaijuu 8 Gou S02E05 V3 BluRay REMUX " +
            "1080p DTS-HD MA 5.1, FLAC 2.0 x265 10-Bit -RQEVN"
        val f = SourceRowClassifier.parseRelease(raw)
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = f.releaseName,
            releaseGroup = f.releaseGroup,
            resolution = f.resolution,
            codec = f.codec,
            bitDepth = f.bitDepth,
            sourceService = f.sourceService,
            hdrVariant = f.hdrVariant,
            audioSummary = f.audioSummary,
            batch = f.batch,
            audioFormat = f.audioFormat,
            subtitleSummary = f.subtitleSummary,
            subtitleLanguages = f.subtitleLanguages,
            sourceClass = f.sourceClass,
        )
        assertEquals("Kaijuu 8 Gou S02E05 V3", cleaned)
        // Raw is preserved for identity/selection.
        assertEquals(raw, f.releaseName)
    }

    @Test
    fun cleanTitleV31_pipelineWebDualAudioEndToEnd() {
        val raw = "[GrpsHubb] Isekai Shikaku S01 (CR WEB 480p AAC 2.0 x264) [Dual-Audio]"
        val f = SourceRowClassifier.parseRelease(raw)
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = f.releaseName,
            releaseGroup = f.releaseGroup,
            resolution = f.resolution,
            codec = f.codec,
            bitDepth = f.bitDepth,
            sourceService = f.sourceService,
            hdrVariant = f.hdrVariant,
            audioSummary = f.audioSummary,
            batch = f.batch,
            audioFormat = f.audioFormat,
            subtitleSummary = f.subtitleSummary,
            subtitleLanguages = f.subtitleLanguages,
            sourceClass = f.sourceClass,
        )
        assertEquals("Isekai Shikaku S01", cleaned)
    }

    @Test
    fun cleanTitleV31_detectsParenTechBlockServiceAlias() {
        // v3.1 parser extension: the standalone-alias delimiters
        // include parens/brackets, so `(CR WEB …)` detects. Previously
        // detected forms are unchanged (guarded by the v2-era tests).
        assertEquals(
            "Crunchyroll",
            SourceRowClassifier.detectSourceService(
                "[GrpsHubb] Isekai Shikaku S01 (CR WEB 480p AAC 2.0 x264) [Dual-Audio]"),
        )
    }

    @Test
    fun cleanTitleV31_withSubtitlesContentWordStays() {
        // Reviewer fixture: `with Subtitles.` is title content even
        // though a subs summary parsed (from the word itself). Only
        // abbreviated/marker sub forms strip.
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "[GrpsHubb] True Remembrance with Subtitles. DUAL-AUDIO 576p x264 AAC",
            releaseGroup = "GrpsHubb",
            resolution = "576p",
            codec = "AVC",
            bitDepth = null,
            sourceService = null,
            audioSummary = "Dual",
            audioFormat = "AAC",
            subtitleSummary = "Multi-subs",
        )
        assertEquals("True Remembrance with Subtitles.", cleaned)
    }

    @Test
    fun cleanTitleV31_webDlWholeAndCodecSceneSuffix() {
        // `WEB-DL` strips whole (never a `DL` residue) and the scene
        // `codec-GROUP` tail goes with its codec. `MAX` stays: it is
        // an English word too, left for the reviewer (open item).
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Smiling Friends S02E05 1080p MAX WEB-DL DDP2.0 x264-PQW",
            releaseGroup = null,
            resolution = "1080p",
            codec = "AVC",
            bitDepth = null,
            sourceService = null,
            audioFormat = "E-AC-3 / DDP",
            sourceClass = "WEB-DL",
        )
        assertEquals("Smiling Friends S02E05 MAX", cleaned)
    }

    @Test
    fun cleanTitleV31_codecUniverseStripsForeignFamily() {
        // An HEVC row carrying a stray H.264 token still strips it
        // (codec tokens are never title content); compounds like
        // `AVC1` stay protected by boundaries.
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = "Show S01E01 HEVC H.264 1080p AVC1",
            releaseGroup = null,
            resolution = "1080p",
            codec = "HEVC",
            bitDepth = null,
            sourceService = null,
        )
        assertEquals("Show S01E01 AVC1", cleaned)
    }

    @Test
    fun cleanTitleV31_trailingTagStripsOnlyGroupShapes() {
        fun trailing(input: String) = SourceRowClassifier.cleanDisplayTitle(
            releaseName = input,
            releaseGroup = null,
            resolution = "1080p",
            codec = null,
            bitDepth = null,
            sourceService = null,
        )
        assertEquals("Sousou no Frieren - 12",
            trailing("Sousou no Frieren - 12 [1080p] [Gira-Fansub].mkv"))
        // Language codes, episode shapes and versions are not tags.
        assertTrue(trailing("Title [1080p] [ENG]").contains("[ENG]"))
        assertTrue(trailing("Title [1080p] [S01]").contains("[S01]"))
        assertTrue(trailing("Title [1080p] [V2]").contains("[V2]"))
    }

    // ---- audio-universe closure (source of truth) ----

    @Test
    fun cleanTitleV32_pcmShapesStripParserDriven() {
        // Mandatory shapes, each through parser-driven values: parse
        // first, then clean with exactly what the parser produced.
        val cases = listOf(
            "Title S01E01 [PCM]" to "PCM",
            "Title S01E01 [PCM 2.0]" to "PCM",
            "Title S01E01 [PCM 5.1]" to "PCM 5.1",
            "Title S01E01 [LPCM]" to "LPCM",
            // Parser quirk (pre-existing, out of scope): the generic
            // letters+digit rewrite emits `MP 2`, exactly as it emits
            // `MP 3` for bare MP3. The clean title still strips the raw
            // `MP2` token via the universe.
            "Title S01E01 [MP2]" to "MP 2",
        )
        for ((raw, expectedFormat) in cases) {
            val f = SourceRowClassifier.parseRelease(raw)
            assertEquals("format for $raw", expectedFormat, f.audioFormat)
            val cleaned = SourceRowClassifier.cleanDisplayTitle(
                releaseName = f.releaseName,
                releaseGroup = f.releaseGroup,
                resolution = f.resolution,
                codec = f.codec,
                bitDepth = f.bitDepth,
                sourceService = f.sourceService,
                hdrVariant = f.hdrVariant,
                audioSummary = f.audioSummary,
                batch = f.batch,
                audioFormat = f.audioFormat,
                subtitleSummary = f.subtitleSummary,
                subtitleLanguages = f.subtitleLanguages,
                sourceClass = f.sourceClass,
            )
            assertEquals("clean for $raw", "Title S01E01", cleaned)
        }
    }

    @Test
    fun cleanTitleV32_datasetPcmRowsStrip() {
        // Exact frozen-corpus PCM shapes (dataset file lines 583, 593:
        // JJK `PCM 2.0` / bare `PCM`; line 716: smashed `PCM20`).
        val row583 = """
            [JP-EN] Jujutsu Kaisen : Season 01 S01 [2020-2021] COMPLETE 1080p Hybrid ITA BDRip x265 PCM 2.0 Kira [SQV]
            Jujutsu Kaisen - Episode 01 - Ryōmen Sukuna 1080p Hybrid ITA BDRip x265 PCM 2.0 Kira [SQV].mkv
            👤 9 💾 2.42 GB ⚙️ IdxOne
            🇬🇧 / 🇮🇹
        """.trimIndent()
        val f583 = SourceRowClassifier.parseRelease(row583)
        assertEquals("PCM", f583.audioFormat)
        val c583 = SourceRowClassifier.cleanDisplayTitle(
            releaseName = f583.releaseName,
            releaseGroup = f583.releaseGroup,
            resolution = f583.resolution,
            codec = f583.codec,
            bitDepth = f583.bitDepth,
            sourceService = f583.sourceService,
            hdrVariant = f583.hdrVariant,
            audioSummary = f583.audioSummary,
            batch = f583.batch,
            audioFormat = f583.audioFormat,
            subtitleSummary = f583.subtitleSummary,
            subtitleLanguages = f583.subtitleLanguages,
            sourceClass = f583.sourceClass,
        )
        assertFalse("PCM 2.0 must strip (row 583)", c583.contains("PCM"))

        val row593 = """
            Jujutsu Kaisen [BD Remux][Part -1] [1080p Dual Audio PCM AVC]
            S01E01-Ryomen Sukuna.mkv
            👤 3 💾 6.74 GB ⚙️ IdxOne
            Dubbed / Dual Audio
        """.trimIndent()
        val f593 = SourceRowClassifier.parseRelease(row593)
        assertEquals("PCM", f593.audioFormat)
        val c593 = SourceRowClassifier.cleanDisplayTitle(
            releaseName = f593.releaseName,
            releaseGroup = f593.releaseGroup,
            resolution = f593.resolution,
            codec = f593.codec,
            bitDepth = f593.bitDepth,
            sourceService = f593.sourceService,
            hdrVariant = f593.hdrVariant,
            audioSummary = f593.audioSummary,
            batch = f593.batch,
            audioFormat = f593.audioFormat,
            subtitleSummary = f593.subtitleSummary,
            subtitleLanguages = f593.subtitleLanguages,
            sourceClass = f593.sourceClass,
        )
        assertFalse("bare PCM must strip (row 593)", c593.contains("PCM"))

        val row716 = """
            Attack.on.Titan.S01.Bluray.REMUX.1080p.TRUHD.51.AC351.TRUHD20.AC320.PCM20.Eng+Jap.EngSubs
            Shingeki no Kyojin - E01 [BD 1080p Hybrid REMUX].mkv
            👤 33 💾 7.71 GB ⚙️ IdxOne
        """.trimIndent()
        val f716 = SourceRowClassifier.parseRelease(row716)
        val c716 = SourceRowClassifier.cleanDisplayTitle(
            releaseName = f716.releaseName,
            releaseGroup = f716.releaseGroup,
            resolution = f716.resolution,
            codec = f716.codec,
            bitDepth = f716.bitDepth,
            sourceService = f716.sourceService,
            hdrVariant = f716.hdrVariant,
            audioSummary = f716.audioSummary,
            batch = f716.batch,
            audioFormat = f716.audioFormat,
            subtitleSummary = f716.subtitleSummary,
            subtitleLanguages = f716.subtitleLanguages,
            sourceClass = f716.sourceClass,
        )
        assertFalse("smashed PCM20 must strip (row 716)", c716.contains("PCM20"))
        assertFalse("smashed AC351 must strip (row 716)", c716.contains("AC351"))
    }

    @Test
    fun cleanTitleV32_audioUniverseCoversEveryParserFamily() {
        // Guarantee: for every normalized base the parser can emit,
        // the clean-title universe holds a removable raw spelling.
        // Each row is parser-driven (format asserted from parseRelease)
        // and must reduce to the bare title.
        val families = listOf(
            "DDP2.0" to "E-AC-3 / DDP",
            "DD 5.1" to "DD 5.1",
            "EAC3" to "E-AC-3",
            "AC3" to "AC-3",
            "AAC" to "AAC",
            "FLAC 5.1" to "FLAC 5.1",
            "Dolby TrueHD 7.1" to "TrueHD 7.1",
            "DTS" to "DTS",
            "DTS-HD MA" to "DTS-HD MA",
            "Dolby Atmos" to "Atmos",
            // Same pre-existing letters+digit quirk as MP2.
            "MP3" to "MP 3",
            "OPUS" to "OPUS",
            "MP2" to "MP 2",
            "LPCM" to "LPCM",
            "PCM" to "PCM",
            "Dolby Digital Plus" to "E-AC-3 / DDP",
            "Dolby Digital" to "DD",
        )
        for ((token, expectedFormat) in families) {
            val raw = "Title S01E01 [$token]"
            val f = SourceRowClassifier.parseRelease(raw)
            assertEquals("format for $token", expectedFormat, f.audioFormat)
            val cleaned = SourceRowClassifier.cleanDisplayTitle(
                releaseName = f.releaseName,
                releaseGroup = f.releaseGroup,
                resolution = f.resolution,
                codec = f.codec,
                bitDepth = f.bitDepth,
                sourceService = f.sourceService,
                hdrVariant = f.hdrVariant,
                audioSummary = f.audioSummary,
                batch = f.batch,
                audioFormat = f.audioFormat,
                subtitleSummary = f.subtitleSummary,
                subtitleLanguages = f.subtitleLanguages,
                sourceClass = f.sourceClass,
            )
            assertEquals("clean for $token", "Title S01E01", cleaned)
        }
    }

    @Test
    fun cleanTitleV32_brokenChannelTailStripsWhole() {
        // General-provider junk `DDP2 0` (broken `DDP 2.0`) must strip
        // whole — never leave a stray `0` behind.
        val f = SourceRowClassifier.parseRelease("Title S01E01 [DDP2 0]")
        assertEquals("E-AC-3 / DDP", f.audioFormat)
        val cleaned = SourceRowClassifier.cleanDisplayTitle(
            releaseName = f.releaseName,
            releaseGroup = f.releaseGroup,
            resolution = f.resolution,
            codec = f.codec,
            bitDepth = f.bitDepth,
            sourceService = f.sourceService,
            hdrVariant = f.hdrVariant,
            audioSummary = f.audioSummary,
            batch = f.batch,
            audioFormat = f.audioFormat,
            subtitleSummary = f.subtitleSummary,
            subtitleLanguages = f.subtitleLanguages,
            sourceClass = f.sourceClass,
        )
        assertEquals("Title S01E01", cleaned)
    }

    // ---- critical/optional pill seam ----

    @Test
    fun seam_denseRowSplitsCriticalFirst() {
        val row = SourceRowSummarizer.build(
            displayLabel = "1080p [Group] Title S01E01 [HEVC] [10bit] " +
                "[HDR10] [DUAL] [AAC] [Multi-Subs].mkv",
            qualityText = "1080p [Group] Title S01E01 [HEVC] [10bit] " +
                "[HDR10] [DUAL] [AAC] [Multi-Subs].mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals(
            listOf(SourcePillKind.QUALITY, SourcePillKind.SUB_LANG),
            row.criticalPills.map { it.kind },
        )
        assertEquals(
            listOf(
                SourcePillKind.HDR10,
                SourcePillKind.AUDIO_MODE,
                SourcePillKind.CODEC,
                SourcePillKind.AUDIO_FMT,
            ),
            row.optionalPills.map { it.kind },
        )
    }

    @Test
    fun seam_splitIsCompleteAndOrderPreserving() {
        // No take(N) cap anywhere: critical + optional is exactly the
        // full model, each in pill order.
        val row = SourceRowSummarizer.build(
            displayLabel = "1080p [Group] Title S01E01 [HEVC] [10bit] " +
                "[HDR10] [DUAL] [AAC] [Multi-Subs].mkv",
            qualityText = "1080p [Group] Title S01E01 [HEVC] [10bit] " +
                "[HDR10] [DUAL] [AAC] [Multi-Subs].mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals(row.pills.size,
            row.criticalPills.size + row.optionalPills.size)
        assertEquals(
            row.pills.filter { it.kind != SourcePillKind.QUALITY && it.kind != SourcePillKind.SUB_LANG },
            row.optionalPills,
        )
    }

    @Test
    fun seam_resOnlyRowHasQualityCriticalOnly() {
        // RELEASE path needs a release filename (extension) or marker
        // line; a bare title with no group is STREAM by design.
        val row = SourceRowSummarizer.build(
            displayLabel = "[Group] Title S01E01 [720p].mkv",
            qualityText = "[Group] Title S01E01 [720p].mkv",
            formatName = "CONTAINER",
            url = magnetUrl,
        )
        assertEquals(SourcePresentationKind.RELEASE, row.presentation)
        assertEquals(
            listOf(SourcePillKind.QUALITY),
            row.criticalPills.map { it.kind },
        )
        assertTrue(row.optionalPills.isEmpty())
    }

    @Test
    fun seam_streamRowsHaveNoCriticalSplit() {
        // STREAM inline card renders its own compact pills; the
        // header critical/optional seam does not apply.
        val row = SourceRowSummarizer.build(
            displayLabel = "Hostone - Sub - 1080p",
            qualityText = "Hostone - Sub - 1080p",
            formatName = "M3U8",
            url = "https://example.com/hostone/playlist.m3u8",
        )
        assertEquals(SourcePresentationKind.STREAM, row.presentation)
        assertTrue(row.criticalPills.isEmpty())
        assertTrue(row.optionalPills.isEmpty())
        assertTrue("inline pills stay", row.pills.isNotEmpty())
    }


    // ---- bounded correction ------------------------------------

    private val sampleGrpbanyamaRaw =
        "4k [Grpbanyama] Frieren Beyond Journeys End Season 1 [IQIYI WebRip 2160p HEVC OPUS Multi-Subs] (Sousou no Frieren) [Grpbanyama] Frieren Beyond Journeys End - 01 [IQIYI WebRip 2160p HEVC OPUS Multi-Subs].mkv Multi Subs"
    private val sampleGrpsaliRaw =
        "1080p HDR [Grpsali] Frieren - Beyond Journey's End S1 - BD (1080p) (HDR) [Dual Audio] [Grpsali] Frieren - Beyond Journey's End - 01 (1080p) (HDR) [Dual Audio].mkv Dubbed / Dual Audio"

    /**
     * RELEASE-shaped quality text for summarizer end-to-end tests: the
     * bare sample raws do not end in a release filename (trailing
     * `… Dual Audio` / `… Multi Subs`), so they classify STREAM on
     * their own. The Relayaddon wrapper carries the 📂 provenance line
     * like real payloads.
     */
    private fun releaseQuality(raw: String): String = "Relayaddon\n📂 IdxOne\n$raw"

    private fun releaseRow(raw: String): SourceRowViewModel =
        SourceRowSummarizer.build(
            displayLabel = releaseQuality(raw),
            qualityText = releaseQuality(raw),
            formatName = "CONTAINER",
            url = magnetUrl,
        )

    private fun cleanOf(raw: String): String {
        val f = SourceRowClassifier.parseRelease(raw)
        return SourceRowClassifier.cleanDisplayTitle(
            releaseName = f.releaseName,
            releaseGroup = f.releaseGroup,
            resolution = f.resolution,
            codec = f.codec,
            bitDepth = f.bitDepth,
            sourceService = f.sourceService,
            hdrVariant = f.hdrVariant,
            audioSummary = f.audioSummary,
            batch = f.batch,
            audioFormat = f.audioFormat,
            subtitleSummary = f.subtitleSummary,
            subtitleLanguages = f.subtitleLanguages,
            sourceClass = f.sourceClass,
        )
    }

    @Test
    fun v33_grpbanyamaIqiyiServiceDetected() {
        // The Grpbanyama row carries raw `[IQIYI …]`
        // blocks; the additive alias resolves the canonical service.
        val f = SourceRowClassifier.parseRelease(sampleGrpbanyamaRaw)
        assertEquals("iQIYI", f.sourceService)
        assertEquals(
            "iQIYI",
            SourceRowClassifier.detectSourceService("[x] Show [IQIYI] 1080p"),
        )
        assertEquals(
            "iQIYI",
            SourceRowClassifier.detectSourceService("[x] Show (IQIYI WEB 1080p)"),
        )
    }

    @Test
    fun v33_grpbanyamaCleanTitleHasNoIqiyiResidual() {
        // Finding 1: no `IQIYI` (any case) and no empty bracket
        // remnant survive in the exact title.
        val cleaned = cleanOf(sampleGrpbanyamaRaw)
        assertFalse(cleaned.contains("iqiyi", ignoreCase = true))
        assertFalse(cleaned.contains("[]"))
        assertEquals(
            // picker-v3-fixes: the ".mkv" container residue is gone.
            "Frieren Beyond Journeys End Season 1 (Sousou no Frieren) Frieren Beyond Journeys End - 01",
            cleaned,
        )
    }

    @Test
    fun v33_iqiyiNeverBecomesReleaseGroup() {
        // Additive alias must not conflate: a lone IQIYI bracket is a
        // service, never a group.
        val f = SourceRowClassifier.parseRelease("[IQIYI] Some Show S01 1080p WEBRip")
        assertEquals("iQIYI", f.sourceService)
        assertFalse(f.releaseGroup.equals("IQIYI", ignoreCase = true))
    }

    @Test
    fun v33_grpsaliBareBdDetectedAsSourceType() {
        // The Grpsali row's bare `BD` parses
        // deterministically as source type `BD`.
        val f = SourceRowClassifier.parseRelease(sampleGrpsaliRaw)
        assertEquals("BD", f.sourceClass)
        assertEquals("Grpsali", f.releaseGroup)
    }

    @Test
    fun v33_grpsaliCleanTitleHasNoBdResidual() {
        // Finding 2: the represented bare-BD token is removed.
        val cleaned = cleanOf(sampleGrpsaliRaw)
        assertFalse(cleaned.contains("BD", ignoreCase = false))
        assertEquals(
            // picker-v3-fixes: ".mkv" and the dangling "/" residue are gone.
            "Frieren - Beyond Journey's End S1 - Frieren - Beyond Journey's End - 01 Dubbed",
            cleaned,
        )
    }

    @Test
    fun v33_bareBdBoundaryShapes() {
        // Finding 2: delimited shapes detect; compounds keep labels.
        assertEquals("BD", SourceRowClassifier.detectSourceClass("Show Title S01 - BD (1080p)"))
        assertEquals("BD", SourceRowClassifier.detectSourceClass("Show Title [BD] 1080p"))
        assertEquals("BD", SourceRowClassifier.detectSourceClass("Show Title (BD) 1080p"))
        assertEquals("BD", SourceRowClassifier.detectSourceClass("Show Title BD"))
        assertEquals("BD", SourceRowClassifier.detectSourceClass("BD Show Title 1080p"))
        assertEquals("BDRIP", SourceRowClassifier.detectSourceClass("Show Title S01 BDRip 1080p"))
        assertEquals("BLURAY", SourceRowClassifier.detectSourceClass("Show Title BluRay 1080p"))
    }

    @Test
    fun v33_bareBdWordBoundaryCounterexamples() {
        // Finding 2: ordinary words containing `bd` never detect.
        assertNull(SourceRowClassifier.detectSourceClass("Abducted S01 1080p"))
        assertNull(SourceRowClassifier.detectSourceClass("Bdubs Show 1080p"))
        assertNull(SourceRowClassifier.detectSourceClass("Show Title S01 1080p"))
    }

    @Test
    fun v33_detailsSourceTypeLabelAndHdrPresence() {
        // Findings 5+6: `Source type` label; plain HDR reads as a
        // presence marker, never `HDR: HDR`.
        val row = releaseRow(sampleGrpsaliRaw)
        val labels = row.details.map { it.label }
        assertTrue(labels.contains("Source type"))
        assertFalse(labels.contains("Source class"))
        val hdr = row.details.first { it.label == "HDR" }
        assertEquals("Yes", hdr.value)
        val svc = row.details.first { it.label == "Source type" }
        assertEquals("BD", svc.value)
    }

    @Test
    fun v33_detailsPreciseHdrVariantKeepsValue() {
        // Finding 5: precise variants are unaffected by the echo fix.
        val row = releaseRow("[G] Show S01 1080p HDR10 DTS 5.1")
        val hdr = row.details.firstOrNull { it.label == "HDR" }
        assertNotNull(hdr)
        assertEquals("HDR10", hdr!!.value)
    }

    @Test
    fun v33_detailsNeverEchoesLabelAsValue() {
        // Finding 5: no detail line anywhere repeats its label.
        val raws = listOf(sampleGrpbanyamaRaw, sampleGrpsaliRaw, "[G] Show S01 1080p HDR10 DTS 5.1")
        for (raw in raws) {
            val row = releaseRow(raw)
            for (line in row.details) {
                val label = line.label ?: continue
                assertFalse(
                    "echo ${label}=${line.value} in $raw",
                    line.value.equals(label, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun v33_criticalPillsStayAheadOfOptional() {
        // Findings 3+4: critical set is exactly QUALITY + SUB_LANG,
        // always split ahead of the optional set.
        val row = releaseRow(sampleGrpsaliRaw)
        assertTrue(row.criticalPills.isNotEmpty())
        assertTrue(row.criticalPills.all {
            it.kind == SourcePillKind.QUALITY || it.kind == SourcePillKind.SUB_LANG
        })
        assertTrue(row.optionalPills.none {
            it.kind == SourcePillKind.QUALITY || it.kind == SourcePillKind.SUB_LANG
        })
        assertEquals("1080p HDR", row.criticalPills.first().text)
    }

    @Test
    fun v33_denseOptionalSetNotSemanticallyTruncated() {
        // Finding 3: no hard take(N) cap — a dense row keeps every
        // optional pill in the model; the scrollable strip (not a cap)
        // handles overflow.
        val raw = "[GRPEM] Frieren S01 [1080p][HEVC][10-bit][E-AC-3][Dual-Audio][Multi-Subs]"
        val row = releaseRow(raw)
        val optionalKinds = row.optionalPills.map { it.kind }
        assertTrue(optionalKinds.contains(SourcePillKind.AUDIO_MODE))
        assertTrue(optionalKinds.contains(SourcePillKind.CODEC))
        assertTrue(optionalKinds.contains(SourcePillKind.BIT_DEPTH))
        assertTrue(optionalKinds.contains(SourcePillKind.AUDIO_FMT))
        // Critical quality + sub-lang still lead in the full list.
        assertEquals(SourcePillKind.QUALITY, row.pills.first().kind)
    }

    @Test
    fun v33_rawReleaseNameRemainsExact() {
        // Raw preservation is untouched by the details cleanup.
        for (raw in listOf(sampleGrpbanyamaRaw, sampleGrpsaliRaw)) {
            val row = releaseRow(raw)
            assertEquals(raw, row.subtitle)
            val rawLine = row.details.first { it.label == "Raw release name" }
            assertEquals(raw, rawLine.value)
        }
    }

    @Test
    fun v33_pcmLpcmMp2UniverseStillGreen() {
        // v3.2 correction stays green under the v3.3 alias/BD delta.
        val cleaned = cleanOf("[G] Show S01 PCM 2.0 FLAC LPCM MP2 AAC")
        assertFalse(cleaned.contains("PCM", ignoreCase = true))
        assertFalse(cleaned.contains("LPCM", ignoreCase = true))
        assertFalse(cleaned.contains("MP2", ignoreCase = true))
    }


    companion object {
        private val magnetUrl =
            "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=t"
    }
}