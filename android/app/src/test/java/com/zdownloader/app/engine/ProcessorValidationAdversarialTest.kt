package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class ProcessorValidationAdversarialTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun sampleFormat(
        id: String = "dash_480p",
        w: Int? = 720,
        h: Int? = 1280,
        label: String? = "480p",
        dimensionsStatus: String? = "unverified_label"
    ): Format {
        val json = JSONObject().apply {
            put("format_id", id)
            put("ext", "mp4")
            put("vcodec", "avc1.4d401f")
            put("acodec", "none")
            put("protocol", "https")
            put("url", "https://video.xx.fbcdn.net/v/test.mp4")
            if (w != null) put("width", w)
            if (h != null) put("height", h)
            if (label != null) put("mediaflow_source_quality_label", label)
            if (dimensionsStatus != null) put("mediaflow_dimensions", dimensionsStatus)
        }
        return Format.parse(json)
    }

    // =========================================================================
    // 1. Processor.validateSelection and Dimension Decoupling Tests
    // =========================================================================

    @Test
    fun testValidateSelectionDecouplesContainerDimensionsFromRepresentationTier() {
        // Container dimensions are 720x1280, but logical tier label is 480p
        val format480 = sampleFormat(id = "dash_480p", w = 720, h = 1280, label = "480p", dimensionsStatus = "unverified_label")
        assertEquals(720, format480.width)
        assertEquals(1280, format480.height)
        assertEquals("480p", format480.sourceQualityLabel)
        assertFalse("Format with unverified_label should have dimensionsVerified=false", format480.dimensionsVerified)

        val selection480 = Selection(primary = format480, secondary = null, audioOnly = false, output = "mp4")
        val dimensionsDiverged = selection480.primary.sourceQualityLabel != null &&
            selection480.primary.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull() != verifiedQualityEdge(selection480.primary)
        assertTrue("480p vs verifiedQualityEdge (720) must be detected as diverged", dimensionsDiverged)
        val dimensionsVerified = selection480.primary.dimensionsVerified && !dimensionsDiverged
        assertFalse("Diverged dimensions must not be verified", dimensionsVerified)

        // When dimensions are not verified, expectedHeight and expectedWidth must be null
        val expectedHeight = if (selection480.audioOnly || !dimensionsVerified) null else selection480.primary.height
        val expectedWidth = if (selection480.audioOnly || !dimensionsVerified) null else selection480.primary.width
        assertNull("expectedHeight must be null to prevent false rejection", expectedHeight)
        assertNull("expectedWidth must be null to prevent false rejection", expectedWidth)
    }

    @Test
    fun testValidateSelectionPreservesDimensionsWhenLabelMatchesContainerEdge() {
        // 720p stream on 720x1280 container (720 matches verifiedQualityEdge minOf(720, 1280) = 720)
        val format720 = sampleFormat(id = "dash_720p", w = 720, h = 1280, label = "720p", dimensionsStatus = "measured_header")
        assertTrue("Matching label retains dimensionsVerified=true", format720.dimensionsVerified)

        val selection720 = Selection(primary = format720, secondary = null, audioOnly = false, output = "mp4")
        val dimensionsDiverged = selection720.primary.sourceQualityLabel != null &&
            selection720.primary.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull() != verifiedQualityEdge(selection720.primary)
        assertFalse("720p matching 720 container edge must not diverge", dimensionsDiverged)
        val dimensionsVerified = selection720.primary.dimensionsVerified && !dimensionsDiverged
        assertTrue("Verified dimensions retained for 720p", dimensionsVerified)

        val expectedHeight = if (selection720.audioOnly || !dimensionsVerified) null else selection720.primary.height
        val expectedWidth = if (selection720.audioOnly || !dimensionsVerified) null else selection720.primary.width
        assertEquals(1280, expectedHeight)
        assertEquals(720, expectedWidth)
    }

    @Test
    fun testAllNonMatchingTiersDivergeCleanly() {
        val tiers = listOf("240p" to 240, "360p" to 360, "480p" to 480, "540p" to 540)
        for ((label, _) in tiers) {
            val format = sampleFormat(id = "dash_$label", w = 720, h = 1280, label = label, dimensionsStatus = "unverified_label")
            val selection = Selection(primary = format, secondary = null, audioOnly = false, output = "mp4")
            val diverged = selection.primary.sourceQualityLabel != null &&
                selection.primary.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull() != verifiedQualityEdge(selection.primary)
            assertTrue("Tier $label must diverge from 720 edge", diverged)
            val verified = selection.primary.dimensionsVerified && !diverged
            assertFalse("Tier $label must have dimensionsVerified=false", verified)
        }
    }

    // =========================================================================
    // 2. Orientation-Aware Dimension Math Tests (Processor.validate logic)
    // =========================================================================

    private data class StreamDim(val width: Int, val height: Int)

    private fun checkDimensionMatch(
        streams: List<StreamDim>,
        expectedWidth: Int?,
        expectedHeight: Int?
    ): Pair<Boolean, Boolean> {
        val actualShort = streams.map { minOf(it.width, it.height) }
        val expectedShort = if (expectedWidth != null && expectedHeight != null) minOf(expectedWidth, expectedHeight) else expectedHeight
        val heightMatches = expectedHeight == null ||
            streams.any { it.height == expectedHeight || (expectedWidth != null && it.width == expectedHeight && it.height == expectedWidth) } ||
            (expectedShort != null && actualShort.any { it == expectedShort })

        val video = streams.firstOrNull()
        val widthMatches = if (expectedWidth != null && video != null) {
            val actualW = video.width
            val actualH = video.height
            val actualLong = maxOf(actualW, actualH)
            val expectedLong = if (expectedHeight != null) maxOf(expectedWidth, expectedHeight) else expectedWidth
            actualW == expectedWidth || (expectedHeight != null && actualW == expectedHeight && actualH == expectedWidth) ||
                (actualLong == expectedLong)
        } else true

        return heightMatches to widthMatches
    }

    @Test
    fun testOrientationAspectFlipMatchesCleanly() {
        // Case 1: Video is portrait (720x1280), expected was landscape (1280x720)
        val portraitStreams = listOf(StreamDim(720, 1280))
        val (hMatch1, wMatch1) = checkDimensionMatch(portraitStreams, expectedWidth = 1280, expectedHeight = 720)
        assertTrue("Portrait stream must match landscape expected height (aspect flip)", hMatch1)
        assertTrue("Portrait stream must match landscape expected width (aspect flip)", wMatch1)

        // Case 2: Video is landscape (1280x720), expected was portrait (720x1280)
        val landscapeStreams = listOf(StreamDim(1280, 720))
        val (hMatch2, wMatch2) = checkDimensionMatch(landscapeStreams, expectedWidth = 720, expectedHeight = 1280)
        assertTrue("Landscape stream must match portrait expected height (aspect flip)", hMatch2)
        assertTrue("Landscape stream must match portrait expected width (aspect flip)", wMatch2)

        // Case 3: 1080p full HD flip (1080x1920 vs 1920x1080)
        val fhdStreams = listOf(StreamDim(1080, 1920))
        val (hMatch3, wMatch3) = checkDimensionMatch(fhdStreams, expectedWidth = 1920, expectedHeight = 1080)
        assertTrue("FHD portrait stream must match landscape expected", hMatch3)
        assertTrue("FHD portrait stream must match landscape expected", wMatch3)

        // Case 4: Genuine dimension mismatch: 480x854 vs expected 720x1280
        val mismatchStreams = listOf(StreamDim(480, 854))
        val (hMatch4, wMatch4) = checkDimensionMatch(mismatchStreams, expectedWidth = 720, expectedHeight = 1280)
        assertFalse("Mismatched dimensions must fail height check", hMatch4)
        assertFalse("Mismatched dimensions must fail width check", wMatch4)
    }

    // =========================================================================
    // 3. Corrupted File Rejection Tests
    // =========================================================================

    @Test
    fun testEmptyFileRejectionInValidate() = runBlocking {
        val processor = Processor()
        val emptyFile = tempDir.newFile("empty.mp4")
        emptyFile.writeBytes(ByteArray(0))

        try {
            processor.validate(emptyFile, audio = false, requireAudio = false)
            fail("Expected MediaException(FILE_WRITE_FAILED) for empty file")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.FILE_WRITE_FAILED, e.code)
        }
    }

    @Test
    fun testCorruptMp4StructuresRejectedByIntegrityAuditor() {
        // FastIntegrityAuditor is called by Processor.validate to verify MP4 atom integrity.

        // Subcase A: Under minimum size (< 64 bytes)
        val small = tempDir.newFile("small.mp4")
        small.writeBytes(ByteArray(30))
        try {
            FastIntegrityAuditor.audit(small, audio = false, requireAudio = false)
            fail("Expected FILE_WRITE_FAILED for file < 64 bytes")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.FILE_WRITE_FAILED, e.code)
        }

        // Subcase B: Missing moov atom
        val noMoov = tempDir.newFile("no_moov.mp4")
        DataOutputStream(FileOutputStream(noMoov)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            out.writeInt(60)
            out.write("free".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(52))
        }
        try {
            FastIntegrityAuditor.audit(noMoov, audio = false, requireAudio = false)
            fail("Expected OUTPUT_VALIDATION_FAILED for missing moov atom")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("missing_moov_atom", e.validationCheck)
        }

        // Subcase C: Truncated atom declaring 100,000 bytes
        val truncated = tempDir.newFile("truncated.mp4")
        DataOutputStream(FileOutputStream(truncated)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            out.writeInt(100000)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(50))
        }
        try {
            FastIntegrityAuditor.audit(truncated, audio = false, requireAudio = false)
            fail("Expected OUTPUT_VALIDATION_FAILED for truncated atom")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_atom_moov", e.validationCheck)
        }

        // Subcase D: Corrupted trailing dangling bytes
        val dangling = tempDir.newFile("dangling.mp4")
        DataOutputStream(FileOutputStream(dangling)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            out.writeInt(16)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(8))
            out.writeInt(32)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(24))
            out.write(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }
        try {
            FastIntegrityAuditor.audit(dangling, audio = false, requireAudio = false)
            fail("Expected OUTPUT_VALIDATION_FAILED for dangling bytes")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_trailing_bytes", e.validationCheck)
        }
    }

    // =========================================================================
    // 4. ManifestMetadata and ISO 8601 Duration Parsing Tests
    // =========================================================================

    @Test
    fun testDashDurationEdgeCases() {
        fun mpd(durationStr: String, type: String = "static") =
            """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" mediaPresentationDuration="$durationStr"></MPD>"""

        // Standard seconds
        assertEquals(30.0, ManifestMetadata.dashDuration(mpd("PT30S"))!!, 0.001)
        assertEquals(45.0, ManifestMetadata.dashDuration(mpd("PT45S"))!!, 0.001)

        // Fractional seconds
        assertEquals(1.5, ManifestMetadata.dashDuration(mpd("PT1.5S"))!!, 0.001)
        assertEquals(0.25, ManifestMetadata.dashDuration(mpd("PT0.25S"))!!, 0.001)
        assertEquals(30.123, ManifestMetadata.dashDuration(mpd("PT30.123S"))!!, 0.001)

        // Hours and minutes
        assertEquals(5400.0, ManifestMetadata.dashDuration(mpd("PT1H30M"))!!, 0.001)
        assertEquals(3600.0, ManifestMetadata.dashDuration(mpd("PT1H"))!!, 0.001)
        assertEquals(120.0, ManifestMetadata.dashDuration(mpd("PT2M"))!!, 0.001)

        // Complex compound hours, minutes, fractional seconds
        assertEquals(4815.5, ManifestMetadata.dashDuration(mpd("PT1H20M15.5S"))!!, 0.001)

        // Edge case: 0 seconds -> must return null (finite positive required)
        assertNull(ManifestMetadata.dashDuration(mpd("PT0S")))
        assertNull(ManifestMetadata.dashDuration(mpd("PT0.0S")))

        // Edge case: empty PT or missing components
        assertNull(ManifestMetadata.dashDuration(mpd("PT")))
        assertNull(ManifestMetadata.dashDuration(mpd("")))

        // Dynamic MPD (live stream) -> must return null
        assertNull(ManifestMetadata.dashDuration(mpd("PT30S", type = "dynamic")))

        // Malformed strings
        assertNull(ManifestMetadata.dashDuration(mpd("invalid_duration")))
        assertNull(ManifestMetadata.dashDuration(mpd("P1DT2H"))) // Days format not matching PT pattern
        assertNull(ManifestMetadata.dashDuration("<MPD></MPD>")) // Missing attribute
    }

    // =========================================================================
    // 5. FacebookScraper Duration Parsing from Page JSON / HTML Patterns
    // =========================================================================

    @Test
    fun testFacebookScraperDurationParsingFromVariousHtmlPatterns() {
        val testUrl = "https://www.facebook.com/reel/123456789"

        // Pattern 1: playable_duration_in_ms
        val htmlMs = """
            <html><body><script>
            var data = {
                "playable_url": "https://video.xx.fbcdn.net/sd.mp4",
                "playable_duration_in_ms": 45000
            };
            </script></body></html>
        """.trimIndent()
        val parsedMs = FacebookScraper.parseHtml(htmlMs, testUrl, "test")
        assertNotNull(parsedMs)
        assertEquals(45.0, parsedMs!!.getDouble("duration"), 0.001)

        // Pattern 2: playable_duration_in_sec with fraction
        val htmlSec = """
            <html><body><script>
            var data = {
                "browser_native_hd_url": "https://video.xx.fbcdn.net/hd.mp4",
                "playable_duration_in_sec": 72.5
            };
            </script></body></html>
        """.trimIndent()
        val parsedSec = FacebookScraper.parseHtml(htmlSec, testUrl, "test")
        assertNotNull(parsedSec)
        assertEquals(72.5, parsedSec!!.getDouble("duration"), 0.001)

        // Pattern 3: duration_in_sec
        val htmlDurInSec = """
            <html><body><script>
            var data = {
                "sd_src": "https://video.xx.fbcdn.net/sd.mp4",
                "duration_in_sec": 120.0
            };
            </script></body></html>
        """.trimIndent()
        val parsedDurInSec = FacebookScraper.parseHtml(htmlDurInSec, testUrl, "test")
        assertNotNull(parsedDurInSec)
        assertEquals(120.0, parsedDurInSec!!.getDouble("duration"), 0.001)

        // Pattern 4: og:video:duration meta tag
        val htmlMeta = """
            <html><head>
            <meta property="og:video" content="https://video.xx.fbcdn.net/sd.mp4" />
            <meta property="og:video:duration" content="88.75" />
            </head><body></body></html>
        """.trimIndent()
        val parsedMeta = FacebookScraper.parseHtml(htmlMeta, testUrl, "test")
        assertNotNull(parsedMeta)
        assertEquals(88.75, parsedMeta!!.getDouble("duration"), 0.001)

        // Pattern 5: duration_s field
        val htmlDurationS = """
            <html><body><script>
            var data = {
                "playable_url": "https://video.xx.fbcdn.net/sd.mp4",
                "duration_s": 33.3
            };
            </script></body></html>
        """.trimIndent()
        val parsedDurationS = FacebookScraper.parseHtml(htmlDurationS, testUrl, "test")
        assertNotNull(parsedDurationS)
        assertEquals(33.3, parsedDurationS!!.getDouble("duration"), 0.001)

        // Pattern 6: No duration present -> safely returns media without duration property
        val htmlNoDuration = """
            <html><body><script>
            var data = { "sd_src": "https://video.xx.fbcdn.net/sd.mp4" };
            </script></body></html>
        """.trimIndent()
        val parsedNoDur = FacebookScraper.parseHtml(htmlNoDuration, testUrl, "test")
        assertNotNull(parsedNoDur)
        assertFalse("Duration property must be absent when no duration field found", parsedNoDur!!.has("duration"))
    }

    // =========================================================================
    // 6. FormatSizes HTTP 206 Content-Range Parser Tests
    // =========================================================================

    @Test
    fun testContentRangeHeaderParsing() {
        // Standard range probe bytes 0-0/total
        val r1 = ContentRange.parse("bytes 0-0/12345")
        assertNotNull(r1)
        assertEquals(0L, r1!!.start)
        assertEquals(0L, r1.end)
        assertEquals(12345L, r1.total)

        // Partial range with larger byte chunk
        val r2 = ContentRange.parse("bytes 0-1000/50000")
        assertNotNull(r2)
        assertEquals(0L, r2!!.start)
        assertEquals(1000L, r2.end)
        assertEquals(50000L, r2.total)

        // Mid-stream range
        val r3 = ContentRange.parse("bytes 1024-2047/8192")
        assertNotNull(r3)
        assertEquals(1024L, r3!!.start)
        assertEquals(2047L, r3.end)
        assertEquals(8192L, r3.total)

        // Unknown total length (indicated by *)
        val r4 = ContentRange.parse("bytes 0-1000/*")
        assertNotNull(r4)
        assertEquals(0L, r4!!.start)
        assertEquals(1000L, r4.end)
        assertNull(r4.total)

        // Boundary exact single byte file: bytes 0-0/1
        val rBoundary = ContentRange.parse("bytes 0-0/1")
        assertNotNull(rBoundary)
        assertEquals(1L, rBoundary!!.total)

        // Invalid: start > end
        assertNull(ContentRange.parse("bytes 1000-0/12345"))

        // Invalid: end >= total (0-indexed byte range: end index must be < total bytes)
        assertNull(ContentRange.parse("bytes 0-12345/12345"))
        assertNull(ContentRange.parse("bytes 0-1/1"))
        assertNull(ContentRange.parse("bytes 0-0/0"))

        // Invalid: missing range unit or syntax
        assertNull(ContentRange.parse("bytes */12345"))
        assertNull(ContentRange.parse("bytes 0-0"))
        assertNull(ContentRange.parse("12345"))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse(null))

        // Invalid: number overflow
        assertNull(ContentRange.parse("bytes 0-0/999999999999999999999999999999999999999"))
    }
}
