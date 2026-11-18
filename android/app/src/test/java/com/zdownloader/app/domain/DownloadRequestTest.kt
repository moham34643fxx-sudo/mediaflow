package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DownloadRequestTest {
    private val capabilities=MediaIntelligence.CodecCapabilities(hasHardwareAvc=true)
    private fun video(id:String,w:Int?=1280,h:Int?=720,codec:String="h264",audio:String="none")=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.mp4").put("ext","mp4").put("vcodec",codec).put("acodec",audio)
        .put("width",w).put("height",h))
    private fun media(vararg formats:Format)=Media("https://example.com/media","id","title","source",null,null,10.0,formats.toList())
    private fun resolve(height:Int,vararg formats:Format)=DownloadRequest(height=height).resolve(media(*formats),capabilities)
    @Test fun capNeverFallsUpAndPrefersTheHighestAllowedRealEdge() {
        assertEquals("720",resolve(720,video("1080",1920,1080),video("480",854,480),video("720")).primary.id)
        assertEquals("480",resolve(720,video("1080",1920,1080),video("480",854,480)).primary.id)
        assertEquals(480,resolve(720,video("480",854,480)).primary.height)
    }
    @Test fun everySubsetOfRealQualitiesResolvesToItsMaximumWithinTheCap() {
        val edges=listOf(144,240,360,480,540,576,720,1080)
        for(mask in 1 until (1 shl edges.size)) {
            val available=edges.filterIndexed {index,_->mask and (1 shl index)!=0}
            val formats=available.reversed().map {video(it.toString(),it*16/9,it)}.toTypedArray()
            for(cap in listOf(144,240,360,480,720,1080)) {
                val expected=available.filter {it<=cap}.maxOrNull()
                if(expected!=null) assertEquals(expected,resolve(cap,*formats).primary.height)
                else assertEquals(ErrorCode.QUALITY_UNAVAILABLE,(runCatching {resolve(cap,*formats)}.exceptionOrNull() as MediaException).code)
            }
        }
    }
    @Test fun unavailable1080Resolves720WithoutUpscalingOrChangingPayload() {
        val original=video("720")
        val selection=resolve(1080,original)
        assertEquals(original,selection.primary);assertEquals(720,selection.primary.height)
    }
    @Test fun portraitAndNonstandardDimensionsUseTheActualShortEdge() {
        assertEquals("portrait",resolve(720,video("portrait",720,1280),video("1152",1152,2048)).primary.id)
        assertEquals("576",resolve(720,video("576",768,576),video("800",1920,800)).primary.id)
        assertEquals(1080,qualityCategory(video("1152",1152,2048)))
    }
    @Test fun exactUnknownOrAboveLimitFailsWithoutInventingAFormat() {
        for(f in listOf(video("unknown",null,null),video("above",1920,1080))) {
            val error=runCatching {DownloadRequest(height=720,exact=true).resolve(media(f),capabilities)}.exceptionOrNull()
            assertEquals(ErrorCode.FORMAT_UNAVAILABLE,(error as MediaException).code)
        }
    }
    @Test fun preferenceCannotSilentlyDownloadAboveTheCapOrAnUnknownSource() {
        for(formats in listOf(arrayOf(video("above",1920,1080)),arrayOf(video("unknown",null,null)),
            arrayOf(video("1080",1920,1080),video("540",540,960),video("720",1280,720)),
            arrayOf(video("1440",2560,1440),video("1080",1920,1080),video("720",1280,720)))) {
            assertEquals(ErrorCode.QUALITY_UNAVAILABLE,(runCatching {resolve(480,*formats)}.exceptionOrNull() as MediaException).code)
        }
        assertEquals("480",resolve(480,video("1080",1920,1080),video("720",1280,720),video("480",854,480)).primary.id)
    }
    @Test fun bestAndHigherCapsResolveActualSources() {
        assertEquals("4k",resolve(0,video("720"),video("4k",3840,2160)).primary.id)
        assertEquals("2k",resolve(1440,video("4k",3840,2160),video("2k",2560,1440)).primary.id)
        assertEquals("4k",resolve(2160,video("4k",3840,2160)).primary.id)
    }
    @Test fun separateTracksAndExplicitAudioOutputRemainExplicit() {
        val audio=Format.parse(JSONObject().put("format_id","audio").put("url","https://example.com/a.m4a")
            .put("ext","m4a").put("vcodec","none").put("acodec","aac"))
        val m=media(video("720"),audio)
        assertEquals(audio,DownloadRequest(height=720).resolve(m,capabilities).secondary)
        val selected=DownloadRequest(audioOnly=true,output="mp3").resolve(m,capabilities)
        assertTrue(selected.audioOnly);assertEquals("mp3",selected.output);assertTrue(selected.convertAudio)
        assertTrue(runCatching {DownloadRequest(audioOnly=true,output="mp4")}.isFailure)
    }
    @Test fun durableRoundTripNeedsNoFormatOrMetadata() {
        val request=DownloadRequest(height=720,template="{title}-mine",wifiOnly=true,retries=4,traceId="op",confirmedAt=123)
        assertEquals(request,DownloadRequest.parse(request.json()))
        assertFalse(JSONObject(request.json()).has("primary"))
        assertFalse(JSONObject(request.json()).has("format_id"))
    }
}
