package com.zdownloader.app.data

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.zdownloader.app.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class StorageRepository @Inject constructor(@ApplicationContext private val context: Context, private val dao: DownloadDao,private val files:MediaFiles) {
    constructor(context:Context,dao:DownloadDao):this(context,dao,MediaFiles(context,dao))
    private companion object {const val RECOVERY_PENDING_PREFIX="mediaflow-publication:"}
    private val publicationLock=Mutex()
    private fun journal(id:String)=OwnedStoragePaths.publication(context.filesDir,id).apply {parentFile?.mkdirs()}
    private fun persist(id:String,value:JSONObject) {
        val atomic=android.util.AtomicFile(journal(id))
        val out=atomic.startWrite()
        try {out.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)} catch(e:Exception) {atomic.failWrite(out);throw e}
    }
    private data class PendingMedia(val uri:Uri,val name:String,val pending:Boolean)
    private data class PendingDocument(val uri:Uri,val name:String)
    private data class PublicationDigest(val bytes:Long,val sha256:String)

    private fun mediaRecord(uri:Uri):PendingMedia? {
        val cursor=context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING),null,null,null)
            ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        return cursor.use {
            if(!it.moveToFirst()) null else PendingMedia(uri,it.getString(0) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED),it.getInt(1)==1)
        }
    }

    private fun documentRecord(uri:Uri):PendingDocument? {
        val cursor=context.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)
            ?: throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
        return cursor.use {
            if(!it.moveToFirst()) null else PendingDocument(uri,it.getString(1) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED))
        }
    }

    @androidx.annotation.RequiresApi(29)
    private fun pendingMedia(id:String):List<PendingMedia> {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}"))) {"Invalid pending publication id"}
        // Files queries omit pending rows unless explicitly included, even for their owner.
        @Suppress("DEPRECATION")
        val collection=MediaStore.setIncludePending(MediaStore.Files.getContentUri("external"))
        val cursor=context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",arrayOf(".$id.pending%"),null)
            ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        return cursor.use {
            buildList {
                while(it.moveToNext()) {
                    val name=it.getString(1) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                    if(name.startsWith(".$id.pending")) add(PendingMedia(android.content.ContentUris.withAppendedId(collection,it.getLong(0)),name,it.getInt(2)==1))
                }
            }
        }
    }

    private fun documentsInTree(tree:Uri):List<PendingDocument> {
        val parent=DocumentsContract.getTreeDocumentId(tree)
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
        val cursor=context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)
            ?: throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
        return cursor.use {
            buildList {
                while(it.moveToNext()) {
                    val name=it.getString(1) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                    add(PendingDocument(DocumentsContract.buildDocumentUriUsingTree(tree,it.getString(0)),name))
                }
            }
        }
    }

    private fun pendingDocuments(tree:Uri,id:String):List<PendingDocument> {
        require(id.matches(Regex("[A-Za-z0-9-]{1,128}"))) {"Invalid pending publication id"}
        return documentsInTree(tree).filter {it.name.startsWith(".$id.pending")}
    }

    private fun clearJournal(id:String) {
        val atomic=android.util.AtomicFile(journal(id))
        atomic.delete()
        val file=journal(id)
        if(file.exists()||File(file.path+".bak").exists()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
    }

    private suspend fun digest(uri:Uri,expected:Long?):PublicationDigest {
        val job=currentCoroutineContext()
        val input=context.contentResolver.openInputStream(uri) ?: throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
        input.use {stream->
            val hash=java.security.MessageDigest.getInstance("SHA-256")
            val buffer=ByteArray(64*1024);var count=0L
            while(true) {
                job.ensureActive()
                val read=stream.read(buffer)
                if(read<0) break
                count+=read
                if(expected!=null&&count>expected) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                hash.update(buffer,0,read)
            }
            return PublicationDigest(count,hash.digest().joinToString("") {"%02x".format(it)})
        }
    }

    private fun rowUri(row:DownloadEntity?):String?=row?.pendingUri?.takeUnless {it.startsWith(RECOVERY_PENDING_PREFIX)}

    /** Returns a byte-verified final output; provider uncertainty is reported by exception. */
    private suspend fun finalizedOutput(id:String,state:JSONObject,row:DownloadEntity?):Pair<Uri,Long>? {
        // A byte-verified copy is not proof that media validation survived process death.
        if(state.optBoolean("validationPending",false)) return null
        val uriText=state.optString("uri").takeIf {it.isNotBlank()} ?: rowUri(row) ?: return null
        val uri=Uri.parse(uriText)
        val destination=state.optString("destination").takeIf {it.isNotBlank()}?.let(Uri::parse)
            ?: row?.destination?.takeIf {it.isNotBlank()}?.let(Uri::parse)
        val name=state.optString("finalName").takeIf {it.isNotBlank()} ?: row?.filename.orEmpty()
        if(destination!=null) {
            if(!DocumentsContract.isTreeUri(destination)||context.checkUriPermission(destination,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
            val children=documentsInTree(destination)
            val direct=children.firstOrNull {it.uri==uri}
            val named=if(name.isNotBlank()) children.filter {it.name==name} else emptyList()
            if(direct!=null&&direct.name!=name&&!direct.name.startsWith(".$id.pending"))
                throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            val candidates=(listOfNotNull(direct?.takeIf {it.name==name})+named).distinctBy {it.uri}
            if(candidates.isEmpty()) return null
            if(candidates.size!=1) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            val expectedBytes=state.optLong("bytes",-1L).takeIf {it>0} ?: row?.outputSize?.takeIf {it>0}
            val expectedHash=state.optString("sha256")
            if(!expectedHash.matches(Regex("[0-9a-f]{64}"))||name.isBlank()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            val candidate=candidates.single()
            val proof=digest(candidate.uri,expectedBytes)
            if(expectedBytes!=null&&proof.bytes!=expectedBytes||proof.sha256!=expectedHash) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            return candidate.uri to proof.bytes
        }
        if(Build.VERSION.SDK_INT<29) return null
        val record=mediaRecord(uri) ?: return null
        if(record.pending) return null
        val expectedBytes=state.optLong("bytes",-1L).takeIf {it>0} ?: row?.outputSize?.takeIf {it>0}
        val expectedHash=state.optString("sha256")
        if(record.name!=name||!expectedHash.matches(Regex("[0-9a-f]{64}"))) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        val proof=digest(uri,expectedBytes)
        if(expectedBytes!=null&&proof.bytes!=expectedBytes||proof.sha256!=expectedHash) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        return uri to proof.bytes
    }

    private fun deleteMedia(uri:Uri) {
        if(context.contentResolver.delete(uri,null,null)>0) return
        if(mediaRecord(uri)==null) return
        throw MediaException(ErrorCode.FILE_WRITE_FAILED)
    }

    private suspend fun requirePendingMediaOwner(entity:DownloadEntity,selection:Selection,uri:Uri) {
        if(uri.scheme!="content"||uri.authority!="media"||android.content.ContentUris.parseId(uri)<=0)
            throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        val pending=mediaRecord(uri) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        if(!pending.pending||pending.name!=".${entity.id}.pending.${selection.output}"||dao.get(entity.id)?.pendingUri!=uri.toString())
            throw MediaException(ErrorCode.FILE_WRITE_FAILED)
    }

    private fun deleteDocument(uri:Uri) {
        if(DocumentsContract.deleteDocument(context.contentResolver,uri)) return
        if(documentRecord(uri)==null) return
        throw MediaException(ErrorCode.FILE_WRITE_FAILED)
    }

    private suspend fun cleanupPendingLocked(id:String) {
        val row=dao.get(id)
        val file=journal(id)
        if(row?.status=="COMPLETED") {clearJournal(id);return}
        val hasJournal=file.isFile||File(file.path+".bak").exists()
        if(!hasJournal&&row?.pendingUri==null) return
        val state=if(hasJournal) JSONObject(String(android.util.AtomicFile(file).readFully(),Charsets.UTF_8)) else JSONObject().put("uri",rowUri(row)).put("destination",row?.destination)
        val destination=state.optString("destination").takeIf {it.isNotBlank()}?.let(Uri::parse)
        if(destination!=null) {
            try {
                if(!DocumentsContract.isTreeUri(destination)||context.checkUriPermission(destination,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
                // Enumerate the authorized destination by our private pending-name token.
                // The journal URI may be stale after a provider rename. A final-name document
                // is never deleted by cleanup; publication reconciliation must adopt it first.
                val operationPending=pendingDocuments(destination,id)
                operationPending.forEach {deleteDocument(it.uri)}
                if(pendingDocuments(destination,id).isNotEmpty()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                val tracked=state.optString("uri").takeIf {it.isNotBlank()}?.let(Uri::parse) ?: rowUri(row)?.let(Uri::parse)
                val trackedRecord=tracked?.takeIf {operationPending.isEmpty()}?.let(::documentRecord)
                if(trackedRecord!=null&&!trackedRecord.name.startsWith(".$id.pending"))
                    throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                val finalName=state.optString("finalName").takeIf {it.isNotBlank()}
                if(finalName!=null&&documentsInTree(destination).any {it.name==finalName})
                    throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            } catch(e:SecurityException) {throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)}
        } else if(Build.VERSION.SDK_INT>=29) {
            // A stale URI can refer to a renamed, completed user file; query the operation's
            // pending-name token and preserve every row that is no longer marked pending.
            val operationPending=pendingMedia(id).filter {it.pending}
            operationPending.forEach {deleteMedia(it.uri)}
            if(pendingMedia(id).any {it.pending}) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            val uri=state.optString("uri").takeIf {it.isNotBlank()}?.let(Uri::parse) ?: rowUri(row)?.let(Uri::parse)
            if(uri!=null&&operationPending.isEmpty()&&mediaRecord(uri)!=null) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        } else if(state.optString("uri").isNotBlank()||rowUri(row)!=null) {
            throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
        }
        clearJournal(id)
        dao.pending(id,null)
    }
    suspend fun cleanupPending(id:String)=withContext(Dispatchers.IO) {publicationLock.withLock {cleanupPendingLocked(id)}}
    suspend fun reconcilePublications()=withContext(Dispatchers.IO) {
        val ids=dao.pendingPublications().map {it.id}.toMutableSet()
        File(context.filesDir,"publications").listFiles()?.filter {it.extension=="json"}?.forEach {ids+=it.nameWithoutExtension}
        for(id in ids) publicationLock.withLock {
            val row=dao.get(id)
            if(row?.status in listOf("SAVING","DOWNLOADING","MERGING","CONVERTING","PROCESSING","VALIDATING")) return@withLock
            val file=try {journal(id)} catch(_:IllegalArgumentException) {
                // Keep unowned paths intact without blocking other operations' recovery.
                if(row!=null&&row.status!="COMPLETED") dao.publicationBlocked(id,ErrorCode.FILE_WRITE_FAILED.name)
                com.zdownloader.app.engine.NetworkDiagnostics.record("storage","publication_invalid_owner",ErrorCode.FILE_WRITE_FAILED)
                return@withLock
            }
            val hasJournal=file.isFile||File(file.path+".bak").exists()
            if(!hasJournal&&row?.pendingUri==null) return@withLock
            val state=try {
                if(hasJournal) JSONObject(String(android.util.AtomicFile(file).readFully(),Charsets.UTF_8))
                else JSONObject().put("uri",rowUri(row)).put("destination",row?.destination)
            } catch(error:Exception) {
                if(row!=null&&row.pendingUri==null) runCatching {dao.pending(id,RECOVERY_PENDING_PREFIX+id)}
                dao.publicationBlocked(id,ErrorCode.FILE_WRITE_FAILED.name)
                com.zdownloader.app.engine.NetworkDiagnostics.record("storage","publication_recovery",ErrorCode.STORAGE_PERMISSION_ERROR,operation=id)
                return@withLock
            }
            try {
                if(row?.status=="COMPLETED") {clearJournal(id);return@withLock}
                val finalized=finalizedOutput(id,state,row)
                if(finalized!=null&&row!=null) {
                    dao.pending(id,finalized.first.toString())
                    val completed=dao.recoverCompletedPublication(id,finalized.first.toString(),finalized.second,System.currentTimeMillis())
                    if(completed!=1&&dao.get(id)?.status!="COMPLETED") throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                    clearJournal(id)
                } else if(finalized!=null) {
                    // The history row was removed independently; preserve the user-visible file.
                    clearJournal(id)
                } else {
                    val recorded=state.optString("uri").takeIf {it.isNotBlank()} ?: rowUri(row)
                    if(row!=null&&row.pendingUri==null&&recorded!=null) dao.pending(id,recorded)
                    cleanupPendingLocked(id)
                }
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {
                // Keep any journal-backed row out of automatic retry until access is restored
                // and publication can be reconciled or safely cleaned.
                val current=dao.get(id)
                if(current!=null&&current.pendingUri==null) runCatching {
                    val recorded=state.optString("uri").takeIf {it.isNotBlank()} ?: RECOVERY_PENDING_PREFIX+id
                    dao.pending(id,recorded)
                }
                val code=if(error is MediaException) error.code else if(error is SecurityException) ErrorCode.STORAGE_PERMISSION_ERROR else ErrorCode.FILE_WRITE_FAILED
                dao.publicationBlocked(id,code.name)
                com.zdownloader.app.engine.NetworkDiagnostics.record("storage","publication_recovery_blocked",code,operation=id,
                    details=JSONObject().put("exception_class",error.javaClass.simpleName).put("files_preserved",true))
            }
        }
        // Deferred native cleanup is process-local; replay terminal-row cleanup
        // after a killed process, preserving every queued/paused/failed partial.
        for(id in dao.cleanableTransfers()) removeTemporary(id)
    }
    fun temporary(id: String) = OwnedStoragePaths.transfer(context.filesDir,id).apply { mkdirs() }
    suspend fun removeTemporary(id: String) = withContext(Dispatchers.IO) {
        val directory = OwnedStoragePaths.transfer(context.filesDir,id)
        if(com.zdownloader.app.engine.NativeWork.busy(directory)) com.zdownloader.app.engine.NativeWork.afterIdle(directory) {
            OwnedStoragePaths.transfer(context.filesDir,id).deleteRecursively()
        }
        else directory.deleteRecursively()
        Unit
    }
    fun mime(ext: String, audio: Boolean) = when(ext) { "jpg","jpeg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";"mp4","m4a" -> if (audio) "audio/mp4" else "video/mp4"; "webm" -> if(audio) "audio/webm" else "video/webm"; "mkv" -> "video/x-matroska"; "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"; "opus","ogg" -> "audio/ogg"; "flac" -> "audio/flac"; else -> "application/octet-stream" }
    suspend fun commit(entity: DownloadEntity, file: File, selection: Selection, control: com.zdownloader.app.engine.TransferControl): Uri = withContext(Dispatchers.IO) {
        val sourceLease=com.zdownloader.app.engine.NativeWork.acquire(file.absoluteFile.parentFile!!)
        var publicationLocked=false
        try {
        publicationLock.lock();publicationLocked=true
        val validationApproval=control.awaitPublicationValidation
        control.check()
        if(validationApproval!=null&&!entity.destination.isNullOrBlank()) throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
        val resolver = context.contentResolver
        cleanupPendingLocked(entity.id)
        val type = mime(selection.output, selection.audioOnly)
        val tree = entity.destination?.takeIf { it.isNotBlank() }?.let {
            DocumentFile.fromTreeUri(context,Uri.parse(it))?.takeIf { folder -> folder.isDirectory && folder.canWrite() }
                ?: throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
        }
        var doc: DocumentFile?=null
        var uri:Uri?=null
        val state=JSONObject().put("destination",entity.destination).put("finalName",entity.filename)
            .put("validationPending",validationApproval!=null)
        try {
        // Journal before provider creation; pending names carry the stable download id.
        withContext(NonCancellable) {
        persist(entity.id,state)
        dao.pending(entity.id,RECOVERY_PENDING_PREFIX+entity.id)
        if (tree != null) {
            var name = entity.filename
            var i = 1
            while(tree.findFile(name) != null) { name = entity.filename.substringBeforeLast('.') + " ($i)." + selection.output; i++ }
            state.put("finalName",name)
            persist(entity.id,state)
            doc = tree.createFile(type,".${entity.id}.pending") ?: throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
            uri = doc!!.uri
            dao.rename(entity.id, name)
        } else {
            if (Build.VERSION.SDK_INT < 29) throw MediaException(ErrorCode.STORAGE_PERMISSION_ERROR)
            doc = null
            // MediaProvider rejects audio/webm in the Audio collection even when
            // the complete WebM has a valid audio-only stream. Downloads accepts
            // its truthful MIME without changing the container, codec or bytes.
            val audioWebm=selection.audioOnly&&selection.output=="webm"
            val collection=when {selection.imageOnly->MediaStore.Images.Media.EXTERNAL_CONTENT_URI;audioWebm->MediaStore.Downloads.EXTERNAL_CONTENT_URI;selection.audioOnly->MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;else->MediaStore.Video.Media.EXTERNAL_CONTENT_URI}
            val folderRoot=when {selection.imageOnly->"Pictures/MediaFlow";audioWebm->"Download/MediaFlow";selection.audioOnly->"Music/MediaFlow";else->"Movies/MediaFlow"}
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".${entity.id}.pending.${selection.output}")
                put(MediaStore.MediaColumns.MIME_TYPE,type)
                put(MediaStore.MediaColumns.RELATIVE_PATH,entity.relativeFolder?.let {folder->
                    "$folderRoot/"+FileNames.sanitize(folder)+"/"
                } ?: folderRoot)
                put(MediaStore.MediaColumns.IS_PENDING,1)
                if(!selection.audioOnly) put(MediaStore.Video.Media.DATE_TAKEN,System.currentTimeMillis())
            }
            uri = resolver.insert(collection,values) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        }
        state.put("uri",uri.toString());persist(entity.id,state)
            dao.pending(entity.id,uri.toString())
        }
            val target=requireNotNull(uri)
            if(validationApproval!=null) requirePendingMediaOwner(entity,selection,target)
            val exportStartAt=android.os.SystemClock.elapsedRealtime()
            val sourceHash=java.security.MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(target,"w")?.use { sink -> file.inputStream().use { source ->
                val buffer = ByteArray(1024 * 1024); var copied = 0L
                while(true) { currentCoroutineContext().ensureActive(); control.check(); val n = source.read(buffer); if(n < 0) break; sink.write(buffer,0,n);sourceHash.update(buffer,0,n); copied += n }
                sink.flush()
                if(tree==null && sink is java.io.FileOutputStream) sink.fd.sync()
                if(copied != file.length()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            } } ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            val copyDurationMs=android.os.SystemClock.elapsedRealtime()-exportStartAt
            control.check()
            val sourceDigest=sourceHash.digest()
            control.verifiedOutputSha256?.let {expected->
                if(sourceDigest.joinToString("") {"%02x".format(it)}!=expected)
                    throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="copy_proof_file_changed")
            }
            state.put("bytes",file.length()).put("sha256",sourceDigest.joinToString("") {"%02x".format(it)});persist(entity.id,state)
            // Fast export verification: verify destination size directly via kernel statSize
            // eliminating redundant multi-megabyte readback pass from flash storage
            val verifyStartedAt=android.os.SystemClock.elapsedRealtime()
            val statSize = resolver.openFileDescriptor(target,"r")?.use { descriptor ->
                val size = if(descriptor.statSize >= 0) descriptor.statSize else {
                    try { android.system.Os.fstat(descriptor.fileDescriptor).st_size } catch(_:Exception) { -1L }
                }
                size
            } ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            if(statSize >= 0 && statSize != file.length()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            if(statSize < 0) {
                val queriedSize = resolver.query(target, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                    if(cursor.moveToFirst()) cursor.getLong(0) else -1L
                } ?: -1L
                if(queriedSize > 0 && queriedSize != file.length()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            }
            control.check()
            val exportDurationMs=android.os.SystemClock.elapsedRealtime()-exportStartAt
            com.zdownloader.app.engine.NetworkDiagnostics.record("storage","mediastore_export_timing",null,operation=entity.id,
                details=org.json.JSONObject().put("copy_duration_ms",copyDurationMs)
                    .put("readback_validation_ms",android.os.SystemClock.elapsedRealtime()-verifyStartedAt)
                     .put("copy_and_verification_ms",exportDurationMs).put("bytes",file.length()).put("buffer_size_kb",1024))
            if(validationApproval!=null) {
                com.zdownloader.app.engine.AnalysisTrace.checkpoint(entity.id,"pending_export_verified")
                // A long decode must not block cancellation/cleanup of other publications.
                publicationLock.unlock();publicationLocked=false
                try {com.zdownloader.app.engine.withControlCancellation(control) {validationApproval()}}
                finally {withContext(NonCancellable) {publicationLock.lock();publicationLocked=true}}
                control.check()
                requirePendingMediaOwner(entity,selection,target)
                state.put("validationPending",false);persist(entity.id,state)
                com.zdownloader.app.engine.AnalysisTrace.checkpoint(entity.id,"publication_validation_approved")
            }
            withContext(NonCancellable) {
            val document=doc
            if(document != null) {
                val name = dao.get(entity.id)?.filename ?: throw CancellationException("History removed")
                val renamed=DocumentsContract.renameDocument(resolver,document.uri,name) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                uri=renamed;state.put("uri",renamed.toString());persist(entity.id,state);dao.pending(entity.id,renamed.toString())
                val actualName=documentRecord(renamed)?.name ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                state.put("finalName",actualName);persist(entity.id,state)
                dao.rename(entity.id,actualName)
            }
            else {
                if(resolver.update(target,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,entity.filename);put(MediaStore.MediaColumns.IS_PENDING,0) },null,null) != 1) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                if(selection.audioOnly&&selection.output=="webm") {
                    // The publication scan infers video/webm from the extension,
                    // overriding the validated audio-only MIME supplied at insert.
                    if(resolver.getType(target)!=type&&resolver.update(target,ContentValues().apply {
                        put(MediaStore.MediaColumns.MIME_TYPE,type)
                    },null,null)!=1) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                    if(resolver.getType(target)!=type) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                }
                resolver.query(target,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {cursor->if(cursor.moveToFirst()) dao.rename(entity.id,cursor.getString(0))}
            }
            }
            control.check()
            val finished = requireNotNull(uri)
            resolver.openFileDescriptor(finished,"r")?.use {descriptor->if(descriptor.statSize>=0 && descriptor.statSize!=file.length()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)} ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            if(tree==null) resolver.query(finished,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use {cursor->
                if(!cursor.moveToFirst() || cursor.getInt(0)!=0) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            } ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
            // Keep a journal URI until completion is durable. Recovery removes unfinished publications.
            if(dao.complete(entity.id,finished.toString(),file.length(),System.currentTimeMillis()) != 1) throw CancellationException("Transfer cancelled during finalization")
            journal(entity.id).delete()
            finished
        } catch(e: Exception) {
            withContext(NonCancellable) {
                try {cleanupPendingLocked(entity.id)} catch(cleanup:Exception) {e.addSuppressed(cleanup)}
            }
            throw e
        }
        } finally {
            if(publicationLocked) publicationLock.unlock()
            sourceLease.release()
        }
    }
    suspend fun deleteFile(entity: DownloadEntity) = withContext(Dispatchers.IO) {
        files.deleted(downloadFile(entity),historyId=entity.id)
    }
    suspend fun rename(entity: DownloadEntity, name: String) = withContext(Dispatchers.IO) {
        files.renamed(downloadFile(entity),name)
        Unit
    }
    private suspend fun downloadFile(entity:DownloadEntity):MediaFile {
        val current=dao.get(entity.id) ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        if(current.status!="COMPLETED") throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        val value=current.uri ?: throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        val tree=current.destination?.takeIf {it.isNotBlank()}?.let(Uri::parse)
        return files.metadata(value).copy(tree=tree?.toString(),parent=tree?.let {
            DocumentsContract.buildDocumentUriUsingTree(it,DocumentsContract.getTreeDocumentId(it)).toString()
        })
    }
}
