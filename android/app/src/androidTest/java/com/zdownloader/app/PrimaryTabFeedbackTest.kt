package com.zdownloader.app

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.ui.PremiumBottomNavigation
import com.zdownloader.app.ui.LocalSpectrumContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrimaryTabFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun downloadsRoutesOnClickWithoutWaitingForAFrame() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
            "com.zdownloader.app.candidate0965")
        val routes = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var page by remember { mutableStateOf("home") }
                    CompositionLocalProvider(LocalSpectrumContext provides activity) {
                        Column {
                            Text("Current pane: $page")
                            PremiumBottomNavigation(page) { route ->
                                routes += route
                                page = route
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("nav_home").assertIsSelected()
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("nav_downloads").performSemanticsAction(SemanticsActions.OnClick)
                compose.runOnIdle { assertEquals(listOf("downloads"), routes) }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.onNodeWithText("Current pane: downloads").assertIsDisplayed()
            compose.onNodeWithTag("nav_downloads").assertIsSelected()
        }
    }
}
