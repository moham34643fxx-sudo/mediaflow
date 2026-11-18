package com.zdownloader.app.data

import android.text.Html
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.awaitResponse
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

data class ClipboardPreview(val title:String?=null,val imageUrl:String?=null,val artwork:ByteArray?=null)

/** Metadata only: never invoke extraction, inspect a video, or enqueue a transfer. */
internal object ClipboardPreviewLoader {
    private const val MAX_HTML=192*1024
    private val client by lazy {Transfer.networkClient.newBuilder().callTimeout(8,TimeUnit.SECONDS)
        .connectTimeout(4,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS).build()}
    private val mediaExtensions=setOf("mp4","m4v","webm","mkv","mov","avi","flv","wmv","3gp","ogv","ts","m2ts","m4s",
        "mp3","m4a","aac","flac","opus","ogg","oga","wav","aiff","wma","m3u8","mpd","jpg","jpeg","png","webp","gif","avif","heic","bmp","tiff")

    suspend fun metadata(url:String,http:OkHttpClient?=null):ClipboardPreview=withContext(Dispatchers.IO) {
        val safe=UrlPolicy.normalize(url).toHttpUrl()
        if(safe.pathSegments.lastOrNull()?.substringAfterLast('.')?.lowercase() in mediaExtensions) return@withContext ClipboardPreview()
        // YouTube's watch-page metadata follows a large script payload; oEmbed
        // provides just the title/artwork without reading beyond the HTML cap.
        val embedded=safe.host in setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be")
        val address=if(embedded) "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url",safe.toString()).addQueryParameter("format","json").build() else safe
        val request=Request.Builder().url(address).header("Accept",if(embedded) "application/json" else "text/html,application/xhtml+xml").build()
        readResponse(http ?: client,request) {response->
            if(embedded) metadataText(response,setOf("application/json"))?.let(ClipboardPreviewMetadata::embedded) ?: ClipboardPreview()
            else htmlPreview(response)
        }
    }

    private suspend fun htmlPreview(response:Response):ClipboardPreview {
        val html=metadataText(response,setOf("text/html","application/xhtml+xml")) ?: return ClipboardPreview()
        val metadata=ClipboardPreviewMetadata.parse(html,response.request.url.toString())
        return metadata.copy(title=metadata.title?.let {Html.fromHtml(it,Html.FROM_HTML_MODE_LEGACY).toString().trim().take(160)})
    }

    private suspend fun metadataText(response:Response,accepted:Set<String>):String? {
        if(!response.isSuccessful) return null
        val body=response.body ?: return null
        val type=body.contentType() ?: return null
        if("${type.type}/${type.subtype}" !in accepted) return null
        return String(limitedBytes(body,MAX_HTML),type.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    suspend fun artwork(url:String,http:OkHttpClient?=null):ByteArray=withContext(Dispatchers.IO) {
        readResponse(http ?: client,Request.Builder().url(UrlPolicy.normalize(url)).header("Accept","image/jpeg,image/png,image/webp").build()) {response->
            if(!response.isSuccessful) throw java.io.IOException("Preview image request rejected")
            val body=response.body ?: throw java.io.IOException("Preview image response is empty")
            require(body.contentType()?.type=="image"&&body.contentLength()<=ThumbnailData.MAX_BYTES)
            ThumbnailData.parse(limitedBytes(body,ThumbnailData.MAX_BYTES+1)).bytes
        }
    }

    private suspend fun limitedBytes(body:ResponseBody,limit:Int):ByteArray {
        val bytes=java.io.ByteArrayOutputStream()
        val source=body.byteStream();val buffer=ByteArray(8192)
        while(bytes.size()<limit) {
            currentCoroutineContext().ensureActive()
            val count=source.read(buffer,0,minOf(buffer.size,limit-bytes.size()))
            if(count<0) break
            bytes.write(buffer,0,count)
        }
        return bytes.toByteArray()
    }

    /** Keep cancellation attached during body reads, not just until headers arrive.
     * Cancel before close: rejected/truncated bodies must not be drained for connection reuse. */
    private suspend fun <T> readResponse(http:OkHttpClient,request:Request,read:suspend (Response)->T):T=coroutineScope {
        val call=http.newCall(request)
        val cancellation=launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
        var response:Response?=null
        try {
            response=awaitResponse(call)
            read(response)
        } finally {
            call.cancel()
            cancellation.cancel()
            response?.close()
        }
    }
}

internal object ClipboardPreviewMetadata {
    private val tags=Regex("<meta\\b[^>]{0,8192}>",RegexOption.IGNORE_CASE)
    private val attributes=Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val titleTag=Regex("<title\\b[^>]*>([\\s\\S]{0,1024}?)</title\\s*>",RegexOption.IGNORE_CASE)

    fun embedded(json:String):ClipboardPreview {
        val metadata=try {org.json.JSONObject(json)} catch(error:org.json.JSONException) {throw java.io.IOException("Invalid preview metadata",error)}
        val title=(metadata.opt("title") as? String)?.takeIf {it.isNotBlank()}?.replace(Regex("\\s+")," ")?.trim()?.take(160)
        return ClipboardPreview(title,safeImage(metadata.optString("thumbnail_url")))
    }

    private fun safeImage(url:String):String?=try {UrlPolicy.normalize(url)} catch(_:com.zdownloader.app.domain.MediaException) {null}

    fun parse(html:String,pageUrl:String):ClipboardPreview {
        val values=mutableMapOf<String,String>()
        tags.findAll(html.take(192*1024)).forEach {tag->
            val attrs=attributes.findAll(tag.value).associate {match->
                match.groupValues[1].lowercase() to match.groupValues.drop(2).firstOrNull {it.isNotEmpty()}.orEmpty()
            }
            val name=(attrs["property"] ?: attrs["name"])?.lowercase()
            if(name!=null) attrs["content"]?.takeIf {it.isNotBlank()}?.let {values.putIfAbsent(name,it)}
        }
        val title=(values["og:title"] ?: values["twitter:title"] ?: titleTag.find(html)?.groupValues?.get(1))
            ?.replace(Regex("\\s+")," ")?.trim()?.take(512)?.takeIf {it.isNotBlank()}
        val image=(values["og:image:secure_url"] ?: values["og:image"] ?: values["twitter:image"])
            ?.replace("&amp;","&")?.let {pageUrl.toHttpUrl().resolve(it)?.toString()}
            ?.let(::safeImage)
        return ClipboardPreview(title,image)
    }
}
