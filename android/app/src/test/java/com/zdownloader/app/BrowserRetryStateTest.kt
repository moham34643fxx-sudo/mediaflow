package com.zdownloader.app

import com.zdownloader.app.ui.BrowserRetryState
import org.junit.Assert.*
import org.junit.Test

class BrowserRetryStateTest {
    @Test fun retriesFailedMainFrameGetRatherThanInitialPage() {
        val state=BrowserRetryState("https://example.com/a")
        state.failed("https://example.com/b","GET")
        assertEquals("https://example.com/b",state.target)
        state.retry()
        assertEquals("https://example.com/b",state.target)
        assertFalse(state.formNotResent)
    }
    @Test fun postAndRendererFailuresNeverReplayFormOrUnsafeScheme() {
        val state=BrowserRetryState("https://example.com/login")
        state.requested("https://example.com/submit","POST")
        state.failed("https://example.com/submit","POST")
        assertEquals("https://example.com/login",state.target)
        assertTrue(state.formNotResent)
        state.rendererGone()
        assertEquals("https://example.com/login",state.target)
        state.failed("javascript:alert(1)","GET")
        assertEquals("https://example.com/login",state.target)
    }
}
