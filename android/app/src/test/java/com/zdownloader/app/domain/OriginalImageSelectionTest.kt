package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OriginalImageSelectionTest {
    private fun photo(id:String,width:Int,height:Int)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id.jpg").put("ext","jpg").put("width",width).put("height",height)
        .put("vcodec","none").put("acodec","none").put("protocol","https").put("mediaflow_image","original").put("filesize",1000))
    private fun media(vararg formats:Format)=Media("https://example.com/post","post","Photo","test",null,null,null,formats.toList())

    @Test fun originalPhotoSelectionKeepsBytesTypeAndPersistedIdentity() {
        val source=media(photo("small",320,400),photo("original",1080,1350))
        val selected=Selection.image(source,source.originalImage()!!)
        assertTrue(selected.imageOnly)
        assertEquals("original",selected.primary.id)
        assertEquals("jpg",selected.output)
        assertFalse(selected.embedMetadata)
        assertFalse(selected.transcodeVideo)
        assertFalse(selected.transcodeAudio)
        assertEquals(MediaSize(1000,false),selected.outputSize)
        assertEquals(selected,Selection.parse(selected.json()))
        assertTrue(Selection.parse(selected.historyJson()).imageOnly)
    }

    @Test fun imageChoiceDoesNotPromoteThumbnailOrAcceptAConversionOutput() {
        val original=photo("original",1080,1350)
        val thumbnail=original.copy(payload=JSONObject(original.payload).apply {remove("mediaflow_image")}.toString())
        assertFalse(thumbnail.image)
        assertNull(QuickShareFormats.defaultChoice(media(thumbnail)))
        val source=media(original)
        assertTrue(QuickShareChoice(false,original.id).resolve(source)!!.imageOnly)
        assertNull(QuickShareChoice(false,original.id,"mp4").resolve(source))
        assertNull(QuickShareChoice(true,original.id).resolve(source))
    }

    @Test fun companionAudioIsSeparateAndDoesNotReplaceTheOriginalPhoto() {
        val image=photo("original",1080,1350)
        val audio=Format.parse(JSONObject().put("format_id","music").put("url","https://example.com/music.m4a")
            .put("ext","m4a").put("vcodec","none").put("acodec","aac").put("protocol","https"))
        val source=media(image,audio)
        assertTrue(QuickShareFormats.defaultChoice(source)!!.resolve(source)!!.imageOnly)
        val selected=DownloadRequest(audioOnly=true).resolve(source)
        assertTrue(selected.audioOnly)
        assertEquals("music",selected.primary.id)
        assertNull(selected.secondary)
        assertEquals(image,QuickShareFormats.defaultChoice(source)!!.resolve(source)!!.primary)
    }

    @Test fun realVideoRemainsTheDefaultWhenAnImageIsAlsoPresent() {
        val image=photo("original",1080,1350)
        val video=Format.parse(JSONObject().put("format_id","video").put("url","https://example.com/video.mp4")
            .put("ext","mp4").put("vcodec","h264").put("acodec","aac").put("width",1920).put("height",1080))
        val selected=DownloadRequest().resolve(media(image,video))
        assertFalse(selected.imageOnly)
        assertEquals("video",selected.primary.id)
    }
}
