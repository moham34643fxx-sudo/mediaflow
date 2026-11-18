package com.zdownloader.app.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.zdownloader.app.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class Extractor internal constructor(private val context:Context,private val direct:DirectMediaResolver,
    private val sizeClient:()->okhttp3.OkHttpClient = {Transfer.networkClient},
    private val connectionIssue:()->ErrorCode? = NetworkDiagnostics::connectionIssue) {
    @Inject constructor(@ApplicationContext context:Context):this(context,DirectMediaResolver())
    private val initLock = Any()
    private val cancellationScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val pythonPrewarmRequested=java.util.concurrent.atomic.AtomicBoolean(false)
    private val directShareExtensions=setOf("mp4","m4a","webm","jpg","jpeg","png","webp")
    private val metadata=ExtractorMetadata(cancellationScope,android.os.SystemClock::elapsedRealtime)
    private val playlistAdmission=PlaylistMetadataAdmission(android.os.SystemClock::elapsedRealtime)
    private val videoHeaders=SharedAnalysis<Media>(cancellationScope)
    val sizePreviews by lazy {com.zdownloader.app.data.SizePreviewRepository(context,this)}
    private data class Cached(val media: Media,val time: Long)
    private val videoHeaderCache=LinkedHashMap<String,Cached>(8,.75f,true)
    private val cacheLock=Any()
    // Native resolver/Python calls may ignore thread interruption. Bound both the UI
    // wait and outstanding native work; cancellation never joins a blocked worker.
    private val workers=java.util.concurrent.ThreadPoolExecutor(2,2,30,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.LinkedBlockingQueue<Runnable>(32))
    private val streamWorkers=java.util.concurrent.ThreadPoolExecutor(10,10,30,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.LinkedBlockingQueue<Runnable>(32))
    suspend fun streamCall(directory: java.io.File, requestId: String, payload: String, path: String, callback: com.zdownloader.app.ProgressProxy,fragmentConcurrency:Int=8,initialBytes:Long=0): JSONObject = suspendCancellableCoroutine { continuation ->
        val reservation=NativeWork.Reservation(directory)
        val work=Runnable {
                if(!reservation.start()) return@Runnable
                try {
                    if(!continuation.isActive) return@Runnable
                    val result = runCatching { JSONObject(python().getModule("zextractor").callAttr("download_stream",payload,path,requestId,callback,fragmentConcurrency,initialBytes).toString()) }
                    if(continuation.isActive) continuation.resumeWith(result)
                } finally { reservation.finish() }
        }
        continuation.invokeOnCancellation { synchronized(work) {streamWorkers.remove(work)};reservation.cancelPending();cancel(requestId) }
        try {
            synchronized(work) {if(continuation.isActive) streamWorkers.execute(work)}
        } catch(_: java.util.concurrent.RejectedExecutionException) {
            reservation.cancelPending()
            continuation.resumeWith(Result.failure(MediaException(ErrorCode.ENGINE_BUSY)))
        }
    }
    suspend fun pythonCall(method:String,vararg args:Any):JSONObject = timedPythonCall(method,args.getOrNull(1)?.toString().orEmpty(),*args)
    private suspend fun timedPythonCall(method:String,trace:String,vararg args:Any):JSONObject {
        AnalysisTrace.checkpoint(trace,"python_queued")
        return cancellableWorker(workers,{AnalysisTrace.checkpoint(trace,"python_wait_cancelled");cancel(args.getOrNull(1)?.toString().orEmpty())}) {
            AnalysisTrace.checkpoint(trace,"python_worker_started")
            val result=runCatching {
                val start=android.os.SystemClock.elapsedRealtime()
                AnalysisTrace.checkpoint(trace,"python_runtime_start")
                val runtime=python()
                val ready=android.os.SystemClock.elapsedRealtime()
                AnalysisTrace.checkpoint(trace,"python_module_start")
                val module=runtime.getModule("zextractor")
                AnalysisTrace.value(trace,"python_runtime_ms",ready-start)
                AnalysisTrace.value(trace,"python_module_ms",android.os.SystemClock.elapsedRealtime()-ready)
                AnalysisTrace.checkpoint(trace,"python_call_start")
                JSONObject(module.callAttr(method,*args).toString()).also { j ->
                AnalysisTrace.checkpoint(trace,"python_call_returned")
                j.optJSONObject("timings_ms")?.let {AnalysisTrace.value(trace,"python_stages",it)}
                j.optJSONObject("js_challenges")?.let {AnalysisTrace.value(trace,"js",it)}
                NetworkDiagnostics.record("python",j.optString("stage",method),j.optString("error").takeIf { it.isNotBlank() }?.let(ErrorCode::parse))
            } }
            result.getOrThrow()
        }
    }
    fun clearMetadataCache() { metadata.clear();synchronized(cacheLock) {videoHeaderCache.clear()} }
    /** Quick Share intent: retain an already-running base discovery (and optional video headers)
     * until its queue subscriber attaches (or the bounded lease expires).
     */
    internal fun retainVideoPreview(input:String,media:Media?=null):AutoCloseable? {
        val url=SharedLinks.normalize(input)
        val network=context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
        val base=metadata.retain(MetadataRequest(url,network,MetadataRoute.AUTOMATIC))
        val headers=media?.let {videoHeaders.retain(videoHeaderKey(it))}
        if(base==null&&headers==null) return null
        return AutoCloseable {base?.close();headers?.close()}
    }
    private fun videoHeaderKey(media:Media):String {
        val network=context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
        return "${media.url}|$network|${media.formats.map {it.payload}.hashCode()}"
    }
    /** Only video consumers wait for missing geometry. Audio uses base metadata
     * immediately and never joins these extra, strictly bounded HTTP reads. */
    internal suspend fun videoMetadata(media:Media,trace:String):Media {
        if(!VideoDimensionDiscovery.needed(media)) return media
        val key=videoHeaderKey(media)
        synchronized(cacheLock) {
            videoHeaderCache[key]?.takeIf {FormatAvailability.reusable(it.media,android.os.SystemClock.elapsedRealtime()-it.time)}?.let {return it.media}
        }
        return videoHeaders.run(key,{}) {
            val measured=VideoDimensionDiscovery.enrich(media,trace,direct) {format,requestId->
                val response=pythonCall("format_header",format.payload,requestId)
                check(response.optString("schema")=="mediaflow_format_header_v1")
                val encoded=response.getString("prefix")
                check(encoded.length<=87_384)
                android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP).also {check(it.size<=65536)}
            }
            synchronized(cacheLock) {
                if(measured.formats.sumOf {it.payload.length.toLong()*2}<=512*1024)
                    videoHeaderCache[key]=Cached(measured,android.os.SystemClock.elapsedRealtime())
                while(videoHeaderCache.size>8||videoHeaderCache.values.sumOf {it.media.formats.sumOf {f->f.payload.length.toLong()*2}}>1024*1024)
                    videoHeaderCache.remove(videoHeaderCache.keys.first())
            }
            measured
        }
    }
    fun python(): Python {
        JsBridge.initialize(context)
        synchronized(initLock) { if (!Python.isStarted()) Python.start(AndroidPlatform(context)) }
        return Python.getInstance()
    }
    // HLS/DASH and page shares need Python, so overlap its cold boot with Activity startup.
    internal fun prewarmForShare(input:String?,trace:String) {
        if(!shouldPrewarmShare(input)||Python.isStarted()||!pythonPrewarmRequested.compareAndSet(false,true)) return
        AnalysisTrace.mark(trace,"python_prewarm_scheduled")
        startPythonPrewarm(trace)
    }
    private fun shouldPrewarmShare(input:String?):Boolean {
        val url=input?.let {runCatching {SharedLinks.normalize(it)}.getOrNull()}?.takeIf {it.isNotBlank()} ?: return false
        val extension=runCatching {java.net.URI(url).path.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)}.getOrDefault("")
        return extension !in directShareExtensions
    }
    private fun startPythonPrewarm(trace:String) {
        cancellationScope.launch {
            val started=android.os.SystemClock.elapsedRealtime()
            try {
                AnalysisTrace.mark(trace,"python_prewarm_started")
                python().getModule("zextractor")
                AnalysisTrace.value(trace,"python_prewarm_ms",android.os.SystemClock.elapsedRealtime()-started)
                AnalysisTrace.mark(trace,"python_prewarm_finished")
            } catch(error:CancellationException) {
                pythonPrewarmRequested.set(false)
                throw error
            } catch(_:Exception) {
                pythonPrewarmRequested.set(false)
                AnalysisTrace.mark(trace,"python_prewarm_failed")
            }
        }
    }
    fun cancel(id: String) { if (id.isNotBlank() && Python.isStarted()) cancellationScope.launch { runCatching { python().getModule("zextractor").callAttr("cancel", id) } } }
    suspend fun analyze(input: String, id: String = UUID.randomUUID().toString(), forceRefresh: Boolean = false, forceExtractor: Boolean = false, enrichOptional:Boolean=true, onMetadata: (Media)->Unit = {}): Media = withContext(Dispatchers.IO) {
        val url=SharedLinks.normalize(input)
        val network=context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
        // Enriched analysis retains its externally cancellable request ID;
        // base-metadata consumers share a producer with its own native ID.
        AnalysisTrace.checkpoint(id,"subscriber_started")
        try { AnalysisOperation.run {
            val request=MetadataRequest(url,network,if(forceExtractor) MetadataRoute.EXTRACTOR else MetadataRoute.AUTOMATIC,
                if(enrichOptional) MetadataMode.ENRICHED else MetadataMode.BASE,
                when {forceRefresh->MetadataFreshness.REFRESH;forceExtractor->MetadataFreshness.SKIP_CACHE;else->MetadataFreshness.REUSE})
            metadata.analyze(request,onMetadata,onCacheHit={recordMetadataReuse(id)}) {publish->
                if(enrichOptional) analyzeFresh(url,id,forceExtractor,true,publish,id)
                else analyzeFresh(url,id,forceExtractor,false,publish)
            }
        }} finally {AnalysisTrace.checkpoint(id,"subscriber_finished")}
    }
    /** Playlist previews and durable children share extractor-backed base metadata,
     * not a policy-specific selection or the preview's native cancellation ID. */
    internal suspend fun analyzePlaylistItem(input:String,id:String=UUID.randomUUID().toString(),forceRefresh:Boolean=false,
        subscriber:AnalysisSubscriber=AnalysisSubscriber.PREVIEW):Media = withContext(Dispatchers.IO) {
        val url=SharedLinks.normalize(input)
        val network=context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
        AnalysisTrace.checkpoint(id,"subscriber_started")
        var producerStarted=false
        var cacheHit=false
        var completed=false
        try {AnalysisOperation.run {
            val request=MetadataRequest(url,network,MetadataRoute.EXTRACTOR,
                freshness=if(forceRefresh) MetadataFreshness.REFRESH else MetadataFreshness.REUSE,subscriber=subscriber)
            metadata.analyze(request,onCacheHit={cacheHit=true;recordMetadataReuse(id)}) {publish->
                producerStarted=true
                AnalysisTrace.checkpoint(id,"playlist_admission_requested")
                playlistAdmission.run {
                    AnalysisTrace.checkpoint(id,"playlist_admission_granted")
                    analyzeFresh(url,id,true,false,publish)
                }
            }.also {completed=true}
        }} finally {
            NetworkDiagnostics.record("analysis","playlist_metadata_source",null,operation=id,
                details=JSONObject().put("source",when {cacheHit->"cache";producerStarted->"producer";completed->"shared";else->"unclassified"})
                    .put("completed",completed))
            AnalysisTrace.checkpoint(id,"subscriber_finished")
        }
    }
    internal fun retainPlaylistItem(input:String):AutoCloseable? {
        val url=SharedLinks.normalize(input)
        val network=context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
        return metadata.retain(MetadataRequest(url,network,MetadataRoute.EXTRACTOR))
    }
    private fun recordMetadataReuse(id:String) {
        AnalysisTrace.mark(id,"extraction_start")
        AnalysisTrace.value(id,"cache_hit",true)
        AnalysisTrace.mark(id,"metadata_ready")
        AnalysisTrace.mark(id,"extractor_finished")
    }
    private suspend fun analyzeFresh(input:String,id:String,forceExtractor:Boolean,enrichOptional:Boolean,onMetadata:(Media)->Unit,nativeId:String=UUID.randomUUID().toString()):Media = withContext(Dispatchers.IO) {
        val url = UrlPolicy.normalize(input)
        val started=android.os.SystemClock.elapsedRealtime()
        AnalysisTrace.mark(id,"extraction_start")
        AnalysisTrace.value(id,"native_request_id",nativeId)
        connectionIssue()?.let {throw MediaException(it)}
        try {
            // This is bounded media inspection, not a UI admission deadline. A
            // tail-moov MP4 may need multiple legitimate ranges. Cancelling at
            // two seconds can erase its only reliable video/audio metadata.
            AnalysisTrace.checkpoint(id,"direct_probe_start")
            var directFailure:MediaException?=null
            val resolved=if(forceExtractor) null else try { withTimeoutOrNull(8000) { direct.resolve(url,id) } }
                catch(e: CancellationException) { throw e }
                catch(e:MediaException) {directFailure=e;null}
                catch(_:Exception) { null }
            AnalysisTrace.checkpoint(id,"direct_probe_finished")
            val probeEnd=android.os.SystemClock.elapsedRealtime()
            AnalysisTrace.value(id,"direct_resolver_ms",probeEnd-started)
            val fbScraped = if (resolved == null && !forceExtractor && FacebookScraper.isFacebookUrl(url)) {
                try { withTimeoutOrNull(12_000) { FacebookScraper.scrape(url) } }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { null }
            } else null
            val extractedMedia=resolved ?: fbScraped ?: try {withTimeout(90_000) {
                val pythonStarted=android.os.SystemClock.elapsedRealtime()
                val j=timedPythonCall("analyze",id,url,nativeId);val extracted=android.os.SystemClock.elapsedRealtime()
                val parsed=Media.parse(j,url)
                AnalysisTrace.value(id,"format_catalog_counts",JSONObject()
                    .put("normalized_formats",j.optJSONArray("formats")?.length() ?: 0)
                    .put("accepted_formats",parsed.formats.size)
                    .put("accepted_video_sources",parsed.formats.count {it.video})
                    .put("accepted_audio_sources",parsed.formats.count {it.audio}))
                AnalysisTrace.mark(id,"first_verified_format")
                AnalysisTrace.mark(id,"complete_verified_formats")
                val parsedAt=android.os.SystemClock.elapsedRealtime()
                AnalysisTrace.value(id,"python_total_ms",extracted-pythonStarted)
                AnalysisTrace.value(id,"kotlin_parse_ms",parsedAt-extracted)
                android.util.Log.i("ZAnalysis","python_total_ms=${extracted-pythonStarted}, kotlin_parse_ms=${parsedAt-extracted}, stages=${j.optJSONObject("timings_ms")}")
                parsed
            }} catch(error:MediaException) {
                // Generic extraction can miss signed CDN/download endpoints with
                // no filename. Inspect their actual media headers as a fallback.
                val fallback=if(error.code in setOf(ErrorCode.EXTRACTION_FAILED,ErrorCode.UNSUPPORTED_PLATFORM,ErrorCode.FORMAT_UNAVAILABLE)) {
                    try {direct.resolve(url,id,allowUnknownExtension=true)}
                        catch(e:CancellationException) {throw e}
                        catch(_:Exception) {null}
                } else null
                // A real extractor success still wins over a restrictive direct
                // probe. On generic fallback failure retain the known HTTP cause
                // only if the requested URL was an actual direct media candidate.
                fallback ?: throw if(directFailure != null && direct.hasMediaExtension(url)) directFailure else error
            }
            ensureActive()
            AnalysisTrace.mark(id,"first_verified_format")
            AnalysisTrace.mark(id,"complete_verified_formats")
            if(extractedMedia.formats.any {it.size(extractedMedia.duration)!=null}) AnalysisTrace.mark(id,"first_discovered_size")
            onMetadata(extractedMedia)
            AnalysisTrace.mark(id,"metadata_ready")
            val manifestStart=android.os.SystemClock.elapsedRealtime()
            // Publish usable choices first. Interactive size previews may then
            // improve missing/approximate lengths with bounded HEAD requests;
            // durable queue analysis does not wait for that optional enrichment.
            val media=if(enrichOptional) FormatSizes.enrich(extractedMedia,client=sizeClient()) else extractedMedia
            android.util.Log.i("ZAnalysis","manifest_enrichment_ms=${android.os.SystemClock.elapsedRealtime()-manifestStart}")
            AnalysisTrace.value(id,"optional_enrichment_ms",android.os.SystemClock.elapsedRealtime()-manifestStart)
            ensureActive()
            android.util.Log.i("ZAnalysis","resolver=${if(resolved!=null) "direct" else "python"}, direct_probe_ms=${probeEnd-started}, total_ms=${android.os.SystemClock.elapsedRealtime()-started}")
            AnalysisTrace.mark(id,"extractor_finished")
            FormatDiagnostics.catalog(media,id)
            NetworkDiagnostics.captureObservation {
                NetworkDiagnostics.record("analysis","extraction_completed",null,operation=id,
                    details=JSONObject().put("extraction_ms",android.os.SystemClock.elapsedRealtime()-started))
            }
            media
        }
        catch (e: TimeoutCancellationException) { cancel(nativeId);ensureActive();throw MediaException(ErrorCode.EXTRACTION_TIMEOUT) }
        catch (e: CancellationException) { cancel(nativeId); throw e }
    }
    suspend fun playlist(url: String, id: String,offset:Int=0,limit:Int=100): JSONObject = withContext(Dispatchers.IO) {
        require(limit in 1..100)
        AnalysisTrace.checkpoint(id,"playlist_discovery_started")
        try {
            val result=withTimeout(90_000) { pythonCall("playlist", SharedLinks.normalize(url), id,offset,limit) }
            AnalysisTrace.checkpoint(id,"playlist_result_returned")
            result
        }
        catch(e:TimeoutCancellationException) {cancel(id);ensureActive();throw MediaException(ErrorCode.EXTRACTION_TIMEOUT)}
        catch(e:CancellationException) {cancel(id);throw e}
        finally {AnalysisTrace.checkpoint(id,"playlist_discovery_finished")}
    }
    suspend fun version(): String = withContext(Dispatchers.IO) { python().getModule("zextractor").callAttr("engine_version").toString() }
}

