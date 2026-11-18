package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistPolicyTest {
    private fun format(id:String,w:Int?,h:Int?,audio:Boolean=true)=Format.parse(JSONObject().put("format_id",id).put("url","https://example.com/$id.mp4").put("width",w).put("height",h).put("ext","mp4").put("vcodec","h264").put("acodec",if(audio) "aac" else "none"))
    private fun media(vararg formats:Format)=Media("https://example.com/v","v","Test","Fixture",null,null,null,formats.toList())
    @Test fun atMostNeverExceedsCapOrFallsUpward() {
        val m=media(format("1080",1920,1080),format("480",854,480))
        assertEquals(480,qualityLabel(PlaylistPolicy(height=720).resolve(m).primary))
        try {PlaylistPolicy(height=360).resolve(m);fail("Must not substitute larger media")} catch(e:MediaException) {assertEquals(ErrorCode.QUALITY_UNAVAILABLE,e.code)}
    }
    @Test fun exactFailsIndependentlyAndBestIsExplicit() {
        val m=media(format("1080",1920,1080),format("480",854,480))
        try {PlaylistPolicy(height=720,fallback=QualityFallback.EXACT).resolve(m);fail("Exact mismatch")} catch(e:MediaException) {assertEquals(ErrorCode.FORMAT_UNAVAILABLE,e.code)}
        assertEquals(1080,qualityLabel(PlaylistPolicy(fallback=QualityFallback.BEST).resolve(m).primary))
    }
    @Test fun portraitAndUnknownSizeRemainReal() {
        val f=format("portrait",720,1280)
        val choice=PlaylistPolicy(height=720,fallback=QualityFallback.EXACT).resolve(media(f))
        assertEquals(1280,choice.primary.height);assertEquals(720,choice.primary.width);assertNull(choice.size)
    }
    @Test fun unknownDimensionsDoNotSatisfyAnExactOrCeilingPolicy() {
        try {PlaylistPolicy().resolve(media(format("unknown",null,null)));fail("Unknown is not 720p")} catch(_:MediaException) {}
    }
    @Test fun policyRoundTripAndMp3Bitrate() {
        val p=PlaylistPolicy(true,"mp3",720,QualityFallback.AT_MOST,320)
        assertEquals(p,PlaylistPolicy.parse(p.json()))
        assertEquals(320,p.resolve(media(format("audio",1920,1080))).bitrate)
    }
    @Test fun ambiguousContextRequiresExplicitScope() {
        assertTrue(PlaylistUrls.ambiguous("https://www.youtube.com/watch?v=abc&list=xyz"))
        assertTrue(PlaylistUrls.ambiguous("https://youtu.be/abc?list=xyz"))
        assertFalse(PlaylistUrls.ambiguous("https://www.youtube.com/playlist?list=xyz"))
        assertTrue(PlaylistUrls.candidate("https://soundcloud.com/artist/sets/album"))
    }
    @Test fun completedBatchHistoryRetainsMediaPropertiesWithoutSessionOrFragmentPayloads() {
        val f=Format.parse(JSONObject(format("history",720,1280).payload).put("cookies","private-cookie").put("fragments",org.json.JSONArray().put(JSONObject().put("url","https://example.com/secret-segment"))).put("http_headers",JSONObject().put("Authorization","secret")))
        val s=Selection(f,duration=10.0)
        val history=s.historyJson()
        assertFalse(history.contains("secret"));assertFalse(history.contains("cookies"));assertFalse(history.contains("fragments"));assertFalse(history.contains("https://"))
        val restored=Selection.parse(history)
        assertEquals(720,restored.primary.width);assertEquals(1280,restored.primary.height);assertEquals("mp4",restored.output)
    }
    @Test fun adaptiveBoundsHysteresisAndServerPenalty() {
        val a=AdaptiveConcurrency()
        assertEquals(2,a.choose(1000,true,false,0,1_000_000))
        assertEquals(3,a.choose(31_000,true,false,0,1_000_000))
        a.failure(ErrorCode.RATE_LIMITED,32_000)
        assertEquals(1,a.choose(80_000,true,false,0,9_000_000))
        assertEquals(1,a.choose(200_000,true,true,0,9_000_000))
        assertTrue(a.choose(240_000,false,false,0,9_000_000) in 1..2)
    }
}
