package com.zdownloader.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class BrandIdentityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private fun capture(name:String) {
        shell("cmd statusbar collapse")
        compose.waitUntil(5000) {instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName}
        compose.waitForIdle()
        Thread.sleep(250)
        val image=instrumentation.uiAutomation.takeScreenshot() ?: error("System screenshot unavailable")
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun officialNamesAndHeaderWorkInBothLanguagesAndThemes() {
        val manager=app.packageManager
        assertEquals("com.zdownloader.app",app.packageName)
        assertEquals("MediaFlow",manager.getApplicationLabel(app.applicationInfo).toString())
        val main=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        assertEquals("MediaFlow",manager.resolveActivity(main,0)!!.loadLabel(manager).toString())
        val share=Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        assertEquals(app.getString(R.string.share_link_target),manager.resolveActivity(share,0)!!.loadLabel(manager).toString())
        for(locale in listOf("ar","en")) {
            val context=app.createConfigurationContext(Configuration(app.resources.configuration).apply {setLocale(Locale.forLanguageTag(locale))})
            assertEquals("MediaFlow",context.getString(R.string.launcher_name))
            assertEquals("MediaFlow",context.getString(R.string.app_name))
        }
        val settings=SettingsRepository(app)
        val old=runBlocking {settings.current()}
        try {
            for((locale,theme) in listOf("ar" to "dark","en" to "light")) {
                runBlocking {settings.text("language",locale);settings.text("theme",theme)}
                compose.waitUntil(10000) {compose.onAllNodesWithTag("brand_header").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("brand_header").assertTextEquals("MediaFlow")
                capture("mediaflow-home-$locale-$theme")
                compose.onNodeWithTag("open_settings").performClick()
                compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_zd"))
                compose.onNodeWithTag("about_zd").performClick()
                compose.onAllNodesWithText("MediaFlow").onFirst().assertIsDisplayed()
                compose.onNodeWithText(if(locale=="ar") "حول التطبيق" else "About").assertIsDisplayed()
                compose.onNodeWithTag("about_version").assertTextContains(BuildConfig.VERSION_NAME,substring=true)
                capture("mediaflow-about-$locale-$theme")
                compose.onNodeWithTag("information_back").performClick()
                compose.onNodeWithTag("settings_back").performClick()
            }
            File(folder,"brand-identity.json").writeText(JSONObject().put("package",app.packageName).put("launcher_label","MediaFlow")
                .put("application_label","MediaFlow").put("share_label",app.getString(R.string.share_link_target)).put("locales","ar,en").put("themes","dark,light").toString(2))
        } finally {runBlocking {settings.text("language",old.language);settings.text("theme",old.theme)}}
    }
    @Test fun installedApprovedLauncherArtworkRenders() {
        val drawable=app.packageManager.getApplicationIcon(app.packageName)
        fun render(name:String,icon:android.graphics.drawable.Drawable) {
            val bitmap=Bitmap.createBitmap(432,432,Bitmap.Config.ARGB_8888)
            icon.setBounds(0,0,432,432)
            icon.draw(android.graphics.Canvas(bitmap))
            var visible=0
            for(y in 0 until 432) for(x in 0 until 432) {
                val pixel=bitmap.getPixel(x,y)
                if(android.graphics.Color.alpha(pixel)>32&&android.graphics.Color.blue(pixel)>100) visible++
            }
            assertTrue("Approved artwork must render visible colored contours",visible>1000)
            File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        render("mediaflow-installed-launcher",drawable)
        if(android.os.Build.VERSION.SDK_INT>=26) {
            // OEM PackageManager can return a flattened/themed icon. Verify the
            // installed adaptive resource separately from that real OEM render.
            val resource=app.resources.getXml(R.mipmap.ic_launcher).use {parser->
                android.graphics.drawable.Drawable.createFromXml(app.resources,parser,app.theme)
            }
            assertTrue(resource is android.graphics.drawable.AdaptiveIconDrawable)
            val adaptive=resource as android.graphics.drawable.AdaptiveIconDrawable
            render("mediaflow-installed-foreground",adaptive.foreground)
            if(android.os.Build.VERSION.SDK_INT>=33) {
                val monochrome=adaptive.monochrome
                assertNotNull("Themed launcher must have the approved silhouette",monochrome)
                render("mediaflow-installed-monochrome",monochrome!!)
            }
        }
    }
}
