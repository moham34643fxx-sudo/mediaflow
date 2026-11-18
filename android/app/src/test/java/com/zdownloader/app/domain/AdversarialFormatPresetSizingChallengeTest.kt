package com.zdownloader.app.domain

import com.zdownloader.app.engine.FormatSizes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test Suite
 * Executed by challenger_1 to verify:
 * 1. Format Cataloging & Priority (sourceQualityLabel prioritization, Facebook DASH identical dimensions decoupling, strict single entry per tier).
 * 2. Preset Caps Strictness (<= cap enforcement, 480p cap choosing 360p over 720p, 720p cap rejecting silent upgrade to 1080p).
 * 3. Format Sizing Bounds & FLAC Unknown Size (max 16 requests, max 3 concurrency, 2s timeout, FLAC outputSize == null, DASH bitrate fallbacks).
 */
class AdversarialFormatPresetSizingChallengeTest {

    private val capabilities = MediaIntelligence.CodecCapabilities(hasHardwareAvc = true)

    private fun videoFormat(
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
        acodec: String = "none",
        url: String = "https://video.xx.fbcdn.net/$id.mp4"
    ): Format {
        val json = JSONObject().apply {
            put("format_id", id)
            put("url", url)
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

    private fun audioFormat(
        id: String = "audio_stream",
        bitrate: Double? = 128.0,
        filesize: Long? = null,
        filesizeApprox: Long? = null,
        acodec: String = "aac",
        ext: String = "m4a"
    ): Format {
        val json = JSONObject().apply {
            put("format_id", id)
            put("url", "https://video.xx.fbcdn.net/$id.$ext")
            put("ext", ext)
            put("audio_ext", ext)
            put("vcodec", "none")
            put("acodec", acodec)
            if (bitrate != null) {
                put("abr", bitrate)
                put("tbr", bitrate)
                put("bitrate", bitrate)
            }
            put("protocol", "https")
            put("mediaflow_source_quality_label", "Audio")
            if (filesize != null) put("filesize", filesize)
            if (filesizeApprox != null) put("filesize_approx", filesizeApprox)
        }
        return Format.parse(json)
    }

    private fun buildMedia(duration: Double? = 30.0, vararg formats: Format): Media {
        val json = JSONObject().apply {
            put("id", "challenge_media_101")
            put("url", "https://www.facebook.com/reel/9988776655")
            put("title", "Adversarial Stress Test Media")
            put("source", "facebook")
            if (duration != null) put("duration", duration)
            val arr = JSONArray()
            formats.forEach { arr.put(JSONObject(it.payload)) }
            put("formats", arr)
        }
        return Media.parse(json, "https://www.facebook.com/reel/9988776655")
    }

    // =========================================================================
    // OBJECTIVE 1: FORMAT CATALOGING & PRIORITY
    // =========================================================================

    @Test
    fun test01_numericVideoQualityAndQualityLabelStrictlyPrioritizeSourceQualityLabelOverContainerDimensions() {
        // Pathological portrait video where container stream dimensions are 720x1280 (short edge 720),
        // but source representations represent lower tiers (480p, 360p, 240p, 144p).
        val f480 = videoFormat("dash_480p", width = 720, height = 1280, label = "480p")
        val f360 = videoFormat("dash_360p", width = 720, height = 1280, label = "360p")
        val f240 = videoFormat("dash_240p", width = 720, height = 1280, label = "240p")
        val f144 = videoFormat("dash_144p", width = 720, height = 1280, label = "144p")
        val f720NoLabel = videoFormat("dash_720_nolabel", width = 720, height = 1280, label = null)

        // 1. sourceQualityLabel takes strict priority over physical dimensions
        assertEquals(480, qualityLabel(f480))
        assertEquals(480, QuickShareCatalog.numericVideoQuality(f480))
        assertEquals("480p", QuickShareCatalog.videoDisplayLabel(f480))

        assertEquals(360, qualityLabel(f360))
        assertEquals(360, QuickShareCatalog.numericVideoQuality(f360))
        assertEquals("360p", QuickShareCatalog.videoDisplayLabel(f360))

        assertEquals(240, qualityLabel(f240))
        assertEquals(240, QuickShareCatalog.numericVideoQuality(f240))
        assertEquals("240p", QuickShareCatalog.videoDisplayLabel(f240))

        assertEquals(144, qualityLabel(f144))
        assertEquals(144, QuickShareCatalog.numericVideoQuality(f144))
        assertEquals("144p", QuickShareCatalog.videoDisplayLabel(f144))

        // 2. Format without sourceQualityLabel falls back to short edge: minOf(720, 1280) = 720
        assertEquals(720, qualityLabel(f720NoLabel))
        assertEquals(720, QuickShareCatalog.numericVideoQuality(f720NoLabel))
        assertEquals("720p", QuickShareCatalog.videoDisplayLabel(f720NoLabel))

        // 3. Physical container dimensions remain untouched
        assertEquals(720, f480.width)
        assertEquals(1280, f480.height)
        assertEquals(720, f360.width)
        assertEquals(1280, f360.height)
    }

    @Test
    fun test02_pathologicalFacebookDashIdenticalContainerDimensionsDecoupleIntoDistinctLogicalTiers() {
        // Facebook DASH manifest where 720p, 540p, 480p, 360p, and 240p share the exact same 720x1280 container dimensions
        val dash720 = videoFormat("dash_720p", 720, 1280, label = "720p", bitrate = 2500.0, dimensionsStatus = "unverified_label")
        val dash540 = videoFormat("dash_540p", 720, 1280, label = "540p", bitrate = 1600.0, dimensionsStatus = "unverified_label")
        val dash480 = videoFormat("dash_480p", 720, 1280, label = "480p", bitrate = 1000.0, dimensionsStatus = "unverified_label")
        val dash360 = videoFormat("dash_360p", 720, 1280, label = "360p", bitrate = 600.0, dimensionsStatus = "unverified_label")
        val dash240 = videoFormat("dash_240p", 720, 1280, label = "240p", bitrate = 300.0, dimensionsStatus = "unverified_label")
        val audio = audioFormat()

        val media = buildMedia(30.0, dash720, dash540, dash480, dash360, dash240, audio)

        // 1. media.qualityGroups() forms 5 discrete quality tiers
        val groups = media.qualityGroups(capabilities)
        assertEquals(5, groups.size)
        assertEquals(listOf(720, 540, 480, 360, 240), groups.map { it.resolution })

        // 2. QuickShareCatalog.videoEntries(media) contains EXACTLY 5 entries
        val entries = QuickShareCatalog.videoEntries(media)
        assertEquals(5, entries.size)
        assertEquals(listOf(240, 360, 480, 540, 720), entries.map { QuickShareCatalog.numericVideoQuality(it.format) })

        // 3. All underlying raw formats remain preserved in media.formats (no candidates destroyed)
        assertEquals(6, media.formats.size) // 5 video + 1 audio
    }

    @Test
    fun test03_catalogVideoEntriesStrictSingleEntryPerLogicalQualityTierEvenWithCodecVariants() {
        // Multiple variants at 720p (H.264, AV1, VP9, 60fps vs 30fps) and 1080p (AV1 vs H.264)
        val f720_h264 = videoFormat("720_h264", 1280, 720, label = "720p", vcodec = "h264", bitrate = 2000.0)
        val f720_av1 = videoFormat("720_av1", 1280, 720, label = "720p", vcodec = "av1", bitrate = 1500.0)
        val f720_vp9 = videoFormat("720_vp9", 1280, 720, label = "720p", vcodec = "vp9", bitrate = 1700.0)
        val f720_60fps = videoFormat("720_60fps", 1280, 720, label = "720p", vcodec = "h264", bitrate = 3000.0).copy(fps = 60.0)

        val f1080_h264 = videoFormat("1080_h264", 1920, 1080, label = "1080p", vcodec = "h264", bitrate = 4500.0)
        val f1080_av1 = videoFormat("1080_av1", 1920, 1080, label = "1080p", vcodec = "av1", bitrate = 3500.0)

        val media = buildMedia(30.0, f720_h264, f720_av1, f720_vp9, f720_60fps, f1080_h264, f1080_av1)

        // 1. Exactly 2 entries in catalog videoEntries (one for 720p, one for 1080p)
        val entries = QuickShareCatalog.videoEntries(media)
        assertEquals("Must produce exactly one visible catalog row per logical quality tier", 2, entries.size)
        assertEquals(listOf(720, 1080), entries.map { QuickShareCatalog.numericVideoQuality(it.format) })

        // 2. All 6 format candidates remain preserved in media.formats
        assertEquals(6, media.formats.size)

        // 3. Quality groups preserve all variants internally
        val groups = media.qualityGroups(capabilities)
        assertEquals(2, groups.size)
        val g720 = groups.first { it.resolution == 720 }
        assertEquals(4, g720.candidates.size)
        assertTrue(g720.hasMeaningfulVariants)

        val g1080 = groups.first { it.resolution == 1080 }
        assertEquals(2, g1080.candidates.size)
        assertTrue(g1080.hasMeaningfulVariants)
    }

    // =========================================================================
    // OBJECTIVE 2: PRESET CAPS STRICTNESS
    // =========================================================================

    @Test
    fun test04_presetCapsStrictlyEnforceCandidateLessThanOrEqualToCap() {
        val f360 = videoFormat("v360", 640, 360, label = "360p", bitrate = 600.0)
        val f480 = videoFormat("v480", 854, 480, label = "480p", bitrate = 1000.0)
        val f540 = videoFormat("v540", 960, 540, label = "540p", bitrate = 1500.0)
        val f720 = videoFormat("v720", 1280, 720, label = "720p", bitrate = 2500.0)
        val f1080 = videoFormat("v1080", 1920, 1080, label = "1080p", bitrate = 4500.0)

        val media = buildMedia(30.0, f360, f480, f540, f720, f1080)

        // 720p cap -> selects 720p
        val sel720 = DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
        assertEquals("v720", sel720.primary.id)
        assertTrue(qualityLabel(sel720.primary)!! <= 720)

        // 480p cap -> selects 480p
        val sel480 = DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
        assertEquals("v480", sel480.primary.id)
        assertTrue(qualityLabel(sel480.primary)!! <= 480)

        // 360p cap -> selects 360p
        val sel360 = DownloadRequest(height = 360, exact = false).resolve(media, capabilities)
        assertEquals("v360", sel360.primary.id)
        assertTrue(qualityLabel(sel360.primary)!! <= 360)
    }

    @Test
    fun test05_adversarialCondition_480pRequestedWithOnly720pAnd360pAvailable_selects360pNever720p() {
        // Adversarial condition explicitly specified in dispatch:
        // Only 720p and 360p are available; 480p is missing.
        val f720 = videoFormat("v720", 1280, 720, label = "720p", bitrate = 2500.0)
        val f360 = videoFormat("v360", 640, 360, label = "360p", bitrate = 600.0)
        val media = buildMedia(30.0, f720, f360)

        // 1. DownloadRequest with 480p cap
        val sel480 = DownloadRequest(height = 480, exact = false).resolve(media, capabilities)
        assertNotNull(sel480)
        assertEquals("Must select 360p when 480p requested and only 720p and 360p available", "v360", sel480.primary.id)
        assertNotEquals("MUST NEVER select 720p when 480p cap requested", "v720", sel480.primary.id)
        assertEquals(360, qualityLabel(sel480.primary))
        assertTrue(qualityLabel(sel480.primary)!! <= 480)

        // 2. QuickShareFormats.preferenceChoice with 480 cap
        val prefChoice480 = QuickShareFormats.preferenceChoice(media, 480)
        assertNotNull(prefChoice480)
        assertEquals("v360", prefChoice480!!.formatId)
        assertNotEquals("v720", prefChoice480.formatId)

        // 3. VideoPreferenceSelection.ready with 480 falls back through resolver without upgrading to 720p
        val readyChoice = QuickShareFormats.preferenceChoice(media, 480)?.resolve(media)
        assertNotNull(readyChoice)
        assertEquals("v360", readyChoice!!.primary.id)
    }

    @Test
    fun test06_adversarialCondition_720pCapWithOnly1080pAvailable_neverSilentlyUpgradesTo1080p() {
        // Adversarial condition explicitly specified in dispatch:
        // Only 1080p is available; cap is 720p.
        val f1080 = videoFormat("v1080", 1920, 1080, label = "1080p", bitrate = 4500.0)
        val media = buildMedia(30.0, f1080)

        // 1. QualityResolver MUST throw QUALITY_UNAVAILABLE, NEVER return 1080p
        try {
            DownloadRequest(height = 720, exact = false).resolve(media, capabilities)
            fail("Expected QUALITY_UNAVAILABLE when only 1080p available and cap is 720p")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE, e.code)
        }

        // 2. QuickShareFormats.preferenceChoice MUST return null, NEVER v1080
        val prefChoice720 = QuickShareFormats.preferenceChoice(media, 720)
        assertNull("preferenceChoice must return null when no format satisfies <= 720p cap", prefChoice720)

        // 3. Same strict behavior for 480p and 360p caps
        assertNull(QuickShareFormats.preferenceChoice(media, 480))
        assertNull(QuickShareFormats.preferenceChoice(media, 360))
    }

    // =========================================================================
    // OBJECTIVE 3: FORMAT SIZING BOUNDS & FLAC UNKNOWN SIZE
    // =========================================================================

    @Test
    fun test07_formatSizesBoundedProbing_max16Requests_max3Concurrent_and2sTimeout() = runBlocking {
        // Construct a media object with 25 unmeasured video formats
        val formats = (1..25).map { i ->
            videoFormat(
                id = "probe_$i",
                width = 1280,
                height = 720,
                label = "720p",
                bitrate = 1000.0 + i,
                filesize = null, // unmeasured -> needs probe
                url = "https://cdn.example.com/stream_$i.mp4"
            )
        }.toTypedArray()
        val media = buildMedia(30.0, *formats)

        val totalRequestsStarted = AtomicInteger(0)
        val currentConcurrency = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        // Interceptor that simulates HTTP server responses while recording request bounds
        val customClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = totalRequestsStarted.incrementAndGet()
                val active = currentConcurrency.incrementAndGet()
                var max = maxObservedConcurrency.get()
                while (active > max && !maxObservedConcurrency.compareAndSet(max, active)) {
                    max = maxObservedConcurrency.get()
                }

                // Simulate slight network delay
                Thread.sleep(50)

                currentConcurrency.decrementAndGet()

                val request = chain.request()
                // Assert probing is non-body downloading (either HEAD or Range bytes=0-0)
                assertTrue("Request must be HEAD or GET range", request.method == "HEAD" || request.header("Range") == "bytes=0-0")

                // Return valid Content-Length response
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", "25000000")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Encoding", "identity")
                    .body("".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val enriched = FormatSizes.enrich(media, client = customClient)
        assertNotNull(enriched)

        // 1. Total probed requests must be capped at 16 (never all 25)
        assertTrue("Probed requests (${totalRequestsStarted.get()}) must not exceed 16", totalRequestsStarted.get() <= 16)
        assertEquals(16, totalRequestsStarted.get())

        // 2. Maximum concurrency must be bounded by 3 (from Semaphore(3))
        assertTrue("Max concurrency (${maxObservedConcurrency.get()}) must not exceed 3", maxObservedConcurrency.get() <= 3)

        // 3. Enriched formats should have updated filesize
        val enrichedMeasured = enriched.formats.filter { it.bytes == 25000000L }
        assertEquals(16, enrichedMeasured.size)
    }

    @Test
    fun test08_formatSizesTimeoutStrictlyCancelsAfter2Seconds() = runBlocking {
        // Construct formats with slow server (simulating 5000ms delay per request)
        val formats = (1..5).map { i ->
            videoFormat(
                id = "slow_$i",
                filesize = null,
                url = "https://cdn.example.com/slow_$i.mp4"
            )
        }.toTypedArray()
        val media = buildMedia(30.0, *formats)

        val slowClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Sleep for 3000ms (exceeding 2000ms withTimeoutOrNull)
                Thread.sleep(3000)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", "1000000")
                    .body("".toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val startTime = System.currentTimeMillis()
        val enriched = FormatSizes.enrich(media, client = slowClient)
        val durationMs = System.currentTimeMillis() - startTime

        assertNotNull(enriched)
        // FormatSizes has withTimeoutOrNull(2000), so execution must return within ~2500ms
        assertTrue("FormatSizes.enrich took $durationMs ms; must finish in <= 2500 ms due to 2s timeout", durationMs < 2500)
    }

    @Test
    fun test09_flacAudioConversionStrictlyEvaluatesToUnknownSize() {
        val audioAac = audioFormat("audio_aac", bitrate = 192.0, filesize = 5_000_000L)
        val audioOpus = audioFormat("audio_opus", bitrate = 128.0, acodec = "opus", ext = "opus")
        val media = buildMedia(60.0, audioAac, audioOpus)

        // 1. FLAC conversion: Selection.audio with output = "flac"
        val selFlac = Selection.audio(media, output = "flac")
        assertTrue(selFlac.audioOnly)
        assertTrue(selFlac.convertAudio)
        assertEquals("flac", selFlac.output)
        // MUST evaluate strictly to unknown size: outputSize == null
        assertNull("FLAC audio conversion must evaluate to null outputSize", selFlac.outputSize)

        // 2. Contrast with MP3 conversion: outputSize is non-null and estimated from bitrate * duration
        val selMp3 = Selection.audio(media, output = "mp3")
        assertTrue(selMp3.convertAudio)
        assertEquals("mp3", selMp3.output)
        assertNotNull("MP3 conversion outputSize must not be null", selMp3.outputSize)
        assertTrue(selMp3.outputSize!!.estimated)
        // 60.0s * 192 kbps * 1000 / 8 = 1,440,000 bytes
        assertEquals(1_440_000L, selMp3.outputSize!!.bytes)

        // 3. Contrast with M4A conversion: outputSize is non-null
        val selM4a = Selection.audio(media, output = "m4a", bitrate = 128)
        assertTrue(selM4a.convertAudio)
        assertNotNull("M4A conversion outputSize must not be null", selM4a.outputSize)
        assertEquals(960_000L, selM4a.outputSize!!.bytes)

        // 4. Bitrate 0 conversion also evaluates to null
        val selZeroRate = selMp3.copy(bitrate = 0)
        assertNull("Bitrate 0 conversion must have null outputSize", selZeroRate.outputSize)
    }

    @Test
    fun test10_dashBandwidthAndBitrateFallbacksForApproximateSizes() {
        // 1. Video format with duration and bitrate but no filesize -> Format.size(duration) calculates approximate size
        val duration = 40.0
        val bitrateKbps = 2000.0 // 2000 kbps
        val formatApprox = videoFormat("dash_approx", bitrate = bitrateKbps, filesize = null)
        val calculatedSize = formatApprox.size(duration)

        assertNotNull(calculatedSize)
        assertTrue(calculatedSize!!.estimated)
        // duration * bitrateKbps * 1000 / 8 = 40.0 * 2000.0 * 1000 / 8 = 10,000,000 bytes
        assertEquals(10_000_000L, calculatedSize.bytes)

        // 2. Format with filesize_approx in payload sets sizeEstimated = true
        val formatPayloadApprox = videoFormat("dash_payload_approx", bitrate = null, filesize = null, filesizeApprox = 12_500_000L)
        assertTrue(formatPayloadApprox.sizeEstimated)
        assertEquals(12_500_000L, formatPayloadApprox.bytes)
        assertEquals(MediaSize(12_500_000L, estimated = true), formatPayloadApprox.size(duration))

        // 3. Exact filesize sets sizeEstimated = false
        val formatExact = videoFormat("dash_exact", bitrate = bitrateKbps, filesize = 8_000_000L)
        assertFalse(formatExact.sizeEstimated)
        assertEquals(MediaSize(8_000_000L, estimated = false), formatExact.size(duration))

        // 4. Combined bitrate fallback when primary video and secondary audio individual sizes are null
        val videoOnly = videoFormat("vid_only", bitrate = null, filesize = null, acodec = "none").copy(combinedBitrate = 3200.0)
        val audioOnly = audioFormat("aud_only", bitrate = null, filesize = null)
        val media = buildMedia(duration, videoOnly, audioOnly)

        val selection = Selection(primary = videoOnly, secondary = audioOnly, duration = duration, output = "mp4")
        val transferSize = selection.transferSize
        assertNotNull(transferSize)
        assertTrue(transferSize!!.estimated)
        // duration * combinedBitrate * 1000 / 8 = 40.0 * 3200.0 * 1000 / 8 = 16,000,000 bytes
        assertEquals(16_000_000L, transferSize.bytes)
    }
}
