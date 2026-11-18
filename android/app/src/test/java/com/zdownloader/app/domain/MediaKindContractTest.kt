package com.zdownloader.app.domain

import com.zdownloader.app.ui.QuickShareState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaKindContractTest {
    private fun photo()=Format.parse(JSONObject().put("format_id","photo").put("url","https://example.com/photo.jpg")
        .put("ext","jpg").put("vcodec","none").put("acodec","none").put("width",1080).put("height",1350)
        .put("mediaflow_image","original"))
    private fun media(vararg formats:Format)=Media("https://example.com/post","post","Post","test",null,null,null,formats.toList())

    @Test fun videoRequestsNeverBecomeOriginalPhotosIncludingBestAndPersistedCaps() {
        val source=media(photo())
        for(height in listOf(0,360,720,1080)) {
            val request=DownloadRequest.parse(DownloadRequest(height=height).json())
            assertTrue("Video intent $height silently became an image",runCatching {request.resolve(source)}.exceptionOrNull() is MediaException)
            assertNull("Image was reused as verified video",VideoPreferenceSelection.ready(source,height))
        }
        assertTrue(QuickShareChoice(false,"photo").resolve(source)!!.imageOnly)
    }

    @Test fun resolvedImageMetadataCannotSatisfyQueuedMediaPreferences() {
        val url="https://example.com/post"
        for(height in listOf(0,360,720,1080,-1)) {
            assertFalse("Image metadata cannot satisfy video/audio preference $height",
                QuickShareState(sourceUrl=url,media=media(photo()),qualityPreference=height,mp3Available=true).canSubmit)
        }
    }

    @Test fun missingCodecsAndUnsupportedImagesAreNotEvidenceOfVideo() {
        for(extension in listOf("avif","heic","heif","gif","bmp","tiff","svg","ico","mp4")) {
            val source=Format.parse(JSONObject().put("format_id","unknown").put("url","https://example.com/file.$extension").put("ext",extension))
            assertFalse("$extension was guessed to be video",source.video)
            assertFalse(source.audio)
        }
    }

    @Test fun mixedSourcesKeepImageAudioAndVideoChoicesSeparate() {
        val video=Format.parse(JSONObject().put("format_id","video").put("url","https://example.com/video.mp4")
            .put("ext","mp4").put("width",1280).put("height",720).put("vcodec","h264").put("acodec","none"))
        val audio=Format.parse(JSONObject().put("format_id","audio").put("url","https://example.com/audio.m4a")
            .put("ext","m4a").put("vcodec","none").put("acodec","aac"))
        val source=media(photo(),video,audio)
        assertEquals(listOf("video"),source.videoOptions().map {it.id})
        assertEquals(listOf("photo"),source.imageOptions().map {it.id})
        assertEquals(listOf("audio"),source.audioOptions().map {it.id})
        assertEquals("video",DownloadRequest(height=720).resolve(source).primary.id)
        assertTrue(DownloadRequest(audioOnly=true).resolve(source).audioOnly)
        assertTrue(QuickShareChoice(false,"photo").resolve(source)!!.imageOnly)
    }
}
