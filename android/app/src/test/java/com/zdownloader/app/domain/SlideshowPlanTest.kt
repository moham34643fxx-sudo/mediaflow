package com.zdownloader.app.domain

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SlideshowPlanTest {
    private fun payload():JSONObject {
        val plan=JSONObject().put("version",1).put("post_id","123").put("timing","equal_generated")
            .put("width",720).put("height",960).put("duration",35)
            .put("images",JSONArray().put(JSONObject().put("url","https://cdn.example.com/image.jpg").put("width",1080).put("height",1440)))
        return JSONObject().put("format_id","mediaflow-slideshow").put("url","https://cdn.example.com/image.jpg")
            .put("ext","mp4").put("protocol",SlideshowPlan.PROTOCOL).put("vcodec","h264").put("acodec","none")
            .put("width",720).put("height",960).put("fps",30).put("mediaflow_slideshow",plan)
    }
    @Test fun generatedVideoHasNoFakeNetworkSize() {
        val f=Format.parse(payload());assertTrue(FormatAvailability.eligible(f));assertTrue(f.video);assertFalse(f.audio);assertNull(f.size(35.0))
        assertEquals(720,SlideshowPlan.parse(f.payload).width)
    }
    @Test fun invalidOrUnsafeRecipeCannotEnterTheQueue() {
        for(url in listOf("file:///private/image.jpg","http://127.0.0.1/a","https://127.0.0.1/a","https://user:pass@example.com/a")) {
            val p=payload();p.getJSONObject("mediaflow_slideshow").getJSONArray("images").getJSONObject(0).put("url",url)
            assertFalse(url,FormatAvailability.eligible(Format.parse(p)))
        }
    }
    @Test fun normalVideoAndImageFilteringRemainUnchanged() {
        val p=payload().put("protocol","https");p.remove("mediaflow_slideshow")
        assertTrue(FormatAvailability.eligible(Format.parse(p)))
        p.put("ext","jpg").put("vcodec","images");assertFalse(FormatAvailability.eligible(Format.parse(p)))
    }
    @Test fun browserAcceptsBothTikTokPostRoutesButNotLookalikeDomains() {
        for(route in listOf("photo","video")) assertTrue(BrowserMediaLinks.matches("https://www.tiktok.com/@fixture/$route/123?_r=1"))
        assertFalse(BrowserMediaLinks.matches("https://tiktok.com.evil.example/@fixture/photo/123"))
    }
}
