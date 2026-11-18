package com.zdownloader.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Empirical Stress Test Suite executed by challenger_1.
 * Adversarially challenges:
 * 1. Quality Preset Maximum Cap Enforcements (720p cap -> 540p; 480p cap -> 360p; 360p cap -> <=360p; no candidate <= cap -> handled explicitly without exceeding cap).
 * 2. Dimensions & Portrait Video (720x1280 evaluated as 720p, not 1280p; separation of sourceQualityLabel from physical stream dimensions).
 * 3. Logical Duplication & Candidate Preservation (1 row per distinct tier in UI catalog, all underlying format variants preserved in model).
 * 4. Media-Type Discrimination (image vs video vs audio; thumbnail never misclassified as video).
 */
class FormatDiscoveryQualityStressChallengerTest {

    private val capabilities = MediaIntelligence.CodecCapabilities(hasHardwareAvc = true)

    private fun videoFormat(
        id: String,
        width: Int? = 1280,
        height: Int? = 720,
        codec: String = "h264",
        audioCodec: String = "none",
        bitrate: Double = 1500.0,
        filesize: Long? = 5_000_000L,
        qualityLabel: String? = null,
        dimensionsStatus: String = "measured_header"
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
        if (qualityLabel != null) json.put("mediaflow_source_quality_label", qualityLabel)
        json.put("mediaflow_dimensions", dimensionsStatus)
        return Format.parse(json)
    }

