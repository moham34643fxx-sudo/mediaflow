package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class QuickShareFormatsTest {
    private fun video(id:String,height:Int)=Format(id,"mp4",height,30.0,"h264","none",1000,1000.0,"https","{\"url\":\"https://example.com/video.mp4\"}",width=height*16/9)
    private val audio=Format("audio","m4a",null,null,"none","aac",1000,128.0,"https","{\"url\":\"https://example.com/audio.m4a\"}")
    private fun media(vararg values:Format)=Media("https://example.com/watch","id","Title","Source",null,null,10.0,values.toList())
    @Test fun shareTextAcceptsOneRealHttpsLinkAndRejectsAmbiguousOrPrivatePayloads() {
        assertEquals("https://example.com/reel?id=123",QuickShareFormats.sharedUrl(listOf("Watch this: https://example.com/reel?id=123")))
        assertEquals("https://example.com/a",QuickShareFormats.sharedUrl(listOf("https://example.com/a","https://example.com/a")))
        assertNull(QuickShareFormats.sharedUrl(listOf("https://example.com/a https://example.com/b")))
        assertNull(QuickShareFormats.sharedUrl(listOf("https://127.0.0.1/private")))
        assertNull(QuickShareFormats.sharedUrl(listOf("javascript:alert(1)")))
    }
    @Test fun compactOffersRealBasicQualitiesAndKeepsTheChosenRow() {
        val all=media(video("1080",1080),video("720",720),video("480",480),video("480-alt",480),audio)
        assertEquals(listOf(480,720),QuickShareFormats.compactGroups(all,720,null).map {it.resolution})
        val chosen=QuickShareChoice(false,"1080")
        assertEquals(listOf(480,720),QuickShareFormats.compactGroups(all,720,chosen).map {it.resolution})
        assertNotNull(chosen.resolve(all))
        assertEquals(2,QuickShareFormats.compactGroups(all,720,chosen).size)
        assertEquals(listOf(480),QuickShareFormats.compactGroups(media(video("only",480)),720,null).map {it.resolution})
    }
    @Test fun choiceResolvesActualOutputAndDoesNotDowngradeMissingCandidate() {
        val all=media(video("720",720),audio)
        val selected=QuickShareChoice(false,"720").resolve(all)!!
        assertEquals(720,selected.primary.height)
        assertEquals("audio",selected.secondary?.id)
        assertNull(QuickShareChoice(false,"1080").resolve(all))
        assertNull(QuickShareChoice(false,"720","invented").resolve(all))
        assertEquals("mp3",QuickShareChoice(true,"audio","mp3").resolve(all)?.output)
        assertTrue(QuickShareChoice(true,"audio","mp3").resolve(all)!!.convertAudio)
    }
    @Test fun unsupportedProtocolNeverBecomesAQuickShareCandidate() {
        val unavailable=video("rtmp",1080).copy(protocol="rtmp")
        assertNull(QuickShareFormats.defaultChoice(media(unavailable)))
        assertNull(QuickShareChoice(false,"rtmp").resolve(media(unavailable)))
    }
    @Test fun preferenceBelowAvailableResolutionsNeverRecommendsUnknownAsALowerQuality() {
        val unknown=video("unknown",480).copy(height=null,width=null)
        val all=media(video("720",720),video("240",240),unknown,audio)
        val groups=all.qualityGroups()
        assertEquals(listOf(720),QuickShareFormats.compactGroups(groups,144,null).map {it.resolution})
        assertNull(QuickShareFormats.defaultChoice(all,144))
        assertNull(QuickShareChoice(false,"invented144").resolve(all))
    }
    @Test fun manyRepresentationsRetainExactChosenCandidateWithoutDuplicatedCompactRows() {
        val heights=listOf(144,360,480,720,1080,2160)
        val variants=(0 until 1000).map {index->video("candidate-$index",heights[index%heights.size])}
        val all=media(*(variants+audio).toTypedArray())
        val groups=all.qualityGroups()
        assertEquals(heights.size,groups.size)
        val choice=QuickShareChoice(false,variants.last {it.height==2160}.id)
        val compact=QuickShareFormats.compactGroups(groups,720,choice)
        assertEquals(3,compact.size)
        assertEquals(3,compact.map {it.resolution}.distinct().size)
        assertEquals(listOf(360,480,720),compact.map {it.resolution})
        assertEquals(choice.formatId,choice.resolve(all)?.primary?.id)
        assertEquals(2160,choice.resolve(all)?.primary?.height)
    }
    @Test fun selectedHighResolutionNeverDisplacesTheBasicVideoChoices() {
        val all=media(video("4k",2160),video("2k",1440),video("1080",1080),video("720",720),video("480",480),audio)
        for(preferred in listOf(0,480,720,1440,2160)) {
            assertEquals(listOf(480,720),QuickShareFormats.compactGroups(all,preferred,QuickShareChoice(false,"4k")).map {it.resolution})
        }
        assertTrue(QuickShareFormats.hasMoreVideo(all))
        assertFalse(QuickShareFormats.hasMoreVideo(media(audio)))
        assertEquals(listOf(360),QuickShareFormats.compactGroups(media(video("360",360)),0,null).map {it.resolution})
    }
    @Test fun audioChoicesKeepConciseOutputOrderAndOnlyContainResolvableDistinctFormats() {
        val all=media(audio)
        val choices=QuickShareFormats.audioChoices(all,audio,true)
        assertEquals("mp3",choices.first().output)
        val order=choices.map {it.output}
        assertTrue(order.indexOf("m4a").let {it<0||it>order.indexOf("mp3")})
        assertTrue(order.indexOf("aac").let {it<0||it>order.indexOf("m4a")})
        assertTrue(order.indexOf("opus").let {it<0||it>order.indexOf("aac")})
        assertTrue(order.indexOf("original").let {it<0||it>order.indexOf("opus")})
        assertTrue(choices.size>1)
        assertTrue(choices.all {it.resolve(all)!=null})
        assertEquals(choices.size,choices.map {it.output}.distinct().size)
        assertEquals("m4a",choices.single {it.output=="original"}.resolve(all)?.output)
        assertEquals("m4a",choices.single {it.output=="m4a"}.resolve(all)?.output)
        assertTrue(QuickShareFormats.audioChoices(all,audio,false).none {it.output=="mp3"})
        assertTrue(QuickShareFormats.audioChoices(media(),audio,true).isEmpty())
    }
    @Test fun multipleCodecsAtSameVerifiedEdgeShowOneAscendingRowAndPreserveAllEntriesAndCaps() {
        val v1080h264=Format("v-1080-h264","mp4",1080,30.0,"h264","none",1000,1000.0,"https","{\"url\":\"https://example.com/1.mp4\"}",width=1920)
        val v1080av1=Format("v-1080-av1","mp4",1080,30.0,"av01","none",1000,1000.0,"https","{\"url\":\"https://example.com/2.mp4\"}",width=1920)
        val v1080vp9=Format("v-1080-vp9","webm",1080,30.0,"vp9","none",1000,1000.0,"https","{\"url\":\"https://example.com/3.mp4\"}",width=1920)
        val v720=Format("v-720","mp4",720,30.0,"h264","none",1000,1000.0,"https","{\"url\":\"https://example.com/4.mp4\"}",width=1280)
        val v480=Format("v-480","mp4",480,30.0,"h264","none",1000,1000.0,"https","{\"url\":\"https://example.com/5.mp4\"}",width=854)
        val unknown=Format("v-unknown","mp4",null,null,"h264","none",1000,1000.0,"https","{\"url\":\"https://example.com/6.mp4\"}")
        val all=media(v1080h264,v1080av1,v1080vp9,v720,v480,unknown,audio)
        val displayed=QuickShareCatalog.allVideoSources(all)
        assertEquals(listOf(480,720,1080,null),displayed.map {verifiedQualityEdge(it)})
        assertEquals(4,displayed.size)
        val rawEntries=QuickShareCatalog.videoEntries(all)
        assertEquals(4,rawEntries.size)
        assertEquals(7,all.formats.size)
        assertNotNull(QuickShareChoice(false,"v-1080-vp9").resolve(all))
        assertNotNull(QuickShareChoice(false,"v-1080-av1").resolve(all))
        val groups=all.qualityGroups()
        assertEquals(listOf(1080,720,480,null),groups.map {it.resolution})
        assertEquals(720,all.recommendedVideo(720)?.height)
    }
    @Test fun likelyImageUrlRecognizesInstagramAndPhotoPostsWithoutTreatingVideoAsImage() {
        assertTrue(QuickShareFormats.isLikelyImageUrl("https://www.instagram.com/p/De4f1OAou7a/"))
        assertTrue(QuickShareFormats.isLikelyImageUrl("https://instagram.com/p/De4f1OAou7a"))
        assertFalse(QuickShareFormats.isLikelyImageUrl("https://www.instagram.com/reel/C8xYz1/"))
        assertFalse(QuickShareFormats.isLikelyImageUrl("https://www.instagram.com/reels/C8xYz1/"))
        assertFalse(QuickShareFormats.isLikelyImageUrl("https://youtu.be/OSrbmtVbw9A"))
        assertTrue(QuickShareFormats.isLikelyImageUrl("https://example.com/art.jpg"))
        assertTrue(QuickShareFormats.isLikelyImageUrl("https://example.com/banner.png?size=large"))
        assertTrue(QuickShareFormats.isLikelyImageUrl("https://www.facebook.com/photo/?fbid=12345"))
        assertTrue(QuickShareFormats.isLikelyImageUrl("https://twitter.com/user/status/123/photo/1"))
        assertFalse(QuickShareFormats.isLikelyImageUrl(null))
        assertFalse(QuickShareFormats.isLikelyImageUrl(""))
    }
    @Test fun imageOnlyMediaDefaultsToOriginalImageAndResolvesProperly() {
        val img1 = Format("img-1080", "jpg", 1080, null, "none", "none", 500000, null, "https", "{\"url\":\"https://example.com/1.jpg\",\"mediaflow_image\":\"original\"}", width=1920)
        val img2 = Format("img-720", "jpg", 720, null, "none", "none", 250000, null, "https", "{\"url\":\"https://example.com/2.jpg\",\"mediaflow_image\":\"original\"}", width=1280)
        val imageMedia = media(img1, img2)
        val defaultChoice = QuickShareFormats.defaultChoice(imageMedia)
        assertNotNull(defaultChoice)
        assertEquals("img-1080", defaultChoice?.formatId)
        val selection = defaultChoice?.resolve(imageMedia)
        assertNotNull(selection)
        assertEquals("img-1080", selection?.primary?.id)
        assertTrue(selection?.imageOnly == true)

    }
}
