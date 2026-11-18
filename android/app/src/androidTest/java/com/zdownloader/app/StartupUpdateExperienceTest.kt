package com.zdownloader.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.ui.*
import com.zdownloader.app.updates.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** Isolated debug package only: no real updater journal, settings, or installer sessions. */
class StartupUpdateExperienceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Before fun isolatedPackage() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".designreview"))
    }

    @Composable private fun Theme(language:String,scale:Float,content:@Composable ()->Unit) {
        val base = LocalContext.current
        val config = Configuration(LocalConfiguration.current).apply {
            setLocale(Locale.forLanguageTag(language)); setLayoutDirection(Locale.forLanguageTag(language))
            fontScale = scale
        }
        val localized = base.createConfigurationContext(config)
        CompositionLocalProvider(LocalSpectrumContext provides localized, LocalConfiguration provides config,
            LocalDensity provides Density(LocalDensity.current.density,scale),
            LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
            FlowTheme(Spectrum.Dark) { content() }
        }
    }

    private fun host(language:String="en",scale:Float=1f,content:@Composable ()->Unit):ActivityScenario<ComponentActivity> =
        ActivityScenario.launch(ComponentActivity::class.java).also { scenario ->
            scenario.onActivity { it.setContent { Theme(language,scale,content) } }
        }

    private fun capture(name:String) {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null),"startup-update-review").apply { mkdirs() }
        val bitmap = checkNotNull(instrument.uiAutomation.takeScreenshot())
        try { File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }

    private fun release() = UpdateRelease(context.packageName,"stable",999,"Test preview",24,null,
        mapOf("en" to "• A cleaner startup.\n• Update directly without opening Settings.",
            "ar" to "• بداية أكثر سلاسة.\n• التحديث مباشرة دون فتح الإعدادات."),emptyList(),null)

    @Test fun automaticDiscoveryDownloadFailureRetryAndReadyStayInPopup() = runBlocking {
        val apk = File(context.applicationInfo.sourceDir)
        val identity = UpdateApkVerifier.installedIdentity(context)
        val abis = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
        val asset = UpdateAsset(if(abis.size==1) abis.single() else "universal",
            "https://github.com/owner/repo/releases/download/v-fixture/app.apk",UpdateApkVerifier.sha256(apk),apk.length())
        val published = release().copy(versionCode=identity.versionCode,assets=listOf(asset))
        val root = File(context.cacheDir,"popup-${UUID.randomUUID()}").apply { mkdirs() }
        val transfers = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if(request.url.host=="feed.example") published.toJson().toString().toResponseBody()
                else {
                    if(transfers.incrementAndGet()==1) throw IOException("Controlled download failure")
                    object : ResponseBody() {
                        private val apkSource = apk.source().buffer()
                        override fun contentType() = null
                        override fun contentLength() = apk.length()
                        override fun source() = apkSource
                    }
                }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build()
        try {
            val updater = AppUpdater(context,UpdateConfiguration("https://feed.example/update","owner/repo","stable",storageRoot=root),
                UpdateHttp(client)) { identity.copy(versionCode=identity.versionCode-1) }
            updater.checkOnEntry().join()
            assertEquals(UpdatePhase.AVAILABLE,updater.state.value.phase)
            assertEquals(0,transfers.get())
            host { UpdateOffer(updater,"en",true) }.use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("update_offer_now").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("A cleaner startup.").assertIsDisplayed()
                capture("update-available-en")
                compose.onNodeWithTag("update_offer_now").performClick()
                compose.waitUntil(10_000) { updater.state.value.phase==UpdatePhase.ERROR }
                compose.onNodeWithTag("update_offer_error").assertExists()
                compose.onNodeWithTag("update_offer_retry").performClick()
                compose.waitUntil(45_000) { updater.state.value.phase==UpdatePhase.READY }
                compose.onNodeWithTag("update_offer_install").assertIsDisplayed()
                assertEquals(asset.sha256,UpdateApkVerifier.sha256(File(root,"app-updates/payload.apk")))
                assertNull(updater.state.value.sessionId)
                assertEquals(2,transfers.get())
                capture("update-ready-en")
                compose.onNodeWithTag("update_offer_later").performClick()
                compose.onNodeWithTag("update_offer").assertDoesNotExist()
                assertEquals(UpdatePhase.READY,updater.state.value.phase)
            }
        } finally { root.deleteRecursively(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun deferredOfferDoesNotDownloadAndWaitsForSafePresentation() {
        var allowed by mutableStateOf(false)
        var dismissed = 0
        var downloads = 0
        val state = UpdateState(phase=UpdatePhase.AVAILABLE,release=release(),automaticPrompt=true)
        host {
            UpdatePopupHost(state,"en",allowed,UpdateDialogActions({dismissed++},{downloads++},{},null,{}))
        }.use {
            compose.onNodeWithTag("update_offer").assertDoesNotExist()
            compose.runOnIdle { allowed=true }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("update_offer_later").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("update_offer_later").performClick()
            compose.onNodeWithTag("update_offer").assertDoesNotExist()
            assertEquals(1,dismissed); assertEquals(0,downloads)
            compose.runOnIdle { allowed=false }; compose.runOnIdle { allowed=true }
            compose.onNodeWithTag("update_offer").assertDoesNotExist()
        }
    }

    @Test fun arabicLargeTextCanPauseResumeAndInstallInsideDialog() {
        val available = release()
        val asset = UpdateAsset("arm64-v8a","https://example.invalid/fixture","ab".repeat(32),1000)
        var state by mutableStateOf(UpdateState(phase=UpdatePhase.DOWNLOADING,release=available,asset=asset,total=1000,downloaded=500))
        var installations = 0
        host("ar",1.6f) {
            UpdateDialog(state,"ar",UpdateDialogActions({}, {state=state.copy(phase=UpdatePhase.DOWNLOADING)},
                {state=state.copy(phase=UpdatePhase.PAUSED)}, {installations++},{}))
        }.use {
            compose.onNodeWithTag("update_offer_progress").assertExists()
            compose.onNodeWithTag("update_offer_pause").assertIsDisplayed().performClick()
            compose.onNodeWithTag("update_offer_now").assertIsDisplayed().performClick()
            assertEquals(UpdatePhase.DOWNLOADING,state.phase)
            compose.runOnIdle { state=state.copy(phase=UpdatePhase.READY) }
            compose.onNodeWithTag("update_offer_install").assertIsDisplayed()
            capture("update-ar-font160")
            compose.onNodeWithTag("update_offer_install").performClick()
            assertEquals(1,installations)
        }
    }

    @Test fun startupIsStationaryAndFinishesWithinShortAnimation() {
        compose.mainClock.autoAdvance = false
        var finished = false
        host { MediaFlowLaunchReveal { finished=true } }.use {
            compose.mainClock.advanceTimeBy(535)
            val settled = compose.onNodeWithTag("brand_launch_name").fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(48)
            assertEquals(settled,compose.onNodeWithTag("brand_launch_name").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("brand_launch_name").assertContentDescriptionEquals("MEDIAFLOW")
            assertFalse(finished)
            capture("startup-centered-midpoint")
            compose.mainClock.advanceTimeBy(250)
            compose.runOnIdle { assertTrue(finished) }
        }
    }
}
