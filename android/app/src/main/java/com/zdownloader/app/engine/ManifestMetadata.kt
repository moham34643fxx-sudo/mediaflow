package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.net.URI

internal object ManifestMetadata {
    /** One bounded manifest read, never segment probing or entire-media scanning. */
    suspend fun enrich(media:Media):Media {
        val path=URI(media.url).path.lowercase()
        val dash=path.endsWith(".mpd")
        val hls=path.endsWith(".m3u8")
        if(!dash&&!hls) return media
        if(dash && media.duration!=null) return media
        suspend fun read(url:String):String? = coroutineScope {
            val call=Transfer.networkClient.newCall(Request.Builder().url(UrlPolicy.normalize(url)).header("Range","bytes=0-262143").header("Accept-Encoding","identity").build())
            val watcher=launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try { awaitResponse(call).use { response ->
                if(!response.isSuccessful) return@coroutineScope null
                val text=response.body?.byteStream()?.use { String(it.readNBytesCompat(262144),Charsets.UTF_8) } ?: return@coroutineScope null
                text
            } } finally { watcher.cancel() }
        }
        return withTimeoutOrNull(2000) {
            val text=read(media.url) ?: return@withTimeoutOrNull media
            if(dash) media.copy(duration=dashDuration(text) ?: media.duration) else {
                val averages=hlsAverages(text,media.url)
                val formats=media.formats.map { f -> averages[JSONObject(f.payload).optString("url")]?.let { average -> Format.parse(JSONObject(f.payload).put("combined_tbr",average)) } ?: f }
                var duration=media.duration ?: hlsDuration(text)
                if(duration==null) {
                    val variant=formats.firstOrNull { it.video }?.let { JSONObject(it.payload).optString("url") }
                    if(variant!=null && variant!=media.url) duration=read(variant)?.let(::hlsDuration)
                }
                media.copy(duration=duration,formats=formats)
            }
        } ?: media
    }
    fun hlsDuration(text:String):Double? {
        if(!text.trimStart().startsWith("#EXTM3U") || !text.contains("#EXT-X-ENDLIST")) return null
        val values=Regex("(?m)^#EXTINF:([0-9.]+)").findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        return values.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it.isFinite() && it>0 }
    }
    fun hlsAverages(text:String,base:String):Map<String,Double> {
        val result=mutableMapOf<String,Double>();var rate:Double?=null
        for(line in text.lineSequence().map { it.trim() }) {
            if(line.startsWith("#EXT-X-STREAM-INF:")) rate=Regex("(?:^|,)AVERAGE-BANDWIDTH=(\\d+)").find(line.substringAfter(':'))?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000)
            else if(line.isNotBlank()&&!line.startsWith('#') && rate!=null) { result[URI(base).resolve(line).toString()]=rate;rate=null }
        }
        return result
    }
    fun dashDuration(text:String):Double? {
        if(Regex("type\\s*=\\s*[\"']dynamic[\"']").containsMatchIn(text)) return null
        val value=Regex("mediaPresentationDuration\\s*=\\s*[\"']([^\"']+)").find(text)?.groupValues?.get(1) ?: return null
        val match=Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").matchEntire(value) ?: return null
        return listOf(3600,60,1).mapIndexed { index,multiplier -> (match.groupValues[index+1].toDoubleOrNull() ?: 0.0)*multiplier }.sum().takeIf { it>0 && it.isFinite() }
    }
}
