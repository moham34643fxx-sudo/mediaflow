package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test

class FacebookSessionManagerTest {

    @Test
    fun defaultStatusWithoutCookiesIsNotConnected() {
        FacebookSessionManager.logout(null)
        val status = FacebookSessionManager.checkStatus()
        assertEquals(FacebookSessionStatus.NOT_CONNECTED, status)
        assertFalse(FacebookSessionManager.hasActiveSession())
    }

    @Test
    fun sessionTransitionsToExpiredWhenMarked() {
        FacebookSessionManager.markSessionExpired()
        assertEquals(FacebookSessionStatus.SESSION_EXPIRED, FacebookSessionManager.status.value)
        assertTrue(FacebookSessionManager.isSessionExpired())
        assertFalse(FacebookSessionManager.hasActiveSession())

        FacebookSessionManager.logout(null)
        assertEquals(FacebookSessionStatus.NOT_CONNECTED, FacebookSessionManager.status.value)
        assertFalse(FacebookSessionManager.isSessionExpired())
        assertFalse(FacebookSessionManager.hasActiveSession())
    }

    @Test
    fun facebookScraperIdentifiesFacebookUrlsWithWhitespace() {
        assertTrue(FacebookScraper.isFacebookUrl(" https://www.facebook.com/reel/123456 \n"))
        assertTrue(FacebookScraper.isFacebookUrl("https://m.facebook.com/watch/?v=123"))
        assertTrue(FacebookScraper.isFacebookUrl("https://fb.watch/xyz123/"))
        assertFalse(FacebookScraper.isFacebookUrl("https://notfacebook.com/video"))
        assertFalse(FacebookScraper.isFacebookUrl("https://google.com"))
        assertFalse(FacebookScraper.isFacebookUrl(""))
    }
}
