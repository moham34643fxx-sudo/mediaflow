package com.zdownloader.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zdownloader.app.ui.ZViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled UI/VM checks: the multi-link branch does not fetch either URL. */
class FunctionalEntryTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun pastedMultipleLinksCanActuallyReachTheBatchScreen() {
        val first="https://example.com/a";val second="https://example.com/b"
        compose.onNodeWithTag("url_input").performTextReplacement("$first\n$second")
        compose.onNodeWithTag("analyze_link").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(listOf(first,second),ViewModelProvider(compose.activity)[ZViewModel::class.java].ui.value.batch)}
    }
    @Test fun editingInputCancelsAnalysisAndClearsThePreviousResult() {
        compose.runOnIdle {
            val model=ViewModelProvider(compose.activity)[ZViewModel::class.java]
            model.input("https://does-not-exist.invalid/old.mp4");model.analyze()
            model.input("https://example.com/new.mp4")
            assertFalse(model.ui.value.analyzing);assertNull(model.ui.value.media);assertNull(model.ui.value.analysisPreview)
            assertEquals("https://example.com/new.mp4",model.ui.value.text)
        }
        compose.waitForIdle()
    }
}
