package com.zdownloader.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaIntelligenceTest {
    @Test fun numberedRecommendationNeverFallsAboveTheCapOrBecomesAudio() {
        val high=media(video("720",720,"avc1",audio=true))
        assertNull(MediaIntelligence.recommend(high,480))
        assertNull(QuickShareFormats.defaultChoice(high,480))
        assertEquals(360,MediaIntelligence.recommend(media(video("360",360,"avc1"),video("540",540,"avc1")),480)?.height)
    }

    private fun video(
        id: String,
        height: Int,
        codec: String,
        ext: String = "mp4",
        bitrate: Double = 2000.0,
        audio: Boolean = false
    ): Format {
        val payload = JSONObject().apply {
            put("format_id", id)
            put("url", "https://example.com/$id.$ext")
            put("ext", ext)
            put("height", height)
            put("width", height * 16 / 9)
            put("vcodec", codec)
            put("acodec", if (audio) "aac" else "none")
            put("protocol", "https")
            put("tbr", bitrate)
        }
        return Format.parse(payload)
    }

    private fun media(vararg formats: Format): Media {
        return Media("https://example.com/watch","test_media","Test Video","example.com",null,null,120.0,formats.toList())
    }

    @Test
    fun hardwareHevcIsPreferredOverAvcForEquivalentResolution() {
        val avc = video("avc_1080", 1080, "avc1.640028", ext = "mp4")
        val hevc = video("hevc_1080", 1080, "hev1.1.6.L120", ext = "mp4")
        val caps = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = true,
            hasHardwareAv1 = false
        )

        val avcScore = MediaIntelligence.score(avc, preferredHeight = 1080, powerConstrained = false, capabilities = caps)
        val hevcScore = MediaIntelligence.score(hevc, preferredHeight = 1080, powerConstrained = false, capabilities = caps)

        // Hardware preference is a policy, not a claim of equal visual quality.
        assertTrue("HEVC ($hevcScore) should have a better score than AVC ($avcScore)", hevcScore < avcScore)
    }

    @Test
    fun softwareAv1IsPenalizedUnderPowerConstraints() {
        val avc = video("avc_1080", 1080, "avc1.640028")
        val av1 = video("av1_1080", 1080, "av01.0.08M.08")
        val caps = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = false,
            hasHardwareAv1 = false // No hardware AV1
        )

        val avcScore = MediaIntelligence.score(avc, preferredHeight = 1080, powerConstrained = true, capabilities = caps)
        val av1Score = MediaIntelligence.score(av1, preferredHeight = 1080, powerConstrained = true, capabilities = caps)

        // On battery/thermal limit, software AV1 must be penalized to protect battery
        assertTrue("Under power constraints, hardware AVC ($avcScore) must beat software AV1 ($av1Score)", avcScore < av1Score)
    }

    @Test
    fun hardwareAv1IsRewardedWhenHardwareSupported() {
        val avc = video("avc_1080", 1080, "avc1.640028")
        val av1 = video("av1_1080", 1080, "av01.0.08M.08")
        val caps = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = true,
            hasHardwareAv1 = true // Hardware AV1 present on modern chip
        )

        val avcScore = MediaIntelligence.score(avc, preferredHeight = 1080, powerConstrained = false, capabilities = caps)
        val av1Score = MediaIntelligence.score(av1, preferredHeight = 1080, powerConstrained = false, capabilities = caps)

        assertTrue("Hardware AV1 ($av1Score) should beat AVC ($avcScore)", av1Score < avcScore)
    }

    @Test
    fun recommendationPicksClosestMatchingResolutionToUserPreference() {
        val v480 = video("v480", 480, "avc1")
        val v720 = video("v720", 720, "avc1")
        val v1080 = video("v1080", 1080, "avc1")
        val m = media(v480, v720, v1080)

        val recommended720 = MediaIntelligence.recommend(m, preferredHeight = 720)
        assertNotNull(recommended720)
        assertEquals(720, recommended720!!.height)

        val recommended1080 = MediaIntelligence.recommend(m, preferredHeight = 1080)
        assertNotNull(recommended1080)
        assertEquals(1080, recommended1080!!.height)
    }

    @Test
    fun qualityGroupsRespectsHardwareHevcCapabilityInCodecRanking() {
        val avc1080 = video("avc_1080", 1080, "avc1.640028", bitrate = 2000.0)
        val hevc1080 = video("hevc_1080", 1080, "hev1.1.6.L120", bitrate = 2000.0)
        val m = media(avc1080, hevc1080)

        // With hardware HEVC available: HEVC should be recommended over AVC
        val capsWithHwHevc = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = true,
            hasHardwareAv1 = false
        )
        val groupsWithHw = m.qualityGroups(capsWithHwHevc)
        assertEquals("hevc_1080", groupsWithHw.single().recommended.id)

        // Without hardware HEVC: AVC should be recommended over HEVC to save CPU
        val capsWithoutHwHevc = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = false,
            hasHardwareAv1 = false
        )
        val groupsWithoutHw = m.qualityGroups(capsWithoutHwHevc)
        assertEquals("avc_1080", groupsWithoutHw.single().recommended.id)
    }

    @Test
    fun softwareAv1IsRankedBelowHardwareDecodersInQualityGroups() {
        val avc1080 = video("avc_1080", 1080, "avc1.640028", bitrate = 2000.0)
        val av11080 = video("av1_1080", 1080, "av01.0.08M.08", bitrate = 2000.0)
        val m = media(avc1080, av11080)

        // Device without hardware AV1 (e.g. MediaTek Dimensity/Helio on test phone):
        val capsNoHwAv1 = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = true,
            hasHardwareAv1 = false
        )
        val groups = m.qualityGroups(capsNoHwAv1)
        assertEquals("avc_1080", groups.single().recommended.id)
    }

    @Test
    fun hardwareHevcBeatsHigherBitrateAvcForSameResolutionAndFramerate() {
        // Bitrate alone cannot establish relative quality across codec families.
        val avc1080 = video("avc_1080", 1080, "avc1.640028", bitrate = 4000.0)
        val hevc1080 = video("hevc_1080", 1080, "hev1.1.6.L120", bitrate = 2200.0)
        val m = media(avc1080, hevc1080)

        val capsWithHwHevc = MediaIntelligence.CodecCapabilities(
            hasHardwareAvc = true,
            hasHardwareHevc = true,
            hasHardwareAv1 = false
        )
        val groupsWithHw = m.qualityGroups(capsWithHwHevc)
        // Prefer hardware HEVC within the same fidelity variant.
        assertEquals("hevc_1080", groupsWithHw.single().recommended.id)
    }

    @Test fun capabilityFallbackAndSoftwareDecoderNamesNeverClaimHardware() {
        val fallback=MediaIntelligence.CodecCapabilities()
        assertFalse(fallback.hasHardwareAvc)
        assertFalse(fallback.hasHardwareHevc)
        assertFalse(MediaIntelligence.hardwareDecoder("OMX.google.h264.decoder",false,null))
        assertFalse(MediaIntelligence.hardwareDecoder("c2.android.av1.decoder",false,null))
        assertFalse(MediaIntelligence.hardwareDecoder("OMX.vendor.sw.avc.decoder",false,null))
        assertFalse(MediaIntelligence.hardwareDecoder("OMX.vendor.hevc.encoder",true,true))
        assertFalse(MediaIntelligence.hardwareDecoder("c2.vendor.avc.decoder",false,false))
        assertTrue(MediaIntelligence.hardwareDecoder("c2.vendor.avc.decoder",false,true))
    }

    @Test fun formatSpecificUnsupportedHevcDoesNotBeatSupportedAvc() {
        val avc=video("avc",1080,"avc1.640028")
        val hevc=video("hevc",1080,"hev1.2.4.L153.B0")
        val caps=MediaIntelligence.CodecCapabilities(true,true,false,false) { it.id=="avc" }
        val m=media(avc,hevc)
        assertEquals("avc",m.recommendedVideo(capabilities=caps)?.id)
        assertEquals(m.recommendedVideo(capabilities=caps),MediaIntelligence.recommend(m,capabilities=caps))
        // Explicit selection is preserved even when not recommended.
        assertEquals(hevc,Selection.video(m,hevc).primary)
    }

    @Test fun hardwareCodecPreferenceDoesNotOverrideResolutionFrameRateHdrOrContainerSelection() {
        val hevc=video("hevc720",720,"hev1")
        val avc=video("avc1080",1080,"avc1").copy(fps=60.0)
        val caps=MediaIntelligence.CodecCapabilities(true,true,true,true)
        val m=media(hevc,avc)
        assertEquals("hevc720",m.recommendedVideo(720,caps)?.id)
        assertEquals("avc1080",m.recommendedVideo(1080,caps)?.id)
        val slower=avc.copy(id="hevc30",videoCodec="hev1",fps=30.0)
        assertEquals("avc1080",media(slower,avc).qualityGroups(caps).single().recommended.id)
        val hdr=avc.copy(id="hdr",dynamicRange="HDR10")
        assertEquals("hdr",media(slower,hdr).qualityGroups(caps).single().recommended.id)
        assertEquals("mkv",Selection.video(media(avc),avc,"mkv").output)
    }

    @Test fun hardwareVp9BeatsSoftwareHevcAndSoftwareOnlyFallbackIsAvc() {
        val formats=arrayOf(video("avc",1080,"avc1"),video("hevc",1080,"hev1"),video("vp9",1080,"vp09",ext="webm"),video("av1",1080,"av01"))
        val m=media(*formats)
        assertEquals("vp9",m.recommendedVideo(capabilities=MediaIntelligence.CodecCapabilities(false,false,false,true))?.id)
        assertEquals("avc",m.recommendedVideo(capabilities=MediaIntelligence.CodecCapabilities())?.id)
    }
}
