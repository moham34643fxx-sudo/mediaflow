package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real app navigation and native captures; never changes or retries user downloads. */
class AuroraVisualReviewTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","aurora")}").apply {mkdirs()}
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(450)
        val bitmap=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
    private fun await(tag:String) {compose.waitUntil(20000) {runCatching {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}}
    @Test fun reviewArabicAndEnglishNavigationAndShareSelections()=runBlocking {
        val preferences=SettingsRepository(app)
        val original=preferences.current()
        val originalLibrary=app.playback.repository.text("local_media_source","device")
        try {
            preferences.text("theme","dark")
            for(language in listOf("ar","en")) {
                preferences.text("language",language)
                ActivityScenario.launch(MainActivity::class.java).use {scenario->
                    await("nav_home")
                    compose.onNodeWithTag("home_headline").assertIsDisplayed()
                    compose.onNodeWithTag("brand_header").assertIsDisplayed()
                    compose.onNodeWithTag("home_brand_logo").assertIsDisplayed()
                    capture("$language-home")
                    compose.onNodeWithTag("nav_downloads").performClick().assertIsSelected()
                    await("downloads_grid");capture("$language-downloads")
                    compose.onNodeWithTag("download_filter_failed").performScrollTo().performClick().assertIsSelected()
                    capture("$language-downloads-failed")
                    compose.onNodeWithTag("nav_library").performClick().assertIsSelected()
                    await("standalone_library")
                    compose.onNodeWithTag("library_source_menu").assertDoesNotExist()
                    await("media_type_video");compose.onNodeWithTag("media_type_video").performClick();Thread.sleep(700);capture("$language-library-videos")
                    compose.onNodeWithTag("media_type_audio").performClick();Thread.sleep(700);capture("$language-library-music")
                    compose.selectLibraryMenuOption("library_view_menu","music_folders");capture("$language-file-manager")
                    compose.onNodeWithTag("library_open_file").performClick()
                    await("local_media_screen");capture("$language-local-media")
                    compose.selectLibraryMenuOption("library_source_menu","library_source_playlists")
                    capture("$language-playlists")
                    scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                    await("standalone_library")
                    compose.onNodeWithTag("nav_home").performClick()
                    compose.onNodeWithTag("home_list").performScrollToIndex(1)
                    compose.onNodeWithTag("brand_header").assertIsDisplayed()
                    compose.onNodeWithTag("open_settings").performClick()
                    await("settings_list");capture("$language-settings")
                    compose.onNodeWithTag("language_settings").performClick()
                    compose.onNodeWithTag("language_$language").assertIsDisplayed();capture("$language-language-dialog")
                    compose.onNodeWithTag("language_$language").performClick()
                    compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_storage_management"))
                    compose.onNodeWithTag("open_storage_management").performClick()
                    await("storage_management");Thread.sleep(1000);capture("$language-storage")
                    scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                    compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_status_saver"))
                    compose.onNodeWithTag("open_status_saver").performClick()
                    await("status_screen");capture("$language-status-saver")
                }
                val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT,"https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?aurora-visual=$language")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                    lateinit var model:QuickShareViewModel
                    scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
                    await("share_preference_480")
                    compose.onNodeWithTag("share_preference_720").assertExists()
                    compose.onNodeWithTag("share_preference_1080").assertDoesNotExist()
                    compose.onNodeWithTag("share_preference_480").performScrollTo().performClick().assertIsSelected()
                    compose.onNodeWithTag("share_download").assertIsEnabled()
                    capture("$language-quick-share")
                    compose.onNodeWithTag("share_audio_mp3").performScrollTo().performClick().assertIsSelected()
                    capture("$language-quick-share-music")
                    compose.onNodeWithTag("share_more").performScrollTo().performClick()
                    await("share_expanded")
                    compose.waitUntil(90_000) {model.state.value.media!=null&&!model.state.value.analyzing}
                    val loaded=requireNotNull(model.state.value.media)
                    val source=loaded.formats.single {it.id=="15"}
                    val sourceTag=compose.revealQuickShareVideoSource(loaded,source)
                    compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(sourceTag))
                    compose.onNodeWithTag(sourceTag).performClick().assertIsSelected()
                    capture("$language-quick-share-more")
                    compose.onNodeWithTag("share_close").performClick()
                }
            }
        } finally {
            preferences.text("language",original.language);preferences.text("theme",original.theme)
            preferences.integer("preferred_height",original.preferredHeight)
            app.playback.repository.setText("local_media_source",originalLibrary)
        }
    }
}
