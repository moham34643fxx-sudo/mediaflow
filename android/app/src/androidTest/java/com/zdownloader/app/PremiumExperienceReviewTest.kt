package com.zdownloader.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.status.*
import com.zdownloader.app.ui.*
import com.zdownloader.app.updates.*
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Callback-only design fixtures. The dedicated runner installs a separate app ID. */
class PremiumExperienceReviewTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    @Before fun debugHost() {
        assumeTrue("The UI test host is debug-only",BuildConfig.DEBUG)
    }

    @Composable private fun Theme(language:String="ar",light:Boolean=false,scale:Float=1f,content:@Composable ()->Unit) {
        val base=LocalContext.current
        val config=Configuration(LocalConfiguration.current).apply {fontScale=scale;setLocale(Locale(language));setLayoutDirection(Locale(language))}
        val localized=remember(base,language,scale) {
            val configured=base.createConfigurationContext(config)
            object:android.content.ContextWrapper(base) {
                override fun getResources()=configured.resources
                override fun getAssets()=configured.assets
            }
        }
        val density=LocalDensity.current
        CompositionLocalProvider(LocalContext provides localized,LocalSpectrumContext provides localized,LocalConfiguration provides config,
            LocalDensity provides Density(density.density,scale),LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
            FlowTheme(if(light) Spectrum.Light else Spectrum.Dark) {
                CompositionLocalProvider(LocalFlowMotionEnabled provides false) {FlowScreen(content)}
            }
        }
    }
    private fun host(language:String="ar",light:Boolean=false,scale:Float=1f,block: @Composable ()->Unit):ActivityScenario<ComponentActivity> =
        ActivityScenario.launch(ComponentActivity::class.java).also {scenario->scenario.onActivity {activity->activity.setContent {Theme(language,light,scale,block)}}}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(300)
        val folder=File(context.getExternalFilesDir(null),"premium-review").apply {mkdirs()}
        val image=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
    }
    private val release=UpdateRelease("com.zdownloader.app","qa",55,"0.9.22",24,null,
        mapOf("ar" to "• نافذة تحديث بتصميم جديد.\n• قوائم تشغيل أكثر تنظيمًا.\n• بحث أسرع داخل ملفات القائمة.",
            "en" to "• A refined update experience.\n• Redesigned playlists.\n• Search within playlist files."),emptyList(),null)
    private val policy=PlaylistPolicy(audio=true,output="mp3")
    private fun file(id:String,title:String,status:String,position:Int)=DownloadEntity(id,"https://example.com/$id",title,"Fixture",null,90.0,"","$id.mp3",null,id,
        status=status,bytes=if(status=="COMPLETED") 2_097_152 else if(status=="DOWNLOADING") 1_048_576 else 0,total=2_097_152,speed=if(status=="DOWNLOADING") 102_400 else 0,
        outputSize=2_097_152L.takeIf {status=="COMPLETED"},batchId="design",playlistPosition=position,relativeFolder="Music/MediaFlow/Quiet collection")
    private val files get()=listOf(file("saved","لحظات هادئة — الجزء الأول","COMPLETED",1),file("active","رحلة قصيرة مع الموسيقى","DOWNLOADING",2),
        file("paused","مساحة للتأمل","PAUSED",3),file("failed","صباح مختلف","FAILED",4),file("queued","نهاية يوم جميل","QUEUED",5))
    private val summary get()=BatchSummary(PlaylistBatch("design","fixture","https://example.com/list","مختاراتك في مكان واحد",policy.json()),
        5,1,1,0,1,1,0,0,"ANY",false,3_145_728,10_485_760.0,5,0,1,"رحلة قصيرة مع الموسيقى","DOWNLOADING")
    private fun actions(play:(DownloadEntity)->Unit={},resume:(String)->Unit={})=PlaylistDetailActions(play,{}, {},resume,{}, {},{}, {},{}, {})

    @Test fun arabicLargeTextOfferShowsActualNotesAndLaterDoesNotDownload() {
        var shown by mutableStateOf(true);var downloads=0
        host(scale=1.6f) {if(shown) UpdateReleaseDialog(release,"ar",56_000_000,{shown=false},{downloads++})}.use {
            compose.onNodeWithTag("update_offer_now").assertIsDisplayed()
            compose.onNodeWithTag("update_offer_later").assertIsDisplayed()
            compose.onNodeWithTag("update_notes").assertExists()
            capture("ar-update-offer-font160")
            compose.onNodeWithTag("update_offer_later").performClick()
            compose.onNodeWithTag("update_offer").assertDoesNotExist();assertEquals(0,downloads)
        }
    }
    @Test fun updateNowLeadsToProgressAndPauseResumeRemainAvailable() {
        var shown by mutableStateOf(true)
        var state by mutableStateOf(UpdateState(phase=UpdatePhase.AVAILABLE,release=release,asset=UpdateAsset("arm64-v8a","https://example.com/fixture","a".repeat(64),1000),total=1000))
        var downloads=0
        host("en",light=true) {
            UpdateSettingsContent(state,"en",Modifier.padding(horizontal=20.dp),{}, {downloads++;state=state.copy(phase=UpdatePhase.DOWNLOADING)},
                {state=state.copy(phase=UpdatePhase.PAUSED)}, {},onRetry={})
            if(shown) UpdateReleaseDialog(release,"en",56_000_000, {shown=false},{shown=false;downloads++;state=state.copy(phase=UpdatePhase.DOWNLOADING,downloaded=500)})
        }.use {
            capture("en-update-offer-light")
            compose.onNodeWithTag("update_offer_now").performClick()
            compose.onNodeWithTag("update_offer").assertDoesNotExist()
            compose.onNodeWithTag("update_progress").assertExists()
            compose.onNodeWithTag("update_pause").assertIsDisplayed().performClick()
            compose.onNodeWithTag("update_download").assertIsDisplayed().performClick()
            assertEquals(2,downloads);capture("en-update-progress-light")
        }
    }
    @Test fun settingsCheckActionIsAtTheBottomAndTechnicalCopyIsAbsent() {
        var state by mutableStateOf(UpdateState(phase=UpdatePhase.CURRENT))
        host("en",light=true,scale=1.6f) {
            UpdateSettingsContent(state,"en",Modifier.padding(horizontal=20.dp),{state=state.copy(phase=UpdatePhase.CHECKING)}, {},{},{},onRetry={})
        }.use {
            val button=compose.onNodeWithTag("update_check").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val overview=compose.onNodeWithTag("update_overview").fetchSemanticsNode().boundsInRoot
            assertTrue("The action belongs below the overview",button.top>=overview.bottom)
            compose.onNodeWithText("QA channel · same-signature updates").assertDoesNotExist()
            compose.onNodeWithText("Your settings, library and downloads are kept.",substring=true).assertDoesNotExist()
            capture("en-update-settings-font160")
            compose.onNodeWithTag("update_check").performClick().assertIsNotEnabled()
            compose.onNodeWithTag("update_status").assertTextEquals("Checking for updates…")
        }
    }
    @Test fun playlistSearchAndPausedFilterPreserveOrderAndOnlyActOnTheChosenFile() {
        var resumed:String?=null
        var dismissKeyboard:()->Unit={}
        val rows=files;val sizes=rows.associate {it.id to MediaSize(2_097_152,false)}
        host {
            val keyboard=LocalSoftwareKeyboardController.current
            val focus=LocalFocusManager.current
            SideEffect {dismissKeyboard={keyboard?.hide();focus.clearFocus()}}
            PlaylistDetailContent("design",summary,rows,policy,sizes,SizeCoverage.of(sizes.values.toList()),actions(resume={resumed=it}),{})
        }.use {
            capture("ar-playlist-overview")
            compose.onNodeWithTag("playlist_detail_list").performScrollToNode(hasTestTag("playlist_search"))
            compose.onNodeWithTag("playlist_search").performTextInput("للتأمل")
            compose.waitForIdle()
            // Global Back may finish the host if the IME has not appeared yet.
            // Dismiss only the test field's keyboard, never navigate the phone.
            compose.runOnIdle {dismissKeyboard()}
            compose.waitForIdle()
            val pausedFilter=compose.onNodeWithTag("playlist_items_filter_paused").performScrollTo()
            // Exercise the chip's accessibility click directly: an IME closing
            // animation can otherwise consume the injected screen coordinate tap.
            pausedFilter.performSemanticsAction(SemanticsActions.OnClick) {it()}
            pausedFilter.assertIsSelected()
            compose.onNodeWithTag("playlist_detail_list").performScrollToNode(hasTestTag("playlist_item_paused"))
            compose.onNodeWithTag("playlist_item_paused").assertExists()
            compose.onNodeWithTag("playlist_item_saved").assertDoesNotExist();assertNull(resumed)
            compose.onNodeWithTag("playlist_toggle_paused").performScrollTo().performClick()
            compose.onNodeWithTag("playlist_resume_paused").performScrollTo().performClick()
            assertEquals("paused",resumed)
            capture("ar-playlist-search-details")
        }
    }
    @Test fun fileDetailsAndPlaybackStayDistinctWithLargeArabicText() {
        var played:String?=null
        val saved=files.first()
        host(scale=1.6f) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                PlaylistFileCard(saved,1,policy,null,MediaSize(2_097_152,false),actions(play={played=it.id}))
            }
        }.use {
            compose.onNodeWithTag("playlist_file_details_saved",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithTag("playlist_play_saved").performClick();assertEquals("saved",played)
            compose.onNodeWithTag("playlist_toggle_saved").performScrollTo().performClick()
            compose.onNodeWithTag("playlist_file_details_saved",useUnmergedTree=true).assertExists()
            capture("ar-playlist-file-font160")
        }
    }
    @Test fun collectionCardOpensDetailsWithoutStartingAQueueAction() {
        var opened=0;var queueActions=0
        host("en",light=true) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                PlaylistBatchCard(summary,{queueActions++},{queueActions++},{queueActions++},{queueActions++},{opened++})
            }
        }.use {
            capture("en-playlist-card-light")
            compose.onNodeWithTag("playlist_batch_details").performClick();assertEquals(1,opened);assertEquals(0,queueActions)
        }
    }
    @Test fun automaticStatusUrisCannotWriteOrDeleteOriginalMedia() {
        val authority="${context.packageName}.status.sources"
        val provider=context.packageManager.resolveContentProvider(authority,0)
        assertNotNull(provider);assertFalse(provider!!.exported)
        val file=File(android.os.Environment.getExternalStorageDirectory(),"WhatsApp/Media/.Statuses/mediaflow-uncreated-${java.util.UUID.randomUUID()}.jpg")
        val uri=androidx.core.content.FileProvider.getUriForFile(context,authority,file)
        try {context.contentResolver.openFileDescriptor(uri,"w");fail("Status sources must not be writable")}
        catch(error:java.io.FileNotFoundException) {assertTrue(error.message.orEmpty().contains("Read-only"))}
        try {context.contentResolver.delete(uri,null,null);fail("Status sources must not be deletable")}
        catch(_:UnsupportedOperationException) { }
        assertFalse(file.exists())
    }
    @Test fun firstStatusEntryUsesSystemConsentWithoutAnAccessCancellationLoop() {
        assumeTrue("Run consent review only in the isolated QA package",context.packageName=="com.zdownloader.app.candidate0965")
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model:StatusSaverViewModel
            scenario.onActivity {activity->
                model=ViewModelProvider(activity)[StatusSaverViewModel::class.java]
                activity.setContent {Theme {StatusSaverScreen(model,{})}}
            }
            compose.waitForIdle()
            val until=System.currentTimeMillis()+20000
            var systemConsent=false
            while(System.currentTimeMillis()<until) {
                val owner=instrument.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                if(owner.isNotBlank()&&owner!=context.packageName&&owner!="com.android.systemui") {systemConsent=true;break}
                if(!model.state.value.loading&&model.state.value.problem==null) break
                if(!model.state.value.loading&&!model.permissions.installed(model.state.value.source)) break
                Thread.sleep(200)
            }
            if(systemConsent) {
                repeat(10) {
                    if(instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()!=context.packageName) {
                        instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(250)
                    }
                }
                Thread.sleep(6000)
                assertEquals("Cancelling access must not reopen the system picker",context.packageName,instrument.uiAutomation.rootInActiveWindow?.packageName?.toString())
                assertNull(model.permissions.configured(model.state.value.source))
                compose.onNodeWithTag("status_choose_folder").assertExists()
                capture("ar-status-access-after-cancel")
            } else {
                assertFalse(model.state.value.loading)
                assertTrue("Either media is readable or this source is not installed",model.state.value.problem==null||!model.permissions.installed(model.state.value.source))
            }
            val folder=File(context.getExternalFilesDir(null),"premium-review").apply {mkdirs()}
            File(folder,"status-access.json").writeText(org.json.JSONObject().put("system_consent_opened",systemConsent)
                .put("source_installed",model.permissions.installed(model.state.value.source)).put("no_cancellation_loop",true).toString(2))
        }
    }
}
