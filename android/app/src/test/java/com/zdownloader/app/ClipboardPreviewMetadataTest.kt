package com.zdownloader.app

import com.zdownloader.app.data.ClipboardPreviewMetadata
import org.junit.Assert.*
import org.junit.Test

class ClipboardPreviewMetadataTest {
    @Test fun embeddedMetadataKeepsPlainTitlesAndRejectsUnsafeArtwork() {
        val preview=ClipboardPreviewMetadata.embedded("""{"title":"A <3 moment","thumbnail_url":"https://i.ytimg.com/vi/fixture/hqdefault.jpg"}""")
        assertEquals("A <3 moment",preview.title)
        assertEquals("https://i.ytimg.com/vi/fixture/hqdefault.jpg",preview.imageUrl)
        assertNull(ClipboardPreviewMetadata.embedded("""{"title":"A title","thumbnail_url":"file:///private.png"}""").imageUrl)
        try {ClipboardPreviewMetadata.embedded("not json");fail("Malformed metadata cannot become a successful preview")}
        catch(_:java.io.IOException) {}
    }

    @Test fun socialMetadataSupportsAttributeOrderRelativeImagesAndTitleFallback() {
        val cases=listOf(
            Triple("<meta content='A real title' property='og:title'><meta property='og:image' content='/cover.jpg'>","A real title","https://example.com/cover.jpg"),
            Triple("<META NAME=twitter:title CONTENT='A second title'><meta content='https://cdn.example.com/a.webp?a=1&amp;b=2' name='twitter:image'>","A second title","https://cdn.example.com/a.webp?a=1&b=2"),
            Triple("<meta content='' property='og:title'><title>  Page\n title </title>","Page title",null),
            Triple("<meta property='og:title' content='First'><meta property='og:title' content='Second'>","First",null)
        )
        for((html,title,image) in cases) {
            val preview=ClipboardPreviewMetadata.parse(html,"https://example.com/watch/video")
            assertEquals(title,preview.title);assertEquals(image,preview.imageUrl)
        }
    }

    @Test fun missingMetadataAndUnsafeImageAddressesKeepACleanFallback() {
        for(image in listOf("file:///sdcard/private.png","http://127.0.0.1/private","http://192.168.1.1/a.jpg","javascript:alert(1)","data:image/png;base64,AAAA")) {
            assertNull(ClipboardPreviewMetadata.parse("<meta property='og:image' content='$image'>","https://example.com").imageUrl)
        }
        val empty=ClipboardPreviewMetadata.parse("<html><head></head></html>","https://example.com")
        assertNull(empty.title);assertNull(empty.imageUrl)
    }
}
