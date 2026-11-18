package com.zdownloader.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.playback.SmartMediaBrowser
import com.zdownloader.app.playback.MediaBrowserSource
import com.zdownloader.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/** Real components/native dialogs and isolated granted documents; no personal media captures. */
class EliteProductUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val evidence get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    @Before fun wake() {
        for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard")) android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
    }
    private fun awaitRoot() {compose.waitUntil(15000) {runCatching {compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(400)
        val bitmap=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {File(evidence,"elite-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
    private fun render(activity:MainActivity,language:String="en",fontScale:Float?=null,content:@Composable ()->Unit) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.setContent {
            val original=LocalConfiguration.current
            val config=remember(original,language,fontScale) {Configuration(original).apply {setLocale(Locale.forLanguageTag(language));setLayoutDirection(Locale.forLanguageTag(language));if(fontScale!=null) this.fontScale=fontScale}}
            val configured=remember(config) {activity.createConfigurationContext(config)}
            val context=remember(config) {object:android.content.ContextWrapper(activity) {override fun getResources()=configured.resources;override fun getAssets()=configured.assets}}
            val density=LocalDensity.current
            CompositionLocalProvider(LocalContext provides context,LocalSpectrumContext provides context,LocalConfiguration provides config,
                LocalDensity provides Density(density.density,fontScale ?: density.fontScale),LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FlowTheme(Spectrum.Dark) {FlowScreen(content)}
            }
        }
    }
    @Test fun artistToVideoSwitchReturnsToAVisibleSupportedView() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {render(it) {SmartMediaBrowser(app.playback,MediaBrowserSource.Device)}}
            awaitRoot()
            compose.onNodeWithText("Audio").performClick()
            compose.selectLibraryMenuOption("library_view_menu","music_artists")
            compose.assertLibraryMenuSelection("library_view_menu","music_artists")
            compose.onNodeWithText("Video").performClick()
            capture("video-after-artist-mode")
            compose.assertLibraryMenuSelection("library_view_menu","music_files")
        }
    }
    @Test fun librarySearchHasASearchActionAndDismissesTheKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {render(it) {SmartMediaBrowser(app.playback,MediaBrowserSource.Downloads(emptyList()))}}
            awaitRoot()
            compose.onNodeWithTag("library_search").performTextInput("Elite-no-match-${UUID.randomUUID()}")
            capture("library-search-editing")
            compose.onNodeWithTag("library_search").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction,ImeAction.Search))
            compose.onNodeWithTag("library_search").performImeAction()
            compose.onNodeWithTag("library_search").assertIsNotFocused()
            val empty=hasText("No matching media")
            compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("local_library_list").performScrollToNode(empty);true}.getOrDefault(false)}
            compose.onNode(empty).assertIsDisplayed()
            capture("library-empty-search")
        }
    }
    @Test fun largeFontNavigationKeepsTheSelectedLabelOnOneLine() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            // Use the actual compact device viewport and app component. Avoid
            // requesting test-only Foundation default methods stripped from release.
            scenario.onActivity {render(it,fontScale=2f) {PremiumBottomNavigation("downloads") {}}}
            awaitRoot()
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Downloads",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {assertTrue(it(layouts))}
            capture("navigation-en-compact-font2")
            File(evidence,"elite-navigation-large-font-layout.json").writeText(JSONObject().put("line_count",layouts.single().lineCount)
                .put("width_px",layouts.single().size.width).put("height_px",layouts.single().size.height)
                .put("overflow_width",layouts.single().didOverflowWidth).put("overflow_height",layouts.single().didOverflowHeight).toString(2))
            assertEquals("Large-font navigation must not break Downloads inside the word",1,layouts.single().lineCount)
            assertFalse("The selected destination must fit at this compact 2x font viewport",layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("nav_home").assertHasClickAction();compose.onNodeWithTag("nav_library").assertHasClickAction()
        }
    }
    @Test fun quickShareNativeDateAndTimePickersFollowTheChosenAppLanguage():Unit=runBlocking {
        val settings=SettingsRepository(app);val original=settings.current()
        val baseLanguage=app.resources.configuration.locales[0].language
        val language=if(baseLanguage=="en") "ar" else "en"
        val config=Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag(language))}
        val expected=app.createConfigurationContext(config)
        val url=com.zdownloader.app.domain.UrlPolicy.normalize("https://www.youtube.com/watch?v=EliteLocaleFixture")
        val format=com.zdownloader.app.domain.Format.parse(JSONObject().put("format_id","fixture").put("url","https://example.com/elite-ui.mp4").put("ext","mp4").put("height",720).put("width",1280).put("vcodec","h264").put("acodec","aac"))
        val media=com.zdownloader.app.domain.Media(url,"elite-locale","Elite controlled UI metadata","Fixture",null,null,12.0,listOf(format))
        // UI-only fixture in the real interactive metadata cache. No network or
        // transfer is claimed; this isolates dialog locale from WAN availability.
        app.extractor.clearMetadataCache()
        val cached=app.extractor.javaClass.declaredClasses.flatMap {it.declaredConstructors.toList()}.first {it.parameterTypes.contentEquals(arrayOf(com.zdownloader.app.domain.Media::class.java,java.lang.Long.TYPE))}.apply {isAccessible=true}
        val cache=app.extractor.javaClass.declaredFields.first {java.util.Map::class.java.isAssignableFrom(it.type)}.apply {isAccessible=true}.get(app.extractor) as MutableMap<String,Any>
        val network=(app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetwork?.networkHandle
        cache["$url|$network|false|false"]=cached.newInstance(media,android.os.SystemClock.elapsedRealtime())
        try {
            settings.text("language",language)
            settings.text("download_type","video")
            val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,url)
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                var model:QuickShareViewModel?=null
                scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
                awaitRoot()
                compose.waitUntil(120000) {val state=model!!.state.value;!state.analyzing&&(state.selection!=null||state.error!=null)}
                assertNotNull("Controlled cached metadata must load before checking its dialogs: ${model!!.state.value.error}",model!!.state.value.selection)
                compose.onNodeWithTag("share_more").performScrollTo().performClick()
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_schedule"))
                compose.onNodeWithTag("share_schedule").performClick()
                for((label,name) in listOf(R.string.schedule_date to "date",R.string.schedule_time to "time")) {
                    compose.onNodeWithText(expected.getString(label)).performClick()
                    instrument.waitForIdleSync();Thread.sleep(350)
                    val nodes=instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1").orEmpty()
                    assertTrue("Native $name picker must expose its positive button",nodes.isNotEmpty())
                    val actual=nodes.first().text?.toString()
                    File(evidence,"elite-$name-picker-locale.json").writeText(JSONObject().put("metadata_source","isolated interactive-cache UI fixture; no WAN transfer").put("base_locale",baseLanguage).put("chosen_locale",language).put("positive_text",actual).put("expected",expected.getString(android.R.string.ok)).toString(2))
                    capture("share-$name-picker-$language")
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    assertEquals("Native $name picker must use the app's chosen locale",expected.getString(android.R.string.ok),actual)
                }
            }
        } finally {app.extractor.clearMetadataCache();settings.text("language",original.language);settings.text("download_type",original.downloadType)}
    }
    @Test fun primaryTabsRestoreAndSettingsHasNoPrimaryNavigation():Unit=runBlocking {
        val settings=SettingsRepository(app);val original=settings.current()
        try {
            settings.text("language","en")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                awaitRoot()
                compose.onNodeWithTag("nav_home").assertIsSelected();capture("navigation-home-selected")
                compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected();capture("navigation-downloads-selected")
                compose.onNodeWithTag("nav_library").performClick().assertIsSelected();capture("navigation-library-selected")
                compose.onNodeWithTag("nav_home").performClick()
                compose.onNodeWithTag("open_settings").performClick();capture("settings-secondary-navigation")
                compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_zd"))
                compose.onNodeWithTag("about_zd").performClick();capture("about-secondary-navigation")
                compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                compose.onNodeWithTag("privacy_information").performScrollTo().performClick();capture("privacy-secondary-navigation")
                compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                compose.onNodeWithTag("information_back").performClick()
                compose.onNodeWithTag("information_back").performClick()
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                compose.onNodeWithTag("nav_home").assertIsSelected()
                repeat(4) {for(tab in listOf("downloads","library","home")) compose.onNodeWithTag("nav_$tab").performClick().assertIsSelected()}
                capture("navigation-after-repeated-tabs")
                settings.text("language","ar")
                for(tab in listOf("home","downloads","library")) {
                    compose.onNodeWithTag("nav_$tab").performClick().assertIsSelected()
                    capture("navigation-$tab-selected-ar")
                }
            }
        } finally {settings.text("language",original.language)}
    }
    @Test fun secondaryHierarchyReturnsToTheActualPreviousPrimaryTab():Unit=runBlocking {
        val settings=SettingsRepository(app);val original=settings.current()
        try {
            settings.text("language","en")
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                var controller:androidx.navigation.NavHostController?=null
                scenario.onActivity {activity->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val model=ViewModelProvider(activity)[ZViewModel::class.java]
                    activity.setContent {val actual=rememberMediaFlowController();SideEffect {controller=actual};ZRoot(model,actual)}
                }
                awaitRoot();compose.waitUntil(10000) {controller!=null}
                for(tab in listOf("downloads","library")) {
                    compose.onNodeWithTag("nav_$tab").performClick().assertIsSelected()
                    compose.runOnIdle {controller!!.openSecondary(SecondaryDestination.SETTINGS)}
                    compose.onNodeWithTag("settings_list").assertIsDisplayed()
                    compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
                    capture("settings-from-$tab")
                    compose.onNodeWithTag("settings_back").performClick()
                    compose.onNodeWithTag("nav_$tab").assertIsSelected()
                }
                compose.runOnIdle {repeat(8) {controller!!.selectPrimary(PrimaryDestination.DOWNLOADS)}}
                compose.onNodeWithTag("nav_downloads").assertIsSelected()
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                compose.onNodeWithTag("nav_home").assertIsSelected()
                File(evidence,"elite-navigation-hierarchy.json").writeText(JSONObject().put("settings_returns_to_downloads",true).put("settings_returns_to_library",true).put("repeated_primary_events_single_back_to_home",true).toString(2))
            }
        } finally {settings.text("language",original.language)}
    }
    @Test fun primaryLibrarySearchRestoresAfterRecreationAndBackground():Unit=runBlocking {
        val settings=SettingsRepository(app);val original=settings.current()
        val preferences=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE)
        val oldSource=preferences.getString("library_source",null)
        try {
            settings.text("language","en");preferences.edit().putString("library_source","downloads").commit()
            val launch=Intent.makeMainActivity(android.content.ComponentName(app,MainActivity::class.java))
            ActivityScenario.launch<MainActivity>(launch).use {scenario->
                scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                awaitRoot();compose.onNodeWithTag("nav_library").performClick()
                val query="Elite-state-${UUID.randomUUID()}"
                compose.onNodeWithTag("library_search").performTextInput(query)
                compose.onNodeWithTag("library_search").performImeAction()
                compose.onNodeWithTag("library_search").assertIsNotFocused()
                compose.waitUntil(10000) {var visible=true;scenario.onActivity {visible=it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true};!visible}
                instrument.uiAutomation.waitForIdle(350,5000)
                compose.onNodeWithTag("nav_downloads").assertIsDisplayed().performClick().assertIsSelected()
                compose.onNodeWithTag("nav_library").assertIsDisplayed().performClick().assertIsSelected()
                compose.onNodeWithTag("library_search").assertTextContains(query)
                scenario.recreate();awaitRoot()
                compose.onNodeWithTag("nav_library").assertIsSelected()
                compose.onNodeWithTag("library_search").assertTextContains(query)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use {it.readBytes()}
                compose.waitUntil(10000) {scenario.state!=androidx.lifecycle.Lifecycle.State.RESUMED}
                // Model a user launcher activation on API35, preserving the exact
                // launch Intent filter so ActivityScenario observes the lifecycle.
                val resumed=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.zdownloader.app/.MainActivity -f 0x10020000")).bufferedReader().use {it.readText()}
                assertFalse(resumed,resumed.contains("Exception")||resumed.contains("Error:"));awaitRoot()
                compose.onNodeWithTag("nav_library").assertIsSelected()
                compose.onNodeWithTag("library_search").assertTextContains(query)
                capture("library-state-after-recreation-and-resume")
                File(evidence,"elite-navigation-restoration.json").writeText(JSONObject().put("tab_and_query_after_switch",true).put("activity_recreation",true).put("background_foreground",true).toString(2))
            }
        } finally {settings.text("language",original.language);preferences.edit().apply {if(oldSource==null) remove("library_source") else putString("library_source",oldSource)}.commit()}
    }
    @Test fun grantedFolderFiltersMediaTypeAndCountsOnlySelectableFiles():Unit=runBlocking {
        val resolver=app.contentResolver
        val root=DocumentsContract.buildTreeDocumentUri("com.zdownloader.app.test.documents","root")
        val prefs=app.getSharedPreferences("mediaflow_file_manager",Context.MODE_PRIVATE)
        val old=prefs.getStringSet("trees",emptySet()).orEmpty().toSet()
        val owned=mutableListOf<Uri>();var folder:Uri?=null
        val prefix="Elite-${UUID.randomUUID().toString().take(8)}"
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
          try {
            scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            app.startActivity(Intent().setComponent(android.content.ComponentName("com.zdownloader.app.test","com.zdownloader.app.TestDocumentsGrantActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(10000) {while(app.checkUriPermission(root,android.os.Process.myPid(),android.os.Process.myUid(),3)!=android.content.pm.PackageManager.PERMISSION_GRANTED) delay(25)}
            compose.waitUntil(10000) {var focused=false;scenario.onActivity {focused=it.hasWindowFocus()};focused}
            folder=DocumentsContract.createDocument(resolver,DocumentsContract.buildDocumentUriUsingTree(root,"root"),DocumentsContract.Document.MIME_TYPE_DIR,prefix)!!
            val tree=DocumentsContract.buildTreeDocumentUri(root.authority!!,DocumentsContract.getDocumentId(folder!!))
            resolver.call(root,"grantTree",DocumentsContract.getDocumentId(folder!!),null)
            for(suffix in listOf("A","B")) {
                val uri=DocumentsContract.createDocument(resolver,folder!!,"video/mp4","$prefix Video $suffix.mp4")!!;owned+=uri
                instrument.context.assets.open("status-original.mp4").use {source->resolver.openOutputStream(uri,"w")!!.use {source.copyTo(it)}}
            }
            val audio=DocumentsContract.createDocument(resolver,folder!!,"audio/wav","$prefix Audio.wav")!!;owned+=audio
            val bytes=ByteArray(44100*2);val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(bytes.size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(bytes.size)}.array()
            resolver.openOutputStream(audio,"w")!!.use {it.write(header);it.write(bytes)}
            owned+=DocumentsContract.createDocument(resolver,folder!!,DocumentsContract.Document.MIME_TYPE_DIR,"$prefix Child folder")!!
            prefs.edit().putStringSet("trees",setOf(tree.toString())).commit()
            scenario.onActivity {render(it) {SmartMediaBrowser(app.playback,MediaBrowserSource.Device)}}
            awaitRoot()
            compose.onNodeWithText("Video").performClick()
            compose.selectLibraryMenuOption("library_view_menu","music_folders")
            capture("saf-folder-bookmark")
            compose.onNode(hasText(prefix) or hasText(folderLabel(tree.toString())),useUnmergedTree=true).performScrollTo().performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("local_media_${owned.first().lastPathSegment}").fetchSemanticsNodes().isNotEmpty()}
            capture("saf-video-files")
            compose.onNodeWithTag("fm_select_all").performScrollTo().performClick()
            capture("saf-video-selection")
            compose.onNodeWithTag("fm_select_all").assertTextEquals("Select shown (2)")
            compose.onAllNodesWithText("$prefix Audio.wav").assertCountEquals(0)
            compose.onNodeWithText("2 selected").assertIsDisplayed()
          } finally {
            prefs.edit().putStringSet("trees",old).commit()
            owned.reversed().forEach {runCatching {DocumentsContract.deleteDocument(resolver,it)}}
            folder?.let {runCatching {DocumentsContract.deleteDocument(resolver,it)}}
          }
        }
    }
}
