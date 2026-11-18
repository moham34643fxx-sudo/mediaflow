package com.zdownloader.app

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.FrameMetrics
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Media
import com.zdownloader.app.playback.DownloadedMediaLibrary
import com.zdownloader.app.playback.PlaybackMedia
import com.zdownloader.app.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.Collections

/** Native renders of production components with owned files and no download-history insertion. */
class UiUxVisualAuditTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private lateinit var scenario:ActivityScenario<MainActivity>
    private lateinit var home:File
    private lateinit var preserved:Map<String,String>
    private val owned=mutableListOf<Uri>()
    private val favoriteFixtures=mutableSetOf<String>()
    private val rows=mutableListOf<DownloadEntity>()
    private val layouts=JSONArray()
    private val evidence:File get() {
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun","uiux-manual")
        require(run.matches(Regex("[a-zA-Z0-9_-]+")))
        return File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
    }

    private fun digest(value:String)=MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") {"%02x".format(it)}

    private fun snapshot():Map<String,String> = runBlocking {
        val library=app.playback.repository.library
        mapOf(
            "downloads" to digest(app.repository.dao.observe().first().sortedBy {it.id}.toString()),
            "favorites" to digest(library.favorites().first().sortedBy {it.uri}.toString()),
            "playlists" to digest(library.playlists().first().sortedBy {it.id}.map {
                it to library.ordered(it.id)
            }.toString()),
            "settings" to digest(SettingsRepository(app).current().toString())
        )
    }

    @Before fun prepare() {
        check(app.packageName=="com.zdownloader.app.candidate0965")
        check(app.getSystemService(android.os.PowerManager::class.java).isInteractive)
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        preserved=snapshot()
        home=File(app.cacheDir,"uiux-${UUID.randomUUID()}")
        check(!home.exists()&&home.mkdirs())
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }

    @After fun finish() {
        if(::scenario.isInitialized) scenario.close()
        runBlocking {favoriteFixtures.forEach {app.playback.repository.library.unfavorite(it)}}
        val removed=owned.sumOf {app.contentResolver.delete(it,null,null)}
        assertEquals("Every owned MediaStore fixture must be removed",owned.size,removed)
        if(::home.isInitialized) {
            home.listFiles().orEmpty().forEach {check(it.isFile&&it.delete())}
            check(home.delete())
        }
        if(!::preserved.isInitialized) return
        val after=snapshot()
        File(evidence,"preservation-${UUID.randomUUID()}.json").writeText(JSONObject()
            .put("before_sha256",JSONObject(preserved)).put("after_sha256",JSONObject(after))
            .put("existing_records_and_settings_unchanged",preserved==after)
            .put("owned_media_removed",removed).put("favorite_fixture_rows_removed",favoriteFixtures.size)
            .put("download_fixture_rows_inserted",0).toString(2))
        assertEquals("Visual review must not change existing records or settings",preserved,after)
    }

    private fun fixture(name:String,mime:String,bytes:ByteArray,duration:Double?=null) {
        val collection=when {
            mime.startsWith("video/")->MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/")->MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else->MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri=checkNotNull(app.contentResolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,when {
                mime.startsWith("video/")->"Movies/MediaFlow-UIUX-${home.name}"
                mime.startsWith("audio/")->"Music/MediaFlow-UIUX-${home.name}"
                else->"Pictures/MediaFlow-UIUX-${home.name}"
            })
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        owned+=uri
        app.contentResolver.openOutputStream(uri,"w")!!.use {it.write(bytes)}
        app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        rows+=DownloadEntity("uiux-${rows.size}","https://uiux.invalid/${rows.size}",name,"UI review fixture",
            null,duration,"{}",name,null,uri.toString(),status="COMPLETED",uri=uri.toString(),outputSize=bytes.size.toLong())
    }

    private fun mediaFixtures() {
        val video=instrument.context.assets.open("status-original.mp4").use {it.readBytes()}
        fixture("رحلة إلى الساحل · Summer coast — 320x240 AVC AAC — نسخة نهائية طويلة للتقييم.mp4","video/mp4",video,12.0)
        fixture("Pond ducks — an unusually long English original camera recording filename.mp4","video/mp4",video,12.0)
        val pcm=ByteBuffer.allocate(48000*5*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000*5) {pcm.putShort((kotlin.math.sin(2*Math.PI*440*it/48000)*1200).toInt().toShort())}
        val wav=ByteBuffer.allocate(44+pcm.capacity()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(pcm.capacity()+36);put("WAVEfmt ".toByteArray())
            putInt(16);putShort(1);putShort(1);putInt(48000);putInt(96000);putShort(2);putShort(16)
            put("data".toByteArray());putInt(pcm.capacity());put(pcm.array())
        }.array()
        fixture("جلسة هادئة · Evening session — original recording.wav","audio/wav",wav,5.0)
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.rgb(24,72,66))
        val paint=android.graphics.Paint().apply {color=Color.rgb(145,227,209)}
        canvas.drawCircle(160f,120f,72f,paint)
        val image=java.io.ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()}
        bitmap.recycle()
        fixture("دليل الألوان · MediaFlow UI review.png","image/png",image)
        val favoriteUri=owned.first().toString()
        favoriteFixtures+=favoriteUri
        runBlocking {app.playback.repository.favorite(
            PlaybackMedia(favoriteUri,rows.first().filename,video=true,mime="video/mp4",duration=12_000L),true
        )}
    }

    private fun render(language:String="en",dark:Boolean=true,width:Int=360,scale:Float=1f,content:@Composable ()->Unit) {
        val renderId=UUID.randomUUID().toString()
        scenario.onActivity {activity->
            activity.applySpectrumBars(dark)
            activity.setContent {key(renderId) {
                val base=LocalConfiguration.current
                val config=remember(base,language,width,scale) {Configuration(base).apply {
                    setLocale(Locale.forLanguageTag(language));setLayoutDirection(Locale.forLanguageTag(language))
                    fontScale=scale;screenWidthDp=width
                }}
                val configured=remember(config) {activity.createConfigurationContext(config)}
                val context=remember(config) {object:android.content.ContextWrapper(activity) {
                    override fun getResources()=configured.resources
                    override fun getAssets()=configured.assets
                }}
                val density=LocalDensity.current.density
                val colors=if(dark) Spectrum.Dark else Spectrum.Light
                Box(Modifier.fillMaxSize().background(colors.background),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=width.dp).fillMaxHeight()) {
                        CompositionLocalProvider(LocalContext provides context,LocalSpectrumContext provides context,
                            LocalConfiguration provides config,LocalDensity provides Density(density,scale),
                            LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                            LocalActivityResultRegistryOwner provides activity) {
                            FlowTheme(colors) {FlowScreen(content)}
                        }
                    }
                }
            }}
        }
        compose.waitForIdle()
    }

    @Composable private fun library(entries:List<DownloadEntity> = rows) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {DownloadedMediaLibrary(app.playback,entries,{},{})}
            PremiumBottomNavigation("library") {}
        }
    }

    private fun await(tag:String) {
        compose.waitUntil(15000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }

    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val drawn=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity {activity->
            activity.window.decorView.postOnAnimation {activity.window.decorView.postOnAnimation {drawn.countDown()}}
        }
        assertTrue("Native screen must draw before capture",drawn.await(3,java.util.concurrent.TimeUnit.SECONDS))
        instrument.waitForIdleSync()
        val bitmap=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {File(evidence,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
    }

    private fun hideKeyboard() {
        scenario.onActivity {it.window.insetsController?.hide(android.view.WindowInsets.Type.ime());it.window.decorView.clearFocus()}
        compose.waitUntil(10000) {
            var visible=false
            scenario.onActivity {visible=it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
            !visible
        }
    }

    private fun inspectTitle(row:DownloadEntity,variant:String) {
        val text=compose.onNodeWithText(row.filename,useUnmergedTree=true)
        val results=mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {assertTrue(it(results))}
        val layout=results.single()
        val card=compose.onNodeWithTag("library_item_${row.id}").fetchSemanticsNode().boundsInRoot
        val bounds=text.fetchSemanticsNode().boundsInRoot
        assertTrue("Filename must stay inside its card",bounds.left>=card.left&&bounds.right<=card.right)
        assertTrue("The rendered paragraph must fit its allocated height",layout.multiParagraph.height<=layout.size.height+1f)
        assertTrue("The final baseline line must fit the text box",layout.getLineBottom(layout.lineCount-1)<=layout.size.height+1f)
        assertTrue("Overflow must be deliberate ellipsis, not cropped text",
            !layout.didOverflowHeight||layout.isLineEllipsized(layout.lineCount-1))
        assertTrue("Filename must use at most two lines",layout.lineCount<=2)
        layouts.put(JSONObject().put("variant",variant).put("first_card_top_px",card.top)
            .put("title_lines",layout.lineCount).put("font_scale",layout.layoutInput.density.fontScale)
            .put("title_width_px",bounds.width).put("title_height_px",bounds.height)
            .put("paragraph_height_px",layout.multiParagraph.height).put("allocated_height_px",layout.size.height)
            .put("reported_overflow_height",layout.didOverflowHeight)
            .put("last_line_ellipsized",layout.isLineEllipsized(layout.lineCount-1)))
        File(evidence,"library-layouts.json").writeText(JSONObject().put("variants",layouts).toString(2))
    }

    @Test fun libraryVisualMatrixUsesRealComponentsAndOwnedMedia() {
        mediaFixtures()
        val variants=listOf(
            Triple("en",true,1f),Triple("ar",true,1f),Triple("en",false,1f),Triple("ar",false,1f),
            Triple("en",false,1.4f),Triple("ar",true,1.4f)
        )
        for((language,dark,scale) in variants) {
            val width=if(scale>1f) 320 else 360
            val name="$language-${if(dark) "dark" else "light"}-w$width-font$scale"
            render(language,dark,width,scale) {library()}
            compose.selectLibraryMenuOption("library_sort_menu","sort_name")
            await("library_item_${rows[1].id}")
            inspectTitle(rows[1],name)
            capture("library-$name-video-list")
            compose.onNodeWithTag("media_type_audio").performScrollTo().performClick()
            await("library_item_${rows[2].id}")
            capture("library-$name-audio-list")
            compose.onNodeWithTag("fm_view").performScrollTo().performClick()
            capture("library-$name-audio-grid")
            compose.onNodeWithTag("media_type_video").performScrollTo().performClick()
            await("library_item_${rows[1].id}")
            capture("library-$name-video-grid")
            if(scale==1f) {
                compose.onNodeWithTag("media_type_image").performScrollTo().performClick()
                await("library_item_${rows[3].id}")
                capture("library-$name-images-grid")
            }
        }
        File(evidence,"library-layouts.json").writeText(JSONObject().put("variants",layouts)
            .put("physical_model","NIC-LX2").put("physical_api",35)
            .put("viewport_note","320dp and 140% are app-local configuration/density constraints; no global display/font change")
            .put("fixture_rows_in_memory",rows.size).put("existing_download_rows_modified",0).toString(2))
    }

    @Test fun librarySearchSelectionMenusAndEmptyStatesKeepFilesIntact() {
        mediaFixtures()
        render {library()}
        await("library_item_${rows.first().id}")
        compose.onNodeWithTag("library_search").performTextReplacement("الساحل")
        compose.onNodeWithTag("library_search").performImeAction()
        compose.onNodeWithTag("library_search").assertIsNotFocused()
        await("library_item_${rows.first().id}")
        compose.onNodeWithTag("library_item_${rows[1].id}").assertDoesNotExist()
        capture("library-search-arabic-query")
        compose.onNodeWithTag("library_item_${rows.first().id}").performTouchInput {longClick()}
        compose.onNodeWithTag("fm_selection").assertExists()
        capture("library-selection-list")
        compose.onNodeWithTag("fm_view").performScrollTo().performClick()
        compose.onNodeWithTag("fm_selection").assertExists()
        capture("library-selection-grid")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithTag("local_clear_search").performScrollTo().performClick()
        compose.selectLibraryMenuOption("library_sort_menu","sort_name")
        val menuTag="fm_menu_${owned.first().lastPathSegment}"
        compose.onNodeWithTag(menuTag).performScrollTo().performClick()
        capture("library-file-actions")
        compose.onNodeWithText("Details").performClick()
        await("file_details_dialog")
        capture("library-file-details")
        compose.onNodeWithTag("file_details_confirm").performClick()
        compose.onNodeWithTag(menuTag).performScrollTo().performClick()
        compose.onNodeWithText("Rename").performClick()
        capture("library-rename-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        hideKeyboard()
        compose.onNodeWithTag(menuTag).performScrollTo().performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Details").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Delete file").performScrollTo().performClick()
        capture("library-delete-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        val expected=instrument.context.assets.open("status-original.mp4").use {it.readBytes()}
        assertArrayEquals("Canceling file dialogs must preserve source bytes",expected,
            app.contentResolver.openInputStream(owned.first())!!.use {it.readBytes()})
        compose.onNodeWithTag("library_search").performScrollTo().performTextReplacement("no-matching-uiux-file")
        compose.onNodeWithTag("library_search").performImeAction()
        await("library_empty_state")
        capture("library-search-no-results")
        render {library(emptyList())}
        await("library_empty_state")
        capture("library-empty-video")
        compose.onNodeWithTag("media_type_audio").performScrollTo().performClick()
        capture("library-empty-audio")
        compose.onNodeWithTag("media_type_image").performScrollTo().performClick()
        capture("library-empty-images")
    }

    @Test fun libraryRefinementsKeepSelectionGeometryAndAccessibleActions() {
        mediaFixtures()
        render {library()}
        val first=rows.first()
        val tag="library_item_${first.id}"
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo()
        val beforeCard=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val beforeTitle=compose.onNodeWithText(first.filename,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val menu=compose.onNodeWithTag("fm_menu_${owned.first().lastPathSegment}")
        menu.assertContentDescriptionEquals("Actions for ${first.filename}")
        val target=menu.fetchSemanticsNode().boundsInRoot
        val density=instrument.targetContext.resources.displayMetrics.density
        assertTrue("File actions must keep a 48dp target",target.width>=48*density-.5f&&target.height>=48*density-.5f)
        compose.onNodeWithTag(tag).performTouchInput {longClick()}
        compose.onNodeWithTag(tag).assertIsSelected().performScrollTo()
        val afterCard=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val afterTitle=compose.onNodeWithText(first.filename,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertEquals("Selection must not move the filename horizontally",beforeTitle.left-beforeCard.left,afterTitle.left-afterCard.left,.5f)
        assertEquals("Selection must not change filename width",beforeTitle.width,afterTitle.width,.5f)
        compose.onNodeWithTag("fm_view").performScrollTo().performClick()
        compose.onNodeWithTag(tag).assertIsSelected()
        compose.onNodeWithTag("fm_select_all").performScrollTo().performClick()
        compose.onNodeWithTag("library_item_${rows[1].id}").assertIsSelected()
        capture("library-accessible-selection-contract")
    }

    @Test fun libraryScrollingSampleUsesAnOwnedEightyFileCollection() {
        val bytes=instrument.context.assets.open("status-original.mp4").use {it.readBytes()}
        fixture("MediaFlow UI review video sample.mp4","video/mp4",bytes,12.0)
        val template=rows.single()
        val scrollRows=(0 until 80).map {index->
            val suffix=index.toString().padStart(3,'0')
            val rowUri=Uri.parse(checkNotNull(template.uri)).buildUpon()
                .appendQueryParameter("uiux_row",suffix).build().toString()
            template.copy(id="uiux-scroll-$suffix",filename="MediaFlow UI review video $suffix.mp4",uri=rowUri)
        }
        app.contentResolver.openFileDescriptor(Uri.parse(checkNotNull(scrollRows.last().uri)),"r")!!.close()
        render {library(scrollRows)}
        compose.selectLibraryMenuOption("library_sort_menu","sort_name")
        await("library_item_${scrollRows.first().id}")
        val durations=Collections.synchronizedList(mutableListOf<Long>())
        val listener=Window.OnFrameMetricsAvailableListener {_,metrics,_->
            durations+=metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        var refreshRate=0f
        scenario.onActivity {refreshRate=it.display?.refreshRate ?: 0f;it.window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper()))}
        val memoryBefore=android.os.Debug.getPss()
        try {
            repeat(8) {compose.onNodeWithTag("local_library_list").performTouchInput {swipeUp(durationMillis=300)}}
            repeat(8) {compose.onNodeWithTag("local_library_list").performTouchInput {swipeDown(durationMillis=300)}}
        } finally {
            scenario.onActivity {it.window.removeOnFrameMetricsAvailableListener(listener)}
        }
        val sorted=durations.toList().sorted()
        assertTrue("The native scrolling sample must record real frames",sorted.isNotEmpty())
        val budget=if(refreshRate>0) (1_000_000_000/refreshRate).toLong() else null
        File(evidence,"scrolling-sample.json").writeText(JSONObject().put("owned_video_files",owned.size)
            .put("logical_video_rows",scrollRows.size).put("fixture_bytes_each",bytes.size)
            .put("swipes",16).put("frames",sorted.size)
            .put("display_refresh_hz",refreshRate).put("median_frame_ms",sorted[sorted.size/2]/1_000_000.0)
            .put("p95_frame_ms",sorted[((sorted.size-1)*.95).toInt()]/1_000_000.0)
            .put("frames_over_nominal_budget",budget?.let {limit->sorted.count {it>limit}})
            .put("pss_before_kib",memoryBefore).put("pss_after_kib",android.os.Debug.getPss())
            .put("qualification","Debug candidate, duplicated controlled video content, one device, one bounded sample; not a general performance or leak claim").toString(2))
    }

    @Test fun primaryScreenInventoryDoesNotReplaceUserDraftsOrDownloads() {
        lateinit var model:ZViewModel
        scenario.onActivity {model=ViewModelProvider(it)[ZViewModel::class.java]}
        check(model.ui.value.text.isBlank()) {"Nonempty candidate draft: do not overwrite or capture it"}
        await("nav_home")
        compose.onNodeWithTag("home_capture_panel").assertIsDisplayed()
        capture("home-actual-full")
        val homeImage=compose.onNodeWithTag("home_capture_panel").captureToImage().asAndroidBitmap()
        try {File(evidence,"home-actual-composer.png").outputStream().use {homeImage.compress(Bitmap.CompressFormat.PNG,100,it)}}
        finally {homeImage.recycle()}
        compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected()
        compose.onNodeWithTag("downloads_search").performTextReplacement("MediaFlow-UIUX-no-personal-results")
        hideKeyboard()
        capture("downloads-actual-no-results")
        compose.onNodeWithTag("nav_home").performClick().assertIsSelected()
        compose.onNodeWithTag("open_settings").performClick()
        await("settings_list")
        capture("settings-actual-navigation")
        compose.onNodeWithTag("language_settings").performClick()
        capture("settings-actual-language-dialog")
    }

    @Test fun settingsQuickShareFormatsAndStatusComponentsUseNativeLayouts() {
        lateinit var model:ZViewModel
        scenario.onActivity {model=ViewModelProvider(it)[ZViewModel::class.java]}
        val format=Format.parse(JSONObject().put("format_id","uiux-video").put("url","https://uiux.invalid/video.mp4")
            .put("ext","mp4").put("width",320).put("height",240).put("vcodec","h264").put("acodec","aac").put("filesize",318465))
        val media=Media("https://uiux.invalid/item","uiux-options","MediaFlow controlled video · مراجعة الواجهة",
            "UI review fixture",null,null,12.0,listOf(format))
        for(language in listOf("en","ar")) {
            render(language) {ConsumerSettings(Settings(language=language),model,{},{})}
            await("settings_list")
            capture("settings-$language-dark")
            render(language,dark=false) {ConsumerSettings(Settings(language=language,theme="light"),model,{},{})}
            capture("settings-$language-light")
            render(language) {Preview(media,Settings(language=language),model,{},{}) {_,_->}}
            await("choose_format")
            capture("download-options-$language")
            compose.onNodeWithTag("choose_format").performScrollTo().performClick()
            await("format_sheet")
            compose.onNodeWithTag("format_confirm").assertIsDisplayed()
            capture("format-selection-$language")
            compose.onNodeWithTag("format_confirm").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithTag("format_sheet").fetchSemanticsNodes().isEmpty()}
            render(language) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    ShareInitialPreferences(480,false,true,{},{},{})
                }
            }
            await("share_preference_480")
            capture("quick-share-preferences-$language")
        }
        render {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                FlowLoadingIndicator()
                FlowAlertDialog({},confirmButton={androidx.compose.material3.TextButton({}) {
                    androidx.compose.material3.Text(spectrumStringResource(R.string.ok))}},
                    title={androidx.compose.material3.Text(spectrumStringResource(R.string.problem))},
                    text={androidx.compose.material3.Text(spectrumStringResource(R.string.file_missing))})
            }
        }
        capture("shared-error-dialog-and-loading")
    }
}
