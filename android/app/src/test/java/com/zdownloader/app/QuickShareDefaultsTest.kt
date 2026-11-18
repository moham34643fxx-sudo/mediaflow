package com.zdownloader.app

import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickShareDefaultsTest {
    private fun format(id:String,height:Int?=null)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id").put("ext",if(height==null) "m4a" else "mp4")
        .put("vcodec",if(height==null) "none" else "h264").put("acodec","aac")
        .put("height",height).put("width",height?.times(2)).put("mediaflow_dimensions",height!=null).put("abr",128))
    private val audio=format("audio")
    private val low=format("low",360)
    private val high=format("high",1080)
    private val media=Media("https://example.com/watch","defaults","Fixture","Test",null,null,60.0,listOf(audio,low,high))
    @Test fun fallbackUsesSavedTypeAndQualityWithoutCreatingExplicitChoice() {
        val state=QuickShareState(media=media,defaultHeight=480,defaultDownloadType="video")
        assertEquals("low",state.defaultChoice!!.formatId)
        assertNull(state.choice)
        assertNull(state.selection)
        assertTrue(state.copy(defaultDownloadType="audio").defaultChoice!!.audio)
        assertNull(state.copy(defaultHeight=144).defaultChoice)
    }
    @Test fun explicitChoiceIsStableWhenPreferencesOrMetadataAreEnriched() {
        val choice=QuickShareChoice(false,"high")
        val original=QuickShareState(media=media,choice=choice,defaultHeight=480,defaultDownloadType="audio")
        val enriched=original.copy(media=media.copy(title="Enriched"),defaultHeight=360)
        assertEquals(choice,enriched.choice)
        assertEquals("high",enriched.selection!!.primary.id)
        assertFalse(enriched.selection!!.audioOnly)
    }
}
