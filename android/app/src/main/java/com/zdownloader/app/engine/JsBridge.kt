package com.zdownloader.app.engine

import com.dokar.quickjs.quickJs
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.javascriptengine.JavaScriptException

/** JNI embedding: never execute a binary from Android's writable app directory. */
object JsBridge {
    @Volatile private var applicationContext:android.content.Context?=null
    internal fun initialize(context:android.content.Context) {applicationContext=context.applicationContext}
    private val runtimeVersion:String by lazy {runBlocking(Dispatchers.Default) {quickJs {version}}}
    @JvmStatic fun version(): String = runtimeVersion
    @JvmStatic fun run(script:String):String=runQuickJs(script,30_000)
    private fun runQuickJs(script: String,timeoutMillis:Long): String = runBlocking(Dispatchers.Default) {
        withTimeout(timeoutMillis) {
            var output = ""
            quickJs {
                // Full player ASTs exhausted 128 MiB; 192 MiB passed live challenge and download checks.
                memoryLimit = 192L * 1024 * 1024
                maxStackSize = 1024L * 1024
                evaluationTimeoutMillis = timeoutMillis
                define("console") {
                    function("log") { args -> output = args.joinToString(" "); Unit }
                    function("warn") { Unit }
                    function("error") { Unit }
                }
                evaluate<Any?>(script)
            }
            output
        }
    }

    /** Fresh, out-of-process V8 isolate when available; no player/request state survives a call. */
    @JvmStatic fun runEjs(script:String):String=runBlocking(Dispatchers.Default) {
        withTimeout(30_000) {
            val at=android.os.SystemClock.elapsedRealtime()
            val context=applicationContext
            var v8Failure:String?=null
            val sandboxOutput=try {
                if(android.os.Build.VERSION.SDK_INT>=26&&context!=null) IsolatedEjsRuntime.evaluate(context,script) else null
            } catch(error:JavaScriptException) {v8Failure=error.javaClass.simpleName;null}
            // Cancellation/timeout is not a failed engine choice: never restart cancelled work.
            sandboxOutput ?: run {
                val fallbackAt=android.os.SystemClock.elapsedRealtime()
                val remaining=30_000-(fallbackAt-at)
                if(remaining<=0) throw java.util.concurrent.TimeoutException("EJS evaluation deadline exceeded")
                val output=runQuickJs(script,remaining)
                org.json.JSONObject().put("output",output).put("bridge",org.json.JSONObject()
                    .put("engine","quickjs").put("evaluation_ms",android.os.SystemClock.elapsedRealtime()-fallbackAt)
                    .put("attempts",if(v8Failure==null) 1 else 2).put("v8_failure",v8Failure)
                    .put("selection_ms",fallbackAt-at)).toString()
            }
        }
    }

    @JvmStatic fun getFacebookCookiesJson(url: String): String {
        if (!FacebookScraper.isFacebookUrl(url)) return "[]"
        if (!FacebookSessionManager.hasActiveSession()) return "[]"
        val cm = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull() ?: return "[]"
        val result = org.json.JSONArray()
        listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com").forEach { host ->
            val cookieStr = runCatching { cm.getCookie("https://$host") }.getOrNull()
            if (!cookieStr.isNullOrBlank()) {
                cookieStr.split(";").forEach { part ->
                    val eq = part.indexOf('=')
                    if (eq > 0) {
                        val name = part.substring(0, eq).trim()
                        val value = part.substring(eq + 1).trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            result.put(org.json.JSONObject().put("name", name).put("value", value)
                                .put("domain", host).put("hostOnly", true))
                        }
                    }
                }
            }
        }
        return result.toString()
    }
}
