package com.zdownloader.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.PlaylistPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaylistDatabaseTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun restoredConnectivityClearsStaleWaitingWithoutResettingRetryTimingOrUserPause()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads()
            fun row(id:String,status:String,bytes:Long=0)=DownloadEntity(id,"https://example.com/$id",id,"Fixture",null,null,"","",null,id,
                status=status,bytes=bytes,nextAttempt=123456,error="NETWORK_UNAVAILABLE")
            dao.insert(row("queued","WAITING_FOR_NETWORK"))
            dao.insert(row("partial","WAITING_FOR_NETWORK",65536))
            dao.insert(row("paused","PAUSED"))
            assertEquals(1,dao.networkAvailable("queued"));assertEquals("QUEUED",dao.get("queued")!!.status)
            assertEquals(1,dao.networkAvailable("partial"));assertEquals("RESUMING",dao.get("partial")!!.status)
            assertNull(dao.get("partial")!!.error);assertEquals(123456L,dao.get("partial")!!.nextAttempt)
            assertEquals(0,dao.networkAvailable("paused"));assertEquals("PAUSED",dao.get("paused")!!.status)
            dao.claim("queued");dao.processing("queued");dao.merged("queued")
            assertEquals(1,dao.reprocessDownloadedTracks("queued"))
            assertEquals("DOWNLOADED",dao.get("queued")!!.checkpoint)
            dao.merged("queued");dao.fail("queued","OUTPUT_VALIDATION_FAILED");dao.resume("queued")
            assertEquals("Old invalid generated outputs must be rebuilt from retained tracks", "DOWNLOADED",dao.get("queued")!!.checkpoint)
        } finally {db.close()}
    }
    @Test fun providerCooldownDefersTheCollectionWithoutBlockingOtherDownloadsOrResettingPauses()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads()
            val batch=PlaylistBatch("cooldown","cooldown-key","https://example.com/playlist","Cooldown",PlaylistPolicy().json())
            fun row(id:String,position:Int,status:String="QUEUED")=DownloadEntity(id,"https://example.com/$id",id,"Fixture",null,null,"","",null,id,
                batchId=batch.id,playlistPosition=position,status=status)
            dao.submitBatch(batch,listOf(row("first",1,"FAILED"),row("second",2),row("paused",3,"PAUSED")))
            val until=System.currentTimeMillis()+60_000
            assertEquals(1,dao.deferThrottledBatch(batch.id,until))
            assertNull(dao.next(until-1))
            assertEquals("RETRYING",dao.get("second")!!.status)
            assertEquals("RATE_LIMITED",dao.get("second")!!.error)
            assertEquals(0,dao.get("second")!!.retries)
            assertEquals("PAUSED",dao.get("paused")!!.status)
            assertEquals("second",dao.next(until)!!.id)
            dao.insert(row("individual",0).copy(batchId=null))
            assertEquals("individual",dao.next(until-1)!!.id)
        } finally {db.close()}
    }
    @Test fun schemaTwoMigratesWithoutLosingHistory()=runBlocking {
        val name="playlist-migration-${UUID.randomUUID()}.db"
        val file=context.getDatabasePath(name)
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)
        old.execSQL("CREATE TABLE downloads (id TEXT NOT NULL PRIMARY KEY,url TEXT NOT NULL,title TEXT NOT NULL,source TEXT NOT NULL,thumbnail TEXT,duration REAL,selection TEXT NOT NULL,filename TEXT NOT NULL,destination TEXT,fingerprint TEXT NOT NULL,status TEXT NOT NULL,createdAt INTEGER NOT NULL,finishedAt INTEGER,bytes INTEGER NOT NULL,total INTEGER,speed INTEGER NOT NULL,uri TEXT,pendingUri TEXT,error TEXT,retries INTEGER NOT NULL,nextAttempt INTEGER NOT NULL,outputSize INTEGER,checkpoint TEXT NOT NULL DEFAULT 'DOWNLOAD',notificationPending INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("CREATE INDEX index_downloads_status ON downloads(status)")
        old.execSQL("CREATE INDEX index_downloads_createdAt ON downloads(createdAt)")
        old.execSQL("CREATE INDEX index_downloads_fingerprint ON downloads(fingerprint)")
        old.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,downloadId TEXT NOT NULL,time INTEGER NOT NULL,state TEXT NOT NULL,error TEXT)")
        old.execSQL("CREATE INDEX index_events_downloadId ON events(downloadId)")
        old.execSQL("INSERT INTO downloads (id,url,title,source,selection,filename,fingerprint,status,createdAt,bytes,speed,retries,nextAttempt) VALUES ('old','https://example.com/old','Existing history','Source','{}','old.mp4','original-fingerprint','PAUSED',123,987,0,1,0)")
        old.version=2;old.close()
        val db=Room.databaseBuilder(context,ZDatabase::class.java,name).addMigrations(ZDatabase.MIGRATION_2_3,ZDatabase.MIGRATION_3_4,ZDatabase.MIGRATION_4_5,ZDatabase.MIGRATION_5_6,ZDatabase.MIGRATION_6_7,ZDatabase.MIGRATION_7_8).build()
        try {
            val row=db.downloads().get("old")!!
            assertEquals("Existing history",row.title);assertEquals(987L,row.bytes);assertEquals("PAUSED",row.status)
            assertNull(row.batchId);assertEquals(0,row.priority)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun atomicIdempotentBatchesAndAdmissionRespectUserIntent()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val dao=db.downloads()
        val batch=PlaylistBatch("batch","tap-key","https://example.com/playlist","Test",PlaylistPolicy().json())
        fun row(id:String,batchId:String?=batch.id)=DownloadEntity(id,"https://example.com/$id",id,"Fixture",null,null,"","",null,id,batchId=batchId,createdAt=100)
        try {
            val first=async(Dispatchers.IO) {dao.submitBatch(batch,listOf(row("1"),row("2")))}
            val second=async(Dispatchers.IO) {dao.submitBatch(batch.copy(id="duplicate"),listOf(row("3","duplicate")))}
            val id=first.await();assertEquals(id,second.await());assertEquals(1,dao.batches().first().size)
            val rows=dao.batchItems(id);assertTrue(rows.size in 1..2)
            dao.batchState(id,"PAUSED");assertNull(dao.next(System.currentTimeMillis()))
            assertEquals(0,dao.claim(rows.first().id))
            dao.batchState(id,"ACTIVE");assertEquals(1,dao.claim(rows.first().id));assertEquals(0,dao.claim(rows.first().id))
            dao.insert(row("single",null));assertEquals("single",dao.next(System.currentTimeMillis(),preferSingle=true)!!.id)
            dao.priority("single",1);assertEquals("single",dao.next(System.currentTimeMillis(),preferSingle=false)!!.id)
            val bad=batch.copy(id="rollback",submissionKey="rollback-key")
            try {dao.submitBatch(bad,listOf(row("same","rollback"),row("same","rollback")));fail("Duplicate child key must roll back parent")} catch(_:android.database.sqlite.SQLiteConstraintException) {}
            assertNull(dao.submittedBatch("rollback-key"))
        } finally {db.close()}
    }
    @Test fun sequentialAdmissionWaitsForPublicationAndAggregateSnapshotsDoNotDoubleCount()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val dao=db.downloads()
        val batch=PlaylistBatch("five","five-tap","https://example.com/playlist","Five videos",PlaylistPolicy().json())
        val totals=listOf(100L,150L,100L,100L,50L)
        val rows=totals.mapIndexed {i,total->DownloadEntity("v${i+1}","https://example.com/${i+1}","Video ${i+1}","Fixture",null,null,"{}","v${i+1}.mp4",null,"five:$i",batchId=batch.id,playlistPosition=i+1,total=total,sizeKind="EXACT")}
        try {
            dao.submitBatch(batch,rows)
            suspend fun summary()=dao.batchSummaries().first().single()
            assertEquals(1,dao.batch(batch.id)!!.concurrency)
            assertEquals(500L,summary().progress.expected)
            for(i in 0..1) {
                val id=rows[i].id
                assertEquals(id,dao.next(System.currentTimeMillis())!!.id)
                assertEquals(1,dao.claim(id));dao.progress(id,totals[i],totals[i],0,"EXACT")
                assertNull("No second video during transfer",dao.next(System.currentTimeMillis()))
                dao.processing(id)
                assertNull("Transfer completion must not release a sequential playlist",dao.next(System.currentTimeMillis()))
                dao.processingStage(id,"SAVING")
                assertNull("Publication is still pending",dao.next(System.currentTimeMillis()))
                assertEquals(1,dao.complete(id,"content://controlled/$id",totals[i],System.currentTimeMillis()))
            }
            assertEquals("v3",dao.next(System.currentTimeMillis())!!.id)
            dao.claim("v3");dao.progress("v3",50,100,10,"EXACT")
            assertEquals(300L,summary().transferred);assertEquals(60,summary().progress.percent)
            dao.progress("v3",50,100,10,"EXACT")
            assertEquals("Repeated callbacks are snapshots, not byte increments",300L,summary().transferred)
            dao.pause("v3");dao.interrupt("v3");dao.resume("v3");dao.claim("v3")
            dao.progress("v3",75,100,10,"EXACT")
            assertEquals(325L,summary().transferred)
            dao.pauseForUserStop()
            assertEquals("Android/user stop must expose a resumable parent", "PAUSED",summary().status)
            dao.batchState(batch.id,"PAUSED");assertNull(dao.next(System.currentTimeMillis()))
        } finally {db.close()}
    }
}
