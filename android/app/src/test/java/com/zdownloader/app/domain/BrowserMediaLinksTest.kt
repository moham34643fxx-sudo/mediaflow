package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class BrowserMediaLinksTest {
    @Test fun queryAndFragmentDoNotChangeTheMediaPath() {
        for(url in listOf("https://cdn.example.com/video.MP4?token=secret#position","https://cdn.example.com/live.m3u8?expires=10",
            "https://cdn.example.com/audio.m4a?key=1","https://cdn.example.com/manifest.mpd#fragment")) assertTrue(url,BrowserMediaLinks.matches(url))
    }
    @Test fun bookmarkDestinationsAndShortLinksHaveRealMediaActions() {
        for(url in listOf("https://www.pinterest.com/pin/123/","https://pin.it/abc123","https://vm.tiktok.com/abc/",
            "https://vt.tiktok.com/abc/","https://www.instagram.com/reel/abc/","https://m.youtube.com/watch?v=abc",
            "https://www.youtube.com/shorts/abc","https://www.facebook.com/share/v/abc/","https://fb.watch/abc/","https://x.com/user/status/123"))
            assertTrue(url,BrowserMediaLinks.matches(url))
    }
    @Test fun siteNamesInAnotherHostOrQueryAreNotMediaDetections() {
        for(url in listOf("https://youtube.com.attacker.example/watch?v=abc","https://example.com/?next=youtube.com/watch?v=abc",
            "https://example.com/promo.mp4.html","https://www.pinterest.com/","https://m.youtube.com/","file:///tmp/a.mp4","javascript:alert(1)","not a URL"))
            assertFalse(url,BrowserMediaLinks.matches(url))
    }
}
