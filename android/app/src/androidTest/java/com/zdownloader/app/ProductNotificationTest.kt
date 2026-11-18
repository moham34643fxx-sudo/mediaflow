package com.zdownloader.app

import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.background.DownloadNotifications
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProductNotificationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val settings get()=SettingsRepository(app)
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    private suspend fun restore(old:Settings) {
        settings.notification("completion_notifications",old.completionNotifications);settings.notification("failure_notifications",old.failureNotifications)
        settings.boolean("notifications",old.notifications)
    }
    @Test fun aDurableQueuedRequestIsConfirmedWithoutWaitingForItsTransfer()=runBlocking {
        val id="submission-test-${UUID.randomUUID()}";val later=System.currentTimeMillis()+86_400_000
        val dao=app.repository.dao
        dao.insert(DownloadEntity(id,"https://example.com/not-requested","Scheduling test","Test",null,null,"{}","fixture.mp4",null,id,nextAttempt=later))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val start=android.os.SystemClock.elapsedRealtime()
                val receipt=app.scheduler.startFromUser(id)
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                val row=dao.get(id)!!
                assertTrue(row.status in listOf("QUEUED","WAITING_FOR_NETWORK"))
                assertNotEquals("START_DELAYED",row.error);assertNotEquals("FAILED",row.status)
                assertEquals(later,row.nextAttempt)
                assertTrue(dao.events(id).any {event->event.state=="SUBMITTED"})
                File(folder,"submission-acknowledgement.json").writeText(JSONObject().put("elapsed_ms",elapsed).put("status",row.status)
                    .put("mechanism",receipt.mechanism).put("transfer_not_started",true).put("submitted_event",true).toString(2))
            }
        } finally {app.repository.cancel(id);app.coordinator.stopAndAwait(id);app.repository.remove(id)}
    }
    @Test fun optionalSwitchesApplyImmediatelyAndStaleFailureCannotReplaceCompletion()=runBlocking {
        val old=settings.current();val dao=app.repository.dao;val prefix="notification-test-${UUID.randomUUID()}"
        val uri=Uri.parse("content://com.zdownloader.app.test/notification-fixture")
        val format=Format.parse(JSONObject().put("format_id","fixture").put("url","https://example.com/test.mp4").put("ext","mp4").put("vcodec","h264").put("acodec","aac"))
        val selection=Selection(format)
        val done=DownloadEntity("$prefix-done","https://example.com/test","ZD notification test","Test",null,null,selection.json(),"fixture.mp4",null,prefix,status="COMPLETED",uri=uri.toString())
        val failed=done.copy(id="$prefix-failed",status="FAILED",uri=null,error="HTTP_ERROR")
        try {
            dao.insert(done);dao.insert(failed)
            settings.notification("completion_notifications",false);settings.notification("failure_notifications",false)
            app.notifications.completed(done,uri,selection);app.notifications.failed(failed,ErrorCode.HTTP_ERROR)
            assertTrue(manager.activeNotifications.none {it.id in listOf(done.id.hashCode(),failed.id.hashCode())})
            assertTrue(app.notifications.summary().flags and Notification.FLAG_ONGOING_EVENT!=0)
            settings.notification("completion_notifications",true);settings.notification("failure_notifications",true)
            app.notifications.completed(done,uri,selection);app.notifications.failed(failed,ErrorCode.HTTP_ERROR)
            if(manager.areNotificationsEnabled()&&android.os.Build.VERSION.SDK_INT>=26) {
                assertEquals(DownloadNotifications.COMPLETED_CHANNEL,manager.activeNotifications.single {it.id==done.id.hashCode()}.notification.channelId)
                assertEquals(DownloadNotifications.FAILED_CHANNEL,manager.activeNotifications.single {it.id==failed.id.hashCode()}.notification.channelId)
            }
            app.notifications.dismiss(failed.id)
            // A real late callback carries an old FAILED snapshot after Room completed.
            dao.state(failed.id,"DOWNLOADING");dao.processing(failed.id);dao.complete(failed.id,uri.toString(),1,System.currentTimeMillis())
            app.notifications.failed(failed,ErrorCode.HTTP_ERROR)
            assertTrue(manager.activeNotifications.none {it.id==failed.id.hashCode()})
            File(folder,"notification-policy.json").writeText(JSONObject().put("disabled_optional_notices",true).put("preferences_live",true)
                .put("system_notifications_enabled",manager.areNotificationsEnabled()).put("separate_optional_channels",true).put("required_summary_retained",true)
                .put("stale_failure_ignored_after_completion",true).toString(2))
        } finally {
            for(row in listOf(done,failed)) {app.notifications.dismiss(row.id);dao.removeHistory(row.id)}
            restore(old)
        }
    }
    @Test fun disabledOptionalNotificationsDoNotBlockRealBackgroundPublication()=runBlocking {
        val old=settings.current();var id:String?=null
        val url="https://www.w3schools.com/html/mov_bbb.mp4?notification-policy=${UUID.randomUUID()}"
        try {
            settings.notification("completion_notifications",false);settings.notification("failure_notifications",false)
            val intent=android.content.Intent(app,ShareReceiverActivity::class.java).setAction(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario ->
                var model:QuickShareViewModel?=null
                scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
                withTimeout(90000) {while(model!!.state.value.let {it.media==null||it.analyzing}) {assertNull(model!!.state.value.error);delay(50)}}
                assertNull("Opening Quick Share must not invent an explicit selection",model!!.state.value.choice)
                assertNull(model!!.state.value.queuedId)
                assertNotNull("The explicit Download action needs a real default",model!!.state.value.defaultChoice)
                val start=android.os.SystemClock.elapsedRealtime()
                scenario.onActivity {model!!.enqueue()}
                withTimeout(20000) {while(model!!.state.value.queuedId==null) {assertNull(model!!.state.value.error);delay(30)}}
                id=model!!.state.value.queuedId
                val acceptance=android.os.SystemClock.elapsedRealtime()-start
                val atConfirmation=app.repository.dao.get(id!!)!!.status
                withTimeout(180000) {while(app.repository.dao.get(id!!)!!.status !in listOf("COMPLETED","FAILED")) delay(150)}
                val row=app.repository.dao.get(id!!)!!
                assertEquals(row.error,"COMPLETED",row.status)
                assertTrue((row.outputSize ?: 0)>0);assertNotNull(row.uri)
                assertTrue(manager.activeNotifications.none {it.id==id!!.hashCode()})
                File(folder,"notifications-disabled-publication.json").writeText(JSONObject().put("status",row.status).put("published_bytes",row.outputSize)
                    .put("submission_confirmation_ms",acceptance).put("status_at_confirmation",atConfirmation).put("optional_completion_notification",false).put("room_authoritative",true).toString(2))
            }
        } finally {
            id?.let {key->val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(key);app.coordinator.stopAndAwait(key);app.repository.remove(key)}}
            restore(old)
        }
    }
    @Test fun retainRuntimeSchedulingEvidenceWithoutAssumingHistoricalPresence() {
        val entries=JSONArray()
        for(name in listOf("network-diagnostics.previous.jsonl","network-diagnostics.jsonl")) {
            val file=File(app.filesDir,name)
            if(file.isFile) file.useLines {lines->lines.forEach {line->runCatching {JSONObject(line)}.getOrNull()?.takeIf {
                it.optString("stack")=="scheduler" && (it.optString("code")=="START_DELAYED" || it.optString("stage") in listOf("accepted_but_not_started","accepted_waiting","runner_ready","late_acceptance_confirmed","workmanager_acceptance_pending"))
            }?.let(entries::put)}}
        }
        File(folder,"scheduling-runtime-evidence.json").writeText(JSONObject().put("matches",entries).put("historical_start_delayed_observed",(0 until entries.length()).any {entries.getJSONObject(it).optString("code")=="START_DELAYED"}).toString(2))
    }
}
