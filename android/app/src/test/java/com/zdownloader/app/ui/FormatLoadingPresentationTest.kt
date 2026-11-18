package com.zdownloader.app.ui

import com.zdownloader.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormatLoadingPresentationTest {
    private fun format(id:String,edge:Int=720,bytes:Long?=null,rate:Double?=null)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.mp4").put("ext","mp4")
        .put("width",edge).put("height",edge*16/9).put("vcodec","avc1").put("acodec","none")
        .put("filesize",bytes).put("vbr",rate))
    private fun media(vararg formats:Format)=Media("https://example.com/watch","fixture","Fixture","Generic",null,null,10.0,formats.toList())

    @Test fun placeholdersExistOnlyForActuallyPendingExpandedDiscovery() {
        val pending=QuickShareState(sourceUrl="https://example.com/watch",expanded=true,analyzing=true,discoveringFormats=true)
        assertEquals(3,FormatLoadingPresentation.pendingRows(pending))
        assertEquals(1,FormatLoadingPresentation.pendingRows(pending.copy(media=media(format("known")))))
        assertEquals(0,FormatLoadingPresentation.pendingRows(pending.copy(expanded=false)))
        assertEquals(0,FormatLoadingPresentation.pendingRows(pending.copy(discoveringFormats=false)))
        assertEquals(0,FormatLoadingPresentation.pendingRows(pending.copy(error=ErrorCode.EXTRACTION_FAILED)))
        assertEquals(0,FormatLoadingPresentation.pendingRows(pending.copy(sourceUrl=null)))
    }

    @Test fun lateRowsAndNewSizeMetadataUseTheSameStrictCatalogSort() {
        val first=media(format("720"),format("1080",1080))
        val order=FormatLoadingPresentation.sources(QuickShareState(media=first)).map {it.id}
        assertEquals(listOf("720","1080"),order)
        val updated=media(format("240",240),format("720",bytes=5000),format("1080",1080,1000))
        val selected=QuickShareChoice(false,"720")
        val state=QuickShareState(media=updated,choice=selected)
        val rows=FormatLoadingPresentation.sources(state)
        assertEquals(listOf("240","720","1080"),rows.map {it.id})
        assertEquals(5000L,rows[1].bytes)
        assertEquals("720",state.selection!!.primary.id)
    }

    @Test fun onlyUnknownSizesOfPendingRequestsHavePlaceholdersAndEstimatesAreImmediate() {
        val unknown=format("unknown")
        val selected=Selection(primary=unknown,videoOnly=true)
        val pending=QuickShareState(pendingSizeIds=setOf("unknown"),enrichingSizes=true)
        assertTrue(FormatLoadingPresentation.sizePending(pending,selected,true))
        assertFalse(FormatLoadingPresentation.sizePending(pending.copy(pendingSizeIds=emptySet()),selected,true))
        assertFalse(FormatLoadingPresentation.sizePending(pending,selected.copy(primary=format("unknown",bytes=1000)),true))
        assertFalse(FormatLoadingPresentation.sizePending(pending,selected.copy(primary=format("unknown",rate=500.0),duration=10.0),true))
    }

    @Test fun optionalAudioSizeCanBePendingWithoutChangingTheSelectedVideo() {
        val video=format("video",bytes=5000)
        val audio=Format.parse(JSONObject().put("format_id","audio").put("url","https://example.com/audio.m4a")
            .put("ext","m4a").put("vcodec","none").put("acodec","mp4a"))
        val selected=Selection(video,audio)
        assertTrue(FormatLoadingPresentation.sizePending(QuickShareState(pendingSizeIds=setOf("audio")),selected,true))
        assertEquals("video",Selection.parse(selected.json()).primary.id)
    }

    @Test fun arrivalsAnimateAtMostOnceAndNeverReplayOnSizeChangesOrScrolling() {
        val tracker=FormatArrivalTracker(listOf("already-loaded"))
        assertFalse(tracker.firstArrival("already-loaded"))
        assertTrue(tracker.firstArrival("new"))
        assertFalse(tracker.firstArrival("new"))
    }
}
