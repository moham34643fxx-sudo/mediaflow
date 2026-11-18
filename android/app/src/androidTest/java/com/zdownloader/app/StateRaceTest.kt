package com.zdownloader.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.ZDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StateRaceTest {
    @Test fun everyTransferPhaseSupportsPauseFailureRetryRefreshAndProcessRecovery()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads()
            for(phase in listOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO")) {
                suspend fun reset() {dao.state(phase,phase)}
                dao.insert(DownloadEntity(phase,"https://example.com/video","Fixture","Test",null,null,"{}","file.mp4",null,phase,status=phase))
                assertEquals(1,dao.pause(phase));assertEquals("PAUSING",dao.get(phase)!!.status)
                dao.interrupt(phase);assertEquals("PAUSED",dao.get(phase)!!.status)
                reset();assertEquals(1,dao.fail(phase,"HTTP_ERROR"));assertEquals("FAILED",dao.get(phase)!!.status)
                reset();assertEquals(1,dao.retryLater(phase,1000,"READ_TIMEOUT"));assertEquals("RETRYING",dao.get(phase)!!.status)
                reset();assertEquals(1,dao.refreshSelection(phase,"{}",100))
                dao.recover();assertEquals("INTERRUPTED",dao.get(phase)!!.status)
                dao.requeueInterrupted();assertEquals("QUEUED",dao.get(phase)!!.status)
            }
        } finally {db.close()}
    }
    @Test fun lateCallbacksCannotOverwriteUserControlsOrCompletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao = db.downloads()
            fun row(id: String) = DownloadEntity(id,"https://example.com/video","Test","Test",null,null,"{}","test.mp4",null,id)
            dao.insert(row("pause"))
            assertEquals(1,dao.claim("pause"))
            assertEquals(1,dao.pause("pause"))
            assertEquals(0,dao.fail("pause","HTTP_ERROR"))
            assertEquals(0,dao.retryLater("pause",1000,"NETWORK_TIMEOUT"))
            assertEquals(0,dao.processing("pause"))
            dao.interrupt("pause")
            assertEquals("PAUSED",dao.get("pause")!!.status)
            dao.insert(row("cancel"))
            dao.claim("cancel"); dao.processing("cancel"); dao.cancel("cancel")
            assertEquals(0,dao.complete("cancel","content://test/1",100,1000))
            assertEquals(0,dao.fail("cancel","MERGE_FAILED"))
            assertEquals("CANCELLED",dao.get("cancel")!!.status)
            dao.insert(row("complete"))
            dao.claim("complete"); dao.processing("complete")
            assertEquals(1,dao.complete("complete","content://test/2",100,1000))
            assertEquals(0,dao.pause("complete"))
            assertEquals(0,dao.cancel("complete"))
            assertEquals(0,dao.fail("complete","HTTP_ERROR"))
            assertEquals(0,dao.retryLater("complete",1000,"NETWORK_TIMEOUT"))
            assertEquals(0,dao.submissionIssue("complete","START_DELAYED"))
            assertEquals(0,dao.submissionWaiting("complete","NETWORK_UNAVAILABLE"))
            dao.state("complete","FAILED","SCHEDULING_FAILED")
            assertEquals("COMPLETED",dao.get("complete")!!.status)
            assertNull(dao.get("complete")!!.error)
        } finally { db.close() }
    }
    @Test fun retryAndValidationStatesRemainDistinctAndRespectPause()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads();val id="retry-validation"
            dao.insert(DownloadEntity(id,"https://example.com/video","Fixture","Test",null,null,"{}","file.mp4",null,id))
            dao.claim(id);dao.processing(id)
            assertEquals(1,dao.processingStage(id,"VALIDATING"))
            assertEquals(1,dao.retryLater(id,10000,"SOURCE_CORRUPT"))
            assertEquals("RETRYING",dao.get(id)!!.status)
            assertEquals("SOURCE_CORRUPT",dao.lastRetryError(id))
            assertNull(dao.next(9999));assertEquals(id,dao.next(10000)?.id)
            dao.pendingWaiting("WIFI_REQUIRED")
            assertEquals("WAITING_FOR_NETWORK",dao.get(id)!!.status)
            assertEquals("SOURCE_CORRUPT",dao.lastRetryError(id))
            assertEquals(1,dao.pause(id));assertEquals("PAUSED",dao.get(id)!!.status)
            assertEquals(0,dao.resetPendingForUser(id))
            assertEquals(0,dao.submissionWaiting(id,"NETWORK_UNAVAILABLE"))
            assertEquals("PAUSED",dao.get(id)!!.status)
        } finally {db.close()}
    }
}
