package com.zdownloader.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FunctionalAuditTest {
    private val video=Format("video","mp4",720,30.0,"h264","none",1000,1000.0,"https","{\"url\":\"https://example.com/video.mp4\"}")
    private val audio=Format("audio","m4a",null,null,"none","aac",100,128.0,"https","{\"url\":\"https://example.com/audio.m4a\"}")
    private fun media(url:String="https://www.facebook.com/share/v/first/")=Media(url,"123456","Title","Facebook",null,null,10.0,listOf(video,audio))

    @Test fun pastedCaptionsAndMultipleLinksReachTheSharedInputContract() {
        assertEquals(listOf("https://example.com/a","https://example.com/b"),UrlPolicy.inputLinks("Watch https://example.com/a\nhttps://example.com/b"))
        assertThrows(MediaException::class.java) {UrlPolicy.inputLinks("http://example.com/a")}
    }
    @Test fun balancedUrlParenthesesArePreservedAndProseDelimitersRemoved() {
        assertEquals(listOf("https://example.com/clip_(one)"),UrlPolicy.links("(https://example.com/clip_(one))."))
        assertEquals(listOf("https://[2001:4860:4860::8888]/v"),UrlPolicy.links("[https://[2001:4860:4860::8888]/v]"))
    }
    @Test fun extractorShareAliasesDeduplicateWithoutCollapsingDifferentMedia() {
        val first=media();val selected=Selection.video(first,video)
        assertEquals(RequestFingerprint.create(first,selected),RequestFingerprint.create(media("https://www.facebook.com/share/v/second/"),selected))
        assertNotEquals(RequestFingerprint.create(first,selected),RequestFingerprint.create(first.copy(id="654321"),selected))
    }
    @Test fun genericIdenticalBasenamesKeepDistinctFullUrls() {
        val first=media("https://example.com/first/clip.mp4").copy(source="example.com",id="clip")
        val second=first.copy(url="https://example.com/second/clip.mp4")
        assertNotEquals(RequestFingerprint.create(first,Selection(video)),RequestFingerprint.create(second,Selection(video)))
    }
    @Test fun mkvAlternativeUsesTheSameIdentityAsFreshMkvSubmission() {
        val m=media();val original=Selection.video(m,video);val alternate=original.copy(output="mkv")
        assertEquals(RequestFingerprint.create(m,alternate),RequestFingerprint.alternative(RequestFingerprint.create(m,original),m.url,alternate))
        assertEquals(RequestFingerprint.legacy(m.url,alternate),RequestFingerprint.alternative("old-hash",m.url,alternate))
    }
    @Test fun irrelevantBitrateDoesNotDuplicateOriginalAudioButRealConversionsRemainDistinct() {
        val m=media();val original=Selection.audio(m,"original")
        assertEquals(RequestFingerprint.create(m,original.copy(bitrate=128)),RequestFingerprint.create(m,original.copy(bitrate=320)))
        val converted=Selection.audio(m,"mp3")
        assertNotEquals(RequestFingerprint.create(m,converted.copy(bitrate=128)),RequestFingerprint.create(m,converted.copy(bitrate=320)))
    }
    @Test fun audioPreferenceIsAppliedToQuickShareAndSilentSourcesRemainUsable() {
        assertTrue(QuickShareFormats.defaultChoice(media(),720,"audio")!!.audio)
        assertFalse(QuickShareFormats.defaultChoice(media().copy(formats=listOf(video)),720,"audio")!!.audio)
    }
    @Test fun explicitMissingAudioCannotSilentlySwitchTracksAndUnsupportedProtocolsAreHidden() {
        val unsupported=audio.copy(id="rtmp",protocol="rtmp",audioBitrate=9999.0)
        val m=media().copy(formats=listOf(video,audio,unsupported))
        assertEquals(audio,Selection.audio(m,"original").primary)
        assertThrows(MediaException::class.java) {Selection.audio(m,"original",unsupported)}
        assertThrows(MediaException::class.java) {Selection.audio(m,"original",audio.copy(id="removed"))}
    }
    @Test fun preferredAudioLanguageBeatsContainerConvenience() {
        val preferred=audio.copy(id="preferred",ext="webm",audioCodec="opus",languagePreference=10.0)
        val commentary=audio.copy(languagePreference=-10.0)
        assertEquals(preferred,Selection.video(media().copy(formats=listOf(video,preferred,commentary)),video).secondary)
    }
    @Test fun extensionsAreSafeAndMissingVideoExtensionDoesNotHideRealAudioExtension() {
        val json=JSONObject().put("video_ext","none").put("audio_ext","M4A")
        assertEquals("m4a",Format.parse(json).ext)
        assertThrows(MediaException::class.java) {Format.parse(JSONObject().put("ext","../../bad"))}
        assertThrows(MediaException::class.java) {FileNames.output("name","../bad","{title}")}
        assertEquals("name.mp4",FileNames.output("name.MP4","mp4","{title}"))
    }
    @Test fun oneBrokenCandidateDoesNotDiscardAValidExtractedRepresentation() {
        val valid=JSONObject().put("format_id","ok").put("url","https://example.com/video.mp4").put("ext","mp4").put("vcodec","h264").put("acodec","aac")
        val bad=JSONObject(valid.toString()).put("url","file:///private.mp4")
        val parsed=Media.parse(JSONObject().put("formats",JSONArray().put(bad).put(valid)),"https://example.com/page")
        assertEquals(listOf("ok"),parsed.formats.map {it.id});assertEquals("Media",parsed.title)
        assertThrows(MediaException::class.java) {Media.parse(JSONObject().put("formats",JSONArray().put(bad)),"https://example.com/page")}
    }
    @Test fun invalidNumericRangeTotalsAreNotReinterpretedAsUnknownLength() {
        assertNull(ContentRange.parse("bytes 0-9/999999999999999999999999999"))
        assertEquals(ContentRange(0,9,null),ContentRange.parse("bytes 0-9/*"))
    }
}
