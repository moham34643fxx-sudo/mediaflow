package com.zdownloader.app

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.DownloadPlan
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ConsumerQuickShareUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ZApplication

    @Test fun consumerMenuOmitsTechnicalDetailsAndSupportsSchedulingRealSelection() = runBlocking {
        val uniqueUrl = "https://www.w3schools.com/html/mov_bbb.mp4?consumer-ui=${UUID.randomUUID()}"
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setComponent(ComponentName(instrumentation.targetContext, ShareReceiverActivity::class.java))
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, uniqueUrl)
        val scenario = ActivityScenario.launch<ShareReceiverActivity>(sendIntent)
        try {
            withTimeout(45000) {
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"))
                compose.onNodeWithTag("share_more").performClick()
                compose.onNodeWithTag("share_expanded").assertIsDisplayed()

                compose.onNodeWithTag("share_technical").assertDoesNotExist()
                val technicalLabel = app.getString(R.string.technical_details)
                compose.onAllNodesWithText(technicalLabel).assertCountEquals(0)

                var model: QuickShareViewModel? = null
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[QuickShareViewModel::class.java]
                }
                compose.waitUntil(30000) {
                    model?.state?.value?.media != null && model?.state?.value?.analyzing == false
                }
                val media = checkNotNull(model?.state?.value?.media) { "Media failed to resolve" }
                assertTrue("Fixture media has video formats", media.formats.isNotEmpty())

                val firstVideo = media.formats.first { it.video }
                val variantTag=compose.revealQuickShareVideoSource(media,firstVideo)
                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(variantTag))
                compose.onNodeWithTag(variantTag).performClick()

                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_schedule"))
                compose.onNodeWithTag("share_schedule").assertIsEnabled()

                compose.onNodeWithTag("share_schedule").performClick()
                compose.onNodeWithTag("schedule_dialog").assertIsDisplayed()
                val inexactText = app.getString(R.string.schedule_inexact)
                compose.onNodeWithText(inexactText).assertIsDisplayed()

                val cancelText = app.getString(R.string.cancel)
                compose.onNodeWithText(cancelText).performClick()
                compose.onNodeWithTag("schedule_dialog").assertDoesNotExist()
                val rowsBeforeSchedule = app.repository.dao.observe().first().filter { it.url == uniqueUrl }
                assertTrue("Opening and canceling ScheduleDialog created no row", rowsBeforeSchedule.isEmpty())

                // Public schedule API persistence is tested with a future timestamp;
                // native date/time picker interaction and future background dispatch are untested boundaries.
                val futureTime = System.currentTimeMillis() + 3600_000L
                val plan = DownloadPlan(at = futureTime)
                scenario.onActivity {
                    model!!.schedule(plan)
                }

                compose.waitUntil(15000) {
                    runBlocking {
                        app.repository.dao.observe().first().any { it.url == uniqueUrl && it.status == "SCHEDULED" }
                    }
                }
                val scheduledRows = app.repository.dao.observe().first().filter { it.url == uniqueUrl }
                assertEquals(1, scheduledRows.size)
                val row = scheduledRows.first()
                assertEquals("SCHEDULED", row.status)
                assertEquals(futureTime, row.scheduledAt)

                compose.waitUntil(10000) {
                    if (scenario.state == Lifecycle.State.DESTROYED) {
                        true
                    } else {
                        var finishing = false
                        scenario.onActivity { finishing = it.isFinishing }
                        finishing
                    }
                }
            }
        } finally {
            try {
                withTimeout(10000) {
                    val rows = app.repository.dao.observe().first().filter { it.url == uniqueUrl }
                    for (row in rows) {
                        app.plannedDownloads.cancel(row.id)
                        app.repository.cancel(row.id)
                        app.repository.remove(row.id)
                    }
                }
            } finally {
                scenario.close()
            }
        }
    }
}
