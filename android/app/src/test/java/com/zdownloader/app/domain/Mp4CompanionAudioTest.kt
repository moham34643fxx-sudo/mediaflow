package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Mp4CompanionAudioTest {
    private fun track(id:String,codec:String,rate:Double,sampleRate:Int=48000,lang:String?="en")=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id").put("protocol","https")
        .put("ext",if(codec=="opus") "webm" else "m4a").put("vcodec","none").put("acodec",codec)
        .put("abr",rate).put("language",lang).put("language_preference",1)
        .put("audio_channels",2).put("asr",sampleRate))
    private val video=Format.parse(JSONObject().put("format_id","vp9").put("url","https://example.com/video")
        .put("protocol","https").put("ext","webm").put("vcodec","vp9").put("acodec","none")
        .put("width",1920).put("height",1080).put("fps",30))
    private val opus=track("opus","opus",128.0,48000)
    private val aac=track("aac","mp4a.40.2",132.0,48000)
    private fun media(vararg formats:Format)=Media("https://example.com/movie","movie","Movie","test",null,null,60.0,formats.toList())

    @Test fun mp4UsesSuitableNativeAacWithoutChangingSelectedVideo() {
        val selected=Selection.video(media(video,opus,aac),video,"mp4")
        assertEquals(video,selected.primary)
        assertEquals(aac,selected.secondary)
        assertFalse(selected.transcodeAudio)
        assertEquals("mp4",selected.output)
    }

    @Test fun aacLcAt44100HzIsEligibleWith48000HzOpusOriginal() {
        // Standard YouTube production scenario: Opus is 48 kHz, while AAC-LC is 44.1 kHz.
        val aac441 = aac.copy(id="aac441", sampleRate=44100, audioBitrate=128.0)
        val selected = Selection.video(media(video, opus, aac441), video, "mp4")
        assertEquals(video, selected.primary)
        assertEquals(aac441, selected.secondary)
        assertFalse(selected.transcodeAudio)
        assertEquals("mp4", selected.output)
    }

    @Test fun compatibilityNeverOverridesLanguageChannelsSampleRateOrDeclaredQuality() {
        val unsuitable=listOf(
            aac.copy(language="fr"),
            aac.copy(languagePreference=-1.0),
            aac.copy(audioChannels=1),
            aac.copy(audioChannels=6),
            aac.copy(sampleRate=22050),
            aac.copy(sampleRate=16000),
            aac.copy(audioBitrate=64.0),
            aac.copy(audioBitrate=48.0),
            aac.copy(audioChannels=null),
            aac.copy(sampleRate=null),
            aac.copy(audioBitrate=null,bitrate=null))
        for(candidate in unsuitable) {
            val selected=Selection.video(media(video,opus,candidate),video,"mp4")
            assertEquals("Unsuitable AAC: $candidate",opus,selected.secondary)
            assertTrue(selected.transcodeAudio)
        }
    }

    @Test fun normalAacPreferredOverDrcWhenBothAvailable() {
        val aacNormal = aac.copy(id="140", audioBitrate=129.5, sampleRate=44100)
        val aacDrc = aac.copy(id="140-drc", audioBitrate=129.5, sampleRate=44100)
        val selected = Selection.video(media(video, opus, aacDrc, aacNormal), video, "mp4")
        assertEquals(aacNormal, selected.secondary)
        assertFalse(selected.transcodeAudio)
    }

    @Test fun missingOriginalLanguageAllowsCandidateWithoutLanguage() {
        val opusNoLang = opus.copy(language=null)
        val aacNoLang = aac.copy(language=null, sampleRate=44100)
        val selected = Selection.video(media(video, opusNoLang, aacNoLang), video, "mp4")
        assertEquals(aacNoLang, selected.secondary)
        assertFalse(selected.transcodeAudio)
    }

    @Test fun unknownOriginalProfileDoesNotAuthorizeSwitchingTracks() {
        val unknownProfiles=listOf(opus.copy(language=null),opus.copy(audioChannels=null),
            opus.copy(sampleRate=null),opus.copy(audioBitrate=null,bitrate=null))
        for(source in unknownProfiles)
            assertEquals(source,Selection.video(media(video,source,aac),video,"mp4").secondary)
    }

    @Test fun fallbackToOriginalOpusWhenNoAacCandidateExists() {
        val selected = Selection.video(media(video, opus), video, "mp4")
        assertEquals(video, selected.primary)
        assertEquals(opus, selected.secondary)
        assertTrue(selected.transcodeAudio)
    }

    @Test fun highestRateSuitableAacWinsButOriginalWebmPreferenceStays() {
        val better=aac.copy(id="better",audioBitrate=192.0)
        val catalog=media(video,opus,aac,better)
        assertEquals(better,Selection.video(catalog,video,"mp4").secondary)
        assertEquals(opus,Selection.video(catalog,video).secondary)
        assertEquals("webm",Selection.video(catalog,video).output)
    }

    @Test fun manualAndPersistedAudioChoicesNeverGetReplaced() {
        val catalog=media(video,opus,aac)
        assertEquals(opus,Selection.audio(catalog,"original",opus).primary)
        val manual=Selection(video,opus,output="mp4")
        assertEquals(opus,Selection.parse(manual.json()).secondary)
        assertTrue(Selection.parse(manual.json()).transcodeAudio)
    }

    @Test fun losslessSourceIsNotReplacedByALossyHigherRateTrack() {
        val lossless=track("lossless","flac",128.0).copy(ext="flac")
        assertEquals(lossless,Mp4CompanionAudio.select(listOf(aac.copy(audioBitrate=256.0)),lossless))
    }

    @Test fun combinedSourceDoesNotAcquireAnUnrequestedCompanion() {
        val combined=video.copy(id="combined",audioCodec="opus")
        val selected=Selection.video(media(combined,opus,aac),combined,"mp4")
        assertEquals(combined,selected.primary)
        assertNull(selected.secondary)
        assertTrue(selected.transcodeAudio)
    }
}
