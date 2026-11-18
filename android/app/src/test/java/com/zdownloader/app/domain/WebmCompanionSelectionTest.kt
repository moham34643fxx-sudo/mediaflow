package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebmCompanionSelectionTest {
    private fun video(codec:String="av01")=Format.parse(JSONObject().put("format_id","video")
        .put("url","https://example.com/video.mp4").put("ext","mp4").put("width",1280).put("height",720)
        .put("fps",60).put("dynamic_range",if(codecFamily(codec)=="av1") "HDR10" else "SDR").put("vcodec",codec).put("acodec","none"))
    private fun audio(id:String,codec:String,ext:String)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id.$ext").put("ext",ext).put("vcodec","none").put("acodec",codec)
        .put("language","ar").put("language_preference",10).put("audio_channels",2).put("asr",48000).put("abr",128))
    private val aac=audio("aac","mp4a.40.2","m4a")
    private val opus=audio("opus","opus","webm")
    private fun media(vararg formats:Format)=Media("https://example.com/watch","id","Fixture","Generic",null,null,30.0,formats.toList())

    @Test fun mp4SourceCanExposeWebmUsingMatchingOpusWithoutChangingDefaultOrVideoFidelity() {
        for(codec in listOf("av01","av1","vp09","vp08")) {
            val video=video(codec)
            val source=media(video,aac,opus)
            val default=Selection.video(source,video)
            assertEquals(aac,default.secondary)
            assertTrue("WebM missing for $codec",source.videoOutputs(video).contains("webm"))
            val webm=Selection.video(source,video,"webm")
            assertEquals(video,webm.primary)
            assertEquals(opus,webm.secondary)
            assertEquals(ProcessingPlan.MERGE_COPY,webm.plan)
            assertEquals(webm,QuickShareChoice(false,video.id,"webm").resolve(source))
            assertEquals(default,Selection.video(source,video))
        }
    }

    @Test fun webmIsNotAdvertisedBySacrificingLanguageChannelsOrAudioQuality() {
        val video=video()
        val incompatible=listOf(
            opus.copy(language="en"),opus.copy(languagePreference=0.0),opus.copy(audioChannels=1),
            opus.copy(sampleRate=16000),opus.copy(audioBitrate=48.0),opus.copy(language=null))
        for(candidate in incompatible) {
            val source=media(video,aac,candidate)
            assertFalse(source.videoOutputs(video).contains("webm"))
            assertNull(QuickShareChoice(false,video.id,"webm").resolve(source))
            assertEquals(aac,Selection.video(source,video).secondary)
        }
    }

    @Test fun incompatibleVideoAndMuxedAudioAreNotRelabelledAsWebm() {
        for(codec in listOf("avc1","hvc1","mpeg4")) {
            val video=video(codec)
            val source=media(video,aac,opus)
            assertFalse(source.videoOutputs(video).contains("webm"))
            assertNull(QuickShareChoice(false,video.id,"webm").resolve(source))
        }
        val combined=Format.parse(JSONObject(video().payload).put("acodec","aac"))
        val source=media(combined,opus)
        assertFalse(source.videoOutputs(combined).contains("webm"))
        assertEquals(combined,Selection.video(source,combined).primary)
    }

    @Test fun webmExtensionCannotOverrideIncompatibleDeclaredTracks() {
        val invalidAudio=video("vp09").copy(ext="webm",audioCodec="aac")
        assertFalse(videoOutputs(invalidAudio,null).contains("webm"))
        val invalidVideo=video("avc1").copy(ext="webm",audioCodec="opus")
        assertFalse(videoOutputs(invalidVideo,null).contains("webm"))
        val validCombined=video("vp09").copy(ext="webm",audioCodec="opus")
        assertTrue(videoOutputs(validCombined,null).contains("webm"))
    }
}