/** Provider protection applies only to new playlist producers, never cached/joined consumers. */
internal class PlaylistMetadataAdmission(private val clock:()->Long,private val wait:suspend (Long)->Unit={delay(it)}) {
    private val lock=Mutex()
    private var finishedAt:Long?=null
    suspend fun <T> run(extract:suspend ()->T):T=lock.withLock {
        currentCoroutineContext().ensureActive()
        finishedAt?.let {finished->(1200L-(clock()-finished)).takeIf {it>0}?.let {wait(it)}}
        currentCoroutineContext().ensureActive()
        try {extract()} finally {finishedAt=clock()}
    }
}

internal enum class MetadataRoute { AUTOMATIC, EXTRACTOR }
internal enum class MetadataMode { BASE, ENRICHED }
internal enum class MetadataFreshness { REUSE, SKIP_CACHE, REFRESH }
internal data class MetadataRequest(val url:String,val network:Long?,val route:MetadataRoute,
    val mode:MetadataMode=MetadataMode.BASE,val freshness:MetadataFreshness=MetadataFreshness.REUSE,
    val subscriber:AnalysisSubscriber=AnalysisSubscriber.PREVIEW)

/** The extractor's single bounded metadata cache and subscriber registry.
 * Kept Android-free so reuse, expiry and cancellation can be checked without a device. */
