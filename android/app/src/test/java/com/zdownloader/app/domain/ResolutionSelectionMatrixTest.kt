package com.zdownloader.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic unit test suite covering the 14 resolution-selection scenarios
 * specified in Part 10 of the MediaFlow Lab Quality Engine specification.
 */
class ResolutionSelectionMatrixTest {

    private val capabilities = MediaIntelligence.CodecCapabilities(hasHardwareAvc = true)

    private fun videoFormat(
        id: String,
        width: Int? = 1280,
        height: Int? = 720,
        codec: String = "h264",
        audioCodec: String = "none",
        bitrate: Double = 1500.0,
        filesize: Long? = 5_000_000L,
        dimensionsVerified: Boolean = true,
        qualityLabel: String? = null
    ): Format {
        val json = JSONObject()
            .put("format_id", id)
            .put("url", "https://video.xx.fbcdn.net/$id.mp4")
            .put("ext", "mp4")
            .put("vcodec", codec)
            .put("acodec", audioCodec)
            .put("tbr", bitrate)
            .put("protocol", "https")
        if (width != null) json.put("width", width)
        if (height != null) json.put("height", height)
        if (filesize != null) json.put("filesize", filesize)
        if (!dimensionsVerified) json.put("mediaflow_dimensions", "unverified_label")
        if (qualityLabel != null) json.put("mediaflow_source_quality_label", qualityLabel)
        return Format.parse(json)
    }

    private fun audioFormat(
        id: String,
        audioCodec: String = "aac",
        bitrate: Double = 128.0,
        filesize: Long? = 1_000_000L
    ): Format {
        val json = JSONObject()
            .put("format_id", id)
            .put("url", "https://video.xx.fbcdn.net/$id.m4a")
            .put("ext", "m4a")
            .put("vcodec", "none")
            .put("acodec", audioCodec)
            .put("abr", bitrate)
            .put("tbr", bitrate)
            .put("protocol", "https")
            .put("mediaflow_source_quality_label", "Audio")
        if (filesize != null) json.put("filesize", filesize)
        return Format.parse(json)
    }

    private fun buildMedia(url: String = "https://www.facebook.com/reel/12345", vararg formats: Format): Media {
        val json = JSONObject()
            .put("id", "12345")
            .put("url", url)
            .put("title", "Test Video")
            .put("source", "facebook")
            .put("duration", 30.0)
        val arr = JSONArray()
        formats.forEach { arr.put(JSONObject(it.payload)) }
        json.put("formats", arr)
        return Media.parse(json, url)
    }

    // Scenario 1: Exact resolution match
    @Test
    fun scenario01_exactResolutionMatch() {
        val media = buildMedia(
            formats = arrayOf(
                videoFormat("1080p", 1920, 1080),
                videoFormat("720p", 1280, 720),
                videoFormat("480p", 854, 480),
                videoFormat("360p", 640, 360)
            )
        )
        val request = DownloadRequest(height = 720, exact = false)
        val selection = request.resolve(media, capabilities)
        assertEquals("720p", selection.primary.id)
        assertEquals(720, verifiedQualityEdge(selection.primary))
    }

    // Scenario 2: Missing exact resolution with lower alternative
    @Test
    fun scenario02_missingExactResolutionWithLowerAlternative() {
        // Source only has 540p, 480p, 360p. User requested 720p.
        val media = buildMedia(
            formats = arrayOf(
                videoFormat("540p", 960, 540),
                videoFormat("480p", 854, 480),
                videoFormat("360p", 640, 360)
            )
        )
        val request = DownloadRequest(height = 720, exact = false)
        val selection = request.resolve(media, capabilities)
        // Must seamlessly select the highest available below 720p: 540p, without throwing an error
        assertEquals("540p", selection.primary.id)
        assertEquals(540, verifiedQualityEdge(selection.primary))
    }

    // Scenario 3: Higher resolutions available but prohibited by cap
    @Test
    fun scenario03_higherResolutionsProhibitedByCap() {
        val media = buildMedia(
            formats = arrayOf(
                videoFormat("1080p", 1920, 1080),
                videoFormat("720p", 1280, 720),
                videoFormat("480p", 854, 480),
                videoFormat("360p", 640, 360)
            )
        )
        val request = DownloadRequest(height = 480, exact = false)
        val selection = request.resolve(media, capabilities)
        // Must NEVER exceed 480p cap
        assertEquals("480p", selection.primary.id)
        assertEquals(480, verifiedQualityEdge(selection.primary))
        assertTrue(verifiedQualityEdge(selection.primary)!! <= 480)
    }

