package com.zdownloader.app.data

import android.content.Context
import android.util.AtomicFile
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Extractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CachedSizeCatalog(val time:Long,val choices:Map<String,SizeForecast>)

/** Metadata-only cache. No signed media URLs, sessions, jobs or download selections are persisted here. */
class SizePreviewRepository(context:Context,private val extractor:Extractor) {
    private val file=AtomicFile(File(context.filesDir,"size-previews-v1.json"))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val ready=CompletableDeferred<Unit>()
    private val writes=Mutex()
    private val inspection=Mutex()
    private val mutable=MutableStateFlow<Map<String,CachedSizeCatalog>>(emptyMap())
    val state=mutable.asStateFlow()
    init {scope.launch {
        try {
            val text=file.openRead().use {input->
                val data=input.readNBytesCompat(16*1024*1024+1)
                if(data.size>16*1024*1024) return@launch
                String(data,Charsets.UTF_8)
            }
            val saved=JSONObject(text)
            mutable.value=saved.keys().asSequence().take(5000).mapNotNull {key->runCatching {
                val row=saved.getJSONObject(key);val time=row.getLong("time")
                if(!fresh(time)) return@runCatching null
                val choices=row.getJSONObject("choices")
                key to CachedSizeCatalog(time,choices.keys().asSequence().associateWith {SizeForecast.parse(choices.getJSONObject(it))})
            }.getOrNull()}.filterNotNull().toMap()
        } catch(_:Exception) { /* An absent/expired preview never blocks extraction. */ }
        finally {ready.complete(Unit)}
    }}
    private fun fresh(time:Long)=System.currentTimeMillis()-time in 0..24*60*60_000L
    fun cached(url:String):CachedSizeCatalog?=state.value[MediaIdentity.urlKey(url)]?.takeIf {fresh(it.time)}
    fun forecast(url:String,policy:PlaylistPolicy,duration:Double?):SizeForecast = cached(url)?.choices?.get(policy.sizeKey)
        ?: SizeForecast(policy.durationOutput(duration),null,null)
    suspend fun remember(media:Media)=withContext(Dispatchers.IO) {
        ready.await()
        val catalog=CachedSizeCatalog(System.currentTimeMillis(),playlistSizeCatalog(media))
        writes.withLock {
            val all=(mutable.value.filterValues {fresh(it.time)}+ (MediaIdentity.urlKey(media.url) to catalog))
                .entries.sortedByDescending {it.value.time}.take(5000).associate {it.key to it.value}
            mutable.value=all
            // Serialize atomically; a failed optional cache write must not turn a
            // successfully analyzed video into an extraction error.
            runCatching {
                val saved=JSONObject()
                all.forEach {(key,value)->val choices=JSONObject();value.choices.forEach {(policy,size)->choices.put(policy,size.json())}
                    saved.put(key,JSONObject().put("time",value.time).put("choices",choices))}
                val stream=file.startWrite()
                try {stream.write(saved.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
                catch(e:Exception) {file.failWrite(stream);throw e}
            }
        }
    }
    suspend fun inspect(url:String) {
        ready.await()
        if(cached(url)!=null) return
        inspection.withLock {
            if(cached(url)!=null) return
            val id=UUID.randomUUID().toString()
            try {remember(extractor.analyzePlaylistItem(url,id))}
            finally {extractor.cancel(id)}
            delay(300) // Serial metadata inspection, independent of transfer concurrency.
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(output.size()<limit) {val count=read(buffer,0,minOf(buffer.size,limit-output.size()));if(count<0) break;output.write(buffer,0,count)}
    return output.toByteArray()
}
