package com.zdownloader.app.status

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.zdownloader.app.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal suspend fun <T> cancellableForEach(items:Iterable<T>,action:suspend (T)->Unit) {
    for(item in items) {currentCoroutineContext().ensureActive();action(item)}
}

@Singleton class StatusSaveCoordinator @Inject constructor(@ApplicationContext private val context:Context,
    private val permissions:StatusPermissions,private val scanner:StatusMediaScanner,private val store:StatusStore,
    private val settings:SettingsRepository) {
    private val lock=Mutex()
    private val resolver get()=context.contentResolver
    private val staging get()=File(context.cacheDir,"status-saver-staging").apply {mkdirs()}
    private fun exists(saved:StatusStore.Saved):Boolean=runCatching {
        resolver.openFileDescriptor(Uri.parse(saved.uri),"r")?.use {it.statSize<0||it.statSize==saved.bytes}==true
    }.getOrDefault(false)
    suspend fun savedUri(item:StatusMedia):String?=withContext(Dispatchers.IO) {
        if(item.bytes==null||item.modified==null) null else store.forSource(item.key)?.takeIf {saved->
            exists(saved)&&try {hash(Uri.parse(saved.uri),saved.bytes).sha256==saved.hash} catch(e:CancellationException) {throw e} catch(_:Exception) {false}
        }?.uri
    }
    private suspend fun hash(uri:Uri,expected:Long?):StatusRules.CopyResult {
        val job=currentCoroutineContext()
        return resolver.openInputStream(uri)?.use {input->StatusRules.copy(input,object:java.io.OutputStream() {override fun write(value:Int) {};override fun write(bytes:ByteArray,offset:Int,length:Int) {}},expected) {job.ensureActive()}}
            ?: throw StatusException(StatusProblem.SAVE_FAILED)
    }
    private fun galleryPending(uri:Uri):Boolean? {
        val cursor=resolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)
            ?: throw StatusException(StatusProblem.SAVE_FAILED)
        return cursor.use {if(it.moveToFirst()) it.getInt(0)==1 else null}
    }
    private fun documentExists(uri:Uri):Boolean {
        if(uri.scheme!="content") throw StatusException(StatusProblem.SAVE_FAILED)
        val cursor=resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)
            ?: throw StatusException(StatusProblem.SAVE_FAILED)
        return cursor.use {it.moveToFirst()}
    }
    private fun removeOwned(uri:Uri,gallery:Boolean) {
        if(gallery) {
            if(resolver.delete(uri,null,null)>0) return
            if(galleryPending(uri)==null) return
            throw StatusException(StatusProblem.SAVE_FAILED)
        }
        if(uri.scheme!="content") throw StatusException(StatusProblem.SAVE_FAILED)
        if(DocumentsContract.deleteDocument(resolver,uri)) return
        if(!documentExists(uri)) return
        throw StatusException(StatusProblem.SAVE_FAILED)
    }
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun pendingGalleryUris(mime:String,name:String):List<Uri> {
        if(name.isBlank()) throw StatusException(StatusProblem.SAVE_FAILED)
        val collection=collection(mime,MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val cursor=resolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1",arrayOf(name),null)
            ?: throw StatusException(StatusProblem.SAVE_FAILED)
        return cursor.use {buildList {while(it.moveToNext()) add(android.content.ContentUris.withAppendedId(collection,it.getLong(0)))}}
    }
    private suspend fun recoverLocked() {
        cancellableForEach(store.pending()) {(id,pending)->
            val gallery=pending.optBoolean("gallery",true)
            val uri=pending.optString("uri").takeIf {it.isNotBlank()}?.let(Uri::parse)
            try {
                if(uri!=null) {
                    val committed=store.saved(pending.getString("hash"))?.takeIf {it.uri==uri.toString()&&exists(it)}
                    if(committed==null) {
                        if(gallery&&Build.VERSION.SDK_INT>=29) {
                            currentCoroutineContext().ensureActive()
                            when(galleryPending(uri)) {
                                null -> Unit // A non-null query with no row confirms absence.
                                true -> removeOwned(uri,true)
                                false -> {
                                    // Visible output is user media: recover it only when its bytes
                                    // match the journal; never delete a completed/edited item.
                                    val proof=hash(uri,pending.getLong("bytes"))
                                    if(proof.sha256!=pending.getString("hash")) throw StatusException(StatusProblem.SAVE_FAILED)
                                    store.remember(pending.getString("source_key"),StatusStore.Saved(proof.sha256,uri.toString(),pending.getString("mime"),proof.bytes,true))
                                }
                            }
                        } else {
                            currentCoroutineContext().ensureActive()
                            if(!documentExists(uri)) Unit
                            else {
                                val proof=hash(uri,pending.getLong("bytes"))
                                if(proof.sha256!=pending.getString("hash")) throw StatusException(StatusProblem.SAVE_FAILED)
                                store.remember(pending.getString("source_key"),StatusStore.Saved(proof.sha256,uri.toString(),pending.getString("mime"),proof.bytes,false))
                            }
                        }
                    }
                } else if(gallery&&Build.VERSION.SDK_INT>=29) {
                    currentCoroutineContext().ensureActive()
                    val collection=collection(pending.getString("mime"),MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val name=pending.getString("pending_name")
                    cancellableForEach(pendingGalleryUris(pending.getString("mime"),name)) {removeOwned(it,true)}
                    currentCoroutineContext().ensureActive()
                    if(pendingGalleryUris(pending.getString("mime"),name).isNotEmpty()) throw StatusException(StatusProblem.SAVE_FAILED)
                }
                currentCoroutineContext().ensureActive()
                store.clearPending(id)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) { /* Keep ownership journal for a later authorized retry. */ }
        }
        currentCoroutineContext().ensureActive()
        cancellableForEach(staging.listFiles()?.toList().orEmpty()) {file->
            withContext(NonCancellable) {file.delete()}
        }
    }
    suspend fun recover()=withContext(Dispatchers.IO) {lock.withLock {recoverLocked()}}
    private fun collection(mime:String,volume:String):Uri = if(mime.startsWith("image/")) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)
    suspend fun save(item:StatusMedia,export:Uri?=null):StatusSaved=withContext(Dispatchers.IO) {lock.withLock {
        recoverLocked()
        val source=Uri.parse(item.uri);permissions.requireReadable(source)
        if(export!=null&&sameDocument(source,export)) throw StatusException(StatusProblem.SAVE_FAILED)
        val current=scanner.inspect(source)
        if(current.type!=item.type||current.width!=item.width||current.height!=item.height ||
            item.bytes!=null&&current.bytes!=item.bytes || item.modified!=null&&current.modified!=item.modified) throw StatusException(StatusProblem.SOURCE_CHANGED)
        val id=UUID.randomUUID().toString();val file=File(staging,"$id.${item.type.extension}")
        try {
            val job=currentCoroutineContext()
            val copied=resolver.openInputStream(source)?.use {input->file.outputStream().use {output->StatusRules.copy(input,output,item.bytes) {job.ensureActive()}}}
                ?: throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)
            val after=scanner.inspect(source)
            if(item.modified!=null&&after.modified!=item.modified||after.bytes!=null&&after.bytes!=copied.bytes) throw StatusException(StatusProblem.SOURCE_CHANGED)
            scanner.validate(file,current)
            val old=store.saved(copied.sha256)
            val oldMatches=if(old!=null&&exists(old)) try {hash(Uri.parse(old.uri),old.bytes).sha256==copied.sha256} catch(e:CancellationException) {throw e} catch(_:Exception) {false} else false
            if(old!=null&&oldMatches) {
                store.remember(item.key,old)
                if(export!=null) runCatching {removeOwned(export,false)}
                return@withLock StatusSaved(old.uri,true,gallery=old.gallery)
            }
            val destination=settings.current().destination
            val custom=destination?.let {value->runCatching {
                val uri=Uri.parse(value)
                if(context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) null
                else StatusRules.customPath(uri.authority,DocumentsContract.getTreeDocumentId(uri),item.type.video)
            }.getOrNull()}
            val relative=custom ?: if(item.type.video) "Movies/MediaFlow/WhatsApp Statuses/" else "Pictures/MediaFlow/WhatsApp Statuses/"
            if(Build.VERSION.SDK_INT<29&&export==null) throw StatusException(StatusProblem.LEGACY_EXPORT_REQUIRED)
            val gallery=export==null
            val finalName="MediaFlow_Status_${System.currentTimeMillis()}_${copied.sha256.take(12)}.${item.type.extension}"
            val pendingName=".mediaflow-status-$id.pending.${item.type.extension}"
            val pending=JSONObject().put("hash",copied.sha256).put("bytes",copied.bytes).put("source_key",item.key)
                .put("mime",item.type.mime).put("gallery",gallery).put("pending_name",pendingName)
            var target:Uri?=null;var committed=false;var published=false
            try {
                job.ensureActive()
                withContext(NonCancellable) {
                    store.pending(id,pending)
                    target=export ?: resolver.insert(collection(item.type.mime,MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME,pendingName);put(MediaStore.MediaColumns.MIME_TYPE,item.type.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH,relative);put(MediaStore.MediaColumns.IS_PENDING,1)
                        put(if(item.type.video) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN,System.currentTimeMillis())
                    }) ?: throw StatusException(StatusProblem.SAVE_FAILED)
                    pending.put("uri",target.toString());store.pending(id,pending)
                }
                val output=target!!
                val written=resolver.openOutputStream(output,"w")?.use {sink->file.inputStream().use {input->
                    StatusRules.copy(input,sink,copied.bytes) {job.ensureActive()}.also {if(sink is java.io.FileOutputStream) sink.fd.sync()}
                }} ?: throw StatusException(StatusProblem.SAVE_FAILED)
                if(written.sha256!=copied.sha256||hash(output,copied.bytes).sha256!=copied.sha256) throw StatusException(StatusProblem.SAVE_FAILED)
                if(!gallery) published=true
                job.ensureActive()
                withContext(NonCancellable) {
                    if(gallery&&resolver.update(output,ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,finalName);put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)!=1) throw StatusException(StatusProblem.SAVE_FAILED)
                    // Once visible, preserve the verified original and its journal if
                    // index persistence fails. Recovery can finish the index later.
                    if(gallery) published=true
                    if(gallery) resolver.query(output,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use {cursor->
                        if(!cursor.moveToFirst()||cursor.getInt(0)!=0) throw StatusException(StatusProblem.SAVE_FAILED)
                    } ?: throw StatusException(StatusProblem.SAVE_FAILED)
                    store.remember(item.key,StatusStore.Saved(copied.sha256,output.toString(),item.type.mime,copied.bytes,gallery))
                    committed=true;store.clearPending(id)
                }
                StatusSaved(output.toString(),false,destination!=null&&custom==null,gallery)
            } catch(error:Exception) {
                if(!committed&&!published) withContext(NonCancellable) {
                    var cleaned=true
                    target?.let {uri->cleaned=runCatching {removeOwned(uri,gallery);true}.getOrDefault(false)}
                    if(cleaned) store.clearPending(id)
                }
                if(error is SecurityException) throw StatusException(StatusProblem.SAVE_FAILED)
                throw error
            }
        } finally {file.delete()}
    }}
    private fun sameDocument(source:Uri,target:Uri):Boolean {
        if(source.authority!=target.authority) return false
        if(source.buildUpon().clearQuery().fragment(null).build()==target.buildUpon().clearQuery().fragment(null).build()) return true
        return runCatching {DocumentsContract.getDocumentId(source)==DocumentsContract.getDocumentId(target)}.getOrDefault(false)
    }
}
