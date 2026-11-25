package com.zdownloader.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual Quick Share controls, separate caller APK, persistent runner and public storage. */
class QuickShareBackgroundMatrixTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val automation get()=instrumentation.uiAutomation
    private val args get()=InstrumentationRegistry.getArguments()
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun eventually(ms:Long=15000,predicate:()->Boolean) {val end=SystemClock.elapsedRealtime()+ms;while(!predicate()) {if(SystemClock.elapsedRealtime()>end) error("Expected state/UI did not appear");Thread.sleep(60)}}
    private fun receiver():ShareReceiverActivity? {var value:ShareReceiverActivity?=null;instrumentation.runOnMainSync {value=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ShareReceiverActivity>().firstOrNull()};return value}
    private fun capture(name:String) {compose.waitForIdle();val bitmap=automation.takeScreenshot()!!;File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    private fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        fun locked()=Regex("\\(current\\):.*deviceLocked=1").containsMatchIn(shell("dumpsys trust"))
        if(locked()) {
            val pin=args.getString("devicePin")?.takeIf {it.matches(Regex("[0-9]{4,12}"))}
                ?: error("Device is PIN-locked; provide the approved devicePin instrumentation argument")
            shell("input text $pin");shell("input keyevent KEYCODE_ENTER")
            eventually(10_000) {!locked()}
        }
    }

    private fun gallery(label:String,uri:Uri,mime:String,verifyAudio:Boolean=true) {
        // Never browse/capture the user's album grid: it can contain unrelated
        // private media. Publication is asserted via MediaStore above, and
        // playback opens only the exact synthetic/public download URI.
        shell("am force-stop com.hihonor.photos")
        // GalleryMain is an album launcher and ignores VIEW data. The exported
        // single-item viewer resolves image VIEW intents; explicit video data
        // selects the real MediaStore VideoItem in its shared photo/video page.
        val gallery=ComponentName("com.hihonor.photos","com.hihonor.gallery.app.SinglePhotoActivity")
        app.grantUriPermission(gallery.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val launched=shell("am start -W -n ${gallery.flattenToString()} -a android.intent.action.VIEW -d $uri -t $mime --grant-read-uri-permission")
        assertFalse("Gallery launch failed: $launched",launched.contains("Exception")||launched.contains("Error:"))
        eventually {automation.rootInActiveWindow?.packageName?.toString()=="com.hihonor.photos"}
        val uid=Regex("uid:(\\d+)").find(shell("pm list packages -U com.hihonor.photos"))!!.groupValues[1]
        if(verifyAudio) eventually {shell("dumpsys audio").lineSequence().any {it.contains("AudioPlaybackConfiguration")&&it.contains("u/pid:$uid/")&&it.contains("state:started")}}
        else eventually {automation.rootInActiveWindow?.packageName?.toString()=="com.hihonor.photos"}
        // No Gallery screenshots or detail-navigation gestures are recorded.
    }

    private fun scenario(label:String,url:String,audio:Boolean=false,prepareDeath:Boolean=false,audioOutput:String="mp3")=runBlocking {
        val main=AtomicInteger();val callback=ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==Stage.CREATED) main.incrementAndGet()}
        instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)}
        var id:String?=null;var retain=false
        val injectOutage=prepareDeath||args.getString("networkCase")==label
        val wifi=if(injectOutage) shell("settings get global wifi_on")!="0" else false
        val mobile=if(injectOutage) shell("settings get global mobile_data")!="0" else false
        val airplane=if(injectOutage) shell("settings get global airplane_mode_on")=="1" else false
        var radioChanged=false;var interrupted=false
        val settings=com.zdownloader.app.data.SettingsRepository(app)
        val oldCompletion=settings.current().completionNotifications
        // Avoid an OEM lock-screen wake from the optional completion alert in
        // the long screen-off probe; notification-enabled cases are separate.
        if(label=="dash-segmented") settings.boolean("completion_notifications",false)
        val report=JSONObject().put("source",url).put("caller","separate ShareOriginActivity APK").put("label",label)
        try {
            wakeAndUnlock()
            com.zdownloader.app.engine.AnalysisTrace.clear()
            app.extractor.clearMetadataCache()
            report.put("url_accepted",UrlPolicy.normalize(url).isNotBlank()).put("metadata_cache_cleared",true)
            automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            // An earlier assertion failure can leave this caller task alive; its
            // auto-shared flag then suppresses the next synthetic Send intent.
            shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")
            shell("cmd statusbar collapse")
            val launch=shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x10008000 --es fixtureUrl '"+url.replace("'","'\\''")+"' --ez autoShare true")
            assertFalse("External caller launch failed: $launch",launch.contains("Exception")||launch.contains("Error:"))
            eventually {receiver()!=null};val activity=receiver()!!
            var model:QuickShareViewModel?=null
            instrumentation.runOnMainSync {model=ViewModelProvider(activity)[QuickShareViewModel::class.java]}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            val height=when {audio->-1;label in setOf("verified-1080","verified-720-under-1080","facebook-shared-link")->1080;label=="dash-segmented"->args.getString("segmentedHeight")?.toInt() ?: 360;else->720}
            lateinit var selection:Selection
            var resolved=false
            compose.waitUntil(90_000) {
                val media=model!!.state.value.media
                media!=null&&(if(audio) media.audioOptions().isNotEmpty() else media.videoOptions().isNotEmpty())
            }
            val media=requireNotNull(model!!.state.value.media)
            if(audio) {
                val source=media.audioOptions().first()
                val choice=QuickShareChoice(true,source.id,audioOutput)
                selection=requireNotNull(choice.resolve(media))
                val rowTag="share_audio_$audioOutput"
                compose.waitUntil(20_000) {compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(rowTag))
                compose.onNodeWithTag(rowTag).assertIsEnabled().performClick()
                assertEquals(source.id,model!!.state.value.choice?.formatId)
                report.put("selected_real_audio_source",true).put("selected_row_tag",rowTag)
                    .put("selected_format_id",selection.primary.id).put("selected_audio_output",selection.output)
            } else {
                val actualSource=media.videoOptions().mapNotNull {format->verifiedQualityEdge(format)?.let {it to format}}
                    .filter {it.first<=height}.maxByOrNull {it.first}
                    ?: error("No verified video source is within the requested $height-pixel bound for $label")
                if(actualSource.first in QuickShareCatalog.basicQualities) {
                    selection=requireNotNull(VideoPreferenceSelection.ready(media,actualSource.first))
                    assertEquals("Compact quality row must map to its verified source edge",actualSource.first,verifiedQualityEdge(selection.primary))
                    val rowTag="share_video_${actualSource.first}"
                    compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(rowTag))
                    compose.onNodeWithTag(rowTag).assertIsEnabled().performClick()
                    assertEquals(actualSource.first,model!!.state.value.qualityPreference)
                    report.put("selected_quality_cap",actualSource.first).put("selected_row_tag",rowTag)
                } else {
                    compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"))
                    compose.onNodeWithTag("share_more").performClick()
                    compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_expanded").fetchSemanticsNodes().isNotEmpty()}
                    val choice=QuickShareChoice(false,actualSource.second.id)
                    selection=requireNotNull(choice.resolve(media))
                    val rowTag=compose.revealQuickShareVideoSource(media,actualSource.second)
                    compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(rowTag))
                    compose.onNodeWithTag(rowTag).assertIsEnabled().performClick()
                    assertEquals(choice,model!!.state.value.choice)
                    report.put("selected_exact_source",true).put("selected_row_tag",rowTag)
                }
                assertEquals("Chosen quality is an actual source pixel edge",actualSource.first,verifiedQualityEdge(selection.primary))
                report.put("requested_height",height).put("selected_real_quality",true)
                    .put("selected_format_id",selection.primary.id).put("selected_width",selection.primary.width)
                    .put("selected_height",selection.primary.height)
            }
            report.put("pressed_before_analysis",false).put("manual_resume",false)
            compose.onNodeWithTag("share_download").assertIsEnabled()
            capture("$label-initial")
            val downloadPressedAt=SystemClock.elapsedRealtime()
            compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
            compose.waitUntil(25000) {
                if(activity.isFinishing||activity.isDestroyed) return@waitUntil true
                val duplicate=runCatching {compose.onAllNodesWithTag("duplicate_confirmation").fetchSemanticsNodes()}.getOrNull()
                if(duplicate!=null&&duplicate.isNotEmpty()) {
                    compose.onNodeWithTag("duplicate_again").performClick()
                }
                activity.isFinishing||activity.isDestroyed
            }
            report.put("download_press_to_window_close_ms",SystemClock.elapsedRealtime()-downloadPressedAt)
            id=model!!.state.value.queuedId!!
            val submission=app.repository.dao.get(id!!)!!
            report.put("persisted_request_present",submission.request!=null)
            assertTrue("Selected real representation must persist its resolved source",submission.selection.isNotBlank())
            assertEquals(selection.primary.id,Selection.parse(submission.selection).primary.id)
            submission.request?.let {report.put("persisted_request",JSONObject(it))}
            File(folder,"active-matrix-request.json").writeText(JSONObject().put("id",id).put("source",url).toString())
            report.put("id",id)
            eventually {automation.windows.any {it.root?.packageName?.toString()=="com.zdownloader.app.test"}}
            // Remove only this fixture's task, then leave the device asleep until
            // the durable row is terminal. No main Activity is opened to finish it.
            shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")
            shell("input keyevent KEYCODE_SLEEP")
            report.put("screen_off_until_terminal",true).put("caller_task_removed",true)
            val start=SystemClock.elapsedRealtime();val states=JSONArray();val samples=JSONArray();var lastBytes=-1L;var last="";var prepared=false
            withTimeout(if(prepareDeath) 1800000 else 600000) {
                while(true) {
                    val row=app.repository.dao.get(id!!)!!
                    if(row.bytes!=lastBytes) {samples.put(JSONObject().put("at",SystemClock.elapsedRealtime()).put("bytes",row.bytes).put("total",row.total).put("kind",row.sizeKind).put("speed",row.speed).put("status",row.status));lastBytes=row.bytes}
                    if(!resolved&&row.selection.isNotBlank()) {
                        selection=Selection.parse(row.selection);resolved=true
                        report.put("extractor_source",row.source).put("protocol",selection.primary.protocol).put("separate_audio",selection.secondary!=null)
                            .put("output",selection.output).put("width",selection.primary.width).put("height",selection.primary.height)
                        if(!audio) assertTrue("Resolved quality exceeds policy",verifiedQualityEdge(selection.primary)!!<=height)
                    }
                    if(row.status.startsWith("DOWNLOADING")&&!report.has("ongoing_notifications")) {
                        val active=app.getSystemService(android.app.NotificationManager::class.java).activeNotifications
                            .filter {it.id==411||it.id==id!!.hashCode()}
                        if(active.any {it.id==id!!.hashCode()}) report.put("ongoing_notifications",JSONArray(active.map {item->
                            JSONObject().put("id",item.id).put("group",item.notification.group).put("flags",item.notification.flags)
                                .put("title",item.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
                                .put("text",item.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
                                .put("progress",item.notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
                                .put("indeterminate",item.notification.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
                        }))
                    }
                    if(injectOutage&&!interrupted&&row.status.startsWith("DOWNLOADING")&&row.bytes>=65536) {
                        radioChanged=true
                        shell("svc wifi disable");shell("svc data disable");shell("cmd connectivity airplane-mode enable")
                        delay(3000)
                        report.put("outage_status",app.repository.dao.get(id!!)!!.status).put("bytes_before_outage",row.bytes)
                        shell("cmd connectivity airplane-mode ${if(airplane) "enable" else "disable"}");shell("svc wifi ${if(wifi) "enable" else "disable"}");shell("svc data ${if(mobile) "enable" else "disable"}")
                        radioChanged=false;interrupted=true
                        report.put("network_interruption_injected",true)
                        if(args.getString("wakeForRouteValidation")=="true") {
                            report.put("route_before_external_wake",com.zdownloader.app.engine.NetworkDiagnostics.connectionIssue()?.name ?: "ready")
                            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
                            withTimeout(60000) {while(com.zdownloader.app.engine.NetworkDiagnostics.connectionIssue()!=null) delay(200)}
                            report.put("external_wake_for_route_validation",true)
                            shell("input keyevent KEYCODE_SLEEP")
                        }
                    }
                    if(row.status!=last) {states.put(JSONObject().put("status",row.status).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("bytes",row.bytes));last=row.status}
                    if(prepareDeath&&row.checkpoint=="MERGED"&&row.status=="VALIDATING") {
                        report.put("checkpoint",row.checkpoint).put("states",states).put("filename",row.filename).put("main_activity_created",main.get()).put("state","prepared_for_process_death")
                        val directory=app.storage.temporary(id!!)
                        report.put("primary_complete",File(directory,"primary.${selection.primary.ext}.complete").isFile)
                            .put("secondary_complete",selection.secondary==null||File(directory,"secondary.${selection.secondary!!.ext}.complete").isFile)
                        File(folder,"process-death-prepared.json").writeText(report.toString(2));retain=true;prepared=true
                        if(args.getString("holdForProcessDeath")=="true") while(true) delay(1000)
                        break
                    }
                    if(row.status in listOf("COMPLETED","FAILED","CANCELLED","PAUSED")) break
                    delay(100)
                }
            }
            if(prepared) return@runBlocking
            val row=app.repository.dao.get(id!!)!!
            // Keep the selected-quality acceptance artifact in Gallery for
            // independent inspection; this test must not delete its download.
            if((label=="selected-real-720"||label=="youtube-video")&&row.status=="COMPLETED") retain=true
            assertTrue("A completed request must have a persisted selection",resolved)
            report.put("screen_interactive_at_completion",app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            if(injectOutage) assertTrue("The outage must actually occur while transferring",interrupted)
            assertEquals(row.error,"COMPLETED",row.status);assertEquals(0,main.get());assertEquals("VALIDATED",row.checkpoint)
            assertTrue(row.uri!!.contains(if(audio) "/audio/media/" else "/video/media/"))
            val uri=Uri.parse(row.uri)
            app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use {cursor->assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));assertEquals(if(audio) "Music/MediaFlow/" else "Movies/MediaFlow/",cursor.getString(1))}
            val file=File(app.storage.temporary("proof-$id"),"saved.${selection.output}")
            app.contentResolver.openInputStream(uri)!!.use {source->file.outputStream().use {source.copyTo(it)}}
            val info=FFprobeKit.getMediaInformation(file.path).mediaInformation
            if(!audio) {
                val retriever=android.media.MediaMetadataRetriever()
                try {retriever.setDataSource(file.path);val frame=retriever.getFrameAtTime(1000000);assertNotNull("Published video must decode",frame);frame?.recycle();report.put("decoded_frame_verified",true)} finally {retriever.release()}
            }
            val expectsAudio=audio || selection.primary.audio || selection.secondary?.audio==true
            assertEquals("Audio tracks must match the selected source",expectsAudio,info.streams.any {it.type=="audio"})
            if(!audio) assertTrue(info.streams.any {it.type=="video"}) else assertEquals(if(audioOutput=="mp3") "mp3" else codecFamily(selection.primary.audioCodec),info.streams.first {it.type=="audio"}.codec)
            if(!audio) {
                val video=info.streams.first {it.type=="video"}
                selection.primary.width?.let {assertEquals("Published width",it,video.width?.toInt())}
                selection.primary.height?.let {assertEquals("Published height",it,video.height?.toInt())}
                report.put("published_width",video.width).put("published_height",video.height).put("published_codec",video.codec)
            }
            val manager=app.getSystemService(android.app.NotificationManager::class.java)
            val prefs=com.zdownloader.app.data.SettingsRepository(app).current()
            val completionAllowed=prefs.notifications&&prefs.completionNotifications&&manager.areNotificationsEnabled()&&
                (android.os.Build.VERSION.SDK_INT<26||manager.getNotificationChannel(com.zdownloader.app.background.DownloadNotifications.COMPLETED_CHANNEL)?.importance!=android.app.NotificationManager.IMPORTANCE_NONE)
            report.put("completion_notification_allowed",completionAllowed).put("system_notifications_enabled",manager.areNotificationsEnabled())
                .put("automatic_retries",row.retries)
                .put("status",row.status).put("uri",row.uri).put("bytes",row.outputSize).put("output_validated",true)
            if(completionAllowed) {
                try {eventually {manager.activeNotifications.any {it.id==id!!.hashCode()&&it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT==0}}}
                catch(error:Throwable) {
                    report.put("notification_pending",app.repository.dao.get(id!!)!!.notificationPending)
                    report.put("notices_after_wait",JSONArray(manager.activeNotifications.map {item->JSONObject().put("id",item.id).put("flags",item.notification.flags).put("group",item.notification.group).put("channel",item.notification.channelId)}))
                    throw error
                }
                report.put("notification",manager.activeNotifications.first {it.id==id!!.hashCode()}.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            } else report.put("notification","not expected: system permission/channel or saved preference disabled")
            report.put("completion_notification_allowed",completionAllowed).put("system_notifications_enabled",manager.areNotificationsEnabled())
            val events=app.repository.dao.events(id!!).map {it.state}
            assertTrue("Verified-selection durable queue lifecycle: $events",
                events.containsAll(listOf("SCHEDULING_REQUESTED","SUBMITTED")) && (events.contains("SCHEDULER_QUEUED") || events.contains("SCHEDULER_RUNNER_READY")))
            assertTrue("Download transfer completed: $events",events.any {it.startsWith("DOWNLOADING")}&&events.contains("TRANSFER_COMPLETE"))
            assertTrue(events.contains("VALIDATING")&&events.contains("SAVING"))
            if(selection.secondary!=null) {
                val stage=if(selection.transcodeAudio||selection.transcodeVideo) "CONVERTING" else "MERGING"
                assertTrue("Expected background $stage and completed audio track: $events",events.contains(stage)&&events.contains("AUDIO_TRACK_COMPLETE"))
            }
            report.put("status",row.status).put("uri",row.uri).put("bytes",row.outputSize).put("states",states)
                .put("events",JSONArray(events)).put("main_activity_created",main.get()).put("output_validated",true)
            report.put("timed_events",JSONArray(app.repository.dao.events(id!!).map {JSONObject().put("state",it.state).put("time",it.time)}))
                .put("traces",JSONArray(com.zdownloader.app.engine.AnalysisTrace.snapshot())).put("live_progress",samples)
            wakeAndUnlock()
            assertFalse("The background request should complete screen-off",report.getBoolean("screen_interactive_at_completion"))
            if(!audio) gallery(label,uri,if(selection.output=="webm") "video/webm" else "video/mp4",expectsAudio)
            report.put("state","passed").put("status",row.status).put("uri",row.uri).put("bytes",row.outputSize).put("states",states).put("events",JSONArray(events)).put("main_activity_created",main.get()).put("gallery_playback",!audio&&expectsAudio).put("gallery_opened",!audio).put("source_has_audio",expectsAudio).put("published_before_gallery",true).put("output_validated",true)
            assertEquals(0,main.get());app.storage.removeTemporary("proof-$id")
        } catch(error:Throwable) {
            report.put("state","failed").put("error",error.toString())
            id?.let {key->
                val row=app.repository.dao.get(key)
                report.put("terminal_state",row?.status).put("checkpoint",row?.checkpoint).put("retries",row?.retries)
                    .put("terminal_code",row?.error).put("events",JSONArray(app.repository.dao.events(key).map {JSONObject().put("state",it.state).put("error",it.error)}))
                val diagnostics=File(app.filesDir,"network-diagnostics.jsonl")
                if(diagnostics.isFile) File(folder,"$label-network.jsonl").writeText(diagnostics.readLines().filter {line->
                    runCatching {JSONObject(line).optString("operation")==key}.getOrDefault(false)
                }.joinToString("\n"))
            }
            capture("$label-failure");throw error
        }
        finally {
            wakeAndUnlock()
            id?.let {key->
                val diagnostics=listOf("network-diagnostics.previous.jsonl","network-diagnostics.jsonl").flatMap {name->
                    File(app.filesDir,name).takeIf {it.isFile}?.readLines().orEmpty()
                }.filter {line->runCatching {JSONObject(line).optString("operation")==key}.getOrDefault(false)}
                File(folder,"$label-network.jsonl").writeText(diagnostics.joinToString("\n"))
            }
            if(radioChanged) {shell("cmd connectivity airplane-mode ${if(airplane) "enable" else "disable"}");shell("svc wifi ${if(wifi) "enable" else "disable"}");shell("svc data ${if(mobile) "enable" else "disable"}")}
            if(label=="dash-segmented") settings.boolean("completion_notifications",oldCompletion)
            File(folder,"matrix-$label.json").writeText(report.toString(2))
            File(folder,"active-matrix-request.json").delete()
            if(!retain) id?.let {key->app.coordinator.stopAndAwait(key);val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(key);app.repository.remove(key)}}
            receiver()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)}
        }
    }
    @Test fun directMp4() {scenario("direct-mp4","https://www.w3schools.com/html/mov_bbb.mp4")}
    @Test fun hls() {scenario("hls","https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8")}
    @Test fun dash() {scenario("dash","https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")}
    @Test fun dashSegmented() {scenario("dash-segmented","https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd")}
    @Test fun verified720() {scenario("verified-720","https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4")}
    @Test fun selected720BackgroundToGallery() {scenario("selected-real-720","https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4")}
    @Test fun verified1080() {scenario("verified-1080","https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4")}
    @Test fun cap1080On720Only() {scenario("verified-720-under-1080","https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4")}
    @Test fun mp3() {scenario("mp3","https://www.w3schools.com/html/mov_bbb.mp4",audio=true)}
    @Test fun audioOriginal() {scenario("audio-original","https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",audio=true,audioOutput="original")}
    @Test fun facebookSharedLink() {scenario("facebook-shared-link","https://www.facebook.com/reel/1102243642315985/")}
    @Test fun genericHtml5() {scenario("generic-html5","https://www.w3schools.com/html/html5_video.asp")}
    @Test fun instagramReel() {scenario("instagram-reel","https://www.instagram.com/reel/Chunk8-jurw/")}
    @Test fun tiktokVideo() {scenario("tiktok-video","https://www.tiktok.com/@scout2015/video/6718335390845095173")}
    @Test fun youtubeVideo() {scenario("youtube-video","https://www.youtube.com/watch?v=jNQXAC9IVRw")}
    @Test fun directWebm() {scenario("direct-webm","https://storage.googleapis.com/shaka-demo-assets/angel-one/video_144p_96k_vp9.webm")}
    @Test fun prepareProcessDeath() {scenario("long-process-death",requireNotNull(args.getString("longUrl")),prepareDeath=true)}
    @Test fun inspectRecoveryStorageWithoutStartingWorkManager()=runBlocking {
        val report=JSONObject().put("time",System.currentTimeMillis())
        report.put("unfinished",JSONArray(app.repository.dao.observe().first().filter {it.status !in listOf("COMPLETED","CANCELLED","FAILED","PAUSED")}.map {JSONObject().put("id",it.id).put("status",it.status).put("checkpoint",it.checkpoint)}))
        val path=listOf(app.getDatabasePath("androidx.work.workdb"),File(app.noBackupFilesDir,"androidx.work.workdb")).firstOrNull {it.exists()} ?: app.getDatabasePath("androidx.work.workdb")
        if(path.exists()) {
            val database=android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            try {database.rawQuery("SELECT id,state,worker_class_name,run_attempt_count,initial_delay,schedule_requested_at,last_enqueue_time FROM WorkSpec WHERE state IN (0,1,4)",null).use {cursor->
                val rows=JSONArray();while(cursor.moveToNext()) {val row=JSONObject();cursor.columnNames.forEachIndexed {index,name->row.put(name,cursor.getString(index))};rows.put(row)};report.put("work_specs",rows)
            }} finally {database.close()}
        }
        val manager=app.getSystemService(android.app.job.JobScheduler::class.java)
        report.put("default_jobs",JSONArray(manager.allPendingJobs.map {it.id}))
        if(android.os.Build.VERSION.SDK_INT>=34) report.put("workmanager_jobs",JSONArray(manager.forNamespace("androidx.work.systemjobscheduler").allPendingJobs.map {it.id}))
        if(android.os.Build.VERSION.SDK_INT>=34) {
            val reasons=JSONObject()
            for(scheduler in listOf(manager,manager.forNamespace("androidx.work.systemjobscheduler"))) for(job in scheduler.allPendingJobs) {
                val reason=scheduler.getPendingJobReason(job.id)
                val names=android.app.job.JobScheduler::class.java.fields.filter {it.name.startsWith("PENDING_JOB_REASON_")&&it.type==Int::class.javaPrimitiveType}.filter {it.getInt(null)==reason}.map {it.name}
                reasons.put(job.id.toString(),JSONObject().put("reason",reason).put("names",JSONArray(names)).put("latency",job.minLatencyMillis))
            }
            report.put("pending_reasons",reasons)
        }
        File(folder,"recovery-storage.json").writeText(report.toString(2))
    }
    @Test fun cleanupInterruptedHarnessFixture()=runBlocking {
        val marker=File(app.getExternalFilesDir(null),"advanced-audit/zd-process-recovery-187/process-death-prepared.json")
        val id=JSONObject(marker.readText()).getString("id")
        val row=app.repository.dao.get(id)
        if(row!=null) {
            app.repository.cancel(id);app.coordinator.stopAndAwait(id);app.notifications.dismiss(id)
            if(row.status=="COMPLETED") app.storage.deleteFile(row) else app.repository.remove(id)
        }
        Unit
    }
    @Test fun cleanupExternalHarnessFixture()=runBlocking {
        // Exact fixture ID retained by the external stop/crash experiment.
        val id="be168cf4-899c-480d-9330-9ec4841ab9de"
        val row=app.repository.dao.get(id)
        if(row!=null) {
            app.repository.cancel(id);app.coordinator.stopAndAwait(id);app.notifications.dismiss(id)
            if(row.status=="COMPLETED") app.storage.deleteFile(row) else app.repository.remove(id)
        }
        Unit
    }
    @Test fun verifyRecoveredProcessDeath()=runBlocking {
        val id=args.getString("recoveryRequestId") ?: app.repository.dao.completed().first().single {it.uri==args.getString("recoveryUri")}.id
        val row=app.repository.dao.get(id)!!
        assertEquals(row.error,"COMPLETED",row.status);assertEquals("VALIDATED",row.checkpoint)
        val events=app.repository.dao.events(id).map {it.state}
        assertEquals("Recovery repeated merging",1,events.count {it=="MERGING"})
        val uri=Uri.parse(row.uri)
        app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE),null,null,null)!!.use {cursor->assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));assertEquals(row.outputSize,cursor.getLong(1))}
        val output=File(folder,"recovered-output.mp4")
        app.contentResolver.openInputStream(uri)!!.use {source->output.outputStream().use {source.copyTo(it)}}
        val info=FFprobeKit.getMediaInformation(output.path).mediaInformation
        val video=info.streams.first {it.type=="video"};assertTrue(info.streams.any {it.type=="audio"})
        val selection=Selection.parse(row.selection)
        assertEquals(selection.primary.width,video.width?.toInt());assertEquals(selection.primary.height,video.height?.toInt())
        val notices=app.getSystemService(android.app.NotificationManager::class.java)
        eventually {notices.activeNotifications.any {it.id==id.hashCode()&&it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT==0}}
        File(folder,"process-recovery-verified.json").writeText(JSONObject().put("state","passed").put("status",row.status).put("uri",row.uri).put("bytes",row.outputSize).put("merge_events",1)
            .put("width",video.width).put("height",video.height).put("video_codec",video.codec).put("duration",info.duration).put("events",JSONArray(events)).toString(2))
    }
}
