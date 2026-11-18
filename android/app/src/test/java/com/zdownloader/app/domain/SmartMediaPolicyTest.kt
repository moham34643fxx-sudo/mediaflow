package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartMediaPolicyTest {
    @Test fun trackingAliasesNormalizeOnlyRecognizedUnsignedPages() {
        assertEquals("https://www.youtube.com/watch?v=abc",MediaIdentity.url("https://WWW.YouTube.com:443/watch?v=abc&utm_source=test&si=share#t=1"))
        assertEquals(MediaIdentity.urlKey("https://youtu.be/abc?si=one"),MediaIdentity.urlKey("https://youtu.be/abc?si=two"))
        assertNotEquals(MediaIdentity.urlKey("https://example.com/video?id=1"),MediaIdentity.urlKey("https://example.com/video?id=2"))
    }
    @Test fun signedAndDirectResourceQueriesArePreservedByteForByte() {
        for(url in listOf("https://cdn.example.com/v.mp4?token=x&utm_source=a&x=%2f%2B","https://www.youtube.com/v?sig=x&utm_source=a","https://example.com/page?utm_source=identity","https://www.youtube.com/videoplayback?expire=42&lsig=x&si=identity&utm_source=a","https://www.youtube.com/watch?v=x&lsig=y&si=identity")) assertEquals(url,MediaIdentity.url(url))
    }
    @Test fun sourceIdentityIgnoresTitleAndKeepsProvidersSeparate() {
        val a=Media("https://example.com/a","123","One","Youtube",null,null,null,emptyList())
        assertEquals(MediaIdentity.sourceKey(a),MediaIdentity.sourceKey(a.copy(title="Renamed",url="https://youtu.be/123")))
        assertNotEquals(MediaIdentity.sourceKey(a),MediaIdentity.sourceKey(a.copy(source="Vimeo")))
        assertNotEquals(MediaIdentity.sourceKey(a),MediaIdentity.sourceKey(a.copy(id="456")))
    }
    @Test fun schedulingIsNeverExactAndAllSelectedConditionsMustHold() {
        val plan=DownloadPlan(1000,DownloadNetwork.WIFI,true)
        assertFalse(plan.eligible(999,true,true,true));assertFalse(plan.eligible(1000,false,true,true));assertFalse(plan.eligible(1000,true,true,false))
        assertTrue(plan.eligible(1000,true,false,true)) // Metered Wi-Fi is still Wi-Fi.
        assertFalse(DownloadPlan(0,DownloadNetwork.UNMETERED).eligible(1,true,false,true))
        assertTrue(DownloadPlan(0,DownloadNetwork.UNMETERED).eligible(1,false,true,false))
    }
    @Test fun cleanupProtectsEveryRecoverableStateAndNativeLease() {
        for(state in DownloadStatus.entries.map {it.name}+"SCHEDULED") {
            if(state !in setOf("COMPLETED","CANCELLED")) assertFalse(state,CleanupPolicy.eligible(state,false,false,Long.MAX_VALUE))
        }
        assertFalse(CleanupPolicy.eligible("COMPLETED",true,false,Long.MAX_VALUE))
        assertFalse(CleanupPolicy.eligible("CANCELLED",false,true,Long.MAX_VALUE))
        assertFalse(CleanupPolicy.eligible(null,false,false,24*60*60_000L-1))
        assertTrue(CleanupPolicy.eligible(null,false,false,24*60*60_000L))
        assertTrue(CleanupPolicy.eligible("COMPLETED",false,false,0))
    }
    @Test fun advancedSelectionsPersistAndRepresentationsRemainDistinct() {
        val payload=JSONObject().put("format_id","v").put("ext","mp4").put("url","https://example.com/media.mp4").put("vcodec","h264").put("acodec","aac").toString()
        val base=Selection(Format.parse(JSONObject(payload)))
        val track=SubtitleTrack("ar","vtt",JSONObject().put("url","https://example.com/sub.vtt").put("format_id","sub").put("ext","vtt").toString())
        val advanced=base.copy(videoOnly=true,subtitle=track,embedMetadata=false,creator="Fixture")
        assertEquals(advanced,Selection.parse(advanced.json()))
        assertFalse(advanced.transcodeAudio);assertNull(advanced.outputSize)
        val media=Media("https://example.com/video","1","Fixture","Youtube",null,null,null,listOf(base.primary))
        assertNotEquals(RequestFingerprint.create(media,base),RequestFingerprint.create(media,advanced))
        assertFalse(advanced.historyJson().contains("https://example.com/sub.vtt"))
    }
    @Test fun invalidSubtitlesCannotAddLocalAccessOrUnsupportedConversions() {
        for(url in listOf("file:///etc/passwd","content://private/file","http://example.com/s.vtt","https://127.0.0.1/s.vtt")) {
            try {SubtitleTrack.parse(JSONObject().put("language","en").put("extension","vtt").put("payload",JSONObject().put("url",url)));fail(url)} catch(_:Exception) {}
        }
        try {SubtitleTrack("en","exe","{}");fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun manualAndQuickShareDefaultsUseTheSameRepresentationAndCreatorMetadata() {
        val format=Format.parse(JSONObject().put("format_id","v").put("url","https://example.com/media.mp4").put("ext","mp4").put("height",720).put("vcodec","h264").put("acodec","aac"))
        val media=Media("https://example.com/watch","video-id","Fixture","Youtube","Actual creator",null,null,listOf(format))
        val manual=Selection.video(media,format)
        val shared=QuickShareChoice(false,format.id).resolve(media)!!
        assertEquals("Actual creator",manual.creator)
        assertEquals(RequestFingerprint.create(media,manual),RequestFingerprint.create(media,shared))
        assertEquals(Selection.audio(media,"original"),QuickShareChoice(true,format.id,"original").resolve(media))
    }
}
