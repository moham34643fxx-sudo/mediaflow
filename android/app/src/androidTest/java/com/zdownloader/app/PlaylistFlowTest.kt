package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** Actual Android queue/media/publication with isolated controlled extraction/HTTP fixtures.
 * Not an external-platform or physical WAN throughput test. */
class PlaylistFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private fun shot(name:String) {
        compose.waitForIdle()
        val image=compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
        File(folder,"playlist-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun shotBatch(name:String,id:String) {
        compose.waitForIdle()
        val image=compose.onNodeWithTag("playlist_batch_$id").captureToImage().asAndroidBitmap()
        File(folder,"playlist-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun fiveSequentialItemsPublishBeforeAdvancementWithOneAggregateOperation()=runBlocking {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        compose.activityRule.scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        compose.waitUntil(30000) {runCatching {compose.onAllNodesWithTag("url_input").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
        val activityIntent=Intent(compose.activity.intent)
        val exerciseScreenOff=InstrumentationRegistry.getArguments().getString("screenOff","false")=="true"
        // A host-aborted run may never execute finally. Clean only requests from
        // this instrumentation-only .invalid source before starting a new run.
        for(batch in app.repository.dao.batches().first().filter {it.url.startsWith("https://mediaflow.fixture.invalid/")}) {
            app.playlists.cancel(batch.id)
            for(row in app.repository.dao.batchItems(batch.id)) {
                app.coordinator.stopAndAwait(row.id)
                val current=app.repository.dao.get(row.id)
                if(current?.status=="COMPLETED") app.storage.deleteFile(current) else app.repository.remove(row.id)
            }
            app.repository.dao.removeEmptyBatch(batch.id)
        }
        withTimeout(30000) {while(app.coordinator.running&&app.repository.dao.remaining()==0) delay(50)}
        val old=SettingsRepository(app).current()
        val active=AtomicInteger();val peak=AtomicInteger();val retry=AtomicInteger()
        val firstHttp=AtomicLong(0)
        val fixture=instrumentation.context.assets.open("av1-validation.mp4").use {it.readBytes()}
        val original=app.transfer.client
        val field=app.transfer.javaClass.declaredFields.first {it.type==OkHttpClient::class.java}.apply {isAccessible=true}
        val testClient=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request()
            firstHttp.compareAndSet(0,SystemClock.elapsedRealtime())
            assertEquals("mediaflow.fixture.invalid",request.url.host)
            if(request.url.encodedPath.endsWith("/4.mp4")&&retry.getAndIncrement()==0) {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("Controlled temporary failure").body(ResponseBody.create(null,ByteArray(0))).build()
            } else {
                val count=active.incrementAndGet();peak.updateAndGet {maxOf(it,count)}
                val known=Regex("/known(\\d+)\\.mp4$").find(request.url.encodedPath)?.groupValues?.get(1)?.toInt()
                // A valid MP4 free atom creates genuinely different representation
                // sizes; no padding bytes are counted unless actually transferred.
                val payload=if(known!=null) fixture+java.nio.ByteBuffer.allocate(8+1000*known).apply {putInt(8+1000*known);put("free".toByteArray())}.array() else fixture
                val buffer=Buffer().write(payload)
                val source=object:Source {
                    var closed=false
                    override fun read(sink:Buffer,byteCount:Long):Long {Thread.sleep(35);return buffer.read(sink,minOf(byteCount,512))}
                    override fun timeout()=Timeout.NONE
                    override fun close() {if(!closed) {closed=true;active.decrementAndGet()};buffer.close()}
                }.buffer()
                val body=object:ResponseBody() {override fun contentType()="video/mp4".toMediaTypeOrNull();override fun contentLength()=payload.size.toLong();override fun source()=source}
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Controlled media").header("Content-Length","${payload.size}").header("ETag","\"fixture-v1\"").body(body).build()
            }
        }.build()
        val batches=mutableListOf<String>()
        var model:ZViewModel?=null
        val python=app.extractor.python()
        val module=python.getModule("zextractor")
        val globals=python.getModule("builtins").callAttr("dict")
        globals.callAttr("__setitem__","mediaflow_fixture_bytes",fixture.size)
        python.getModule("builtins").callAttr("exec",instrumentation.context.assets.open("playlist_fixture.py").bufferedReader().use {it.readText()},globals)
        field.set(app.transfer,testClient)
        val results=JSONArray()
        try {
            assertFalse("Acceptance fixture must start without unrelated active work",app.coordinator.running)
            val settings=SettingsRepository(app)
            settings.boolean("wifi",false);settings.text("destination","");settings.text("language","en");settings.integer("concurrency",3)
            compose.activityRule.scenario.onActivity {model=ViewModelProvider(it)[ZViewModel::class.java]}
            val untouched=app.repository.dao.batches().first().size
            compose.runOnIdle {model!!.input("https://www.youtube.com/watch?v=fixture&list=fixture-list");model!!.analyze()}
            compose.onNodeWithTag("playlist_single").assertIsDisplayed()
            compose.onNodeWithTag("playlist_entire").assertIsDisplayed()
            assertEquals(untouched,app.repository.dao.batches().first().size)
            shot("scope-choice")
            compose.runOnIdle {model!!.playlists.close()}
            // Exercise real discovery and selection UI once. No format extraction in preview.
            compose.runOnIdle {model!!.playlists.open("https://mediaflow.fixture.invalid/playlist/five")}
            compose.waitUntil(60000) {model!!.playlists.state.value.page?.items?.size==5}
            shot("preview")
            settings.text("language","ar")
            compose.waitUntil(10000) {compose.onAllNodesWithText("قائمة تشغيل",substring=false).fetchSemanticsNodes().isNotEmpty()}
            shot("preview-ar")
            settings.text("language","en")
            compose.waitUntil(10000) {compose.onAllNodesWithText("Playlist",substring=false).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("playlist_clear").performScrollTo().performClick()
            compose.onNodeWithTag("playlist_selected_count").assertTextContains("0 selected",substring=true)
            compose.onNodeWithTag("playlist_select_all").performClick()
            shot("selection")
            compose.onNodeWithTag("playlist_list").performScrollToNode(hasTestTag("playlist_policy_BEST"))
            compose.onNodeWithTag("playlist_policy_BEST").performClick();shot("quality")
            compose.onNodeWithTag("playlist_list").performScrollToNode(hasTestTag("playlist_advanced"))
            compose.onNodeWithTag("playlist_advanced").assertIsOff()
            compose.onNodeWithTag("playlist_concurrency_3").assertDoesNotExist()
            shot("concurrency-default-sequential")
            val started=SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime()
            firstHttp.set(0)
            val before=app.repository.dao.batches().first().map {it.id}.toSet()
            compose.onNodeWithTag("playlist_download").performClick()
            withTimeout(60000) {while(app.repository.dao.batches().first().none {it.id !in before}) delay(50)}
            val id=app.repository.dao.batches().first().first {it.id !in before}.id;batches+=id
            assertEquals("Sequential is independent of global ordinary-download cap",1,app.repository.dao.batch(id)!!.concurrency)
            withTimeout(60000) {while(app.repository.dao.batchItems(id).none {it.status.startsWith("DOWNLOADING")}) delay(20)}
            compose.waitUntil(30000) {runCatching {compose.onAllNodesWithTag("downloads_grid").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onAllNodes(hasTestTag("playlist_aggregate_progress") and hasAnyAncestor(hasTestTag("playlist_batch_$id"))).assertCountEquals(1)
            for(row in app.repository.dao.batchItems(id)) compose.onNodeWithTag("playlist_child_${row.id}").assertDoesNotExist()
            shotBatch("active",id)
            // Leave MediaFlow while the same scheduled UIDT/WorkManager owns all entries.
            app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if(exerciseScreenOff) {shell("input keyevent KEYCODE_SLEEP");delay(500)}
            val screenOff=exerciseScreenOff&&!app.getSystemService(android.os.PowerManager::class.java).isInteractive
            if(exerciseScreenOff) assertTrue("Screen-off acceptance must actually turn the screen off",screenOff)
            var immediatePublication=false
            val seenPublished=mutableSetOf<String>()
            withTimeout(240000) {
                while(true) {
                    val current=app.repository.dao.batchItems(id)
                    assertTrue("Only one batch item may transfer/process/save at a time",current.count {it.status in setOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","PROCESSING","WAITING_FOR_PROCESSOR","MERGING","CONVERTING","VALIDATING","SAVING")}<=1)
                    val summary=app.repository.dao.batchSummaries().first().first {it.batch.id==id}
                    assertTrue("Retries must not double-count representation bytes",summary.transferred<=fixture.size*5L)
                    if(summary.completed<5) assertNotEquals(100,summary.progress.percent)
                    for(row in current.filter {it.status=="COMPLETED"&&it.id !in seenPublished}) {
                        app.contentResolver.query(Uri.parse(row.uri),arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {c->assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0))}
                        seenPublished+=row.id
                        if(current.any {it.status!="COMPLETED"}) immediatePublication=true
                    }
                    if(current.all {it.status in setOf("COMPLETED","FAILED","CANCELLED")}) break
                    delay(100)
                }
            }
            val rows=app.repository.dao.batchItems(id)
            assertEquals(rows.map {it.error}.toString(),5,rows.count {it.status=="COMPLETED"})
            assertEquals(1,peak.get())
            assertTrue("Each file must appear before the batch ends",immediatePublication)
            for(i in 1 until rows.size) {
                val start=app.repository.dao.events(rows[i].id).first {it.state=="ANALYZING"}.time
                assertTrue("Next item started before prior publication",start>=rows[i-1].finishedAt!!)
            }
            val aggregate=app.repository.dao.batchSummaries().first().first {it.batch.id==id}
            assertEquals(fixture.size*5L,aggregate.transferred)
            assertEquals(fixture.size*5L,aggregate.progress.expected)
            assertEquals(100,aggregate.progress.percent)
            for(row in rows) {
                val uri=Uri.parse(row.uri)
                app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use {c->assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));assertTrue(c.getString(1).startsWith("Movies/MediaFlow/"))}
                val retriever=android.media.MediaMetadataRetriever()
                try {retriever.setDataSource(app,uri);assertNotNull(retriever.getFrameAtTime(500000));assertEquals("96",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))} finally {retriever.release()}
            }
            val elapsed=SystemClock.elapsedRealtime()-started
            results.put(JSONObject().put("scenario","5-item sequential default, temporary503, Activity backgrounded").put("single_parent_bar",true).put("immediate_publication",immediatePublication).put("transfer_bytes",aggregate.transferred).put("expected_transfer_bytes",aggregate.progress.expected).put("screen_off_verified",screenOff).put("first_http_ms",firstHttp.get()-started).put("elapsed_ms",elapsed).put("max_http_transfers",peak.get()).put("completed",5).put("cpu_ms",android.os.Process.getElapsedCpuTime()-cpu).put("pss_kb_sample",Debug.getPss()).put("published_bytes",rows.sumOf {it.outputSize ?: 0}).put("transfer_fixture_bytes",fixture.size))
            if(exerciseScreenOff) {
                File(folder,"playlist-controlled-screen-off.json").writeText(JSONObject().put("state","passed").put("fixture","controlled HTTPS responses; actual installed scheduler/media/publication; not WAN acceptance").put("measurements",results).toString(2))
                return@runBlocking
            }
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            instrumentation.runOnMainSync {compose.activity.startActivity(Intent(activityIntent).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
            compose.waitUntil(30000) {runCatching {compose.onAllNodesWithTag("nav_downloads").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithTag("nav_downloads").performClick()
            compose.waitUntil(30000) {runCatching {compose.onAllNodesWithTag("downloads_grid").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            shotBatch("completed",id)
            withTimeout(30000) {while(app.coordinator.running) delay(50)}
            peak.set(0)
            compose.runOnIdle {model!!.playlists.open("https://mediaflow.fixture.invalid/playlist/known-five")}
            compose.waitUntil(60000) {model!!.playlists.state.value.page?.items?.size==5}
            val knownPage=model!!.playlists.state.value.page!!
            val expected=fixture.size*5L+(1..5).sumOf {8L+1000*it}
            assertEquals(expected,PlaylistPolicy().expected(knownPage.items)?.bytes)
            shot("known-size-preview")
            val priorBatches=app.repository.dao.batches().first().map {it.id}.toSet()
            compose.onNodeWithTag("playlist_download").performClick()
            val knownId=withTimeout(30000) {var result:String?=null;while(result==null) {result=app.repository.dao.batches().first().firstOrNull {it.id !in priorBatches}?.id;if(result==null) delay(30)};checkNotNull(result)}
            batches+=knownId
            withTimeout(60000) {while(app.repository.dao.batchSummaries().first().first {it.batch.id==knownId}.progress.fraction?.let {it>.05f&&it<.9f}!=true) delay(30)}
            compose.waitUntil(10000) {runCatching {compose.onAllNodesWithTag("playlist_batch_$knownId").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            shotBatch("known-size-active",knownId)
            app.playlists.pause(knownId)
            withTimeout(30000) {while(app.repository.dao.batchItems(knownId).any {it.status=="PAUSING"}) delay(30)}
            shotBatch("paused",knownId)
            app.playlists.resume(knownId);app.scheduler.startFromUser()
            // Hold only the final test publication, after four genuine saves,
            // to prove that all network bytes cannot announce batch completion.
            withTimeout(120000) {while(app.repository.dao.batchItems(knownId).count {it.status=="COMPLETED"}<4) delay(30)}
            val publication=app.storage.javaClass.declaredFields.first {it.type==kotlinx.coroutines.sync.Mutex::class.java}.apply {isAccessible=true}.get(app.storage) as kotlinx.coroutines.sync.Mutex
            publication.lock()
            try {
                withTimeout(30000) {while(!app.repository.dao.batchSummaries().first().first {it.batch.id==knownId}.progress.finalizing) delay(30)}
                val pending=app.repository.dao.batchSummaries().first().first {it.batch.id==knownId}
                assertEquals(expected,pending.transferred);assertEquals(4,pending.completed);assertNull(pending.progress.percent)
                compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("playlist_operation_$knownId",useUnmergedTree=true).assertTextEquals("Finalizing playlist…");true}.getOrDefault(false)}
                compose.onAllNodes(hasText("100% downloaded") and hasAnyAncestor(hasTestTag("playlist_batch_$knownId"))).assertCountEquals(0)
                shotBatch("finalizing",knownId)
            } finally {publication.unlock()}
            withTimeout(180000) {
                while(true) {
                    val snapshot=app.repository.dao.batchSummaries().first().first {it.batch.id==knownId}
                    assertEquals(expected,snapshot.progress.expected)
                    assertTrue(snapshot.transferred<=expected)
                    if(snapshot.finished) {assertEquals(5,snapshot.completed);break}
                    delay(80)
                }
            }
            assertEquals(1,peak.get())
            val knownRows=app.repository.dao.batchItems(knownId)
            assertEquals(expected,knownRows.sumOf {it.bytes})
            shotBatch("known-size-completed",knownId)
            results.put(JSONObject().put("scenario","5 unequal known-size representations; sequential pause/resume; one parent").put("transfer_bytes",knownRows.sumOf {it.bytes}).put("expected_transfer_bytes",expected).put("max_http_transfers",peak.get()).put("completed",5).put("no_attempt_byte_double_count",true).put("finalizing_before_last_publication_verified",true))
            suspend fun runCase(label:String,limit:Int,audio:Boolean=false,partial:Boolean=false,controls:Boolean=false) {
                withTimeout(30000) {while(app.coordinator.running) delay(50)}
                peak.set(0);retry.set(0);firstHttp.set(0)
                val url="https://mediaflow.fixture.invalid/playlist/${if(partial) "partial" else "ten"}"
                val discoveryStart=SystemClock.elapsedRealtime()
                val page=PlaylistPage.parse(app.extractor.playlist(url,UUID.randomUUID().toString()),url)
                val discoveryMs=SystemClock.elapsedRealtime()-discoveryStart
                val start=SystemClock.elapsedRealtime();val cpuStart=android.os.Process.getElapsedCpuTime()
                settings.integer("concurrency",limit)
                val batchId=app.playlists.submit(UUID.randomUUID().toString(),page,page.items,PlaylistPolicy(audio,if(audio) "mp3" else "original",fallback=QualityFallback.BEST,bitrate=128),true,limit)
                batches+=batchId
                val maxPss=AtomicLong();val maxNative=AtomicInteger();val maxTemporary=AtomicLong()
                val sampler=launch(Dispatchers.IO) {
                    while(isActive) {
                        maxPss.updateAndGet {maxOf(it,Debug.getPss())}
                        maxNative.updateAndGet {maxOf(it,com.arthenica.ffmpegkit.FFmpegKit.listSessions().count {s->s.state==com.arthenica.ffmpegkit.SessionState.RUNNING})}
                        val bytes=app.repository.dao.batchItems(batchId).sumOf {row->
                            val directory=File(app.filesDir,"transfers/${row.id}")
                            if(directory.exists()) directory.walkTopDown().filter {it.isFile}.sumOf {it.length()} else 0L
                        }
                        maxTemporary.updateAndGet {maxOf(it,bytes)}
                        delay(150)
                    }
                }
                try {
                app.scheduler.startFromUser()
                if(controls) {
                    withTimeout(60000) {while(app.repository.dao.batchItems(batchId).none {it.status.startsWith("DOWNLOADING")}) delay(20)}
                    app.playlists.pause(batchId)
                    withTimeout(30000) {while(app.repository.dao.batchItems(batchId).any {it.status.startsWith("DOWNLOADING")||it.status=="PAUSING"}) delay(20)}
                    assertEquals("PAUSED",app.repository.dao.batch(batchId)!!.state)
                    app.playlists.resume(batchId);app.scheduler.startFromUser()
                }
                withTimeout(240000) {while(app.repository.dao.batchItems(batchId).any {it.status !in setOf("COMPLETED","FAILED","CANCELLED")}) delay(100)}
                val items=app.repository.dao.batchItems(batchId)
                assertEquals(items.map {it.error}.toString(),10,items.count {it.status=="COMPLETED"})
                assertEquals(if(partial) 1 else 0,items.count {it.status=="FAILED"})
                assertTrue("$label exceeded its transfer slots: ${peak.get()}",peak.get()<=(if(limit==0) 3 else limit))
                assertEquals(0,active.get())
                assertTrue("FFmpeg must remain globally serialized",maxNative.get()<=1)
                if(audio) for(item in items) if(item.status=="COMPLETED") {
                    assertEquals("audio/mpeg",app.contentResolver.getType(Uri.parse(item.uri)))
                    val retriever=android.media.MediaMetadataRetriever()
                    try {retriever.setDataSource(app,Uri.parse(item.uri));assertTrue(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)} finally {retriever.release()}
                }
                if(partial) {
                    val completedBefore=items.filter {it.status=="COMPLETED"}.associate {it.id to it.uri}
                    app.playlists.resume(batchId,retryFailed=true);app.scheduler.startFromUser()
                    withTimeout(120000) {while(app.repository.dao.batchItems(batchId).any {it.status !in setOf("COMPLETED","FAILED")}) delay(100)}
                    assertEquals(completedBefore,app.repository.dao.batchItems(batchId).filter {it.status=="COMPLETED"}.associate {it.id to it.uri})
                }
                val elapsed=SystemClock.elapsedRealtime()-start
                val phaseTimes=JSONArray()
                for(item in items.filter {it.status=="COMPLETED"}) {
                    val events=app.repository.dao.events(item.id)
                    val transfer=events.firstOrNull {it.state in setOf("DOWNLOADING_VIDEO","DOWNLOADING_AUDIO")}
                    val processing=events.lastOrNull {it.state in setOf("PROCESSING","MERGING","CONVERTING")}
                    val saving=events.lastOrNull {it.state=="SAVING"}
                    phaseTimes.put(JSONObject().put("position",item.playlistPosition).put("format_ready_ms",transfer?.time?.minus(item.createdAt))
                        .put("processing_and_validation_ms",if(processing!=null&&saving!=null) saving.time-processing.time else JSONObject.NULL)
                        .put("publish_ms",if(saving!=null&&item.finishedAt!=null) item.finishedAt-saving.time else JSONObject.NULL))
                }
                results.put(JSONObject().put("scenario",label).put("concurrency",limit).put("discovery_ms",discoveryMs).put("elapsed_ms",elapsed)
                    .put("first_http_ms",firstHttp.get()-start).put("peak_sampled_pss_kb",maxPss.get()).put("max_ffmpeg_sessions",maxNative.get()).put("peak_sampled_temporary_bytes",maxTemporary.get()).put("item_phases",phaseTimes)
                    .put("cpu_ms",android.os.Process.getElapsedCpuTime()-cpuStart).put("pss_kb_sample",Debug.getPss()).put("max_http_transfers",peak.get())
                    .put("completed",items.count {it.status=="COMPLETED"}).put("failed",items.count {it.status=="FAILED"})
                    .put("output_bytes",items.sumOf {it.outputSize ?: 0}).put("controlled_input_bytes",fixture.size*10)
                    .put("aggregate_transfer_bytes_per_second",fixture.size*10L*1000/elapsed.coerceAtLeast(1)))
                } finally {sampler.cancelAndJoin()}
            }
            for(limit in listOf(1,2,4,0)) runCase("video benchmark",limit)
            runCase("pause/resume then partial result and retry-failed-only",3,partial=true,controls=true)
            runCase("MP3 128kbps batch",2,audio=true)
            withTimeout(30000) {while(app.coordinator.running) delay(50)}
            val cancelUrl="https://mediaflow.fixture.invalid/playlist/ten"
            val cancelPage=PlaylistPage.parse(app.extractor.playlist(cancelUrl,UUID.randomUUID().toString()),cancelUrl)
            val cancelId=app.playlists.submit(UUID.randomUUID().toString(),cancelPage,cancelPage.items,PlaylistPolicy(fallback=QualityFallback.BEST),true,1)
            batches+=cancelId;app.scheduler.startFromUser()
            withTimeout(120000) {while(app.repository.dao.batchItems(cancelId).none {it.status=="COMPLETED"}) delay(50)}
            val preserved=app.repository.dao.batchItems(cancelId).filter {it.status=="COMPLETED"}.associate {it.id to it.uri}
            app.playlists.cancel(cancelId)
            withTimeout(30000) {while(app.repository.dao.batchItems(cancelId).any {app.coordinator.active(it.id)}) delay(50)}
            assertEquals("CANCELLED",app.repository.dao.batch(cancelId)!!.state)
            assertTrue(app.repository.dao.batchItems(cancelId).any {it.status=="CANCELLED"})
            for((key,uri) in preserved) {
                assertEquals("COMPLETED",app.repository.dao.get(key)!!.status)
                app.contentResolver.openFileDescriptor(Uri.parse(uri),"r")!!.use {assertTrue(it.statSize>0)}
            }
            results.put(JSONObject().put("scenario","cancel batch preserves published media").put("preserved",preserved.size).put("cancelled",app.repository.dao.batchItems(cancelId).count {it.status=="CANCELLED"}))
            File(folder,"playlist-controlled-acceptance.json").writeText(JSONObject().put("state","passed").put("fixture","in-process controlled HTTPS responses and installed yt-dlp test extractor; not WAN/platform acceptance").put("measurements",results).toString(2))
        } finally {
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            compose.activityRule.scenario.onActivity {it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            for(id in batches) {
                app.playlists.cancel(id)
                for(row in app.repository.dao.batchItems(id)) {
                    app.coordinator.stopAndAwait(row.id)
                    val current=app.repository.dao.get(row.id)
                    if(current?.status=="COMPLETED") app.storage.deleteFile(current) else app.repository.remove(row.id)
                }
                app.repository.dao.removeEmptyBatch(id)
            }
            // Remove only empty parents from this controlled .invalid fixture,
            // including retained empty parents from earlier failed attempts.
            for(batch in app.repository.dao.batches().first().filter {it.url.startsWith("https://mediaflow.fixture.invalid/")}) app.repository.dao.removeEmptyBatch(batch.id)
            withTimeoutOrNull(30000) {while(app.coordinator.running) delay(100)}
            queueAuditSnapshot("after-controlled-playlists")
            field.set(app.transfer,original)
            python.getModule("builtins").callAttr("exec","zextractor.AnalysisYoutubeDL.add_default_info_extractors = mediaflow_original_registry\nzextractor.analyze = mediaflow_original_analyze",globals)
            app.extractor.clearMetadataCache()
            val settings=SettingsRepository(app)
            settings.integer("concurrency",old.concurrency);settings.boolean("wifi",old.wifiOnly);settings.text("destination",old.destination.orEmpty());settings.text("language",old.language)
        }
    }
}
