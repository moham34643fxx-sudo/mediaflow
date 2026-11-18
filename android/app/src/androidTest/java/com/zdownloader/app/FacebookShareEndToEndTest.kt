package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Facebook Lite -> its Share UI -> Android chooser -> ZD -> published media.
 * Source coordinates are supplied from inspected device screenshots because Lite
 * omits its canvas controls from accessibility. No such mechanism exists in the app.
 */
class FacebookShareEndToEndTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val automation get()=instrumentation.uiAutomation
    private val args get()=InstrumentationRegistry.getArguments()
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private fun eventually(ms:Long=15000,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+ms
        while(!predicate()) {if(SystemClock.elapsedRealtime()>end) throw AssertionError("Expected native UI/state did not appear");Thread.sleep(50)}
    }
    private fun capture(name:String) {
        val image=automation.takeScreenshot() ?: error("System screenshot unavailable")
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun tapArgument(key:String) {
        val point=requireNotNull(args.getString(key)) {"Supply $key=x,y from the inspected source screen"}.split(',').map(String::toInt)
        require(point.size==2)
        shell("input tap ${point[0]} ${point[1]}")
    }
    private fun receiver():ShareReceiverActivity? {
        var result:ShareReceiverActivity?=null
        instrumentation.runOnMainSync {result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ShareReceiverActivity>().firstOrNull()}
        return result
    }
    private fun facebookVisible()=automation.windows.any {it.root?.packageName?.toString()=="com.facebook.lite"}

    @Test fun originalFacebookVideoStartsInBackgroundAndPublishesValidMp4()=runBlocking {
        val original=requireNotNull(args.getString("facebookUrl"))
        val expectedId=requireNotNull(args.getString("facebookMediaId"))
        require(expectedId.matches(Regex("[0-9]+")))
        val launchUrl=args.getString("facebookLaunchUrl") ?: original
        require(launchUrl.matches(Regex("https://(?:www\\.|m\\.)?facebook\\.com/[^\\s]+")))
        val report=JSONObject().put("original_url",original).put("expected_media_id",expectedId).put("source_app","com.facebook.lite")
        val samples=JSONArray();val mainLaunches=java.util.concurrent.atomic.AtomicInteger()
        val mainEvents=java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
        val testStarted=SystemClock.elapsedRealtime()
        val callback=ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==Stage.CREATED) {
            mainLaunches.incrementAndGet()
            mainEvents.add(JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()-testStarted).put("action",activity.intent?.action)
                .put("flags",activity.intent?.flags).put("task_id",activity.taskId)
                .put("referrer_package",activity.referrer?.takeIf {it.scheme=="android-app"}?.host))
        }}
        instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)}
        fun save() {File(folder,"facebook-share-e2e.json").writeText(report.toString(2))}
        try {
            assertTrue("Facebook Lite is unavailable",shell("pm path com.facebook.lite").contains("package:"))
            automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            if(args.getString("facebookSourceReady")!="true") {
                shell("am force-stop com.facebook.lite")
                // UiAutomation tokenizes directly rather than through a shell;
                // quoting this no-space URI would pass literal quote characters.
                report.put("launch_result",shell("am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d $launchUrl -n com.facebook.lite/com.facebook.deeplinking.aliasactivity.CatchAllUriAliasActivity"))
                Thread.sleep((args.getString("facebookSourceWaitMs")?.toLong() ?: 4000).coerceIn(1000,20000))
            }
            eventually {facebookVisible()}
            capture("facebook-original-video")
            tapArgument("facebookSharePoint")
            // Lite exposes this canvas sheet without accessibility nodes. Wait for
            // its inspected dark bottom-panel background, rather than tapping the
            // underlying video before the asynchronously loaded sheet appears.
            eventually(25000) {
                automation.takeScreenshot()?.let {image->
                    try {
                        val color=image.getPixel(5,image.height-20)
                        val channels=listOf(android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color))
                        channels.all {it in 24..64}&&channels.max()-channels.min()<10
                    } finally {image.recycle()}
                }==true
            }
            Thread.sleep(650)
            capture("facebook-share-panel")
            tapArgument("facebookMorePoint")
            eventually {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("ZD")?.any {
                    it.text?.toString()?.trim()=="ZD"||it.contentDescription?.toString()?.trim()=="ZD"
                }==true
            }
            capture("facebook-android-chooser")
            val target=automation.rootInActiveWindow!!.findAccessibilityNodeInfosByText("ZD").first {
                it.text?.toString()?.trim()=="ZD"||it.contentDescription?.toString()?.trim()=="ZD"
            }
            target.refresh();val rect=android.graphics.Rect();target.getBoundsInScreen(rect)
            shell("input tap ${rect.centerX()} ${rect.centerY()}")
            eventually {receiver()!=null}
            instrumentation.waitForIdleSync()
            compose.waitUntil(120000) {compose.onAllNodesWithTag("share_more").fetchSemanticsNodes().isNotEmpty()}
            val activity=receiver()!!
            var model:QuickShareViewModel?=null
            instrumentation.runOnMainSync {model=ViewModelProvider(activity)[QuickShareViewModel::class.java]}
            eventually(120000) {model!!.state.value.media!=null}
            val media=model!!.state.value.media!!
            assertEquals("The actual Facebook share opened a different video",expectedId,media.id)
            val requestedHeight=args.getString("facebookQuality")?.toInt() ?: 1080
            compose.onNodeWithTag("share_video_$requestedHeight").performScrollTo().performClick().assertIsSelected()
            val selected=model!!.state.value.selection!!
            assertEquals("mp4",selected.output)
            report.put("received_shared_url",media.url).put("actual_media_id",media.id).put("selected_id",selected.primary.id)
                .put("selected_width",selected.primary.width).put("selected_height",selected.primary.height).put("output",selected.output)
            compose.waitForIdle();capture("facebook-zd-selected-mp4")
            report.put("submission_elapsed_ms",SystemClock.elapsedRealtime()-testStarted)
            compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
            compose.waitUntil(25000) {activity.isFinishing||activity.isDestroyed}
            eventually {facebookVisible()}
            val id=model!!.state.value.queuedId ?: error("No confirmed durable request")
            report.put("request_id",id).put("sheet_closed",true).put("facebook_visible_after_submission",true)
            val started=SystemClock.elapsedRealtime()
            var lastBytes=-1L;var lastStatus="";var sawProgress=false
            withTimeout(600000) {
                while(true) {
                    val row=app.repository.dao.get(id) ?: error("Persisted request vanished")
                    if(row.bytes!=lastBytes||row.status!=lastStatus) {
                        if(lastBytes>=0&&row.bytes>lastBytes&&facebookVisible()) sawProgress=true
                        samples.put(JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("status",row.status)
                            .put("bytes",row.bytes).put("error",row.error).put("facebook_visible",facebookVisible()).put("screen_interactive",app.getSystemService(android.os.PowerManager::class.java).isInteractive))
                        lastBytes=row.bytes;lastStatus=row.status
                        report.put("samples",samples);save()
                    }
                    if(row.status in listOf("COMPLETED","FAILED","CANCELLED","PAUSED")) break
                    delay(150)
                }
            }
            val completed=app.repository.dao.get(id)!!
            report.put("status",completed.status).put("error",completed.error).put("retries",completed.retries)
                .put("main_activity_created",mainLaunches.get()).put("main_activity_events",JSONArray(mainEvents.toList())).put("background_progress_observed",sawProgress).put("published_bytes",completed.outputSize)
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertEquals(0,completed.retries);assertEquals(0,mainLaunches.get())
            assertTrue("No real-byte progress was observed while Facebook stayed visible",sawProgress)
            val retained=Selection.parse(completed.selection)
            assertEquals(selected.primary.id,retained.primary.id);assertEquals(selected.primary.height,retained.primary.height)
            val uri=Uri.parse(completed.uri)
            assertTrue("Video must use the video collection",uri.toString().contains("/video/media/"))
            app.contentResolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING,android.provider.MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));assertEquals("Movies/MediaFlow/",cursor.getString(1))
            }
            report.put("published_before_gallery_open",true).put("final_uri",uri.toString())
            app.contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),"_display_name=? AND relative_path=?",arrayOf(completed.filename,"Movies/MediaFlow/"),null)!!.use {cursor->assertEquals("Duplicate final media",1,cursor.count)}
            val notices=app.getSystemService(android.app.NotificationManager::class.java)
            val language=com.zdownloader.app.data.SettingsRepository(app).current().language
            val expectedNotice=if(language=="ar") "تم اكتمال تحميل الفيديو" else "Video download completed."
            eventually {notices.activeNotifications.any {it.id==id.hashCode()&&it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()==expectedNotice}}
            val notice=notices.activeNotifications.first {it.id==id.hashCode()}.notification
            assertEquals(expectedNotice,notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertEquals(0,notice.flags and android.app.Notification.FLAG_ONGOING_EVENT)
            report.put("completion_notification",notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            val output=File(folder,"facebook-real-share-output.mp4")
            app.contentResolver.openInputStream(uri)!!.use {input->output.outputStream().use {input.copyTo(it)}}
            assertEquals(completed.outputSize,output.length())
            val info=FFprobeKit.getMediaInformation(output.path).mediaInformation
            val video=info.streams.first {it.type=="video"}
            assertEquals(selected.primary.width,video.width?.toInt());assertEquals(selected.primary.height,video.height?.toInt())
            assertTrue(info.streams.any {it.type=="audio"})
            report.put("video_codec",video.codec).put("duration",info.duration).put("video_frames",video.allProperties.optString("nb_frames"))
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(app,uri)
                val first=retriever.getFrameAtTime(0);val last=retriever.getFrameAtTime(((media.duration ?: 1.0)*1000000-1000000).toLong())
                assertNotNull(first);assertNotNull(last);first?.recycle();last?.recycle()
            } finally {retriever.release()}
            val events=app.repository.dao.events(id).map {it.state}
            report.put("events",JSONArray(events));assertTrue(events.contains("SCHEDULER_RUNNER_READY"));assertTrue(events.contains("VALIDATING"));assertTrue(events.contains("SAVING"))
            val network=File(app.filesDir,"network-diagnostics.jsonl")
            delay(500)
            if(network.isFile) report.put("request_diagnostics",JSONArray(network.readLines().mapNotNull {runCatching {JSONObject(it)}.getOrNull()}.filter {it.optString("operation")==id}))
            report.put("state","passed").put("retained_for_user",true).put("android_first_and_last_frame_decodable",true)
            capture("facebook-returned-completed")
            if(args.getString("verifyGallery")=="true") {
                shell("am force-stop com.hihonor.photos")
                shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.hihonor.photos")
                eventually {automation.rootInActiveWindow?.packageName?.toString()=="com.hihonor.photos"}
                Thread.sleep(1200)
                fun clickText(text:String):Boolean {
                    val nodes=automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
                    val node=nodes.firstOrNull() ?: return false
                    val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}");Thread.sleep(500);return true
                }
                val title=automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.hihonor.photos:id/head_actionmode_title")?.firstOrNull()?.text?.toString()
                if(title !in listOf("الفيديوهات","Videos")) {
                    clickText("الألبومات")||clickText("Albums")
                    clickText("الفيديوهات")||clickText("Videos")
                }
                eventually {automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.hihonor.photos:id/iv_thumbnail")?.isNotEmpty()==true}
                capture("gallery-video-list")
                val first=automation.rootInActiveWindow!!.findAccessibilityNodeInfosByViewId("com.hihonor.photos:id/iv_thumbnail").first()
                val bounds=android.graphics.Rect();first.getBoundsInScreen(bounds)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                Thread.sleep(1500);capture("gallery-playing-first")
                val uid=Regex("uid:(\\d+)").find(shell("pm list packages -U com.hihonor.photos"))!!.groupValues[1].toInt()
                var audio=""
                eventually(15000) {
                    audio=shell("dumpsys audio").lineSequence().firstOrNull {it.contains("AudioPlaybackConfiguration")&&it.contains("u/pid:$uid/")&&it.contains("state:started")}.orEmpty()
                    audio.isNotEmpty()
                }
                Thread.sleep(2000);capture("gallery-playing-later")
                shell("input tap 72 132");Thread.sleep(300);capture("gallery-file-details")
                report.put("gallery_package","com.hihonor.photos").put("gallery_opened_after_publication",true)
                    .put("gallery_audio_playing",audio).put("gallery_playback_screenshots",true)
                assertEquals("Gallery must not start ZD",0,mainLaunches.get())
            }
            save()
        } catch(error:Throwable) {
            report.put("state","failed").put("failure",error.javaClass.simpleName).put("message",error.message)
            capture("facebook-e2e-failure");save();throw error
        } finally {
            receiver()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)}
        }
    }
}
