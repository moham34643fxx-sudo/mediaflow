package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class MediaModelTest {
    private val video=Format("1080","mp4",1080,30.0,null,"none",null,1000.0,"https","{\"url\":\"https://example.com/video.mp4\"}")
    private val audio=Format("audio","m4a",null,null,"none","aac",1000,128.0,"https","{\"url\":\"https://example.com/audio.m4a\"}",audioBitrate=128.0)
    private fun media(vararg formats:Format)=Media("https://example.com/movie","id","title","source",null,null,60.0,formats.toList())
    @Test fun missingVideoCodecWithDimensionsRetainsVideoOnlyQuality() {
        assertTrue(video.video)
        assertFalse(video.audio)
        val selection=Selection.video(media(video,audio),video)
        assertEquals(audio,selection.secondary)
        assertEquals("mp4",selection.output)
        assertEquals(2,media(video,video.copy(id="720",height=720),audio).videoOptions().size)
    }
    @Test fun recommendationPrioritizesResolutionAndRespectsPreference() {
        val low=video.copy(id="360",height=360,audioCodec="aac")
        val mid=video.copy(id="720",height=720)
        val m=media(low,mid,video,audio)
        assertEquals("1080",m.recommendedVideo()?.id)
        assertEquals("720",m.recommendedVideo(720)?.id)
        assertEquals("360",m.recommendedVideo(480)?.id)
    }
    @Test fun qualityGroupsShowEachResolutionOnce() {
        val only=media(video.copy(id="720",height=720),video.copy(id="360",height=360))
        assertEquals(2,only.qualityGroups().size)
        val single=media(video.copy(id="480",height=480))
        assertEquals(listOf(480),single.qualityGroups().map { it.resolution })
    }
    @Test fun equivalentRepresentationsCollapseToOneGroupWithRecommended() {
        val variants=(1..5).map { video.copy(id="v$it",height=720,width=1280,fps=30.0,ext="mp4",videoCodec="h264",audioCodec="aac") }
        val groups=media(*variants.toTypedArray()).qualityGroups()
        assertEquals(1,groups.size)
        assertEquals(720,groups.single().resolution)
        assertEquals(5,groups.single().candidates.size)
        assertTrue(groups.single().recommended.audio)
    }
    @Test fun frameRateAlternativesStaySelectableInsideOneGroup() {
        val standard=video.copy(id="30",height=1080,fps=30.0)
        val high=video.copy(id="60",height=1080,fps=60.0)
        val groups=media(standard,high).qualityGroups()
        assertEquals(1,groups.size)
        assertEquals(2,groups.single().variantChoices.size)
    }
    @Test fun variantsPreserveRealCombinationsAndRecommendationPrefersFidelity() {
        val lowCombined=video.copy(id="combined",height=1080,width=1920,fps=30.0,videoBitrate=500.0,audioCodec="aac")
        val highSdr=video.copy(id="sdr60",height=1080,width=1920,fps=60.0,videoBitrate=4000.0,dynamicRange="SDR")
        val highHdr=highSdr.copy(id="hdr60",dynamicRange="HDR10")
        val narrow=highSdr.copy(id="narrow",width=1440,fps=29.97)
        val group=media(lowCombined,highSdr,highHdr,narrow).qualityGroups().single()
        assertEquals("hdr60",group.recommended.id)
        assertEquals(4,group.variantChoices.size)
        assertEquals("29.97",fpsLabel(narrow.fps))
    }
    @Test fun originalMp3DoesNotRequestReencoding() {
        val mp3=audio.copy(id="mp3",ext="mp3",audioCodec="mp3",audioBitrate=320.0)
        val selected=Selection.audio(media(mp3),"original",mp3)
        assertEquals("mp3",selected.output)
        assertFalse(selected.convertAudio)
        assertFalse(selected.transcodeAudio)
    }
    @Test fun originalAudioContainerFollowsCodecRatherThanVideoExtension() {
        val combined=video.copy(id="combined-opus",videoCodec="h264",audioCodec="opus",ext="mp4")
        val selected=Selection.audio(media(combined),"original",combined)
        assertEquals("opus",selected.output)
        assertFalse(selected.convertAudio)
        val vorbis=combined.copy(id="vorbis",audioCodec="vorbis",ext="mkv")
        assertEquals("ogg",Selection.audio(media(vorbis),"original",vorbis).output)
    }
    @Test fun refreshedTracksMustPreserveLanguageChannelsHdrAndKnownFrameRate() {
        val selected=video.copy(width=1920,videoCodec="h264",dynamicRange="HDR10",language="ar",audioChannels=2,sampleRate=48000)
        assertTrue(selected.copy(payload="{new transport}").preservesSelection(selected))
        assertFalse(selected.copy(language="en").preservesSelection(selected))
        assertFalse(selected.copy(audioChannels=6).preservesSelection(selected))
        assertFalse(selected.copy(dynamicRange="SDR").preservesSelection(selected))
        assertFalse(selected.copy(fps=null).preservesSelection(selected))
        assertFalse(selected.copy(width=1440).preservesSelection(selected))
    }
    @Test fun preferredAudioLanguageAndChannelsRemainDistinct() {
        val english=audio.copy(id="en",language="en",languagePreference=1.0,audioChannels=2)
        val arabic=audio.copy(id="ar",language="ar",languagePreference=10.0,audioChannels=6)
        assertEquals(arabic,Selection.audio(media(english,arabic),"original").primary)
        assertNotEquals(english.language to english.audioChannels,arabic.language to arabic.audioChannels)
    }
    @Test fun sizesDistinguishExactApproximateCombinedAndConversion() {
        assertEquals(MediaSize(1000,false),audio.size(60.0))
        assertEquals(MediaSize(1000,true),audio.copy(sizeEstimated=true).size(60.0))
        assertEquals(MediaSize(7500000,true),video.size(60.0))
        val merged=Selection.video(media(video,audio),video)
        assertEquals(7501000L,merged.size)
        assertTrue(merged.outputSize!!.estimated)
        assertEquals(1440000L,Selection.audio(media(video,audio),"m4a").outputSize!!.bytes)
        assertNull(Selection.audio(media(video,audio),"flac").outputSize)
        assertNull(video.copy(bitrate=null).size(null))
        assertNull(estimateBytes(Double.MAX_VALUE,Double.MAX_VALUE))
    }
    @Test fun webmUsesCompatibleAudioAndMp4RequiresRealConversion() {
        val webm=video.copy(ext="webm",videoCodec="vp9")
        val opus=audio.copy(id="opus",ext="webm",audioCodec="opus")
        val selected=Selection.video(media(webm,audio,opus),webm)
        assertEquals(opus,selected.secondary)
        assertEquals("webm",selected.output)
        val conversion=Selection.video(media(webm,opus),webm,"mp4")
        assertTrue(conversion.transcodeVideo)
        assertEquals(MediaSize(8940000,true),conversion.outputSize)
    }
    @Test fun exactStreamSizesTakePriorityOverManifestBitrate() {
        val v=video.copy(bytes=1000,combinedBitrate=5000.0)
        assertEquals(MediaSize(2000,false),Selection.video(media(v,audio),v).transferSize)
        val unknownAudio=audio.copy(bytes=null,bitrate=null,audioBitrate=null)
        assertEquals(MediaSize(37500000,true),Selection.video(media(v,unknownAudio),v).transferSize)
    }
    @Test fun directHttpsPreferredOverHlsWhenPropertiesMatch() {
        val hls1080 = video.copy(id="hls-1080", height=1080, width=1920, protocol="m3u8_native", bitrate=4000.0, videoCodec="h264")
        val direct1080 = video.copy(id="https-1080", height=1080, width=1920, protocol="https", bitrate=3500.0, videoCodec="h264")
        val m = media(hls1080, direct1080, audio)
        val group = m.qualityGroups().single { it.resolution == 1080 }
        assertEquals("Direct HTTPS must be preferred over HLS when resolution and codec match", "https-1080", group.recommended.id)
        val options = m.videoOptions()
        assertEquals("https-1080", options.first { it.height == 1080 }.id)
    }
    @Test fun hlsNeverDowngradedWhenHigherQualityThanDirectHttps() {
        val hls1080 = video.copy(id="hls-1080", height=1080, width=1920, protocol="m3u8", bitrate=4000.0, videoCodec="h264")
        val direct720 = video.copy(id="https-720", height=720, width=1280, protocol="https", bitrate=2500.0, videoCodec="h264")
        val m = media(hls1080, direct720, audio)
        val groups = m.qualityGroups()
        assertEquals(2, groups.size)
        assertEquals("1080p must remain available and recommended at top", 1080, groups.first().resolution)
        assertEquals("hls-1080", groups.first().recommended.id)
        assertEquals("hls-1080", m.recommendedVideo()?.id)
        assertEquals("https-720", m.recommendedVideo(720)?.id)
    }
    @Test fun audioSelectionPrefersDirectHttpsOverHls() {
        val hlsAudio = audio.copy(id="hls-audio", protocol="m3u8", audioBitrate=128.0)
        val directAudio = audio.copy(id="https-audio", protocol="https", audioBitrate=128.0)
        val m = media(video, hlsAudio, directAudio)
        val selected = Selection.audio(m, "m4a")
        assertEquals("https-audio", selected.primary.id)
    }
}
