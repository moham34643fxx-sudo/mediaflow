package com.zdownloader.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.media3.common.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
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

/** Isolated local fixtures; actual service, MediaController, decoder and Android UI. */
class PlaybackIntegrationTest {
    @get:Rule val compose=createEmptyComposeRule()
    @get:Rule val testName=org.junit.rules.TestName()
    private val startupSamples=JSONArray()
    private lateinit var rootActivity:MainActivity
    private var taskRemoved=false
    private var phase="initial"
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val connection get()=app.playback
    private val outputs=mutableListOf<Uri>()
    private val downloads=mutableListOf<String>()
    private val playlistIds=mutableListOf<String>()
    private val settings get()=SettingsRepository(app)
    private lateinit var original:com.zdownloader.app.data.Settings
    private var playbackPrefs:Map<String,*> = emptyMap<String,Any>()
    private val evidence get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    private val directory get()=File(app.cacheDir,"player-verification").apply {mkdirs()}
    private fun shell(value:String)=ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(value)).bufferedReader().use {it.readText().trim()}
    private suspend fun <T> main(block:()->T):T=withContext(Dispatchers.Main) {block()}
    private suspend fun waitFor(timeout:Long=20000,condition:()->Boolean) {
        try {withTimeout(timeout) {while(!main(condition)) delay(50)}}
        catch(error:TimeoutCancellationException) {
            val diagnostic=main {JSONObject().put("phase",phase).put("step",connection.diagnostic).put("error",connection.state.value.error).put("queue_items",connection.state.value.queue.size).put("ready",connection.state.value.ready).put("playing",connection.state.value.playing).put("position",connection.state.value.position).put("service_active",PlaybackRuntime.active).put("tracks",JSONArray().apply {connection.state.value.tracks.groups.forEach {group->for(i in 0 until group.length) put(JSONObject().put("type",group.type).put("id",group.getTrackFormat(i).id).put("label",group.getTrackFormat(i).label).put("selected",group.isTrackSelected(i)))}})}
               .put("current",connection.state.value.current?.let {media->JSONObject().put("video",media.video).put("width",media.videoWidth).put("height",media.videoHeight)
                   .put("pixel_ratio",media.pixelWidthHeightRatio).put("rotation",media.rotationDegrees).put("display_aspect",media.displayAspect)})
               .put("requested_orientation",playerActivity()?.requestedOrientation).put("configuration_orientation",playerActivity()?.resources?.configuration?.orientation)
              .put("repeat",connection.state.value.repeat).put("duration",connection.state.value.duration).put("connected",connection.player.value!=null).put("pip",playerActivity()?.isInPictureInPictureMode)
              .apply {
                  connection.player.value?.let {player->
                      put("controller_state",player.playbackState)
                      put("play_when_ready",player.playWhenReady)
                      put("suppression_reason",player.playbackSuppressionReason)
                      put("player_error_code",player.playerError?.errorCode)
                  }
              }
             File(evidence,"player-timeout.json").writeText(diagnostic.toString(2));capture("timeout")
            throw AssertionError("Player condition timed out: $diagnostic",error)
        }
    }
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(450)
        val image=instrument.uiAutomation.takeScreenshot()!!
        File(evidence,"player-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun playerActivity():PlayerActivity? {
        fun current()=listOf(Stage.RESUMED,Stage.PAUSED,Stage.STARTED).flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<PlayerActivity>().firstOrNull()
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) return current()
        var activity:PlayerActivity?=null;instrument.runOnMainSync {activity=current()}
        return activity
    }
    @Before fun setup()=runBlocking {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        original=settings.current();settings.text("language","en");settings.text("theme","dark")
        playbackPrefs=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE).all.toMap()
        taskRemoved=false
        rootActivity=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrument.runOnMainSync {rootActivity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        // The instrumentation observes the controller after HOME/screen-off too.
        // Keep its own owner instead of borrowing Activity owners which correctly
        // detach in the background and release their controller after 500 ms.
        main {connection.attach()}
        Unit
    }
    @After fun cleanup()=runBlocking {
        main {connection.sleep(0);connection.clear();playerActivity()?.finish();connection.detach();connection.disconnect();app.stopService(Intent(app,MediaPlaybackService::class.java))}
        withTimeoutOrNull(10000) {while(PlaybackRuntime.active) delay(50)}
        for(id in downloads) {val row=app.repository.dao.get(id);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(id);app.coordinator.stopAndAwait(id);app.repository.remove(id)}}
        outputs.forEach {connection.repository.library.unfavorite(it.toString());runCatching {app.contentResolver.delete(it,null,null)}}
        playlistIds.forEach {connection.repository.library.delete(it)}
        val edit=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE).edit().clear()
        playbackPrefs.forEach {(key,value)->when(value) {is String->edit.putString(key,value);is Long->edit.putLong(key,value);is Int->edit.putInt(key,value);is Float->edit.putFloat(key,value);is Boolean->edit.putBoolean(key,value)}};edit.commit()
        settings.text("language",original.language);settings.text("theme",original.theme)
        main {listOf(Stage.RESUMED,Stage.PAUSED,Stage.STARTED,Stage.STOPPED,Stage.CREATED).flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.distinct().filter {it is MainActivity||it is PlayerActivity}.forEach {it.finish()}}
    }
    private fun showVideoControls() {compose.onNodeWithTag("video_player").performSemanticsAction(SemanticsActions.OnClick) {it()}}
    private fun ffmpeg(vararg arguments:String) {
        val session=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error",*arguments))
        assertTrue("Fixture generation failed (return ${session.returnCode})",ReturnCode.isSuccess(session.returnCode))
    }
    private fun fixtures():Map<String,File> {
        val pcm=File(directory,"tone.pcm");val wave=File(directory,"tone.wav")
        if(!wave.exists()) {
            val data=ByteBuffer.allocate(44100*12*2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(44100*12) {data.putShort((kotlin.math.sin(it*2*Math.PI*440/44100)*3500).toInt().toShort())}
            pcm.writeBytes(data.array())
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(data.capacity()+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(data.capacity())
            };wave.outputStream().use {it.write(header.array());it.write(data.array())}
        }
        val mp3=File(directory,"tone.mp3")
        if(!mp3.exists()) assertEquals(0,LameEncoder.encodeEx(pcm.path,mp3.path,128,44100,1,AtomicBoolean(false)))
        val m4a=File(directory,"tone.m4a");if(!m4a.exists()) ffmpeg("-i",wave.path,"-c:a","aac","-metadata","title=MediaFlow audio fixture","-metadata","artist=Controlled verification","-metadata","album=MediaFlow controlled album",m4a.path)
        val aac=File(directory,"tone.aac");if(!aac.exists()) ffmpeg("-i",m4a.path,"-c","copy",aac.path)
        val flac=File(directory,"tone.flac");if(!flac.exists()) ffmpeg("-i",wave.path,"-c:a","flac",flac.path)
        val ogg=File(directory,"tone.ogg");if(!ogg.exists()) ffmpeg("-i",flac.path,"-c","copy","-f","ogg",ogg.path)
        val video=File(directory,"landscape.mp4");if(!video.exists()) instrument.context.assets.open("status-original.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
        val webm=File(directory,"flower.webm");if(!webm.exists()) instrument.context.assets.open("playback-cc0-flower.webm").use {input->webm.outputStream().use {input.copyTo(it)}}
        val mkv=File(directory,"landscape.mkv");if(!mkv.exists()) ffmpeg("-i",video.path,"-c","copy",mkv.path)
        val portrait=File(directory,"portrait.mp4");if(!portrait.exists()) ffmpeg("-i",video.path,"-c","copy","-metadata:s:v:0","rotate=90",portrait.path)
        val srt=File(directory,"captions.srt").apply {writeText("1\n00:00:00,000 --> 00:00:11,000\nMediaFlow subtitle verification\n")}
        val multi=File(directory,"multitrack.mp4")
        if(!multi.exists()) ffmpeg("-i",video.path,"-i",m4a.path,"-i",srt.path,"-map","0:v:0","-map","0:a:0","-map","1:a:0","-map","2:0","-c:v","copy","-c:a","copy","-c:s","mov_text","-metadata:s:a:0","language=eng","-metadata:s:a:1","language=ara","-metadata:s:s:0","language=eng","-t","10",multi.path)
        return linkedMapOf("WAV" to wave,"MP3" to mp3,"M4A" to m4a,"AAC" to aac,"FLAC" to flac,"OGG-FLAC" to ogg,"MP4" to video,"MKV" to mkv,"WebM" to webm,"portrait-MP4" to portrait,"multitrack-MP4" to multi,"SRT" to srt)
    }
    private fun publish(file:File,video:Boolean=false,subtitle:Boolean=false,displayName:String?=null):PlaybackMedia {
        val mime=if(subtitle) "application/x-subrip" else when(file.extension) {"mp3"->"audio/mpeg";"m4a"->"audio/mp4";"aac"->"audio/aac";"wav"->"audio/wav";"flac"->"audio/flac";"ogg"->"audio/ogg";"mkv"->"video/x-matroska";"webm"->"video/webm";else->"video/mp4"}
        val collection=if(subtitle) MediaStore.Downloads.EXTERNAL_CONTENT_URI else if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val publishedName=displayName ?: "MediaFlow-player-${UUID.randomUUID()}-${file.name}"
        val uri=app.contentResolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,publishedName);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,if(subtitle) "Download/MediaFlow/Player verification" else if(video) "Movies/MediaFlow/Player verification" else "Music/MediaFlow/Player verification");put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!;outputs+=uri
        app.contentResolver.openOutputStream(uri)!!.use {sink->file.inputStream().use {it.copyTo(sink)}}
        app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        app.contentResolver.openFileDescriptor(uri,"r")!!.use {descriptor->assertTrue("Published local fixture is readable",descriptor.statSize>0)}
        return PlaybackMedia(uri.toString(),displayName ?: "MediaFlow ${file.name}",video,mime)
    }
    private fun embeddedArtworkFixture():File {
        val mp3=fixtures().getValue("MP3")
        val cover=File(directory,"embedded-cover.jpg")
        if(!cover.isFile) {
            val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.MAGENTA)
            cover.outputStream().use {assertTrue("Cover fixture JPEG encoding failed",bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it))}
            bitmap.recycle()
        }
        val embedded=File(directory,"tone-embedded-art.mp3")
        if(!embedded.isFile) ffmpeg("-i",mp3.path,"-i",cover.path,"-map","0:a:0","-map","1:v:0","-c:a","copy","-c:v","copy",
            "-id3v2_version","3","-disposition:v:0","attached_pic","-metadata","title=Embedded artwork fixture",
            "-metadata","artist=Controlled verification","-metadata","album=MediaFlow test album",
            "-metadata:s:v:0","title=Album cover","-metadata:s:v:0","comment=Cover (front)",embedded.path)
        return embedded
    }
    private suspend fun start(media:PlaybackMedia,open:Boolean=false) {
        val began=android.os.SystemClock.elapsedRealtime()
        main {connection.play(listOf(media),open=open)}
        waitFor {connection.state.value.current?.uri==media.uri&&(connection.state.value.ready||connection.state.value.error!=null)}
        assertNull("Playback error for ${media.mime}",main {connection.state.value.error})
        waitFor {connection.state.value.playing&&connection.state.value.position>150}
        startupSamples.put(JSONObject().put("mime",media.mime).put("open_activity",open).put("request_to_playing_and_position_over_150ms",android.os.SystemClock.elapsedRealtime()-began))
        File(evidence,"player-startup-${testName.methodName}.json").writeText(JSONObject().put("scope","Instrumented request through metadata/controller/decoder, including 150ms of media progress and 50ms polling; not isolated first-frame latency").put("samples",startupSamples).toString(2))
    }
    private suspend fun openFullAudioPlayer(items:List<PlaybackMedia>) {
        require(items.isNotEmpty())
        main {connection.play(items,open=false)}
        waitFor {connection.state.value.current?.uri==items.first().uri&&(connection.state.value.ready||connection.state.value.error!=null)}
        assertNull("Full audio player fixture failed to prepare",main {connection.state.value.error})
        waitFor {connection.state.value.playing&&connection.state.value.position>150}
        main {app.startActivity(Intent(app,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("audio_player",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    private fun assertFullAudioPlayerFits():JSONObject {
        val root=compose.onNodeWithTag("audio_player",useUnmergedTree=true)
        root.assertIsDisplayed()
        val rootNode=root.fetchSemanticsNode()
        val viewport=rootNode.boundsInRoot
        assertTrue("Player viewport must have positive bounds: $viewport",viewport.width>0f&&viewport.height>0f)
        assertFalse("The full audio-player root must not expose a scroll action",rootNode.config.contains(SemanticsActions.ScrollBy))
        val bounds=JSONObject()
        val requiredTags=listOf("player_back","player_background","player_floating","player_stop","player_artwork","player_title",
            "player_metadata","player_seek","player_position","player_duration","player_shuffle","player_previous",
            "player_play_pause","player_next","player_repeat","player_speed","player_queue","player_sleep","player_effects")
        for(tag in requiredTags) {
            val node=compose.onNodeWithTag(tag,useUnmergedTree=true)
            node.assertIsDisplayed()
            val rect=node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag has empty bounds: $rect",rect.width>0f&&rect.height>0f)
            assertTrue("$tag is clipped above the player viewport: $rect / $viewport",rect.top>=viewport.top-1f)
            assertTrue("$tag is clipped below the player viewport: $rect / $viewport",rect.bottom<=viewport.bottom+1f)
            assertTrue("$tag escapes the left edge of the player viewport: $rect / $viewport",rect.left>=viewport.left-1f)
            assertTrue("$tag escapes the right edge of the player viewport: $rect / $viewport",rect.right<=viewport.right+1f)
            bounds.put(tag,JSONObject().put("left",rect.left).put("top",rect.top).put("right",rect.right).put("bottom",rect.bottom))
        }
        val artwork=compose.onNodeWithTag("player_artwork",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("Artwork should remain meaningfully visible after responsive sizing: $artwork",artwork.width>0f&&artwork.height>0f)
        return JSONObject().put("viewport",JSONObject().put("left",viewport.left).put("top",viewport.top).put("right",viewport.right).put("bottom",viewport.bottom))
            .put("bounds",bounds).put("scroll_action",false)
    }
    private suspend fun closeFullAudioPlayer() {
        main {playerActivity()?.finish()}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("audio_player",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        main {connection.clear()}
    }
    private suspend fun delayedDocument(source:File,mime:String):Uri {
        val tree=android.provider.DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        app.startActivity(Intent().setComponent(android.content.ComponentName("com.zdownloader.app.test","com.zdownloader.app.TestDocumentsGrantActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(10000) {while(app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3)!=android.content.pm.PackageManager.PERMISSION_GRANTED) delay(25)}
        waitFor {rootActivity.hasWindowFocus()}
        val uri=android.provider.DocumentsContract.createDocument(app.contentResolver,android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"root"),mime,"elite-${UUID.randomUUID()}-${source.name}")!!
        source.inputStream().use {input->app.contentResolver.openOutputStream(uri,"w")!!.use {input.copyTo(it)}}
        return uri
    }
    private fun queryControl(uri:Uri,method:String):android.os.Bundle?=app.contentResolver.call(uri,method,android.provider.DocumentsContract.getDocumentId(uri),android.os.Bundle().apply {putLong("millis",2500)})
    private suspend fun awaitQuery(uri:Uri,key:String) {withTimeout(10000) {while(queryControl(uri,"queryState")?.getBoolean(key)!=true) delay(25)}}
    @Test fun queueInsertionSurvivesAProviderReadWhileItsOwnerDetaches():Unit=runBlocking {
        val fixture=fixtures();val document=delayedDocument(fixture.getValue("MP3"),"audio/mpeg")
        val reader=PlaybackConnection(app)
        try {
            start(publish(fixture.getValue("WAV")))
            main {connection.player.value!!.repeatMode=Player.REPEAT_MODE_ONE}
            withContext(Dispatchers.Main) {reader.attach();reader.connect()}
            queryControl(document,"delayQuery")
            main {reader.enqueue(PlaybackMedia(document.toString(),"Elite delayed queue item",false,"audio/mpeg"))}
            awaitQuery(document,"started");main {reader.detach()}
            awaitQuery(document,"finished");delay(900)
            val observed=withContext(Dispatchers.Main) {reader.attach();val player=reader.connect();(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}}
            File(evidence,"elite-delayed-queue.json").writeText(JSONObject().put("provider_query_completed",true).put("queue_size",observed.size).put("requested_item_retained",document.toString() in observed).toString(2))
            assertTrue("An explicit queue insertion must survive owner detach during provider metadata I/O",document.toString() in observed)
        } finally {main {reader.detach();reader.disconnect()};queryControl(document,"clearQuery");android.provider.DocumentsContract.deleteDocument(app.contentResolver,document)}
    }
    @Test fun delayedSubtitleValidationCannotAttachToADifferentSelectedMedia():Unit=runBlocking {
        val fixture=fixtures();val first=publish(fixture.getValue("MP4"),true);val second=publish(fixture.getValue("MP4"),true)
        val source=File(directory,"elite-${UUID.randomUUID()}.srt").apply {writeText("1\n00:00:00,000 --> 00:00:11,000\nElite ownership subtitle\n")}
        val document=delayedDocument(source,"application/x-subrip")
        try {
            start(first);main {connection.player.value!!.repeatMode=Player.REPEAT_MODE_ONE}
            queryControl(document,"delayQuery")
            main {connection.subtitle(document)};awaitQuery(document,"started")
            start(second);awaitQuery(document,"finished")
            suspend fun hasExternal()=main {connection.state.value.tracks.groups.any {group->(0 until group.length).any {index->group.getTrackFormat(index).id?.contains("mediaflow_external")==true||group.getTrackFormat(index).label==app.getString(R.string.player_external_subtitle)}}}
            withTimeoutOrNull(3000) {while(!hasExternal()) delay(50)}
            val attached=hasExternal()
            File(evidence,"elite-caption-ownership.json").writeText(JSONObject().put("selected_media_changed",true).put("selected_media_retained",main {connection.state.value.current?.uri==second.uri}).put("obsolete_caption_attached",attached).toString(2))
            assertEquals(second.uri,main {connection.state.value.current?.uri})
            assertFalse("A caption selected for the previous item must not migrate to a different item after metadata validation",attached)
        } finally {queryControl(document,"clearQuery");android.provider.DocumentsContract.deleteDocument(app.contentResolver,document);source.delete()}
    }
    @Test fun explicitPlaySurvivesACompositionOwnerHandoff()=runBlocking {
        val song=publish(fixtures().getValue("MP3"))
        val handoff=PlaybackConnection(app)
        try {
            main {handoff.attach();handoff.play(listOf(song),open=false);handoff.detach();handoff.attach()}
            withTimeout(20000) {while(!main {handoff.state.value.current?.uri==song.uri&&handoff.state.value.ready&&handoff.state.value.playing}) delay(50)}
            assertNull(main {handoff.state.value.error})
            assertTrue(main {handoff.player.value?.isConnected==true})
            File(evidence,"player-owner-handoff.json").writeText(JSONObject().put("explicit_play_survived_owner_handoff",true).put("controller_connected",true).put("service_owned_playback",true).toString(2))
        } finally {main {handoff.clear();handoff.detach();handoff.disconnect()}}
    }
    @Test fun controllerSessionPlaysAnAccessibleLocalFile()=runBlocking {
        val song=publish(fixtures().getValue("WAV"))
        try {connection.repository.resolve(song.uri,song.title,false)} catch(error:Exception) {
            File(evidence,"player-resolve-failure.txt").writeText(error.stackTraceToString());throw error
        }
        val controller=withContext(Dispatchers.Main) {withTimeout(10000) {connection.connect()}}
        assertTrue(main {controller.isConnected})
        start(song,open=true)
        capture("first-local-playback")
        main {connection.editLibrary {throw android.database.sqlite.SQLiteFullException("Synthetic library failure")}}
        waitFor {connection.state.value.error==R.string.player_error_library}
        assertTrue("A library write failure must not stop playback",main {controller.playWhenReady})
        main {connection.clearError()}
        main {controller.pause();controller.setMediaItems(List(LocalPlaybackPolicy.MAX_QUEUE) {song.item()},0,0L)}
        waitFor {connection.state.value.queue.size==LocalPlaybackPolicy.MAX_QUEUE}
        main {connection.enqueue(song)}
        waitFor {connection.state.value.error==R.string.player_error_queue_full}
        assertEquals(LocalPlaybackPolicy.MAX_QUEUE,main {controller.mediaItemCount})
        main {connection.clearError()}
    }
    @Test fun localCodecMatrixQueueBackgroundControlsFocusAndResume()=runBlocking {
        val fixture=fixtures();val matrix=JSONArray()
        for((name,file) in fixture.filterKeys {it!="SRT"&&it!="multitrack-MP4"&&it!="portrait-MP4"}) {
            val video=name in listOf("MP4","MKV","WebM")
            val media=publish(file,video);start(media)
            val state=main {connection.state.value}
            val formats=JSONArray();state.tracks.groups.forEach {group->for(i in 0 until group.length) if(group.isTrackSelected(i)) formats.put(JSONObject().put("mime",group.getTrackFormat(i).sampleMimeType).put("codecs",group.getTrackFormat(i).codecs))}
            matrix.put(JSONObject().put("container",name).put("result","played").put("position_ms",state.position).put("duration_ms",state.duration).put("tracks",formats))
        }
        File(evidence,"player-compatibility.json").writeText(JSONObject().put("scope","Local MediaStore URIs on HONOR Android API35; OGG fixture contains FLAC, not a universal codec claim").put("results",matrix).toString(2))
        val first=publish(fixture.getValue("MP3"));val second=publish(fixture.getValue("M4A"))
        start(first)
        main {connection.play(listOf(first,second),open=true)}
        waitFor {connection.state.value.queue.map {it.uri}==listOf(first.uri,second.uri)}
        compose.waitUntil(20000) {compose.onAllNodesWithTag("audio_player").fetchSemanticsNodes().isNotEmpty()}
        waitFor {connection.state.value.playing}
        capture("audio-en")
        settings.text("language","ar");compose.waitForIdle();capture("audio-ar");settings.text("language","en")
        main {connection.speed(1.25f);connection.shuffle();connection.repeat()}
        waitFor {connection.state.value.speed==1.25f&&connection.state.value.shuffle&&connection.state.value.repeat==Player.REPEAT_MODE_ALL}
        for(speed in listOf(.25f,3f)) {main {connection.speed(speed)};waitFor {connection.state.value.speed==speed&&connection.state.value.playing};delay(250)}
        main {connection.speed(1.25f)}
        main {connection.move(1,0)};waitFor {connection.state.value.queue.first().uri==second.uri}
        main {connection.select(0)};waitFor {connection.state.value.current?.uri==second.uri}
        main {connection.remove(1)};waitFor {connection.state.value.queue.size==1}
        compose.onNodeWithTag("player_queue").performClick();capture("queue");instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        main {connection.command(PlaybackCommands.EFFECTS)};delay(400)
        compose.onNodeWithTag("player_effects").performClick();capture("effects");instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        main {connection.sleep(5)};waitFor {connection.state.value.extras.getLong("sleep_deadline")>android.os.SystemClock.elapsedRealtime()}
        main {connection.sleep(0)};waitFor {connection.state.value.extras.getLong("sleep_deadline")==0L}
        main {playerActivity()?.closePlayer()};compose.waitUntil(15000) {compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty()}
        capture("mini-player");compose.onNodeWithTag("nav_downloads").performClick();compose.onNodeWithTag("mini_player").assertIsDisplayed()
        main {connection.speed(1f);connection.seek(2500);connection.pause()};delay(300)
        val stored=connection.repository.position(second.uri);assertTrue("Resume position stored",stored>=2000)
        instrument.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            val sessions=(app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(null)
            val remote=sessions.first {it.packageName==app.packageName}
            remote.transportControls.play();waitFor {connection.state.value.playing}
            val before=main {connection.state.value.position}
            shell("input keyevent KEYCODE_HOME");delay(1200)
            withContext(Dispatchers.Main) {connection.connect()}
            waitFor {connection.state.value.position>before+500}
            remote.transportControls.pause();waitFor {!connection.state.value.playing}
            remote.transportControls.play();waitFor {connection.state.value.playing}
            val screenOffStart=main {connection.state.value.position}
            try {
                shell("input keyevent KEYCODE_SLEEP");delay(1800)
                assertEquals("MediaSession remains playing with screen off",android.media.session.PlaybackState.STATE_PLAYING,remote.playbackState!!.state)
                assertTrue("Controller position advances with screen off",main {connection.player.value!!.currentPosition}>screenOffStart+500)
            } finally {shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")}
            File(evidence,"player-screen-off.json").writeText(JSONObject().put("screen_off_ms",1800).put("platform_session_playing",true).put("position_advanced",true).put("scope","Short screen-off check, not prolonged sleep/Doze acceptance").toString(2))
            File(evidence,"player-background-session.json").writeText(JSONObject().put("background_progress",true).put("platform_media_session_play_pause",true).put("queue_reorder_remove",true).put("shuffle_repeat_speed",true).put("timed_sleep_arm_cancel",true).put("resume_position_ms",stored).toString(2))
        } finally {instrument.uiAutomation.dropShellPermissionIdentity()}
        val relaunched=shell("am start -W -n ${app.packageName}/${MainActivity::class.java.name} -f 0x10020000")
        assertTrue(relaunched,relaunched.contains("Status: ok"));delay(500)
        withContext(Dispatchers.Main) {connection.connect()}
        val audio=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener({}).build()
        val focus=main {audio.requestAudioFocus(request)}
        if(focus==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            waitFor {!connection.state.value.playing};main {connection.pause();audio.abandonAudioFocusRequest(request)};delay(300)
            assertFalse("Manual pause must survive focus restoration",main {connection.player.value!!.playWhenReady})
        }
        main {connection.player.value!!.repeatMode=Player.REPEAT_MODE_OFF;connection.player.value!!.shuffleModeEnabled=false;connection.sleep(-1);connection.seek((connection.state.value.duration-700).coerceAtLeast(0));connection.player.value!!.play()}
        waitFor {connection.state.value.extras.getBoolean("sleep_end")};waitFor(10000) {!connection.state.value.playing&&!connection.state.value.extras.getBoolean("sleep_end")}
        File(evidence,"player-focus-sleep.json").writeText(JSONObject().put("focus_request_granted",focus==AudioManager.AUDIOFOCUS_REQUEST_GRANTED).put("manual_pause_not_resumed",focus==AudioManager.AUDIOFOCUS_REQUEST_GRANTED).put("end_track_sleep_stopped",true).toString(2))
        main {connection.pause();connection.seek(2000)};delay(250)
        main {connection.disconnect();app.stopService(Intent(app,MediaPlaybackService::class.java))}
        waitFor { !PlaybackRuntime.active }
        val restored=withContext(Dispatchers.Main) {connection.connect()}
        assertFalse("Cold service restore must not autoplay",main {restored.playWhenReady})
        assertTrue("Cold service keeps the saved queue",main {restored.mediaItemCount}>0)
        File(evidence,"player-resume-recreation.json").writeText(JSONObject().put("service_recreated",true).put("queue_restored",true).put("autoplay",false).put("position_ms",main {restored.currentPosition}).toString(2))
        main {connection.player.value!!.repeatMode=Player.REPEAT_MODE_ONE;connection.seek(1000);connection.player.value!!.play()};waitFor {connection.state.value.playing}
        instrument.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            val remote=(app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(null).first {it.packageName==app.packageName}
            taskRemoved=true;main {rootActivity.finishAndRemoveTask()};delay(1500)
            assertTrue("Service survives task removal",PlaybackRuntime.active)
            assertEquals(android.media.session.PlaybackState.STATE_PLAYING,remote.playbackState!!.state)
            File(evidence,"player-recents.json").writeText(JSONObject().put("activity_task_removed",true).put("service_active",true).put("platform_session_playing",true).toString(2))
        } finally {instrument.uiAutomation.dropShellPermissionIdentity()}
    }
    @Test fun screenOffStopsVideoEvenWhileTheActivityStillReportsPictureInPicture():Unit=runBlocking {
        main {connection.repository.setBoolean("auto_rotate",false)}
        val video=publish(fixtures().getValue("MP4"),true)
        start(video,open=true)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
        val activity=playerActivity()!!
        Assume.assumeTrue("Device supports picture in picture",activity.pipSupported)
        val observer=PlaybackConnection(app)
        try {
            val player=withContext(Dispatchers.Main) {observer.connect().also {connection.player.value!!.repeatMode=Player.REPEAT_MODE_ONE}}
            main {activity.enterPip()}
            phase="visible PiP keeps video playing"
            waitFor {activity.isInPictureInPictureMode&&player.isPlaying}
            shell("input keyevent KEYCODE_SLEEP")
            phase="screen off must stop invisible PiP video"
            waitFor {player.mediaItemCount==0&&!player.playWhenReady&&!player.isPlaying}
            assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {it.id==57001})
            main {player.play()};delay(500)
            assertFalse("A hidden video must not resume as audio from session controls",main {player.isPlaying})
            File(evidence,"video-hidden-pip.json").writeText(JSONObject().put("visible_pip_keeps_playing",true)
                .put("screen_off_clears_video_session",true).put("transport_cannot_resume_hidden_video",true).toString(2))
        } finally {shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");main {observer.disconnect()}}
    }
    @Test fun homeWithoutPictureInPictureStopsVideoAndRemovesItsNotification():Unit=runBlocking {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=31)
        main {connection.repository.setBoolean("auto_rotate",false)}
        start(publish(fixtures().getValue("MP4"),true),open=true)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
        val activity=playerActivity()!!
        val observer=PlaybackConnection(app)
        try {
            val player=withContext(Dispatchers.Main) {observer.connect()}
            // Disable auto-entry for this Activity only, without changing the
            // user's system PiP permission or any persistent preference.
            main {activity.setPictureInPictureParams(android.app.PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())}
            shell("input keyevent KEYCODE_HOME")
            phase="Home without PiP closes video session"
            waitFor {player.mediaItemCount==0&&!player.playWhenReady&&!player.isPlaying}
            assertFalse(main {activity.isInPictureInPictureMode})
            waitFor {app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {it.id==57001}}
            File(evidence,"video-home-exit.json").writeText(JSONObject().put("actual_home_key",true)
                .put("pip_not_visible",true).put("video_session_empty",true).put("playback_notification_removed",true).toString(2))
        } finally {main {observer.disconnect()}}
    }
    @Test fun normalVideoCloseClearsItsNotificationSession():Unit=runBlocking {
        main {connection.repository.setBoolean("auto_rotate",false)}
        start(publish(fixtures().getValue("MP4"),true),open=true)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
        phase="normal close clears video session"
        main {playerActivity()!!.closePlayer()}
        waitFor {connection.state.value.current==null&&!connection.state.value.playing}
        waitFor {app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {it.id==57001}}
    }
    @Test fun videoPlayerCanSwitchToAudioOnlyAndContinueInBackground():Unit=runBlocking {
        main {connection.repository.setBoolean("auto_rotate",false)}
        start(publish(fixtures().getValue("MP4"),true),open=true)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
        showVideoControls()
        compose.onNodeWithTag("video_options").performClick()
        compose.onNodeWithTag("video_play_audio_only").performClick()
        phase="video player audio-only handoff"
        waitFor {connection.state.value.extras.getBoolean("audio_only")&&connection.state.value.playing}
        assertTrue("Audio-only handoff keeps the selected video item",connection.state.value.current?.video==true)
        assertTrue("Audio-only mode disables video track selection",main {
            connection.player.value!!.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        })
        val observerConnection=PlaybackConnection(app)
        try {
            val observer=withContext(Dispatchers.Main) {observerConnection.connect()}
            val before=main {observer.currentPosition}
            shell("input keyevent KEYCODE_HOME");delay(1200)
            waitFor {observer.isPlaying&&kotlin.math.abs(observer.currentPosition-before)>300}
            assertTrue("Audio-only mode continues without video tracks",main {
                observer.currentTracks.groups.filter {it.type==C.TRACK_TYPE_VIDEO}.none {group->(0 until group.length).any(group::isTrackSelected)}
            })
        } finally {withContext(Dispatchers.Main) {observerConnection.disconnect()}}
    }
    @Test fun closingVideoWhileItsProviderIsResolvingCannotStartBackgroundAudioLater():Unit=runBlocking {
        val document=delayedDocument(fixtures().getValue("MP4"),"video/mp4")
        val observer=PlaybackConnection(app)
        try {
            main {rootActivity.startActivity(Intent(app,PlayerActivity::class.java))}
            waitFor {playerActivity()!=null}
            val activity=playerActivity()!!
            val player=withContext(Dispatchers.Main) {observer.connect()}
            queryControl(document,"delayQuery")
            main {connection.play(listOf(PlaybackMedia(document.toString(),"Delayed video exit",true,"video/mp4")),open=false)}
            awaitQuery(document,"started")
            main {activity.closePlayer()}
            awaitQuery(document,"finished")
            // Observe past the provider return; an empty optimistic timeline before
            // the I/O completes would not prove that the deferred play was cancelled.
            delay(1000)
            assertFalse("Leaving during metadata I/O must cancel its pending video playback",main {player.playWhenReady||player.isPlaying})
            assertEquals(0,main {player.mediaItemCount})
            File(evidence,"video-exit-during-resolution.json").writeText(JSONObject().put("provider_was_in_flight",true)
                .put("provider_returned_after_exit",true).put("deferred_video_not_started",true).toString(2))
        } finally {main {observer.disconnect()};queryControl(document,"clearQuery");android.provider.DocumentsContract.deleteDocument(app.contentResolver,document)}
    }
    @Test fun externalVideoDismissedDuringProviderReadCannotResurrectItsPendingRequest():Unit=runBlocking {
        val document=delayedDocument(fixtures().getValue("MP4"),"video/mp4")
        val observer=PlaybackConnection(app)
        try {
            val player=withContext(Dispatchers.Main) {observer.connect()}
            queryControl(document,"delayQuery")
            main {rootActivity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(document,"video/mp4")
                .setClass(app,PlayerActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
            awaitQuery(document,"started");waitFor {playerActivity()!=null}
            main {playerActivity()!!.closePlayer()}
            awaitQuery(document,"finished")
            waitFor {connection.repository.pendingExternalOpen()==null}
            delay(1000)
            assertFalse(main {player.playWhenReady||player.isPlaying})
            assertEquals(0,main {player.mediaItemCount})
            File(evidence,"external-video-exit-during-resolution.json").writeText(JSONObject().put("real_action_view",true)
                .put("closed_while_provider_busy",true).put("no_deferred_background_audio",true).put("pending_request_retired",true).toString(2))
        } finally {main {observer.disconnect()};queryControl(document,"clearQuery");android.provider.DocumentsContract.deleteDocument(app.contentResolver,document)}
    }
    @Test fun videoTracksExternalSubtitlesRotationAndAudioOnlyMode()=runBlocking {
        main {connection.repository.setBoolean("auto_rotate",true)}
        val fixture=fixtures();val video=publish(fixture.getValue("multitrack-MP4"),true)
        start(video,open=true)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("Landscape must be selected before fullscreen controls are presented",
            android.content.res.Configuration.ORIENTATION_LANDSCAPE,playerActivity()?.resources?.configuration?.orientation)
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,playerActivity()?.requestedOrientation)
        main {connection.player.value!!.repeatMode=Player.REPEAT_MODE_ONE}
        waitFor {connection.state.value.tracks.groups.filter {it.type==C.TRACK_TYPE_AUDIO}.sumOf {it.length}>=2}
        assertTrue("Embedded subtitles discovered",main {connection.state.value.tracks.groups.any {it.type==C.TRACK_TYPE_TEXT}})
        capture("video-landscape-content")
        showVideoControls()
        compose.onNodeWithTag("player_tracks").performClick();capture("tracks")
        val group=main {connection.state.value.tracks.groups.filter {it.type==C.TRACK_TYPE_AUDIO}.last()}
        main {connection.track(C.TRACK_TYPE_AUDIO,group,group.length-1)}
        instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        // Distinguish the newly loaded external cue from the embedded cue. An
        // identical string could satisfy readiness before the source replacement.
        val externalText="MediaFlow external subtitle verification"
        val external=File(directory,"external-captions.srt").apply {writeText("1\n00:00:00,000 --> 00:00:11,000\n$externalText\n")}
        val subtitle=publish(external,subtitle=true)
        phase="external track selection"
        main {connection.subtitle(Uri.parse(subtitle.uri))};waitFor {connection.state.value.tracks.groups.any {group->
            group.type==C.TRACK_TYPE_TEXT&&group.isSelected&&(0 until group.length).any {group.getTrackFormat(it).id?.contains("mediaflow_external")==true}
        }}
        phase="external subtitle cue"
        waitFor {connection.player.value!!.currentCues.cues.any {it.text?.contains(externalText)==true}}
        // The cue spans the complete clip. Do not mask it with an unrelated,
        // asynchronous seek immediately before testing paused native rendering.
        // Seeking is exercised separately below by the gesture assertions.
        waitFor {connection.state.value.ready&&connection.state.value.playing}
        main {connection.pause()}
        showVideoControls()
        compose.waitForIdle()
        val transportBounds=compose.onNodeWithTag("video_transport_controls").fetchSemanticsNode().boundsInRoot
        fun subtitleView(view:android.view.View):androidx.media3.ui.SubtitleView? {
            if(view is androidx.media3.ui.SubtitleView) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) subtitleView(view.getChildAt(i))?.let {return it}
            return null
        }
        fun renderedCaption():Boolean {
            val native=subtitleView(playerActivity()!!.window.decorView) ?: return false
            return native.javaClass.declaredFields.filter {java.util.List::class.java.isAssignableFrom(it.type)}.any {field->
                field.isAccessible=true
                (field.get(native) as? List<*>)?.any {it is androidx.media3.common.text.Cue&&it.text?.contains(externalText)==true}==true
            }
        }
        File(evidence,"subtitle-native-before-wait.json").writeText(main {
            val native=subtitleView(playerActivity()!!.window.decorView)!!
            JSONObject().put("controller_cues",connection.player.value!!.currentCues.cues.size).put("native_caption_present",renderedCaption())
                .put("native_width",native.width).put("native_height",native.height).put("native_shown",native.isShown)
                .put("child_width",native.getChildAt(0).width).put("child_height",native.getChildAt(0).height).toString(2)
        })
        phase="native caption renderer receives the selected cue"
        waitFor {renderedCaption()}
        val captionInset=main {subtitleView(playerActivity()!!.window.decorView)!!.getChildAt(0).paddingBottom}
        assertTrue("Visible controls must reserve caption space",captionInset>=transportBounds.height.toInt())
        capture("video-subtitles-controls-separated")
        File(evidence,"subtitle-control-clearance.json").writeText(JSONObject().put("caption_bottom_padding_px",captionInset).put("transport_height_px",transportBounds.height).put("external_cue_active",true).toString(2))
        compose.onNodeWithContentDescription("Hide playback controls").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("video_transport_controls").fetchSemanticsNodes().isEmpty()}
        waitFor {subtitleView(playerActivity()!!.window.decorView)?.getChildAt(0)?.paddingBottom==0}
        assertEquals("Hidden controls restore the full caption surface",0,main {subtitleView(playerActivity()!!.window.decorView)!!.getChildAt(0).paddingBottom})
        capture("video-subtitles")
        main {connection.player.value!!.play()}
        phase="automatic landscape"
        waitFor {playerActivity()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        showVideoControls()
        main {connection.seek(4000)}
        waitFor {connection.state.value.position in 3900..5000&&connection.state.value.ready}
        main {connection.player.value!!.play()}
        val beforeRotation=main {connection.state.value}
        phase="instant rotate to portrait"
        compose.onNodeWithTag("player_orientation_control").performClick()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,playerActivity()?.requestedOrientation)
        waitFor {playerActivity()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
        waitFor {connection.state.value.current?.uri==beforeRotation.current?.uri&&connection.state.value.playing&&connection.state.value.position>=beforeRotation.position}
        assertTrue("External subtitle selection survives orientation change",
            main {connection.state.value.tracks.groups.any {it.type==C.TRACK_TYPE_TEXT&&it.isSelected}})
        phase="instant rotate to landscape"
        showVideoControls()
        compose.onNodeWithTag("player_orientation_control").performClick()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,playerActivity()?.requestedOrientation)
        waitFor {playerActivity()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        waitFor {connection.state.value.current?.uri==beforeRotation.current?.uri&&connection.state.value.playing}
        capture("video-fullscreen-landscape")
        phase="automatic controls hide"
        compose.waitUntil(10000) {compose.onAllNodesWithTag("player_tracks").fetchSemanticsNodes().isEmpty()}
        capture("video-immersive")
        phase="double-tap seek"
        main {connection.pause();connection.seek(4000)}
        waitFor {connection.state.value.position in 3500..4500&&!connection.state.value.playing}
        compose.onNodeWithTag("video_gesture_area").performTouchInput {doubleClick(Offset(width*.25f,height*.5f))}
        waitFor {connection.state.value.position<1500}
        phase="horizontal swipe seek"
        compose.onNodeWithTag("video_gesture_area").performTouchInput {swipe(Offset(width*.25f,height*.5f),Offset(width*.65f,height*.5f),300)}
        waitFor {connection.state.value.position>1500}
        val audioManager=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        compose.onNodeWithTag("video_gesture_area").performTouchInput {swipe(Offset(width*.75f,height*.7f),Offset(width*.75f,height*.3f),250)}
        assertTrue(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)>=volume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
        compose.onNodeWithTag("video_gesture_area").performTouchInput {swipe(Offset(width*.25f,height*.7f),Offset(width*.25f,height*.3f),250)}
        assertTrue("Brightness gesture updates this window",main {playerActivity()!!.window.attributes.screenBrightness}>.5f)
        phase="rotated portrait container"
        val portrait=publish(fixture.getValue("portrait-MP4"),true);start(portrait,open=true)
        waitFor {playerActivity()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,playerActivity()?.requestedOrientation)
        waitFor {connection.state.value.ready};capture("portrait-video")
        showVideoControls()
        phase="portrait direct rotation and return"
        compose.onNodeWithTag("player_orientation_control").performClick()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,playerActivity()?.requestedOrientation)
        waitFor {playerActivity()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        showVideoControls()
        compose.onNodeWithTag("player_orientation_control").performClick()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,playerActivity()?.requestedOrientation)
        waitFor {playerActivity()?.resources?.configuration?.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
        File(evidence,"player-video-features.json").writeText(JSONObject().put("multiple_audio_tracks",true).put("embedded_subtitle_track",true).put("external_srt_selected",true).put("landscape_rotation",true).put("portrait_container_played",true).put("double_tap_and_swipe_seek",true).put("volume_brightness_gestures",true).put("pip_coverage","separate lifecycle tests").toString(2))
    }
    @Test fun independentMusicLibraryFavoritesAlbumsArtistsAndPersistentPlaylists()=runBlocking {
        val fixture=fixtures();val song=publish(fixture.getValue("M4A"));val second=publish(fixture.getValue("MP3"))
        val dao=connection.repository.library
        connection.repository.favorite(song,true)
        assertTrue(dao.favorites().first().any {it.uri==song.uri})
        val id=UUID.randomUUID().toString();playlistIds+=id
        dao.create(PlaybackList(id,"MediaFlow local playlist"));dao.append(id,song);dao.append(id,second)
        dao.move(id,second.uri,-1)
        assertEquals(second.uri,dao.ordered(id).first().uri)
        dao.rename(id,"MediaFlow persistent playlist")
        val before=dao.ordered(id)
        val overflow=runCatching {dao.appendAll(id,(0..500).map {song.copy(uri="content://media/external/audio/media/$it")})}
        assertTrue("Oversized additions are rejected",overflow.isFailure)
        assertEquals("Rejected additions are atomic",before,dao.ordered(id))
        val rejectedId=UUID.randomUUID().toString();playlistIds+=rejectedId
        assertTrue(runCatching {dao.createWithItems(PlaybackList(rejectedId,"Rejected oversized queue"),(0..500).map {song.copy(uri="content://media/external/audio/media/$it")})}.isFailure)
        assertFalse("Failed creation leaves no empty playlist",dao.playlists().first().any {it.id==rejectedId})
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("library_open_file").performClick()
        compose.selectLibraryMenuOption("library_source_menu","library_source_device")
        compose.onNodeWithTag("media_type_audio").performClick()
        compose.onNodeWithTag("local_library_search").performTextInput("MediaFlow")
        compose.onNodeWithTag("local_library_search").performImeAction()
        compose.onNodeWithTag("local_library_search").assertIsNotFocused()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("local_media_${song.uri.substringAfterLast('/')}").fetchSemanticsNodes().isNotEmpty()}
        capture("music-songs")
        compose.selectLibraryMenuOption("library_view_menu","music_albums")
        compose.assertLibraryMenuSelection("library_view_menu","music_albums")
        compose.onNodeWithText("MediaFlow controlled album").performScrollTo().assertIsDisplayed();capture("music-albums")
        compose.selectLibraryMenuOption("library_view_menu","music_artists")
        compose.assertLibraryMenuSelection("library_view_menu","music_artists")
        compose.onNodeWithText("Controlled verification").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MediaFlow controlled album").assertDoesNotExist();capture("music-artists")
        compose.selectLibraryMenuOption("library_source_menu","library_source_favorites");capture("music-favorites")
        compose.selectLibraryMenuOption("library_source_menu","library_source_playlists")
         compose.onNodeWithText("MediaFlow persistent playlist").performClick();capture("music-playlist")
         compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("local_media_${second.uri.substringAfterLast('/')}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("local_media_${second.uri.substringAfterLast('/')}").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("audio_player").fetchSemanticsNodes().isNotEmpty()}
        waitFor {connection.state.value.current?.uri==second.uri&&connection.state.value.playing}
        assertEquals(2,main {connection.state.value.queue.size})
        File(evidence,"player-independent-library.json").writeText(JSONObject().put("no_download_required",true).put("mediastore_own_local_media_browsed",true).put("favorites_persisted_room",true).put("playlist_created_renamed_reordered_played",true).put("songs_albums_artists_rendered",true).toString(2))
    }
    @Test fun localUriErrorsAndConcurrentDownloadUseExistingPipeline()=runBlocking {
        val fixture=fixtures();val audio=publish(fixture.getValue("WAV"));start(audio)
        main {connection.player.value!!.repeatMode=Player.REPEAT_MODE_ONE}
        val media=app.extractor.analyze("https://www.w3schools.com/html/mov_bbb.mp4","player-download-${UUID.randomUUID()}")
        val selection=Selection.video(media,media.videoOptions().first())
        val id=app.repository.create(media,selection,"Player concurrent verification");downloads+=id
        app.repository.prepareForSubmission(id);app.scheduler.startFromUser(id)
        withTimeout(180000) {while(app.repository.dao.get(id)!!.status !in setOf("COMPLETED","FAILED")) delay(200)}
        assertEquals("COMPLETED",app.repository.dao.get(id)!!.status)
        assertEquals(audio.uri,main {connection.state.value.current?.uri});assertTrue(main {connection.state.value.playing})
        val row=app.repository.dao.get(id)!!
        // Open the actual completed-download entry via Library, not a synthetic raw path.
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("library_search").performTextInput(row.filename)
        compose.onNodeWithTag("library_search").performImeAction()
        compose.onNodeWithTag("library_search").assertIsNotFocused()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("library_item_$id").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("library_item_$id").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
        waitFor {connection.state.value.current?.uri==row.uri&&connection.state.value.playing}
        capture("downloaded-video")
        val audioSelection=Selection.audio(media,"mp3")
        val second=app.repository.create(media,audioSelection,"Player video concurrent MP3 verification");downloads+=second
        app.repository.prepareForSubmission(second);app.scheduler.startFromUser(second)
        withTimeout(180000) {while(app.repository.dao.get(second)!!.status !in setOf("COMPLETED","FAILED")) delay(200)}
        assertEquals("COMPLETED",app.repository.dao.get(second)!!.status)
        assertEquals(row.uri,main {connection.state.value.current?.uri});assertTrue(main {connection.state.value.playing})
        val deleted=publish(fixture.getValue("MP3"));start(deleted)
        app.contentResolver.delete(Uri.parse(deleted.uri),null,null)
        main {connection.player.value!!.stop();connection.player.value!!.prepare();connection.player.value!!.play()}
        waitFor {connection.state.value.error!=null}
        assertEquals(R.string.player_error_missing,main {connection.state.value.error})
        main {connection.clearError();connection.playUri("content://com.zdownloader.app.test.statusdocs/document/denied-playback",open=false)}
        waitFor {connection.state.value.error!=null}
        assertTrue(main {connection.state.value.error in listOf(R.string.player_error_permission,R.string.player_error_missing)})
        File(evidence,"player-download-errors.json").writeText(JSONObject().put("download_completed_during_audio",true).put("mp3_download_completed_during_video",true).put("downloaded_entry_opened_from_library",true).put("deleted_file_error",true).put("ungranted_document_rejected",true).toString(2))
    }
    @Test fun audioPlayerControlsRemainReachableOnNarrowTabletAndLargeFonts()=runBlocking {
        val oldSize=shell("wm size").lineSequence().firstOrNull {it.startsWith("Override size:")}?.substringAfter(": ")
        val oldDensity=shell("wm density").lineSequence().firstOrNull {it.startsWith("Override density:")}?.substringAfter(": ")
        val oldFont=shell("settings get system font_scale")
        try {
            shell("wm density 320")
            val fixture=fixtures()
            val short=publish(fixture.getValue("MP3"),displayName="Tone.mp3")
            val queueNext=publish(fixture.getValue("MP3"),displayName="Queue second fixture.mp3")
            val arabic=publish(fixture.getValue("MP3"),displayName="تسجيل صوتي طويل للغاية لاختبار عنوان الملف العربي في مشغل ميديافلو مع بيانات موسعة.mp3")
            val english=publish(fixture.getValue("MP3"),displayName="A very long English recording filename to verify title truncation and responsive audio controls in MediaFlow player.mp3")
            val artwork=publish(embeddedArtworkFixture())
            val cases=JSONArray()

            suspend fun showCase(name:String,media:PlaybackMedia,width:Int,height:Int,fontScale:Float,language:String,
                                 hasArtwork:Boolean,queue:List<PlaybackMedia> = listOf(media)) {
                phase="audio-layout-$name"
                settings.text("language",language)
                shell("settings put system font_scale $fontScale")
                shell("wm size ${width*2}x${height*2}")
                openFullAudioPlayer(queue)
                waitFor {playerActivity()?.resources?.configuration?.let {
                    kotlin.math.abs(it.screenWidthDp-width)<=4&&kotlin.math.abs(it.fontScale-fontScale)<.05f
                }==true}
                if(hasArtwork) {
                    waitFor {connection.state.value.current?.artwork!=null}
                    compose.waitUntil(10000) {compose.onAllNodesWithTag("embedded_artwork",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithTag("embedded_artwork",useUnmergedTree=true).assertIsDisplayed()
                    compose.onNodeWithTag("player_metadata",useUnmergedTree=true).assertTextContains("Controlled verification",substring=true)
                } else compose.onNodeWithTag("artwork_fallback",useUnmergedTree=true).assertIsDisplayed()
                val expectedTitle=if(hasArtwork) "Embedded artwork fixture" else media.title
                compose.onNodeWithTag("player_title",useUnmergedTree=true).assertTextContains(expectedTitle.take(12),substring=true)
                val duration=main {connection.state.value.duration}
                assertTrue("Fixture duration should be present",duration>0)
                compose.onNodeWithTag("player_duration",useUnmergedTree=true).assertTextEquals(playTime(duration))
                val bounds=assertFullAudioPlayerFits()
                val configuration=playerActivity()!!.resources.configuration
                cases.put(JSONObject().put("name",name).put("title",media.title).put("language",language).put("width_dp",configuration.screenWidthDp)
                    .put("height_dp",configuration.screenHeightDp).put("font_scale",configuration.fontScale).put("artwork",hasArtwork).put("bounds",bounds))
                capture("audio-fixed-$name")
            }

            showCase("short-no-art",short,360,780,1.0f,"en",false,listOf(short,queueNext))
            phase="audio-layout-transport-controls"
            val afterPause=main {connection.state.value.position}
            compose.onNodeWithTag("player_play_pause",useUnmergedTree=true).assertIsDisplayed().performClick()
            waitFor { !connection.state.value.playing }
            compose.onNodeWithTag("player_play_pause",useUnmergedTree=true).performClick()
            waitFor { connection.state.value.playing }
            compose.onNodeWithTag("player_seek",useUnmergedTree=true).performSemanticsAction(SemanticsActions.SetProgress) {setProgress->
                assertTrue("Seek control must accept a progress update",setProgress(7000f))
            }
            waitFor {connection.state.value.position>=6500}
            val afterSeek=main {connection.state.value.position}
            compose.onNodeWithTag("player_next",useUnmergedTree=true).assertIsEnabled().performClick()
            waitFor {connection.state.value.current?.uri==queueNext.uri}
            compose.onNodeWithTag("player_previous",useUnmergedTree=true).assertIsEnabled().performClick()
            waitFor {connection.state.value.current?.uri==short.uri}
            cases.put(JSONObject().put("play_pause","passed").put("seek_to_7000ms",true).put("next_previous","passed")
                .put("position_before_controls",afterPause).put("position_after_seek",afterSeek))
            closeFullAudioPlayer()

            showCase("long-arabic-no-art",arabic,320,852,2.0f,"ar",false)
            showCase("long-english-no-art",english,840,852,1.6f,"en",false)
            showCase("embedded-artwork-and-metadata",artwork,360,780,1.0f,"en",true)
            val noArtAtLargeFont=publish(fixture.getValue("MP3"),displayName="Fallback cover placeholder verification.mp3")
            showCase("no-art-large-font-rtl",noArtAtLargeFont,320,852,2.0f,"ar",false)

            val packageInfo=app.packageManager.getPackageInfo(app.packageName,0)
            File(evidence,"player-fixed-layout-${testName.methodName}.json").writeText(JSONObject().put("package",app.packageName)
                .put("version_name",packageInfo.versionName).put("version_code",androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo))
                .put("device","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("api",android.os.Build.VERSION.SDK_INT)
                .put("cases",cases).put("all_essential_controls_inside_viewport",true)
                .put("full_player_vertical_scroll_action",false).toString(2))
        } finally {
            shell(if(oldSize==null) "wm size reset" else "wm size $oldSize");shell(if(oldDensity==null) "wm density reset" else "wm density $oldDensity")
            shell(if(oldFont=="null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
        }
    }
}
