package com.zdownloader.app

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BrandResourcesTest {
    @Test fun mediaItemCountsUseEnglishAndArabicPluralRules() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(language:String)=app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {setLocale(java.util.Locale.forLanguageTag(language))}).resources
        val english=localized("en");val arabic=localized("ar")
        assertEquals("1 item",english.getQuantityString(R.plurals.player_item_count,1,1))
        assertEquals("2 items",english.getQuantityString(R.plurals.player_item_count,2,2))
        assertEquals("لا عناصر",arabic.getQuantityString(R.plurals.player_item_count,0,0))
        assertEquals("عنصر واحد",arabic.getQuantityString(R.plurals.player_item_count,1,1))
        assertEquals("عنصران",arabic.getQuantityString(R.plurals.player_item_count,2,2))
        assertTrue(arabic.getQuantityString(R.plurals.player_item_count,3,3).endsWith("عناصر"))
        assertTrue(arabic.getQuantityString(R.plurals.player_item_count,11,11).endsWith("عنصرًا"))
    }
    @Test fun mediaFlowBrandFitsAdaptiveSafeZoneAndRendersMasks() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val icon=context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val foreground=Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888)
        icon.foreground.setBounds(0,0,108,108)
        icon.foreground.draw(Canvas(foreground))
        var visible=0
        for(y in 0..107) for(x in 0..107) if(Color.alpha(foreground.getPixel(x,y))>0) {
            visible++
            assertTrue("Brand glyph outside central 66dp at $x,$y",x in 21..86&&y in 21..86)
            assertTrue("Brand glyph outside safe circle at $x,$y",kotlin.math.hypot(x-53.5,y-53.5)<=33.0)
        }
        assertTrue("Empty brand foreground",visible>500)
        foreground.recycle()
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val folder=File(context.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        listOf("circle","rounded-square","squircle").forEach { mask->
            val image=Bitmap.createBitmap(324,324,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image)
            val path=Path()
            when(mask) {
                "circle"->path.addCircle(162f,162f,162f,Path.Direction.CW)
                "rounded-square"->path.addRoundRect(0f,0f,324f,324f,64f,64f,Path.Direction.CW)
                else->{path.moveTo(162f,0f);path.cubicTo(300f,0f,324f,24f,324f,162f);path.cubicTo(324f,300f,300f,324f,162f,324f);path.cubicTo(24f,324f,0f,300f,0f,162f);path.cubicTo(0f,24f,24f,0f,162f,0f);path.close()}
            }
            canvas.clipPath(path)
            // AdaptiveIconDrawable expands 108dp layers beyond its visible 72dp
            // viewport. Test actual launcher scaling rather than a tiny full layer.
            icon.background.setBounds(-81,-81,405,405);icon.background.draw(canvas)
            icon.foreground.setBounds(-81,-81,405,405);icon.foreground.draw(canvas)
            assertEquals(0,Color.alpha(image.getPixel(0,0)))
            File(folder,"adaptive-$mask.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
        for(resource in listOf(R.drawable.ic_launcher_monochrome,R.drawable.ic_zd_notification)) {
            val drawable=context.getDrawable(resource)!!
            val image=Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,108,108);drawable.draw(Canvas(image))
            var count=0
            for(y in 0..107) for(x in 0..107) {
                val color=image.getPixel(x,y)
                if(Color.alpha(color)>0) {count++;assertEquals(255,Color.red(color));assertEquals(255,Color.green(color));assertEquals(255,Color.blue(color))}
            }
            assertTrue("Monochrome icon is empty",count>400)
            assertEquals(0,Color.alpha(image.getPixel(0,0)))
            File(folder,if(resource==R.drawable.ic_zd_notification) "notification-alpha.png" else "launcher-monochrome.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
    }
}
