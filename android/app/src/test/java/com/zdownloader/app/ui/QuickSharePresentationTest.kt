package com.zdownloader.app.ui

import com.zdownloader.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickSharePresentationTest {
    private fun source()=Media("https://example.com/watch","id","Fixture","Generic",null,null,10.0,
        listOf(270,360,480,720,1080).mapIndexed {index,quality->Format.parse(JSONObject()
            .put("format_id","v$index").put("url","https://example.com/v$index.mp4").put("ext","mp4")
            .put("width",quality * 16 / 9).put("height",quality).put("vcodec","av01").put("acodec","none")
            .put("filesize",1000+index*500).put("mediaflow_source_quality_label","${quality}p"))})
    @Test fun offThreadPresentationReusesTheOriginalSelectionContractAndAllIds():Unit=runBlocking {
        val media=source();val preview=QuickSharePresentation.prepare(media)
        assertEquals(QuickShareCatalog.allVideoSources(media).map {it.id},preview.videoSources.map {it.id})
        assertEquals(5,preview.videoSources.size)
        assertEquals(5,QuickShareCatalog.videoEntries(media).size)
        for(format in media.formats) {
            val choice=QuickShareChoice(false,format.id)
            val expected=choice.resolve(media)!!
            assertEquals(expected.json(),preview.selection(choice)!!.json())
            assertSame(preview.selection(choice),preview.selection(choice))
            val custom=QuickShareChoice(false,format.id,"mkv")
            assertEquals(custom.resolve(media)!!.json(),preview.selection(custom)!!.json())
        }
    }
    @Test fun optionalSizesCreateANewSnapshotWithoutMutatingThePreviousOne():Unit=runBlocking {
        val media=source();val first=QuickSharePresentation.prepare(media)
        val updated=media.copy(formats=media.formats.map {format->
            Format.parse(JSONObject(format.payload).put("filesize",99999))
        })
        val second=QuickSharePresentation.prepare(updated)
        val choice=QuickShareChoice(false,"v0")
        assertEquals(1000L,first.selection(choice)!!.primary.bytes)
        assertEquals(99999L,second.selection(choice)!!.primary.bytes)
        assertEquals("v0",second.selection(choice)!!.primary.id)
    }
    @Test fun displaySizesArePrimedOffThreadBeforePublishingThePresentation():Unit=runBlocking {
        val main=Thread.currentThread().id
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val cache=SizeLabelCache({value->
            assertNotEquals("Localized size formatting must leave the caller thread",main,Thread.currentThread().id)
            calls.incrementAndGet();"size:${value?.bytes}:${value?.estimated}"
        })
        val preview=QuickSharePresentation.prepare(source(),cache)
        val preparedCalls=calls.get()
        assertTrue(preparedCalls>0)
        for(format in preview.videoSources) {
            val selection=preview.selection(QuickShareChoice(false,format.id))!!
            cache.label(selection.transferSize);cache.label(selection.outputSize)
        }
        cache.label(null)
        assertEquals("Visible row reads must not recreate the localized formatter",preparedCalls,calls.get())
    }
    @Test fun preferenceSizesFollowTheResolverAndExposeTheActualFallbackEdge():Unit=runBlocking {
        val actual360=source().formats.first().copy(id="real360",width=640,height=360,videoCodec="h264",audioCodec="aac",bytes=12_345,sourceQualityLabel="360p")
        val media=source().copy(formats=listOf(actual360))
        val preview=QuickSharePresentation.prepare(media)
        // A stream-copy output remains an estimate because remuxing changes overhead.
        assertEquals(MediaSize(12_345,true),preview.preferenceSizes[360])
        assertNull(preview.preferenceSizes[480])
        assertNull(preview.preferenceSizes[720])
        assertEquals(360,preview.preferenceQualities[480])
        assertEquals(360,preview.preferenceQualities[720])
        assertEquals("real360",VideoPreferenceSelection.ready(media,720)?.primary?.id)
        assertFalse(VideoPreferenceSelection.request(720).exact)
        assertEquals(MediaSize(240_000,true),preview.mp3Size)
        val withoutEvidence=media.copy(duration=null,formats=listOf(actual360.copy(bytes=null,bitrate=null)))
        val unknown=QuickSharePresentation.prepare(withoutEvidence)
        assertTrue(unknown.preferenceSizes.isEmpty())
        assertNull(unknown.mp3Size)
    }
    @Test fun representationTiersPopulateAccuratePreferenceSizes():Unit=runBlocking {
        val preview=QuickSharePresentation.prepare(source())
        assertTrue(preview.preferenceSizes.containsKey(360))
        assertTrue(preview.preferenceSizes.containsKey(480))
        assertTrue(preview.preferenceSizes.containsKey(720))
        assertEquals(source().formats.map {it.id}.toSet(),preview.videoSources.map {it.id}.toSet())
    }

    @Test fun expiredRawVideoIsNotPublishedAsASelectableQuickShareSource():Unit=runBlocking {
        val stale=Format.parse(JSONObject(source().formats.first().payload)
            .put("format_id","expired")
            .put("url","https://example.com/expired.mp4?expire=${System.currentTimeMillis()/1000-60}"))
        val available=source().formats.last()
        val media=source().copy(formats=listOf(stale,available))

        val presentation=QuickSharePresentation.prepare(media)

        assertEquals(listOf(available.id),presentation.videoSources.map {it.id})
        assertNull(presentation.selection(QuickShareChoice(false,stale.id)))
        assertNotNull(presentation.selection(QuickShareChoice(false,available.id)))
    }

    @Test fun verifiedTopCinemaVariantsKeepExactDimensionsAndAscendByQuality():Unit=runBlocking {
        val dimensions=listOf(1920 to 800,1280 to 532,852 to 356,428 to 176)
        val formats=dimensions.mapIndexed {index,(width,height)->Format.parse(JSONObject()
            .put("format_id","hls-$index").put("url","https://example.com/hls-$index.m3u8")
            .put("ext","mp4").put("protocol","m3u8_native").put("width",width).put("height",height)
            .put("fps",24).put("vcodec","avc1").put("acodec","aac"))}
        val media=Media("https://topcinemaa.one/fixture/watch/","fixture","Fixture",
            "TopCinema",null,null,60.0,formats)
        val preview=QuickSharePresentation.prepare(media)
        assertEquals(listOf(176,356,532,800),preview.groups.map {it.dimensions.edge})
        assertEquals(dimensions.reversed(),preview.groups.map {it.dimensions.width to it.dimensions.height})
        assertEquals(listOf(24.0,24.0,24.0,24.0),preview.groups.map {it.recommended.fps})
        assertEquals(formats.map {it.id}.toSet(),preview.videoSources.map {it.id}.toSet())
    }

    @Test fun portraitGeometryGroupsLogicalLabelsButDoesNotMergeDifferentAspectRatios():Unit=runBlocking {
        val base=source().formats.first()
        fun format(id:String,w:Int,h:Int,label:String)=base.copy(id=id,width=w,height=h,sourceQualityLabel=label)
        val high=format("higher",1080,1920,"480p")
        val sameA=format("dash-long-representation-a",720,1280,"240p")
        val sameB=format("dash-long-representation-b",720,1280,"720p")
        val square=format("square",720,720,"1080p")
        val low=format("lower",360,640,"2160p")
        val landscape=format("landscape",1280,720,"360p")
        val media=source().copy(formats=listOf(high,sameA,low,square,sameB,landscape))

        val groups=QuickSharePresentation.prepare(media).groups

        assertEquals(listOf("360x640","720x720","720x1280","1280x720","1080x1920"),groups.map {it.dimensions.key})
        assertEquals(listOf(sameB.id,sameA.id),groups[2].candidates.map {it.id})
        assertEquals(media.formats.map {it.id}.sorted(),groups.flatMap {it.candidates}.map {it.id}.sorted())
    }

    @Test fun partialOrUnverifiedDimensionsNeverBorrowTheSourceLabelOrAnotherStreamsGeometry():Unit=runBlocking {
        val verified=source().formats.first()
        val partial=verified.copy(id="partial",width=null,height=720)
        val widthOnly=verified.copy(id="width-only",width=720,height=null,sourceQualityLabel="1080p")
        val unverified=Format.parse(JSONObject(verified.payload)
            .put("format_id","dash-2160p-1280x720").put("format_note","1920x1080")
            .put("mediaflow_dimensions","unverified_label").put("mediaflow_source_quality_label","2160p")
            .put("width",1280).put("height",720).put("filesize",1_000_000_000))

        val groups=QuickSharePresentation.prepare(source().copy(formats=listOf(unverified,widthOnly,partial,verified))).groups

        assertEquals(listOf("480x270","height-720","unknown"),groups.map {it.dimensions.key})
        assertEquals(setOf(unverified.id,widthOnly.id),groups.last().candidates.map {it.id}.toSet())
    }

    @Test fun expandedPickerKeepsCodecContainerAndBitrateAlternativesSelectable():Unit=runBlocking {
        val base=source().formats.first {it.height==720}
        fun variant(id:String,codec:String,ext:String,rate:Int)=Format.parse(JSONObject(base.payload)
            .put("format_id",id).put("url","https://example.com/$id.$ext").put("vcodec",codec).put("ext",ext).put("tbr",rate))
        val variants=listOf(variant("av1","av01","mp4",500),variant("vp9-webm","vp09","webm",800),
            variant("vp9-mp4","vp09","mp4",800),variant("vp9-high","vp09","webm",1200))
        val media=source().copy(formats=variants)
        val preview=QuickSharePresentation.prepare(media)
        assertEquals(1,QuickShareCatalog.videoEntries(media).size)
        assertEquals(variants.map {it.id}.toSet(),preview.videoSources.map {it.id}.toSet())
        for(format in variants) for(container in listOf("mp4","mkv","webm").filter {
            QuickShareChoice(false,format.id,it).resolve(media)!=null
        }) {
            val choice=QuickShareChoice(false,format.id,container)
            assertEquals(choice,preview.groups.single().selectedChoice(choice))
            assertEquals(choice.resolve(media),preview.selection(choice))
        }
        assertTrue(preview.variantFields.getValue("vp9-webm").contains(QuickShareVariantDetails.Field.BITRATE))
        assertNull(preview.groups.single().selectedChoice(QuickShareChoice(false,"missing")))
    }
}