    // Scenario 4: Single-resolution source
    @Test
    fun scenario04_singleResolutionSource() {
        val media = buildMedia(
            formats = arrayOf(
                videoFormat("480p", 854, 480)
            )
        )
        // Cap 720p -> falls back to single available 480p
        val selection720 = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("480p", selection720.primary.id)
        assertEquals(480, verifiedQualityEdge(selection720.primary))

        // Cap 360p -> 480p is higher than 360p cap -> throws QUALITY_UNAVAILABLE
        try {
            DownloadRequest(height = 360, exact = false).resolve(media, capabilities)
            fail("Expected QUALITY_UNAVAILABLE when single available resolution is above cap")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE, e.code)
        }
    }

    // Scenario 5: Mixed progressive and DASH formats
    @Test
    fun scenario05_mixedProgressiveAndDashFormats() {
        val progSd = videoFormat("sd", 640, 360, audioCodec = "aac")
        val progHd = videoFormat("hd", 1280, 720, audioCodec = "aac")
        val dash720 = videoFormat("dash_720p_1", 1280, 720, audioCodec = "none", bitrate = 2200.0)
        val dash480 = videoFormat("dash_480p_2", 854, 480, audioCodec = "none", bitrate = 1200.0)
        val dashAudio = audioFormat("dash_audio_3", audioCodec = "aac", bitrate = 128.0)

        val media = buildMedia(formats = arrayOf(progSd, progHd, dash720, dash480, dashAudio))
        val groups = media.qualityGroups(capabilities)

        // Groups must have 720, 480, 360
        val resolutions = groups.mapNotNull { it.resolution }
        assertTrue(resolutions.contains(720))
        assertTrue(resolutions.contains(480))
        assertTrue(resolutions.contains(360))

        // Request 720 -> selects 720p with companion audio if DASH
        val selection720 = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals(720, verifiedQualityEdge(selection720.primary))
        if (!selection720.primary.audio) {
            assertNotNull(selection720.secondary)
            assertTrue(selection720.secondary!!.audio)
        }

        // Request 480 -> selects dash 480
        val selection480 = DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
        assertEquals(480, verifiedQualityEdge(selection480.primary))
    }

    // Scenario 6: Portrait video (720x1280 classified as 720p)
    @Test
    fun scenario06_portraitVideoShortEdgeClassification() {
        val portrait = videoFormat("portrait_720", width = 720, height = 1280)
        val media = buildMedia(formats = arrayOf(portrait))

        // Short edge is minOf(720, 1280) = 720
        assertEquals(720, verifiedQualityEdge(portrait))
        assertEquals(720, qualityLabel(portrait))

        val selection = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("portrait_720", selection.primary.id)
        assertEquals(720, verifiedQualityEdge(selection.primary))
    }

    // Scenario 7: Duplicate representations
    @Test
    fun scenario07_duplicateRepresentations() {
        val rep1 = videoFormat("dash_720p_a", 1280, 720, bitrate = 1500.0)
        val rep2 = videoFormat("dash_720p_b", 1280, 720, bitrate = 2200.0) // higher bitrate
        val media = buildMedia(formats = arrayOf(rep1, rep2))

        val groups = media.qualityGroups(capabilities)
        assertEquals(1, groups.size)
        assertEquals(720, groups.first().resolution)
        // Higher bitrate is prioritized
        assertEquals("dash_720p_b", groups.first().recommended.id)
    }

    // Scenario 8: Missing or inconsistent resolution metadata
    @Test
    fun scenario08_missingOrInconsistentResolutionMetadata() {
        val unverified = videoFormat("unverified_hd", 1280, 720, dimensionsVerified = false)
        val valid480 = videoFormat("valid_480", 854, 480, dimensionsVerified = true)
        val media = buildMedia(formats = arrayOf(unverified, valid480))

        assertFalse(unverified.dimensionsVerified)
        assertNull(verifiedQualityEdge(unverified))

        // Cap 720p cannot verify unverified format, resolves valid 480p safely
        val selection = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("valid_480", selection.primary.id)
        assertEquals(480, verifiedQualityEdge(selection.primary))
    }

    // Scenario 9: Unknown file size
    @Test
    fun scenario09_unknownFileSize() {
        val videoNoSize = videoFormat("720p_nosize", 1280, 720, filesize = null)
        val media = buildMedia(formats = arrayOf(videoNoSize))

        assertNull(videoNoSize.bytes)
        val selection = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("720p_nosize", selection.primary.id)
        assertEquals(720, verifiedQualityEdge(selection.primary))
    }

    // Scenario 10: Separate audio-only formats
    @Test
    fun scenario10_separateAudioOnlyFormats() {
        val video = videoFormat("dash_720", 1280, 720, audioCodec = "none")
        val audioAac = audioFormat("dash_audio_aac", audioCodec = "aac", bitrate = 128.0)
        val media = buildMedia(formats = arrayOf(video, audioAac))

        // Audio extraction with original/m4a
        val selectionOriginal = DownloadRequest(audioOnly = true, output = "original").resolve(media, capabilities)
        assertTrue(selectionOriginal.audioOnly)
        assertEquals("dash_audio_aac", selectionOriginal.primary.id)
        assertFalse(selectionOriginal.convertAudio)
        assertEquals("m4a", selectionOriginal.output)

        // Audio extraction with explicit m4a
        val selectionM4a = DownloadRequest(audioOnly = true, output = "m4a").resolve(media, capabilities)
        assertTrue(selectionM4a.audioOnly)
        assertEquals("dash_audio_aac", selectionM4a.primary.id)
        assertEquals("m4a", selectionM4a.output)

        // Audio extraction with mp3 transcode
        val selectionMp3 = DownloadRequest(audioOnly = true, output = "mp3").resolve(media, capabilities)
        assertTrue(selectionMp3.audioOnly)
        assertEquals("dash_audio_aac", selectionMp3.primary.id)
        assertTrue(selectionMp3.convertAudio)
        assertEquals("mp3", selectionMp3.output)

        // Video selection pairs companion audio
        val selectionVideo = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("dash_720", selectionVideo.primary.id)
        assertNotNull(selectionVideo.secondary)
        assertEquals("dash_audio_aac", selectionVideo.secondary!!.id)
    }

    // Scenario 11: Changing quality selection while metadata enrichment is running
    @Test
    fun scenario11_changingQualityWhileEnrichmentRuns() {
        val v720 = videoFormat("720p", 1280, 720)
        val v480 = videoFormat("480p", 854, 480)
        val mediaInitial = buildMedia(formats = arrayOf(v720, v480))

        // User explicitly selects 480p
        val choice = QuickShareChoice(audio = false, formatId = "480p")
        val selection1 = choice.resolve(mediaInitial)
        assertNotNull(selection1)
        assertEquals("480p", selection1!!.primary.id)

        // Background size enrichment completes, returning enriched media instance
        val v720Enriched = videoFormat("720p", 1280, 720, filesize = 12_000_000L)
        val v480Enriched = videoFormat("480p", 854, 480, filesize = 6_000_000L)
        val mediaEnriched = buildMedia(formats = arrayOf(v720Enriched, v480Enriched))

        // The user's choice resolved on the enriched media retains 480p without flipping to 720p
        val selection2 = choice.resolve(mediaEnriched)
        assertNotNull(selection2)
        assertEquals("480p", selection2!!.primary.id)
    }

    // Scenario 12: Reusing cached analysis results without corrupting selected format
    @Test
    fun scenario12_reusingCachedAnalysisWithoutCorruptingSelection() {
        val v1080 = videoFormat("1080p", 1920, 1080)
        val v720 = videoFormat("720p", 1280, 720)
        val mediaCached = buildMedia(formats = arrayOf(v1080, v720))

        // First resolve at 720p cap
        val sel1 = DownloadRequest(height = 720, exact = false).resolve(mediaCached, capabilities)
        assertEquals("720p", sel1.primary.id)

        // Second resolve at 1080p cap using the exact same cached media instance
        val sel2 = DownloadRequest(height = 1080, exact = false).resolve(mediaCached, capabilities)
        assertEquals("1080p", sel2.primary.id)

        // Ensure first selection remained untouched
        assertEquals("720p", sel1.primary.id)
    }

    // Scenario 13: No eligible source at or below cap
    @Test
    fun scenario13_noEligibleSourceAtOrBelowCapThrowsQualityUnavailable() {
        // Media only contains 1080p and 1440p
        val v1440 = videoFormat("1440p", 2560, 1440)
        val v1080 = videoFormat("1080p", 1920, 1080)
        val media = buildMedia(formats = arrayOf(v1440, v1080))

        // User cap is 480p -> none <= 480p exists
        try {
            DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
            fail("Expected QUALITY_UNAVAILABLE when no resolution is <= cap")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE, e.code)
        }
    }

    // Scenario 14: Multiple extraction engines returning overlapping format results
    @Test
    fun scenario14_multipleExtractionEnginesOverlappingResults() {
        // Formats from FacebookScraper (dash_720p_1, dash_480p_2, dash_audio_3)
        val scraper720 = videoFormat("dash_720p_1", 1280, 720, qualityLabel = "720p")
        val scraper480 = videoFormat("dash_480p_2", 854, 480, qualityLabel = "480p")
        val scraperAudio = audioFormat("dash_audio_3")

        // Formats from Python Extractor / yt-dlp (hd, sd)
        val pythonHd = videoFormat("hd", 1280, 720, qualityLabel = "720p", audioCodec = "aac")
        val pythonSd = videoFormat("sd", 854, 480, qualityLabel = "480p", audioCodec = "aac")

        val media = buildMedia(formats = arrayOf(scraper720, scraper480, scraperAudio, pythonHd, pythonSd))

        // All formats coexist cleanly
        assertEquals(5, media.formats.size)

        // Quality groups group by resolution
        val groups = media.qualityGroups(capabilities)
        assertEquals(2, groups.size)
        assertEquals(720, groups[0].resolution)
        assertEquals(480, groups[1].resolution)

        // Cap 720 resolves properly
        val sel720 = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals(720, verifiedQualityEdge(sel720.primary))
    }

    // Scenario 15: Facebook DASH formats with container dimensions form distinct quality groups and single entry per resolution
    @Test
    fun scenario15_facebookDashIdenticalContainerDimensionsDecoupleAnd480ResolvesDash480() {
        val dash720 = videoFormat("dash_720p_1", width = 720, height = 1280, qualityLabel = "720p", bitrate = 2500.0)
        val dash540 = videoFormat("dash_540p_2", width = 540, height = 960, qualityLabel = "540p", bitrate = 1500.0)
        val dash480 = videoFormat("dash_480p_3", width = 480, height = 854, qualityLabel = "480p", bitrate = 1000.0)
        val dash360 = videoFormat("dash_360p_4", width = 360, height = 640, qualityLabel = "360p", bitrate = 600.0)
        val dash240 = videoFormat("dash_240p_5", width = 240, height = 426, qualityLabel = "240p", bitrate = 300.0)
        val progSd = videoFormat("sd", width = 360, height = 640, qualityLabel = "360p", audioCodec = "aac", bitrate = 800.0)
        val dashAudio = audioFormat("dash_audio_6")

        val media = buildMedia(formats = arrayOf(dash720, dash540, dash480, dash360, dash240, progSd, dashAudio))

        // Distinct quality groups formed for 720, 540, 480, 360, 240
        val groups = media.qualityGroups(capabilities)
        val resolutions = groups.mapNotNull { it.resolution }
        assertEquals(listOf(720, 540, 480, 360, 240), resolutions)

        // Request 480 selects genuine dash_480p representation, NOT progressive sd (360)
        val sel480 = DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
        assertEquals("dash_480p_3", sel480.primary.id)
        assertEquals("480p", sel480.primary.sourceQualityLabel)
        assertEquals(480, qualityLabel(sel480.primary))
        assertNotEquals("sd", sel480.primary.id)

        // Single-Entry Per Resolution invariant holds
        val entries = QuickShareCatalog.videoEntries(media)
        assertEquals(listOf(240, 360, 480, 540, 720), entries.map { verifiedQualityEdge(it.format) })
        assertEquals(5, entries.size)
    }
}
