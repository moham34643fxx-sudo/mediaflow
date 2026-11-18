package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PolicyTest {
    @Test fun urlsPreserveIdentifiersAndRejectDangerousSchemes() {
        val url="https://example.com/watch?v=123&token=a%2Fb#fragment"
        assertEquals("https://example.com/watch?v=123&token=a%2Fb",UrlPolicy.normalize(url))
        listOf("file:///etc/passwd","javascript:alert(1)","http://example.com/video","https://user:pass@example.com/video","https://example.com:0/video","https://example.com:65536/video","https://example.com:/video").forEach { input -> assertThrows(MediaException::class.java) { UrlPolicy.normalize(input) } }
    }
    @Test fun privateAndSpecialIpRangesCannotBeDownloaded() {
        listOf("127.0.0.1","10.1.2.3","172.16.1.2","192.168.1.1","169.254.169.254","100.64.1.1","::1","fc00::1","2001:db8::1").forEach { assertFalse(it,UrlPolicy.publicAddress(InetAddress.getByName(it))) }
        assertTrue(UrlPolicy.publicAddress(InetAddress.getByName("1.1.1.1")))
    }
    @Test fun namesPreserveArabicAndRemovePathTraversal() {
        assertEquals("فيديو _ رائع",FileNames.sanitize("فيديو / رائع"))
        assertEquals("_file",FileNames.sanitize("../file"))
        assertEquals("Media",FileNames.sanitize(".."))
    }
    @Test fun duplicateLinksDeduplicateButDifferentQueriesRemain() {
        assertEquals(listOf("https://example.com/v?a=1","https://example.com/v?a=2"),UrlPolicy.links("https://example.com/v?a=1\nhttps://example.com/v?a=1\nhttps://example.com/v?a=2"))
    }
    @Test fun platformUrlsAreCorrectlyNormalizedAndCleaned() {
        // HTTPS in links
        assertEquals(listOf("https://instagram.com/reel/123"), UrlPolicy.links("Check this out: https://instagram.com/reel/123"))
        // Instagram share link normalization in UrlPolicy
        assertEquals("https://www.instagram.com/reel/Chunk8-jurw/?igsh=123", UrlPolicy.normalize("https://www.instagram.com/share/reel/Chunk8-jurw/?igsh=123#fragment"))
        assertEquals("https://www.instagram.com/p/Chunk8-jurw/", UrlPolicy.normalize("https://www.instagram.com/share/p/Chunk8-jurw/"))
        // MediaIdentity tracking query stripping
        assertEquals("https://www.instagram.com/reel/Chunk8-jurw/", MediaIdentity.url("https://www.instagram.com/share/reel/Chunk8-jurw/?igsh=MWxxxx==&utm_source=ig_web_copy_link"))
        assertEquals("https://x.com/user/status/12345", MediaIdentity.url("https://x.com/user/status/12345?s=20&t=abc123xyz"))
        assertEquals("https://www.tiktok.com/@user/video/98765", MediaIdentity.url("https://www.tiktok.com/@user/video/98765?_r=1&_t=8abcd"))
        assertEquals("https://www.facebook.com/reel/55555", MediaIdentity.url("https://www.facebook.com/reel/55555?mibextid=ww55"))
    }
}
