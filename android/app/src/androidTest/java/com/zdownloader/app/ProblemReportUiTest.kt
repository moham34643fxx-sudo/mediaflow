package com.zdownloader.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.ui.ZViewModel
import com.zdownloader.app.ui.openProblemReport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProblemReportUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    private fun openHelp() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings)).performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_help_report"))
        compose.onNodeWithTag("open_help_report").performClick()
        compose.onNodeWithTag("help_report_problem").performClick()
        compose.onNodeWithTag("problem_report_form").assertIsDisplayed()
    }

    @Test fun settingsDraftSurvivesKeyboardClosePageReturnAndActivityRecreation() {
        openHelp()
        compose.onNodeWithTag("report_whatsapp").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("report_url").performScrollTo().performTextReplacement("https://example.com/watch?v=7&token=private")
        compose.onNodeWithTag("report_description").performScrollTo().performTextReplacement("Video stops halfway through playback")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("report_preview").performScrollTo().assertTextContains("https://example.com/watch?v=7",substring=true)
        val preview=compose.onNodeWithTag("report_preview").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString {it.text}
        assertFalse(preview.contains("private"))
        compose.onNodeWithTag("report_close").performScrollTo().performClick()
        compose.onNodeWithTag("help_back").performScrollTo().performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_help_report"))
        compose.onNodeWithTag("open_help_report").performClick()
        compose.onNodeWithTag("help_report_problem").performClick()
        compose.onNodeWithTag("report_description").performScrollTo().assertTextContains("Video stops halfway through playback")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("report_description").performScrollTo().assertTextContains("Video stops halfway through playback")
        compose.onNodeWithTag("report_whatsapp").performScrollTo().assertIsEnabled()
    }

    @Test fun analysisFailurePrefillsReviewedReportAndExternalHandoffDoesNotClaimSent() {
        compose.runOnIdle {
            val model=ViewModelProvider(compose.activity)[ZViewModel::class.java]
            model.input("https://example.com/watch?v=321&access_token=secret")
            model.reportError(ErrorCode.UNSUPPORTED_PLATFORM)
        }
        compose.onNodeWithTag("analysis_report_problem").performClick()
        compose.onNodeWithTag("report_url").assertTextContains("https://example.com/watch?v=321")
        compose.onNodeWithTag("report_preview").performScrollTo().assertTextContains("Problem Type: Unsupported URL",substring=true)
        var captured:Intent?=null
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_VIEW&&intent.data?.host=="wa.me") {
                    captured=Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("report_whatsapp").performScrollTo().performClick()
            compose.waitUntil(5000) {captured!=null}
            val uri=checkNotNull(captured?.data)
            assertEquals("https",uri.scheme)
            assertEquals("/201099305871",uri.path)
            val message=checkNotNull(uri.getQueryParameter("text"))
            assertTrue(message.contains("Error Details: UNSUPPORTED_PLATFORM"))
            assertTrue(message.contains("Media URL: https://example.com/watch?v=321"))
            assertFalse(message.contains("secret"))
            compose.onNodeWithTag("problem_report_form").assertIsDisplayed()
            compose.onNodeWithTag("report_whatsapp").assertIsEnabled()
        } finally {instrumentation.removeMonitor(monitor)}
    }

    @Test fun unavailableWhatsAppClientsFallBackToOfficialHttpsWithoutSendingAnything() {
        val attempts=mutableListOf<Intent>()
        val boundary=object:ContextWrapper(compose.activity) {
            override fun startActivity(intent:Intent) {
                attempts+=Intent(intent)
                if(intent.`package`!=null) throw ActivityNotFoundException()
            }
        }
        val target="https://wa.me/201099305871?text=Reviewed%20report"
        assertTrue(openProblemReport(boundary,target))
        assertEquals(listOf("com.whatsapp","com.whatsapp.w4b",null),attempts.map {it.`package`})
        assertTrue(attempts.all {it.action==Intent.ACTION_VIEW&&it.dataString==target})
    }
}
