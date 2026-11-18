package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class VideoPreferenceSelectionTest {
    private fun video(id:String,height:Int)=Format.parse(org.json.JSONObject().put("format_id",id).put("ext","mp4").put("height",height).put("width",height*16/9)
        .put("fps",30.0).put("vcodec","h264").put("acodec","none").put("filesize",1000).put("tbr",1000.0).put("protocol","https").put("url","https://example.com/$id.mp4"))
    private val audio=Format("audio","m4a",null,null,"none","aac",1000,128.0,"https","{\"url\":\"https://example.com/audio.m4a\"}")
    private fun media(vararg formats:Format)=Media("https://example.com/watch","id","Title","Source",null,null,10.0,formats.toList())

    @Test fun readyVideoUsesTheSamePolicyAndVerifiedStreamsWithoutAnotherDiscovery() {
        val source=media(video("1080",1080),video("720",720),video("480",480),audio)
        val ready=VideoPreferenceSelection.ready(source,720)!!
        val expected=DownloadRequest(height=720).resolve(source)
        assertEquals(expected.json(),ready.json())
        assertEquals("720",ready.primary.id)
        assertEquals("audio",ready.secondary?.id)
        assertFalse(ready.audioOnly)
    }
    @Test fun audioNeverEntersTheNewVideoPath() {
        val source=media(video("720",720),audio)
        assertNull(VideoPreferenceSelection.ready(source,-1))
        assertNull(VideoPreferenceSelection.ready(media(audio),720))
    }
    @Test fun absentUnselectedAndUnsupportedPreferencesKeepExistingBackgroundResolution() {
        val source=media(video("720",720),audio)
        assertNull(VideoPreferenceSelection.ready(null,720))
        assertNull(VideoPreferenceSelection.ready(source,null))
        assertNull(VideoPreferenceSelection.ready(source,620))
        assertNull(VideoPreferenceSelection.ready(media(),720))
    }
    @Test fun quickShareCapsSelectTheHighestSourceAtOrBelowTheLimitWhileBestIsExplicit() {
        val source=media(video("1080",1080),video("480",480),audio)
        for(height in listOf(0,480,1080)) {
            assertEquals(VideoPreferenceSelection.request(height).resolve(source).json(),VideoPreferenceSelection.ready(source,height)!!.json())
        }
        assertNull(VideoPreferenceSelection.ready(source,360))
        assertEquals("480",VideoPreferenceSelection.ready(source,720)?.primary?.id)
        assertEquals("1080",VideoPreferenceSelection.ready(source,2160)?.primary?.id)
        assertNull(VideoPreferenceSelection.ready(media(video("720",720),audio),480))
        assertEquals("360",VideoPreferenceSelection.ready(media(video("360",360),audio),480)?.primary?.id)
        assertFalse(VideoPreferenceSelection.request(480).exact)
        assertFalse(DownloadRequest.parse(VideoPreferenceSelection.request(480).json()).exact)
    }
    @Test fun facebook540IsSkippedForA480CapInFavorOfTheLowerVerifiedFormat() {
        val source=media(video("1080",1080),video("720",720),video("540",540),video("360",360),audio)
        assertEquals("360",VideoPreferenceSelection.ready(source,480)?.primary?.id)
        assertEquals("720",VideoPreferenceSelection.ready(source,720)?.primary?.id)
        val unknown=video("hd",720).copy(height=null,width=null)
        assertNull(VideoPreferenceSelection.ready(media(video("540",540),unknown,audio),480))
    }
    @Test fun everyNumberedQuickChoiceKeepsItsFormatIdUrlAndDimensions() {
        val formats=DownloadRequest.heights.filter {it>0}.map {video("v$it",it)}
        val source=media(*(formats+audio).toTypedArray())
        for(format in formats) {
            val selection=VideoPreferenceSelection.ready(source,format.height)!!
            assertEquals(format,selection.primary)
            assertEquals(format,Selection.parse(selection.json()).primary)
        }
    }
    @Test fun unavailableStreamsAreNotReusedAsReadyVideo() {
        val unsupported=video("unsupported",720).copy(protocol="rtmp")
        assertNull(VideoPreferenceSelection.ready(media(unsupported,audio),720))
        val expired=Format.parse(org.json.JSONObject().put("format_id","expired").put("ext","mp4")
            .put("url","https://example.com/video.mp4?expire=1000000000").put("vcodec","h264").put("acodec","aac").put("height",720).put("width",1280))
        assertNull(VideoPreferenceSelection.ready(media(expired),720))
    }
}
