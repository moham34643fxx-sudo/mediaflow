package com.zdownloader.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.extraction.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** MF-01: only this test's rows and private temporary files are ever deleted. */
class ExtractionRecoveryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory:File
    private val rows=mutableListOf<Uri>()
    private val bytes="disposable extraction output".toByteArray()
    private val sha get()=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    @Before fun isolate() {
        check(context.packageName=="com.zdownloader.app.candidate0965")
        directory=File(context.cacheDir,"mf01-${UUID.randomUUID()}").apply {check(mkdirs())}
    }
    @After fun cleanup() {
        rows.distinct().forEach {uri->
            val exists=context.contentResolver.query(uri,arrayOf("_id"),null,null,null)!!.use {it.moveToFirst()}
            if(exists) assertEquals(1,context.contentResolver.delete(uri,null,null))
        }
        if(::directory.isInitialized) directory.deleteRecursively()
    }
    private fun output(pending:Boolean):Uri {
        val uri=context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"mf01-${UUID.randomUUID()}.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE,"audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Music/MediaFlow-Test-Fixtures/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        rows+=uri
        context.contentResolver.openOutputStream(uri)!!.use {it.write(bytes)}
        if(!pending) context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        return uri
    }
    private fun seed(uri:Uri?,export:Boolean=false,phase:ExtractionPhase=ExtractionPhase.SAVING) {
        File(directory,"prepared.m4a").writeBytes(bytes)
        File(directory,"task.json").writeText(JSONObject().put("id","prepared").put("source","content://unused/input")
            .put("name","disposable.m4a").put("phase",phase.name).put("track",0).put("valid",true)
            .put("sha256",sha).put("bytes",bytes.size).put("pending",uri?.toString()).put("export",uri?.toString()?.takeIf {export})
            .put("plan",JSONObject().put("extension","m4a").put("mime","audio/mp4").put("codec","aac")
                .put("copy",true).put("bitrate",0).put("channels",2).put("rate",48000)).toString())
    }
    private fun read(uri:Uri)=context.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
    @Test fun unpublishedOwnedRowIsCleanedAndRepeatedRecoveryIsIdempotent()=runBlocking {
        val uri=output(true);seed(uri)
        val repo=ExtractionRepository(context,directory)
        repo.recover();repo.recover()
        assertNull(repo.task.value!!.pending)
        context.contentResolver.query(uri,arrayOf("_id"),null,null,null)!!.use {assertFalse(it.moveToFirst())}
        assertTrue(File(directory,"prepared.m4a").exists())
    }
    @Test fun visibleOutputAfterPublicationOrBeforeCompletionIsAdoptedAcrossRestart()=runBlocking {
        val uri=output(false);seed(uri)
        val repo=ExtractionRepository(context,directory)
        repo.recover();repo.recover()
        assertEquals(ExtractionPhase.COMPLETED,repo.task.value!!.phase)
        assertEquals(uri.toString(),repo.task.value!!.output)
        assertArrayEquals(bytes,read(uri))
        assertEquals(ExtractionPhase.COMPLETED,ExtractionRepository(context,directory).task.value!!.phase)
        assertFalse(File(directory,"prepared.m4a").exists())
    }
    @Test fun externallyModifiedPublishedOutputIsPreservedAndCannotRetryIntoDuplicate()=runBlocking {
        val uri=output(false);seed(uri)
        val changed="externally changed user output".toByteArray()
        context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(changed)}
        val repo=ExtractionRepository(context,directory)
        repo.recover();repo.retrySave();repo.recover()
        assertEquals(uri.toString(),repo.task.value!!.pending)
        assertEquals(ExtractionPhase.SAVE_FAILED,repo.task.value!!.phase)
        assertArrayEquals(changed,read(uri))
    }
    @Test fun exportedDestinationIsAdoptedEvenWhenProviderPendingFlagIsTrue()=runBlocking {
        val uri=output(true);seed(uri,export=true)
        val repo=ExtractionRepository(context,directory)
        repo.recover()
        assertEquals(ExtractionPhase.COMPLETED,repo.task.value!!.phase)
        assertArrayEquals(bytes,read(uri))
    }
    @Test fun uncertainSafProviderIsPreservedWithoutDeleteOrDuplicate()=runBlocking {
        val uri=Uri.parse("content://unavailable.mf01.test/document/disposable")
        seed(uri,export=true)
        val repo=ExtractionRepository(context,directory)
        repo.recover();repo.retrySave();repo.recover()
        assertEquals(uri.toString(),repo.task.value!!.pending)
        assertEquals(R.string.extract_output_preserved,repo.task.value!!.error)
        assertTrue(File(directory,"prepared.m4a").exists())
    }
    @Test fun completionJournalFailureDoesNotDeleteVisibleOutput()=runBlocking {
        val uri=output(false);seed(uri)
        val repo=ExtractionRepository(context,directory) {if(it.phase==ExtractionPhase.COMPLETED) throw IOException("injected completion write failure")}
        assertTrue(runCatching {repo.recover()}.exceptionOrNull() is IOException)
        assertArrayEquals(bytes,read(uri))
        assertTrue(File(directory,"prepared.m4a").exists())
        val restarted=ExtractionRepository(context,directory)
        restarted.recover()
        assertEquals(ExtractionPhase.COMPLETED,restarted.task.value!!.phase)
    }
    @Test fun cancellationNearPublicationPreservesPublishedOutputAndCleansOnlyPending()=runBlocking {
        for(pending in listOf(true,false)) {
            val uri=output(pending);seed(uri,phase=ExtractionPhase.CANCELLED)
            val repo=ExtractionRepository(context,directory)
            repo.recover()
            if(pending) assertNull(repo.task.value!!.pending) else {
                assertEquals(ExtractionPhase.COMPLETED,repo.task.value!!.phase)
                assertArrayEquals(bytes,read(uri))
            }
        }
    }
    @Test fun actualPublicationCompletionWriteFailureRetainsExportAndRecovers()=runBlocking {
        val uri=output(true);seed(null)
        // Persist a queued, already validated operation using this disposable export.
        val json=JSONObject(File(directory,"task.json").readText()).put("phase","QUEUED").put("export",uri.toString())
        File(directory,"task.json").writeText(json.toString())
        val repo=ExtractionRepository(context,directory,serviceStarter={}) {if(it.phase==ExtractionPhase.COMPLETED) throw IOException("injected finalization failure")}
        repo.retrySave(uri)
        repo.run("prepared") {}
        assertEquals(ExtractionPhase.SAVE_FAILED,repo.task.value!!.phase)
        assertEquals(uri.toString(),repo.task.value!!.pending)
        assertArrayEquals(bytes,read(uri))
        val restarted=ExtractionRepository(context,directory)
        restarted.recover()
        assertEquals(ExtractionPhase.COMPLETED,restarted.task.value!!.phase)
    }
    @Test fun failureAfterMediaStoreVisibilityPreservesOutputAndRecoveryAdoptsIt()=runBlocking {
        seed(null)
        val repo=ExtractionRepository(context,directory,serviceStarter={}) {if(it.phase==ExtractionPhase.COMPLETED) throw IOException("injected completion failure")}
        repo.retrySave()
        repo.run("prepared") {}
        val pending=Uri.parse(repo.task.value!!.pending).also {rows+=it}
        assertEquals(ExtractionPhase.SAVE_FAILED,repo.task.value!!.phase)
        assertArrayEquals(bytes,read(pending))
        context.contentResolver.query(pending,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {
            assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
        }
        val restarted=ExtractionRepository(context,directory)
        restarted.recover();restarted.recover()
        assertEquals(ExtractionPhase.COMPLETED,restarted.task.value!!.phase)
    }
    @Test fun deterministicCancellationOnEachSideOfPublicationCleansOnlyUnpublishedOutput()=runBlocking {
        for(cancelBeforePublish in listOf(true,false)) {
            seed(null)
            lateinit var repo:ExtractionRepository
            var destination:Uri?=null
            repo=ExtractionRepository(context,directory,serviceStarter={}) {next->
                next.pending?.let {destination=Uri.parse(it);rows+=destination!!}
                if(cancelBeforePublish&&next.pending!=null||!cancelBeforePublish&&next.phase==ExtractionPhase.COMPLETED) repo.cancel(next.id)
            }
            repo.retrySave()
            repo.run("prepared") {}
            assertNotNull(destination)
            if(cancelBeforePublish) {
                assertEquals(ExtractionPhase.CANCELLED,repo.task.value!!.phase)
                context.contentResolver.query(destination!!,arrayOf("_id"),null,null,null)!!.use {assertFalse(it.moveToFirst())}
            } else {
                assertEquals(ExtractionPhase.COMPLETED,repo.task.value!!.phase)
                assertArrayEquals(bytes,read(destination!!))
            }
        }
    }
    @Test fun interruptionBeforeDestinationCreationRetainsVerifiedPrivateOutput()=runBlocking {
        seed(null)
        val repo=ExtractionRepository(context,directory)
        repo.recover();repo.recover()
        assertEquals(ExtractionPhase.SAVE_FAILED,repo.task.value!!.phase)
        assertNull(repo.task.value!!.pending)
        assertArrayEquals(bytes,File(directory,"prepared.m4a").readBytes())
    }
    @Test fun realSafExportIsAdoptedAndExternallyChangedExportIsPreserved()=runBlocking {
        DisposableDocuments().use {docs->
            val uri=docs.create(docs.home,"saved.m4a")
            docs.resolver.openOutputStream(uri)!!.use {it.write(bytes)}
            seed(uri,export=true)
            ExtractionRepository(context,directory).also {
                it.recover();assertEquals(ExtractionPhase.COMPLETED,it.task.value!!.phase)
            }
            seed(uri,export=true)
            val modified="user modification".toByteArray()
            docs.resolver.openOutputStream(uri,"wt")!!.use {it.write(modified)}
            val repo=ExtractionRepository(context,directory)
            repo.recover();repo.retrySave();repo.recover()
            assertEquals(uri.toString(),repo.task.value!!.pending)
            assertArrayEquals(modified,read(uri))
        }
    }
    @Test fun actualSafProviderQueryFailureCannotTriggerDocumentDeletion()=runBlocking {
        DisposableDocuments().use {docs->
            val uri=docs.create(docs.home,"provider-error.m4a")
            docs.resolver.openOutputStream(uri)!!.use {it.write(bytes)}
            seed(uri,export=true)
            val id=android.provider.DocumentsContract.getDocumentId(uri)
            docs.resolver.call(uri,"failQuery",id,null)
            val repo=ExtractionRepository(context,directory)
            repo.recover()
            assertEquals(uri.toString(),repo.task.value!!.pending)
            assertEquals(0,docs.resolver.call(uri,"deletions",id,null)!!.getInt("count"))
            assertArrayEquals(bytes,read(uri))
            repo.recover()
            assertEquals(ExtractionPhase.COMPLETED,repo.task.value!!.phase)
        }
    }
    @Test fun unresolvedOutputBlocksNewTaskAndRetryCannotOverwriteSource()=runBlocking {
        val uri=output(false);seed(uri)
        context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write("changed".toByteArray())}
        var launches=0
        val repo=ExtractionRepository(context,directory,serviceStarter={launches++})
        val track=AudioTrack(0,"aac",2,48000,1.0,null,null,true)
        val plan=ExtractionPolicy.plan(track,AudioOutput.ORIGINAL,0)
        val source=ExtractionSource("content://unused/input","fixture.mp4",null,1.0,listOf(track))
        assertTrue(runCatching {repo.start(source,track,plan)}.exceptionOrNull() is ExtractionFailure)
        assertEquals(uri.toString(),repo.task.value!!.pending)
        assertEquals(0,launches)
        seed(null)
        val retry=ExtractionRepository(context,directory,serviceStarter={launches++})
        assertTrue(runCatching {retry.retrySave(Uri.parse(source.uri))}.exceptionOrNull() is ExtractionFailure)
        assertEquals(0,launches)
        assertArrayEquals(bytes,File(directory,"prepared.m4a").readBytes())
    }
}
