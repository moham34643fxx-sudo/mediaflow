package com.zdownloader.app.ui

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewSecurityPolicyTest {
    @Test fun authModeDisablesThirdPartyCookiesAndMixedContentWithoutChangingNormalBrowsing() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        var authView:WebView?=null
        var normalView:WebView?=null
        try {
            instrumentation.runOnMainSync {
                authView=WebView(context)
                normalView=WebView(context)
                configureBrowserWebViewSecurity(requireNotNull(authView),authMode=true)
                configureBrowserWebViewSecurity(requireNotNull(normalView),authMode=false)

                val cookies=CookieManager.getInstance()
                assertFalse(cookies.acceptThirdPartyCookies(requireNotNull(authView)))
                assertTrue(cookies.acceptThirdPartyCookies(requireNotNull(normalView)))
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,requireNotNull(authView).settings.mixedContentMode)
                assertEquals(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE,requireNotNull(normalView).settings.mixedContentMode)
                assertFalse(requireNotNull(authView).settings.allowFileAccess)
                assertFalse(requireNotNull(authView).settings.allowContentAccess)
                assertFalse(requireNotNull(authView).settings.allowFileAccessFromFileURLs)
                assertFalse(requireNotNull(authView).settings.allowUniversalAccessFromFileURLs)
                assertTrue(requireNotNull(normalView).settings.allowFileAccess)
                assertTrue(requireNotNull(normalView).settings.allowContentAccess)
            }
        } finally {
            instrumentation.runOnMainSync {
                authView?.destroy()
                normalView?.destroy()
            }
        }
    }
}
