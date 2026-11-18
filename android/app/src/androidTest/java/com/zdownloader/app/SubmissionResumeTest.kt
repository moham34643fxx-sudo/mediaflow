package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** P0: a resubmitted share must never stick on an inherited PAUSED row. */
class SubmissionResumeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-evidence/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}

    @Test fun resharedPausedRequestResumesAndCompletesWithoutManualAction()=runBlocking {
        val url="https://www.w3schools.com/html/mov_bbb.mp4?submission-test=${UUID.randomUUID()}"
        val mainLaunches=AtomicInteger()
        val callback=ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==Stage.CREATED) mainLaunches.incrementAndGet()}
        instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)}
        var id:String?=null
        try {
            val media=app.extractor.analyze(url)
            val selection=Selection.video(media,media.formats.first { it.video })
            id=app.repository.create(media,selection,"Submission resume test")
            app.repository.pause(id)
            assertEquals("PAUSED",app.repository.dao.get(id)!!.status)
            // A second share of the same video reattaches to the paused row.
            val resubmitted=app.repository.create(media,selection,"Submission resume test")
            assertEquals("Duplicate share must reuse the same queue row",id,resubmitted)
            // New submission path: settle inherited pause, then schedule with receipt.
            val prepared=app.repository.prepareForSubmission(resubmitted)
            assertTrue("Submit must clear inherited PAUSED, was ${prepared.status}",prepared.status in listOf("QUEUED","RESUMING"))
            val receipt=app.scheduler.startFromUser(resubmitted)
            assertNull("Submit must not report a network wait on healthy Wi-Fi",receipt.waitingReason)
            assertTrue("Scheduler must confirm a running queue",receipt.runnerReady)
            withTimeout(180000) {
                while(app.repository.dao.get(resubmitted)!!.status !in listOf("COMPLETED","FAILED")) delay(250)
            }
            val row=app.repository.dao.get(resubmitted)!!
            assertEquals(row.error,"COMPLETED",row.status)
            assertEquals(0,mainLaunches.get())
            File(folder,"submission-resume.json").writeText(JSONObject().put("reused_paused_row",true)
                .put("status",row.status).put("mechanism",receipt.mechanism).put("main_activity_created",mainLaunches.get()).toString(2))
        } finally {
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)}
            id?.let { key->val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(key);app.coordinator.stopAndAwait(key);app.repository.remove(key)} }
        }
    }
}
