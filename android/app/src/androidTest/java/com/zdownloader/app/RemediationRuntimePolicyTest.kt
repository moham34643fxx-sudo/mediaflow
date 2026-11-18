package com.zdownloader.app

import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.background.DownloadNotifications
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

class RemediationRuntimePolicyTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=(instrument.targetContext.applicationContext as ZApplication).also {
        check(it.packageName=="com.zdownloader.app.candidate0965")
    }
    private suspend fun await(check:()->Boolean)=withTimeout(15000) {while(!check()) delay(25)}
    private fun evidence(name:String,cases:JSONArray) {
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
        require(run.matches(Regex("[A-Za-z0-9-]{1,100}")))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        File(folder,"$name.json").writeText(JSONObject().put("cases",cases).toString(2))
    }
    private data class BatchCase(val completed:Int,val completionEnabled:Boolean,val failureEnabled:Boolean,val channel:String?)
    @Test fun realBatchNotificationsRespectIndependentSwitchesAndShowActualCounts()=runBlocking<Unit> {
        val settings=SettingsRepository(app);val original=settings.current()
        val dao=app.repository.dao;val manager=app.getSystemService(NotificationManager::class.java)
        assertTrue("POST_NOTIFICATIONS is a test precondition",manager.areNotificationsEnabled())
        val cases=JSONArray()
        try {
            settings.boolean("notifications",true)
            for(case in listOf(
                BatchCase(0,false,true,DownloadNotifications.FAILED_CHANNEL),
                BatchCase(1,true,false,DownloadNotifications.COMPLETED_CHANNEL),
                BatchCase(1,false,true,DownloadNotifications.FAILED_CHANNEL),
                BatchCase(2,false,true,null),BatchCase(0,true,false,null),
                BatchCase(2,true,true,DownloadNotifications.COMPLETED_CHANNEL))) {
                settings.notification("completion_notifications",case.completionEnabled)
                settings.notification("failure_notifications",case.failureEnabled)
                val id="mf04-${UUID.randomUUID()}";val notificationId="batch:$id".hashCode()
                val rows=(0..1).map {index->DownloadEntity("$id-$index","https://example.com/$id","Disposable batch","Test",null,null,
                    "{}","fixture.mp4",null,"$id-$index",batchId=id,nextAttempt=System.currentTimeMillis()+86400000)}
                try {
                    dao.submitBatch(PlaylistBatch(id,id,"https://example.com/$id","Disposable batch","best"),rows)
                    await {manager.activeNotifications.any {it.id==notificationId&&it.notification.flags and Notification.FLAG_ONGOING_EVENT!=0}}
                    for((index,row) in rows.withIndex()) dao.state(row.id,if(index<case.completed) "COMPLETED" else "FAILED")
                    if(case.channel==null) await {manager.activeNotifications.none {it.id==notificationId}}
                    else {
                        await {manager.activeNotifications.any {it.id==notificationId&&it.notification.channelId==case.channel&&it.notification.flags and Notification.FLAG_ONGOING_EVENT==0}}
                        val notice=manager.activeNotifications.single {it.id==notificationId}.notification
                        val counts=notice.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                        val expected=listOf("en","ar").map {language->
                            val config=android.content.res.Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag(language))}
                            app.createConfigurationContext(config).getString(R.string.batch_result_counts,case.completed,2-case.completed,0)
                        }
                        assertTrue("Terminal counts must reflect actual successes/failures: $counts",counts in expected)
                    }
                    val summary=dao.batchSummaries().first().single {it.batch.id==id}
                    assertEquals(if(case.completed==0) "FAILED" else if(case.completed==2) "COMPLETED" else "PARTIAL",summary.status)
                    cases.put(JSONObject().put("completed",case.completed).put("failed",2-case.completed)
                        .put("completion_enabled",case.completionEnabled).put("failure_enabled",case.failureEnabled)
                        .put("channel",case.channel ?: JSONObject.NULL).put("batch_status",summary.status))
                } finally {
                    app.notifications.dismiss("batch:$id")
                    for(row in rows) {dao.state(row.id,"CANCELLED");dao.delete(row.id)}
                    dao.removeEmptyBatch(id)
                }
            }
        } finally {
            settings.notification("completion_notifications",original.completionNotifications)
            settings.notification("failure_notifications",original.failureNotifications)
            settings.boolean("notifications",original.notifications)
            evidence("batch-notification-remediation",cases)
        }
    }

    private fun format(id:String,height:Int?=null)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id.${if(height==null) "m4a" else "mp4"}")
        .put("ext",if(height==null) "m4a" else "mp4").put("vcodec",if(height==null) "none" else "h264")
        .put("acodec","aac").put("height",height).put("width",height?.times(2))
        .put("mediaflow_dimensions",height!=null).put("abr",128).put("filesize",960000))
    @Test fun savedDefaultsReachRealViewModelAndMetadataCannotReplaceExplicitChoice()=runBlocking<Unit> {
        val settings=SettingsRepository(app);val original=settings.current();val cases=JSONArray()
        try {
            for(type in listOf("video","audio")) {
                settings.text("download_type",type);settings.integer("preferred_height",480)
                val url="https://example.com/mf07-${UUID.randomUUID()}"
                val media=Media(url,"mf07","Disposable metadata","Test",null,null,60.0,
                    listOf(format("audio"),format("low",360),format("high",1080)))
                val extractor=Extractor(app)
                val metadata=Extractor::class.java.getDeclaredField("metadata").apply {isAccessible=true}.get(extractor) as ExtractorMetadata
                val network=app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.networkHandle
                val request=MetadataRequest(url,network,MetadataRoute.AUTOMATIC,freshness=MetadataFreshness.REFRESH)
                metadata.analyze(request) {media}
                val store=ViewModelStore();var model:QuickShareViewModel?=null
                try {
                    withContext(Dispatchers.Main) {
                        model=QuickShareViewModel(extractor,app.repository,settings,app.scheduler,SavedStateHandle(),app.playlists,app.plannedDownloads)
                        store.put("mf07",model!!)
                        val trace=UUID.randomUUID().toString()
                        model!!.accept(url,trace);model!!.windowDrawn(trace)
                    }
                    await {model!!.state.value.catalogReady}
                    val loaded=model!!.state.value
                    assertNull(loaded.error);assertNull(loaded.choice);assertNull(loaded.queuedId)
                    assertEquals(type=="audio",loaded.defaultChoice!!.audio)
                    assertEquals(if(type=="audio") "audio" else "low",loaded.defaultChoice!!.formatId)
                    val explicit=QuickShareChoice(false,"high")
                    withContext(Dispatchers.Main) {model!!.choose(explicit)}
                    metadata.analyze(request) {media.copy(formats=media.formats.filter {it.id!="high"})}
                    withContext(Dispatchers.Main) {model!!.retry()}
                    await {!model!!.state.value.analyzing}
                    assertEquals(explicit,model!!.state.value.choice)
                    assertTrue(model!!.state.value.preferenceUnavailable)
                    assertFalse(model!!.state.value.canSubmit)
                    metadata.analyze(request) {media.copy(title="Enriched metadata")}
                    withContext(Dispatchers.Main) {model!!.retry()}
                    await {!model!!.state.value.analyzing}
                    assertEquals(explicit,model!!.state.value.choice)
                    assertEquals("high",model!!.state.value.selection!!.primary.id)
                    assertTrue(model!!.state.value.canSubmit)
                    cases.put(JSONObject().put("saved_type",type).put("saved_height",480)
                        .put("initial_default",loaded.defaultChoice!!.formatId).put("initial_explicit_choice",JSONObject.NULL)
                        .put("missing_explicit_format_blocked",true).put("restored_choice","high").put("submitted",false))
                } finally {withContext(Dispatchers.Main) {store.clear()};extractor.clearMetadataCache()}
            }
        } finally {
            settings.text("download_type",original.downloadType);settings.integer("preferred_height",original.preferredHeight)
            evidence("quickshare-saved-defaults",cases)
        }
    }
}
