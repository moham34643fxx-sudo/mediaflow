package com.zdownloader.app.ui

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.webkit.*
import android.widget.FrameLayout
import com.zdownloader.app.adblock.BrowserCosmeticBridge
import com.zdownloader.app.adblock.BrowserServiceWorkerInterceptor

/** Own exactly one provider instance; renderer death and Compose release are idempotent. */
internal class BrowserWebViewHost(context:Context):FrameLayout(context) {
    var browser:WebView?=null
        private set
    fun attach(view:WebView) {
        check(browser==null)
        browser=view
        addView(view,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
    }
    fun release(rendererGone:Boolean=false) {
        val view=browser ?: return
        browser=null
        BrowserServiceWorkerInterceptor.unregisterWebView(view)
        removeView(view)
        if(!rendererGone) runCatching {
            view.stopLoading()
            view.onPause()
            view.webChromeClient=null
            view.webViewClient=WebViewClient()
            view.removeJavascriptInterface(BrowserCosmeticBridge.NAME)
            view.removeAllViews()
        }
        runCatching {view.destroy()}
    }
}

internal open class BrowserNavigationClient(
    private val navigation:(WebView,String)->Unit,
    private val pageError:(WebView,String,String)->Unit,
    private val requested:(String,String)->Unit,
    private val pageStarted:(WebView,String)->Unit,
    private val intercept:(WebResourceRequest)->WebResourceResponse?,
    private val mainFrameNavigationAllowed:(String)->Boolean={true},
    private val blockedMainFrameNavigation:(WebView?,String)->Unit={_,_->},
    private val blockMainFrameRedirect:(WebResourceRequest)->Boolean={false}
):WebViewClient() {
    override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
        if(request==null) return true
        val url=request.url.toString()
        val scheme=request.url.scheme?.lowercase(java.util.Locale.ROOT)
        if(scheme !in setOf("http","https")) {
            if(request.isForMainFrame) blockedMainFrameNavigation(view,url)
            return true
        }
        if(request.isForMainFrame) {
            if(!mainFrameNavigationAllowed(url)) {
                blockedMainFrameNavigation(view,url)
                return true
            }
            if(request.isRedirect&&blockMainFrameRedirect(request)) return true
            requested(url,request.method)
        }
        return false
    }
    override fun onPageStarted(view:WebView?,url:String?,favicon:android.graphics.Bitmap?) {
        if(view!=null&&url!=null) pageStarted(view,url)
    }
    override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse? {
        if(view==null||request==null) return null
        if(request.isForMainFrame) {
            val url=request.url.toString()
            if(!mainFrameNavigationAllowed(url)) {
                // shouldOverrideUrlLoading is not invoked for POST navigations.
                // Intercept this disallowed request before it can send a form
                // body; onPageStarted remains a secondary navigation check.
                view.post {blockedMainFrameNavigation(view,url)}
                return WebResourceResponse("text/plain","utf-8",403,"Blocked by MediaFlow",
                    emptyMap(),java.io.ByteArrayInputStream(byteArrayOf()))
            }
        }
        return intercept(request)
    }
    override fun onPageFinished(view:WebView?,url:String?) {if(view!=null&&url!=null) navigation(view,url)}
    override fun doUpdateVisitedHistory(view:WebView?,url:String?,isReload:Boolean) {if(view!=null&&url!=null) navigation(view,url)}
    override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:WebResourceError?) {
        if(request?.isForMainFrame==true&&view!=null) pageError(view,request.url.toString(),request.method)
    }
    override fun onReceivedHttpError(view:WebView?,request:WebResourceRequest?,response:WebResourceResponse?) {
        if(request?.isForMainFrame==true&&view!=null&&(response?.statusCode ?: 0)>=500) pageError(view,request.url.toString(),request.method)
    }
    override fun onFormResubmission(view:WebView?,dontResend:android.os.Message,resend:android.os.Message) {dontResend.sendToTarget()}
    // SSL errors deliberately retain WebView's default cancel behavior.
}

@TargetApi(26)
private class RecoveringBrowserClient(
    navigation:(WebView,String)->Unit,pageError:(WebView,String,String)->Unit,
    private val rendererGone:(WebView)->Unit,requested:(String,String)->Unit,
    pageStarted:(WebView,String)->Unit,
    intercept:(WebResourceRequest)->WebResourceResponse?,
    mainFrameNavigationAllowed:(String)->Boolean,
    blockedMainFrameNavigation:(WebView?,String)->Unit,
    blockMainFrameRedirect:(WebResourceRequest)->Boolean
):BrowserNavigationClient(navigation,pageError,requested,pageStarted,intercept,
    mainFrameNavigationAllowed,blockedMainFrameNavigation,blockMainFrameRedirect) {
    override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
        rendererGone(view)
        return true
    }
}

internal fun browserNavigationClient(
    navigation:(WebView,String)->Unit,
    pageError:(WebView,String,String)->Unit,
    rendererGone:(WebView)->Unit,
    requested:(String,String)->Unit,
    pageStarted:(WebView,String)->Unit={_,_->},
    intercept:(WebResourceRequest)->WebResourceResponse?={_->null},
    mainFrameNavigationAllowed:(String)->Boolean={true},
    blockedMainFrameNavigation:(WebView?,String)->Unit={_,_->},
    blockMainFrameRedirect:(WebResourceRequest)->Boolean={false}
):WebViewClient =
    if(Build.VERSION.SDK_INT>=26) RecoveringBrowserClient(navigation,pageError,rendererGone,requested,pageStarted,intercept,
        mainFrameNavigationAllowed,blockedMainFrameNavigation,blockMainFrameRedirect)
    else BrowserNavigationClient(navigation,pageError,requested,pageStarted,intercept,
        mainFrameNavigationAllowed,blockedMainFrameNavigation,blockMainFrameRedirect)

internal fun configureBrowserWebViewSecurity(webView:WebView,authMode:Boolean) {
    webView.settings.mixedContentMode=if(authMode) WebSettings.MIXED_CONTENT_NEVER_ALLOW
        else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    webView.settings.allowFileAccess=!authMode
    webView.settings.allowContentAccess=!authMode
    webView.settings.allowFileAccessFromFileURLs=!authMode
    webView.settings.allowUniversalAccessFromFileURLs=!authMode
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView,!authMode)
}