    private fun audioFormat(
        id: String = "audio_1",
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

    private fun imageFormat(
        id: String = "original-image",
        width: Int = 1080,
        height: Int = 1920,
        ext: String = "jpg",
        filesize: Long? = 850_000L
    ): Format {
        val json = JSONObject()
            .put("format_id", id)
            .put("url", "https://cdn.example.com/$id.$ext")
            .put("ext", ext)
            .put("width", width)
            .put("height", height)
            .put("vcodec", "none")
            .put("acodec", "none")
            .put("protocol", "https")
            .put("mediaflow_image", "original")
        if (filesize != null) json.put("filesize", filesize)
        return Format.parse(json)
    }

    private fun buildMedia(
        url: String = "https://www.facebook.com/reel/12345",
        thumbnail: String? = "https://cdn.example.com/thumb.jpg",
        duration: Double? = 30.0,
        vararg formats: Format
    ): Media {
        val json = JSONObject()
            .put("id", "12345")
            .put("url", url)
            .put("title", "Challenger Test Media")
            .put("source", "facebook")
        if (duration != null) json.put("duration", duration)
        if (thumbnail != null) json.put("thumbnail", thumbnail)
        val arr = JSONArray()
        formats.forEach { arr.put(JSONObject(it.payload)) }
        json.put("formats", arr)
        return Media.parse(json, url)
    }

    // =========================================================================
    // SECTION 1: Quality Preset Maximum Cap Enforcements
    // =========================================================================

    @Test
    fun capEnforcement_720pCapWith360_480_540Available_mustSelect540p() {
        val f360 = videoFormat("v360", 640, 360, qualityLabel = "360p", bitrate = 600.0)
        val f480 = videoFormat("v480", 854, 480, qualityLabel = "480p", bitrate = 1000.0)
        val f540 = videoFormat("v540", 960, 540, qualityLabel = "540p", bitrate = 1600.0)
        val media = buildMedia(formats = arrayOf(f360, f480, f540))

        // 1. QualityResolver with 720p cap
        val request720 = DownloadRequest(height = 720, exact = false)
        val selection = request720.resolve(media, capabilities)
        assertEquals("720p cap must select the highest available candidate <= 720p (540p)", "v540", selection.primary.id)
        assertEquals(540, qualityLabel(selection.primary))

        // 2. QuickShareFormats.preferenceChoice with 720 cap
        val prefChoice = QuickShareFormats.preferenceChoice(media, 720)
        assertNotNull(prefChoice)
        assertEquals("v540", prefChoice!!.formatId)
    }

    @Test
    fun capEnforcement_480pCapWith360And540Available_mustSelect360p() {
        val f360 = videoFormat("v360", 640, 360, qualityLabel = "360p", bitrate = 600.0)
        val f540 = videoFormat("v540", 960, 540, qualityLabel = "540p", bitrate = 1600.0)
        val media = buildMedia(formats = arrayOf(f360, f540))

        // 1. QualityResolver with 480p cap
        val request480 = DownloadRequest(height = 480, exact = false)
        val selection = request480.resolve(media, capabilities)
        assertEquals("480p cap with only 360p and 540p must select 360p, NEVER 540p", "v360", selection.primary.id)
        assertEquals(360, qualityLabel(selection.primary))

        // 2. QuickShareFormats.preferenceChoice with 480 cap
        val prefChoice = QuickShareFormats.preferenceChoice(media, 480)
        assertNotNull(prefChoice)
        assertEquals("v360", prefChoice!!.formatId)
    }

    @Test
    fun capEnforcement_360pCap_neverSelects480pOrHigher() {
        val f360 = videoFormat("v360", 640, 360, qualityLabel = "360p", bitrate = 600.0)
        val f480 = videoFormat("v480", 854, 480, qualityLabel = "480p", bitrate = 1000.0)
        val f540 = videoFormat("v540", 960, 540, qualityLabel = "540p", bitrate = 1600.0)
        val f720 = videoFormat("v720", 1280, 720, qualityLabel = "720p", bitrate = 2500.0)
        val f1080 = videoFormat("v1080", 1920, 1080, qualityLabel = "1080p", bitrate = 4500.0)
        val media = buildMedia(formats = arrayOf(f360, f480, f540, f720, f1080))

        val request360 = DownloadRequest(height = 360, exact = false)
        val selection = request360.resolve(media, capabilities)
        assertEquals("360p cap must select v360", "v360", selection.primary.id)
        assertTrue("Selected resolution MUST be <= 360", (qualityLabel(selection.primary) ?: Int.MAX_VALUE) <= 360)

        val prefChoice = QuickShareFormats.preferenceChoice(media, 360)
        assertNotNull(prefChoice)
        assertEquals("v360", prefChoice!!.formatId)
    }

    @Test
    fun capEnforcement_noCandidatesAtOrBelowCap_handledExplicitlyWithoutExceedingCap() {
        // Source only has 720p and 1080p
        val f720 = videoFormat("v720", 1280, 720, qualityLabel = "720p", bitrate = 2500.0)
        val f1080 = videoFormat("v1080", 1920, 1080, qualityLabel = "1080p", bitrate = 4500.0)
        val media = buildMedia(formats = arrayOf(f720, f1080))

        // Cap 360: no formats <= 360
        try {
            DownloadRequest(height = 360, exact = false).resolve(media, capabilities)
            fail("Expected QUALITY_UNAVAILABLE when no candidate <= 360p cap exists")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE, e.code)
        }

        // QuickShareFormats.preferenceChoice(media, 360) must return null, NEVER 720 or 1080
        val choice360 = QuickShareFormats.preferenceChoice(media, 360)
        assertNull("preferenceChoice must return null when cap cannot be satisfied", choice360)

        // Cap 480: no formats <= 480
        try {
            DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
            fail("Expected QUALITY_UNAVAILABLE when no candidate <= 480p cap exists")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE, e.code)
        }

