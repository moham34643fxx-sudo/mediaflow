package com.zdownloader.app.extraction

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import com.zdownloader.app.R
import com.zdownloader.app.engine.NativeWork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class ExtractionTask(val id:String,val source:String,val name:String,val plan:ExtractionPlan,val track:Int,val duration:Double?,
    val phase:ExtractionPhase=ExtractionPhase.QUEUED,val progress:Float?=null,val output:String?=null,val pending:String?=null,
    val valid:Boolean=false,val sha256:String?=null,val bytes:Long=0,val error:Int?=null,val export:String?=null)

/** An independent single-operation lane. Download rows, sessions and player state are not mutated. */
internal class ExtractionRepository(private val context:Context,
    val directory:File=File(context.filesDir,"audio-extraction").apply {mkdirs()},
    private val serviceStarter:(Intent)->Unit={ContextCompat.startForegroundService(context,it);Unit},
    private val beforePersist:(ExtractionTask)->Unit={}) {
    private val journal=AtomicFile(File(directory,"task.json"))
    private val lock=Mutex()
    private val mutable=MutableStateFlow(load())
    val task=mutable.asStateFlow()
    @Volatile private var control:ExtractionControl?=null
    @Volatile private var cancelledId:String?=null
    val engine=ExtractionEngine(context)
    private fun file(t:ExtractionTask)=File(directory,"${t.id}.${t.plan.extension}")
    private fun load():ExtractionTask?=runCatching {
        val j=JSONObject(journal.openRead().bufferedReader().use {it.readText()})
        val p=j.getJSONObject("plan")
        val phase=ExtractionPhase.valueOf(j.getString("phase"))
        ExtractionTask(j.getString("id"),j.getString("source"),j.getString("name"),ExtractionPlan(p.getString("extension"),p.getString("mime"),p.getString("codec"),p.getBoolean("copy"),p.getInt("bitrate"),p.getInt("channels"),p.getInt("rate")),
            j.getInt("track"),j.optString("duration").toDoubleOrNull(),if(phase.active) {if(j.optBoolean("valid")) ExtractionPhase.SAVE_FAILED else ExtractionPhase.INTERRUPTED} else phase,
            output=j.optString("output").takeIf {it.isNotBlank()},pending=j.optString("pending").takeIf {it.isNotBlank()},valid=j.optBoolean("valid"),sha256=j.optString("sha256").takeIf {it.isNotBlank()},bytes=j.optLong("bytes"),export=j.optString("export").takeIf {it.isNotBlank()})
    }.getOrNull()
    private fun persist(t:ExtractionTask) {
        beforePersist(t)
        val p=t.plan
        val j=JSONObject().put("id",t.id).put("source",t.source).put("name",t.name).put("track",t.track).put("duration",t.duration)
            .put("phase",t.phase.name).put("valid",t.valid).put("sha256",t.sha256).put("bytes",t.bytes).put("output",t.output).put("pending",t.pending).put("export",t.export)
            .put("plan",JSONObject().put("extension",p.extension).put("mime",p.mime).put("codec",p.codec).put("copy",p.copy).put("bitrate",p.bitrate).put("channels",p.channels).put("rate",p.rate))
        val stream=journal.startWrite()
        try {stream.write(j.toString().toByteArray());journal.finishWrite(stream)} catch(e:Exception) {journal.failWrite(stream);throw e}
    }
    private fun set(t:ExtractionTask) {persist(t);mutable.value=t}
    private fun phase(phase:ExtractionPhase) {set(mutable.value!!.copy(phase=phase,progress=null,error=null))}
    private var lastProgress=0L
    private fun progress(value:Float?,phase:ExtractionPhase,id:String) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastProgress<150) return
        lastProgress=now
        mutable.update {if(it?.id==id&&it.phase==phase) it.copy(progress=value?.coerceIn(0f,1f)) else it}
    }
    fun cancel(id:String?=task.value?.id) {if(id==task.value?.id&&task.value?.phase?.active==true) {cancelledId=id;control?.cancel()}}
    private fun startDiagnostic(error:Exception) {
        if(com.zdownloader.app.BuildConfig.DEBUG) runCatching {File(directory,"start-error.txt").writeText(error.stackTraceToString())}
    }
    suspend fun failStart(id:String,cause:Exception?=null)=withContext(Dispatchers.IO) {lock.withLock {
        cause?.let(::startDiagnostic)
        task.value?.takeIf {it.id==id&&it.phase.active}?.let {
            val failed=it.copy(phase=if(it.valid) ExtractionPhase.SAVE_FAILED else ExtractionPhase.FAILED,error=R.string.extract_background_error)
            runCatching {persist(failed)}
            mutable.value=failed
        }
    }}
    suspend fun start(source:ExtractionSource,track:AudioTrack,plan:ExtractionPlan,export:Uri?=null):Unit=withContext(Dispatchers.IO) {
        if(task.value?.phase?.active==true) throw ExtractionFailure(R.string.extract_busy)
        lock.withLock {
            recoverLocked()
            if(task.value?.phase?.active==true) throw ExtractionFailure(R.string.extract_busy)
            // Never discard a verified, unpublished result implicitly.
            if(task.value?.let {it.pending!=null||it.valid&&it.phase==ExtractionPhase.SAVE_FAILED&&file(it).isFile}==true) throw ExtractionFailure(R.string.extract_retained)
            task.value?.let {file(it).delete()}
            val id=UUID.randomUUID().toString()
            val t=ExtractionTask(id,source.uri,ExtractionPolicy.filename(source.name,plan.extension,id),plan,track.index,track.duration,export=export?.toString())
            if(Build.VERSION.SDK_INT<29&&export==null) throw ExtractionFailure(R.string.extract_choose_save)
            if(export?.toString()==source.uri) throw ExtractionFailure(R.string.extract_save_error)
            set(t)
            launchService(t)
        }
    }
    private fun launchService(t:ExtractionTask) {
        try {serviceStarter(Intent(context,AudioExtractionService::class.java).putExtra("id",t.id))}
        catch(error:Exception) {startDiagnostic(error);set(t.copy(phase=if(t.valid) ExtractionPhase.SAVE_FAILED else ExtractionPhase.FAILED,error=R.string.extract_background_error))}
    }
    suspend fun retrySave(export:Uri?=null)=withContext(Dispatchers.IO) {lock.withLock {
        recoverLocked()
        val t=task.value ?: return@withLock
        if(t.phase.active||t.pending!=null||!t.valid||!file(t).isFile||t.phase==ExtractionPhase.COMPLETED) return@withLock
        if(export?.toString()==t.source) throw ExtractionFailure(R.string.extract_save_error)
        val next=t.copy(phase=ExtractionPhase.QUEUED,error=null,export=export?.toString() ?: t.export.takeIf {Build.VERSION.SDK_INT<29})
        set(next);launchService(next)
    }}
    suspend fun discard()=withContext(Dispatchers.IO) {lock.withLock {
        val t=task.value ?: return@withLock
        if(t.phase.active) return@withLock
        recoverLocked()
        task.value?.let {file(it).delete()}
        journal.delete();mutable.value=null
    }}
    suspend fun recover()=withContext(Dispatchers.IO) {lock.withLock {recoverLocked()}}
    private fun recoverLocked() {
        val t=task.value ?: return
        if(t.phase.active) return
        t.pending?.let {value->
            val uri=Uri.parse(value)
            val ownership=outputOwnership(t,uri)
            val matches=(ownership==ExtractionOutputOwnership.VISIBLE||ownership==ExtractionOutputOwnership.EXPORTED)&&
                t.sha256!=null&&try {hash(uri)==t.sha256} catch(_:Exception) {false}
            when(extractionRecoveryAction(ownership,matches)) {
                ExtractionRecoveryAction.ADOPT->{
                    set(t.copy(phase=ExtractionPhase.COMPLETED,output=value,pending=null,progress=1f,error=null))
                    file(t).delete()
                }
                ExtractionRecoveryAction.REMOVE_PENDING->{
                    if(removeOwnedPending(t,uri)) set(t.copy(pending=null))
                    else preserveOutput(t)
                }
                ExtractionRecoveryAction.CLEAR_MISSING->set(t.copy(pending=null))
                ExtractionRecoveryAction.PRESERVE->preserveOutput(t)
            }
        }
        val current=task.value ?: return
        if(!current.valid&&!current.phase.active) file(current).delete()
    }
    private fun preserveOutput(t:ExtractionTask) {
        set(t.copy(phase=ExtractionPhase.SAVE_FAILED,error=R.string.extract_output_preserved))
    }
    private fun outputOwnership(t:ExtractionTask,uri:Uri):ExtractionOutputOwnership {
        // A provider exception is an ownership failure, not permission to clean up.
        return try {
            val gallery=Build.VERSION.SDK_INT>=29&&t.export==null&&uri.authority==MediaStore.AUTHORITY
            val columns=if(gallery) arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else arrayOf("_size")
            val cursor=context.contentResolver.query(uri,columns,null,null,null) ?: return ExtractionOutputOwnership.UNCERTAIN
            cursor.use {
                when {
                    !it.moveToFirst()->ExtractionOutputOwnership.MISSING
                    !gallery->ExtractionOutputOwnership.EXPORTED
                    it.isNull(0)->ExtractionOutputOwnership.UNCERTAIN
                    it.getInt(0)==0->ExtractionOutputOwnership.VISIBLE
                    it.getInt(0)==1&&it.getString(1)==context.packageName->ExtractionOutputOwnership.OWNED_PENDING
                    else->ExtractionOutputOwnership.UNCERTAIN
                }
            }
        } catch(_:Exception) {ExtractionOutputOwnership.UNCERTAIN}
    }
    private fun removeOwnedPending(t:ExtractionTask,uri:Uri):Boolean {
        when(outputOwnership(t,uri)) {
            ExtractionOutputOwnership.MISSING->return true
            ExtractionOutputOwnership.OWNED_PENDING->Unit
            else->return false
        }
        // Repeat ownership in the mutation itself: a publication between query and delete must survive.
        val removed=context.contentResolver.delete(uri,
            "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf(context.packageName))
        return removed==1||outputOwnership(t,uri)==ExtractionOutputOwnership.MISSING
    }
    private fun pendingAfterFailure(t:ExtractionTask,failure:Exception):String? {
        val pending=t.pending ?: return null
        return try {if(removeOwnedPending(t,Uri.parse(pending))) null else pending}
        catch(cleanup:Exception) {
            // Any provider failure leaves ownership uncertain; retain the journal, never guess-delete.
            failure.addSuppressed(cleanup)
            pending
        }
    }
    suspend fun run(id:String,onChange:(ExtractionTask)->Unit)=withContext(Dispatchers.IO) {lock.withLock {
        var t=task.value?.takeIf {it.id==id&&it.phase.active} ?: return@withLock
        val own=ExtractionControl();control=own
        if(cancelledId==id) own.cancel()
        val scope=currentCoroutineContext()
        val watch=CoroutineScope(scope).launch {task.collect {if(it!=null) onChange(it)}}
        val lease=NativeWork.acquire(directory)
        val cancellation=CoroutineScope(scope).launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {own.cancel()}}
        val out=file(t)
        try {
            if(!t.valid) {
                phase(ExtractionPhase.READING)
                val source=engine.inspect(Uri.parse(t.source),own);own.check()
                val track=source.tracks.firstOrNull {it.index==t.track} ?: throw ExtractionFailure(R.string.extract_no_audio)
                if(t.plan.copy&&track.codec!=t.plan.codec) throw ExtractionFailure(R.string.extract_source_changed)
                t=t.copy(duration=track.duration)
                val estimated=if(t.plan.copy) 16L*1024*1024 else ((track.duration ?: 0.0)*t.plan.bitrate*125).toLong()+16L*1024*1024
                if(StatFs(directory.path).availableBytes<estimated*2) throw ExtractionFailure(R.string.extract_no_space)
                phase(ExtractionPhase.EXTRACTING)
                engine.extract(source,track,t.plan,out,own) {ms->progress(track.duration?.let {(ms/1000.0/it).toFloat()},ExtractionPhase.EXTRACTING,t.id)}
                phase(ExtractionPhase.VALIDATING)
                engine.validate(out,t.plan,track.duration,own) {ms->progress(track.duration?.let {(ms/1000.0/it).toFloat()},ExtractionPhase.VALIDATING,t.id)}
                own.check()
                set(task.value!!.copy(valid=true,bytes=out.length(),duration=track.duration,sha256=hash(out,own)))
            }
            own.check();phase(ExtractionPhase.SAVING)
            publish(own)
            out.delete()
        } catch(e:Exception) {
            val latest=task.value!!
            if(latest.phase!=ExtractionPhase.COMPLETED) {
                withContext(NonCancellable) {
                    val pending=pendingAfterFailure(latest,e)
                    val cancelled=e is CancellationException||own.cancellationRequested.get()
                    val failed=latest.copy(phase=if(pending!=null) ExtractionPhase.SAVE_FAILED else if(cancelled) ExtractionPhase.CANCELLED else if(latest.valid) ExtractionPhase.SAVE_FAILED else ExtractionPhase.FAILED,
                        pending=pending,progress=null,error=if(pending!=null) R.string.extract_output_preserved else if(cancelled) null else (e as? ExtractionFailure)?.messageId ?: if(latest.valid) R.string.extract_save_error else R.string.extract_processing_error)
                    try {set(failed)} catch(persistence:Exception) {mutable.value=failed;e.addSuppressed(persistence);startDiagnostic(e)}
                    if(cancelled||!latest.valid) out.delete()
                }
            }
        } finally {
            withContext(NonCancellable) {cancellation.cancelAndJoin();watch.cancelAndJoin()}
            control=null;lease.release()
        }
    }}
    private fun hash(file:File,control:ExtractionControl):String=file.inputStream().use {stream->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        while(true) {control.check();val n=stream.read(buffer);if(n<0) break;digest.update(buffer,0,n)}
        digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun hash(uri:Uri,own:ExtractionControl?=null):String=context.contentResolver.openInputStream(uri)?.use {stream->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        while(true) {own?.check();val n=stream.read(buffer);if(n<0) break;digest.update(buffer,0,n)}
        digest.digest().joinToString("") {"%02x".format(it)}
    } ?: throw ExtractionFailure(R.string.extract_save_error)
    private suspend fun publish(control:ExtractionControl) {
        val t=task.value!!;val resolver=context.contentResolver
        val uri=t.export?.let(Uri::parse) ?: if(Build.VERSION.SDK_INT>=29) resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,t.name);put(MediaStore.MediaColumns.MIME_TYPE,t.plan.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Music/MediaFlow/Extracted Audio/");put(MediaStore.MediaColumns.IS_PENDING,1)
        }) else null
        if(uri==null) throw ExtractionFailure(R.string.extract_save_error)
        try {set(t.copy(pending=uri.toString()))} catch(error:Exception) {
            // If rollback is uncertain, keep the URI in memory so the failure path
            // can persist it and a second save cannot silently create another copy.
            var removed=false
            try {removed=removeOwnedPending(t,uri)} catch(cleanup:Exception) {error.addSuppressed(cleanup)}
            if(!removed) mutable.value=t.copy(pending=uri.toString())
            throw error
        }
        currentCoroutineContext().ensureActive();control.check()
        resolver.openOutputStream(uri,"w")?.use {sink->file(t).inputStream().use {input->
            val buffer=ByteArray(64*1024);var copied=0L
            while(true) {currentCoroutineContext().ensureActive();control.check();val n=input.read(buffer);if(n<0) break;sink.write(buffer,0,n);copied+=n;progress(copied.toFloat()/t.bytes,ExtractionPhase.SAVING,t.id)}
            sink.flush();if(sink is java.io.FileOutputStream) sink.fd.sync()
            if(copied!=t.bytes) throw ExtractionFailure(R.string.extract_save_error)
        }} ?: throw ExtractionFailure(R.string.extract_save_error)
        if(hash(uri,control)!=t.sha256) throw ExtractionFailure(R.string.extract_save_error)
        currentCoroutineContext().ensureActive();control.check()
        withContext(NonCancellable) {
            if(Build.VERSION.SDK_INT>=29&&t.export==null) {
                if(resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)!=1) throw ExtractionFailure(R.string.extract_save_error)
                resolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE),null,null,null)?.use {c->
                    if(!c.moveToFirst()||c.getInt(0)!=0||c.getLong(1)!=t.bytes) throw ExtractionFailure(R.string.extract_save_error)
                } ?: throw ExtractionFailure(R.string.extract_save_error)
            }
            set(task.value!!.copy(phase=ExtractionPhase.COMPLETED,output=uri.toString(),pending=null,progress=1f,error=null))
        }
    }
}
