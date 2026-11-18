package com.zdownloader.app

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.DownloadRequest
import com.zdownloader.app.playback.PlaybackFavorite
import com.zdownloader.app.playback.PlaybackList
import com.zdownloader.app.playback.PlaybackListItem
import com.zdownloader.app.playback.PlaybackMedia
import com.zdownloader.app.playback.PlaybackRepository
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Correctness regressions from the audit, including cross-feature persistence. */
class ComprehensiveAuditRegressionTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private fun evidence(name:String,value:JSONObject) {
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"audit-$name.json").writeText(value.toString(2))
    }
    @Test fun retryOfSameDurableRequestMustBeIdempotent():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val repo=DownloadRepository(db.downloads(),SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val id=UUID.randomUUID().toString();val url="https://example.com/audit-request"
            val request=DownloadRequest(height=720)
            assertEquals(id,repo.createRequest(id,url,request,null))
            val repeated=runCatching {repo.createRequest(id,url,request,null)}
            evidence("request-retry",JSONObject().put("first_id",id).put("retry_id",repeated.getOrNull())
                .put("retry_exception",repeated.exceptionOrNull()?.javaClass?.simpleName).put("rows",db.downloads().observe().first().size))
            assertEquals("Retrying the same committed operation must not ask to duplicate itself",id,repeated.getOrThrow())
            assertTrue(runCatching {repo.createRequest(id,"https://example.com/other",request,null,allowAgain=true)}.exceptionOrNull() is com.zdownloader.app.domain.MediaException)
            db.downloads().cancel(id)
            val cancelled=runCatching {repo.createRequest(id,url,request,null,allowAgain=true)}.exceptionOrNull() as com.zdownloader.app.domain.MediaException
            assertEquals(com.zdownloader.app.domain.ErrorCode.DOWNLOAD_INTERRUPTED,cancelled.code)
        } finally {db.close()}
    }
    @Test fun replacingShareAfterPersistedFailureMustAllowNewSelection() {
        val saved=SavedStateHandle(mapOf("url" to "https://example.com/old-share","persistedId" to UUID.randomUUID().toString(),
            "qualityPreference" to 720,"requestOperation" to UUID.randomUUID().toString(),"requestJson" to DownloadRequest(height=720).json()))
        val store=ViewModelStore()
        var observed:com.zdownloader.app.ui.QuickShareState?=null
        instrument.runOnMainSync {
            val model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,saved,app.playlists,app.plannedDownloads)
            store.put("audit",model)
            try {
                model.accept("https://example.com/new-share");model.prefer(480)
                observed=model.state.value
            } finally {store.clear()}
        }
        val state=observed!!
        evidence("replacement-share",JSONObject().put("source_url",state.sourceUrl).put("quality",state.qualityPreference)
            .put("persisted_id_retained",state.persistedId!=null).put("can_submit",state.canSubmit))
        assertEquals("A new shared URL must be independently selectable after an old submission failed",480,state.qualityPreference)
        assertNull(state.persistedId)
        assertNull(saved.get<String>("requestOperation"));assertNull(saved.get<String>("requestJson"))
    }
    @Test fun sameNormalizedShareKeepsItsOperationWhileInvalidReplacementResetsIt() {
        val id=UUID.randomUUID().toString();val operation=UUID.randomUUID().toString()
        val saved=SavedStateHandle(mapOf("url" to "https://example.com/retained","persistedId" to id,
            "qualityPreference" to 720,"requestOperation" to operation))
        val states=mutableListOf<com.zdownloader.app.ui.QuickShareState>();val store=ViewModelStore()
        instrument.runOnMainSync {
            val model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,saved,app.playlists,app.plannedDownloads)
            store.put("audit",model)
            try {
                model.accept(" https://example.com/retained ");states+=model.state.value
                model.accept(null);states+=model.state.value
                model.accept("https://example.com/replacement");model.prefer(480);states+=model.state.value
            } finally {store.clear()}
        }
        assertEquals(id,states[0].persistedId);assertEquals(720,states[0].qualityPreference)
        assertNull(states[1].persistedId);assertNull(states[1].sourceUrl)
        assertEquals(com.zdownloader.app.domain.ErrorCode.INVALID_URL,states[1].error)
        assertEquals(480,states[2].qualityPreference);assertTrue(states[2].canSubmit)
    }
    @Test fun restoredCommittedSubmissionRetriesWithoutPreviewMetadata():Unit=runBlocking {
        val id="restored-${UUID.randomUUID()}";val url="https://example.com/$id"
        app.repository.dao.insert(DownloadEntity(id,url,"Restored audit","Audit",null,null,"{}","fixture.mp4",null,id,status="COMPLETED"))
        val saved=SavedStateHandle(mapOf("url" to url,"persistedId" to id))
        val store=ViewModelStore();var model:QuickShareViewModel?=null
        try {
            instrument.runOnMainSync {
                model=QuickShareViewModel(app.extractor,app.repository,SettingsRepository(app),app.scheduler,saved,app.playlists,app.plannedDownloads)
                store.put("audit",model!!);model!!.retry()
            }
            withTimeout(5000) {while(model!!.state.value.queuedId==null) {assertNull(model!!.state.value.error);delay(20)}}
            assertEquals(id,model!!.state.value.queuedId);assertNull(model!!.state.value.media)
            assertEquals("COMPLETED",app.repository.dao.get(id)!!.status)
        } finally {instrument.runOnMainSync {store.clear()};app.repository.dao.removeHistory(id)}
    }
    private suspend fun tree():Uri {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "am start -W -n com.zdownloader.app.test/com.zdownloader.app.TestDocumentsGrantActivity")).bufferedReader().use {it.readText()}
        val tree=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        withTimeout(10000) {while(app.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),3)!=android.content.pm.PackageManager.PERMISSION_GRANTED) delay(25)}
        return tree
    }
    private suspend fun mutateDownload(rename:Boolean) {
        val tree=tree();val id="audit-${UUID.randomUUID()}"
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
        var uri=DocumentsContract.createDocument(app.contentResolver,parent,"audio/mpeg","$id.mp3")!!
        app.contentResolver.openOutputStream(uri)!!.use {it.write(byteArrayOf(1,2,3,4))}
        val old=uri.toString();val library=app.database.playbackLibrary()
        val playback=PlaybackRepository(app);val previousQueue=playback.savedQueue()
        val listId="audit-list-${UUID.randomUUID()}"
        val format=com.zdownloader.app.domain.Format.parse(JSONObject().put("format_id","audit").put("ext","mp3").put("vcodec","none").put("acodec","mp3").put("protocol","https").put("url","https://example.com/audit.mp3"))
        val selection=com.zdownloader.app.domain.Selection(format,audioOnly=true,output="mp3")
        val row=DownloadEntity(id,"https://example.com/$id","Audit only","Audit",null,null,selection.json(),"$id.mp3",tree.toString(),id,status="COMPLETED",uri=old)
        app.repository.dao.insert(row);library.favorite(PlaybackFavorite(old,"Audit only",false,"audio/mpeg",null,null,null,0))
        library.create(PlaybackList(listId,"Audit mutation"))
        library.insert(PlaybackListItem(listId,old,0,"Audit only",false,"audio/mpeg",null,null,null,0))
        playback.saveQueue(listOf(PlaybackMedia(old,"Audit only",false,"audio/mpeg")),0)
        try {
            if(rename) {
                val conflict=DocumentsContract.createDocument(app.contentResolver,parent,"audio/mpeg","$id-renamed.mp3")!!
                try {
                    assertTrue(runCatching {app.storage.rename(row,"$id-renamed")}.exceptionOrNull() is FileNameConflictException)
                    app.contentResolver.openInputStream(uri)!!.use {assertArrayEquals(byteArrayOf(1,2,3,4),it.readBytes())}
                } finally {DocumentsContract.deleteDocument(app.contentResolver,conflict)}
                app.storage.rename(row,"$id-renamed")
                uri=Uri.parse(app.repository.dao.get(id)!!.uri)
            } else app.storage.deleteFile(row)
            val favorites=library.favorites().first().map {it.uri}
            evidence(if(rename) "download-rename" else "download-delete",JSONObject().put("old_uri",old).put("new_uri",uri.toString())
                .put("stale_favorite",old in favorites).put("new_uri_favorite",uri.toString() in favorites))
            if(rename) {
                assertNotEquals("Fixture provider must change the URI on rename",old,uri.toString())
                assertTrue("Download rename must relink the existing favorite",uri.toString() in favorites)
                assertFalse(old in favorites)
                assertEquals(uri.toString(),library.ordered(listId).single().uri)
                assertEquals(uri.toString(),playback.savedQueue().first.single().uri)
            } else {
                assertFalse("Download deletion must remove its now-unplayable favorite",old in favorites)
                assertTrue(library.ordered(listId).isEmpty())
                assertTrue(playback.savedQueue().first.isEmpty())
            }
        } finally {
            library.delete(listId);playback.saveQueue(previousQueue.first,previousQueue.second)
            library.unfavorite(old);library.unfavorite(uri.toString())
            app.repository.dao.removeHistory(id)
            runCatching {DocumentsContract.deleteDocument(app.contentResolver,uri)}
            if(uri.toString()!=old) runCatching {DocumentsContract.deleteDocument(app.contentResolver,Uri.parse(old))}
        }
    }
    @Test fun downloadDeletionMustRemovePlaybackReferences():Unit=runBlocking {mutateDownload(false)}
    @Test fun downloadRenameMustRelinkPlaybackReferences():Unit=runBlocking {mutateDownload(true)}
    private fun web(view:View):WebView? {
        if(view is WebView) return view
        if(view is ViewGroup) repeat(view.childCount) {web(view.getChildAt(it))?.let {found->return found}}
        return null
    }
    private fun js(view:WebView,script:String):String {
        val ready=CountDownLatch(1);var result=""
        instrument.runOnMainSync {view.evaluateJavascript(script) {result=it;ready.countDown()}}
        assertTrue("WebView JS response",ready.await(5,TimeUnit.SECONDS))
        return result
    }
    private fun browser(action:(WebView)->Unit) {
        var view:WebView?=null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                compose.waitUntil(20000) {runCatching {compose.onAllNodesWithTag("home_browser").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
                compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_browser"))
                compose.onNodeWithTag("home_browser").performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithTag("browser_screen").fetchSemanticsNodes().isNotEmpty()}
                scenario.onActivity {view=web(it.window.decorView)}
                assertNotNull(view);action(view!!)
            }
        } finally {view?.let {target->instrument.runOnMainSync {target.stopLoading();target.onPause();target.removeAllViews();target.destroy()}}}
    }
    @Test fun signedDirectMediaMustExposeBrowserDownloadAction() {
        browser {view->
            val url="https://example.com/audit-video.mp4?token=controlled"
            instrument.runOnMainSync {view.loadDataWithBaseURL(url,"<html><body>Controlled media-link page</body></html>","text/html","UTF-8",url)}
            compose.waitUntil(10000) {js(view,"document.readyState")=="\"complete\""}
            compose.waitForIdle();Thread.sleep(500)
            val visible=compose.onAllNodesWithTag("floating_download_button").fetchSemanticsNodes().isNotEmpty()
            evidence("browser-signed-link",JSONObject().put("url",url).put("webview_url",js(view,"location.href")).put("download_action_visible",visible))
            assertTrue("A direct MP4 URL must remain downloadable when it has signed query parameters",visible)
        }
    }
    @Test fun closingBrowserMustStopItsJavascriptWork() {
        browser {view->
            instrument.runOnMainSync {view.loadDataWithBaseURL("https://example.com/audit-browser",
                "<html><script>window.auditTicks=0;window.auditTimer=setInterval(function(){window.auditTicks++},25)</script><body>Lifecycle audit</body></html>","text/html","UTF-8",null)}
            compose.waitUntil(10000) {(js(view,"window.auditTicks||0").toIntOrNull() ?: 0)>2}
            val language=runBlocking {SettingsRepository(app).current().language}
            val localized=app.createConfigurationContext(Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag(language))})
            compose.onNodeWithContentDescription(localized.getString(R.string.close)).performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithTag("browser_screen").fetchSemanticsNodes().isEmpty()}
            fun ticksOrReleased():Int? {
                val ready=CountDownLatch(1);var result:Int?=null
                instrument.runOnMainSync {view.evaluateJavascript("window.auditTicks") {result=it.toIntOrNull();ready.countDown()}}
                ready.await(750,TimeUnit.MILLISECONDS)
                return result
            }
            val before=ticksOrReleased()
            Thread.sleep(1600)
            val after=ticksOrReleased()
            var attached=true;instrument.runOnMainSync {attached=view.isAttachedToWindow}
            evidence("browser-lifecycle",JSONObject().put("attached_after_close",attached).put("ticks_after_close",before).put("ticks_later",after)
                .put("javascript_context_released",before==null&&after==null))
            assertFalse(attached)
            assertTrue("A closed browser must stop page work",before==null&&after==null||before!=null&&after==before)
        }
    }
}
