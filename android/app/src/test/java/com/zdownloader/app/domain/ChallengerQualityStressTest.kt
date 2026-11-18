package com.zdownloader.app.domain

import com.zdownloader.app.ui.QuickSharePresentation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Empirical Adversarial & Stress Test Suite
 * Executed by challenger_qs1 to rigorously challenge:
 * 1. Facebook DASH format decoupling with identical container dimensions (240p, 360p, 480p, 540p, 720p).
 * 2. QualityResolver resolution matrix across all presets without unexpected fallbacks.
 * 3. QuickShare presentation: VideoPreferenceSelection.ready and preferenceSizes returning valid, non-null, non-zero sizes.
 * 4. Adversarial edge cases: unusual labels, missing dimensions, zero/null bitrates, portrait/landscape aspect ratios.
 */
class ChallengerQualityStressTest {

    private val capabilities = MediaIntelligence.CodecCapabilities(hasHardwareAvc = true)

    private fun buildVideoFormat(
        id: String,
        width: Int? = 720,
        height: Int? = 1280,
        label: String? = null,
        dimensionsStatus: String? = "measured_header",
        bitrate: Double? = 1000.0,
        filesize: Long? = null,
        filesizeApprox: Long? = null,
        ext: String = "mp4",
        vcodec: String = "h264",
        acodec: String = "none"
    ): Format {
        val json = JSONObject().apply {
            put("format_id", id)
            put("url", "https://video.xx.fbcdn.net/$id.mp4")
            put("ext", ext)
            put("video_ext", ext)
            put("vcodec", vcodec)
            put("acodec", acodec)
            put("protocol", "https")
            if (width != null) put("width", width)
            if (height != null) put("height", height)
            if (label != null) put("mediaflow_source_quality_label", label)
            if (dimensionsStatus != null) put("mediaflow_dimensions", dimensionsStatus)
            if (bitrate != null) {
                put("tbr", bitrate)
                put("bitrate", bitrate)
            }
            if (filesize != null) put("filesize", filesize)
            if (filesizeApprox != null) put("filesize_approx", filesizeApprox)
        }
        return Format.parse(json)
    }

    private fun buildAudioFormat(
        id: String = "dash_audio",
        bitrate: Double = 128.0,
        filesize: Long? = null,
        filesizeApprox: Long? = null
    ): Format {
        val json = JSONObject().apply {
            put("format_id", id)
            put("url", "https://video.xx.fbcdn.net/$id.m4a")
            put("ext", "m4a")
            put("audio_ext", "m4a")
            put("vcodec", "none")
            put("acodec", "aac")
            put("abr", bitrate)
            put("tbr", bitrate)
            put("bitrate", bitrate)
            put("protocol", "https")
            put("mediaflow_source_quality_label", "Audio")
            if (filesize != null) put("filesize", filesize)
            if (filesizeApprox != null) put("filesize_approx", filesizeApprox)
        }
        return Format.parse(json)
    }

    private fun buildMedia(duration: Double? = 30.0, vararg formats: Format): Media {
        val json = JSONObject().apply {
            put("id", "challenge_video_123")
            put("url", "https://www.facebook.com/reel/1441356070769297")
            put("title", "Facebook Reel Stress Test")
            put("source", "facebook")
            if (duration != null) put("duration", duration)
            val arr = JSONArray()
            formats.forEach { arr.put(JSONObject(it.payload)) }
            put("formats", arr)
        }
        return Media.parse(json, "https://www.facebook.com/reel/1441356070769297")
    }

    // =========================================================================
    // Challenge Dimension 1: Identical Container Dimensions Decoupling
    // =========================================================================

