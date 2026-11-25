package com.zdownloader.app.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserYoutubePlayerScriptletTest {
    @Test
    fun bundledScriptUsesOnlyExactHttpsOriginsAndDoesNotWaitForNetworkRules() {
        val loading = BrowserAdblockState(engineReady = false)
        for (origin in listOf("https://youtube.com", "https://www.youtube.com", "https://m.youtube.com")) {
            assertTrue(origin, BrowserYoutubePlayerScriptlet.isAllowed(origin, loading))
        }
        for (origin in listOf(null, "http://m.youtube.com", "https://m.youtube.com:8443",
            "https://tv.youtube.com", "https://www.youtube.com.example.test", "https://www.youtube.com/")) {
            assertFalse(origin, BrowserYoutubePlayerScriptlet.isAllowed(origin, loading))
        }
    }

    @Test
    fun masterSwitchAndExactSiteExceptionUseTheSamePreferenceSnapshotAsNetworkFiltering() {
        val settings = BrowserAdblockState(engineReady = true, excludedHosts = setOf("m.youtube.com"))
        assertFalse(BrowserYoutubePlayerScriptlet.isAllowed("https://m.youtube.com", settings))
        assertTrue(BrowserYoutubePlayerScriptlet.isAllowed("https://www.youtube.com", settings))
        assertFalse(BrowserYoutubePlayerScriptlet.isAllowed("https://www.youtube.com", settings.copy(enabled = false)))
    }

    @Test
    fun theLegacyBridgeRejectsMissingAndMismatchedWebViewKeys() {
        val bridge = BrowserCosmeticBridge(null, "scoped-example-key")
        val origin = "https://m.youtube.com"
        assertFalse(bridge.youtubePlayerScriptletAllowed(null, origin))
        assertFalse(bridge.youtubePlayerScriptletAllowed("other-webview-key", origin))
        assertFalse(BrowserCosmeticBridge(null).youtubePlayerScriptletAllowed("scoped-example-key", origin))
    }
}
