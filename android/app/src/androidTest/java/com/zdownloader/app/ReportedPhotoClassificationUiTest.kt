package com.zdownloader.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReportedPhotoClassificationUiTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun reportedPhotoShowsOnlyActualImageAndCompanionAudioChoices() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val intent=Intent(instrument.targetContext,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://www.instagram.com/p/De4f1OAou7a/")
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
            lateinit var model:QuickShareViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model=ViewModelProvider(it)[QuickShareViewModel::class.java]
            }
            compose.waitUntil(120_000) {model.state.value.error!=null||model.state.value.media!=null&&!model.state.value.analyzing}
            assertNull("Real reported-photo discovery failed",model.state.value.error)
            val media=requireNotNull(model.state.value.media)
            assertTrue(media.videoOptions().isEmpty())
            val image=requireNotNull(media.originalImage())
            for(height in listOf(360,480,720,1080)) compose.onNodeWithTag("share_video_$height").assertDoesNotExist()
            compose.onNodeWithTag("share_more").assertDoesNotExist()
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_photo_${image.id}"))
            compose.onNodeWithTag("share_photo_${image.id}").assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertTrue(model.state.value.selection!!.imageOnly)
                assertEquals(image.ext,model.state.value.selection!!.output)
                assertTrue(model.state.value.canSubmit)
                model.prefer(360)
                assertFalse("A stale video preference must never select this image",model.state.value.canSubmit)
            }
            if(media.audioOptions().isEmpty()) compose.onNodeWithTag("share_audio_mp3").assertDoesNotExist()
        }
    }
}
