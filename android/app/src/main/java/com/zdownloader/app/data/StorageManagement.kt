package com.zdownloader.app.data

import android.content.Context
import android.os.StatFs
import coil.imageLoader
import com.zdownloader.app.ZApplication
import com.zdownloader.app.domain.CleanupPolicy
import com.zdownloader.app.engine.NativeWork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class StorageUsage(val video:Long,val audio:Long,val temporary:Long,val safeTemporary:Long,val recoverableTemporary:Long,val cache:Long,val available:Long,val counted:Int,val missing:Int,val other:Long=0) {
    val total get()=video+audio+other
}
@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Singleton class StorageManagement @Inject constructor(@ApplicationContext private val context:Context,private val dao:DownloadDao,private val files:MediaFiles,private val settings:SettingsRepository) {
    private val app get()=context.applicationContext as ZApplication
    private val transfers get()=File(context.filesDir,"transfers")
    // Android may alias /data/user/0 to /data/data. Canonicalize the parent,
    // rejecting a link at this entry rather than rejecting legitimate ancestors.
    private fun direct(file:File)=file.canonicalFile==File(file.parentFile.canonicalFile,file.name)
    private fun bytes(file:File):Long {
        if(!direct(file)) return 0
        return if(file.isFile) file.length() else file.listFiles()?.sumOf(::bytes) ?: 0
    }
    private fun safelyDelete(file:File):Boolean {
        if(!direct(file)) return file.delete() // Remove the link itself, never traverse its target.
        if(file.isDirectory&&file.listFiles()?.all(::safelyDelete)!=true) return false
        return file.delete()
    }
    private suspend fun eligible(directory:File):Boolean {
        if(!direct(directory)||directory.parentFile?.canonicalFile!=transfers.canonicalFile) return false
        val row=dao.get(directory.name)
        return CleanupPolicy.eligible(row?.status,app.coordinator.active(directory.name),NativeWork.busy(directory),System.currentTimeMillis()-directory.lastModified())
    }
    suspend fun usage():StorageUsage=withContext(Dispatchers.IO) {
        var video=0L;var audio=0L;var other=0L;var counted=0;var missing=0
        for(row in dao.completed().first().distinctBy {it.uri ?: it.id}) {
            ensureActive()
            val file=row.uri?.let {try {files.metadata(it)} catch(e:CancellationException) {throw e} catch(_:Exception) {null}}
            if(file?.size!=null) {if(file.mime?.startsWith("audio/")==true) audio+=file.size else if(file.mime?.startsWith("video/")==true) video+=file.size else other+=file.size;counted++} else missing++
        }
        var temporary=0L;var safe=0L;var protected=0L
        for(directory in transfers.listFiles().orEmpty()) {ensureActive();val size=bytes(directory);temporary+=size;if(eligible(directory)) safe+=size else protected+=size}
        val art=File(context.cacheDir,"playback-art")
        StorageUsage(video,audio,temporary,safe,protected,bytes(art)+(context.imageLoader.diskCache?.size ?: 0),StatFs(context.filesDir.path).availableBytes,counted,missing,other)
    }
    suspend fun destination()=settings.current().destination
    suspend fun cleanTemporary(ids:Set<String>?=null):Long=withContext(Dispatchers.IO) {
        var removed=0L
        for(directory in transfers.listFiles().orEmpty()) {
            ensureActive()
            if(ids!=null&&directory.name !in ids) continue
            // Recheck current Room state and native leases immediately before each delete.
            if(eligible(directory)) {val size=bytes(directory);if(safelyDelete(directory)) removed+=size}
        }
        removed
    }
    suspend fun clearCaches()=withContext(Dispatchers.IO) {
        context.imageLoader.memoryCache?.clear();context.imageLoader.diskCache?.clear()
        File(context.cacheDir,"playback-art").listFiles()?.filter(::direct)?.forEach {it.delete()}
        app.extractor.clearMetadataCache()
        Unit
    }
    internal suspend fun duplicates():List<List<MediaFile>> = withContext(Dispatchers.IO) {
        val candidates=dao.completed().first().mapNotNull {row->row.uri?.let {try {files.metadata(it)} catch(e:CancellationException) {throw e} catch(_:Exception) {null}}}.distinctBy {it.uri}
            .filter {it.size!=null&&it.size>0}.groupBy {it.size}.values.filter {it.size>1}
        val result=mutableListOf<List<MediaFile>>()
        for(group in candidates) {
            val hashed=mutableMapOf<String,MutableList<MediaFile>>()
            for(file in group) {
                ensureActive();val digest=MessageDigest.getInstance("SHA-256");var count=0L
                try {context.contentResolver.openInputStream(android.net.Uri.parse(file.uri))?.use {source->val buffer=ByteArray(64*1024);while(true) {ensureActive();val n=source.read(buffer);if(n<0) break;digest.update(buffer,0,n);count+=n}}
                    if(count==file.size) hashed.getOrPut(digest.digest().joinToString("") {"%02x".format(it)}) {mutableListOf()}.add(file)
                } catch(e:CancellationException) {throw e} catch(_:Exception) { /* Skip inaccessible or changing files. */ }
            }
            result+=hashed.values.filter {it.size>1}
        }
        result
    }
}