internal class ExtractorMetadata(scope:CoroutineScope,private val clock:()->Long,
    private val wallClock:()->Long=System::currentTimeMillis) {
    private data class Cached(val media:Media,val time:Long,val generation:Long)
    private val shared=SharedAnalysis<Media>(scope)
    private val cache=LinkedHashMap<String,Cached>(16,.75f,true)
    private val generations=java.util.concurrent.atomic.AtomicLong()
    private fun key(request:MetadataRequest)=with(request) {"${url.length}:$url|$network|${route.name}|${mode.name}"}
    fun clear()=synchronized(cache) {cache.clear()}
    fun retain(request:MetadataRequest):AutoCloseable?=shared.retain(key(request))
    suspend fun analyze(request:MetadataRequest,onMetadata:(Media)->Unit={},onCacheHit:()->Unit={},
        load:suspend ((Media)->Unit)->Media):Media {
        val key=key(request)
        val cached=if(request.freshness==MetadataFreshness.REUSE) reusable(key) else null
        if(cached!=null) {onCacheHit();onMetadata(cached);return cached}
        val producer:suspend ((Media)->Unit)->Media={publish->
            val generation=generations.incrementAndGet()
            load(publish).also {media->
                currentCoroutineContext().ensureActive()
                remember(key,media,generation)
            }
        }
        // Refresh bypasses ordinary flights as well as cached results. A unique
        // flight also prevents one consumer's refresh from owning another's wait.
        return if(request.mode==MetadataMode.ENRICHED) producer(onMetadata)
        else shared.run(if(request.freshness==MetadataFreshness.REFRESH) "$key|refresh:${UUID.randomUUID()}" else key,
            onMetadata,request.subscriber,producer)
    }
    private fun reusable(key:String):Media?=synchronized(cache) {
        cache[key]?.takeIf {FormatAvailability.reusable(it.media,clock()-it.time,wallClock())}?.media
    }
    private fun remember(key:String,media:Media,generation:Long)=synchronized(cache) {
        // An older producer can finish after refresh; it must not replace that newer result.
        if((cache[key]?.generation ?: -1)>generation) return@synchronized
        if(media.formats.sumOf {it.payload.length.toLong()*2}<=512*1024) cache[key]=Cached(media,clock(),generation)
        while(cache.size>16||cache.values.sumOf {row->row.media.formats.sumOf {it.payload.length.toLong()*2}}>2*1024*1024)
            cache.remove(cache.keys.first())
    }
}
