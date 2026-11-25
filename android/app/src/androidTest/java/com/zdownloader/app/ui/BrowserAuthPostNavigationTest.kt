package com.zdownloader.app.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserAuthPostNavigationTest {
    @Test fun disallowedPostMainFrameIsInterceptedBeforeItsBodyCanBeSent() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        var view:WebView?=null
        instrumentation.runOnMainSync {view=WebView(context)}
        val webView=requireNotNull(view)
        val client=browserNavigationClient(
            navigation={_,_->},
            pageError={_,_,_->},
            rendererGone={},
            requested={_,_->},
            mainFrameNavigationAllowed=FacebookAuthNavigationPolicy::allowsAuthNavigation,
            blockedMainFrameNavigation={_,_->}
        )

        try {
            val url="http://login.example.test/submit"
            val response=client.shouldInterceptRequest(webView,request(url,"POST",true))

            assertNotNull(response)
            assertEquals(403,requireNotNull(response).statusCode)
        } finally {
            instrumentation.runOnMainSync {webView.destroy()}
        }
    }

    @Test fun allowedExternalHttpsPostStillPassesThroughForIdentityProviderRedirects() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        var view:WebView?=null
        instrumentation.runOnMainSync {view=WebView(context)}
        val webView=requireNotNull(view)
        val client=browserNavigationClient(
            navigation={_,_->},
            pageError={_,_,_->},
            rendererGone={},
            requested={_,_->},
            mainFrameNavigationAllowed=FacebookAuthNavigationPolicy::allowsAuthNavigation
        )
        try {
            val response=client.shouldInterceptRequest(webView,request("https://identity.example.test/continue","POST",true))
            assertNull(response)
        } finally {
            instrumentation.runOnMainSync {webView.destroy()}
        }
    }

    private fun request(url:String,method:String,mainFrame:Boolean)=object:WebResourceRequest {
        override fun getUrl():Uri=Uri.parse(url)
        override fun isForMainFrame():Boolean=mainFrame
        override fun isRedirect():Boolean=false
        override fun hasGesture():Boolean=true
        override fun getMethod():String=method
        override fun getRequestHeaders():Map<String,String> = emptyMap()
    }
}
