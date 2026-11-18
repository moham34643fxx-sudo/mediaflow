package com.zdownloader.app.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.zdownloader.app.domain.FileNames
import com.zdownloader.app.playback.PlaybackMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal data class MediaFile(val uri:String,val name:String,val mime:String?,val size:Long?,val duration:Long?=null,
    val modified:Long?=null,val location:String?=null,val width:Int?=null,val height:Int?=null,
    val artist:String?=null,val album:String?=null,val tree:String?=null,val parent:String?=null,val flags:Int=0,val volume:String?=null,val addedAt:Long?=null) {
    val video get()=mime?.startsWith("video/")==true
    val image get()=LocalMediaKind.IMAGE.accepts(mime)
    fun playback()=PlaybackMedia(uri,name,video,mime,artist,album,duration=duration ?: 0)
    val mediaStore get()=Uri.parse(uri).authority==MediaStore.AUTHORITY
    val movable get()=if(mediaStore) Build.VERSION.SDK_INT>=29&&volume=="external_primary" else parent!=null&&flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE!=0
    val renamable get()=if(mediaStore) Build.VERSION.SDK_INT>=29 else flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME!=0
    val deletable get()=if(mediaStore) Build.VERSION.SDK_INT>=29 else flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE!=0
}
internal class FileInUseException:IllegalStateException()
internal class FileNameConflictException:IllegalArgumentException()

