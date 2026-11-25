package com.zdownloader.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserServiceWorkerInterceptorTest {
    @Test
    fun bothRequiredWebViewFeaturesMustBeAvailable() {
        assertTrue(BrowserServiceWorkerInterceptor.supportsInterception(true, true))
        assertFalse(BrowserServiceWorkerInterceptor.supportsInterception(true, false))
        assertFalse(BrowserServiceWorkerInterceptor.supportsInterception(false, true))
        assertFalse(BrowserServiceWorkerInterceptor.supportsInterception(false, false))
    }

    @Test
    fun matchingRefererSelectsItsFullPageUrl() {
        val referer = "https://youtube.com/watch?v=fixture"
        assertEquals(
            referer,
            BrowserServiceWorkerInterceptor.selectPageContext(
                referer,
                null,
                listOf(context("https://youtube.com/home"))
            )
        )
    }

    @Test
    fun originHeaderCanAttributeRequestWhenRefererIsAbsent() {
        assertEquals(
            "https://youtube.com/home",
            BrowserServiceWorkerInterceptor.selectPageContext(
                null,
                "https://youtube.com",
                listOf(context("https://youtube.com/home"))
            )
        )
    }

    @Test
    fun validOriginUsesPageContextInsteadOfMalformedReferer() {
        assertEquals(
            "https://publisher.example/article",
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://[invalid",
                "https://publisher.example",
                listOf(context("https://publisher.example/article"))
            )
        )
    }

    @Test
    fun conflictingOriginAndRefererDoNotAttributeWorkerRequest() {
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://publisher.example/article",
                "https://accounts.example",
                listOf(
                    context("https://publisher.example/article"),
                    context("https://accounts.example/login", authMode = true)
                )
            )
        )
    }

    @Test
    fun requestWithoutMatchingActiveBrowserContextPassesThrough() {
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://other.example/page",
                null,
                listOf(context("https://youtube.com/home"))
            )
        )
    }

    @Test
    fun matchingAuthenticationWebViewIsNeverFiltered() {
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://accounts.example/session",
                null,
                listOf(context("https://accounts.example/login", authMode = true))
            )
        )
    }

    @Test
    fun authenticationContextMakesAmbiguousSharedOriginPassThrough() {
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://accounts.example/session",
                null,
                listOf(
                    context("https://accounts.example/login"),
                    context("https://accounts.example/continue", authMode = true)
                )
            )
        )
    }

    @Test
    fun schemeAndPortArePartOfTheOrigin() {
        val contexts = listOf(context("https://youtube.com:8443/watch"))
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://youtube.com/watch",
                null,
                contexts
            )
        )
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                "http://youtube.com:8443/watch",
                null,
                contexts
            )
        )
    }

    @Test
    fun nonHttpOriginHeadersAreIgnored() {
        assertNull(BrowserServiceWorkerInterceptor.originOf("data:text/plain,fixture"))
        assertNull(BrowserServiceWorkerInterceptor.originOf("null"))
        assertNull(
            BrowserServiceWorkerInterceptor.selectPageContext(
                null,
                "null",
                listOf(context("https://youtube.com/home"))
            )
        )
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertEquals(
            "https://WWW.YouTube.Com/watch",
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://WWW.YouTube.Com/watch",
                null,
                listOf(context("https://www.youtube.com/home"))
            )
        )
    }

    @Test
    fun defaultPortsAreNormalizedAsSameOrigin() {
        assertEquals(
            "https://youtube.com:443/watch",
            BrowserServiceWorkerInterceptor.selectPageContext(
                "https://youtube.com:443/watch",
                null,
                listOf(context("https://youtube.com/home"))
            )
        )
        assertEquals("http://youtube.com", BrowserServiceWorkerInterceptor.originOf("http://youtube.com:80"))
    }

    private fun context(url: String, authMode: Boolean = false) =
        BrowserServiceWorkerInterceptor.PageContext(url, authMode)
}
