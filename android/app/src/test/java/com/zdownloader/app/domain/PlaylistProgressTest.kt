package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class PlaylistProgressTest {
    @Test fun byteWeightedProgressIsNotAnAverageOfItemPercentages() {
        val progress=PlaylistProgress(300,500,false,5,2,0,0,2)
        assertEquals(60,progress.percent)
        assertEquals(.6f,progress.fraction!!,.0001f)
    }
    @Test fun unknownAndStaleTotalsNeverInventPercentages() {
        assertNull(PlaylistProgress(300,null,false,5,2,0,0,2).percent)
        assertNull(PlaylistProgress(600,500,true,5,2,0,0,2).fraction)
    }
    @Test fun estimatedSizeRemainsExplicit() {
        val progress=PlaylistProgress(100,500,true,5,1,0,0,1)
        assertTrue(progress.estimated);assertEquals(20,progress.percent)
    }
    @Test fun allBytesAreNotPublishedCompletion() {
        val progress=PlaylistProgress(500,500,false,5,4,0,0,5)
        assertTrue(progress.finalizing);assertTrue(progress.indeterminate)
        assertFalse(progress.finished);assertNull(progress.percent)
    }
    @Test fun partialResultsAreNotAnnouncedAs100PercentSuccess() {
        val progress=PlaylistProgress(500,500,false,5,4,1,0,5)
        assertTrue(progress.finished);assertFalse(progress.successful);assertNull(progress.percent)
    }
    @Test fun onlyPublishedSuccessReaches100() {
        val progress=PlaylistProgress(500,500,false,5,5,0,0,5)
        assertTrue(progress.successful);assertEquals(100,progress.percent)
    }
    @Test fun selectedSizeHintsRespectPolicyAndUnknownItems() {
        val entry=PlaylistEntry("1","https://example.com/v",1,"Known",sizeHint=PlaylistSizeHint(500,false,true,true,720))
        assertEquals(MediaSize(1000,false),PlaylistPolicy(height=720).expected(listOf(entry,entry.copy(key="2"))))
        assertNull(PlaylistPolicy(height=360).expected(entry))
        assertNull(PlaylistPolicy().expected(listOf(entry,entry.copy(key="unknown",sizeHint=null))))
        assertEquals(500L,PlaylistPolicy(audio=true,output="mp3").expected(entry)?.bytes)
    }
    @Test fun requiredVideoAndAudioTrackBytesAreCountedOnce() {
        val video=Format.parse(org.json.JSONObject().put("format_id","v").put("url","https://example.com/v.mp4").put("ext","mp4").put("width",1280).put("height",720).put("vcodec","h264").put("acodec","none").put("filesize",100))
        val audio=Format.parse(org.json.JSONObject().put("format_id","a").put("url","https://example.com/a.m4a").put("ext","m4a").put("vcodec","none").put("acodec","aac").put("filesize",50))
        assertEquals(MediaSize(150,false),Selection(video,audio).transferSize)
    }
}
