package com.zdownloader.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserAdblockDiagnosticsTest {
    @Test
    fun knownActionUsesFixedVocabulary() {
        assertEquals(
            "event=player_action action=skip_result attempt=2 result=ad_cleared",
            BrowserAdblockDiagnostics.playerActionMessage("skip_result", 2, "ad_cleared")
        )
    }

    @Test
    fun arbitraryJavascriptStringsAreNotLoggedEvenWhenAlphanumeric() {
        assertNull(BrowserAdblockDiagnostics.playerActionMessage("ExampleSecret123", 1, "ad_cleared"))
        assertNull(BrowserAdblockDiagnostics.playerActionMessage("skip_result", 1, "ExampleSecret123"))
        assertNull(BrowserAdblockDiagnostics.playerActionMessage("skip_result", 1, "playback_started"))
    }
}
