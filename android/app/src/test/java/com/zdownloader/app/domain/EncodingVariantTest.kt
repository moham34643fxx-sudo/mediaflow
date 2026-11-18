package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EncodingVariantTest {
    private fun source(id:String,rate:Double,edge:Int=720)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id.mp4").put("ext","mp4").put("width",edge).put("height",edge*16/9)
        .put("vcodec","av01").put("acodec","none").put("tbr",rate).put("fps",30))
    private fun media(vararg rows:Format)=Media("https://example.com/watch","fixture","Video","Facebook",null,null,99.287,rows.toList())
    @Test fun sevenFacebookBitratesRemainSelectableWithinTheSameReal720Group() {
        val rows=listOf(268.329,409.282,594.065,724.799,907.669,1102.733,1355.521).mapIndexed {index,rate->source("v$index",rate)}
        val media=media(*rows.toTypedArray());val group=media.qualityGroups().single()
        assertEquals(720,group.resolution);assertEquals(7,group.variantChoices.size)
        assertEquals(rows.map {it.id}.toSet(),group.variantChoices.map {it.id}.toSet())
        assertEquals("Video list presents exactly one best row per resolution",1,
            QuickShareCatalog.allVideoSources(media).size)
        for(row in group.variantChoices) assertEquals(row,QuickShareChoice(false,row.id).resolve(media)!!.primary)
    }
    @Test fun genuineDuplicateMetadataDoesNotCreateExtraSizeChoices() {
        val first=source("first",724.799)
        assertEquals(1,media(first,first.copy(id="mirror")).qualityGroups().single().variantChoices.size)
    }
    @Test fun aLowerCompressionRateCannotTurnA720PixelFileIntoA480PixelChoice() {
        val media=media(source("small720",268.329),source("large720",1355.521),source("real360",400.0,360))
        assertEquals("real360",DownloadRequest(height=480).resolve(media).primary.id)
        assertEquals(720,verifiedQualityEdge(QuickShareChoice(false,"small720").resolve(media)!!.primary))
    }
    @Test fun theFullCatalogueRetainsEverySupportedIdEvenWithIdenticalEncodingMetadata() {
        val first=source("first",724.799)
        val rows=listOf(first,first.copy(id="second"),first.copy(id="third"))
        val media=media(*rows.toTypedArray())
        assertEquals(3,media.formats.size)
        assertEquals(1,QuickShareCatalog.videoEntries(media).size)
        assertEquals(1,QuickShareCatalog.allVideoSources(media).size)
    }
    @Test fun sizeOnlyAndSubKilobitDifferencesAreNotDroppedFromMoreFormats() {
        val first=source("first",724.1)
        val rows=listOf(first,first.copy(id="near-rate",bitrate=724.9),first.copy(id="size",bytes=99_000),
            first.copy(id="missing-rate",bitrate=null))
        val media=media(*rows.toTypedArray())
        assertEquals(4,media.formats.size)
        assertEquals(1,QuickShareCatalog.allVideoSources(media).size)
    }
    @Test fun aSourceLabelSurvivesParsingAndSatisfiesRepresentationCap() {
        val labelled=Format.parse(JSONObject(source("tier480",724.799,480).payload).put("mediaflow_source_quality_label","480p"))
        assertEquals("480p",labelled.sourceQualityLabel)
        assertEquals(480,verifiedQualityEdge(labelled))
        val ready = VideoPreferenceSelection.ready(media(labelled),480)
        assertNotNull(ready)
        assertEquals("tier480",ready!!.primary.id)
        val selected=QuickShareChoice(false,labelled.id).resolve(media(labelled))!!
        assertEquals("480p",Selection.parse(selected.json()).primary.sourceQualityLabel)
    }
}
