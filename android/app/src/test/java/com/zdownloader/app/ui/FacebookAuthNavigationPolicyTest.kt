package com.zdownloader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookAuthNavigationPolicyTest {
    @Test fun onlyTheExistingExactHttpsFacebookHostsAreTrustedForSessionCompletion() {
        for(host in listOf("facebook.com","www.facebook.com","m.facebook.com","web.facebook.com")) {
            val url="https://$host/login/"
            assertEquals(url,host,FacebookAuthNavigationPolicy.inspect(url).host)
            assertEquals(url,FacebookAuthOriginKind.FACEBOOK_HTTPS,FacebookAuthNavigationPolicy.inspect(url).kind)
            assertTrue(url,FacebookAuthNavigationPolicy.isFacebookAuthOrigin(url))
            assertTrue(url,FacebookAuthNavigationPolicy.allowsAuthNavigation(url))
        }
        for(url in listOf(
            "https://evil.facebook.com/login/",
            "https://facebook.com.attacker.test/login/",
            "https://notfacebook.com/login/",
            "http://www.facebook.com/login/"
        )) {
            assertFalse(url,FacebookAuthNavigationPolicy.isFacebookAuthOrigin(url))
        }
    }

    @Test fun secureExternalLoginRedirectsRemainPossibleButAreClearlyClassified() {
        val url="https://login.identity-provider.test/authorize"
        val origin=FacebookAuthNavigationPolicy.inspect(url)

        assertEquals("login.identity-provider.test",origin.host)
        assertEquals(FacebookAuthOriginKind.EXTERNAL_HTTPS,origin.kind)
        assertTrue(FacebookAuthNavigationPolicy.allowsAuthNavigation(url))
        assertFalse(FacebookAuthNavigationPolicy.isFacebookAuthOrigin(url))
    }

    @Test fun authModeRejectsCleartextCredentialsAndMalformedOrNonstandardOrigins() {
        for(url in listOf(
            "http://m.facebook.com/login/",
            "http://login.identity-provider.test/authorize",
            "https://www.facebook.com:444/login/",
            "https://user:pass@www.facebook.com/login/",
            "https://www.facebook.com:/login/",
            "javascript:alert(1)",
            "file:///data/local/tmp/login.html",
            "not a URL",
            "https://www.facebook.com/"+"x".repeat(8193)
        )) {
            assertFalse(url,FacebookAuthNavigationPolicy.allowsAuthNavigation(url))
        }
    }

    @Test fun lookalikeAndNestedHostsArePresentedAsExternalNotFacebook() {
        for(url in listOf(
            "https://www.facebook.com.attacker.test/login/",
            "https://evil.facebook.com/login/",
            "https://facebook.com.evil/login/"
        )) {
            assertEquals(url,FacebookAuthOriginKind.EXTERNAL_HTTPS,
                FacebookAuthNavigationPolicy.inspect(url).kind)
            assertFalse(url,FacebookAuthNavigationPolicy.isFacebookAuthOrigin(url))
        }
    }
}
