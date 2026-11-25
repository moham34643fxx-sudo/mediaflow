package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

internal data class VideoDimensionProgress(
    val media:Media,val pendingIds:Set<String> = emptySet(),val failedIds:Set<String> = emptySet()
)

/** Unknown SD/HD is not a resolution. Inspect a bounded number of real headers,
 * never a full video, before declaring that no source fits a numbered cap. */
internal object VideoDimensionDiscovery {
    fun needed(media:Media)=candidates(media).isNotEmpty()
    internal fun pendingIds(media:Media,onlyIds:Set<String>?=null)=candidates(media,onlyIds).map {it.id}.toSet()
    private fun candidates(media:Media,onlyIds:Set<String>?=null)=media.formats.asSequence()
        .filter {(onlyIds==null||it.id in onlyIds)&&it.video&&verifiedQualityEdge(it)==null&&
            FormatAvailability.eligible(it)&&it.protocol=="https"&&it.ext in listOf("mp4","webm")}
        .distinctBy {it.id}.take(2).toList()
    internal fun merge(original:Format,measured:Format):Format? {
        if(original.image||!measured.video||verifiedQualityEdge(measured)==null) return null
        val data=JSONObject(original.payload)
            .put("width",measured.width).put("height",measured.height)
            .put("vcodec",measured.videoCodec).put("acodec",measured.audioCodec)
            .put("mediaflow_dimensions","measured_header")
        return Format.parse(data)
    }

    internal fun measuredPrefix(original:Format,prefix:ByteArray):Format? {
        if(prefix.size<12 || String(prefix,4,4,Charsets.US_ASCII)!="ftyp") return null
        val header=Mp4Header.parse(prefix)
        if(!header.complete || header.width==null || header.height==null ||
            header.videoCodec !in setOf("avc1","avc3","hvc1","hev1","vp09","av01")) return null
        val payload=JSONObject(original.payload).put("width",header.width).put("height",header.height)
            .put("vcodec",header.videoCodec).put("acodec",header.audioCodec ?: "none")
            .put("mediaflow_dimensions","measured_header")
        return Format.parse(payload).takeIf {verifiedQualityEdge(it)!=null}
    }

    suspend fun enrich(media:Media,trace:String,resolver:DirectMediaResolver,onlyIds:Set<String>?,
        onProgress:(VideoDimensionProgress)->Unit,sourcePrefix:suspend (Format,String)->ByteArray):VideoDimensionProgress {
        val began=android.os.SystemClock.elapsedRealtime()
        val ids=pendingIds(media,onlyIds)
        if(ids.isEmpty()) return VideoDimensionProgress(media)
        val result=inspect(media,ids,onProgress=onProgress) {original->
            val payload=JSONObject(original.payload)
            // Scoped sessions must use the same cookie-aware transport as transfer.
            if(payload.optString("cookies").isNotBlank()&&original.ext=="mp4") {
                val prefix=sourcePrefix(original,"$trace-header-${java.util.UUID.randomUUID()}")
                measuredPrefix(original,prefix)?.let {return@inspect it}
            }
            val headers=payload.optJSONObject("http_headers")?.let {h->h.keys().asSequence().take(32).associateWith {h.optString(it)}}.orEmpty()
            resolver.resolve(payload.getString("url"),trace,allowUnknownExtension=true,sourceHeaders=headers)
                ?.formats?.firstOrNull {it.video}?.let {merge(original,it)}
        }
        NetworkDiagnostics.record("analysis","video_dimensions_inspected",null,operation=trace,
            details=JSONObject().put("candidates",ids.size).put("confirmed",ids.size-result.failedIds.size)
                .put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-began))
        return result
    }

    /** Merge each completed check in input order, so the last response cannot erase an earlier one. */
    internal suspend fun inspect(media:Media,onlyIds:Set<String>,timeoutMillis:Long=8_000,
        onProgress:(VideoDimensionProgress)->Unit={},measure:suspend (Format)->Format?):VideoDimensionProgress {
        val sources=candidates(media,onlyIds)
        val pending=sources.mapTo(linkedSetOf()) {it.id}
        val failed=mutableSetOf<String>()
        val measured=mutableMapOf<String,Format>()
        fun snapshot()=if(measured.isEmpty()) media else media.copy(formats=media.formats.map {measured[it.id] ?: it})
        withTimeoutOrNull(timeoutMillis) {coroutineScope {
            val completed=Channel<Pair<String,Format?>>(sources.size)
            sources.forEach {source->launch {
                val result=try {measure(source)}
                    catch(error:CancellationException) {ensureActive();null}
                    catch(_:Exception) {null}
                completed.send(source.id to result)
            }}
            repeat(sources.size) {
                val (id,result)=completed.receive()
                pending.remove(id)
                if(result==null) failed+=id else measured[id]=result
                if(pending.isNotEmpty()) onProgress(VideoDimensionProgress(snapshot(),pending.toSet(),failed.toSet()))
            }
        }}
        return VideoDimensionProgress(snapshot(),failedIds=failed+pending)
    }
}