    @Test
    fun challenge01_identicalContainerDimensionsFormDiscreteQualityGroups() {
        // Facebook DASH manifest where all representations report container dimensions 720x1280
        val dash720 = buildVideoFormat("dash_720p", 720, 1280, label = "720p", bitrate = 2500.0)
        val dash540 = buildVideoFormat("dash_540p", 720, 1280, label = "540p", bitrate = 1600.0, dimensionsStatus = "unverified_label")
        val dash480 = buildVideoFormat("dash_480p", 720, 1280, label = "480p", bitrate = 1000.0, dimensionsStatus = "unverified_label")
        val dash360 = buildVideoFormat("dash_360p", 720, 1280, label = "360p", bitrate = 600.0, dimensionsStatus = "unverified_label")
        val dash240 = buildVideoFormat("dash_240p", 720, 1280, label = "240p", bitrate = 300.0, dimensionsStatus = "unverified_label")
        val audio = buildAudioFormat()

        val media = buildMedia(30.0, dash720, dash540, dash480, dash360, dash240, audio)

        // 1. Verify 5 distinct groups are formed
        val groups = media.qualityGroups(capabilities)
        assertEquals(5, groups.size)

        val resolutions = groups.map { it.resolution }
        assertEquals(listOf(720, 540, 480, 360, 240), resolutions)

        // 2. Verify each group's recommended format is the exact matching representation
        assertEquals("dash_720p", groups[0].recommended.id)
        assertEquals("dash_540p", groups[1].recommended.id)
        assertEquals("dash_480p", groups[2].recommended.id)
        assertEquals("dash_360p", groups[3].recommended.id)
        assertEquals("dash_240p", groups[4].recommended.id)
    }

    @Test
    fun challenge02_request480SelectsGenuineDash480WithoutFallingBackTo360() {
        val dash720 = buildVideoFormat("dash_720p", 720, 1280, label = "720p", bitrate = 2500.0)
        val dash540 = buildVideoFormat("dash_540p", 720, 1280, label = "540p", bitrate = 1600.0, dimensionsStatus = "unverified_label")
        val dash480 = buildVideoFormat("dash_480p", 720, 1280, label = "480p", bitrate = 1000.0, dimensionsStatus = "unverified_label")
        val dash360 = buildVideoFormat("dash_360p", 720, 1280, label = "360p", bitrate = 600.0, dimensionsStatus = "unverified_label")
        val progSd = buildVideoFormat("sd", 360, 640, label = "360p", bitrate = 800.0, acodec = "aac")
        val audio = buildAudioFormat()

        val media = buildMedia(30.0, dash720, dash540, dash480, dash360, progSd, audio)

        val request480 = DownloadRequest(height = 480, exact = false)
        val selection480 = request480.resolve(media, capabilities)

        assertNotNull(selection480)
        assertEquals("dash_480p", selection480.primary.id)
        assertEquals("480p", selection480.primary.sourceQualityLabel)
        assertEquals(480, qualityLabel(selection480.primary))
        assertNotEquals("sd", selection480.primary.id)
        assertNotEquals("dash_360p", selection480.primary.id)
        assertNotNull("Must pair companion audio for DASH video", selection480.secondary)
        assertEquals("dash_audio", selection480.secondary!!.id)
    }

    // =========================================================================
    // Challenge Dimension 2: QualityResolver Full Matrix Across All Presets
    // =========================================================================

    @Test
    fun challenge03_qualityResolverFullMatrixWithoutUnexpectedFallbacks() {
        val dash1080 = buildVideoFormat("dash_1080p", 1080, 1920, label = "1080p", bitrate = 4500.0)
        val dash720 = buildVideoFormat("dash_720p", 720, 1280, label = "720p", bitrate = 2500.0)
        val dash540 = buildVideoFormat("dash_540p", 720, 1280, label = "540p", bitrate = 1600.0, dimensionsStatus = "unverified_label")
        val dash480 = buildVideoFormat("dash_480p", 720, 1280, label = "480p", bitrate = 1000.0, dimensionsStatus = "unverified_label")
        val dash360 = buildVideoFormat("dash_360p", 720, 1280, label = "360p", bitrate = 600.0, dimensionsStatus = "unverified_label")
        val dash240 = buildVideoFormat("dash_240p", 720, 1280, label = "240p", bitrate = 300.0, dimensionsStatus = "unverified_label")
        val dash144 = buildVideoFormat("dash_144p", 720, 1280, label = "144p", bitrate = 150.0, dimensionsStatus = "unverified_label")
        val audio = buildAudioFormat()

        val media = buildMedia(30.0, dash1080, dash720, dash540, dash480, dash360, dash240, dash144, audio)

        // Matrix tests for standard presets: 1080, 720, 480, 360, 240, 144
        val presets = listOf(
            1080 to "dash_1080p",
            720 to "dash_720p",
            480 to "dash_480p",
            360 to "dash_360p",
            240 to "dash_240p",
            144 to "dash_144p"
        )

        for ((height, expectedId) in presets) {
            val req = DownloadRequest(height = height, exact = false)
            val sel = req.resolve(media, capabilities)
            assertEquals("Preset $height must resolve to $expectedId", expectedId, sel.primary.id)
            assertEquals(height, qualityLabel(sel.primary))
        }

        // Test height = 0 (auto/best available) -> must select highest quality (1080p)
        val reqAuto = DownloadRequest(height = 0, exact = false)
        val selAuto = reqAuto.resolve(media, capabilities)
        assertEquals("Auto (height=0) must resolve highest quality", "dash_1080p", selAuto.primary.id)
    }

