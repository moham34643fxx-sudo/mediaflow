package com.zdownloader.app.ui

import com.zdownloader.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QualityRowLabelsTest {
    @Test fun screenshotLowAndStandardQualitiesUseFastNamesWithTheMatchingDescriptionBand() {
        for(edge in listOf(144,240,270)) assertEquals(QualityRowLabels.Band.LOW,QualityRowLabels.band(edge))
        for(edge in listOf(360,480,540,576,640)) assertEquals(QualityRowLabels.Band.STANDARD,QualityRowLabels.band(edge))
        assertEquals(QualityRowLabels.titleResource(240),QualityRowLabels.titleResource(640))
        assertNotEquals(QualityRowLabels.descriptionResource(240),QualityRowLabels.descriptionResource(640))
    }
    @Test fun highQualityNamesCover720And1080WithoutRenamingTheirNumericLabels() {
        assertEquals(QualityRowLabels.Band.HIGH,QualityRowLabels.band(720))
        assertEquals(QualityRowLabels.Band.FULL_HD,QualityRowLabels.band(1080))
        assertEquals(QualityRowLabels.titleResource(720),QualityRowLabels.titleResource(1080))
        assertNotEquals(QualityRowLabels.descriptionResource(720),QualityRowLabels.descriptionResource(1080))
        assertEquals("\u2066720p\u2069",QualityRowLabels.isolatedLabel("720p"))
    }
    @Test fun missingQualityNeverCreatesAnInventedNumberOrDescription() {
        assertEquals(QualityRowLabels.Band.UNKNOWN,QualityRowLabels.band(null))
        assertNull(QualityRowLabels.descriptionResource(null))
        assertNull(QualityRowLabels.descriptionResource(0))
    }
    @Test fun sourceLabelWordingResolvesRepresentationTierWhilePreservingContainerDimensions() {
        val format=Format.parse(JSONObject().put("format_id","source270").put("url","https://example.com/video.mp4")
            .put("ext","mp4").put("width",270).put("height",480).put("vcodec","av01").put("acodec","none")
            .put("mediaflow_source_quality_label","270p"))
        assertEquals(QualityRowLabels.Band.LOW,QualityRowLabels.band(QuickShareCatalog.numericVideoQuality(format)))
        assertEquals(270,verifiedQualityEdge(format))
        assertEquals("270p",QuickShareCatalog.videoDisplayLabel(format))
        val media=Media("https://example.com/watch","fixture","Fixture","Generic",null,null,10.0,listOf(format))
        assertEquals("source270",QuickShareChoice(false,"source270").resolve(media)!!.primary.id)
        assertEquals("source270",VideoPreferenceSelection.ready(media,480)?.primary?.id)
        assertNotNull(VideoPreferenceSelection.ready(media,0))
    }
}
