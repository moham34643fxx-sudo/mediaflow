package com.zdownloader.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zdownloader.app.ui.ZRoot
import com.zdownloader.app.ui.ZViewModel
import com.zdownloader.app.ui.MediaFlowLaunchReveal
import com.zdownloader.app.ui.ClipboardPromptViewModel
import com.zdownloader.app.ui.ClipboardPromptUi
import com.zdownloader.app.ui.NotificationPermissionPolicy
import com.zdownloader.app.data.SettingsRepository
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import com.zdownloader.app.updates.AppUpdater
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var updater: AppUpdater
    private val model: ZViewModel by viewModels()
    private val clipboardPrompt:ClipboardPromptViewModel by viewModels()
    private var spectrumDark: Boolean? = null
    private var launcherLaunchRevision by mutableIntStateOf(0)
    private var splashExited by mutableStateOf(false)
    private var nativeExitStarted=false
    fun applySpectrumBars(dark: Boolean) {
        if(spectrumDark == dark) return
        spectrumDark=dark
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor(if(dark) "#080D14" else "#F8FAFC")))
        enableEdgeToEdge(
            statusBarStyle=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
            navigationBarStyle=if(dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
        preferHighRefreshRate()
    }
    private fun preferHighRefreshRate() {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val currentDisplay = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val modes = currentDisplay?.supportedModes ?: return
            val current = currentDisplay.mode ?: return
            val best = modes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if(best.modeId != window.attributes.preferredDisplayModeId && best.refreshRate > current.refreshRate) {
                val params = window.attributes
                params.preferredDisplayModeId = best.modeId
                window.attributes = params
            }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) spectrumDark?.let(::applySpectrumBars)
        if(hasFocus) inspectClipboardWhenFocused()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash=installSplashScreen()
        super.onCreate(savedInstanceState)
        if(intent?.action==Intent.ACTION_MAIN) launcherLaunchRevision++
        splash.setOnExitAnimationListener {screen->
            nativeExitStarted=true
            android.os.Trace.beginSection("MediaFlow splash handoff")
            try {
                // The first Compose buffer is already available underneath the system splash.
                screen.remove()
                splashExited=true
            } finally {
                android.os.Trace.endSection()
            }
        }
        enableEdgeToEdge()
        setContent {
            var reveal by remember {mutableStateOf(savedInstanceState==null)}
            var appMounted by remember {mutableStateOf(!reveal)}
            ReportDrawnWhen { appMounted && !reveal && splashExited }
            LaunchedEffect(Unit) {
                // Allow the first rendered frame to deliver the native exit.
                // Warm launches without a system splash still get a handoff.
                withFrameNanos { }
                if(!nativeExitStarted) splashExited=true
            }
            Box(Modifier.fillMaxSize()) {
                if(appMounted) {
                    val clipboard by clipboardPrompt.prompt.collectAsStateWithLifecycle()
                    val clipboardPreview by clipboardPrompt.preview.collectAsStateWithLifecycle()
                    var onboardingCompleted by rememberSaveable {mutableStateOf(!SettingsRepository.shouldShowWelcome(this@MainActivity))}
                    var notificationPermissionRevision by remember {mutableIntStateOf(0)}
                    val firstLaunchNotificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        notificationPermissionRevision++
                    }
                    LaunchedEffect(reveal,splashExited,launcherLaunchRevision,onboardingCompleted) {
                        if(reveal||!splashExited||launcherLaunchRevision==0||!onboardingCompleted||
                            !NotificationPermissionPolicy.requiresRuntimePermission(Build.VERSION.SDK_INT)) return@LaunchedEffect
                        val permissionState=getSharedPreferences(NotificationPermissionPolicy.PREFERENCES,MODE_PRIVATE)
                        val alreadyRequested=permissionState.getBoolean(NotificationPermissionPolicy.FIRST_LAUNCH_REQUESTED,false)
                        val granted=ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
                        if(NotificationPermissionPolicy.shouldRequestOnFirstLaunch(Build.VERSION.SDK_INT,granted,alreadyRequested)) {
                            // Persist before showing Android's UI so process recreation cannot repeat it.
                            permissionState.edit().putBoolean(NotificationPermissionPolicy.FIRST_LAUNCH_REQUESTED,true).apply()
                            firstLaunchNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if(granted&&!alreadyRequested) {
                            permissionState.edit().putBoolean(NotificationPermissionPolicy.FIRST_LAUNCH_REQUESTED,true).apply()
                            notificationPermissionRevision++
                        }
                    }
                    ZRoot(model,updater=updater,clipboard=clipboard?.takeUnless {reveal||!onboardingCompleted}?.let {
                        ClipboardPromptUi(it,::openClipboardDownload,clipboardPrompt::consume,clipboardPreview,
                            {clipboardPrompt.loadPreview(it)},clipboardPrompt::pausePreview)
                    },notificationPermissionRevision=notificationPermissionRevision,startupReady=!reveal&&splashExited,
                    onOnboardingComplete={onboardingCompleted=true})
                }
                if(reveal) MediaFlowLaunchReveal(
                    start=splashExited,
                    onBrandVisible={appMounted=true},
                    finished={reveal=false;appMounted=true}
                )
            }
        }
        if(savedInstanceState == null) receive(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.action==Intent.ACTION_MAIN) launcherLaunchRevision++
        receive(intent)
    }
    override fun onPostResume() {
        super.onPostResume()
        updater.onForeground(this)
        window.decorView.post {inspectClipboardWhenFocused()}
    }
    override fun onPause() {
        clipboardPrompt.pausePreview()
        updater.onBackground(this)
        super.onPause()
    }
    private fun receive(intent: Intent?) {
        val text = runCatching {if(intent?.action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else intent?.dataString}.getOrNull()
        if(!text.isNullOrBlank()) {clipboardPrompt.handlingSharedText(text);model.input(text);model.analyze()}
    }
    private fun inspectClipboardWhenFocused() {
        if(!isFinishing&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)&&hasWindowFocus()) clipboardPrompt.onForegroundWindow()
    }
    private fun openClipboardDownload() {
        val candidate=clipboardPrompt.prompt.value ?: return
        clipboardPrompt.consume()
        startActivity(Intent(this,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,candidate.url))
    }
}