/** Indexed queries and explicit document grants. No raw shared-storage paths or broad storage access. */
@Singleton class MediaFiles @Inject constructor(@ApplicationContext private val context:Context,private val dao:DownloadDao) {
    private val resolver get()=context.contentResolver
    private val preferences=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE)
    private val metadataCache=java.util.concurrent.ConcurrentHashMap<String,MediaFile>()
    internal fun cachedMetadata(value:String):MediaFile?=metadataCache[value]
    fun folders():Set<String> = preferences.getStringSet(TREE_KEY,emptySet()).orEmpty().toSet()
    fun rememberFolder(uri:Uri) {preferences.edit().putStringSet(TREE_KEY,folders()+uri.toString()).apply()}
    internal suspend fun indexed(audio:Boolean,search:String,sort:String,limit:Int):List<MediaFile> =
        indexed(if(audio) LocalMediaKind.AUDIO else LocalMediaKind.VIDEO,search,sort,limit)

    internal suspend fun indexed(kind:LocalMediaKind,search:String,sort:String,limit:Int):List<MediaFile> = withContext(Dispatchers.IO) {
        val audio=kind==LocalMediaKind.AUDIO
        val collection=when(kind) {
            LocalMediaKind.AUDIO->MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            LocalMediaKind.VIDEO->MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            LocalMediaKind.IMAGE->MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val columns=mutableListOf("_id","_display_name","mime_type","_size","date_modified","date_added")
        if(kind!=LocalMediaKind.IMAGE) columns+="duration"
        if(Build.VERSION.SDK_INT>=29) columns+=listOf("relative_path","volume_name")
        if(audio) columns+=listOf("artist","album") else columns+=listOf("width","height")
        val selection=buildList {add(if(audio) "(_display_name LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\')" else "_display_name LIKE ? ESCAPE '\\'");if(Build.VERSION.SDK_INT>=29) add("is_pending=0");if(Build.VERSION.SDK_INT>=30) add("is_trashed=0")}.joinToString(" AND ")
        val arg="%"+search.take(256).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        val arguments=if(audio) arrayOf(arg,arg,arg) else arrayOf(arg)
        val order=when(sort) {"name"->"_display_name COLLATE NOCASE ASC";"size"->"_size DESC";"duration"->if(kind==LocalMediaKind.IMAGE) "date_added DESC" else "duration DESC";else->"date_added DESC"}+", _id ASC"
        val bound=limit.coerceAtLeast(1)
        val cursor=if(Build.VERSION.SDK_INT>=26) resolver.query(collection,columns.toTypedArray(),Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arguments)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,order)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT,bound)
        },null) else resolver.query(collection,columns.toTypedArray(),selection,arguments,"$order LIMIT $bound")
        cursor?.use {c->buildList {
            fun s(key:String)=c.getColumnIndex(key).takeIf {it>=0}?.let {c.getString(it)}?.takeUnless {it.isBlank()||it=="<unknown>"}
            fun n(key:String)=c.getColumnIndex(key).takeIf {it>=0&&!c.isNull(it)}?.let {c.getLong(it)}?.takeIf {it>=0}
            while(size<bound&&c.moveToNext()) {ensureActive();add(MediaFile(ContentUris.withAppendedId(collection,c.getLong(0)).toString(),s("_display_name") ?: "Media",s("mime_type"),n("_size"),n("duration"),n("date_modified")?.times(1000),s("relative_path"),n("width")?.toInt(),n("height")?.toInt(),s("artist"),s("album"),volume=s("volume_name"),addedAt=n("date_added")?.takeIf {it in 1..Long.MAX_VALUE/1000}?.times(1000))) }
        }} ?: emptyList()
    }
    internal suspend fun metadata(value:String, forceRefresh:Boolean = false):MediaFile=withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            metadataCache[value]?.let { return@withContext it }
        }
        val uri=Uri.parse(value);require(uri.scheme=="content")
        val document=DocumentsContract.isTreeUri(uri)||DocumentsContract.isDocumentUri(context,uri)
        val columns=if(document) arrayOf("_display_name","mime_type","_size","last_modified","flags") else arrayOf("_display_name","mime_type","_size","date_modified")+(if(Build.VERSION.SDK_INT>=29&&uri.authority==MediaStore.AUTHORITY) arrayOf("relative_path","volume_name") else emptyArray())+(if(uri.authority==MediaStore.AUTHORITY) arrayOf("date_added") else emptyArray())
        resolver.query(uri,columns,null,null,null)?.use {c->
            if(!c.moveToFirst()) {metadataCache.remove(value);throw java.io.FileNotFoundException()}
            val addedIndex=c.getColumnIndex("date_added")
            val addedAt=addedIndex.takeIf {it>=0&&!c.isNull(it)}?.let {c.getLong(it)}?.takeIf {it in 1..Long.MAX_VALUE/1000}?.times(1000)
            MediaFile(value,c.getString(0) ?: "Media",c.getString(1) ?: resolver.getType(uri),if(c.isNull(2)) null else c.getLong(2).takeIf {it>=0},
                modified=if(c.isNull(3)) null else c.getLong(3)*(if(document) 1 else 1000),location=if(!document&&Build.VERSION.SDK_INT>=29&&uri.authority==MediaStore.AUTHORITY) c.getString(c.getColumnIndexOrThrow("relative_path")) else null,flags=if(document) c.getInt(4) else 0,volume=if(!document&&Build.VERSION.SDK_INT>=29&&uri.authority==MediaStore.AUTHORITY) c.getString(c.getColumnIndexOrThrow("volume_name")) else null,addedAt=addedAt).also {metadataCache[value]=it}
        } ?: run {metadataCache.remove(value);throw java.io.FileNotFoundException()}
    }
    internal suspend fun documents(treeValue:String,parentValue:String?=null):List<MediaFile> = withContext(Dispatchers.IO) {
        val tree=Uri.parse(treeValue);val id=parentValue ?: DocumentsContract.getTreeDocumentId(tree)
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,id)
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),arrayOf("document_id","_display_name","mime_type","_size","last_modified","flags"),null,null,null)?.use {c->buildList {
            while(c.moveToNext()) {ensureActive();val mime=c.getString(2).orEmpty();if(mime==DocumentsContract.Document.MIME_TYPE_DIR||LocalMediaKind.entries.any {it.accepts(mime)}) add(MediaFile(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)).toString(),c.getString(1) ?: "Media",mime,if(c.isNull(3)) null else c.getLong(3).takeIf {it>=0},modified=if(c.isNull(4)) null else c.getLong(4),tree=treeValue,parent=parent.toString(),flags=c.getInt(5))) }
        }} ?: emptyList()
    }
    internal suspend fun details(file:MediaFile):Map<String,String> = withContext(Dispatchers.IO) {
        val latest=metadata(file.uri)
        val result=linkedMapOf("name" to latest.name,"type" to (latest.mime ?: "—"),"uri" to file.uri)
        latest.size?.let {result["bytes"]=it.toString()};latest.modified?.let {result["modified"]=it.toString()}
        file.location?.let {result["location"]=it}
        val extractor=android.media.MediaExtractor()
        try {
            resolver.openFileDescriptor(Uri.parse(file.uri),"r")?.use {fd->extractor.setDataSource(fd.fileDescriptor)}
            val codecs=mutableListOf<String>()
            for(i in 0 until extractor.trackCount.coerceAtMost(32)) {
                ensureActive();val f=extractor.getTrackFormat(i)
                f.getString(android.media.MediaFormat.KEY_MIME)?.let {codecs+=it}
                if(f.containsKey(android.media.MediaFormat.KEY_DURATION)) result["duration"]=(f.getLong(android.media.MediaFormat.KEY_DURATION)/1000).toString()
                if(f.containsKey("width")&&f.containsKey("height")) result["resolution"]="${f.getInteger("width")} × ${f.getInteger("height")}"
            }
            if(codecs.isNotEmpty()) result["codecs"]=codecs.distinct().joinToString(" · ")
        } catch(e:CancellationException) {throw e} catch(_:Exception) { /* Unknown is not incompatible. */ }
        finally {extractor.release()}
        result
    }
    internal suspend fun accessible(uri:String?):Boolean=withContext(Dispatchers.IO) {
        if(uri==null) false else try {resolver.openFileDescriptor(Uri.parse(uri),"r")?.use {it.statSize!=0L} ?: false} catch(_:Exception) {false}
    }
    internal fun confirmation(files:List<MediaFile>,delete:Boolean):PendingIntent? {
        if(Build.VERSION.SDK_INT<30) return null
        val media=files.filter {it.mediaStore}.map {Uri.parse(it.uri)}
        if(media.isEmpty()) return null
        // Platform owns other-app authorization. DeleteRequest performs deletion on consent.
        return if(delete) MediaStore.createDeleteRequest(resolver,media) else MediaStore.createWriteRequest(resolver,media)
    }
    companion object {
        private const val PREFERENCES="mediaflow_file_manager"
        private const val TREE_KEY="trees"

        /** Conservative ownership check, never an access authorization. Opaque provider IDs
         * must not cause a shared tree grant to be revoked merely because they are not paths. */
        internal fun permissionCovers(grant:Uri,reference:Uri):Boolean {
            if(grant==reference) return true
            if(grant.scheme!="content"||reference.scheme!="content"||grant.authority!=reference.authority||!DocumentsContract.isTreeUri(grant)) return false
            return runCatching {
                val treeId=DocumentsContract.getTreeDocumentId(grant)
                // A tree-backed document URI contains both /tree/ and /document/;
                // isTreeUri alone does not distinguish it from the root tree URI.
                val documentId=runCatching {DocumentsContract.getDocumentId(reference)}
                    .getOrElse {DocumentsContract.getTreeDocumentId(reference)}
                if(grant.authority=="com.android.externalstorage.documents") {
                    documentId==treeId||documentId.startsWith(if(treeId.endsWith(':')) treeId else treeId.trimEnd('/')+"/")
                } else {
                    // Custom/cloud IDs have provider-defined semantics. A same-tree reference
                    // is an owner; a same-authority opaque reference may also depend on it.
                    // Preserve access when we cannot disprove ownership without provider I/O.
                    true
                }
            }.getOrDefault(true)
        }

        /** A SAF grant belongs to the application, so status cleanup must respect Library owners too. */
        internal fun referencesFolderPermission(context:Context,uri:Uri):Boolean =
            context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).getStringSet(TREE_KEY,emptySet()).orEmpty()
                .any { stored -> runCatching {permissionCovers(uri,Uri.parse(stored))}.getOrDefault(false) }
    }
    private fun mutable(file:MediaFile) {
        val app=context.applicationContext as com.zdownloader.app.ZApplication
        if(app.playback.state.value.current?.uri==file.uri) throw FileInUseException()
    }
    internal suspend fun renamed(file:MediaFile,name:String):MediaFile=withContext(Dispatchers.IO) {
        mutable(file);require(file.renamable)
        val extension=file.name.substringAfterLast('.',"")
        val safe=if(extension.matches(Regex("[a-zA-Z0-9]{1,12}"))) FileNames.output(name,extension,"{title}") else FileNames.sanitize(name)
        val uri=Uri.parse(file.uri)
        if(!file.mediaStore) {
            val siblings=file.tree?.let {documents(it,file.parent?.let {value->DocumentsContract.getDocumentId(Uri.parse(value))})}.orEmpty()
            if(siblings.any {it.name==safe&&it.uri!=file.uri}) throw FileNameConflictException()
        }
        val target=if(file.mediaStore) {check(resolver.update(uri,ContentValues().apply {put("_display_name",safe)},null,null)==1);uri}
            else DocumentsContract.renameDocument(resolver,uri,safe) ?: throw java.io.IOException("Rename failed")
        afterMutation(file,file.copy(uri=target.toString(),name=safe))
    }
    internal suspend fun deleted(file:MediaFile,platformDeleted:Boolean=false,historyId:String?=null)=withContext(Dispatchers.IO) {
        if(!platformDeleted) mutable(file)
        require(file.deletable)
        if(!platformDeleted) check(if(file.mediaStore) resolver.delete(Uri.parse(file.uri),null,null)==1 else DocumentsContract.deleteDocument(resolver,Uri.parse(file.uri)))
        metadataCache.remove(file.uri)
        val app=context.applicationContext as com.zdownloader.app.ZApplication
        withContext(NonCancellable) {app.database.withTransaction {
            dao.fileDeleted(file.uri);app.database.playbackLibrary().fileDeleted(file.uri)
            historyId?.let {dao.removeHistory(it)}
        };app.playback.fileChanged(file.uri,null,file.name)}
    }
    internal suspend fun copied(file:MediaFile,targetTree:String):MediaFile=withContext(Dispatchers.IO) {
        val tree=DocumentFile.fromTreeUri(context,Uri.parse(targetTree))?.takeIf {it.isDirectory&&it.canWrite()} ?: throw SecurityException()
        // Provider-created unique document; never open a same-name existing file for writing.
        val pending=tree.createFile(file.mime ?: "application/octet-stream",".mediaflow-${java.util.UUID.randomUUID()}.pending") ?: throw java.io.IOException()
        try {
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            resolver.openInputStream(Uri.parse(file.uri))?.use {source->resolver.openOutputStream(pending.uri,"w")?.use {sink->
                val buffer=ByteArray(64*1024)
                while(true) {ensureActive();val n=source.read(buffer);if(n<0) break;sink.write(buffer,0,n);digest.update(buffer,0,n);count+=n};sink.flush()
            } ?: throw java.io.IOException()} ?: throw java.io.FileNotFoundException()
            val verify=MessageDigest.getInstance("SHA-256");var verified=0L
            resolver.openInputStream(pending.uri)?.use {source->val buffer=ByteArray(64*1024);while(true) {ensureActive();val n=source.read(buffer);if(n<0) break;verify.update(buffer,0,n);verified+=n}} ?: throw java.io.IOException()
            check(count==verified&&MessageDigest.isEqual(digest.digest(),verify.digest()))
            val base=file.name.substringBeforeLast('.',file.name);val extension=file.name.substringAfterLast('.',"")
            var name=file.name;var index=1
            while(tree.findFile(name)!=null) {ensureActive();name=FileNames.sanitize(base)+" (${index++})"+(if(extension.isNotEmpty()) ".$extension" else "")}
            check(pending.renameTo(name))
            metadata(pending.uri.toString()).copy(tree=targetTree,parent=DocumentsContract.buildDocumentUriUsingTree(tree.uri,DocumentsContract.getTreeDocumentId(tree.uri)).toString())
        } catch(e:Exception) {withContext(NonCancellable) {runCatching {pending.delete()}};throw e}
    }
    internal suspend fun moved(file:MediaFile,targetTree:String):MediaFile=withContext(Dispatchers.IO) {
        mutable(file);require(file.movable)
        val target=Uri.parse(targetTree)
        val folder=DocumentFile.fromTreeUri(context,target)?.takeIf {it.canWrite()} ?: throw SecurityException()
        val sameFolder=if(file.mediaStore) target.authority=="com.android.externalstorage.documents"&&DocumentsContract.getTreeDocumentId(target)=="primary:"+file.location?.trim('/')
            else file.parent?.let {DocumentsContract.getDocumentId(Uri.parse(it))==DocumentsContract.getTreeDocumentId(target)&&Uri.parse(it).authority==target.authority}==true
        if(sameFolder) return@withContext file
        if(folder.findFile(file.name)!=null) throw FileNameConflictException()
        val uri=Uri.parse(file.uri)
        val newUri=if(file.mediaStore&&Build.VERSION.SDK_INT>=29) {
            require(target.authority=="com.android.externalstorage.documents")
            val id=DocumentsContract.getTreeDocumentId(target)
            require(id.startsWith("primary:"));val path=id.substringAfter(':').trim('/')
            require(path.split('/').none {it==".."||it=="."}&&path.substringBefore('/') in setOf("Movies","Music","Download","DCIM","Pictures"))
            check(resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.RELATIVE_PATH,"$path/")},null,null)==1);uri
        } else {
            require(uri.authority==target.authority)
            val moved=DocumentsContract.moveDocument(resolver,uri,Uri.parse(requireNotNull(file.parent)),DocumentsContract.buildDocumentUriUsingTree(target,DocumentsContract.getTreeDocumentId(target))) ?: throw java.io.IOException()
            DocumentsContract.buildDocumentUriUsingTree(target,DocumentsContract.getDocumentId(moved))
        }
        afterMutation(file,file.copy(uri=newUri.toString(),tree=targetTree,
            location=if(file.mediaStore) DocumentsContract.getTreeDocumentId(target).substringAfter(':').trim('/')+"/" else file.location,
            parent=if(file.mediaStore) null else DocumentsContract.buildDocumentUriUsingTree(target,DocumentsContract.getTreeDocumentId(target)).toString()))
    }
    private suspend fun afterMutation(old:MediaFile,fallback:MediaFile):MediaFile=withContext(NonCancellable) {
        metadataCache.remove(old.uri)
        metadataCache.remove(fallback.uri)
        // A confirmed provider mutation must relink even if its follow-up query
        // is temporarily unavailable. Do not cache the unverified fallback.
        val refreshed=try {metadata(fallback.uri,forceRefresh=true).copy(tree=fallback.tree,parent=fallback.parent)}
            catch(_:Exception) {null}
        val actual=refreshed ?: fallback
        relink(old.uri,actual.uri,actual.name)
        refreshed?.let {metadataCache[it.uri]=it}
        actual
    }
    private suspend fun relink(old:String,new:String,name:String) {
        val app=context.applicationContext as com.zdownloader.app.ZApplication
        app.database.withTransaction {dao.fileRelinked(old,new,name);app.database.playbackLibrary().fileRelinked(old,new,name)}
        app.playback.fileChanged(old,new,name)
    }
}
