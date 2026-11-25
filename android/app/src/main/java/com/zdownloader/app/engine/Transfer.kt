package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import com.zdownloader.app.ProgressProxy
import okhttp3.*
import okhttp3.Dns
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

class TransferControl {
    var fragmentConcurrency:Int=32
    val totalKinds=ConcurrentHashMap<String,String>()
    val finished = CompletableDeferred<Unit>()
    val stopped = AtomicBoolean(false)
    val stopRequested = CompletableDeferred<Unit>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val cancellers = ConcurrentHashMap<Any, () -> Unit>()
    val call: Call? get() = calls.firstOrNull()
    @Volatile var session: Long? = null
    @Volatile var onProcessingReady: (suspend () -> Unit)? = null
    @Volatile var onValidationReady: (suspend () -> Unit)? = null
    @Volatile internal var awaitPublicationValidation: (suspend () -> Unit)? = null
    @Volatile internal var copySources:CopyVerification.Sources?=null
    @Volatile internal var verifiedOutputSha256:String?=null
    fun add(call: Call) { calls += call; if(stopped.get()) call.cancel() }
    fun remove(call: Call) { calls -= call }
    fun addCanceller(key: Any, cancel: () -> Unit) { cancellers[key] = cancel; if(stopped.get()) cancel() }
    fun removeCanceller(key: Any) { cancellers.remove(key) }
    fun stop() { stopped.set(true); stopRequested.complete(Unit); calls.forEach(Call::cancel); cancellers.values.forEach { cancel -> runCatching { cancel() } }; session?.let { com.arthenica.ffmpegkit.FFmpegKit.cancel(it) } }
    fun check() { if (stopped.get()) throw CancellationException("Transfer stopped") }
}

