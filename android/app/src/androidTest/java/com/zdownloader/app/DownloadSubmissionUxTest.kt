package com.zdownloader.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.MainActivity
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.DownloadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DownloadSubmissionUxTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ZApplication
    private val registeredUrls = mutableSetOf<String>()

    @After
    fun cleanupOwnTestRows() = runBlocking {
        // Bounded cleanup: only discover and delete rows explicitly created by the test's registered test URLs
        withTimeout(10000) {
            val rows = app.repository.dao.observe().first()
            for (row in rows) {
                if (row.url in registeredUrls) {
                    if (row.status == "COMPLETED") {
                        app.storage.deleteFile(row)
                    } else {
                        app.repository.cancel(row.id)
                        app.coordinator.stopAndAwait(row.id)
                        app.repository.remove(row.id)
                    }
                }
            }
        }
        registeredUrls.clear()
    }

    private fun withUiFailureEvidence(name: String, assertion: () -> Unit) {
        try {
            assertion()
        } catch (failure: Throwable) {
            try {
                captureUiEvidence(name)
            } catch (evidenceFailure: Exception) {
                failure.addSuppressed(evidenceFailure)
            }
            throw failure
        }
    }

    private fun captureUiEvidence(name: String) {
        val folder = File(app.getExternalFilesDir(null), "test-stability/$name-${System.currentTimeMillis()}")
        check(folder.mkdirs()) { "Cannot create UI evidence folder" }
        File(folder, "semantics.txt").writeText(compose.onRoot().printToString())
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UI screenshot unavailable" }
        try {
            File(folder, "screen.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun awaitActionablePreview() {
        compose.waitUntil(30000) {
            var keyboardVisible = false
            instrumentation.runOnMainSync {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    keyboardVisible = compose.activity.window.decorView.rootWindowInsets
                        ?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
            }
            !keyboardVisible && compose.onAllNodes(hasTestTag("start_download") and isEnabled())
                .fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        compose.onNodeWithTag("start_download").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun rapidTapsCreateExactlyOneDurableRequestAndPreviewDismisses(): Unit = runBlocking {
        val beforeIds = app.repository.dao.observe().first().map { it.id }.toSet()
        val testUrl = "https://www.w3schools.com/html/mov_bbb.mp4?submission-ux=${UUID.randomUUID()}"
        registeredUrls.add(testUrl)

        // Input media URL
        compose.onNodeWithTag("url_input").performTextReplacement(testUrl)
        compose.onNodeWithTag("analyze_link").performClick()

        awaitActionablePreview()

        // Simulate rapid repeat taps via single gesture to prevent idle wait between clicks
        compose.onNodeWithTag("start_download").performTouchInput { doubleClick() }

        // Verify prompt dismissal of preview
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("start_download").fetchSemanticsNodes().isEmpty()
        }

        // Verify exactly one durable row persisted in Room DB
        val afterRows = app.repository.dao.observe().first()
        val matchingRows = afterRows.filter { it.url == testUrl }
        assertEquals("Exactly one durable request per operation", 1, matchingRows.size)

        val created = matchingRows.single()
        assertFalse(beforeIds.contains(created.id))
    }

    @Test
    fun secondLinkCanBeEnteredAndAnalyzedImmediatelyAfterSubmission(): Unit = runBlocking {
        val firstUrl = "https://www.w3schools.com/html/mov_bbb.mp4?sub-first=${UUID.randomUUID()}"
        val secondUrl = "https://www.w3schools.com/html/mov_bbb.mp4?sub-second=${UUID.randomUUID()}"
        registeredUrls.add(firstUrl)
        registeredUrls.add(secondUrl)

        // Submit first link
        compose.onNodeWithTag("url_input").performTextReplacement(firstUrl)
        compose.onNodeWithTag("analyze_link").performClick()

        awaitActionablePreview()
        compose.onNodeWithTag("start_download").performClick()

        withUiFailureEvidence("second-link-home") {
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("start_download").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("url_input").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("url_input").assertIsDisplayed().assertIsEnabled()
        }

        val firstCreated = checkNotNull(app.repository.dao.observe().first().singleOrNull { it.url == firstUrl }) {
            "First download row must be durably created in Room DB"
        }

        // Second link can be entered and analyzed immediately
        compose.onNodeWithTag("url_input").performTextReplacement(secondUrl)
        compose.onNodeWithTag("analyze_link").assertIsEnabled().performClick()

        awaitActionablePreview()
        // A previous submission's snackbar must not cover the next preview action.
        compose.onAllNodesWithText(app.getString(R.string.download_added)).assertCountEquals(0)
        compose.onNodeWithTag("start_download").assertIsDisplayed().performClick()

        withUiFailureEvidence("second-link-submit") {
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("start_download").fetchSemanticsNodes().isEmpty()
            }
        }

        val secondCreated = checkNotNull(app.repository.dao.observe().first().singleOrNull { it.url == secondUrl }) {
            "Second download row must be durably created in Room DB"
        }

        assertNotEquals("First and second IDs must be distinct", firstCreated.id, secondCreated.id)

        // Exercise Downloads snackbar action
        val viewDownloadsLabel = app.getString(R.string.view_downloads)
        val snackActionNodes = compose.onAllNodesWithText(viewDownloadsLabel).fetchSemanticsNodes()
        if (snackActionNodes.isNotEmpty()) {
            compose.onNodeWithText(viewDownloadsLabel).performClick()
        } else {
            compose.onNodeWithTag("nav_downloads").performClick()
        }

        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("downloads_grid").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("downloads_grid").assertIsDisplayed()
    }

    @Test
    fun downloadsScreenRedesignExposesSearchAndFilters(): Unit = runBlocking {
        val searchTestId = UUID.randomUUID().toString()
        val searchTestUrl = "https://www.w3schools.com/html/mov_bbb.mp4?search-filter=${UUID.randomUUID()}"
        registeredUrls.add(searchTestUrl)
        // Non-executable PAUSED fixture avoids startup/recovery execution races
        val searchEntity = DownloadEntity(
            id = searchTestId,
            url = searchTestUrl,
            title = "UniqueSearchableMediaTrack",
            source = "w3schools.com",
            thumbnail = null,
            duration = 30.0,
            selection = "{}",
            filename = "unique_track.mp4",
            destination = null,
            fingerprint = "fp_$searchTestId",
            status = "PAUSED",
            bytes = 1000L,
            total = 5000L,
            speed = 0L,
            outputSize = null,
            sizeKind = "EXACT"
        )
        app.repository.dao.insert(searchEntity)

        compose.onNodeWithTag("nav_downloads").performClick()

        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("downloads_grid").fetchSemanticsNodes().isNotEmpty()
        }

        // Verify our search item is visible
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("UniqueSearchableMediaTrack").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("UniqueSearchableMediaTrack").assertIsDisplayed()

        // Open filter menu and verify filter options are present
        compose.onNodeWithTag("downloads_filter_menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("download_filter_all").assertIsDisplayed()
        compose.onNodeWithTag("download_filter_active").assertIsDisplayed()
        compose.onNodeWithTag("download_filter_queued").assertIsDisplayed()
        compose.onNodeWithTag("download_filter_done").assertIsDisplayed()
        compose.onNodeWithTag("download_filter_failed").assertIsDisplayed()
        compose.onNodeWithTag("download_filter_cancelled").assertIsDisplayed()

        // Filter for waiting keeps non-executable PAUSED entity visible
        compose.onNodeWithTag("download_filter_queued").performClick()
        compose.onNodeWithText("UniqueSearchableMediaTrack").assertIsDisplayed()

        // Filter for completed hides PAUSED entity
        compose.onNodeWithTag("downloads_filter_menu").performClick()
        compose.onNodeWithTag("download_filter_done").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("UniqueSearchableMediaTrack").fetchSemanticsNodes().isEmpty()
        }

        // Reset filter to ALL
        compose.onNodeWithTag("downloads_filter_menu").performClick()
        compose.onNodeWithTag("download_filter_all").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("UniqueSearchableMediaTrack").fetchSemanticsNodes().isNotEmpty()
        }

        // Search input and clear button: assert no-match state and clearing restores expected results
        compose.onNodeWithTag("downloads_search").performTextInput("NonExistentTestQuery12345")
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("UniqueSearchableMediaTrack").fetchSemanticsNodes().isEmpty()
        }
        val noResultsText = app.getString(R.string.downloads_no_search_results)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(noResultsText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(noResultsText).assertIsDisplayed()

        // Clear search restores the item
        compose.onNodeWithTag("downloads_clear_search").assertIsDisplayed().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("UniqueSearchableMediaTrack").fetchSemanticsNodes().isNotEmpty()
        }
        withUiFailureEvidence("search-restore") {
            compose.onNodeWithText("UniqueSearchableMediaTrack").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText(noResultsText).fetchSemanticsNodes().isEmpty())
        }
    }
}