        val choice480 = QuickShareFormats.preferenceChoice(media, 480)
        assertNull("preferenceChoice must return null when 480 cap cannot be satisfied", choice480)
    }

    // =========================================================================
    // SECTION 2: Dimensions & Portrait Video
    // =========================================================================

    @Test
    fun dimensions_physicalStream720x1280_evaluatedAs720pNot1280p() {
        val portrait = videoFormat("portrait_stream", width = 720, height = 1280)
        val media = buildMedia(formats = arrayOf(portrait))

        // verifiedQualityEdge uses minOf(width, height)
        val verifiedEdge = verifiedQualityEdge(portrait)
        assertEquals("Portrait 720x1280 verified edge MUST be 720, not 1280", 720, verifiedEdge)

        // qualityLabel uses verifiedQualityEdge when sourceQualityLabel is null
        assertEquals(720, qualityLabel(portrait))

        // QuickShareCatalog.numericVideoQuality evaluates to 720
        assertEquals(720, QuickShareCatalog.numericVideoQuality(portrait))
        assertEquals("720p", QuickShareCatalog.videoDisplayLabel(portrait))

        // QualityResolver for 720p cap matches portrait format
        val selection = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("portrait_stream", selection.primary.id)
        assertEquals(720, verifiedQualityEdge(selection.primary))

        // Physical dimensions must NOT be swapped or altered
        assertEquals(720, selection.primary.width)
        assertEquals(1280, selection.primary.height)
    }

    @Test
    fun dimensions_separationOfLogicalSourceQualityLabelFromPhysicalStreamDimensions() {
        // Facebook DASH stream: sourceQualityLabel = "480p", physical dimensions = 720x1280
        val diverged = videoFormat(
            id = "dash_480_in_720_canvas",
            width = 720,
            height = 1280,
            qualityLabel = "480p",
            dimensionsStatus = "unverified_label"
        )
        val media = buildMedia(formats = arrayOf(diverged))

        // 1. sourceQualityLabel is exactly "480p"
        assertEquals("480p", diverged.sourceQualityLabel)
        assertEquals("480p", QuickShareCatalog.sourceQualityLabel(diverged))

        // 2. Physical dimensions are preserved as 720 and 1280
        assertEquals(720, diverged.width)
        assertEquals(1280, diverged.height)

        // 3. Logical quality tier is 480 (from sourceQualityLabel)
        assertEquals(480, qualityLabel(diverged))
        assertEquals(480, QuickShareCatalog.numericVideoQuality(diverged))
        assertEquals("480p", QuickShareCatalog.videoDisplayLabel(diverged))

        // 4. Dimensions are marked unverified, so verifiedQualityEdge is null
        assertNull(verifiedQualityEdge(diverged))

        // 5. Resolving 480p cap successfully matches this format
        val selection = DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
        assertEquals("dash_480_in_720_canvas", selection.primary.id)
        assertEquals("480p", selection.primary.sourceQualityLabel)
        assertEquals(720, selection.primary.width)
        assertEquals(1280, selection.primary.height)
    }

    // =========================================================================
    // SECTION 3: Logical Duplication & Candidate Preservation
    // =========================================================================

    @Test
    fun duplication_oneVisibleRowPerLogicalTier_allUnderlyingVariantsPreserved() {
        // Two 1080p formats: one AV1, one H.264
        val av1_1080 = videoFormat("1080p_av1", 1920, 1080, codec = "av1", bitrate = 3000.0, qualityLabel = "1080p")
        val h264_1080 = videoFormat("1080p_h264", 1920, 1080, codec = "h264", bitrate = 4500.0, qualityLabel = "1080p")

        // Two 720p formats: 60fps vs 30fps
        val f720_60 = videoFormat("720p_60fps", 1280, 720, codec = "h264", bitrate = 2800.0, qualityLabel = "720p")
            .copy(fps = 60.0)
        val f720_30 = videoFormat("720p_30fps", 1280, 720, codec = "h264", bitrate = 2000.0, qualityLabel = "720p")
            .copy(fps = 30.0)

        val media = buildMedia(formats = arrayOf(av1_1080, h264_1080, f720_60, f720_30))

        // 1. media.formats contains all 4 raw formats
        assertEquals(4, media.formats.size)

        // 2. UI catalog videoEntries produces EXACTLY 2 entries (one for 1080p, one for 720p)
        val entries = QuickShareCatalog.videoEntries(media)
        assertEquals("UI catalog must present exactly one row per distinct logical tier", 2, entries.size)
        val catalogTiers = entries.map { QuickShareCatalog.numericVideoQuality(it.format) }
        assertEquals(listOf(720, 1080), catalogTiers)

        // 3. QualityGroups preserves all candidate variants internally
        val groups = media.qualityGroups(capabilities)
        assertEquals(2, groups.size)
        val g1080 = groups.first { it.resolution == 1080 }
        assertEquals(2, g1080.candidates.size)
        assertTrue(g1080.hasMeaningfulVariants)
        assertEquals(setOf("1080p_av1", "1080p_h264"), g1080.candidates.map { it.id }.toSet())

        val g720 = groups.first { it.resolution == 720 }
        assertEquals(2, g720.candidates.size)
        assertTrue(g720.hasMeaningfulVariants)

        // 4. Any variant remains resolvable via QuickShareChoice without losing codec metadata
        val choiceAv1 = QuickShareChoice(false, "1080p_av1").resolve(media)
        assertNotNull(choiceAv1)
        assertEquals("1080p_av1", choiceAv1!!.primary.id)
        assertEquals("av1", choiceAv1.primary.videoCodec)

        val choiceH264 = QuickShareChoice(false, "1080p_h264").resolve(media)
        assertNotNull(choiceH264)
        assertEquals("1080p_h264", choiceH264!!.primary.id)
        assertEquals("h264", choiceH264.primary.videoCodec)
    }

    // =========================================================================
    // SECTION 4: Media-Type Discrimination
    // =========================================================================

    @Test
    fun mediaType_directImageVsVideoVsAudio_properlyDiscriminated() {
        val image = imageFormat("img_1", 1080, 1920, "jpg")
        val video = videoFormat("vid_1", 1280, 720)
        val audio = audioFormat("aud_1")

        // Image discrimination
        assertTrue(image.image)
        assertFalse(image.video)
        assertFalse(image.audio)

        // Video discrimination
        assertFalse(video.image)
        assertTrue(video.video)
        assertFalse(video.streamOnly)

        // Audio discrimination
        assertFalse(audio.image)
        assertFalse(audio.video)
        assertTrue(audio.audio)
        assertTrue(audio.streamOnly)

        val mediaImage = buildMedia(url = "https://cdn.example.com/photo.jpg", formats = arrayOf(image))
        assertEquals(1, mediaImage.imageOptions().size)
        assertTrue(mediaImage.videoOptions().isEmpty())
        assertTrue(mediaImage.audioOptions().isEmpty())

        // QuickShareFormats.defaultChoice for image-only media returns the image choice
        val defaultImgChoice = QuickShareFormats.defaultChoice(mediaImage)
        assertNotNull(defaultImgChoice)
        assertEquals("img_1", defaultImgChoice!!.formatId)
        assertFalse(defaultImgChoice.audio)

        val imgSelection = defaultImgChoice.resolve(mediaImage)
        assertNotNull(imgSelection)
        assertTrue(imgSelection!!.imageOnly)
        assertFalse(imgSelection.audioOnly)
        assertNull(imgSelection.secondary)
    }

    @Test
    fun mediaType_videoThumbnail_neverMisclassifiedOrChosenAsVideo() {
        val thumbUrl = "https://cdn.example.com/thumbnail_cover.jpg"
        val video = videoFormat("vid_main", 1280, 720)
        val audio = audioFormat("aud_main")
        val media = buildMedia(thumbnail = thumbUrl, formats = arrayOf(video, audio))

        // Thumbnail is on Media, NOT a format in formats list
        assertEquals(thumbUrl, media.thumbnail)
        assertTrue("Thumbnail URL must not be added into formats", media.formats.none { it.id == "thumbnail" || it.id.contains("thumb") })

        // Formats contains only video and audio
        assertEquals(2, media.formats.size)
        assertTrue(media.formats.any { it.video })
        assertFalse(media.formats.any { it.image })

        // Selection.video picks vid_main, never any thumbnail
        val sel = Selection.video(media, video)
        assertEquals("vid_main", sel.primary.id)
        assertFalse(sel.primary.image)
        assertTrue(sel.primary.video)
    }
}