@Singleton class Transfer internal constructor(private val extractor: Extractor, injectedClient: OkHttpClient?,private val processor:Processor=Processor()) {
    @Inject constructor(extractor: Extractor,processor:Processor): this(extractor,null,processor)
    constructor(extractor:Extractor):this(extractor,null,Processor())
    val client: OkHttpClient = injectedClient ?: networkClient
    companion object {
    val DIRECT_HEADERS = setOf(
        "user-agent", "referer", "origin", "accept", "accept-language",
        "accept-encoding", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-site",
        "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "range", "if-range"
    )
    val networkClient: OkHttpClient by lazy { OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests=64;maxRequestsPerHost=32 })
        .connectionPool(ConnectionPool(32,5,TimeUnit.MINUTES))
        .proxySelector(AndroidNetworkBridge.selector())
        .eventListenerFactory { it.request().tag(NetworkDiagnostics.Trace::class.java) ?: NetworkDiagnostics.Trace() }
        .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> {
            val addresses = InetAddress.getAllByName(hostname).sortedBy { it !is java.net.Inet4Address }
            val proxied = hostname in AndroidNetworkBridge.proxyHosts.get().orEmpty()
            if (addresses.isEmpty() || (!proxied && addresses.any { !UrlPolicy.publicAddress(it) })) {
                throw PolicyAddressException()
            }
            return addresses
        } })
        .addInterceptor { chain ->
            try { UrlPolicy.normalize(chain.request().url.toString()) } catch(_: MediaException) { throw IOException("Disallowed URL") }
            try {chain.proceed(chain.request())} finally {AndroidNetworkBridge.proxyHosts.remove()}
        }
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            try { UrlPolicy.normalize(chain.request().url.toString()) } catch(_: MediaException) { throw IOException("Disallowed URL") }
            chain.proceed(chain.request())
        }
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .build() }
    }

    private fun identity(format:Format)=listOf("v2",format.id,format.ext,codecFamily(format.videoCodec),codecFamily(format.audioCodec),format.width,format.height,format.fps,format.audioBitrate,format.language,format.audioChannels,format.sampleRate,format.dynamicRange).joinToString("|")+
        if(format.generatedSlideshow) SlideshowPlan.parse(format.payload).let {"|slideshow-v1:${it.postId}:${it.urls.size}:${it.duration}"} else ""
    private fun completedLength(format:Format,file:File):Long? {
        val marker=File(file.path+".complete").readTextOrNull()?.let {runCatching {JSONObject(it)}.getOrNull()}
        return file.length().takeIf {file.isFile&&it>0&&marker?.optLong("length")==it&&marker.optString("identity")==identity(format)}
    }
    /** Seed aggregate progress from resumable on-disk state, not old attempt counters. */
    internal fun retainedBytes(format:Format,file:File):Long {
        completedLength(format,file)?.let {return it}
        val part=File(file.path+".part")
        if(!part.isFile) return 0
        val resume=File(file.path+".resume").readTextOrNull()?.let {runCatching {JSONObject(it)}.getOrNull()}
        val http=resume?.optString("formatId")==format.id&&resume.optString("validator").isNotBlank()
        return if(http||File(file.path+".ytdl").isFile) part.length() else 0
    }
    suspend fun stream(format: Format, file: File, id: String, control: TransferControl, awaitIdle: Boolean = true,
        onFirstNewByte:()->Unit={},progress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        val firstNewByteReported=AtomicBoolean()
        val reportFirstNewByte={if(firstNewByteReported.compareAndSet(false,true)) onFirstNewByte()}
        withTransferProgressTimeout(progress = progress) { tracked ->
            streamOwned(format,file,id,control,awaitIdle,tracked,reportFirstNewByte)
        }
    }
    private suspend fun streamOwned(format: Format, file: File, id: String, control: TransferControl, awaitIdle: Boolean,
        progress: (Long, Long?) -> Unit,onFirstNewByte:()->Unit) {
        control.check()
        if(awaitIdle) withControlCancellation(control) { NativeWork.awaitIdle(file.parentFile!!) }
        val completed=File(file.path+".complete")
        val identity=identity(format)
        completedLength(format,file)?.let {length->control.totalKinds[format.id]="EXACT";progress(length,length);return}
        // A native/fragment downloader may leave a nonempty final name after a crash.
        // Reuse only tracks which this pipeline durably finished.
        if(file.exists()) file.delete()
        val payload = JSONObject(format.payload)
        val headers = payload.optJSONObject("http_headers")
        val hasFragments = payload.optJSONArray("fragments")?.length()?.let { it > 0 } == true
        val hasSpecialDownloaderOptions = payload.optJSONObject("downloader_options")?.keys()?.asSequence()?.any { it != "http_chunk_size" } == true
        // yt-dlp's serialized cookies contain domain/path attributes, not a raw
        // Cookie request header. Its isolated jar must restore and scope them.
        val requiresNativeDownloader = hasFragments || hasSpecialDownloaderOptions || payload.optString("cookies").let {it.isNotBlank()&&it!="null"}
        if(format.generatedSlideshow) {
            SlideshowTransfer.download(format,file,client,control,progress,onFirstNewByte) {manifest,plan->processor.renderSlideshow(manifest,file,plan,control)}
        } else if (format.protocol in listOf("http", "https") && !requiresNativeDownloader) {
            http(format, file, id, control, progress,onFirstNewByte)
        } else if (format.protocol in listOf("http", "https", "m3u8_native", "m3u8", "http_dash_segments", "dash")) {
            val requestId = "$id:${java.util.UUID.randomUUID()}"
            val retainedBeforeStart=retainedBytes(format,file)
            control.addCanceller(requestId) { extractor.cancel(requestId) }
            control.check()
            val callback = object : ProgressProxy {
                override fun confidence(kind:String) {control.totalKinds[format.id]=kind.takeIf {it in setOf("EXACT","ESTIMATED","UNKNOWN")} ?: "UNKNOWN"}
                override fun progress(downloaded: String, total: String) {
                    if (control.stopped.get()) extractor.cancel(requestId)
                    control.check()
                    progress(downloaded.toLongOrNull() ?: 0, total.toLongOrNull()?.takeIf { it > 0 })
                }
                override fun firstNewByte() = onFirstNewByte()
            }
            try {
                val partial=File(file.path+".part")
                val fragmentState=runCatching {JSONObject(File(file.path+".ytdl").readText())}.getOrNull()
                NetworkDiagnostics.record("python","native_resume_checkpoint",null,operation=id,
                    details=JSONObject().put("partial_bytes",partial.length()).put("fragment_index",fragmentState?.optJSONObject("downloader")?.optJSONObject("current_fragment")?.optInt("index")))
                val result = withControlCancellation(control) { extractor.streamCall(file.parentFile!!, requestId, format.payload, file.path, callback,control.fragmentConcurrency,retainedBeforeStart) }
                NetworkDiagnostics.record("python",result.optString("stage","transfer"),result.optString("error").takeIf {it.isNotBlank()}?.let(ErrorCode::parse),operation=id,
                    details=JSONObject().put("exception_class",result.optString("diagnostic")).put("exception_chain",result.optJSONArray("exception_chain")))
                control.check()
                if (result.has("error")) throw MediaException(ErrorCode.parse(result.optString("error")))
            } finally { control.removeCanceller(requestId) }
        } else throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
        if (!file.isFile || file.length() == 0L) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        java.io.FileOutputStream(completed).use {it.write(JSONObject().put("length",file.length()).put("identity",identity).toString().toByteArray());it.fd.sync()}
    }

    /**
     * Bounded sequential range downloader. Starts with 2 MiB windows and grows
     * successful fast responses up to 32 MiB to amortize network round trips.
     * Writes directly into the .part file with a fixed 256 KiB memory buffer.
     * If the server does not support Range (HTTP 200), streams smoothly to completion.
     */
    private suspend fun http(format: Format, output: File, id: String, control: TransferControl,
        progress: (Long, Long?) -> Unit,onFirstNewByte:()->Unit) = coroutineScope {
        val f = JSONObject(format.payload)
        val url = UrlPolicy.normalize(f.getString("url"))
        if(client===networkClient) NetworkDiagnostics.connectionIssue()?.let {throw MediaException(it)}
        val part = File(output.path + ".part")
        val resume = File(output.path + ".resume")
        var offset = part.length()
        val resumeJson = if (resume.isFile) runCatching { JSONObject(resume.readText()) }.getOrNull() else null
        var validator = resumeJson?.optString("validator")?.takeIf { it.isNotBlank() }
        val resumeFormat = resumeJson?.optString("formatId")
        var resumeTotal = resumeJson?.optLong("total", -1)?.takeIf { it >= 0 }
        // Representation identity: never resume a different format or an unvalidated partial.
        if (offset > 0 && (validator == null || validator.startsWith("synthetic:") || (resumeFormat != null && resumeFormat != format.id))) {
            part.delete(); resume.delete(); offset = 0; resumeTotal = null; validator = null
        }
        val trace = NetworkDiagnostics.Trace(operation = id)
        val reqHeaders = f.optJSONObject("http_headers")
        var bytes = offset
        // A saved length is a consistency constraint, not fresh evidence of EOF.
        // Even an exactly sized partial must contact the server with If-Range.
        var total: Long? = null
        var supportsRange = true

        // Respect applicable YouTube/yt-dlp HTTP chunk size limits (10 MiB / 10485760 bytes max).
        val upstreamChunk = f.optJSONObject("downloader_options")?.optLong("http_chunk_size", 0L)?.takeIf { it > 0 }
        val maxChunkSize = upstreamChunk?.coerceAtMost(10485760L) ?: 10485760L
        val minChunkSize = minOf(512L * 1024, maxChunkSize)
        var chunkSize = if ((format.bytes ?: resumeTotal)?.let { it > 0 } == true) maxChunkSize else minOf(2L * 1024 * 1024, maxChunkSize)
        val BUF_SIZE = 256 * 1024 // 256 KB read/write buffer

        fun addHeaders(builder: Request.Builder) {
            builder.header("Accept-Encoding", "identity").header("Connection", "keep-alive")
            reqHeaders?.let { h ->
                h.keys().forEach { name ->
                    val lower = name.lowercase()
                    if (lower !in setOf("range", "if-range", "connection", "accept-encoding", "host", "content-length")) {
                        builder.header(name, h.getString(name))
                    }
                }
            }
        }

        var inStreamRetries = 0
        while (true) {
            control.check()
            val requestStart = bytes
            val builder = Request.Builder().url(url).tag(NetworkDiagnostics.Trace::class.java, trace)
            addHeaders(builder)
            if (supportsRange) {
                val endRange = if (total != null) minOf(bytes + chunkSize - 1, total!! - 1) else bytes + chunkSize - 1
                builder.header("Range", "bytes=$bytes-$endRange")
                validator?.takeUnless { it.startsWith("synthetic:") }?.let { builder.header("If-Range", it) }
            }
            val call = client.newCall(builder.build())
            val requestStarted=System.nanoTime()
            control.add(call)
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                awaitResponse(call).use { response ->
                    control.check()
                    if (!response.isSuccessful) {
                        if (response.code == 416) {
                            if (bytes == 0L && supportsRange) {
                                supportsRange = false
                                return@use
                            }
                            part.delete(); resume.delete(); throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                        }
                        throw httpError(response)
                    }
                    val body = response.body ?: throw MediaException(ErrorCode.HTTP_ERROR)
                    val range = ContentRange.parse(response.header("Content-Range"))
                    if (response.code == 206 && (range == null || range.start != bytes || (body.contentLength() >= 0 && body.contentLength() != range.end - range.start + 1))) {
                        throw MediaException(ErrorCode.HTTP_ERROR)
                    }
                    // A bounded partial response is never proof of EOF. Without
                    // a full length, restart once as an ordinary full-body GET.
                    if(response.code==206 && range?.total==null) {
                        if(!supportsRange) throw MediaException(ErrorCode.HTTP_ERROR)
                        supportsRange=false;bytes=0;total=null;validator=null
                        part.delete();resume.delete()
                        return@use
                    }
                    val append = response.code == 206 && bytes > 0
                    if (!append) {
                        bytes = 0
                        if (response.code == 200) { validator = null; total = null; supportsRange = false }
                        if (part.length() > 0 && response.code == 200 && resume.isFile) { part.delete() }
                    }
                    val responseTotal = if (response.code == 206) range?.total else body.contentLength().takeIf { it >= 0 }
                    val expectedTotal = total ?: resumeTotal
                    if(append&&expectedTotal!=null&&responseTotal!=null&&expectedTotal!=responseTotal) {
                        part.delete();resume.delete();throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                    }
                    val tag = response.header("ETag")?.takeIf { it.isNotBlank()&&!it.startsWith("W/") }
                        ?: response.header("Last-Modified")?.takeIf {it.isNotBlank()}
                    // A complete single response needs no cross-response identity. A
                    // partial window or resumed prefix does: never splice guessed versions.
                    if(response.code==206&&tag==null&&(append||range!!.end+1<responseTotal!!)) {
                        if(!supportsRange) throw MediaException(ErrorCode.HTTP_ERROR)
                        supportsRange=false;bytes=0;total=null;resumeTotal=null;validator=null
                        part.delete();resume.delete()
                        return@use
                    }
                    val effectiveValidator = tag ?: ("synthetic:" + (responseTotal ?: format.id))
                    if (append && tag != null && tag != validator && validator?.startsWith("synthetic:") != true) {
                        part.delete(); resume.delete(); throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                    }
                    if (validator == null) validator = effectiveValidator
                    if (responseTotal != null) { total = responseTotal }
                    resume.writeText(JSONObject().put("validator", validator.orEmpty()).put("formatId", format.id).put("total", total ?: -1).toString())
                    control.totalKinds[format.id] = if (total != null) "EXACT" else "UNKNOWN"
                    progress(bytes, total)

                    readBody(body, part, append, control, BUF_SIZE,onFirstNewByte) { count ->
                        bytes += count
                        progress(bytes, total)
                    }
                    if(response.code==206 && bytes!=range!!.end+1) throw IOException("Incomplete range response")
                    if(response.code==200 && total!=null && bytes<total!!) throw IOException("Incomplete response body")
                    progress(bytes, total)

                    if (total == null) {
                        if (!part.renameTo(output)) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                        resume.delete()
                        return@coroutineScope
                    }
                    if (bytes == total) {
                        if (!part.renameTo(output)) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                        resume.delete()
                        return@coroutineScope
                    }
                    if (bytes > total!!) {
                        part.delete(); resume.delete(); throw MediaException(ErrorCode.SOURCE_CORRUPT)
                    }
                    if (bytes <= requestStart) throw MediaException(ErrorCode.DOWNLOAD_INTERRUPTED)
                    // Small fixed windows waste a round trip every 2 MiB on fast
                    // connections. Grow the range, not the in-memory buffer.
                    val elapsed=(System.nanoTime()-requestStarted)/1_000_000
                    if(response.code==206&&bytes-requestStart>=chunkSize&&elapsed<1000) chunkSize=(chunkSize*2).coerceAtMost(maxChunkSize)
                    else if(elapsed>5000) chunkSize=(chunkSize/2).coerceAtLeast(minChunkSize)
                    inStreamRetries = 0
                }
            } catch (e: IOException) {
                control.check()
                if (hasNoSpace(e)) throw MediaException(ErrorCode.INSUFFICIENT_STORAGE)
                if (e is java.io.FileNotFoundException) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                if (bytes > 0 && inStreamRetries < 3 && !control.stopped.get()) {
                    // Without a server validator a retry must start afresh;
                    // a guessed size is not a representation identity.
                    if(validator==null||validator!!.startsWith("synthetic:")) {
                        part.delete();resume.delete();bytes=0;total=null;validator=null
                    }
                    inStreamRetries++
                    delay(1000L * inStreamRetries)
                    continue
                }
                throw MediaException(NetworkDiagnostics.classify(e, trace.stage))
            } finally {
                control.remove(call)
                cancellation.cancel()
            }
        }
    }

    /** Read response body into a file with large buffers, reporting byte increments. */
    private suspend fun readBody(body: ResponseBody, file: File, append: Boolean, control: TransferControl,
        bufSize: Int = 256 * 1024,onFirstNewByte:()->Unit,onBytes: (Int) -> Unit) {
        java.io.FileOutputStream(file, append).use { rawSink ->
            java.io.BufferedOutputStream(rawSink, bufSize).use { sink ->
                body.byteStream().use { source ->
                    val buffer = ByteArray(bufSize)
                    while (true) {
                        currentCoroutineContext().ensureActive(); control.check()
                        val count = source.read(buffer); if (count < 0) break
                        try { sink.write(buffer,0,count) } catch(e: IOException) { throw MediaException(if(hasNoSpace(e)) ErrorCode.INSUFFICIENT_STORAGE else ErrorCode.FILE_WRITE_FAILED) }
                        onFirstNewByte()
                        onBytes(count)
                    }
                    sink.flush()
                }
            }
        }
    }

    private fun httpError(response: Response): MediaException {
        val retry=response.header("Retry-After")?.let { value -> value.toLongOrNull()?.coerceIn(0,3600)?.times(1000) ?: runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",java.util.Locale.US).apply {timeZone=java.util.TimeZone.getTimeZone("GMT");isLenient=false}.parse(value)?.let { it.time-System.currentTimeMillis() } }.getOrNull() }?.coerceIn(0,3600000)
        return MediaException(when(response.code) { 404 -> ErrorCode.VIDEO_NOT_FOUND; 410 -> ErrorCode.MEDIA_URL_EXPIRED; 401 -> ErrorCode.AUTHENTICATION_REQUIRED; 403 -> ErrorCode.HTTP_FORBIDDEN; 408 -> ErrorCode.READ_TIMEOUT; 429 -> ErrorCode.RATE_LIMITED; in 500..599 -> ErrorCode.SERVER_UNAVAILABLE; else -> ErrorCode.HTTP_ERROR },retry)
    }
}
private fun File.readTextOrNull():String?=runCatching {readText()}.getOrNull()
private fun hasNoSpace(error: Throwable?): Boolean {
    var current = error
    var depth = 0
    while (current != null && depth < 8) {
        if (current.message.orEmpty().contains("ENOSPC", true) || current.message.orEmpty().contains("no space", true)) return true
        current = current.cause
        depth++
    }
    return false
}
internal suspend fun awaitResponse(call:Call):Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call:Call,response:Response) {
                if(continuation.isActive) continuation.resume(response) { _,value,_ -> value.close() } else response.close()
            }
        })
}
