package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class QuickShareCatalogTest {
    private fun video(id:String,width:Int?,height:Int?)=Format(id,"mp4",height,30.0,"h264","aac",1000,1000.0,"https","{\"format_id\":\"$id\",\"url\":\"https://example.com/$id.mp4\"}",width=width)
    private fun media(vararg formats:Format)=Media("https://example.com/watch","id","Title","Source",null,null,10.0,formats.toList())
    @Test fun compactPreferencesNeverDisappearWhenA480SourceIsMissing() {
        val catalog=media(video("1080",1080,1920),video("720",720,1280))
        assertEquals(listOf(360,480,720),QuickShareCatalog.compactPreferences)
        assertEquals(listOf(720,1080),QuickShareCatalog.verifiedGroups(catalog).map {it.resolution})
        assertEquals(listOf(360,480,720),QuickShareCatalog.compactPreferences)
        assertTrue(QuickShareCatalog.verifiedGroups(null).isEmpty())
    }
    @Test fun compactRealRowsStaySmallAndKeepTheSelectedQualityVisible() {
        val groups=QuickShareCatalog.verifiedGroups(media(*listOf(144,240,360,480,540,720,1080,1440,2160).map {video(it.toString(),it*16/9,it)}.toTypedArray()))
        assertEquals(listOf(360,480,720),QuickShareCatalog.compactGroups(groups,null).map {it.resolution})
        assertEquals(listOf(360,480,720),QuickShareCatalog.compactGroups(groups,"2160").map {it.resolution})
    }
    @Test fun unratedSdAndHdNeverAppearAsInventedNumberedQualities() {
        val sd=video("sd",null,null);val hd=video("hd",null,null)
        val source=media(sd,video("1080",1080,1920),hd,video("720",720,1280))
        assertEquals(listOf(720,1080),QuickShareCatalog.verifiedGroups(source).map {it.resolution})
        assertEquals(setOf("sd","hd"),QuickShareCatalog.unratedSources(source).map {it.id}.toSet())
        assertTrue(QuickShareCatalog.unratedSources(source).all {verifiedQualityEdge(it)==null})
    }
    @Test fun variantsStayInOneOrderedQualityRowAndOriginalSelectionRemainsResolvable() {
        val a=video("720-h264",720,1280)
        val b=video("720-av1",720,1280).copy(videoCodec="av1",fps=60.0)
        val source=media(video("1080",1080,1920),a,b)
        val groups=QuickShareCatalog.verifiedGroups(source)
        assertEquals(listOf(720,1080),groups.map {it.resolution})
        assertEquals(setOf(a.id,b.id),groups.first().candidates.map {it.id}.toSet())
        assertEquals(a.id,QuickShareChoice(false,a.id).resolve(source)!!.primary.id)
    }
    @Test fun fullSourceRowsSortBySourceLabelAndRetainCompressionVariants() {
        val real360=video("real360",360,640)
        val low480=video("low480",480,854).copy(sourceQualityLabel="480p",videoBitrate=300.0,bytes=3_100_000)
        val high480=low480.copy(id="high480",videoBitrate=540.0,bytes=3_600_000)
        val source540=low480.copy(id="source540",width=540,height=960,sourceQualityLabel="540p",videoBitrate=410.0)
        val source720=low480.copy(id="source720",width=720,height=1280,sourceQualityLabel="720p",videoBitrate=720.0)
        val source=media(source720,high480,real360,low480,source540,low480)

        val rows=QuickShareCatalog.videoEntries(source).map {it.format}
        assertEquals(listOf("real360","high480","source540","source720"),rows.map {it.id})
        assertEquals(listOf(360,480,540,720),rows.map {verifiedQualityEdge(it)})

        assertEquals("All raw candidates remain retained in media.formats",6,source.formats.size)

        val displayed=QuickShareCatalog.allVideoSources(source)
        assertEquals(listOf("real360","high480","source540","source720"),displayed.map {it.id})
        assertEquals(listOf(360,480,540,720),displayed.map {verifiedQualityEdge(it)})
        assertEquals(listOf(360,480,540,720),displayed.map {QuickShareCatalog.numericVideoQuality(it)})
    }
    @Test fun invalidOrMissingSourceLabelsFallBackToVerifiedPixelsWithoutInventingLabels() {
        val actual=video("actual",720,1280)
        val unknown=video("unknown",null,null).copy(sourceQualityLabel="720p?" )
        assertNull(QuickShareCatalog.sourceQualityLabel(actual))
        assertNull(QuickShareCatalog.sourceQualityLabel(unknown))
        assertEquals(720,verifiedQualityEdge(actual))
        assertNull(verifiedQualityEdge(unknown))
    }
    @Test fun everyCatalogRowResolvesAndSerializesTheSameDownloadFormatId() {
        val formats=listOf(
            video("source480",480,854).copy(sourceQualityLabel="480p",videoBitrate=300.0),
            video("source540",540,960).copy(sourceQualityLabel="540p",videoBitrate=400.0),
            video("actual720",720,1280))
        val source=media(*formats.toTypedArray())
        for(format in QuickShareCatalog.videoEntries(source).map {it.format}) {
            val selected=QuickShareChoice(false,format.id).resolve(source)!!
            assertEquals(format.id,selected.primary.id)
            assertEquals(format.id,Selection.parse(selected.json()).primary.id)
        }
    }
    @Test fun numericQualityThenSizeOverridesCodecGeometryIdsAndExtractionOrder() {
        val rows=listOf(
            video("a1080",1080,1920).copy(sourceQualityLabel="1080p",bytes=1),
            video("z270",270,480).copy(sourceQualityLabel="270p",bytes=90_000),
            video("z360small",360,640).copy(sourceQualityLabel="360p",bytes=1000,videoCodec="av01",videoBitrate=9999.0),
            video("a360large",360,640).copy(bytes=2000,videoCodec="h264",videoBitrate=1.0),
            video("z480",480,854).copy(sourceQualityLabel="480p",bytes=10),
            video("z540",540,960).copy(sourceQualityLabel="540p",bytes=20),
            video("z576",576,1024).copy(bytes=30),
            video("z720",720,1280).copy(sourceQualityLabel="720p",bytes=40))
        val source=media(*rows.reversed().toTypedArray())
        val sorted=QuickShareCatalog.videoEntries(source).map {it.format}
        assertEquals(listOf(270,360,480,540,576,720,1080),sorted.map {QuickShareCatalog.numericVideoQuality(it)})
        assertEquals(1,sorted.filter {QuickShareCatalog.numericVideoQuality(it)==360}.size)
        assertEquals(listOf("z480"),sorted.filter {QuickShareCatalog.numericVideoQuality(it)==480}.map {it.id})
        assertEquals(listOf("z720"),sorted.filter {QuickShareCatalog.numericVideoQuality(it)==720}.map {it.id})
        for(row in sorted) assertEquals(row.id,QuickShareChoice(false,row.id).resolve(source)!!.primary.id)
    }
    @Test fun equalQualitySizesUseTheDisplayedTransferTotalAndUnknownSizesComeLast() {
        val small=video("small",720,1280).copy(bytes=1000)
        val large=small.copy(id="large",bytes=2000,videoCodec="av01")
        val unknown=small.copy(id="unknown",bytes=null,bitrate=null)
        val source=media(unknown,large,small)
        assertEquals(1,QuickShareCatalog.videoEntries(source).size)
        val best=QuickShareCatalog.videoEntries(source).first().format
        assertEquals("720p",QuickShareCatalog.videoDisplayLabel(best))
        assertNull(QuickShareCatalog.videoDisplayLabel(video("no-label",null,null)))
    }
    @Test fun sourceQualityLabelDistinguishesRepresentationTierWhilePreservingContainerDimensions() {
        val labelled=video("tier270",270,480).copy(sourceQualityLabel="270p")
        assertEquals(270,QuickShareCatalog.numericVideoQuality(labelled))
        assertEquals("270p",QuickShareCatalog.videoDisplayLabel(labelled))
        assertEquals(270,verifiedQualityEdge(labelled))
    }
    @Test fun unverifiedDimensionsFallBackToSourceQualityLabel() {
        val unverified=Format.parse(org.json.JSONObject().put("format_id","tier270").put("url","https://example.com/video.mp4")
            .put("ext","mp4").put("mediaflow_dimensions","unverified_label").put("mediaflow_source_quality_label","270p"))
        assertNull(verifiedQualityEdge(unverified))
        assertEquals(270,QuickShareCatalog.numericVideoQuality(unverified))
        assertEquals("270p",QuickShareCatalog.videoDisplayLabel(unverified))
    }
    @Test fun sizeOrderingIncludesSeparateAudioJustLikeTheDisplayedRows() {
        val muxed=video("muxed",360,640).copy(bytes=1000)
        val separate=video("video-only",720,1280).copy(sourceQualityLabel="720p",bytes=400,audioCodec="none",videoCodec="av01")
        val audio=Format.parse(org.json.JSONObject().put("format_id","audio").put("url","https://example.com/audio.m4a")
            .put("ext","m4a").put("vcodec","none").put("acodec","mp4a").put("filesize",1000))
        val source=media(separate,audio,muxed)
        assertEquals(1400L,QuickShareCatalog.videoDisplaySize(source,separate)!!.bytes)
        assertEquals(listOf("muxed","video-only"),QuickShareCatalog.allVideoSources(source).map {it.id})
    }
    @Test fun focusedRealModelFixtureRetainsBasicCompactAndAscendingAllVideoSources() {
        val v144=video("v144",256,144)
        val v240=video("v240",426,240)
        val v360=video("v360",640,360)
        val v480=video("v480",854,480)
        val v720h264=video("v720-h264",1280,720)
        val v720av1=video("v720-av1",1280,720).copy(videoCodec="av01")
        val v1080=video("v1080",1920,1080)
        val source=media(v1080,v720av1,v720h264,v480,v360,v240,v144)

        val groups=QuickShareCatalog.verifiedGroups(source)
        // Compact edges strictly 360/480/720 ascending
        assertEquals(listOf(360,480,720),QuickShareCatalog.compactGroups(groups,null).map {it.resolution})
        // Selected 1080 is not injected into compact choices
        assertEquals(listOf(360,480,720),QuickShareCatalog.compactGroups(groups,"v1080").map {it.resolution})

        // Exactly one entry per actual video resolution ascending: 144, 240, 360, 480, 720, 1080
        val displayed=QuickShareCatalog.allVideoSources(source)
        assertEquals(listOf(144,240,360,480,720,1080),displayed.map {verifiedQualityEdge(it)})
        assertEquals(6,displayed.size)

        // Exactly one entry per resolution in videoEntries
        val raw=QuickShareCatalog.videoEntries(source)
        assertEquals(6,raw.size)
        // All raw formats retained in source.formats
        assertEquals(7,source.formats.size)

        // Selected 1080 remains resolvable and not silently changed
        val choice1080=QuickShareChoice(false,"v1080")
        val resolved1080=choice1080.resolve(source)
        assertNotNull(resolved1080)
        assertEquals("v1080",resolved1080!!.primary.id)
        assertEquals(1080,resolved1080.primary.height)
    }
    @Test fun noBasicMember176StillAccessibleViaFullListWithoutInventingBasicChoices() {
        val clip176=media(video("v176",220,176))
        val groups176=QuickShareCatalog.verifiedGroups(clip176)
        // Compact choices are empty when no 360/480/720 basic member exists; never invent 480/720 or expose 176 as basic
        assertTrue(QuickShareCatalog.compactGroups(groups176,null).isEmpty())
        assertTrue(QuickShareCatalog.compactGroups(groups176,"v176").isEmpty())

        // Full list retains 176
        val full=QuickShareCatalog.allVideoSources(clip176)
        assertEquals(listOf("v176"),full.map {it.id})
        assertEquals(listOf(176),full.map {verifiedQualityEdge(it)})
        assertNotNull(QuickShareChoice(false,"v176").resolve(clip176))
    }
    @Test fun preference480FallsBackTo360When480MissingEvenThough720Exists() {
        val source=media(video("v720",1280,720),video("v360",640,360))
        val choice=QuickShareFormats.preferenceChoice(source,480)
        assertNotNull(choice)
        assertEquals("v360",choice!!.formatId)
        assertNotEquals("v720",choice.formatId)
        val resolved=choice.resolve(source)
        assertNotNull(resolved)
        assertEquals(360,resolved!!.primary.height)
    }
}
