package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object FormatSizes {
    /** At most 16 HEAD requests / 3 concurrent / 2 seconds; no media body is downloaded. */
    suspend fun enrich(media:Media,onProgress:(Media,Set<String>)->Unit={_,_->},client:okhttp3.OkHttpClient=Transfer.networkClient):Media {
        val measuredFormats=ConcurrentHashMap<String,Format>()
        val groups=media.qualityGroups()
        val audio=media.formats.filter {it.streamOnly}.sortedWith(compareByDescending<Format> {it.languagePreference ?: -1.0}.thenByDescending {it.audioBitrate ?: it.bitrate ?: 0.0})
        val candidates=(groups.map {it.recommended}+audio+groups.flatMap {it.candidates}).distinctBy {it.id}
            .filter {(it.bytes==null||it.sizeEstimated)&&it.protocol=="https"&&it.protocol !in setOf("m3u8","m3u8_native")&&it.ext!="m3u8"}.take(16)
        if(candidates.isEmpty()) return media
        val pending=candidates.map {it.id}.toMutableSet()
        val progressLock=Any()
        // Parse a successful response once, not again for every subsequent HEAD
        // completion. Unchanged rows retain their original Format instances.
        fun snapshot()=media.copy(formats=media.formats.map {f->measuredFormats[f.id] ?: f})
        onProgress(media,pending.toSet())
        withTimeoutOrNull(2000) { coroutineScope {
            val semaphore=Semaphore(3)
            candidates.forEach { f -> launch { semaphore.withPermit {
                val payload=JSONObject(f.payload)
                val rawUrl=payload.optString("url")
                if(rawUrl.isBlank()) return@withPermit
                val request=Request.Builder().url(UrlPolicy.normalize(rawUrl)).head().header("Accept-Encoding","identity")
                payload.optJSONObject("http_headers")?.let { h -> h.keys().forEach { key -> if(key.lowercase() in listOf("user-agent","referer","origin","accept","accept-language")) request.header(key,h.getString(key)) } }
                val call=client.newCall(request.build())
                val watcher=launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    awaitResponse(call).use { response ->
                        if(response.code==200 && response.header("Content-Encoding")?.let { it!="identity" }!=true) {
                            val mime=response.header("Content-Type").orEmpty().lowercase()
                            if(!mime.startsWith("text/") && !mime.contains("json")) response.header("Content-Length")?.toLongOrNull()?.takeIf { it>0 }?.let {
                                measuredFormats[f.id]=Format.parse(JSONObject(f.payload).put("filesize",it))
                            }
                        }
                    }
                } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Optional metadata: retain extractor data. */ } finally {
                    watcher.cancel()
                }

                if (measuredFormats[f.id] == null) {
                    val rangeReq = Request.Builder().url(UrlPolicy.normalize(rawUrl)).get()
                        .header("Range", "bytes=0-0")
                        .header("Accept-Encoding", "identity")
                    payload.optJSONObject("http_headers")?.let { h -> h.keys().forEach { key -> if(key.lowercase() in listOf("user-agent","referer","origin","accept","accept-language")) rangeReq.header(key,h.getString(key)) } }
                    val rangeCall = client.newCall(rangeReq.build())
                    val rangeWatcher = launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { rangeCall.cancel() } }
                    try {
                        awaitResponse(rangeCall).use { response ->
                            if (response.code == 206) {
                                val range = ContentRange.parse(response.header("Content-Range"))
                                range?.total?.takeIf { it > 0 }?.let { totalBytes ->
                                    measuredFormats[f.id] = Format.parse(JSONObject(f.payload).put("filesize", totalBytes))
                                }
                            } else if (response.code == 200 && response.header("Content-Encoding")?.let { it != "identity" } != true) {
                                val mime = response.header("Content-Type").orEmpty().lowercase()
                                if (!mime.startsWith("text/") && !mime.contains("json")) response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let {
                                    measuredFormats[f.id] = Format.parse(JSONObject(f.payload).put("filesize", it))
                                }
                            }
                        }
                    } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Optional metadata: retain extractor data. */ } finally {
                        rangeWatcher.cancel()
                    }
                }
                synchronized(progressLock) {pending.remove(f.id);onProgress(snapshot(),pending.toSet())}
            } } }
        } }
        val result=snapshot()
        onProgress(result,emptySet())
        return result
    }
}
