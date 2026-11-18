package com.zdownloader.app.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AacCopyTimingPolicyTest {
    private fun stream()=JSONObject().put("codec_name","aac").put("sample_rate","48000")
        .put("time_base","1/48000").put("start_pts",0).put("duration_ts",42626560)

    @Test fun copiedAudioMustRetainTheExactDeclaredEnd() {
        val timing=requireNotNull(AacCopyTiming.fromStream(stream()))
        assertTrue(timing.matches(stream()))
        assertFalse(timing.matches(stream().put("duration_ts",42627072)))
        assertFalse(timing.matches(stream().put("duration_ts",42626559)))
        assertFalse(timing.matches(stream().put("time_base","1/44100")))
        assertFalse(timing.matches(stream().put("sample_rate",44100)))
        assertFalse(timing.matches(stream().put("start_pts",1)))
    }

    @Test fun unprovenOrUnsupportedSourceTimelinesDoNotEnableDurationCorrection() {
        val cases=listOf(stream().put("codec_name","opus"),stream().put("start_pts",1024),
            stream().put("duration_ts",0),stream().put("duration_ts",-1),stream().put("duration_ts","NaN"),
            stream().put("duration_ts","1.5"),stream().put("duration_ts","9007199254740992"),
            stream().put("time_base","1/1000"),stream().put("sample_rate",0),
            stream().apply {remove("duration_ts")},stream().apply {remove("start_pts")})
        for(source in cases) assertNull("Unsupported timeline: $source",AacCopyTiming.fromStream(source))
    }
}
