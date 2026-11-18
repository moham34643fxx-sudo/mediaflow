package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded HTTP/header resolver: never downloads a complete file just to determine size. */
internal class DirectMediaResolver(private val suppliedClient:okhttp3.OkHttpClient?=null) {
    private val agent="Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val client by lazy { suppliedClient ?: Transfer.networkClient.newBuilder().followRedirects(false).callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build() }
    fun hasMediaExtension(url: String): Boolean {
        return try {
            val extension = URI(url).path.substringAfterLast('.').lowercase()
            extension in listOf("mp4", "m4a", "webm", "jpg", "jpeg", "png", "webp", "mp3", "ogg", "wav", "flac")
        } catch (_: Exception) {
            false
        }
    }
    suspend fun resolve(url: String,traceId:String=java.util.UUID.randomUUID().toString(),allowUnknownExtension:Boolean=false,sourceHeaders:Map<String,String> = emptyMap()): Media? = coroutineScope {
        val extension=URI(url).path.substringAfterLast('.').lowercase()
        if(!allowUnknownExtension&&extension !in listOf("mp4","m4a","webm","jpg","jpeg","png","webp")) return@coroutineScope null
        var target=url
        val publicHeaders=sourceHeaders.filter { (name,value)->
            name.lowercase(java.util.Locale.ROOT) in setOf("user-agent","referer","origin","accept","accept-language")&&
                value.length<=2048&&value.none {it=='\r'||it=='\n'}
        }.entries.take(16).associate {it.key.lowercase(java.util.Locale.ROOT) to it.value}
        fun request(target:String,range:String):Request {
            val builder=Request.Builder().url(UrlPolicy.normalize(target)).tag(NetworkDiagnostics.Trace::class.java,NetworkDiagnostics.Trace(traceId))
                .header("User-Agent",agent)
            publicHeaders.forEach { (key,value)->builder.header(key,value) }
            return builder.header("Range",range).header("Accept-Encoding","identity").build()
        }
        fun retainedHeaders()=JSONObject().put("user-agent",agent).apply {publicHeaders.forEach {(key,value)->put(key,value)}}
        repeat(6) {
            val call=client.newCall(request(target,"bytes=0-65535"))
            val watcher=launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                awaitResponse(call).use { response ->
                    android.util.Log.d("ZAnalysis","direct_http_code=${response.code}, type=${response.header("Content-Type")}")
                    if(response.code in listOf(301,302,303,307,308)) {
                        target=UrlPolicy.normalize(response.request.url.resolve(response.header("Location") ?: return@coroutineScope null)?.toString() ?: return@coroutineScope null)
                    } else {
                        NetworkDiagnostics.httpError(response.code)?.let {throw MediaException(it)}
                        if(response.code !in listOf(200,206) || response.header("Content-Encoding")?.let { it!="identity" }==true) return@coroutineScope null
                        val body=response.body ?: return@coroutineScope null
                        val prefix=try { body.byteStream().readNBytesCompat(65536) } finally { if(response.code==200) call.cancel() }
                        val range=ContentRange.parse(response.header("Content-Range"))
                        if(response.code==206 && (range==null || range.start!=0L)) return@coroutineScope null
                         val length=if(response.code==206) range?.total else body.contentLength().takeIf { it>0 }
                         val imageExtension=MediaFileSignature.imageExtension(prefix)
                         if(imageExtension!=null) {
                             val dimensions=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                             android.graphics.BitmapFactory.decodeByteArray(prefix,0,prefix.size,dimensions)
                             if(dimensions.outWidth !in 1..32768||dimensions.outHeight !in 1..32768) return@coroutineScope null
                             val format=JSONObject().put("format_id","original-image").put("url",target).put("ext",imageExtension)
                                 .put("protocol","https").put("http_headers",retainedHeaders()).put("filesize",length)
                                 .put("width",dimensions.outWidth).put("height",dimensions.outHeight)
                                 .put("vcodec","none").put("acodec","none").put("mediaflow_image","original")
                             val name=URI(url).path.substringAfterLast('/').substringBeforeLast('.').ifBlank {"Image"}
                             return@coroutineScope Media.parse(JSONObject().put("id",url).put("title",name)
                                 .put("formats",JSONArray().put(format)),url)
                         }
                        val webm=prefix.size>=4&&prefix.take(4).map {it.toInt() and 255}==listOf(0x1a,0x45,0xdf,0xa3)
                        if(webm) {
                            val header=WebmHeader.parse(prefix) ?: return@coroutineScope null
                            val format=JSONObject().put("format_id","direct").put("url",target).put("ext","webm").put("protocol","https")
                                .put("http_headers",retainedHeaders()).put("filesize",length)
                                .put("width",header.width).put("height",header.height)
                                .put("vcodec",header.videoCodec ?: "none").put("acodec",header.audioCodec ?: "none")
                            val name=URI(url).path.substringAfterLast('/').substringBeforeLast('.').ifBlank {"Media"}
                            return@coroutineScope Media.parse(JSONObject().put("id",url).put("title",name).put("duration",header.duration)
                                .put("formats",JSONArray().put(format)),url)
                        }
                        if(prefix.size<12 || String(prefix,4,4,Charsets.US_ASCII)!="ftyp") return@coroutineScope null
                        var header=Mp4Header.parse(prefix)
                        if(!header.complete && length!=null && length>prefix.size) {
                            // Walk real top-level box offsets, jumping over mdat. A
                            // random tail starts inside media data, not at a box boundary.
                            suspend fun range(start:Long,count:Int):ByteArray? = coroutineScope {
                                val request=client.newCall(request(target,"bytes=$start-${start+count-1}"))
                                val watch=launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {request.cancel()}}
                                try {awaitResponse(request).use {r->if(r.code!=206 || ContentRange.parse(r.header("Content-Range"))?.start!=start || r.header("Content-Encoding")?.let {it!="identity"}==true) null else r.body?.byteStream()?.use {it.readNBytesCompat(count)}}} finally {watch.cancel()}
                            }
                            var position=0L
                            var inspected=0
                            while(inspected++<16&&position+8<=length&&!header.complete) {
                                // Read a bounded useful prefix at a real box boundary.
                                // A small tail moov then needs one range, not a
                                // header-only request followed by the same box again.
                                val box=if(position+16<=prefix.size) prefix.copyOfRange(position.toInt(),prefix.size)
                                    else range(position,minOf(65536L,length-position).toInt()) ?: break
                                if(box.size<8) break
                                val b=ByteBuffer.wrap(box).order(ByteOrder.BIG_ENDIAN)
                                var size=b.int.toLong() and 0xffffffffL
                                val type=String(box,4,4,Charsets.US_ASCII)
                                if(size==1L) {if(box.size<16) break;size=b.getLong(8)}
                                if(size==0L) size=length-position
                                if(size<8 || size>length-position) break
                                if(type=="moov" && size<=512*1024) {
                                    val data=if(size<=box.size) box.copyOfRange(0,size.toInt()) else range(position,size.toInt()) ?: break
                                    header=Mp4Header.parse(data)
                                }
                                position+=size
                            }
                        }
                        if(!header.complete || header.duration==null || header.videoCodec==null && header.audioCodec==null) return@coroutineScope null
                        if(header.videoCodec=="encv"||header.audioCodec=="enca") return@coroutineScope null
                        val audioOnly=header.videoCodec==null&&header.audioCodec!=null
                        val format=JSONObject().put("format_id","direct").put("url",target).put("ext",if(audioOnly) "m4a" else "mp4").put("protocol","https")
                            .put("http_headers",retainedHeaders())
                            .put("filesize",length).put("height",header.height).put("width",header.width)
                            .put("vcodec",if(audioOnly) "none" else header.videoCodec)
                            .put("acodec",header.audioCodec ?: if(header.complete && !audioOnly) "none" else null)
                        val name=URI(url).path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Media" }
                        return@coroutineScope Media.parse(JSONObject().put("id",url).put("title",name).put("source",URI(url).host).put("duration",header.duration).put("formats",JSONArray().put(format)),url)
                    }
                }
            } finally { watcher.cancel() }
        }
        null
    }
}
internal fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(output.size()<limit) { val n=read(buffer,0,minOf(buffer.size,limit-output.size()));if(n<0) break;output.write(buffer,0,n) }
    return output.toByteArray()
}
internal data class Mp4Header(val width:Int?=null,val height:Int?=null,val duration:Double?=null,val videoCodec:String?=null,val audioCodec:String?=null,val complete:Boolean=false) {
    companion object {
        fun parse(bytes:ByteArray):Mp4Header {
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun boxes(start:Int,end:Int):List<Triple<String,Int,Int>> {
                val result=mutableListOf<Triple<String,Int,Int>>();var p=start
                while(p+8<=end) {
                    var size=b.getInt(p).toLong() and 0xffffffffL
                    var headerLen = 8
                    if(size==1L) {
                        // 64-bit largesize; requires 16-byte header.
                        if(p+16>end) break
                        size = b.getLong(p+8)
                        headerLen = 16
                        if(size<16 || size>end-p) break
                    } else if(size==0L) {
                        // Size 0 means to end of box parent.
                        size = (end-p).toLong()
                    }
                    if(size<8 || size>end-p || size>Int.MAX_VALUE) break
                    // Box type must be printable ASCII to reject spoofed moov signatures inside mdat.
                    val type = String(bytes,p+4,4,Charsets.US_ASCII)
                    if(type.any { it.code !in 32..126 }) break
                    result+=Triple(type,p+headerLen,p+size.toInt());p+=size.toInt()
                }
                return result
            }
            // Strict top-level moov only; never scan raw bytes for "moov" inside media data.
            val moov=boxes(0,bytes.size).firstOrNull { it.first=="moov" } ?: return Mp4Header()
            var width:Int?=null;var height:Int?=null;var duration:Double?=null;var video:String?=null;var audio:String?=null
            for(box in boxes(moov.second,moov.third)) {
                if(box.first=="mvhd" && box.third-box.second>=24) {
                    val p=box.second;val version=bytes[p].toInt();val scale=if(version==1 && box.third-p>=32) b.getInt(p+20).toLong() and 0xffffffffL else b.getInt(p+12).toLong() and 0xffffffffL
                    val time=if(version==1 && box.third-p>=32) b.getLong(p+24) else b.getInt(p+16).toLong() and 0xffffffffL
                    if(scale>0 && time>0) duration=time.toDouble()/scale
                }
                if(box.first!="trak") continue
                val track=boxes(box.second,box.third)
                track.firstOrNull { it.first=="tkhd" && it.third-it.second>=80 }?.let { tk -> val w=b.getInt(tk.third-8) ushr 16;val h=b.getInt(tk.third-4) ushr 16;if(w>0&&h>0) {width=w;height=h} }
                val mdia=track.firstOrNull { it.first=="mdia" } ?: continue
                val fields=boxes(mdia.second,mdia.third)
                val hdlr=fields.firstOrNull { it.first=="hdlr" && it.third-it.second>=12 } ?: continue
                val handler=String(bytes,hdlr.second+8,4,Charsets.US_ASCII)
                val minf=fields.firstOrNull { it.first=="minf" }
                val stbl=minf?.let { boxes(it.second,it.third).firstOrNull { child -> child.first=="stbl" } }
                val stsd=stbl?.let { boxes(it.second,it.third).firstOrNull { child -> child.first=="stsd" && child.third-child.second>=16 } }
                val codec=stsd?.takeIf { it.second+16 <= it.third && it.second+12+4 <= bytes.size }?.let {
                    String(bytes,it.second+12,4,Charsets.US_ASCII).takeIf { name -> name.all { c -> c.code in 32..126 } }
                } ?: "unknown"
                if(handler=="vide") {
                    video=codec
                    // tkhd describes display geometry (e.g. anamorphic scaling),
                    // whereas stsd's visual sample entry carries encoded pixels.
                    if(stsd!=null&&stsd.third-stsd.second>=44) {
                        val w=b.getShort(stsd.second+40).toInt() and 0xffff
                        val h=b.getShort(stsd.second+42).toInt() and 0xffff
                        if(w>0&&h>0) {width=w;height=h}
                    }
                }
                if(handler=="soun") audio=codec
            }
            return Mp4Header(width,height,duration,video,audio,duration!=null && (video!=null || audio!=null))
        }
    }
}
