package com.zdownloader.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.PlaylistPolicy
import com.zdownloader.app.engine.NativeWork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PlaylistRemovalRegressionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun batch(id:String="batch",state:String="CANCELLED")=PlaylistBatch(id,"key-$id","https://example.com/list","Collection",PlaylistPolicy().json(),state=state)
    private fun row(id:String,status:String="CANCELLED",batch:String?="batch")=DownloadEntity(id,"https://example.com/$id",id,"Fixture",null,null,"{}","$id.mp4",null,id,status=status,batchId=batch,
        uri=if(status=="COMPLETED") "content://fixture/$id" else null,outputSize=123L.takeIf {status=="COMPLETED"},relativeFolder="Original folder")
    @Test fun deletionRemovesCancelledRowsAndTrashButKeepsCompletedMediaAndUnrelatedRows()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads();val saved=row("saved","COMPLETED")
            dao.submitBatch(batch(),listOf(row("cancelled"),saved));dao.insert(row("unrelated","PAUSED",null))
            dao.archive(DownloadTrashEntry("cancelled","https://example.com/cancelled","Cancelled","CANCELLED"))
            dao.event(DownloadEvent(downloadId="cancelled",state="CANCELLED"))
            assertNotNull(dao.settleCancelledBatchRemoval("batch"));assertTrue(dao.removeCancelledBatch("batch"))
            assertNull(dao.batch("batch"));assertNull(dao.get("cancelled"));assertNull(dao.trashEntry("cancelled"));assertTrue(dao.events("cancelled").isEmpty())
            assertEquals(saved.copy(batchId=null,playlistPosition=0),dao.get("saved"));assertNotNull(dao.get("unrelated"))
            assertTrue(dao.removeCancelledBatch("batch"))
        } finally {db.close()}
    }
    @Test fun pendingActiveOrPausedWorkCannotBeDeletedEvenInACancelledBatch()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {val dao=db.downloads()
            for(status in listOf("QUEUED","DOWNLOADING","SAVING","PAUSING","PAUSED","FAILED","INTERRUPTED")) {
                val id="batch-$status";dao.submitBatch(batch(id),listOf(row(status,status,id)))
                assertNull(dao.settleCancelledBatchRemoval(id));assertFalse(dao.removeCancelledBatch(id));assertNotNull(dao.get(status))
            }
            dao.submitBatch(batch("pending"),listOf(row("pending-uri",batch="pending").copy(pendingUri="content://pending/1")))
            assertFalse(dao.removeCancelledBatch("pending"));assertNotNull(dao.get("pending-uri"))
        } finally {db.close()}
    }
    @Test fun cancellationCannotBeReversedByAStaleResumeAndIndividualCancellationsCanBeRemoved()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {val dao=db.downloads()
            dao.submitBatch(batch(state="ACTIVE"),listOf(row("one"),row("two")))
            assertNotNull(dao.settleCancelledBatchRemoval("batch"))
            dao.batchState("batch","ACTIVE");dao.batchState("batch","PAUSED")
            assertEquals("CANCELLED",dao.batch("batch")!!.state)
            assertTrue(dao.removeCancelledBatch("batch"))
        } finally {db.close()}
    }
    @Test fun databaseFailureRollsBackTheWholeRemovalAndCompletedDetachment()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {val dao=db.downloads()
            dao.submitBatch(batch(),listOf(row("one"),row("saved","COMPLETED")))
            dao.archive(DownloadTrashEntry("one","https://example.com/one","One","CANCELLED"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER stop_batch_delete BEFORE DELETE ON playlist_batches BEGIN SELECT RAISE(ABORT,'fixture'); END")
            try {dao.removeCancelledBatch("batch");fail("Injected failure ignored")} catch(expected:android.database.sqlite.SQLiteException) { }
            assertNotNull(dao.get("one"));assertNotNull(dao.trashEntry("one"));assertEquals("batch",dao.get("saved")!!.batchId);assertNotNull(dao.batch("batch"))
        } finally {db.close()}
    }
    @Test fun realRepositoryDefersNativeCleanupAndNeverDeletesCompletedFile()=runBlocking {
        check(context.packageName=="com.zdownloader.app.candidate0965")
        val app=context.applicationContext as ZApplication;val dao=app.repository.dao
        val id="removal-${UUID.randomUUID()}";val cancelled="$id-cancelled";val saved="$id-saved"
        val completedFile=File(app.cacheDir,"$saved.mp4").apply {writeBytes(ByteArray(1024) {it.toByte()})}
        val source=app.storage.temporary(cancelled);File(source,"primary.part").writeBytes(ByteArray(512))
        val lease=NativeWork.acquire(source)
        try {
            dao.submitBatch(batch(id),listOf(row(cancelled,batch=id),row(saved,"COMPLETED",id).copy(uri=android.net.Uri.fromFile(completedFile).toString())))
            assertTrue(app.playlists.removeCancelled(id))
            assertNull(dao.batch(id));assertNull(dao.get(cancelled));assertTrue(source.exists())
            assertTrue(completedFile.isFile);assertEquals(1024L,completedFile.length());assertNull(dao.get(saved)!!.batchId)
        } finally {
            lease.release();withTimeout(5000) {while(source.exists()) delay(10)}
            dao.removeHistory(saved);dao.forgetTrash(saved);completedFile.delete()
            dao.removeHistory(cancelled);dao.forgetTrash(cancelled);dao.removeEmptyBatch(id)
        }
    }
}
