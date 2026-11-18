package com.zdownloader.app

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PlaylistRemovalUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val policy=PlaylistPolicy()
    private val summary get()=BatchSummary(PlaylistBatch("cancelled","key","https://example.com/list","قائمة ملغية طويلة — Cancelled collection",policy.json(),state="CANCELLED"),
        2,1,0,1,0,0,0,0,"ANY",false,1024,2048.0,2,0,1,null,null)
    @Composable private fun Theme(language:String,content:@Composable ()->Unit) {
        val base=LocalContext.current
        val config=Configuration(LocalConfiguration.current).apply {setLocale(Locale.forLanguageTag(language));fontScale=1.3f}
        val localized=remember(language) {base.createConfigurationContext(config)}
        val density=LocalDensity.current
        CompositionLocalProvider(LocalContext provides localized,LocalSpectrumContext provides localized,LocalConfiguration provides config,
            LocalDensity provides Density(density.density,1.3f),LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
            FlowTheme(if(language=="ar") Spectrum.Dark else Spectrum.Light) {content()}
        }
    }
    private fun host(language:String="ar",content:@Composable ()->Unit)=ActivityScenario.launch(ComponentActivity::class.java).also {scenario->
        scenario.onActivity {it.setContent {Theme(language,content)}}
    }
    @Test fun cancelledCollectionOffersRemovalAndConfirmationCanBeDismissedInBothLanguages() {
        for(language in listOf("ar","en")) {
            var removed=0;var cancelled=0
            host(language) {PlaylistBatchCard(summary,{}, {},{cancelled++},{},{},remove={removed++})}.use {
                compose.onNodeWithTag("playlist_batch_more_cancelled").performClick()
                compose.onNodeWithTag("playlist_remove_cancelled").performClick()
                compose.onNodeWithTag("playlist_confirm_remove").assertIsDisplayed()
                assertEquals(0,removed)
                compose.onNodeWithTag("playlist_dismiss_confirmation").performClick();assertEquals(0,removed)
                compose.onNodeWithTag("playlist_batch_more_cancelled").performClick()
                compose.onNodeWithTag("playlist_remove_cancelled").performClick()
                compose.onNodeWithTag("playlist_confirm_remove").performClick()
                assertEquals(1,removed);assertEquals(0,cancelled)
            }
        }
    }
    @Test fun detailScreenHasTheSameSafeRemovalAction() {
        var removed=0
        val actions=PlaylistDetailActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {removed++})
        host {PlaylistDetailContent("cancelled",summary,emptyList(),policy,emptyMap(),SizeCoverage.of(emptyList()),actions,{})}.use {
            compose.onNodeWithTag("playlist_action_remove").performScrollTo().performClick()
            assertEquals(0,removed);compose.onNodeWithTag("playlist_confirm_remove").performClick();assertEquals(1,removed)
        }
    }
    @Test fun renameRequiresANameAndShowsALabel() {
        var name by mutableStateOf("");var saves=0
        host("en") {RenameMediaDialog(name,{name=it},{},{saves++})}.use {
            compose.onNodeWithTag("rename_save").assertIsNotEnabled()
            compose.onNodeWithTag("rename_filename").performTextInput("Lecture.mp3")
            compose.onNodeWithTag("rename_save").assertIsEnabled().performClick();assertEquals(1,saves)
        }
    }
    @Test fun chargingLabelTogglesTheWholeAccessibleRow() {
        host("en") {ScheduleDialog({},{})}.use {
            compose.onNodeWithTag("schedule_charging").performScrollTo().assertIsOff().performClick().assertIsOn()
        }
    }
}
