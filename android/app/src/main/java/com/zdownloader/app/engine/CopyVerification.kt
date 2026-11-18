package com.zdownloader.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Source decode evidence only: this class cannot authorize output publication. */
internal class CopyVerification internal constructor(
    private val context:Context,
    private val inputs:Inputs,
    private val admission:NativeAdmission.OptionalLease
) {
    data class Inputs(val video:File,val audio:File,val control:TransferControl)
    data class DecodedSource(val file:File,val bytes:Long,val sha256:String,val videoFrames:Long,val mediaTimeMs:Long,
        val decodedBeforeTransferEnd:Boolean=false)
    data class Sources(val video:DecodedSource,val audio:DecodedSource)
    private enum class Kind { VIDEO, AUDIO }
    private class Track(val file:File,val kind:Kind) {
        val signal=Channel<Unit>(Channel.CONFLATED)
        val transferred=AtomicBoolean(false)
    }

    val readerOwner=NativeWork.ReadOwner()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cancelled=AtomicBoolean(false)
    private val fedBytes=AtomicLong()
    private val deadline=SystemClock.elapsedRealtime()+MAX_WALL_MS
    private val operations=CopyOnWriteArrayList<ReadOperation>()
    private val tracks=listOf(Track(inputs.video,Kind.VIDEO),Track(inputs.audio,Kind.AUDIO))
    private val readers=tracks.map(::startReader)

    init {
        scope.launch {withTimeoutOrNull(MAX_WALL_MS) {admission.yieldRequested.await()};this@CopyVerification.cancel()}
        inputs.control.addCanceller(this,::cancel)
    }

    fun progress(file:File) {track(file).signal.trySend(Unit)}
    fun transferred(file:File) {
        val track=track(file)
        track.transferred.set(true)
        track.signal.close()
    }
    private fun track(file:File)=tracks.single {it.file==file}

    /** A yielded or unavailable optional decode requires the unchanged full validator. */
    suspend fun awaitSources():Sources? {
        try {
            val sources=Sources(readers[0].await(),readers[1].await())
            inputs.control.check();currentCoroutineContext().ensureActive()
            return sources.takeUnless {cancelled.get()||admission.yieldRequested.isCompleted}
        }
        catch(error:CancellationException) {
            inputs.control.check();currentCoroutineContext().ensureActive();return null
        } catch(error:IOException) {
            cancel()
            NetworkDiagnostics.record("media","source_overlap_unavailable",null,operation=inputs.video.parentFile?.name,
                details=org.json.JSONObject().put("reason",error.javaClass.simpleName))
            return null
        } catch(error:android.system.ErrnoException) {
            cancel()
            NetworkDiagnostics.record("media","source_overlap_unavailable",null,operation=inputs.video.parentFile?.name,
                details=org.json.JSONObject().put("errno",error.errno))
            return null
        }
    }

    fun cancel() {
        if(cancelled.compareAndSet(false,true)) {
            admission.cancel()
            tracks.forEach {it.signal.close()}
            scope.cancel()
            operations.forEach {it.cancelNative()}
        }
    }

    suspend fun awaitSettled():Boolean = withContext(NonCancellable) {
        val settled=withTimeoutOrNull(10_000) {operations.forEach {it.settled.await()};true} ?: false
        if(settled) inputs.control.removeCanceller(this@CopyVerification)
        settled
    }

    private fun startReader(track:Track):Deferred<DecodedSource> {
        val permit=admission.tryStart()
        if(permit==null) return CompletableDeferred<DecodedSource>().apply {cancel()}
        val operation=ReadOperation(track,permit).also {operations+=it}
        val reader=scope.async(start=CoroutineStart.LAZY) {
            operation.started.set(true)
            try {operation.consume()} finally {operation.finishReader()}
        }
        reader.invokeOnCompletion {failure->
            if(!operation.started.get()) operation.finishReader()
            if(failure!=null) cancel()
        }
        reader.start()
        return reader
    }

    private inner class ReadOperation(private val track:Track,private val permit:NativeAdmission.Permit) {
        private val lease=NativeWork.acquireRead(requireNotNull(track.file.parentFile),readerOwner)
        val started=AtomicBoolean(false)
        val settled=CompletableDeferred<Unit>()
        private val readerFinished=AtomicBoolean(false)
        private val nativeFinished=CompletableDeferred<ReturnCode?>()
        private val released=AtomicBoolean(false)
        private val frames=AtomicLong()
        private val mediaTime=AtomicLong()
        private val beforeEof=AtomicBoolean()
        private val nativeProgressAt=AtomicLong(SystemClock.elapsedRealtime())
        @Volatile private var session:FFmpegSession?=null
        private var pipe:String?=null
        private var writer:FileDescriptor?=null
        private var source:FileInputStream?=null
        private val digest=MessageDigest.getInstance("SHA-256")
        private val buffer=ByteArray(64*1024)
        private var fed=0L

        suspend fun consume():DecodedSource {
            startNative()
            while(true) {
                drain()
                if(track.signal.receiveCatching().isClosed) break
            }
            if(!track.transferred.get()) throw IOException("Producer did not finish")
            drain()
            event("source_bytes_fed")
            val sourceSha=digest.digest().hex()
            verifyCompleteSource(sourceSha)
            closeWriter()
            event("source_identity_verified")
            val code=awaitNativeEnd()
            if(!ReturnCode.isSuccess(code)||track.kind==Kind.VIDEO&&frames.get()<=0||mediaTime.get()<=0)
                throw IOException("Complete native source decode unavailable")
            return DecodedSource(track.file,fed,sourceSha,frames.get(),mediaTime.get(),beforeEof.get())
        }

        private suspend fun awaitNativeEnd():ReturnCode? {
            while(!nativeFinished.isCompleted) {
                checkActive()
                if(SystemClock.elapsedRealtime()-nativeProgressAt.get()>30_000) throw IOException("Optional source decoder stalled")
                withTimeoutOrNull(1000) {nativeFinished.await()}
            }
            event("source_decoder_eof")
            return nativeFinished.await()
        }

        private fun event(stage:String) {
            NetworkDiagnostics.record("media",stage,null,operation=track.file.parentFile?.name,
                details=org.json.JSONObject().put("track",track.kind.name).put("fed_bytes",fed)
                    .put("frames",frames.get()).put("media_time_ms",mediaTime.get()))
        }

        private fun startNative() {
            pipe=FFmpegKitConfig.registerNewFFmpegPipe(context)
            writer=Os.open(requireNotNull(pipe),OsConstants.O_RDWR or OsConstants.O_NONBLOCK,0)
            val args=mutableListOf("-nostdin","-v","error","-xerror","-err_detect","explode","-hwaccel","none","-stats_period","0.1")
            // Oversized speculative inputs fall back; never allocate an unbounded reference-frame set.
            if(track.kind==Kind.VIDEO) args+=listOf("-threads:v","4","-max_pixels",MAX_DECODE_PIXELS.toString())
            args+=listOf("-protocol_whitelist","file,pipe","-i",requireNotNull(pipe),"-map",if(track.kind==Kind.VIDEO) "0:v:0" else "0:a:0",
                "-max_error_rate","0","-f","null","-")
            session=FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),{completed->
                nativeFinished.complete(completed.returnCode);releaseIfSettled()
            },null,{sample->
                if(sample.videoFrameNumber.toLong()>frames.get()||sample.time.toLong()>mediaTime.get()) nativeProgressAt.set(SystemClock.elapsedRealtime())
                frames.updateAndGet {maxOf(it,sample.videoFrameNumber.toLong())}
                mediaTime.updateAndGet {maxOf(it,sample.time.toLong())}
                if(sample.time>0&&!track.transferred.get()) beforeEof.set(true)
            })
            event("source_decoder_started")
            if(cancelled.get()||admission.yieldRequested.isCompleted) cancelNative()
        }

        private suspend fun drain() {
            checkActive()
            if(source==null) source=openSource() ?: return
            val stream=requireNotNull(source)
            if(stream.channel.size()<fed) throw IOException("Source shrank during decode")
            while(stream.channel.position()<stream.channel.size()) {
                checkActive()
                val remaining=stream.channel.size()-stream.channel.position()
                val count=stream.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                if(count<=0||fed+count>MAX_SOURCE_BYTES||fedBytes.addAndGet(count.toLong())>MAX_TOTAL_BYTES)
                    throw IOException("Source decode byte budget exceeded")
                writeFifo(buffer,count)
                digest.update(buffer,0,count);fed+=count
            }
        }

        private fun openSource():FileInputStream? {
            val partial=File(track.file.path+".part")
            return try {FileInputStream(partial)} catch(error:java.io.FileNotFoundException) {
                if(track.file.isFile) FileInputStream(track.file) else null
            }
        }

        private suspend fun writeFifo(bytes:ByteArray,count:Int) {
            val fd=requireNotNull(writer)
            val poll=arrayOf(StructPollfd().apply {this.fd=fd;events=OsConstants.POLLOUT.toShort()})
            var offset=0
            var wroteAt=SystemClock.elapsedRealtime()
            while(offset<count) {
                checkActive()
                if(SystemClock.elapsedRealtime()-wroteAt>30_000) throw IOException("Optional source pipe stalled")
                if(nativeFinished.isCompleted) throw IOException("Source decoder ended before feed EOF")
                try {
                    val written=Os.write(fd,bytes,offset,count-offset)
                    if(written<=0) throw IOException("Source FIFO write failed")
                    offset+=written
                    wroteAt=SystemClock.elapsedRealtime()
                } catch(error:android.system.ErrnoException) {
                    when(error.errno) {OsConstants.EINTR->Unit;OsConstants.EAGAIN->Os.poll(poll,100);else->throw error}
                }
            }
        }

        private suspend fun verifyCompleteSource(expectedSha:String) {
            checkActive()
            val input=requireNotNull(source)
            val opened=Os.fstat(input.fd)
            val named=Os.stat(track.file.path)
            if(opened.st_dev!=named.st_dev||opened.st_ino!=named.st_ino||opened.st_size!=fed||named.st_size!=fed||fed<=0)
                throw IOException("Source identity changed before EOF")
            val finalDigest=MessageDigest.getInstance("SHA-256")
            var checked=0L
            track.file.inputStream().use {final->
                val buffer=ByteArray(64*1024)
                while(true) {
                    checkActive();val count=final.read(buffer);if(count<0) break
                    checked+=count;if(checked>fed) throw IOException("Source grew after EOF")
                    finalDigest.update(buffer,0,count)
                }
            }
            if(checked!=fed||finalDigest.digest().hex()!=expectedSha) throw IOException("Decoded source differs from completed bytes")
        }

        private suspend fun checkActive() {
            currentCoroutineContext().ensureActive()
            if(cancelled.get()||admission.yieldRequested.isCompleted) throw CancellationException("Optional source decode yielded")
            if(SystemClock.elapsedRealtime()>=deadline) throw IOException("Optional source decode deadline")
        }

        private fun closeWriter() {writer?.let {Os.close(it);writer=null}}
        fun cancelNative() {session?.takeUnless {nativeFinished.isCompleted}?.let {FFmpegKit.cancel(it.sessionId)}}

        fun finishReader() {
            try {source?.close()} finally {
                try {closeWriter()} finally {
                    readerFinished.set(true)
                    if(session==null) nativeFinished.complete(null)
                    try {cancelNative()} finally {releaseIfSettled()}
                }
            }
        }

        private fun releaseIfSettled() {
            if(readerFinished.get()&&nativeFinished.isCompleted&&released.compareAndSet(false,true)) {
                try {pipe?.let {FFmpegKitConfig.closeFFmpegPipe(it)}}
                finally {lease.release();permit.release();settled.complete(Unit)}
            }
        }
    }

    companion object {
        // These are work/disk limits, not allocations: each reader retains 64 KiB.
        private const val MAX_SOURCE_BYTES=16L*1024*1024*1024
        private const val MAX_TOTAL_BYTES=24L*1024*1024*1024
        private const val MAX_WALL_MS=2*60*60_000L
        private const val MAX_DECODE_PIXELS=1920*1080

        internal fun eligible(context:Context,inputs:Inputs):Boolean {
            val root=File(context.filesDir,"transfers").canonicalPath+File.separator
            val video=inputs.video.canonicalFile
            val audio=inputs.audio.canonicalFile
            if(video==audio||video.parentFile!=audio.parentFile||!video.path.startsWith(root)) return false
            if(listOf(video,audio).any {file->listOf("",".part",".resume",".complete",".ytdl").any {File(file.path+it).exists()}}) return false
            val memory=ActivityManager.MemoryInfo().also {context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)}
            val power=context.getSystemService(android.os.PowerManager::class.java)
            return !memory.lowMemory&&memory.availMem>=1024L*1024*1024&&!power.isPowerSaveMode&&
                (android.os.Build.VERSION.SDK_INT<29||power.currentThermalStatus<android.os.PowerManager.THERMAL_STATUS_MODERATE)
        }

        private fun ByteArray.hex()=joinToString("") {"%02x".format(it)}
    }
}
