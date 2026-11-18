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
            val tier=QuickShareVideoRows.expanded(preview.groups,choice,emptySet()).single {it.selected}
            assertFalse(tier.expanded)
            val rows=QuickShareVideoRows.variants(tier.group,choice)
            assertEquals(choice,rows.single {it.selected}.choice)
            assertEquals(choice.resolve(media),preview.selection(choice))
        }
        assertTrue(preview.variantFields.getValue("av1").contains(QuickShareVariantDetails.Field.CODEC))
        assertTrue(preview.variantFields.getValue("vp9-webm").contains(QuickShareVariantDetails.Field.CONTAINER))
    }
}
