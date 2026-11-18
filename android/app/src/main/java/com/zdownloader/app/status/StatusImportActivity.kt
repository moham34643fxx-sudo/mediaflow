package com.zdownloader.app.status

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.ui.LocalSpectrumContext
import com.zdownloader.app.ui.Spectrum
import com.zdownloader.app.ui.FlowTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

/** Explicit image/video shares are separate from the existing text-link receiver. */
@AndroidEntryPoint class StatusImportActivity:ComponentActivity() {
    private val model:StatusSaverViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent {
            val prefs by model.settings.collectAsStateWithLifecycle()
            val base=LocalContext.current;val current=LocalConfiguration.current
            val config=remember(prefs.language,current) {Configuration(current).apply {setLocale(Locale.forLanguageTag(prefs.language));setLayoutDirection(Locale.forLanguageTag(prefs.language))}}
            val localized=remember(base,config) {
                val configured=base.createConfigurationContext(config)
                // Preserve the Activity context chain used to locate the result
                // registry. A bare configuration Context loses that owner.
                object:android.content.ContextWrapper(base) {
                    override fun getResources()=configured.resources
                    override fun getAssets()=configured.assets
                }
            }
            val dark=prefs.theme=="dark"||prefs.theme=="system"&&isSystemInDarkTheme()
            SideEffect {enableEdgeToEdge(statusBarStyle=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
                navigationBarStyle=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))}
            CompositionLocalProvider(LocalContext provides localized,LocalSpectrumContext provides localized,LocalConfiguration provides config,
                LocalLayoutDirection provides if(prefs.language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FlowTheme(if(dark) Spectrum.Dark else Spectrum.Light) {
                    androidx.compose.material3.Surface {Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {StatusSaverScreen(model,::finish)}}
                }
            }
        }
        receive(intent)
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);receive(intent)}
    private fun receive(intent:Intent?) {
        val sharedUrl=videoShareUrl(intent)
        if(sharedUrl!=null) {
            startActivity(Intent(this,com.zdownloader.app.ShareReceiverActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,sharedUrl))
            finish();return
        }
        model.importFiles(sharedMediaUris(intent).filter {it.toString().length<=com.zdownloader.app.domain.UrlPolicy.MAX_URL_LENGTH})
    }
    private fun videoShareUrl(intent:Intent?):String? {
        if(intent==null||intent.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)||intent.type?.startsWith("video/")!=true) return null
        if(hasSharedVideoFile(intent)) return null
        return runCatching {
            val texts=buildList {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf {it.length<=com.zdownloader.app.domain.UrlPolicy.MAX_INPUT_LENGTH}?.let(::add)
                intent.dataString?.takeIf {it.length<=com.zdownloader.app.domain.UrlPolicy.MAX_INPUT_LENGTH}?.let(::add)
                intent.clipData?.let {clip->repeat(minOf(clip.itemCount,StatusRules.MAX_ITEMS)) {index->
                    val item=clip.getItemAt(index)
                    item.text?.toString()?.takeIf {it.length<=com.zdownloader.app.domain.UrlPolicy.MAX_INPUT_LENGTH}?.let(::add)
                    item.uri?.toString()?.takeIf {it.length<=com.zdownloader.app.domain.UrlPolicy.MAX_INPUT_LENGTH}?.let(::add)
                }}
            }
            com.zdownloader.app.domain.QuickShareFormats.sharedUrl(texts)
        }.getOrNull()
    }
    private fun hasSharedVideoFile(intent:Intent):Boolean=runCatching {
        val streams=buildList {
            @Suppress("DEPRECATION")
            if(intent.action==Intent.ACTION_SEND) (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? android.net.Uri)?.let(::add)
            else intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.take(StatusRules.MAX_ITEMS)?.filterIsInstance<android.net.Uri>()?.let(::addAll)
            intent.clipData?.let {clip->for(index in 0 until minOf(clip.itemCount,StatusRules.MAX_ITEMS)) clip.getItemAt(index).uri?.let(::add)}
        }
        streams.any {it.scheme=="content"}
    }.getOrDefault(false)
    private fun sharedMediaUris(intent:Intent?):List<android.net.Uri> = runCatching {buildList {
            if(intent?.action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)&&
                intent?.type?.let {it.startsWith("image/")||it.startsWith("video/") }==true) {
                @Suppress("DEPRECATION")
                if(intent.action==Intent.ACTION_SEND) (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? android.net.Uri)?.let(::add)
                else intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)?.take(StatusRules.MAX_ITEMS)?.filterIsInstance<android.net.Uri>()?.let(::addAll)
                intent.clipData?.let {clip->for(index in 0 until minOf(clip.itemCount,StatusRules.MAX_ITEMS)) clip.getItemAt(index).uri?.let(::add)}
            }
        }}.getOrDefault(emptyList())
}
