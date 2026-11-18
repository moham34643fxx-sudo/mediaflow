package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VerifiedQualityTest {
    @Test fun labelDerivedDimensionsCannotSatisfyCapsButDoNotReclassifyAudioSources() {
        val legacy=Format.parse(JSONObject().put("format_id","progressive_hd_src").put("url","https://example.com/v.mp4")
            .put("ext","mp4").put("height",720).put("acodec","aac").put("mediaflow_dimensions","unverified_label"))
        val source=media(legacy)
        assertTrue(legacy.video);assertTrue(legacy.audio);assertFalse(legacy.streamOnly)
        assertFalse(legacy.dimensionsVerified);assertNull(verifiedQualityEdge(legacy))
        assertNull(VideoPreferenceSelection.ready(source,720))
        assertEquals("m4a",Selection.audio(source,"original").output)
        assertFalse(Selection.parse(Selection.video(source,legacy).historyJson()).primary.dimensionsVerified)
    }
    private fun format(w:Int?=null,h:Int?=null,extra:(JSONObject)->Unit={}):Format {
        val j=JSONObject().put("format_id","1080p-id").put("url","https://example.com/v.mp4")
            .put("ext","mp4").put("vcodec","h264").put("acodec","none").put("width",w).put("height",h)
        extra(j)
        return Format.parse(j)
    }
    private fun media(vararg formats:Format)=Media("https://example.com/v","id","t","source",null,null,null,formats.toList())
    @Test fun dimensionsWinOverIdsNotesBitratesAndThumbnails() {
        val f=format(1280,720) {it.put("format_note","1080p").put("resolution","1920x1080").put("tbr",1152).put("thumbnail",JSONObject().put("width",3840).put("height",2160))}
        assertEquals(720,qualityLabel(f));assertEquals(1280,f.width);assertEquals(720,f.height)
        assertNull(qualityLabel(format {it.put("format_note","4K").put("tbr",1080)}))
        assertNull(qualityLabel(format(1920,null)))
    }
    @Test fun portraitNonstandardAndHigherResolutionsAreHonestAndNeverRoundedUp() {
        assertEquals(720,qualityLabel(format(720,1280)))
        val unusual=format(1152,2048)
        assertEquals(1152,verifiedQualityEdge(unusual));assertEquals(1080,qualityCategory(unusual));assertEquals(1152,qualityLabel(unusual));assertEquals(1152,unusual.width)
        assertEquals(720,qualityCategory(format(1920,800)))
        assertEquals("2K",qualityCategoryName(qualityLabel(format(2560,1440))!!))
        assertEquals("4K",qualityCategoryName(qualityLabel(format(3840,2160))!!))
        assertEquals("8K",qualityCategoryName(qualityLabel(format(7680,4320))!!))
    }
    @Test fun malformedOrNonVideoGeometryNeverInventsQuality() {
        assertNull(qualityLabel(format {it.put("height",720.5)}))
        assertNull(qualityLabel(format {it.put("height",1e30).put("resolution","1920x1080")}))
        assertNull(qualityLabel(format {it.put("resolution","fake 1920x1080 thumbnail")}))
        assertEquals(720,qualityLabel(format {it.put("resolution","1280x720")}))
        assertFalse(format(1920,1080) {it.put("vcodec","none").put("acodec","aac")}.video)
        assertFalse(format(1920,1080) {it.put("ext","jpg")}.video)
        assertFalse("Unknown video metadata must not invent an audio track",format {it.remove("vcodec");it.remove("acodec")}.audio)
    }
    @Test fun capPreferencesDoNotInventOrSilentlySelectHigherFormats() {
        val only720=media(format(1280,720))
        assertEquals(720,QuickShareFormats.preferenceChoice(only720,1080)?.resolve(only720)?.primary?.height)
        assertFalse(QuickShareFormats.hasExactPreference(only720,1080))
        assertNull(QuickShareFormats.preferenceChoice(only720,480))
        assertEquals(720,QuickShareFormats.preferenceChoice(only720,0)?.resolve(only720)?.primary?.height)
        assertNull(QuickShareFormats.preferenceChoice(media(format(1152,2048)),1080))
    }
    @Test fun equivalentRowsCollapseWhileHdrFpsCodecAndContainerRemainSelectable() {
        val f=format(1280,720)
        val m=media(f,f.copy(id="copy"),f.copy(id="60",fps=60.0),f.copy(id="hdr",dynamicRange="HDR10"),f.copy(id="hevc",videoCodec="hevc"),f.copy(id="mkv",ext="mkv"))
        assertEquals(1,m.qualityGroups().size)
        assertEquals(5,m.qualityGroups().single().variantChoices.size)
    }
}
