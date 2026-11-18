package com.zdownloader.app.ui

/** In-memory navigation only. Never replay an unknown method or a form body. */
internal class BrowserRetryState(initialUrl:String) {
    private var safeGet=initialUrl
    private var failedGet:String?=null
    var formNotResent=false
        private set
    val target:String get()=failedGet ?: safeGet
    fun requested(url:String,method:String) {
        if(method.equals("GET",true)&&http(url)) safeGet=url
    }
    fun failed(url:String,method:String) {
        failedGet=url.takeIf {method.equals("GET",true)&&http(it)}
        formNotResent=failedGet==null
    }
    fun rendererGone() {failedGet=null;formNotResent=true}
    fun retry() {safeGet=target;failedGet=null}
    private fun http(url:String)=try {java.net.URI(url).let {it.scheme in setOf("http","https")&&!it.host.isNullOrBlank()}} catch(_:java.net.URISyntaxException) {false}
}
