package com.zdownloader.app

import android.provider.DocumentsContract
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.LameEncoder
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class StorageContractTest {
    private val syntheticJournalId=Regex("""journal-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    @Test fun globalPublicationReconciliationPreflightFindsNoOutstandingEvidence()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val pending=app.repository.dao.pendingPublications()
        val journalEntries=File(app.filesDir,"publications").listFiles().orEmpty()
        val report=org.json.JSONObject().put("pending_publication_rows",pending.size).put("publication_journal_entries",journalEntries.size)
            .put("scope","Read-only preflight before tests that reconcile all persisted publication records")
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        File(evidence,"publication-reconciliation-preflight.json").writeText(report.toString(2))
        assertTrue("Refusing global recovery test while app-owned publication evidence exists; rows=${pending.size}, files=${journalEntries.size}",
            pending.isEmpty()&&journalEntries.isEmpty())
    }
    private suspend fun setDocumentsGrant(app:android.content.Context,revoke:Boolean) {
        app.startActivity(android.content.Intent().setComponent(android.content.ComponentName("com.zdownloader.app.test","com.zdownloader.app.TestDocumentsGrantActivity"))
            .putExtra("auditRevoke",revoke).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        kotlinx.coroutines.withTimeout(5000) {
            while((app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3)==android.content.pm.PackageManager.PERMISSION_GRANTED)==revoke)
                kotlinx.coroutines.delay(25)
        }
    }

    private suspend fun cleanupStaleJournalFixtures(app:ZApplication) {
        val rows=app.repository.dao.observe().first().filter {
            it.url=="https://example.com/journal"&&syntheticJournalId.matches(it.id)
        }
        for(row in rows) {
            val journal=File(app.filesDir,"publications/${row.id}.json")
            val state=if(journal.isFile) org.json.JSONObject(journal.readText()) else org.json.JSONObject()
            val tree=Uri.parse(state.optString("destination").takeIf {it.isNotBlank()} ?: row.destination.orEmpty())
            check(tree.authority=="com.zdownloader.app.test.documents"&&DocumentsContract.isTreeUri(tree))
            setDocumentsGrant(app,false)
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
            val cursor=app.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)
                ?: throw AssertionError("Synthetic journal fixture tree is unavailable")
            val owned=cursor.use {buildList {
                while(it.moveToNext()) {
                    val name=it.getString(1).orEmpty()
                    if(name==".${row.id}.pending"||name==row.filename&&name.startsWith("Recovered-${row.id}"))
                        add(DocumentsContract.buildDocumentUriUsingTree(tree,it.getString(0)))
                }
            }}
            owned.forEach {check(DocumentsContract.deleteDocument(app.contentResolver,it))}
            journal.delete();File(journal.path+".bak").delete()
            app.repository.dao.pending(row.id,null);app.repository.dao.cancel(row.id);app.repository.dao.removeHistory(row.id)
            app.storage.removeTemporary(row.id)
        }
    }

    @Test fun interruptedPublicationWithRecoveryMarkerIsNotAutomaticallyRequeued()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        cleanupStaleJournalFixtures(app)
        val fixtureId=Regex("""blocked-recovery-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        app.repository.dao.observe().first().filter {it.url=="https://example.com/blocked-recovery"&&fixtureId.matches(it.id)}.forEach {stale->
            app.repository.dao.cancel(stale.id);app.repository.dao.removeHistory(stale.id)
        }
        val id="blocked-recovery-${java.util.UUID.randomUUID()}"
        app.repository.dao.insert(DownloadEntity(id,"https://example.com/blocked-recovery","Recovery","test",null,null,"{}",
            "recovery.mp4",null,id,status="INTERRUPTED",pendingUri="mediaflow-publication:$id"))
        try {
            app.repository.dao.requeueInterrupted()
            assertEquals("Unresolved publication evidence must block automatic redownload","INTERRUPTED",app.repository.dao.get(id)!!.status)
            app.repository.dao.pending(id,null)
            app.repository.dao.requeueInterrupted()
            assertEquals("Confirmed resolution permits the normal retry transition","QUEUED",app.repository.dao.get(id)!!.status)
        } finally {app.repository.dao.cancel(id);app.repository.dao.removeHistory(id)}
    }

    @Test fun uncertainProviderCleanupRetainsJournalAndRetryRemovesOnlyPendingOutput()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        setDocumentsGrant(app,false)
        assertTrue(DocumentsContract.isTreeUri(tree))
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3))
        val childUri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val childCursor=app.contentResolver.query(childUri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)
            ?: throw AssertionError("Test provider returned no child cursor")
        childCursor.close()
        val root=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,tree)!!
        val id="recover-${java.util.UUID.randomUUID()}"
        val doc=root.createFile("video/mp4",".$id.pending")!!
        val bytes=byteArrayOf(9,8,7,6)
        app.contentResolver.openOutputStream(doc.uri)!!.use {it.write(bytes)}
        val journal=File(app.filesDir,"publications").apply {mkdirs()}.let {File(it,"$id.json")}
        journal.writeText(org.json.JSONObject().put("destination",tree.toString()).put("uri",doc.uri.toString()).toString())
        app.repository.dao.insert(DownloadEntity(id,"https://example.com/recovery","Recovery","Test",null,null,"{}","Recovered.mp4",tree.toString(),id,status="INTERRUPTED"))
        try {
            setDocumentsGrant(app,true)
            val failed=runCatching {app.storage.cleanupPending(id)}.exceptionOrNull()
            assertNotNull("Revoked provider access is uncertain and must not be treated as absence",failed)
            assertTrue(journal.isFile)
            setDocumentsGrant(app,false)
            app.contentResolver.openInputStream(doc.uri)!!.use {assertArrayEquals(bytes,it.readBytes())}
            app.storage.cleanupPending(id)
            assertFalse(journal.exists())
            assertTrue(runCatching {app.contentResolver.openFileDescriptor(doc.uri,"r")}.isFailure)
        } finally {
            setDocumentsGrant(app,false)
            runCatching {DocumentsContract.deleteDocument(app.contentResolver,doc.uri)}
            journal.delete();app.repository.dao.removeHistory(id)
        }
    }

    @Test fun interruptedPublicationsRemoveOnlyOwnedPendingUris()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        app.startActivity(android.content.Intent().setComponent(android.content.ComponentName("com.zdownloader.app.test","com.zdownloader.app.TestDocumentsGrantActivity")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        kotlinx.coroutines.withTimeout(5000) {while(app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3)!=android.content.pm.PackageManager.PERMISSION_GRANTED) kotlinx.coroutines.delay(25)}
        val directory=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,tree)!!
        for(rename in listOf(false,true)) {
            val id="journal-${java.util.UUID.randomUUID()}"
            val data=byteArrayOf(1,2,3,4)
            val doc=directory.createFile("video/mp4",".$id.pending")!!
            app.contentResolver.openOutputStream(doc.uri)!!.use {it.write(data)}
            val old=doc.uri
            val name="Recovered-$id.mp4"
            val published=if(rename) DocumentsContract.renameDocument(app.contentResolver,old,name)!! else old
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it)}
            val journal=File(app.filesDir,"publications").apply {mkdirs()}.let {File(it,"$id.json")}
            journal.writeText(org.json.JSONObject().put("destination",tree.toString()).put("uri",old.toString())
                .put("finalName",name).put("sha256",hash).put("bytes",data.size).toString())
            app.repository.dao.insert(DownloadEntity(id,"https://example.com/journal","Journal","Test",null,null,"{}",name,tree.toString(),id,
                status="INTERRUPTED",pendingUri=old.toString()))
            try {
                assertEquals("Tree read/write grant before cleanup (renamed=$rename)",android.content.pm.PackageManager.PERMISSION_GRANTED,
                    app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3))
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                val cursor=app.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)
                    ?: throw AssertionError("SAF child query unavailable (renamed=$rename)")
                cursor.close()
                try {app.storage.reconcilePublications()} catch(error:Exception) {throw AssertionError("First recovery failed for renamed=$rename",error)}
                if(!rename) assertNull("First safe cleanup must release the publication marker: ${app.repository.dao.get(id)}",app.repository.dao.get(id)?.pendingUri)
                try {app.storage.reconcilePublications()} catch(error:Exception) {throw AssertionError("Repeated recovery failed for renamed=$rename",error)}
                if(rename) {
                    val recovered=app.repository.dao.get(id)!!
                    assertEquals("COMPLETED",recovered.status);assertEquals(published.toString(),recovered.uri)
                    assertEquals(data.size.toLong(),recovered.outputSize)
                    app.contentResolver.openInputStream(published)!!.use {assertArrayEquals(data,it.readBytes())}
                    val finalCount=app.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)!!.use {cursor->
                        var count=0;while(cursor.moveToNext()) if(cursor.getString(0)==name) count++;count
                    }
                    assertEquals(1,finalCount)
                } else {
                    assertNull("Repeated recovery left a pending marker: ${app.repository.dao.get(id)}",app.repository.dao.get(id)?.pendingUri)
                    assertFalse(doc.exists())
                }
                assertFalse(journal.exists())
            } finally {runCatching {DocumentsContract.deleteDocument(app.contentResolver,published)};journal.delete();app.repository.dao.removeHistory(id)}
        }
    }
    @Test fun safCommitRenameAndHistoryRemovalPreserveCorrectUriAndFile() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val id=java.util.UUID.randomUUID().toString()
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        setDocumentsGrant(app,revoke=false)
        val format=Format("storage-fixture","mp4",null,null,"h264","aac",4,null,"https","{}")
        val selection=Selection(format)
        val row=DownloadEntity(id,"https://example.com/storage-fixture","Storage fixture","Test",null,null,selection.json(),"اختبار.mp4",tree.toString(),id)
        val file=File(app.storage.temporary(id),"fixture.mp4").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        app.repository.dao.insert(row);app.repository.dao.claim(id);app.repository.dao.processing(id)
        try {
            val uri=app.storage.commit(row,file,selection,TransferControl())
            File(app.filesDir,"publications").apply {mkdirs()}.let {File(it,"$id.json")}.writeText(org.json.JSONObject().put("destination",tree.toString()).put("uri",uri.toString()).toString())
            app.storage.cleanupPending(id)
            app.contentResolver.openInputStream(uri)!!.use {assertEquals(4,it.readBytes().size)}
            app.storage.rename(app.repository.dao.get(id)!!,"Renamed ملف.mp4")
            val renamed=app.repository.dao.get(id)!!
            assertNotEquals(uri.toString(),renamed.uri)
            assertEquals("Renamed ملف.mp4",renamed.filename)
            val finalUri=android.net.Uri.parse(renamed.uri)
            app.contentResolver.openInputStream(finalUri)!!.use { assertArrayEquals(byteArrayOf(1,2,3,4),it.readBytes()) }
            app.repository.remove(id)
            assertNull(app.repository.dao.get(id))
            app.contentResolver.openInputStream(finalUri)!!.use { assertEquals(4,it.readBytes().size) }
            DocumentsContract.deleteDocument(app.contentResolver,finalUri)
            Unit
        } finally {
            try {
                app.repository.cancel(id)
                app.repository.remove(id)
                app.storage.removeTemporary(id)
            } finally {
                setDocumentsGrant(app,revoke=true)
            }
        }
    }

    @Test fun mediaStoreFinalOutputIsAdoptedExactlyOnceAfterInterruptedCompletion()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val id="recover-media-${java.util.UUID.randomUUID()}"
        val data=byteArrayOf(7,6,5,4,3,2,1)
        val finalName="MediaFlow-$id.mp4"
        val collection=android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri=app.contentResolver.insert(collection,android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,".$id.pending.mp4")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Movies/MediaFlow/")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        val journal=File(app.filesDir,"publications").apply {mkdirs()}.let {File(it,"$id.json")}
        try {
            app.contentResolver.openOutputStream(uri)!!.use {it.write(data)}
            assertEquals(1,app.contentResolver.update(uri,android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,finalName)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)
            },null,null))
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it)}
            journal.writeText(org.json.JSONObject().put("uri",uri.toString()).put("finalName",finalName)
                .put("sha256",hash).put("bytes",data.size).toString())
            app.repository.dao.insert(DownloadEntity(id,"https://example.com/media-recovery","Media recovery","test",null,null,"{}",
                finalName,null,id,status="INTERRUPTED",pendingUri=uri.toString()))

            app.storage.reconcilePublications();app.storage.reconcilePublications()

            val recovered=app.repository.dao.get(id)!!
            assertEquals("COMPLETED",recovered.status);assertEquals(uri.toString(),recovered.uri)
            assertEquals(data.size.toLong(),recovered.outputSize);assertNull(recovered.pendingUri)
            assertArrayEquals(data,app.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
            assertFalse(journal.exists())
        } finally {
            runCatching {app.contentResolver.delete(uri,null,null)}
            journal.delete();app.repository.dao.removeHistory(id)
        }
    }
    @Test fun modifiedFinalSafOutputIsPreservedAndBlocksAutomaticRetry()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        setDocumentsGrant(app,false)
        val id="recover-mismatch-${java.util.UUID.randomUUID()}"
        val original=byteArrayOf(1,2,3,4);val edited=byteArrayOf(4,3,2,1)
        val name="MediaFlow-$id.mp4"
        val root=androidx.documentfile.provider.DocumentFile.fromTreeUri(app,tree)!!
        val pending=root.createFile("video/mp4",".$id.pending")!!
        val old=pending.uri
        val journal=File(app.filesDir,"publications").apply {mkdirs()}.let {File(it,"$id.json")}
        var finalUri:android.net.Uri?=null
        try {
            app.contentResolver.openOutputStream(old)!!.use {it.write(original)}
            finalUri=DocumentsContract.renameDocument(app.contentResolver,old,name)!!
            app.contentResolver.openOutputStream(finalUri,"w")!!.use {it.write(edited)}
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") {"%02x".format(it)}
            journal.writeText(org.json.JSONObject().put("destination",tree.toString()).put("uri",old.toString())
                .put("finalName",name).put("bytes",original.size).put("sha256",hash).toString())
            app.repository.dao.insert(DownloadEntity(id,"https://example.com/recovery-mismatch","Mismatch","test",null,null,"{}",
                name,tree.toString(),id,status="INTERRUPTED",pendingUri=old.toString()))

            app.storage.reconcilePublications()

            val row=app.repository.dao.get(id)!!
            assertEquals("FAILED",row.status);assertEquals(ErrorCode.FILE_WRITE_FAILED.name,row.error);assertNotNull(row.pendingUri)
            assertTrue("Unverified visible output and ownership journal are retained",journal.isFile)
            app.contentResolver.openInputStream(finalUri)!!.use {assertArrayEquals(edited,it.readBytes())}
        } finally {
            finalUri?.let {runCatching {DocumentsContract.deleteDocument(app.contentResolver,it)}}
            journal.delete();app.repository.dao.removeHistory(id)
        }
    }

    @Test fun nativeMp3EncoderSeparatesUnavailableOutputFromUnavailableInput() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val home=File(app.filesDir,"storage-classification-${UUID.randomUUID()}").apply {check(mkdirs())}
        val pcm=File(home,"tone.pcm")
        val samples=ByteBuffer.allocate(44100*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100) {index->samples.putShort((kotlin.math.sin(index*2*Math.PI*440/44100)*5000).toInt().toShort())}
        pcm.writeBytes(samples.array())
        try {
            val missingParent=File(home,"missing-output-parent/tone.mp3")
            assertEquals("ENOENT from the MP3 destination is not an unexpected write failure",-7,
                LameEncoder.encodeEx(pcm.path,missingParent.path,128,44100,1,AtomicBoolean(false)))
            val pipe=android.os.ParcelFileDescriptor.createPipe()
            pipe[0].use {input->pipe[1].use {
                assertEquals("The pipe encoder must preserve the output-open errno",-7,
                    LameEncoder.encodePipe(input.fd,missingParent.path,128,44100,1,AtomicBoolean(false)))
            }}
            val missingInput=File(home,"missing-input.pcm")
            val original=byteArrayOf(1,2,3)
            val output=File(home,"existing.mp3").apply {writeBytes(original)}
            assertEquals("An input-open failure belongs to processing, not storage",-5,
                LameEncoder.encodeEx(missingInput.path,output.path,128,44100,1,AtomicBoolean(false)))
            assertArrayEquals("An unopened output must not be removed on input failure",original,output.readBytes())
        } finally {check(home.deleteRecursively())}
    }

    @Test fun actualMp3FixturePublishesToMediaStoreOnScopedStorage()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        check(android.os.Build.VERSION.SDK_INT>=29) {"The default MediaStore destination requires Android 10 or later"}
        val id=UUID.randomUUID().toString()
        val directory=app.storage.temporary(id)
        val pcm=File(directory,"tone.pcm")
        val encoded=File(directory,"tone.mp3")
        val samples=ByteBuffer.allocate(44100*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100) {index->samples.putShort((kotlin.math.sin(index*2*Math.PI*440/44100)*5000).toInt().toShort())}
        pcm.writeBytes(samples.array())
        var uri:Uri?=null
        try {
            assertEquals(0,LameEncoder.encodeEx(pcm.path,encoded.path,128,44100,1,AtomicBoolean(false)))
            assertTrue(encoded.isFile&&encoded.length()>0)
            val format=Format("fixture-mp3","mp3",null,null,"none","mp3",encoded.length(),128.0,"https","{}")
            val selection=Selection(format,audioOnly=true,output="mp3",bitrate=128)
            val row=DownloadEntity(id,"https://example.com/storage-fixture","MP3 storage fixture","test",null,1.0,
                selection.json(),"MediaFlow-$id.mp3",null,id)
            app.repository.dao.insert(row)
            assertEquals(1,app.repository.dao.claim(id))
            assertEquals(1,app.repository.dao.processing(id))

            uri=app.storage.commit(row,encoded,selection,TransferControl())

            app.contentResolver.openInputStream(uri!!)!!.use {assertArrayEquals(encoded.readBytes(),it.readBytes())}
            app.contentResolver.query(uri!!,arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0))
            }
            val completed=app.repository.dao.get(id)!!
            assertEquals("COMPLETED",completed.status)
            assertEquals(encoded.length(),completed.outputSize)
            assertEquals(uri.toString(),completed.uri)
        } finally {
            uri?.let {runCatching {app.contentResolver.delete(it,null,null)}}
            app.repository.dao.get(id)?.let {row->
                if(row.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED","INTERRUPTED")) app.repository.dao.cancel(id)
                runCatching {app.storage.cleanupPending(id)}
                runCatching {app.repository.dao.removeHistory(id)}
            }
            app.storage.removeTemporary(id)
        }
    }

    @Test fun unavailableCustomDestinationCanRetryWithoutDiscardingValidatedOutput()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val id=UUID.randomUUID().toString()
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root").toString()
        val staged=File(app.storage.temporary(id),"validated-output.mp3").apply {writeBytes(byteArrayOf(9,8,7,6,5,4))}
        val original=DownloadEntity(id,"https://example.com/retry-storage","Retry storage","test",null,1.0,"{}",
            "retry.mp3",tree,id,status="FAILED",bytes=staged.length(),error=ErrorCode.STORAGE_DESTINATION_UNAVAILABLE.name,checkpoint="VALIDATED")
        val blockedId=UUID.randomUUID().toString()
        val blocked=DownloadEntity(blockedId,"https://example.com/retry-storage-pending","Retry blocked storage","test",null,1.0,"{}",
            "blocked.mp3",tree,blockedId,status="FAILED",pendingUri="content://media/external/audio/media/987654321",
            error=ErrorCode.STORAGE_DESTINATION_UNAVAILABLE.name)
        val unrelatedId=UUID.randomUUID().toString()
        val unrelated=DownloadEntity(unrelatedId,"https://example.com/retry-storage-write","Retry write error","test",null,1.0,"{}",
            "write-error.mp3",tree,unrelatedId,status="FAILED",error=ErrorCode.FILE_WRITE_FAILED.name)
        try {
            app.repository.dao.insert(original);app.repository.dao.insert(blocked);app.repository.dao.insert(unrelated)
            assertEquals(1,app.repository.dao.retryInDefaultDestination(id))
            assertEquals(0,app.repository.dao.retryInDefaultDestination(blockedId))
            assertEquals(0,app.repository.dao.retryInDefaultDestination(unrelatedId))
            val changed=app.repository.dao.get(id)!!
            assertNull(changed.destination)
            assertEquals("VALIDATED",changed.checkpoint)
            assertEquals("FAILED",changed.status)
            assertArrayEquals(byteArrayOf(9,8,7,6,5,4),staged.readBytes())
            app.repository.dao.resume(id)
            assertEquals("RESUMING",app.repository.dao.get(id)!!.status)
            assertEquals("VALIDATED",app.repository.dao.get(id)!!.checkpoint)
            assertEquals(tree,app.repository.dao.get(blockedId)!!.destination)
        } finally {
            for(rowId in listOf(id,blockedId,unrelatedId)) {
                runCatching {app.repository.dao.state(rowId,"FAILED")}
                runCatching {app.repository.dao.removeHistory(rowId)}
                app.storage.removeTemporary(rowId)
            }
        }
    }
}
