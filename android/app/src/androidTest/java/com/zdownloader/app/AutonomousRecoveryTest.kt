package com.zdownloader.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import com.zdownloader.app.background.DownloadNotifications
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Isolated real Room/coordinator/FFmpeg/MediaStore; controlled transport interruption. */
class AutonomousRecoveryTest {
    @Test fun startupDetectsClaimedRequestsWithoutResumingExplicitPauses()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val db=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        val dao=db.downloads()
        val id=UUID.randomUUID().toString()
        try {
            dao.insertRequest(DownloadEntity(id,"https://example.com/recoverable.mp4","Recovery QA","controlled",null,null,
                "","recovery.mp4",null,id,request=DownloadRequest(height=720).json()))
            for(state in listOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","WAITING_FOR_PROCESSOR","MERGING","CONVERTING","VALIDATING","SAVING")) {
                dao.state(id,state)
                assertEquals(0,dao.remaining());assertEquals(0,dao.interruptedCount())
                assertTrue("Startup lost a killed owner in $state",dao.recoveryNeeded())
            }
            dao.recover();dao.requeueInterrupted()
            assertEquals("QUEUED",dao.get(id)!!.status)
            assertEquals(720,DownloadRequest.parse(dao.get(id)!!.request!!).height)
            for(state in listOf("PAUSED","SCHEDULED","COMPLETED","FAILED","CANCELLED")) {
                // state() intentionally protects completed history; use a fresh
                // row for each terminal/pause policy instead of mutating it.
                val other=UUID.randomUUID().toString()
                dao.cancel(id);dao.removeHistory(id)
                dao.insert(DownloadEntity(other,"https://example.com/recoverable.mp4","Recovery QA","controlled",null,null,
                    "","recovery.mp4",null,other,status=state))
                assertFalse("Startup must respect $state",dao.recoveryNeeded())
                dao.cancel(other);dao.removeHistory(other)
            }
        } finally {db.close()}
    }
    @Test fun interruptedChildIsRecoveredWithoutRestartingItsQueueOwner()=checkRecovery(false)
    @Test fun repeatedUnexpectedInterruptionEndsInFailureAfterBoundedRetries()=checkRecovery(true)
    private fun checkRecovery(alwaysInterrupt:Boolean)=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        val db=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        val dao=db.downloads();val settings=SettingsRepository(app);val old=settings.current()
        val storage=StorageRepository(app,dao);val notices=DownloadNotifications(app,settings,dao)
        val id="automatic-recovery-${UUID.randomUUID()}"
        val data=instrumentation.context.assets.open("status-original.mp4").use {it.readBytes()}
        val attempts=AtomicInteger()
        lateinit var queue:QueueCoordinator
        val client=OkHttpClient.Builder().addInterceptor {chain->
            if(attempts.incrementAndGet()==1||alwaysInterrupt) queue.stop(id)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag","\"stable-qa-representation\"").body(data.toResponseBody("video/mp4".toMediaType())).build()
        }.build()
        queue=QueueCoordinator(app,dao,settings,Transfer(app.extractor,client),Processor(),storage,notices,app.extractor)
        val format=Format.parse(JSONObject().put("format_id","direct").put("url","https://example.com/autonomous.mp4")
            .put("protocol","https").put("ext","mp4").put("vcodec","h264").put("acodec","aac"))
        val media=Media("https://example.com/autonomous.mp4",id,"Autonomous QA","controlled",null,null,null,listOf(format))
        dao.insert(DownloadEntity(id,media.url,media.title,media.source,null,null,Selection.video(media,format).json(),"$id.mp4",null,id))
        var owner:Job?=null
        try {
            settings.integer("retries",2)
            owner=launch(Dispatchers.IO) {queue.run()}
            if(args.getString("recoveryBaseline")=="true") {
                withTimeout(15000) {while(dao.get(id)!!.status!="INTERRUPTED") delay(25)}
                delay(2000)
                val row=dao.get(id)!!
                File(folder,"interrupted-child.json").writeText(JSONObject().put("status",row.status).put("attempts",attempts.get())
                    .put("owner_running",queue.running).put("eligible_next",dao.next(System.currentTimeMillis())?.id)
                    .put("scope","Controlled child stop without user pause; real queue/Room on device").toString(2))
                assertEquals("INTERRUPTED",row.status);assertEquals(1,attempts.get())
            } else {
                withTimeout(60000) {while(dao.get(id)!!.status !in setOf("COMPLETED","FAILED")) delay(50)}
                val row=dao.get(id)!!
                assertEquals(row.error,if(alwaysInterrupt) "FAILED" else "COMPLETED",row.status);assertEquals(if(alwaysInterrupt) 3 else 2,attempts.get())
                assertEquals(if(alwaysInterrupt) 2 else 1,row.retries)
                if(!alwaysInterrupt) assertNotNull(row.uri) else assertNull(row.uri)
                File(folder,if(alwaysInterrupt) "bounded-interruption.json" else "interrupted-child.json").writeText(JSONObject().put("status",row.status).put("attempts",attempts.get())
                    .put("retries",row.retries).put("published",row.uri).put("manual_resume",false)
                    .put("scope","Controlled child stop; production Room/coordinator/FFmpeg/MediaStore").toString(2))
            }
        } finally {
            owner?.cancelAndJoin()
            dao.get(id)?.let {row->if(row.status=="COMPLETED") storage.deleteFile(row) else {dao.cancel(id);dao.removeHistory(id)}}
            storage.removeTemporary(id);notices.dismiss(id);settings.integer("retries",old.retries);db.close()
        }
    }
}
