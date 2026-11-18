package com.zdownloader.app.status

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.FileNotFoundException
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class StatusMediaScanner @Inject constructor(@ApplicationContext private val context:Context,private val permissions:StatusPermissions) {
    private val cache=LinkedHashMap<String,StatusMedia>(64,.75f,true)
    private data class Document(val uri:Uri,val bytes:Long?,val modified:Long?)
    /** Providers do not universally send change notifications for WhatsApp
     * writes. Poll only bounded child metadata, not media bytes, while visible. */
    fun changes(source:StatusSource,imports:List<Uri>):Flow<Unit> = flow {
        var previous:String?=null
        while(currentCoroutineContext().isActive) {
            val signature=try {
                val rows=mutableListOf<String>()
                if(source==StatusSource.IMPORTS) for(uri in imports.take(StatusRules.MAX_ITEMS)) {
                    currentCoroutineContext().ensureActive();permissions.requireReadable(uri)
                    val doc=metadata(uri);rows+="${uri}|${doc.bytes}|${doc.modified}"
                } else {
                    when(val location=permissions.location(source)) {
                    is StatusPermissions.Location.Direct->location.directory.files.take(StatusRules.MAX_ITEMS).forEach {file->rows+="${file.name}|${file.length()}|${file.lastModified()}"}
                    is StatusPermissions.Location.Granted->{
                    val folder=location.folder
                    val children=DocumentsContract.buildChildDocumentsUriUsingTree(folder.uri,DocumentsContract.getDocumentId(folder.uri))
                    context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {cursor->
                        val id=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        var count=0
                        while(cursor.moveToNext()&&count<StatusRules.MAX_ITEMS) {
                            currentCoroutineContext().ensureActive()
                            val docId=cursor.getString(id)
                            val displayName=if(nameIdx>=0&&!cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                            val fileName=displayName ?: docId.substringAfterLast('/')
                            if(fileName.startsWith('.')) continue
                            count++
                            rows+=(0 until cursor.columnCount).joinToString("|") {if(cursor.isNull(it)) "?" else cursor.getString(it)}
                        }
                    } ?: throw StatusException(StatusProblem.FOLDER_UNAVAILABLE)
                    }
                    }
                }
                StatusRules.fingerprint(rows.sorted().joinToString("\n"),null,null)
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {"unavailable:${(error as? StatusException)?.problem ?: error.javaClass.simpleName}"}
            if(previous!=signature) {previous=signature;emit(Unit)}
            delay(5000)
        }
    }.flowOn(Dispatchers.IO)
    private fun metadata(uri:Uri):Document {
        var bytes:Long?=null;var modified:Long?=null
        val cursorResult=try {context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)}
            catch(_:IllegalArgumentException) {context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)}
        cursorResult?.use {cursor->
            if(!cursor.moveToFirst()) throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf {it>=0&&!cursor.isNull(it)}?.let {bytes=cursor.getLong(it).takeIf {value->value>=0}}
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf {it>=0&&!cursor.isNull(it)}?.let {modified=cursor.getLong(it).takeIf {value->value>0}}
        } ?: throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)
        return Document(uri,bytes,modified)
    }
    suspend fun inspect(uri:Uri,authorizedSource:Boolean=true):StatusMedia=withContext(Dispatchers.IO) {
        if(uri.scheme!="content") throw StatusException(StatusProblem.UNSUPPORTED)
        if(authorizedSource) permissions.requireReadable(uri)
        try {inspectDocument(metadata(uri))}
        catch(_:SecurityException) {throw StatusException(StatusProblem.ACCESS_REVOKED)}
        catch(_:FileNotFoundException) {throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)}
    }
    private fun inspectDocument(document:Document):StatusMedia {
        val (uri,size,modified)=document
        if(size!=null&&size>StatusRules.MAX_BYTES) throw StatusException(StatusProblem.TOO_LARGE)
        val key=StatusRules.fingerprint(uri.toString(),size,modified)
        if(size!=null&&modified!=null) synchronized(cache) {cache[key]?.let {return it}}
        val prefix=ByteArray(64);var count=0
        context.contentResolver.openInputStream(uri)?.use {input->while(count<prefix.size) {val n=input.read(prefix,count,prefix.size-count);if(n<=0) break;count+=n}}
            ?: throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)
        val type=StatusRules.type(prefix.copyOf(count)) ?: throw StatusException(StatusProblem.UNSUPPORTED)
        var width=0;var height=0;var duration:Long?=null;var audio=false
        if(!type.video) {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            context.contentResolver.openFileDescriptor(uri,"r")?.use {BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,bounds);Unit}
                ?: throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)
            width=bounds.outWidth;height=bounds.outHeight
            if(width<=0||height<=0||width>32768||height>32768||width.toLong()*height>100_000_000) throw StatusException(StatusProblem.CORRUPT)
            var sample=1;while(maxOf(width,height)/sample>256) sample*=2
            val decoded=BitmapFactory.Options().apply {inSampleSize=sample}
            val bitmap=context.contentResolver.openFileDescriptor(uri,"r")?.use {BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,decoded)}
                ?: throw StatusException(StatusProblem.CORRUPT)
            bitmap.recycle()
        } else {
            val extractor=MediaExtractor()
            try {
                context.contentResolver.openFileDescriptor(uri,"r")?.use {extractor.setDataSource(it.fileDescriptor)
                    for(index in 0 until extractor.trackCount) {
                        val format=extractor.getTrackFormat(index);val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if(mime.startsWith("video/")) {width=format.getInteger(MediaFormat.KEY_WIDTH);height=format.getInteger(MediaFormat.KEY_HEIGHT)
                            if(format.containsKey(MediaFormat.KEY_DURATION)) duration=format.getLong(MediaFormat.KEY_DURATION)/1000}
                        if(mime.startsWith("audio/")) audio=true
                    }
                } ?: throw StatusException(StatusProblem.SOURCE_UNAVAILABLE)
            } catch(e:StatusException) {throw e}
            catch(_:Exception) {throw StatusException(StatusProblem.CORRUPT)}
            finally {extractor.release()}
            if(width<=0||height<=0||width>16384||height>16384) throw StatusException(StatusProblem.CORRUPT)
        }
        return StatusMedia(key,uri.toString(),type,size,modified,width,height,duration,audio).also {item->
            if(size!=null&&modified!=null) synchronized(cache) {cache[key]=item;while(cache.size>512) cache.remove(cache.keys.first())}
        }
    }
    suspend fun scan(source:StatusSource,imports:List<Uri> = emptyList()):StatusScan=withContext(Dispatchers.IO) {
        val documents=mutableListOf<Document>();var limited=false
        try {
            if(source==StatusSource.IMPORTS) imports.take(StatusRules.MAX_ITEMS).distinct().forEach {uri->
                permissions.requireReadable(uri)
                runCatching {metadata(uri)}.getOrNull()?.let(documents::add)
            } else {
                when(val location=permissions.location(source)) {
                is StatusPermissions.Location.Direct->{
                    limited=location.directory.files.size>StatusRules.MAX_ITEMS
                    location.directory.files.take(StatusRules.MAX_ITEMS).forEach {file->
                        ensureActive()
                        documents+=Document(permissions.directUri(file),file.length(),file.lastModified().takeIf {it>0})
                    }
                }
                is StatusPermissions.Location.Granted->{
                val folder=location.folder
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(folder.uri,DocumentsContract.getDocumentId(folder.uri))
                context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {cursor->
                    val id=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mime=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val size=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);val modified=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val nameIdx=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    var visited=0
                    while(cursor.moveToNext()) {
                        ensureActive()
                        if(cursor.getString(mime)==DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val docId=cursor.getString(id)
                        val displayName=if(nameIdx>=0&&!cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                        val fileName=displayName ?: docId.substringAfterLast('/')
                        if(fileName.startsWith('.')) continue
                        if(++visited>StatusRules.MAX_ITEMS) {limited=true;break}
                        documents+=Document(DocumentsContract.buildDocumentUriUsingTree(folder.uri,docId),
                            size.takeIf {it>=0&&!cursor.isNull(it)}?.let {cursor.getLong(it).takeIf {value->value>=0}},
                            modified.takeIf {it>=0&&!cursor.isNull(it)}?.let {cursor.getLong(it).takeIf {value->value>0}})
                    }
                } ?: throw StatusException(StatusProblem.FOLDER_UNAVAILABLE)
                }
                }
            }
            var skipped=0
            val items=documents.distinctBy {it.uri}.sortedByDescending {it.modified ?: 0}.mapNotNull {document->
                ensureActive();permissions.requireReadable(document.uri)
                try {inspectDocument(document)} catch(e:CancellationException) {throw e}
                catch(_:SecurityException) {throw StatusException(StatusProblem.ACCESS_REVOKED)}
                catch(_:Exception) {skipped++;null}
            }
            StatusScan(items,skipped,limited)
        } catch(_:SecurityException) {throw StatusException(StatusProblem.ACCESS_REVOKED)}
    }
    /** Validate staged original bytes using platform decoders, never an encoder. */
    fun validate(file:java.io.File,expected:StatusMedia) {
        if(!expected.type.video) {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            java.io.FileInputStream(file).use {BitmapFactory.decodeFileDescriptor(it.fd,null,bounds)}
            if(bounds.outWidth!=expected.width||bounds.outHeight!=expected.height) throw StatusException(StatusProblem.SOURCE_CHANGED)
            java.io.RandomAccessFile(file,"r").use {input->
                if(expected.type.mime=="image/jpeg") {if(file.length()<2) throw StatusException(StatusProblem.CORRUPT);input.seek(file.length()-2);if(input.readUnsignedShort()!=0xffd9) throw StatusException(StatusProblem.CORRUPT)}
                if(expected.type.mime=="image/png") {if(file.length()<12) throw StatusException(StatusProblem.CORRUPT);input.seek(file.length()-12);val tail=ByteArray(12);input.readFully(tail);if(!tail.contentEquals(byteArrayOf(0,0,0,0,73,69,78,68,174.toByte(),66,96,130.toByte()))) throw StatusException(StatusProblem.CORRUPT)}
                if(expected.type.mime=="image/webp") {input.seek(4);val length=java.lang.Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL;if(length+8!=file.length()) throw StatusException(StatusProblem.CORRUPT)}
            }
            return
        }
        val retriever=MediaMetadataRetriever()
        try {
            java.io.FileInputStream(file).use {retriever.setDataSource(it.fd)}
            val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if(width!=expected.width||height!=expected.height) throw StatusException(StatusProblem.SOURCE_CHANGED)
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if(duration<=0) throw StatusException(StatusProblem.CORRUPT)
            for(time in listOf(0L,(duration-200).coerceAtLeast(0)*1000)) {
                val bitmap=if(android.os.Build.VERSION.SDK_INT>=27) retriever.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,128,128)
                    else if(width.toLong()*height<=4_000_000) retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST_SYNC) else null
                if(bitmap==null) throw StatusException(StatusProblem.CORRUPT)
                bitmap.recycle()
            }
        } catch(e:StatusException) {throw e}
        catch(_:Exception) {throw StatusException(StatusProblem.CORRUPT)}
        finally {retriever.release()}
    }
}
