@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.engine.LameEncoder
import com.zdownloader.app.playback.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CompactAudioPlayerTest {
    @get:Rule val compose=createEmptyComposeRule()
    @get:Rule val testName=org.junit.rules.TestName()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val testPackage get()=instrument.context.packageName
    private val connection get()=app.playback
    private lateinit var previousSettings:Settings
    private lateinit var home:File
    private lateinit var files:Map<String,File>
    private var previousPlayback:Map<String,*> = emptyMap<String,Any>()
    private var ownsSession=false
    private var underlyingClosed=false
    private lateinit var underlying:ActivityScenario<MainActivity>
    private val publications=mutableListOf<Uri>()
    private val arabicTitle="الدرس السادس — الحفظ والاسترجاع وفهم التسجيلات الصوتية الطويلة بدون فقد موضع الاستماع"
    private val englishTitle="A long English recording title that should keep moving inside the audio card without wrapping"
    private val shortTitle="Blue sky"
    private val artist="صاحب التسجيل التجريبي"
    private val report=JSONObject()
    private var phase="setup"
    private var expectedUri:Uri?=null
    private suspend fun <T> main(block:()->T):T=withContext(Dispatchers.Main) {block()}
    private fun externalOriginIntent()=Intent().setComponent(ComponentName(testPackage,ExternalAudioOriginActivity::class.java.name))
        .putExtra("targetPackage",app.packageName)
    private suspend fun await(timeoutMillis:Long=25_000,floatingWindow:Boolean?=null,pumpCompose:Boolean=true,
        predicate:()->Boolean) {
        try {withTimeout(timeoutMillis) {while(true) {
            // Pump the empty Compose rule while waiting for the active playback
            // Activity to attach the shared controller.
            if(pumpCompose) compose.waitForIdle()
            // Accessibility must run off the app main thread: the same thread
            // services requests for this process's overlay view hierarchy.
            if(main(predicate)&&(floatingWindow==null||hasFloatingWindow()==floatingWindow)) break
            delay(50)
        }}}
        catch(timeout:TimeoutCancellationException) {
            val state=main {playbackEvidence()}
            state.put("external_preview",main {
                previewTimingEvidence().put("expected_uri_matches",
                    expectedUri?.toString()==ExternalAudioPreview.player?.currentMediaItem?.mediaId)
            })
            state.put("accessibility_windows",accessibilityWindowEvidence())
            val overlayAudit=app.cacheDir.listFiles {file->file.name.startsWith("mediaflow-overlay-audit-")}
                ?.maxByOrNull {it.lastModified()}
                ?.let {file->runCatching {file.readLines().takeLast(32)}.getOrDefault(emptyList())}
                .orEmpty()
            state.put("overlay_audit",JSONArray(overlayAudit))
            val serviceDump=android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrument.uiAutomation.executeShellCommand("dumpsys activity services ${app.packageName}"))
                .bufferedReader().use {it.readText()}
            state.put("service_bindings",JSONArray(serviceDump.lineSequence().filter {line->
                listOf("ConnectionRecord","AppBindRecord","startRequested=","isForeground=").any(line::contains)
            }.take(20).toList()))
            report.put("timeout",state)
            throw AssertionError("Playback wait failed: $state",timeout)
        }
    }
    private suspend fun awaitPreview(timeoutMillis:Long=25_000,floatingWindow:Boolean?=null,
        predicate:()->Boolean)=await(timeoutMillis,floatingWindow,pumpCompose=false,predicate=predicate)
    /** Only state flags and error classes; never serialize user metadata or URI values. */
    private fun playbackEvidence():JSONObject {
        val state=connection.state.value;val player=connection.player.value
        return JSONObject().put("phase",phase).put("expected_uri_matches",expectedUri?.toString()==state.current?.uri)
            .put("queue_count",state.queue.size).put("playing",state.playing).put("buffering",state.buffering)
            .put("ready",state.ready).put("position_ms",state.position).put("duration_ms",state.duration)
            .put("ui_error",state.error ?: JSONObject.NULL)
            .put("diagnostic",connection.diagnostic.takeIf {it.matches(Regex("[A-Za-z0-9_:.-]{1,160}"))} ?: "redacted")
            .put("controller_present",player!=null).put("controller_state",player?.playbackState ?: JSONObject.NULL)
            .put("controller_connected",player?.isConnected ?: false).put("repeat_mode",player?.repeatMode ?: JSONObject.NULL)
            .put("service_active",PlaybackRuntime.active)
            .put("play_when_ready",player?.playWhenReady ?: JSONObject.NULL).put("media_count",player?.mediaItemCount ?: 0)
            .put("player_error",player?.playerError?.errorCode ?: JSONObject.NULL)
            .put("error_cause_class",player?.playerError?.cause?.javaClass?.simpleName ?: JSONObject.NULL)
            .put("screen_interactive",app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("keyguard_locked",app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("overlay_permission",android.provider.Settings.canDrawOverlays(app))
            .put("external_openers",externalOpeners().size).put("full_windows",fullPlayers().size)
            .put("activities",JSONArray(visibleActivities().map {activity->
                JSONObject().put("class",activity.javaClass.simpleName)
                    .put("stage",ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity).name)
                    .put("finishing",activity.isFinishing).put("focused",activity.hasWindowFocus())
                    .put("handoff_in_progress",(activity as? ExternalAudioOpenActivity)?.handoffInProgress ?: false)
                    .put("resolving_preview",(activity as? ExternalAudioOpenActivity)?.resolvingPreview ?: false)
            }))
    }
    private fun visibleActivities()=listOf(Stage.RESUMED,Stage.STARTED,Stage.PAUSED,Stage.STOPPED)
        .flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.distinct()
    private fun externalOpeners()=visibleActivities().filterIsInstance<ExternalAudioOpenActivity>()
    private fun fullPlayers()=visibleActivities().filterIsInstance<PlayerActivity>()
    private fun previewTimingEvidence():JSONObject {
        val preview=ExternalAudioPreview.player
        return JSONObject().put("elapsed_ms",android.os.SystemClock.elapsedRealtime())
            .put("preview_present",preview!=null).put("playing",preview?.isPlaying ?: false)
            .put("position_ms",preview?.currentPosition ?: -1).put("duration_ms",preview?.duration ?: -1)
            .put("state",preview?.playbackState ?: -1)
    }

    @Before fun preserveSessionAndPrepareOwnedLocalAudio()=runBlocking<Unit> {
        if(main {PlaybackRuntime.active}) {
            val existing=withContext(Dispatchers.Main) {connection.connect()}
            assertFalse("Do not interrupt an existing audio session",main {existing.isPlaying||existing.playWhenReady||existing.mediaItemCount>0})
        }
        assertFalse("Do not interrupt the user's audio",main {connection.state.value.playing})
        previousSettings=SettingsRepository(app).current()
        previousPlayback=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE).all.toMap()
        ownsSession=true
        home=File(app.cacheDir,"compact-audio-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val backup=JSONObject()
        for((key,value) in previousPlayback) backup.put(key,JSONObject().put("type",when(value) {
            is String->"string";is Long->"long";is Int->"int";is Float->"float";is Boolean->"boolean";is Set<*>->"strings";else->error("Unexpected playback preference type")
        }).put("value",if(value is Set<*>) JSONArray(value.filterIsInstance<String>()) else value))
        File(home,"playback-before.json").writeText(backup.toString())
        File(home,"settings-before.json").writeText(JSONObject().put("theme",previousSettings.theme).put("language",previousSettings.language).toString())
        report.put("test",testName.methodName)
        files=fixtures()
        underlying=ActivityScenario.launch(MainActivity::class.java)
        underlying.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }

    @Test fun externalAudioCodecsUseOneSessionlessPreviewWithoutLaunchingTheFullPlayer()=runBlocking<Unit> {
        assertTrue(android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        underlying.close();underlyingClosed=true
        val codecs=JSONArray()
        report.put("codecs_completed",codecs)
        for((extension,mime) in listOf("mp3" to "audio/mpeg","m4a" to "audio/mp4","flac" to "audio/flac","wav" to "audio/wav")) {
            // HONOR accessibility synchronization consumed 18.7/20 seconds of
            // M4A before the next launch. Keep fixture EOS out of this control/
            // replacement scenario; EOS is not the behavior asserted here.
            val source=File(home,"codec-matrix.$extension")
            // Re-encode only the lossless FLAC fixture so STREAMINFO's sample
            // count describes the extended stream, not the original 20 seconds.
            val fixtureCodec=if(extension=="flac") "flac" else "copy"
            ffmpeg("-stream_loop","-1","-i",files.getValue(extension).path,"-t","90","-c",fixtureCodec,source.path)
            phase="external-$extension-start"
            report.put("before_$extension",main {previewTimingEvidence()})
            report.put("active_codec",extension).put("fixture_bytes",source.length())
            val probe=Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://fixture/track.$extension"),mime).setPackage(app.packageName)
            assertEquals(listOf("com.zdownloader.app.playback.AudioPopupActivity"),app.packageManager.queryIntentActivities(probe,0).map {it.activityInfo.name})
            val uri=send(source,mime)
            await(floatingWindow=true) {ExternalAudioPreview.player?.let {it.currentMediaItem?.mediaId==uri.toString()&&it.isPlaying&&it.currentPosition>100}==true}
            assertEquals(0,main {fullPlayers().size})
            assertTrue(hasFloatingWindow())
            assertEquals(0,main {activeMediaFlowSessionCount()})
            assertFalse(main {PlaybackRuntime.active})
            assertEquals(1,main {externalOpeners().count {!it.isFinishing}})
            assertEquals(1,main {checkNotNull(ExternalAudioPreview.player).mediaItemCount})
            report.put("duration_$extension",main {checkNotNull(ExternalAudioPreview.player).duration})
            assertTrue("The codec fixture must leave time for UI synchronization",
                main {checkNotNull(ExternalAudioPreview.player).duration>=80_000})
            assertArrayEquals(source.readBytes(),app.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
            report.put("after_$extension",main {previewTimingEvidence()})
            codecs.put(extension)
        }
        main {ExternalAudioPreview.stop()}
        await(floatingWindow=false) {ExternalAudioPreview.player==null}
        report.put("codecs",codecs).put("explicit_stop_ends_audio",true).put("candidate_media_sessions",0)
            .put("one_preview_at_a_time",true)
    }

    @Test fun internalAudioUsesFullPlayerAndMiniPlayerWithoutDuplicatingTheSession()=runBlocking<Unit> {
        val uri=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.playUri(uri.toString(),title=arabicTitle)}
        await {connection.state.value.current?.uri==uri.toString()&&connection.state.value.playing&&fullPlayers().any {!it.isFinishing}}
        assertEquals(1,main {fullPlayers().count {!it.isFinishing}})
        assertTrue(main {externalOpeners().isEmpty()})
        compose.onNodeWithTag("audio_player").assertIsDisplayed()
        val controller=main {connection.player.value!!}
        compose.onNodeWithTag("player_play_pause").performClick()
        await {!connection.state.value.playing}
        main {connection.seek(5000)}
        await {kotlin.math.abs(connection.state.value.position-5000)<300}
        val position=main {connection.state.value.position}
        assertSame(controller,main {connection.player.value})
        assertEquals(uri.toString(),main {connection.state.value.current?.uri})
        assertTrue("The full player moved the paused audio",main {kotlin.math.abs(position-5000)<300})
        assertFalse(main {connection.state.value.playing})
        assertEquals(1,main {controller.mediaItemCount})

        compose.onNodeWithTag("player_back").performClick()
        await {fullPlayers().isEmpty()&&connection.state.value.current?.uri==uri.toString()}
        compose.onNodeWithTag("mini_player").assertIsDisplayed()
        assertSame(controller,main {connection.player.value})
        assertFalse(main {connection.state.value.playing})
        assertTrue(main {kotlin.math.abs(connection.state.value.position-5000)<300})

        compose.onNodeWithTag("mini_player").performClick()
        await {fullPlayers().any {!it.isFinishing}}
        assertSame(controller,main {connection.player.value})
        assertEquals(uri.toString(),main {connection.state.value.current?.uri})
        assertEquals(1,main {controller.mediaItemCount})
        report.put("internal_audio_full_player",true).put("mini_player_reopens_same_session",true)
            .put("seek_ms",5000).put("no_restart_or_duplicate",true)
    }

    @Test fun slowExternalMetadataCompletesWithoutDuplicatingThePlaybackSession()=runBlocking<Unit> {
        send(files.getValue("mp3"),"audio/mpeg",slow=true)
        await {connection.state.value.playing&&connection.state.value.current?.uri==expectedUri?.toString()&&externalOpeners().isEmpty()}
        assertEquals(1,main {activeMediaFlowSessionCount()})
        report.put("slow_provider_completes",true).put("external_preview_activities",0).put("single_media_session",true)
    }

    @Test fun externalEntryPointRejectsUnsupportedAndPrivateInputsWithoutOpeningAPlayer()=runBlocking<Unit> {
        for((uri,mime) in listOf("content://fixture/video.mp4" to "video/mp4","https://example.com/audio.mp3" to "audio/mpeg",
            Uri.fromFile(File(app.filesDir,"private.mp3")).toString() to "audio/mpeg")) {
            main {app.startActivity(Intent(Intent.ACTION_VIEW).setClass(app,ExternalAudioOpenActivity::class.java)
                .setDataAndType(Uri.parse(uri),mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            await {externalOpeners().isEmpty()}
            assertFalse(main {connection.state.value.playing})
            assertTrue(main {connection.state.value.queue.isEmpty()})
            assertEquals(0,main {fullPlayers().size})
        }
        report.put("external_entrypoint_rejects_unsupported_inputs",true).put("no_preview_activity",true)
    }

    @Test fun internalAudioAfterExternalVideoUsesFullPlayerWithoutAnOldRequest()=runBlocking<Unit> {
        val first=send(files.getValue("m4a"),"audio/mp4")
        await {connection.state.value.current?.uri==first.toString()&&connection.state.value.playing&&externalOpeners().isEmpty()}
        val video=File(home,"video.mp4")
        instrument.context.assets.open("status-original.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
        val movie=send(video,"video/mp4")
        await {fullPlayers().isNotEmpty()&&connection.state.value.current?.uri==movie.toString()&&connection.state.value.current?.video==true&&connection.state.value.playing}
        val local=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.playUri(local.toString(),title=arabicTitle)}
        await {connection.state.value.current?.uri==local.toString()&&connection.state.value.playing&&fullPlayers().any {!it.isFinishing}}
        compose.onNodeWithTag("audio_player").assertIsDisplayed()
        compose.onNodeWithTag("player_title").assertTextEquals(arabicTitle)
        assertEquals(1,main {fullPlayers().count {!it.isFinishing}})
        assertTrue(main {externalOpeners().isEmpty()})
        report.put("video_stays_fullscreen",true).put("old_external_request_not_reused_for_internal_audio",true)
    }

    @Test fun backgroundAudioSurvivesUiTaskRemovalAndScreenLockWithoutOverlay()=runBlocking<Unit> {
        SettingsRepository(app).text("language","en")
        val uri=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.playUri(uri.toString(),title=arabicTitle)}
        await {connection.state.value.playing&&fullPlayers().any {!it.isFinishing}}
        main {connection.player.value!!.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
        val token=activeMediaFlowSessionToken()
        val system=android.media.session.MediaController(app,token)
        compose.onNodeWithTag("player_background").performClick()
        phase="background-dismiss"
        await {fullPlayers().isEmpty()}
        await {system.playbackState?.state==android.media.session.PlaybackState.STATE_PLAYING}
        assertEquals(1,activeMediaFlowSessionCount())
        assertTrue("Background handoff must publish the foreground media notification",
            app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.id==57001})
        underlying.onActivity {it.finishAndRemoveTask()}
        underlying.close()
        instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await {connection.player.value==null}
        phase="background-detached"
        assertFalse(hasFloatingWindow())
        await {system.playbackState?.state==android.media.session.PlaybackState.STATE_PLAYING}
        assertEquals(1,activeMediaFlowSessionCount())
        instrument.uiAutomation.executeShellCommand("am start -a android.settings.SETTINGS").use {descriptor->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}
        }
        delay(1000)
        assertFalse(hasFloatingWindow())
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING,system.playbackState?.state)
        val before=system.playbackState!!.position
        try {
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            delay(1800)
            assertFalse(app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            assertFalse(hasFloatingWindow())
            system.transportControls.pause()
            phase="screen-off-pause"
            await {system.playbackState?.state==android.media.session.PlaybackState.STATE_PAUSED}
            assertTrue("Playback did not advance with UI closed",system.playbackState!!.position>before+500)
            system.transportControls.play()
            phase="screen-off-resume"
            await {system.playbackState?.state==android.media.session.PlaybackState.STATE_PLAYING}
            assertEquals(token,activeMediaFlowSessionToken())
        } finally {
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrument.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        }
        main {connection.open(app)}
        phase="background-return-to-full"
        await {fullPlayers().isNotEmpty()&&connection.state.value.playing}
        assertEquals(token,activeMediaFlowSessionToken())
        assertEquals(1,activeMediaFlowSessionCount())
        assertEquals(uri.toString(),main {connection.state.value.current?.uri})
        clickSystemText("Stop playback")
        phase="background-stop"
        await {!PlaybackRuntime.active}
        assertEquals(0,activeMediaFlowSessionCount())
        await {app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {it.id==57001}}
        report.put("task_removed_without_ui_owner",true).put("screen_off_session_controls",true)
            .put("same_session_on_return",true).put("no_automatic_overlay",true).put("explicit_stop_releases_service",true)
    }

    @Test fun leavingFullPlayerKeepsAudioInMiniPlayerAndFullPlayerCanStopIt()=runBlocking<Unit> {
        val uri=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.playUri(uri.toString(),title=arabicTitle)}
        await {connection.state.value.playing&&fullPlayers().any {!it.isFinishing}}
        main {connection.player.value!!.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
        val token=activeMediaFlowSessionToken()
        compose.onNodeWithTag("player_back").performClick()
        phase="leave-full-player"
        await {fullPlayers().isEmpty()&&connection.state.value.playing}
        compose.onNodeWithTag("mini_player").assertIsDisplayed()
        assertEquals(token,activeMediaFlowSessionToken())
        assertFalse(hasFloatingWindow())
        compose.onNodeWithTag("mini_player").performClick()
        phase="mini-player-open-full"
        await {fullPlayers().isNotEmpty()}
        compose.onNodeWithTag("player_stop").assertIsDisplayed().performClick()
        phase="mini-player-stop"
        await {!PlaybackRuntime.active}
        report.put("mini_player_keeps_playing",true).put("full_player_can_stop",true)
    }

    @Test fun deniedOverlayKeepsAudioInTheExistingNotificationSession()=runBlocking<Unit> {
        assertFalse("Run this case before granting the audit app overlay access",android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        val uri=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.playUri(uri.toString(),title=arabicTitle);connection.attach()}
        try {
            await {connection.state.value.playing&&fullPlayers().any {!it.isFinishing}}
            val controller=main {connection.player.value!!}
            main {controller.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
            assertEquals("The short audio fixture must repeat throughout floating-window interactions",
                androidx.media3.common.Player.REPEAT_MODE_ONE,main {controller.repeatMode})
            compose.onNodeWithTag("player_floating").performClick()
            clickSystemText("Use notification only")
            await {fullPlayers().isEmpty()&&controller.isPlaying}
            assertSame(controller,main {connection.player.value})
            await {app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.id==57001}}
            assertFalse(android.provider.Settings.canDrawOverlays(app))
            assertFalse(hasFloatingWindow())
            report.put("denial_fallback_same_session",true).put("media_notification_present",true)
        } finally {main {connection.detach()}}
    }

    @Test fun floatingWindowControlsTheSameSessionOutsideTheAppAndCleansUp()=runBlocking<Unit> {
        assertTrue("Grant overlay only through the audit app's Android permission screen first",android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        val uri=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.playUri(uri.toString(),title=arabicTitle);connection.attach()}
        try {
            await {connection.state.value.playing&&fullPlayers().any {!it.isFinishing}}
            val controller=main {connection.player.value!!}
            main {controller.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
            compose.onNodeWithTag("player_floating").performClick()
            await {fullPlayers().isEmpty()&&controller.isPlaying}
            assertFalse("No duplicate overlay inside MediaFlow",hasFloatingWindow())
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            withTimeout(10_000) {while(!hasFloatingWindow()) delay(100)}
            captureFloatingSurface("floating-en")
            manualControllerCheckpoint("floating")
            dispatchSystemMediaKey("pause")
            await {!controller.isPlaying&&!controller.playWhenReady}
            dispatchSystemMediaKey("play")
            await {controller.isPlaying}
            clickSystemText("Pause playback")
            await {!controller.isPlaying&&!controller.playWhenReady}
            clickSystemText("Play media")
            await {controller.isPlaying}
            assertSame(controller,main {connection.player.value})
            main {app.startActivity(requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)))}
            withTimeout(10_000) {while(hasFloatingWindow()) delay(100)}
            assertTrue(main {controller.isPlaying})
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            withTimeout(10_000) {while(!hasFloatingWindow()) delay(100)}
            clickSystemText("Close window · keep audio playing")
            await {controller.isPlaying&&controller.mediaItemCount==1}
            withTimeout(10_000) {while(hasFloatingWindow()) delay(100)}
            withContext(Dispatchers.Main) {connection.stopAudio()}
            await {!PlaybackRuntime.active}
            report.put("overlay_outside_app",true).put("overlay_hides_inside_app",true)
                .put("overlay_pause_resume",true).put("overlay_close_keeps_audio",true).put("explicit_stop_releases_service",true).put("same_session",true)
        } finally {main {connection.detach()}}
    }

    @Test fun externalAudioOpensTheSubstantialFloatingCardWhenOverlayAccessIsGranted()=runBlocking<Unit> {
        assertTrue("Grant overlay only through the audit app's Android permission screen first",android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        try {
            underlying.close();underlyingClosed=true
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val longFixture=File(home,"floating-card-long.m4a")
            ffmpeg("-stream_loop","4","-i",files.getValue("m4a").path,"-c","copy",longFixture.path)
            val uri=send(longFixture,"audio/mp4")
            phase="external-audio-playing"
            await(floatingWindow=true) {
                ExternalAudioPreview.player?.let {it.currentMediaItem?.mediaId==uri.toString()&&it.isPlaying}==true&&
                    fullPlayers().isEmpty()
            }
            val preview=main {checkNotNull(ExternalAudioPreview.player)}
            assertEquals("External preview must remain sessionless",0,activeMediaFlowSessionCount())
            assertEquals(0,app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.count {it.id==57001})
            captureFloatingSurface("floating-external-en")
            val nodes=accessibleNodes()
            assertTrue("Card should identify MediaFlow",nodes.any {it.text?.toString()==app.getString(R.string.app_name)})
            assertTrue("Card should expose accessible album artwork",nodes.any {
                it.className=="android.widget.ImageView"&&!it.contentDescription?.toString().isNullOrBlank()
            })
            assertTrue("Card should offer opening the in-app player",nodes.any {
                it.text?.toString()?.contains("MediaFlow",ignoreCase=true)==true||
                    it.contentDescription?.toString()?.contains("MediaFlow",ignoreCase=true)==true
            })
            tapFloatingPlaybackControl()
            await {!preview.playWhenReady}
            tapFloatingPlaybackControl()
            await {preview.isPlaying}

            val seekTarget=main {(preview.duration*.6).toLong()}
            tapFloatingSeek(.6f)
            await {kotlin.math.abs(preview.currentPosition-seekTarget)<1500}

            clickFloatingOpenInApp()
            await(floatingWindow=false) {fullPlayers().isNotEmpty()&&ExternalAudioPreview.player==null&&
                connection.state.value.current?.uri==uri.toString()&&connection.state.value.playing}
            captureScreen("floating-full-player-foreground")
            val token=activeMediaFlowSessionToken()
            val system=android.media.session.MediaController(app,token)
            assertEquals(1,activeMediaFlowSessionCount())
            main {fullPlayers().single().finish()}
            await {fullPlayers().isEmpty()}
            await {system.playbackState?.state==android.media.session.PlaybackState.STATE_PLAYING}
            captureScreen("floating-card-after-player")
            assertFalse("Closing the normal player must not resurrect the external preview",hasFloatingWindow())
            assertEquals(token,activeMediaFlowSessionToken())
            report.put("external_auto_overlay",true).put("overlay_seek",true).put("overlay_open_in_app",true)
                .put("external_preview_sessionless",true).put("closing_full_player_keeps_normal_audio",true)
        } finally {
            main {ExternalAudioPreview.stop()}
            if(main {PlaybackRuntime.active}) withContext(Dispatchers.Main) {connection.stopAudio()}
        }
    }

    @Test fun externalPreviewIsSessionlessReplacesAndStopsOnX()=runBlocking<Unit> {
        assertEquals("Never exercise the production package","com.zdownloader.app.candidate0965",app.packageName)
        assertTrue(android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        try {
            underlying.close();underlyingClosed=true
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val uri=send(files.getValue("mp3"),"audio/mpeg")
            phase="sessionless-external"
            await(floatingWindow=true) {ExternalAudioPreview.player?.let {it.currentMediaItem?.mediaId==uri.toString()&&it.isPlaying&&it.currentPosition>500}==true}
            val first=main {checkNotNull(ExternalAudioPreview.player)}
            assertEquals(0,main {activeMediaFlowSessionCount()})
            assertFalse(main {PlaybackRuntime.active})
            assertTrue(main {fullPlayers().isEmpty()})
            assertEquals(0,app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.count {it.id==57001})
            captureScreen("floating-sessionless-playing")
            tapFloatingPlaybackControl()
            await {!first.isPlaying}
            tapFloatingSeek(.5f)
            await {first.currentPosition in 8000..12000}
            captureScreen("floating-sessionless-paused")
            val pausedAt=main {first.currentPosition}
            tapFloatingPlaybackControl()
            await {first.isPlaying&&first.currentPosition>pausedAt+300}
            val secondUri=send(files.getValue("flac"),"audio/flac")
            await {ExternalAudioPreview.player?.let {it.currentMediaItem?.mediaId==secondUri.toString()&&it.isPlaying}==true}
            assertNotSame(first,main {ExternalAudioPreview.player})
            assertEquals(0,main {activeMediaFlowSessionCount()})
            assertEquals(1,main {externalOpeners().count {!it.isFinishing}})
            captureScreen("floating-sessionless-replacement")
            tapFloatingDismiss()
            await(floatingWindow=false) {ExternalAudioPreview.player==null&&externalOpeners().isEmpty()}
            assertEquals(0,main {activeMediaFlowSessionCount()})
            assertFalse(main {PlaybackRuntime.active})
            captureScreen("floating-sessionless-stopped")
            report.put("external_session_count",0).put("normal_service_started",false)
                .put("pause_seek_resume",true).put("second_file_replaced",true).put("x_released_player",true)
        } finally {main {ExternalAudioPreview.stop()}}
    }

    @Test fun externalPreviewOpenActionTransfersSameFilePositionAndArtwork()=runBlocking<Unit> {
        assertEquals("Never exercise the production package","com.zdownloader.app.candidate0965",app.packageName)
        assertTrue(android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        var attached=false
        try {
            underlying.close();underlyingClosed=true
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val longCover=File(home,"covered-long.mp3")
            ffmpeg("-stream_loop","4","-i",files.getValue("cover").path,"-c","copy",longCover.path)
            val uri=send(longCover,"audio/mpeg")
            phase="sessionless-preview-before-explicit-handoff"
            awaitPreview(timeoutMillis=60_000,floatingWindow=true) {
                ExternalAudioPreview.player?.let {
                    it.currentMediaItem?.mediaId==uri.toString()&&it.isPlaying&&it.currentPosition>500
                }==true
            }
            val preview=main {checkNotNull(ExternalAudioPreview.player)}
            assertEquals(0,activeMediaFlowSessionCount())
            assertFalse(main {PlaybackRuntime.active})
            assertTrue("The localized Open action is visible",accessibleNodes().any {it.text?.toString()=="Open in MediaFlow"})
            main {connection.attach();attached=true}
            assertEquals("Connecting the test observer must not create a normal session",0,activeMediaFlowSessionCount())
            assertFalse(main {PlaybackRuntime.active})
            assertTrue("Embedded title is shown",accessibleNodes().any {it.text?.toString()==arabicTitle})
            assertTrue("Embedded creator is shown",accessibleNodes().any {it.text?.toString()==artist})
            assertTrue("Elapsed and total timestamps are shown",accessibleNodes().any {
                it.text?.toString()?.matches(Regex("\\d+:\\d{2} / \\d+:\\d{2}"))==true
            })
            delay(500)
            val imageNode=accessibleNodes().single {it.contentDescription?.toString()=="Album artwork"}
            val actionNode=accessibleNodes().single {it.text?.toString()=="Open in MediaFlow"}
            val imageBounds=android.graphics.Rect().also(imageNode::getBoundsInScreen)
            val actionBounds=android.graphics.Rect().also(actionNode::getBoundsInScreen)
            val screenshot=checkNotNull(instrument.uiAutomation.takeScreenshot())
            try {
                val rendered=screenshot.getPixel(imageBounds.centerX(),imageBounds.centerY())
                val expected=Color.rgb(40,150,170)
                assertTrue("Embedded cover art must render without the placeholder tint: $rendered",
                    kotlin.math.abs(Color.red(rendered)-Color.red(expected))<55&&
                        kotlin.math.abs(Color.green(rendered)-Color.green(expected))<55&&
                        kotlin.math.abs(Color.blue(rendered)-Color.blue(expected))<55)
                val density=app.resources.displayMetrics.density
                val actionBackground=screenshot.getPixel(actionBounds.left+(8*density).toInt(),actionBounds.centerY())
                val cardBackground=screenshot.getPixel(actionBounds.left+(8*density).toInt(),actionBounds.top-(4*density).toInt())
                assertTrue("Open in MediaFlow is plain text without a colored background: $actionBackground vs $cardBackground",
                    kotlin.math.abs(Color.red(actionBackground)-Color.red(cardBackground))<=8&&
                        kotlin.math.abs(Color.green(actionBackground)-Color.green(cardBackground))<=8&&
                        kotlin.math.abs(Color.blue(actionBackground)-Color.blue(cardBackground))<=8)
            } finally {screenshot.recycle()}

            SettingsRepository(app).text("language","ar")
            awaitPreview {accessibleNodes().any {it.text?.toString()=="فتح في MediaFlow"}}
            SettingsRepository(app).text("language","en")
            awaitPreview {accessibleNodes().any {it.text?.toString()=="Open in MediaFlow"}}
            assertFalse("The complete fixed player has no vertical scrolling",
                accessibleNodes().any {it.packageName==app.packageName&&it.className.toString().contains("ScrollView")})
            captureFloatingSurface("floating-preview-open-action")

            tapFloatingPlaybackControl()
            awaitPreview {!preview.isPlaying}
            // Avoid the thumb's end padding; an edge-adjacent tap can clamp
            // back to the current position instead of exercising a seek.
            tapFloatingSeek(.30f)
            awaitPreview {preview.currentPosition in 15_000..35_000}
            val sourcePosition=main {preview.currentPosition}
            assertFalse(main {preview.isPlaying})
            phase="explicit-preview-to-full-player-handoff"
            clickFloatingOpenInApp()
            phase="await-explicit-preview-handoff"
            awaitPreview(floatingWindow=false) {
                ExternalAudioPreview.player==null&&fullPlayers().any {!it.isFinishing}&&
                    connection.player.value?.let {it.isPlaying&&it.currentMediaItem?.mediaId==uri.toString()}==true
            }
            val normal=main {checkNotNull(connection.player.value)}
            assertEquals(uri.toString(),main {normal.currentMediaItem?.mediaId})
            val transferredPosition=main {normal.currentPosition}
            assertTrue("Handoff preserves the seeked position (source=$sourcePosition target=$transferredPosition)",
                kotlin.math.abs(transferredPosition-sourcePosition)<3500)
            assertFalse("The floating-only preview is no longer audible",main {preview.isPlaying})
            assertEquals("Only the normal MediaSession is active",1,activeMediaFlowSessionCount())
            assertEquals("Normal playback notification is active",1,
                app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.count {it.id==57001})
            assertTrue(main {PlaybackRuntime.active})
            captureScreen("floating-preview-handoff-full-player")
            report.put("preview_open_action_visible",true).put("open_action_arabic_localized",true)
                .put("embedded_artwork_rendered",true).put("title_artist_timestamps_visible",true)
                .put("same_uri_position_handoff",true).put("single_normal_session",true)
                .put("floating_preview_closed_after_handoff",true)

            val beforeHome=main {normal.currentPosition}
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            awaitPreview {normal.isPlaying&&normal.currentPosition>beforeHome+500}
            assertEquals("Background playback keeps the one normal session",1,activeMediaFlowSessionCount())
            assertEquals("Background notification remains attached to normal playback",1,
                app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.count {it.id==57001})
            report.put("normal_background_after_handoff",true)
        } finally {
            withContext(Dispatchers.Main) {
                if(PlaybackRuntime.active) connection.stopAudio()
                if(attached) connection.detach()
            }
        }
    }

    @Test fun normalBackgroundAndExternalPreviewAreIsolated()=runBlocking<Unit> {
        assertEquals("com.zdownloader.app.candidate0965",app.packageName)
        assertTrue(android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        main {connection.attach()}
        try {
        val uri=publish(files.getValue("mp3"),"audio/mpeg")
        main {connection.playUri(uri.toString(),title=englishTitle)}
        await {connection.state.value.playing&&connection.state.value.current?.uri==uri.toString()}
        val normal=main {checkNotNull(connection.player.value)}
        main {normal.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
        val token=activeMediaFlowSessionToken()
        assertFalse(hasFloatingWindow())
        instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        val beforeHome=main {normal.currentPosition}
        await {normal.isPlaying&&normal.currentPosition>beforeHome+500}
        assertEquals(1,activeMediaFlowSessionCount())
        captureScreen("floating-normal-background")
        withExternalMediaController(token) {remote->
            remote.pause();await {!normal.isPlaying}
            remote.play();await {normal.isPlaying}
        }
        try {
            val longPreview=File(home,"isolation-long.m4a")
            ffmpeg("-stream_loop","4","-i",files.getValue("m4a").path,"-c","copy",longPreview.path)
            val previewUri=send(longPreview,"audio/mp4")
            phase="normal-to-preview"
            await(floatingWindow=true) {ExternalAudioPreview.player?.let {it.currentMediaItem?.mediaId==previewUri.toString()&&it.isPlaying}==true}
            assertFalse(main {PlaybackRuntime.active})
            assertEquals(0,activeMediaFlowSessionCount())
            captureScreen("floating-external-after-normal")
            val saved=main {connection.repository.savedQueue().first}
            assertEquals(listOf(uri.toString()),saved.map {it.uri})
            assertTrue(connection.repository.position(uri.toString())>0)
            // Android 15 denies fresh audio focus from a background app without a foreground service.
            // Return through the launcher before requesting playback, as a user would.
            phase="return-to-main"
            app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!)
            fun focusedOwnPlaybackActivity() = visibleActivities().firstOrNull {
                (it is MainActivity||it is PlayerActivity)&&it.hasWindowFocus()&&
                    ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(it)==Stage.RESUMED
            }
            await {focusedOwnPlaybackActivity()!=null}
            // Android may restore the PlayerActivity that was above Home in the task.
            if(main {focusedOwnPlaybackActivity() is PlayerActivity}) {
                assertTrue(instrument.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            }
            await {
                focusedOwnPlaybackActivity() is MainActivity
            }
            main {connection.playUri(uri.toString(),title=englishTitle,open=false)}
            phase="preview-to-normal"
            await(floatingWindow=false) {connection.player.value?.isPlaying==true&&ExternalAudioPreview.player==null}
            val resumed=main {checkNotNull(connection.player.value)}
            assertNotSame(normal,resumed)
            val resumedToken=activeMediaFlowSessionToken()
            main {resumed.pause();resumed.seekTo(5000)}
            await {!resumed.isPlaying&&kotlin.math.abs(resumed.currentPosition-5000)<300}
            main {resumed.play()}
            await {resumed.isPlaying&&resumed.currentPosition>5500}
            assertEquals(1,app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.count {it.id==57001})
            report.put("normal_home_continues",true).put("remote_pause_resume",true)
                .put("normal_queue_preserved_on_mode_exit",true).put("mode_isolation",true).put("normal_seek",true)
            // Lock last: switching modes must not depend on automating a user unlock.
            try {
                val beforeLock=main {resumed.currentPosition}
                instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                await {resumed.isPlaying&&resumed.currentPosition>beforeLock+500&&
                    !app.getSystemService(android.os.PowerManager::class.java).isInteractive}
                assertEquals(resumedToken,activeMediaFlowSessionToken())
                report.put("normal_screen_off_continues",true)
            } finally {
                for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard")) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
                }
            }
        } finally {
            main {ExternalAudioPreview.stop()}
            if(main {connection.player.value?.isConnected==true}) withContext(Dispatchers.Main) {connection.stopAudio()}
        }
        } finally {main {connection.detach()}}
    }

    @Test fun floatingWindowPresentsLongArabicEnglishAndShortTitlesInOneLine()=runBlocking<Unit> {
        assertTrue("Grant overlay only through the audit app's Android permission screen first",android.provider.Settings.canDrawOverlays(app))
        SettingsRepository(app).text("language","en")
        try {
            underlying.close();underlyingClosed=true
            instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val cases=listOf(
                Triple("m4a",arabicTitle,"floating-title-arabic"),
                Triple("m4a-english",englishTitle,"floating-title-english"),
                Triple("m4a-short",shortTitle,"floating-title-short")
            )
            val longCases=cases.map {(key,title,name)->
                val longFixture=File(home,"$name-long.m4a")
                ffmpeg("-stream_loop","4","-i",files.getValue(key).path,"-c","copy",
                    "-metadata","title=$title","-metadata","artist=$artist",longFixture.path)
                Triple(title,name,longFixture)
            }
            for((title,name,fixture) in longCases) {
                phase="external floating title $name"
                val uri=send(fixture,"audio/mp4")
                await(timeoutMillis=60_000,floatingWindow=true) {
                    ExternalAudioPreview.player?.let {
                        it.currentMediaItem?.mediaId==uri.toString()&&it.isPlaying
                    }==true&&fullPlayers().isEmpty()
                }
                assertEquals("External title previews remain sessionless",0,activeMediaFlowSessionCount())
                assertFalse(PlaybackRuntime.active)
                captureFloatingSurface(name)
                val titleNodes=accessibleNodes().filter {
                    it.packageName==app.packageName&&it.className=="android.widget.TextView"
                }
                assertTrue("The full filename/title remains accessible for $name; nodes="+
                    titleNodes.map {it.text?.toString() to it.contentDescription?.toString()},
                    titleNodes.any {it.text?.toString()==title||it.contentDescription?.toString()==title})
                val titleNode=titleNodes.single {
                    it.text?.toString()==title||it.contentDescription?.toString()==title
                }
                val titleBounds=android.graphics.Rect().also(titleNode::getBoundsInScreen)
                fun titlePixels():IntArray {
                    val screen=checkNotNull(instrument.uiAutomation.takeScreenshot())
                    try {
                        return IntArray(titleBounds.width()*titleBounds.height()).also {pixels->
                            screen.getPixels(pixels,0,titleBounds.width(),titleBounds.left,titleBounds.top,
                                titleBounds.width(),titleBounds.height())
                        }
                    } finally {screen.recycle()}
                }
                val before=titlePixels()
                delay(1400)
                val after=titlePixels()
                val changed=before.indices.count {before[it]!=after[it]}
                if(name=="floating-title-short") {
                    assertEquals("A short title must remain stationary",0,changed)
                } else {
                    assertTrue("The long title must visibly move horizontally for $name",changed>0)
                }
                report.put("$name-changed-title-pixels",changed)
                captureFloatingSurface("$name-marquee-later")
                assertFalse("The fixed floating card must not contain a scrolling container",
                    accessibleNodes().any {it.packageName==app.packageName&&it.className.toString().contains("ScrollView")})
                tapFloatingDismiss()
                await(floatingWindow=false) {ExternalAudioPreview.player==null&&externalOpeners().isEmpty()}
                assertEquals("X stops the sessionless preview",0,activeMediaFlowSessionCount())
                assertFalse(PlaybackRuntime.active)
            }
            report.put("arabic_long_title",true).put("english_long_title",true).put("short_title",true)
                .put("title_marquee_screenshots",true).put("no_overlay_scroll_container",true)
                .put("sessionless_preview_titles",true).put("x_stops_each_preview",true)
        } finally {
            main {ExternalAudioPreview.stop()}
        }
    }

    private fun accessibleNodes():List<android.view.accessibility.AccessibilityNodeInfo> {
        val automation=instrument.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun descendants(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(node)+(0 until node.childCount).flatMap {index->node.getChild(index)?.let(::descendants).orEmpty()}
        return automation.windows.flatMap {it.root?.let(::descendants).orEmpty()}
    }
    private fun accessibilityWindowEvidence():JSONArray {
        val windows=runCatching {instrument.uiAutomation.windows}.getOrDefault(emptyList())
        fun containsFloatingTitle(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
            if(node==null)return false
            if(node.contentDescription?.toString()=="Floating audio player")return true
            return (0 until node.childCount).any {
                containsFloatingTitle(runCatching {node.getChild(it)}.getOrNull())
            }
        }
        return JSONArray(windows.map {window->
            val root=runCatching {window.root}.getOrNull()
            JSONObject().put("type",runCatching {window.type}.getOrNull())
                .put("active",runCatching {window.isActive}.getOrDefault(false))
                .put("focused",runCatching {window.isFocused}.getOrDefault(false))
                .put("root_package",runCatching {root?.packageName?.toString()}.getOrNull())
                .put("root_class",runCatching {root?.className?.toString()}.getOrNull())
                .put("floating_title_accessible",containsFloatingTitle(root))
        })
    }
    private fun hasFloatingWindow()=accessibleNodes().any {
        it.packageName==app.packageName&&it.className?.toString()=="android.widget.LinearLayout"&&
            it.contentDescription?.toString()=="Floating audio player"
    }
    private fun captureFloatingSurface(name:String) {
        val panel=accessibleNodes().single {it.packageName==app.packageName&&it.contentDescription?.toString()=="Floating audio player"}
        val bounds=android.graphics.Rect();panel.getBoundsInScreen(bounds)
        val density=app.resources.displayMetrics.density
        assertTrue("Overlay must be a substantial rectangular card",
            bounds.width()/density in 280f..340f&&bounds.height()/density in 260f..340f)
        val screen=checkNotNull(instrument.uiAutomation.takeScreenshot())
        val crop=Bitmap.createBitmap(screen,bounds.left,bounds.top,bounds.width(),bounds.height())
        try {
            File(home,"$name.png").outputStream().use {assertTrue(crop.compress(Bitmap.CompressFormat.PNG,100,it))}
            persistReviewScreenshot(name,crop)
        } finally {crop.recycle();screen.recycle()}
        report.put("overlay_width_dp",bounds.width()/density).put("overlay_height_dp",bounds.height()/density)
    }
    private fun persistReviewScreenshot(name:String,bitmap:Bitmap) {
        if(android.os.Build.VERSION.SDK_INT<29) return
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "compact-audio"
        require(run.matches(Regex("[A-Za-z0-9-]{1,100}")))
        val relativePath="${android.os.Environment.DIRECTORY_PICTURES}/MediaFlowAudit/$run"
        val displayName="$name.png"
        val resolver=app.contentResolver
        val selection="${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val existing=resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID),
            selection,arrayOf("$relativePath%","$displayName"),null)?.use {it.moveToFirst()} ?: false
        check(!existing) {"Refusing to overwrite existing review screenshot $displayName"}
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,displayName)
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,relativePath)
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }) ?: error("Unable to create a review screenshot")
        try {
            val output=resolver.openOutputStream(uri) ?: error("Unable to open the review screenshot")
            output.use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            check(resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1)
        } catch(error:Throwable) {
            resolver.delete(uri,null,null)
            throw error
        }
    }
    private fun captureScreen(name:String) {
        val screen=checkNotNull(instrument.uiAutomation.takeScreenshot())
        File(home,"$name.png").outputStream().use {assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,it))}
        screen.recycle()
    }
    private suspend fun clickSystemText(label:String) {
        phase="click-$label"
        withTimeout(10_000) {
            while(true) {
                val node=accessibleNodes().firstOrNull {(it.text?.toString()?.equals(label,ignoreCase=true)==true||it.contentDescription?.toString()==label)&&it.isClickable}
                if(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout
                delay(100)
            }
        }
    }
    private suspend fun clickFloatingOpenInApp() {
        phase="click-open-in-app"
        val action=accessibleNodes().single {
            it.packageName==app.packageName&&it.text?.toString()=="Open in MediaFlow"&&it.isClickable
        }
        check(action.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            "Open in MediaFlow accessibility action was rejected"
        }
    }
    private fun tapFloatingSeek(fraction:Float) {
        require(fraction in 0f..1f)
        tapFloatingPoint(84f+204f*fraction,174f)
    }
    private fun tapFloatingPlaybackControl()=tapFloatingPoint(40f,174f)
    private fun tapFloatingDismiss()=tapFloatingPoint(282f,34f)
    private fun tapFloatingPoint(xDp:Float,yDp:Float) {
        val panel=accessibleNodes().single {
            it.packageName==app.packageName&&it.contentDescription?.toString()=="Floating audio player"
        }
        val bounds=android.graphics.Rect().also(panel::getBoundsInScreen)
        val density=app.resources.displayMetrics.density
        val x=bounds.left+(xDp*density).toInt()
        val y=bounds.top+(yDp*density).toInt()
        val command=instrument.uiAutomation.executeShellCommand("input tap $x $y")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(command).use {it.readBytes()}
    }

    private fun activeMediaFlowSessionCount():Int {
        instrument.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {app.getSystemService(android.media.session.MediaSessionManager::class.java)
            .getActiveSessions(null).count {it.packageName==app.packageName}}
        finally {instrument.uiAutomation.dropShellPermissionIdentity()}
    }
    private fun activeMediaFlowSessionToken():android.media.session.MediaSession.Token {
        instrument.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        return try {app.getSystemService(android.media.session.MediaSessionManager::class.java)
            .getActiveSessions(null).single {it.packageName==app.packageName}.sessionToken}
        finally {instrument.uiAutomation.dropShellPermissionIdentity()}
    }
    private fun dispatchSystemMediaKey(key:String) {
        require(key in setOf("play","pause","play-pause","next","previous"))
        val descriptor=instrument.uiAutomation.executeShellCommand("cmd media_session dispatch $key")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}
    }
    private fun manualStageRequested(stage:String)=InstrumentationRegistry.getArguments().getString("mediaflow_manual_stages")
        .orEmpty().split(',').map(String::trim).any {it==stage}
    private suspend fun manualControllerCheckpoint(stage:String) {
        if(!manualStageRequested(stage)) return
        val cache=app.cacheDir
        check(cache.isDirectory&&cache.canWrite()) {"Audit cache is not writable for manual checkpoint: ${cache.absolutePath}"}
        val ready=File(cache,"mediaflow-manual-controller-ready-$stage")
        val resume=File(cache,"mediaflow-manual-controller-continue-$stage")
        ready.delete();resume.delete()
        ready.writeText("ready")
        report.put("manual_controller_$stage","waiting")
        try {
            withTimeout(5*60_000L) {while(!resume.exists()) delay(200)}
            report.put("manual_controller_$stage","continued")
        } finally {ready.delete();resume.delete()}
    }
    private inner class ExternalMediaControllerProbe {
        private val resolver=instrument.context.contentResolver
        private val authority=Uri.parse("content://${instrument.context.packageName}.external-media-controller")
        private fun call(method:String,arg:String?=null,extras:android.os.Bundle?=null)=checkNotNull(resolver.call(authority,method,arg,extras))
        fun uid()=call("uid").getInt("uid")
        suspend fun connect(token:android.media.session.MediaSession.Token)=withContext(Dispatchers.IO) {
            call("connect",extras=android.os.Bundle().apply {putParcelable("session_token",token)}).getBoolean("connected")
        }
        suspend fun pause()=withContext(Dispatchers.IO) {call("pause")}
        suspend fun play()=withContext(Dispatchers.IO) {call("play")}
        suspend fun release()=withContext(Dispatchers.IO) {call("release")}
    }
    private suspend fun withExternalMediaController(token:android.media.session.MediaSession.Token,block:suspend (ExternalMediaControllerProbe)->Unit) {
        val controller=ExternalMediaControllerProbe()
        try {
            assertNotEquals("The wearable probe must use a distinct app UID",app.applicationInfo.uid,controller.uid())
            assertTrue("Platform MediaController connects from the separate test APK UID",controller.connect(token))
            block(controller)
        } finally {
            controller.release()
        }
    }
    @Test fun systemMediaKeysControlOneExistingSessionForAudioQueueAndVideo()=runBlocking<Unit> {
        assertEquals("No stale MediaFlow media session before the fixture",0,activeMediaFlowSessionCount())
        SettingsRepository(app).text("language","en")
        val first=publish(files.getValue("m4a"),"audio/mp4")
        val second=publish(files.getValue("m4a"),"audio/mp4")
        main {connection.play(listOf(
            PlaybackMedia(first.toString(),"Media-control audio one",false,"audio/mp4"),
            PlaybackMedia(second.toString(),"Media-control audio two",false,"audio/mp4")),open=true)}
        compose.waitUntil(25_000) {connection.state.value.current?.uri==first.toString()&&connection.state.value.playing}
        assertEquals("Exactly one service session for app playback",1,activeMediaFlowSessionCount())
        val player=main {connection.player.value!!}
        val audioRepeatMode=main {player.repeatMode}
        if(manualStageRequested("audio")) main {player.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
        assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.id==57001&&it.notification.actions.isNotEmpty()})
        manualControllerCheckpoint("audio")
        if(manualStageRequested("audio")) main {player.repeatMode=audioRepeatMode}
        withExternalMediaController(activeMediaFlowSessionToken()) {remote->
        remote.pause()
        compose.waitUntil(10_000) {!connection.state.value.playing&&connection.state.value.current?.uri==first.toString()}
        remote.play()
        compose.waitUntil(10_000) {connection.state.value.playing&&connection.state.value.current?.uri==first.toString()}
        dispatchSystemMediaKey("pause")
        compose.waitUntil(10_000) {!connection.state.value.playing&&connection.state.value.current?.uri==first.toString()}
        dispatchSystemMediaKey("play")
        compose.waitUntil(10_000) {connection.state.value.playing&&connection.state.value.current?.uri==first.toString()}
        dispatchSystemMediaKey("play-pause")
        compose.waitUntil(10_000) {!connection.state.value.playing&&connection.state.value.current?.uri==first.toString()}
        dispatchSystemMediaKey("play-pause")
        compose.waitUntil(10_000) {connection.state.value.playing&&connection.state.value.current?.uri==first.toString()}
        dispatchSystemMediaKey("next")
        compose.waitUntil(10_000) {connection.state.value.current?.uri==second.toString()&&connection.state.value.playing}
        dispatchSystemMediaKey("previous")
        compose.waitUntil(10_000) {connection.state.value.current?.uri==first.toString()&&connection.state.value.playing}

        val video=File(home,"system-media-control.mp4")
        instrument.context.assets.open("status-original.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
        val movie=publish(video,"video/mp4")
        val videoRepeatMode=main {player.repeatMode}
        if(manualStageRequested("video")) main {player.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
        main {connection.playUri(movie.toString(),title="Media-control video",video=true)}
        compose.waitUntil(25_000) {connection.state.value.current?.uri==movie.toString()&&connection.state.value.playing}
        await {fullPlayers().isNotEmpty()}
        assertSame("Video handoff keeps the same MediaController",player,main {connection.player.value})
        assertEquals("Video uses the same single Media3 session",1,activeMediaFlowSessionCount())
        manualControllerCheckpoint("video")
        if(manualStageRequested("video")) main {player.repeatMode=videoRepeatMode}
        remote.pause()
        compose.waitUntil(10_000) {!connection.state.value.playing&&connection.state.value.current?.uri==movie.toString()}
        remote.play()
        compose.waitUntil(10_000) {connection.state.value.playing&&connection.state.value.current?.uri==movie.toString()}
        dispatchSystemMediaKey("pause")
        compose.waitUntil(10_000) {!connection.state.value.playing&&connection.state.value.current?.uri==movie.toString()}
        dispatchSystemMediaKey("play")
        compose.waitUntil(10_000) {connection.state.value.playing&&connection.state.value.current?.uri==movie.toString()}
        dispatchSystemMediaKey("play-pause")
        compose.waitUntil(10_000) {!connection.state.value.playing&&connection.state.value.current?.uri==movie.toString()}
        dispatchSystemMediaKey("play-pause")
        compose.waitUntil(10_000) {connection.state.value.playing&&connection.state.value.current?.uri==movie.toString()}
        report.put("active_sessions_audio",1).put("active_sessions_video",1).put("system_pause_play_audio",true)
            .put("system_play_pause_toggle_audio",true).put("system_next_previous",true)
            .put("system_pause_play_video",true).put("system_play_pause_toggle_video",true)
            .put("untrusted_controller_audio_video",true).put("notification_controls_present",true)
        }
    }

    private fun send(file:File,mime:String,slow:Boolean=false):Uri {
        val name=(if(slow) "slow-qa-" else "compact-qa-")+UUID.randomUUID()+".${file.extension}"
        expectedUri=Uri.parse("content://$testPackage.external-audio/$name")
        assertEquals("External tests must target only the isolated candidate","com.zdownloader.app.candidate0965",app.packageName)
        val resolved=app.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW).setDataAndType(expectedUri,mime).setPackage(app.packageName),0)
        assertEquals(app.packageName,resolved?.activityInfo?.packageName)
        assertEquals(if(mime.startsWith("audio/")) "com.zdownloader.app.playback.AudioPopupActivity" else "com.zdownloader.app.playback.PlayerActivity",resolved?.activityInfo?.name)
        // A file-manager Intent carries a grant, not the file bytes in Binder.
        val source=publish(file,mime)
        app.startActivity(externalOriginIntent().setData(source).apply {clipData=ClipData.newRawUri("Owned audio fixture",source)}
            .putExtra("name",name).putExtra("mime",mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return checkNotNull(expectedUri)
    }

    private fun publish(file:File,mime:String):Uri {
        val name="compact-audio-${UUID.randomUUID()}.${file.extension}"
        val video=mime.startsWith("video/")
        val collection=if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri=checkNotNull(app.contentResolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,if(video) "Movies/MediaFlow" else "Music/MediaFlow");put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        publications+=uri
        app.contentResolver.openOutputStream(uri)!!.use {output->file.inputStream().use {it.copyTo(output)}}
        check(app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1)
        return uri
    }

    private fun fixtures():Map<String,File> {
        val pcm=File(home,"tone.pcm");val samples=ByteBuffer.allocate(48000*20*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000*20) {samples.putShort((kotlin.math.sin(2*Math.PI*440*it/48000)*1600).toInt().toShort())}
        pcm.writeBytes(samples.array())
        val wav=File(home,"tone.wav");val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(samples.capacity()+36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(48000).putInt(96000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.capacity())
        wav.outputStream().use {it.write(header.array());it.write(samples.array())}
        val mp3=File(home,"tone.mp3");assertEquals(0,LameEncoder.encodeEx(pcm.path,mp3.path,128,48000,1,AtomicBoolean(false)))
        val m4a=File(home,"tone.m4a");ffmpeg("-i",wav.path,"-c:a","aac","-metadata","title=$arabicTitle","-metadata","artist=$artist",m4a.path)
        val english=File(home,"tone-english.m4a")
        if(!english.exists()) ffmpeg("-i",m4a.path,"-c","copy","-metadata","title=$englishTitle",english.path)
        val short=File(home,"tone-short.m4a")
        if(!short.exists()) ffmpeg("-i",m4a.path,"-c","copy","-metadata","title=$shortTitle",short.path)
        val flac=File(home,"tone.flac");ffmpeg("-i",wav.path,"-c:a","flac",flac.path)
        val shortWav=File(home,"external.wav");ffmpeg("-i",wav.path,"-t","6","-c:a","copy",shortWav.path)
        val cover=File(home,"cover.jpg");val bitmap=Bitmap.createBitmap(240,180,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(40,150,170));cover.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
        val tagged=File(home,"covered.mp3");ffmpeg("-i",mp3.path,"-i",cover.path,"-map","0:a","-map","1:v","-c","copy","-id3v2_version","3",
            "-disposition:v:0","attached_pic","-metadata","title=$arabicTitle","-metadata","artist=$artist",tagged.path)
        return mapOf("mp3" to mp3,"m4a" to m4a,"m4a-english" to english,"m4a-short" to short,
            "wav" to shortWav,"flac" to flac,"cover" to tagged)
    }

    private fun ffmpeg(vararg arguments:String) {
        val session=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error",*arguments))
        assertTrue(session.allLogsAsString,ReturnCode.isSuccess(session.returnCode))
    }

    @After fun restoreOnlyOwnedFixturesAndThePreviousSessionPreferences()=runBlocking<Unit> {
        if(!ownsSession) return@runBlocking
        val failures=mutableListOf<Throwable>()
        suspend fun step(block:suspend ()->Unit) {try {block()} catch(error:Throwable) {failures+=error}}
        step {main {ExternalAudioPreview.stop();connection.clear();fullPlayers().forEach {it.finish()}}}
        // Close the underlying lifecycle owner before disconnecting; returning
        // from a full video otherwise legitimately reattaches its controller.
        step {if(::underlying.isInitialized&&!underlyingClosed) underlying.close()}
        step {main {connection.clear();connection.disconnect();app.stopService(Intent(app,MediaPlaybackService::class.java))};Unit}
        step {withTimeout(10_000) {while(main {PlaybackRuntime.active}) delay(50)}}
        if(!main {PlaybackRuntime.active}) {
            step {
                if(::previousSettings.isInitialized) {
                    val settings=SettingsRepository(app);settings.text("language",previousSettings.language);settings.text("theme",previousSettings.theme)
                }
                val preferences=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE)
                val editor=preferences.edit().clear()
                for((key,value) in previousPlayback) when(value) {
                    is String->editor.putString(key,value);is Long->editor.putLong(key,value);is Int->editor.putInt(key,value)
                    is Float->editor.putFloat(key,value);is Boolean->editor.putBoolean(key,value)
                    is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet())
                }
                assertTrue(editor.commit());assertEquals(previousPlayback,preferences.all)
                report.put("playback_preferences_restored",true)
            }
            step {for(uri in publications) {
                val name=app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use {check(it.moveToFirst());it.getString(0)}
                check(name.startsWith("compact-audio-"));check(app.contentResolver.delete(uri,null,null)==1)
            }}
        }
        if(::home.isInitialized) {
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "compact-audio"
            require(run.matches(Regex("[A-Za-z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {check(isDirectory||mkdirs())}
            File(evidence,"compact-${home.name}.json").writeText(report.toString(2))
            for(image in home.listFiles().orEmpty().filter {(it.name.startsWith("popup-")||it.name.startsWith("floating-"))&&it.extension=="png"})
                image.copyTo(File(evidence,"${home.name}-${image.name}"))
            if(failures.isEmpty()) check(home.deleteRecursively())
        }
        if(failures.isNotEmpty()) throw AssertionError("Owned audio fixture cleanup failed; private preference snapshots retained").apply {failures.forEach(::addSuppressed)}
    }
}
