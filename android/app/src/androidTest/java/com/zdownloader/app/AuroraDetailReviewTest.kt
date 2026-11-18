package com.zdownloader.app

import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.playback.PlayerActivity
import com.zdownloader.app.ui.QuickShareSheet
import com.zdownloader.app.ui.QuickShareViewModel
import com.zdownloader.app.ui.ZRoot
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AuroraDetailReviewTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","aurora")}").apply {mkdirs()}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(400)
        val bitmap=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
    private fun await(tag:String) {
        try {compose.waitUntil(25000) {runCatching {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}}
        catch(error:Throwable) {
            val state=app.playback.state.value
            File(folder,"await-$tag-failure.json").writeText(org.json.JSONObject().put("wanted_tag",tag).put("diagnostic",app.playback.diagnostic).put("current_uri",state.current?.uri)
                .put("playing",state.playing).put("ready",state.ready).put("error",state.error).put("queue_size",state.queue.size).put("audio_only",state.extras.getBoolean("audio_only")).toString(2))
            throw error
        }
    }
    private fun back() {instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);compose.waitForIdle()}

    @Test fun videoCloseCannotResumeAsAudioAndAudioOnlyRequiresAnExplicitChoice():Unit=runBlocking {
        val prefs=SettingsRepository(app);val original=prefs.current()
        val connection=app.playback
        val playbackPrefs=app.getSharedPreferences("mediaflow_playback",0);val savedPrefs=playbackPrefs.all.toMap()
        val notifications=app.getSystemService(android.app.NotificationManager::class.java)
        var owned:Uri?=null
        prefs.text("language","en");prefs.text("theme","dark")
        ActivityScenario.launch(MainActivity::class.java).use {
            val player=withContext(Dispatchers.Main) {connection.attach();connection.connect()}
            val previous=withContext(Dispatchers.Main) {(0 until player.mediaItemCount).map {index->com.zdownloader.app.playback.PlaybackMedia.from(player.getMediaItemAt(index)).item()}}
            val previousIndex=withContext(Dispatchers.Main) {player.currentMediaItemIndex.coerceAtLeast(0)}
            val position=withContext(Dispatchers.Main) {player.currentPosition};val playing=withContext(Dispatchers.Main) {player.playWhenReady};val volume=withContext(Dispatchers.Main) {player.volume}
            try {
                connection.repository.setBoolean("auto_rotate",false)
                // A stale preference must never opt another video into background audio.
                connection.repository.setBoolean("background_video",true)
                withContext(Dispatchers.Main) {player.volume=0f;player.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ONE}
                val uri=checkNotNull(app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,"MediaFlow explicit playback mode review.mp4");put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/MediaFlow/Mode review");put(MediaStore.MediaColumns.IS_PENDING,1)
                }));owned=uri
                instrument.context.assets.open("status-original.mp4").use {input->app.contentResolver.openOutputStream(uri)!!.use {input.copyTo(it)}}
                app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                val media=com.zdownloader.app.playback.PlaybackMedia(uri.toString(),"Video mode review",true,"video/mp4")
                suspend fun startVideo() {
                    withContext(Dispatchers.Main) {connection.play(listOf(media))}
                    await("video_player")
                    withTimeout(25000) {while(!connection.state.value.playing||connection.state.value.extras.getBoolean("audio_only")) delay(100)}
                }
                startVideo()
                compose.onNodeWithContentDescription("Back").performClick()
                withTimeout(15000) {while(connection.state.value.current!=null||notifications.activeNotifications.any {it.id==57001}) delay(100)}
                withContext(Dispatchers.Main) {player.play()}
                delay(700)
                assertFalse("A closed video cannot resume through a retained notification controller",connection.state.value.playing)
                assertNull(connection.state.value.current)
                startVideo()
                compose.onNodeWithTag("video_options").performClick()
                compose.onNodeWithTag("video_play_audio_only").performClick()
                await("mini_player")
                withTimeout(15000) {while(!connection.state.value.playing||!connection.state.value.extras.getBoolean("audio_only")||notifications.activeNotifications.none {it.id==57001}) delay(100)}
                assertTrue(withContext(Dispatchers.Main) {player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)})
                val before=connection.state.value.position
                fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
                shell("input keyevent KEYCODE_SLEEP");delay(2500)
                assertTrue("Explicit audio mode continues with the display asleep",connection.state.value.playing)
                assertTrue(connection.state.value.position!=before)
                shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
                notifications.activeNotifications.first {it.id==57001}.notification.contentIntent.send()
                await("audio_player")
                assertTrue("Opening the notification must retain the explicit audio-only mode",connection.state.value.extras.getBoolean("audio_only"))
                capture("explicit-video-as-audio")
                compose.onNodeWithContentDescription("Back").performClick()
                startVideo()
                assertFalse("A new Play Video request resets audio-only even for the same URI",connection.state.value.extras.getBoolean("audio_only"))
                assertFalse(withContext(Dispatchers.Main) {player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)})
                compose.onNodeWithContentDescription("Back").performClick()
                withTimeout(15000) {while(connection.state.value.current!=null||notifications.activeNotifications.any {it.id==57001}) delay(100)}
                File(folder,"video-audio-mode-separation.json").writeText(org.json.JSONObject().put("normal_video_close_clears_session",true).put("closed_notification_cannot_resume_audio",true)
                    .put("legacy_background_preference_ignored",true).put("explicit_audio_only_survives_screen_off",true).put("notification_reopens_audio_player",true).put("same_uri_play_video_resets_audio_mode",true).toString(2))
            } finally {
                instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                withContext(Dispatchers.Main) {
                    listOf(Stage.RESUMED,Stage.PAUSED,Stage.STOPPED).flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<PlayerActivity>().forEach {it.finish()}
                    if(previous.isEmpty()) player.clearMediaItems() else {player.setMediaItems(previous,previousIndex,position.coerceAtLeast(0));player.prepare()};player.playWhenReady=playing;player.volume=volume
                    connection.detach()
                }
                delay(600)
                owned?.let {app.contentResolver.delete(it,null,null)}
                val edit=playbackPrefs.edit().clear()
                savedPrefs.forEach {(key,value)->when(value) {is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)}};edit.commit()
                prefs.text("language",original.language);prefs.text("theme",original.theme)
            }
        }
    }

    @Test fun actualQualitySheetLightThemeLargeTextAndLandscape():Unit=runBlocking {
        val prefs=SettingsRepository(app);val original=prefs.current()
        try {
            prefs.text("language","en");prefs.text("theme","dark")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                await("url_input")
                compose.onNodeWithTag("url_input").performScrollTo().performTextInput("https://www.w3schools.com/html/mov_bbb.mp4")
                compose.onNodeWithTag("url_input").performImeAction()
                compose.waitUntil(100000) {runCatching {compose.onAllNodesWithTag("choose_format").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
                compose.onNodeWithTag("choose_format").performScrollTo();capture("en-quality-preview")
                compose.onNodeWithTag("choose_format").performClick();await("format_sheet");capture("en-quality-sheet")
                compose.onNodeWithTag("format_confirm").performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithTag("format_sheet").fetchSemanticsNodes().isEmpty()}
                compose.onNodeWithContentDescription("Back").performClick()
                scenario.onActivity {ViewModelProvider(it)[ZViewModel::class.java].input("")}
                await("home_list")
                prefs.text("theme","light")
                compose.onNodeWithTag("home_list").performScrollToIndex(0);capture("en-home-light")
                prefs.text("theme","dark");prefs.text("language","ar")
                scenario.onActivity {activity->
                    val model=ViewModelProvider(activity)[ZViewModel::class.java]
                    activity.setContent {
                        val density=LocalDensity.current;val config=Configuration(LocalConfiguration.current).apply {fontScale=1.7f}
                        CompositionLocalProvider(LocalDensity provides Density(density.density,1.7f),LocalConfiguration provides config) {ZRoot(model)}
                    }
                }
                await("home_list");capture("ar-home-font170")
                compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected();capture("ar-downloads-font170")
            }
            prefs.text("language","ar")
            ActivityScenario.launch<ShareReceiverActivity>(Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4?aurora-detail=1").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
                scenario.onActivity {activity->
                    val model=ViewModelProvider(activity)[QuickShareViewModel::class.java]
                    activity.setContent {
                        val density=LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density,1.7f)) {QuickShareSheet(model) {activity.finish()}}
                    }
                }
                await("share_preference_720")
                compose.onNodeWithTag("share_preference_720").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("share_download").assertIsDisplayed().assertIsEnabled();capture("ar-quick-share-font170")
            }
            prefs.text("language","en")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                compose.waitUntil(20000) {app.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
                await("nav_home");capture("en-home-landscape")
                compose.onNodeWithTag("nav_downloads").performClick();capture("en-downloads-landscape")
                scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
            }
        } finally {prefs.text("language",original.language);prefs.text("theme",original.theme);prefs.integer("preferred_height",original.preferredHeight)}
    }

    @Test fun realLocalAudioVideoQueueAndMiniPlayer():Unit=runBlocking {
        val prefs=SettingsRepository(app);val original=prefs.current()
        val connection=app.playback
        val owned=mutableListOf<Uri>()
        val playbackPrefs=app.getSharedPreferences("mediaflow_playback",0)
        val savedPrefs=playbackPrefs.all.toMap()
        fun publish(name:String,mime:String,video:Boolean,bytes:ByteArray):Uri {
            val uri=checkNotNull(app.contentResolver.insert(if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,if(video) "Movies/MediaFlow/Aurora review" else "Music/MediaFlow/Aurora review");put(MediaStore.MediaColumns.IS_PENDING,1)
            }))
            owned+=uri
            app.contentResolver.openOutputStream(uri)!!.use {it.write(bytes)}
            app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            return uri
        }
        prefs.text("theme","dark");prefs.text("language","en")
        ActivityScenario.launch(MainActivity::class.java).use {
            val player=withContext(Dispatchers.Main) {connection.attach();connection.connect()}
            val previous=withContext(Dispatchers.Main) {(0 until player.mediaItemCount).map {index->com.zdownloader.app.playback.PlaybackMedia.from(player.getMediaItemAt(index)).item()}}
            val previousIndex=withContext(Dispatchers.Main) {player.currentMediaItemIndex.coerceAtLeast(0)}
            val position=withContext(Dispatchers.Main) {player.currentPosition}
            val playing=withContext(Dispatchers.Main) {player.playWhenReady}
            val volume=withContext(Dispatchers.Main) {player.volume}
            try {
                withContext(Dispatchers.Main) {player.volume=0f}
                connection.repository.setBoolean("auto_rotate",false)
                val pcm=ByteArray(44100*16*2)
                val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(pcm.size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size)}.array()
                val audio=publish("Aurora — Quiet moments.wav","audio/wav",false,header+pcm)
                val video=publish("Aurora — A little escape.mp4","video/mp4",true,instrument.context.assets.open("status-original.mp4").use {it.readBytes()})
                val queue=listOf(com.zdownloader.app.playback.PlaybackMedia(audio.toString(),"Quiet moments",false),com.zdownloader.app.playback.PlaybackMedia(video.toString(),"A little escape",true))
                withContext(Dispatchers.Main) {connection.play(queue)}
                await("audio_player")
                withTimeout(25000) {while(!connection.state.value.ready) delay(100)}
                withContext(Dispatchers.Main) {connection.pause()};capture("en-music-player")
                compose.onNodeWithTag("player_seek").performTouchInput {click(percentOffset(.6f,.5f))}
                withTimeout(10000) {while(connection.state.value.position<7000) delay(100)}
                compose.onNodeWithTag("player_queue").performClick();capture("en-playback-queue");back()
                prefs.text("language","ar");capture("ar-music-player")
                back();await("nav_home");compose.onNodeWithTag("mini_player").assertIsDisplayed();capture("ar-mini-player")
                prefs.text("language","en")
                withContext(Dispatchers.Main) {connection.play(queue,1)}
                await("video_player")
                withTimeout(25000) {while(connection.state.value.current?.uri!=video.toString()||!connection.state.value.ready) delay(100)}
                withContext(Dispatchers.Main) {connection.pause()}
                compose.onNodeWithTag("video_player").performSemanticsAction(SemanticsActions.OnClick) {action->action()}
                capture("en-video-player")
                assertNull(connection.state.value.error)
                back()
            } finally {
                withContext(Dispatchers.Main) {
                    listOf(Stage.RESUMED,Stage.PAUSED,Stage.STOPPED).flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<PlayerActivity>().forEach {it.finish()}
                    if(previous.isEmpty()) player.clearMediaItems() else {player.setMediaItems(previous,previousIndex,position.coerceAtLeast(0));player.prepare()};player.playWhenReady=playing;player.volume=volume
                    connection.detach()
                }
                delay(600)
                owned.forEach {uri->app.contentResolver.delete(uri,null,null)}
                val edit=playbackPrefs.edit().clear()
                savedPrefs.forEach {(key,value)->when(value) {is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)}}
                edit.commit()
                prefs.text("language",original.language);prefs.text("theme",original.theme)
            }
        }
    }
}
