package com.zdownloader.app.domain

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class SharedLinksTest {
    private fun encoded(value:String)=URLEncoder.encode(value,"UTF-8")

    @Test fun longSignedUrlsSurvivePasteShareAndFormatValidationExactly() {
        val url="https://cdn.example.com/download?id=clip&signature="+"a%2Fb%2B".repeat(3000)+"&part=1&part=2"
        assertTrue(url.length>16384)
        assertEquals(listOf(url),UrlPolicy.inputLinks(url))
        assertEquals(url,QuickShareFormats.sharedUrl(listOf(url)))
        assertEquals(url,UrlPolicy.normalize(url))
    }

    @Test fun completePastedUrlKeepsLegalApostrophesAndTrailingSignedPunctuation() {
        for(url in listOf("https://cdn.example.com/user's/video?signature=abc,", "https://cdn.example.com/download?token=abc.")) {
            assertEquals(listOf(url),UrlPolicy.inputLinks(url))
            assertEquals(url,QuickShareFormats.sharedUrl(listOf(url)))
        }
        assertEquals(listOf("https://example.com/video"),UrlPolicy.inputLinks("شاهد (https://example.com/video)."))
        assertEquals(listOf("https://example.com/user's/video"),UrlPolicy.inputLinks("شاهد https://example.com/user's/video"))
        assertEquals(listOf("https://example.com/video"),UrlPolicy.inputLinks("شاهد 'https://example.com/video'"))
    }

    @Test fun commonSharingEnvelopesResolveToTheExactSignedDestination() {
        val destination="https://cdn.example.com/download?sig=a%2Fb%2Bc+def&item=1&item=2"
        for(prefix in listOf("https://l.facebook.com/l.php?u=","https://lm.facebook.com/l.php?u=",
            "https://l.instagram.com/?u=","https://www.google.com/url?q=","https://www.google.com.eg/url?url=",
            "https://www.youtube.com/redirect?q=","https://nam01.safelinks.protection.outlook.com/?url=")) {
            val wrapper=prefix+encoded(destination)+"&tracking=unchanged"
            assertEquals(destination,SharedLinks.normalize(wrapper))
            assertEquals(listOf(destination),UrlPolicy.inputLinks(wrapper))
        }
    }

    @Test fun youtubeAttributionRetainsVideoAndPlaylistIdentifiers() {
        val destination="/watch?v=abc123&list=PL123&index=7"
        assertEquals("https://www.youtube.com$destination",SharedLinks.normalize("https://www.youtube.com/attribution_link?u="+encoded(destination)))
    }

    @Test fun nestedSharingEnvelopesAndTheirDirectDestinationDeduplicate() {
        val destination="https://www.youtube.com/watch?v=abc123"
        val facebook="https://l.facebook.com/l.php?u="+encoded(destination)
        val google="https://www.google.com/url?q="+encoded(facebook)
        assertEquals(listOf(destination),UrlPolicy.inputLinks("$google\n$destination"))
        assertEquals(destination,QuickShareFormats.sharedUrl(listOf(google,destination)))
    }

    @Test fun unrelatedAndTransportUrlsAreNeverRewritten() {
        val wrapped="https://l.facebook.com/l.php?u="+encoded("https://example.com/video")
        assertEquals(wrapped,UrlPolicy.normalize(wrapped))
        for(url in listOf("https://cdn.example.com/download?url=https%3A%2F%2Fother.example%2Fclip&sig=a%2Bb",
            "https://www.google.com.evil.example/url?q="+encoded("https://example.com/clip"),
            "https://l.facebook.com/not-a-redirect?u="+encoded("https://example.com/clip"))) {
            assertEquals(url,SharedLinks.normalize(url))
        }
    }

    @Test fun wrappedDestinationsStillPassTheCompleteUrlPolicy() {
        for(destination in listOf("https://127.0.0.1/video","http://example.com/video","file:///private",
            "https://user:pass@example.com/video","https://example.com/a\nb")) {
            assertThrows(MediaException::class.java) {SharedLinks.normalize("https://l.facebook.com/l.php?u="+encoded(destination))}
        }
    }

    @Test fun urlLengthLimitIsConsistentAndNeverSilentlyTruncates() {
        val prefix="https://cdn.example.com/"
        val maximum=prefix+"x".repeat(UrlPolicy.MAX_URL_LENGTH-prefix.length)
        assertEquals(maximum,UrlPolicy.normalize(maximum))
        assertThrows(MediaException::class.java) {UrlPolicy.inputLinks(maximum+"x")}
        assertThrows(MediaException::class.java) {SharedLinks.normalize("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2F%ZZ")}
    }
}
