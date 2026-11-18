package com.zdownloader.app.playback

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.ZApplication
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.ui.FlowTheme
import com.zdownloader.app.ui.LocalSpectrumContext
import com.zdownloader.app.ui.Spectrum
import java.util.Locale
import kotlinx.coroutines.flow.map

/** In-app audio preview only. External files use ExternalAudioOpenActivity directly. */
class InAppAudioPreviewActivity:ComponentActivity() {
    internal val connection get()=(application as ZApplication).playback
    private var leavingForFullPlayer=false
    private var closed=false
    private var localizedContext:android.content.Context?=null
    private val handoff=AudioPlaybackHandoff(this,{connection},{localizedContext ?: this},::finish)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(routeExternalIntent(intent)) return
        setContent {
            val preferences by remember {SettingsRepository(applicationContext).flow.map {it as Settings?}}
                .collectAsStateWithLifecycle(initialValue=null)
            val settings=preferences ?: return@setContent
            val current=LocalConfiguration.current
            val configuration=remember(current,settings.language) {Configuration(current).apply {
                setLocale(Locale.forLanguageTag(settings.language));setLayoutDirection(Locale.forLanguageTag(settings.language))
            }}
            val localized=remember(configuration) {createConfigurationContext(configuration)}
            SideEffect {localizedContext=localized}
            val dark=settings.theme=="dark"||settings.theme=="system"&&isSystemInDarkTheme()
            SideEffect {enableEdgeToEdge(
                statusBarStyle=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
                navigationBarStyle=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))}
            CompositionLocalProvider(LocalContext provides localized,LocalSpectrumContext provides localized,LocalConfiguration provides configuration,
                LocalLayoutDirection provides if(settings.language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FlowTheme(if(dark) Spectrum.Dark else Spectrum.Light) {
                    PlaybackLifecycle(connection)
                    AudioPopupScreen(connection,null,AudioPopupActions(::dismissPlayer,::openFullPlayer,handoff::floating,handoff::background,::closeAudio))
                }
            }
        }
    }

    private fun routeExternalIntent(incoming:Intent):Boolean {
        if(incoming.action!=Intent.ACTION_VIEW) return false
        if(ExternalAudioOpen.acceptsIntent(this,incoming))
            startActivity(Intent(incoming).setClass(this,ExternalAudioOpenActivity::class.java))
        finish()
        return true
    }

    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(!routeExternalIntent(intent)) connection.refreshSession()
    }

    internal fun closeAudio() {
        if(closed||leavingForFullPlayer) return
        closed=true
        handoff.stop()
    }

    private fun dismissPlayer() {finish()}

    internal fun openFullPlayer() {
        if(closed||leavingForFullPlayer) return
        leavingForFullPlayer=true
        connection.open(this)
        finish()
    }
}
