package com.zdownloader.app.ui

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.webkit.*
import android.widget.FrameLayout

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
        removeView(view)
        if(!rendererGone) runCatching {
            view.stopLoading()
            view.onPause()
            view.webChromeClient=null
            view.webViewClient=WebViewClient()
            view.removeAllViews()
        }
        runCatching {view.destroy()}
    }
}

internal open class BrowserNavigationClient(
    private val navigation:(WebView,String)->Unit,
    private val pageError:(WebView,String,String)->Unit,
    private val requested:(String,String)->Unit
):WebViewClient() {
    override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
        val scheme=request?.url?.scheme?.lowercase(java.util.Locale.ROOT)
        if(request?.isForMainFrame==true&&scheme in setOf("http","https")) requested(request.url.toString(),request.method)
        return scheme !in setOf("http","https")
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
    private val rendererGone:(WebView)->Unit,requested:(String,String)->Unit
):BrowserNavigationClient(navigation,pageError,requested) {
    override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
        rendererGone(view)
        return true
    }
}

internal fun browserNavigationClient(navigation:(WebView,String)->Unit,pageError:(WebView,String,String)->Unit,rendererGone:(WebView)->Unit,requested:(String,String)->Unit):WebViewClient =
    if(Build.VERSION.SDK_INT>=26) RecoveringBrowserClient(navigation,pageError,rendererGone,requested)
    else BrowserNavigationClient(navigation,pageError,requested)
