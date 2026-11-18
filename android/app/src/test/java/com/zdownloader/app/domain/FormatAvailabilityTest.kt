package com.zdownloader.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormatAvailabilityTest {
    private fun format(id:String="v",height:Int=1080,protocol:String="https",audio:Boolean=false,url:String="https://example.com/$id"):JSONObject =
        JSONObject().put("format_id",id).put("url",url).put("ext",if(audio) "m4a" else "mp4")
            .put("height",if(audio) null else height).put("width",if(audio) null else height*16/9)
            .put("vcodec",if(audio) "none" else "avc1").put("acodec",if(audio) "aac" else "none").put("protocol",protocol)
    private fun media(vararg rows:JSONObject)=Media.parse(JSONObject().put("formats",JSONArray(rows.toList())),"https://example.com/page")
    @Test fun missing720And1080NeverCreatePresets() {
        assertEquals(listOf(480),media(format(height=480)).qualityGroups().map {it.resolution})
        assertEquals(listOf(1080),media(format()).qualityGroups().map {it.resolution})
        assertEquals(listOf(720),media(format(height=720)).qualityGroups().map {it.resolution})
    }
    @Test fun separate1080AndAudioCreateAnExecutableMergePlan() {
        val m=media(format(),format("a",audio=true));val s=Selection.video(m,m.qualityGroups().single().recommended)
        assertEquals("a",s.secondary!!.id);assertEquals("mp4",s.output);assertEquals(ProcessingPlan.MERGE_COPY,s.plan)
        assertEquals(1080,qualityLabel(s.primary));assertEquals(s,QuickShareChoice(false,"v").resolve(m))
    }
    @Test fun equivalentResolutionsAreOneRowAndOneVariant() {
        val m=media(format("first"),format("second"));val g=m.qualityGroups().single()
        assertEquals(1,g.variantChoices.size);assertEquals(1080,g.resolution)
    }
    @Test fun directHlsAndDashRemainAvailableWithTheirRealTransportPlans() {
        for(protocol in listOf("https","m3u8_native","http_dash_segments")) {
            val f=format(protocol=protocol)
            if(protocol=="http_dash_segments") f.put("fragments",JSONArray().put(JSONObject().put("url","https://example.com/segment")))
            val m=media(f,format("a",audio=true))
            assertNotNull(QuickShareChoice(false,"v").resolve(m))
        }
    }
    @Test fun invalidIdsAndNonExecutableDashAreNotSelectable() {
        val m=media(format(""),format("broken",protocol="http_dash_segments"),format("valid",height=480))
        assertEquals(listOf("valid"),m.formats.map {it.id})
        assertNull(QuickShareChoice(false,"broken").resolve(m))
    }
    @Test fun conflictingDuplicateIdsAreNotAmbiguousSelections() {
        val m=media(format("collision"),format("collision",height=720),format("valid",height=480))
        assertEquals(listOf("valid"),m.formats.map {it.id})
    }
    @Test fun expiredMediaUrlIsNotAValidChoiceAndFreshCacheDoesNotOverrideExpiry() {
        val expired=format(url="https://example.com/v?expire=1000000000")
        val m=media(expired,format("ok",height=480))
        assertEquals(listOf(480),m.qualityGroups().map {it.resolution})
        val fresh=media(format())
        assertTrue(FormatAvailability.reusable(fresh,100))
        assertFalse(FormatAvailability.reusable(fresh,45_000))
        val nearExpiry=media(format(url="https://example.com/v?expire=${System.currentTimeMillis()/1000+10}"))
        assertFalse(FormatAvailability.reusable(nearExpiry,0))
    }
    @Test fun refreshNeverSubstitutesALowerResolution() {
        val selected=Format.parse(format());val lower=Format.parse(format(height=720))
        assertFalse(lower.preservesSelection(selected))
    }
    @Test fun unknownSizeDoesNotMeanUnavailable() {
        val m=media(format(),format("a",audio=true));val selection=QuickShareChoice(false,"v").resolve(m)
        assertNotNull(selection);assertNull(selection!!.outputSize)
    }
}
