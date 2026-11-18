package com.zdownloader.app.domain

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProductQualityTest {
    private fun video(res:Int,width:Int=res*16/9,height:Int=res):Format=Format.parse(JSONObject()
        .put("format_id","$res").put("url","https://example.com/$res.mp4").put("ext","mp4")
        .put("width",width).put("height",height).put("vcodec","h264").put("acodec","aac"))
    private fun media(vararg rows:Format)=Media("https://example.com/v","v","Video","Test",null,null,null,rows.toList())
    @Test fun compactChoicesPreferARealConventionalAlternative() {
        val media=media(video(1080),video(540),video(480),video(360))
        assertEquals(listOf(360,480),QuickShareFormats.compactGroups(media,0,null).map {it.resolution})
        assertEquals(listOf(360,480,540,1080),QuickShareCatalog.allVideoSources(media).map {verifiedQualityEdge(it)})
        assertEquals(listOf(1080,540,480,360),media.qualityGroups().map {it.resolution})
    }
    @Test fun aNonstandardSourceIsNotRelabelledAndRemainsAvailableInTheFullList() {
        val media=media(video(540),video(480),video(360))
        assertEquals(listOf(360,480),QuickShareFormats.compactGroups(media,0,null).map {it.resolution})
        assertTrue(media.qualityGroups().any {it.resolution==540})
        val nonstandardOnly=media(video(540))
        assertTrue(QuickShareFormats.compactGroups(nonstandardOnly,0,null).isEmpty())
        assertEquals(listOf(540),QuickShareCatalog.allVideoSources(nonstandardOnly).map {verifiedQualityEdge(it)})
        assertEquals(540,QuickShareChoice(false,"540").resolve(nonstandardOnly)!!.primary.height)
    }
    @Test fun aDefaultPreferenceDoesNotChangeTheSimpleCompactRowsOrTheResolvedSource() {
        val media=media(video(1080),video(540),video(480))
        val choice=QuickShareFormats.defaultChoice(media,720)!!
        assertEquals("540",choice.formatId)
        assertEquals(listOf(480),QuickShareFormats.compactGroups(media,720,choice).map {it.resolution})
        assertEquals(listOf(480,540,1080),QuickShareCatalog.allVideoSources(media).map {verifiedQualityEdge(it)})
        assertEquals(540,choice.resolve(media)!!.primary.height)
    }
    @Test fun portraitDimensionsUseTheShortEdgeAndPreserveTheChosenSource() {
        for((short,long) in listOf(1080 to 1920,720 to 1280,540 to 960)) {
            val source=video(short,short,long);val media=media(source)
            assertEquals(short,qualityLabel(source))
            val choice=QuickShareChoice(false,source.id).resolve(media)!!
            assertEquals(short,choice.primary.width);assertEquals(long,choice.primary.height)
        }
    }
    @Test fun selectedAdditionalQualityDoesNotDisplaceTheBasicCompactRows() {
        val media=media(video(1080),video(720),video(540),video(480))
        val chosen=QuickShareChoice(false,"540")
        val groups=QuickShareFormats.compactGroups(media,0,chosen)
        assertEquals(listOf(480,720),groups.map {it.resolution})
        assertEquals(listOf(480,540,720,1080),QuickShareCatalog.allVideoSources(media).map {verifiedQualityEdge(it)})
        assertEquals("540",chosen.resolve(media)!!.primary.id)
        assertEquals(groups.size,groups.distinctBy {it.resolution}.size)
    }
    @Test fun leavingTheScreenAfterPersistenceCannotCancelTheSchedulingHandoff()=runBlocking {
        val parent=Job();val scope=CoroutineScope(parent+Dispatchers.Default)
        val persisted=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val accepted=CompletableDeferred<Unit>()
        val request=scope.launch {SubmissionHandoff.run {persisted.complete(Unit);release.await();accepted.complete(Unit)}}
        try {
            persisted.await();parent.cancel();assertFalse(accepted.isCompleted)
            release.complete(Unit)
            withTimeout(2000) {accepted.await();request.join()}
        } finally {release.complete(Unit);parent.cancel()}
    }
    @Test fun actualSubmissionExceptionsAreNotHidden()=runBlocking {
        val failure=IllegalStateException("Scheduling rejected")
        try {SubmissionHandoff.run<Unit> {throw failure};fail("Expected real rejection")}
        catch(actual:IllegalStateException) {assertEquals(failure.message,actual.message)}
    }
}
