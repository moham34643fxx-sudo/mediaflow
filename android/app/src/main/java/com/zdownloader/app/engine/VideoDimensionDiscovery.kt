package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Unknown SD/HD is not a resolution. Inspect a bounded number of real headers,
 * never a full video, before declaring that no source fits a numbered cap. */
internal object VideoDimensionDiscovery {
    fun needed(media:Media)=candidates(media).isNotEmpty()
    internal fun pendingIds(media:Media)=candidates(media).map {it.id}.toSet()
    private fun candidates(media:Media)=media.formats.filter {it.video&&verifiedQualityEdge(it)==null&&FormatAvailability.eligible(it)&&it.protocol=="https"&&it.ext in listOf("mp4","webm")}.take(2)
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

    suspend fun enrich(media:Media,trace:String,resolver:DirectMediaResolver,sourcePrefix:suspend (Format,String)->ByteArray):Media {
        val candidates=candidates(media)
        if(candidates.isEmpty()) return media
        val found=ConcurrentHashMap<String,Format>()
        val began=android.os.SystemClock.elapsedRealtime()
        withTimeoutOrNull(8_000) {coroutineScope {
            candidates.forEach {original->launch {
                try {
                    val payload=JSONObject(original.payload)
                    // Scoped sessions must use the same cookie-aware transport as transfer.
                    if(payload.optString("cookies").isNotBlank() && original.ext=="mp4") {
                        val prefix=sourcePrefix(original,"$trace-header-${java.util.UUID.randomUUID()}")
                        measuredPrefix(original,prefix)?.let {found[original.id]=it;return@launch}
                    }
                    val headers=payload.optJSONObject("http_headers")?.let {h->h.keys().asSequence().take(32).associateWith {h.optString(it)}}.orEmpty()
                    val measured=resolver.resolve(payload.getString("url"),trace,allowUnknownExtension=true,sourceHeaders=headers)
                        ?.formats?.firstOrNull {it.video} ?: return@launch
                    merge(original,measured)?.let {found[original.id]=it}
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) { /* Unconfirmed candidates remain unrated, never guessed. */ }
            }}
        }}
        NetworkDiagnostics.record("analysis","video_dimensions_inspected",null,operation=trace,
            details=JSONObject().put("candidates",candidates.size).put("confirmed",found.size)
                .put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-began))
        return if(found.isEmpty()) media else media.copy(formats=media.formats.map {found[it.id] ?: it})
    }
}
