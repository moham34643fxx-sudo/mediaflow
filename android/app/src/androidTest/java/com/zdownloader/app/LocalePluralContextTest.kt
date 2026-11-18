package com.zdownloader.app

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.ui.LocalSpectrumContext
import com.zdownloader.app.ui.spectrumPluralStringResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class LocalePluralContextTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    private fun localized(base:Context,tag:String):Context {
        val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}
        return base.createConfigurationContext(configuration)
    }

    @Test fun composePluralUsesSelectedAppLanguageWithOppositeSystemLanguage() {
        val base=instrumentation.targetContext
        val selected=mutableStateOf(localized(base,"ar"))
        val system=mutableStateOf(localized(base,"en"))
        val rendered=mutableStateOf<String?>(null)
        compose.activityRule.scenario.onActivity {activity->activity.setContent {
                val appContext=selected.value
                CompositionLocalProvider(LocalContext provides system.value,
                    LocalConfiguration provides appContext.resources.configuration,
                    LocalSpectrumContext provides appContext) {
                    rendered.value=spectrumPluralStringResource(R.plurals.player_sleep_minutes,2,2)
                }
            }
        }
        for((appLanguage,systemLanguage,expected) in listOf(
            Triple("ar","en","دقيقتان"),
            Triple("en","ar","2 minutes")
        )) {
            selected.value=localized(base,appLanguage)
            system.value=localized(base,systemLanguage)
            compose.waitForIdle()
            assertEquals("App=$appLanguage system=$systemLanguage",expected,rendered.value)
        }
    }
}
