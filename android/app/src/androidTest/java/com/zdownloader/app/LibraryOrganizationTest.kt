package com.zdownloader.app

import android.content.ContentValues
import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class LibraryOrganizationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun compactMenusPreserveSourcesViewsSortingAndSearch() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val repository=app.playback.repository
        val originalSource=repository.text("local_media_source","device")
        try {
            compose.onNodeWithTag("nav_library").performClick()
            compose.onNodeWithTag("standalone_library").assertIsDisplayed()
            compose.onNodeWithTag("library_source_menu").assertDoesNotExist()
            compose.onNodeWithTag("library_search").assertIsDisplayed()
            compose.selectLibraryMenuOption("library_view_menu","music_folders")
            compose.assertLibraryMenuSelection("library_view_menu","music_folders")
            compose.selectLibraryMenuOption("library_view_menu","music_files")
            compose.selectLibraryMenuOption("library_sort_menu","sort_name")
            compose.assertLibraryMenuSelection("library_sort_menu","sort_name")
            compose.onNodeWithTag("library_search").performTextReplacement("no-match-${UUID.randomUUID()}")
            compose.onNodeWithTag("library_search").performImeAction()
            compose.waitUntil(10000) {compose.onAllNodesWithTag("library_empty_state").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("library_empty_state").assertIsDisplayed()
            compose.onNodeWithTag("library_search").performTextClearance()
            compose.onNodeWithTag("library_open_file").performClick()
            compose.onNodeWithTag("local_media_screen").assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            for(source in listOf("device","favorites","playlists")) {
                compose.selectLibraryMenuOption("library_source_menu","library_source_$source")
                compose.assertLibraryMenuSelection("library_source_menu","library_source_$source")
                compose.onNodeWithTag("local_media_screen").assertIsDisplayed()
            }
        } finally {
            repository.setText("local_media_source",originalSource)
        }
    }

    /** Regression: the download Library must not fall back to MediaStore or import opened files. */
    @Test fun localPlaybackDoesNotImportDeviceMediaIntoDownloads():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val token="separation-${UUID.randomUUID()}"
        val uris=mutableListOf<Uri>()
        val completedId="$token-download"
        val preferences=app.getSharedPreferences("mediaflow_playback",android.content.Context.MODE_PRIVATE)
        val originalSource=preferences.getString("local_media_source",null)
        fun publish(suffix:String):Uri {
            val resolver=app.contentResolver
            val uri=checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,"$token-$suffix.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/MediaFlow-QA")
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }))
            uris+=uri
            instrument.context.assets.open("status-original.mp4").use {input->resolver.openOutputStream(uri,"w")!!.use {input.copyTo(it)}}
            resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            return uri
        }
        try {
            val local=publish("local")
            val downloaded=publish("downloaded")
            app.repository.dao.insert(DownloadEntity(completedId,"https://example.com/$token","Download fixture","QA",null,null,"{}","$token-downloaded.mp4",null,token,status="COMPLETED",uri=downloaded.toString()))
            val completedBefore=app.repository.dao.completed().first().sortedBy {it.id}
            compose.onNodeWithTag("nav_library").performClick()
            compose.onNodeWithTag("library_search").performTextReplacement(token)
            compose.onNodeWithTag("library_search").performImeAction()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("library_item_$completedId").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("local_media_${local.lastPathSegment}").assertDoesNotExist()
            compose.onNodeWithTag("library_access_card").assertDoesNotExist()
            compose.onNodeWithTag("fm_connect_folder").assertDoesNotExist()
            compose.onNodeWithTag("library_open_file").performClick()
            compose.selectLibraryMenuOption("library_source_menu","library_source_device")
            compose.onNodeWithTag("media_type_video").performClick()
            compose.onNodeWithTag("local_library_search").performTextReplacement(token)
            compose.onNodeWithTag("local_library_search").performImeAction()
            compose.onNodeWithTag("local_library_list").performScrollToNode(hasTestTag("local_media_${local.lastPathSegment}"))
            compose.onNodeWithTag("local_media_${local.lastPathSegment}").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isNotEmpty()}
            assertTrue("System Back must target the foreground player",instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(15000) {compose.onAllNodesWithTag("video_player").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("local_media_screen").assertIsDisplayed()
            compose.runOnUiThread {compose.activity.onBackPressedDispatcher.onBackPressed()}
            compose.onNodeWithTag("standalone_library").assertIsDisplayed()
            compose.onNodeWithTag("nav_library").assertIsSelected()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("library_item_$completedId").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("library_item_$completedId").assertExists()
            compose.onNodeWithTag("local_media_${local.lastPathSegment}").assertDoesNotExist()
            assertEquals("Opening local media must not create or modify completed-download records",completedBefore,app.repository.dao.completed().first().sortedBy {it.id})
        } finally {
            app.repository.dao.removeHistory(completedId)
            uris.forEach {app.contentResolver.delete(it,null,null)}
            preferences.edit().apply {if(originalSource==null) remove("local_media_source") else putString("local_media_source",originalSource)}.commit()
        }
    }
}