    @Test
    fun challenge04_qualityResolverFallbackWhenRequestedPresetMissing() {
        // Only 540p, 360p, 240p available. 720p and 480p are missing.
        val dash540 = buildVideoFormat("dash_540p", 720, 1280, label = "540p", bitrate = 1600.0, dimensionsStatus = "unverified_label")
        val dash360 = buildVideoFormat("dash_360p", 720, 1280, label = "360p", bitrate = 600.0, dimensionsStatus = "unverified_label")
        val dash240 = buildVideoFormat("dash_240p", 720, 1280, label = "240p", bitrate = 300.0, dimensionsStatus = "unverified_label")
        val audio = buildAudioFormat()

        val media = buildMedia(30.0, dash540, dash360, dash240, audio)

        // Request 720 -> cap rule allows falling back to <= 720, which is 540p
        val sel720 = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("dash_540p", sel720.primary.id)

        // Request 480 -> cap rule allows falling back to <= 480, which is 360p
        val sel480 = DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
        assertEquals("dash_360p", sel480.primary.id)

        // Request 144 -> no format <= 144 exists -> throws QUALITY_UNAVAILABLE
        try {
            DownloadRequest(height = 144, exact = false).resolve(media, capabilities)
            fail("Expected QUALITY_UNAVAILABLE when no format satisfies <= 144")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE, e.code)
        }
    }

    // =========================================================================
    // Challenge Dimension 3: QuickShare Presentation & Non-Null Non-Zero Sizes
    // =========================================================================

    @Test
    fun challenge05_quickShareVideoPreferenceReadyAndPreferenceSizesPopulatedFor360And480() = runBlocking {
        // Manifest with 720, 540, 480, 360, 240 + audio + progressive hd/sd
        val duration = 45.0
        val dash720 = buildVideoFormat("dash_720p", 720, 1280, label = "720p", bitrate = 2500.0)
        val dash540 = buildVideoFormat("dash_540p", 720, 1280, label = "540p", bitrate = 1600.0, dimensionsStatus = "unverified_label")
        val dash480 = buildVideoFormat("dash_480p", 720, 1280, label = "480p", bitrate = 1000.0, dimensionsStatus = "unverified_label")
        val dash360 = buildVideoFormat("dash_360p", 720, 1280, label = "360p", bitrate = 600.0, dimensionsStatus = "unverified_label")
        val dash240 = buildVideoFormat("dash_240p", 720, 1280, label = "240p", bitrate = 300.0, dimensionsStatus = "unverified_label")
        val hd = buildVideoFormat("hd", 720, 1280, label = "720p", bitrate = 2000.0, filesizeApprox = ((duration * 2000.0 * 1000) / 8).toLong(), acodec = "aac")
        val sd = buildVideoFormat("sd", 360, 640, label = "360p", bitrate = 800.0, filesizeApprox = ((duration * 800.0 * 1000) / 8).toLong(), acodec = "aac")
        val audio = buildAudioFormat("dash_audio", bitrate = 128.0)

        val media = buildMedia(duration, dash720, dash540, dash480, dash360, dash240, hd, sd, audio)

        // 1. Verify VideoPreferenceSelection.ready returns non-null, valid selections
        val ready360 = VideoPreferenceSelection.ready(media, 360)
        assertNotNull("ready(360) must not be null", ready360)
        assertEquals("dash_360p", ready360!!.primary.id)
        assertNotNull("ready(360) outputSize must not be null", ready360.outputSize)
        assertTrue("ready(360) size must be > 0", ready360.outputSize!!.bytes > 0)

        val ready480 = VideoPreferenceSelection.ready(media, 480)
        assertNotNull("ready(480) must not be null", ready480)
        assertEquals("dash_480p", ready480!!.primary.id)
        assertNotNull("ready(480) outputSize must not be null", ready480.outputSize)
        assertTrue("ready(480) size must be > 0", ready480.outputSize!!.bytes > 0)

        val ready720 = VideoPreferenceSelection.ready(media, 720)
        assertNotNull("ready(720) must not be null", ready720)
        assertEquals("dash_720p", ready720!!.primary.id)
        assertTrue("ready(720) size must be > 0", ready720.outputSize!!.bytes > 0)

        // 2. Verify QuickSharePresentation.prepare populates preferenceSizes with non-null non-zero sizes
        val presentation = QuickSharePresentation.prepare(media)
        assertNotNull(presentation)

        val sizes = presentation.preferenceSizes
        assertTrue("preferenceSizes must contain 360", sizes.containsKey(360))
        assertTrue("preferenceSizes must contain 480", sizes.containsKey(480))
        assertTrue("preferenceSizes must contain 720", sizes.containsKey(720))

        val size360 = sizes[360]
        assertNotNull(size360)
        assertTrue("360p size must be non-zero (observed: ${size360!!.bytes} bytes)", size360.bytes > 0)

        val size480 = sizes[480]
        assertNotNull(size480)
        assertTrue("480p size must be non-zero (observed: ${size480!!.bytes} bytes)", size480.bytes > 0)

        val size720 = sizes[720]
        assertNotNull(size720)
        assertTrue("720p size must be non-zero (observed: ${size720!!.bytes} bytes)", size720.bytes > 0)

        // 3. Verify monotonic size progression: 720p size > 480p size > 360p size
        assertTrue("720p size (${size720.bytes}) should exceed 480p size (${size480.bytes})", size720.bytes > size480.bytes)
        assertTrue("480p size (${size480.bytes}) should exceed 360p size (${size360.bytes})", size480.bytes > size360.bytes)
    }

    // =========================================================================
    // Challenge Dimension 4: Adversarial Labels & Edge Cases
    // =========================================================================

    @Test
    fun challenge06_adversarialQualityLabelsDegradeGracefully() {
        // Unusual labels: "480p60", "hd720", "sd", "480P", "unknown", empty
        val formatP60 = buildVideoFormat("dash_480p60", 720, 1280, label = "480p60", bitrate = 1200.0)
        // Format.parse filters non-matching regex "[1-9][0-9]{1,4}p", so sourceQualityLabel is null
        assertNull("480p60 does not match label regex and safely falls back", formatP60.sourceQualityLabel)
        assertEquals(720, qualityLabel(formatP60)) // falls back to verified container edge (minOf 720, 1280)

        val formatHd = buildVideoFormat("dash_hd720", 720, 1280, label = "hd720", bitrate = 2000.0)
        assertNull(formatHd.sourceQualityLabel)
        assertEquals(720, qualityLabel(formatHd))

        val formatUppercase = buildVideoFormat("dash_480P", 720, 1280, label = "480P", bitrate = 1000.0)
        assertNull(formatUppercase.sourceQualityLabel)
        assertEquals(720, qualityLabel(formatUppercase))

        val formatZeroBitrate = buildVideoFormat("dash_zero_br", 720, 1280, label = "480p", bitrate = 0.0)
        assertEquals("480p", formatZeroBitrate.sourceQualityLabel)
        assertEquals(480, qualityLabel(formatZeroBitrate))

        val formatMissingDims = buildVideoFormat("dash_nodims", null, null, label = "480p", bitrate = 1000.0)
        assertEquals("480p", formatMissingDims.sourceQualityLabel)
        assertEquals(480, qualityLabel(formatMissingDims))
    }

    @Test
    fun challenge07_duplicateQualityTiersKeepHighestBitrateCandidate() {
        // Two 480p streams with different bitrates (e.g. baseline vs high profile)
        val dash480Low = buildVideoFormat("dash_480p_low", 720, 1280, label = "480p", bitrate = 800.0, dimensionsStatus = "unverified_label")
        val dash480High = buildVideoFormat("dash_480p_high", 720, 1280, label = "480p", bitrate = 1200.0, dimensionsStatus = "unverified_label")
        val audio = buildAudioFormat()

        val media = buildMedia(30.0, dash480Low, dash480High, audio)

        val groups = media.qualityGroups(capabilities)
        assertEquals("Duplicate representations of same quality tier collapse into one group", 1, groups.size)
        assertEquals(480, groups[0].resolution)
        // Highest bitrate should be recommended
        assertEquals("dash_480p_high", groups[0].recommended.id)
    }

    // =========================================================================
    // Challenge Dimension 5: Stream Validation Divergence Check
    // =========================================================================

    @Test
    fun challenge08_streamValidationDivergenceTaggingPreventsHeightRejection() {
        val format480 = buildVideoFormat("dash_480p", 720, 1280, label = "480p", dimensionsStatus = "unverified_label")
        val selection480 = Selection(primary = format480, secondary = null, audioOnly = false, output = "mp4")

        val diverged = selection480.primary.sourceQualityLabel != null &&
            selection480.primary.sourceQualityLabel?.removeSuffix("p")?.toIntOrNull() != verifiedQualityEdge(selection480.primary)
        assertTrue("480p label vs 720 container edge must be flagged diverged", diverged)

        val verified = selection480.primary.dimensionsVerified && !diverged
        assertFalse("Diverged format must not be marked dimensionsVerified", verified)

        val expectedHeight = if (selection480.audioOnly || !verified) null else selection480.primary.height
        val expectedWidth = if (selection480.audioOnly || !verified) null else selection480.primary.width
        assertNull("expectedHeight must be null so Processor.validate does not reject 1280 vs 480", expectedHeight)
        assertNull("expectedWidth must be null", expectedWidth)
    }

    @Test
    fun challenge09_facebookDivergedManifestPopulates480pSizeAndPreservesExactLabel() = runBlocking {
        // Facebook DASH manifest where 480p format has container dimensions 720x1280 and dimensionsStatus = "measured_header"
        val dash720 = buildVideoFormat("dash_720p", 720, 1280, label = "720p", bitrate = 2500.0, dimensionsStatus = "measured_header")
        val dash480 = buildVideoFormat("dash_480p", 720, 1280, label = "480p", bitrate = 1000.0, dimensionsStatus = "measured_header")
        val dash360 = buildVideoFormat("dash_360p", 540, 960, label = "360p", bitrate = 600.0, dimensionsStatus = "measured_header")
        val audio = buildAudioFormat("dash_audio", bitrate = 128.0)

        val media = buildMedia(30.0, dash720, dash480, dash360, audio)

        // 1. Ready must return 480p directly from catalog matching
        val ready480 = VideoPreferenceSelection.ready(media, 480)
        assertNotNull("ready(480) must not be null for diverged 480p format", ready480)
        assertEquals("dash_480p", ready480!!.primary.id)
        assertNotNull(ready480.outputSize)
        assertTrue(ready480.outputSize!!.bytes > 0)

        // 2. QuickSharePresentation must populate preferenceSizes[480]
        val presentation = QuickSharePresentation.prepare(media)
        assertNotNull(presentation)
        assertTrue("preferenceSizes must contain 480", presentation.preferenceSizes.containsKey(480))
        val size480 = presentation.preferenceSizes[480]
        assertNotNull("480p size must not be null", size480)
        assertTrue("480p size must be > 0 bytes", size480!!.bytes > 0)

        // 3. preferenceQualities[480] must be 480 (not 720, preventing confusing secondary label)
        assertEquals(480, presentation.preferenceQualities[480])
        assertEquals(360, presentation.preferenceQualities[360])
        assertEquals(720, presentation.preferenceQualities[720])
    }
}
