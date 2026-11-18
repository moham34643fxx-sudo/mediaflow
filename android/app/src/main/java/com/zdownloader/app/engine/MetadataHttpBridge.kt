package com.zdownloader.app.engine

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Transport only: yt-dlp retains ownership of cookies, redirects and decompression. */
class MetadataHttpBridge internal constructor(private val client:OkHttpClient) : Closeable {
    constructor():this(metadataClient)
    private val closed=AtomicBoolean()
    private val calls=ConcurrentHashMap.newKeySet<Call>()

    fun execute(requestJson:String,payload:ByteArray?):MetadataHttpResponse {
        if(closed.get()) throw MetadataTransportException("DOWNLOAD_INTERRUPTED")
        val config=JSONObject(requestJson)
        val trace=NetworkDiagnostics.Trace(config.getString("operation"))
        val call=client.newCall(request(config,payload).newBuilder().tag(NetworkDiagnostics.Trace::class.java,trace).build())
        call.timeout().timeout(config.getLong("timeout_ms").coerceIn(1,85_000),TimeUnit.MILLISECONDS)
        calls.add(call)
        if(closed.get()) call.cancel()
        try {
            return call.execute().use {response->readResponse(response)}
        } catch(error:IOException) {
            runCatching { android.util.Log.e("ZAnalysis","MetadataHttpBridge failed url=${config.optString("url")} error=$error",error) }
            throw MetadataTransportException(if(closed.get()) "DOWNLOAD_INTERRUPTED" else
                if(error is java.io.InterruptedIOException && error !is java.net.SocketTimeoutException)
                    if(trace.stage=="tls") "TLS_TIMEOUT" else "READ_TIMEOUT" else
                if(error is MetadataTransportException) error.code else NetworkDiagnostics.classify(error,trace.stage).name).apply {initCause(error)}
        } finally {calls.remove(call)}
    }

    private fun request(config:JSONObject,payload:ByteArray?):Request {
        val request=Request.Builder().url(config.getString("url"))
        val headers=config.getJSONArray("headers")
        for(index in 0 until headers.length()) {
            val pair=headers.getJSONArray(index)
            val name=pair.getString(0)
            if(name.lowercase() !in setOf("connection","host","content-length","transfer-encoding"))
                request.addHeader(name,pair.getString(1))
        }
        // An explicit encoding prevents OkHttp from transparently decoding bytes a second time.
        if(request.build().header("Accept-Encoding")==null) request.header("Accept-Encoding","identity")
        val method=config.getString("method")
        val body=payload?.toRequestBody(null) ?: if(method in setOf("POST","PUT","PATCH","PROPPATCH","REPORT")) ByteArray(0).toRequestBody(null) else null
        return request.method(method,body).build()
    }

    private fun readResponse(response:okhttp3.Response):MetadataHttpResponse {
        val encoded=JSONArray()
        for(index in 0 until response.headers.size)
            encoded.put(JSONArray().put(response.headers.name(index)).put(response.headers.value(index)))
        val bytes=ByteArrayOutputStream()
        response.body?.byteStream()?.use {input->
            val buffer=ByteArray(32*1024)
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                if(bytes.size()+count>MAX_RESPONSE) throw MetadataTransportException("METADATA_LIMIT")
                bytes.write(buffer,0,count)
            }
        }
        return MetadataHttpResponse(response.code,response.message,encoded.toString(),bytes.toByteArray())
    }

    override fun close() {closed.set(true);calls.forEach {it.cancel()}}

    companion object {
        private const val MAX_RESPONSE=8*1024*1024
        private val metadataClient by lazy {
            Transfer.networkClient.newBuilder().followRedirects(false).followSslRedirects(false)
                // Do not introduce invisible replays, particularly for extractor POST requests.
                .retryOnConnectionFailure(false).build()
        }
    }
}

class MetadataHttpResponse(val status:Int,val reason:String,val headersJson:String,val body:ByteArray)
class MetadataTransportException(val code:String):IOException(code)
