package com.zdownloader.app.extraction

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.R
import com.zdownloader.app.engine.LameEncoder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class ExtractionControl {
    val stopped=AtomicBoolean(false)
    val cancellationRequested=AtomicBoolean(false)
    @Volatile var session:Long?=null
    fun cancel() {cancellationRequested.set(true);stopNative()}
    fun stopNative() {stopped.set(true);session?.let(FFmpegKit::cancel)}
    fun check() {if(stopped.get()) throw CancellationException("Audio extraction cancelled")}
}

/** Local SAF input is opened by FFmpeg's seekable SAF protocol. The video is never staged/copied. */
internal class ExtractionEngine(private val context:Context) {
    private fun input(uri:Uri):String {
        require(uri.scheme=="content")
        return FFmpegKitConfig.getSafParameterForRead(context,uri)
    }
    suspend fun inspect(uri:Uri,control:ExtractionControl?=null):ExtractionSource=withContext(Dispatchers.IO) {
        var name="Video";var bytes:Long?=null
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {c->
            if(c.moveToFirst()) {name=c.getString(0) ?: name;if(!c.isNull(1)) bytes=c.getLong(1).takeIf {it>=0}}
        }
        val info=probe(input(uri),control)
        val streams=info.optJSONArray("streams") ?: throw ExtractionFailure(R.string.extract_unreadable)
        val rows=(0 until streams.length()).map {streams.getJSONObject(it)}
        if(rows.none {it.optString("codec_type")=="video"&&it.optJSONObject("disposition")?.optInt("attached_pic",0)!=1}) throw ExtractionFailure(R.string.extract_not_video)
        val duration=info.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()?.takeIf {it.isFinite()&&it>0}
        val tracks=rows.filter {it.optString("codec_type")=="audio"}.map {row->
            val tags=row.optJSONObject("tags")
            AudioTrack(row.getInt("index"),row.optString("codec_name"),row.optInt("channels"),row.optString("sample_rate").toIntOrNull() ?: 0,
                row.optString("duration").toDoubleOrNull()?.takeIf {it.isFinite()&&it>0} ?: duration,
                tags?.optString("language")?.takeIf {it.isNotBlank()&&it!="und"},tags?.optString("title")?.takeIf {it.isNotBlank()},row.optJSONObject("disposition")?.optInt("default")==1)
        }.filter {it.channels in 1..32&&it.sampleRate>0}
        if(tracks.isEmpty()) throw ExtractionFailure(R.string.extract_no_audio)
        ExtractionSource(uri.toString(),name,bytes,duration,tracks)
    }
    private suspend fun probe(path:String,control:ExtractionControl?=null):JSONObject {
        currentCoroutineContext().ensureActive();control?.check()
        val done=CompletableDeferred<FFprobeSession>()
        val session=FFprobeKit.executeWithArgumentsAsync(arrayOf("-v","error","-protocol_whitelist","file,pipe,saf","-show_streams","-show_format","-of","json",path),{done.complete(it)})
        control?.session=session.sessionId
        try {
            control?.check()
            val result=done.await()
            control?.check()
            if(!ReturnCode.isSuccess(result.returnCode)) throw ExtractionFailure(R.string.extract_unreadable)
            return JSONObject(result.output)
        } finally {
            if(!done.isCompleted) FFmpegKit.cancel(session.sessionId)
            withContext(NonCancellable) {done.await()}
            control?.session=null
        }
    }
    suspend fun execute(args:List<String>,control:ExtractionControl,progress:(Long)->Unit={}) {
        currentCoroutineContext().ensureActive();control.check()
        val done=CompletableDeferred<FFmpegSession>()
        val session=FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),{done.complete(it)},null,{progress(it.time.toLong())})
        control.session=session.sessionId
        try {
            control.check()
            val result=done.await()
            control.check()
            if(!ReturnCode.isSuccess(result.returnCode)) throw ExtractionFailure(R.string.extract_processing_error)
        } finally {
            if(!done.isCompleted) control.cancel()
            // Descriptors, native buffers and the output remain owned until native completion.
            withContext(NonCancellable) {done.await()}
            control.session=null
        }
    }
    suspend fun extract(source:ExtractionSource,track:AudioTrack,plan:ExtractionPlan,output:File,control:ExtractionControl,progress:(Long)->Unit)=withContext(Dispatchers.IO) {
        val base=listOf("-nostdin","-y","-v","error","-stats","-stats_period","0.25","-xerror","-threads","2","-protocol_whitelist","file,pipe,saf","-i",input(Uri.parse(source.uri)),
            "-map","0:${track.index}","-vn","-sn","-dn","-map_metadata","-1","-map_chapters","-1")
        if(!plan.copy&&plan.codec=="mp3") {
            coroutineScope {
                val pipe=ParcelFileDescriptor.createPipe()
                val decoder=async(Dispatchers.IO) {
                    try {execute(base+listOf("-ac",plan.channels.toString(),"-ar",plan.rate.toString(),"-f","s16le","pipe:${pipe[1].fd}"),control,progress)}
                    finally {pipe[1].close()}
                }
                try {
                    val code=LameEncoder.encodePipe(pipe[0].fd,output.path,plan.bitrate,plan.rate,plan.channels,control.stopped)
                    if(code!=0) {control.stopNative();throw ExtractionFailure(if(code==-3) R.string.extract_no_space else R.string.extract_processing_error)}
                    decoder.await()
                } finally {
                    if(!decoder.isCompleted) {control.stopNative();decoder.cancel()}
                    // LAME has returned and closed its duplicated reader. Close the
                    // original reader BEFORE joining FFmpeg, otherwise its blocked
                    // write still sees a live reader and cancellation deadlocks.
                    pipe[0].close()
                    withContext(NonCancellable) {decoder.join();pipe[1].close()}
                }
            }
        } else {
            val encoding=if(plan.copy) listOf("-c:a","copy") else listOf("-c:a","aac","-b:a","${plan.bitrate}k","-ar",plan.rate.toString())
            execute(base+encoding+(if(plan.extension=="m4a") listOf("-movflags","+faststart") else emptyList())+output.path,control,progress)
        }
        control.check()
        if(!output.isFile||output.length()==0L) throw ExtractionFailure(R.string.extract_processing_error)
    }
    suspend fun validate(file:File,plan:ExtractionPlan,expectedDuration:Double?,control:ExtractionControl,progress:(Long)->Unit={})=withContext(Dispatchers.IO) {
        control.check()
        val info=probe(file.path,control);val streams=info.getJSONArray("streams")
        if(streams.length()!=1) throw ExtractionFailure(R.string.extract_invalid_output)
        val audio=streams.getJSONObject(0)
        val format=info.getJSONObject("format")
        val duration=format.optString("duration").toDoubleOrNull()
        val names=format.optString("format_name").split(',')
        val expected=when(plan.extension) {"m4a"->"mp4";"mka"->"matroska";"opus","ogg"->"ogg";else->plan.extension}
        if(audio.optString("codec_type")!="audio"||audio.optString("codec_name")!=plan.codec||expected !in names||audio.optInt("channels")!=plan.channels||
            audio.optString("sample_rate").toIntOrNull()!=plan.rate||duration==null||!duration.isFinite()||duration<=0||
            expectedDuration!=null&&kotlin.math.abs(duration-expectedDuration)>maxOf(.5,expectedDuration*.01)) throw ExtractionFailure(R.string.extract_invalid_output)
        // Decode every audio packet, without decoding the source video or loading PCM in memory.
        execute(listOf("-nostdin","-v","error","-stats","-stats_period","0.25","-xerror","-err_detect","explode","-threads","2","-protocol_whitelist","file,pipe","-i",file.path,"-map","0:a:0","-f","null","-"),control,progress)
    }
}
