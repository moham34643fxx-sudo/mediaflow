package com.zdownloader.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import com.zdownloader.app.ui.FlowDropdownMenu as DropdownMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.*
import com.zdownloader.app.ui.spectrumStringResource as stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.windowsizeclass.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.videoFrameMillis
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.background.errorResource
import com.zdownloader.app.playback.PlaybackLifecycle
import com.zdownloader.app.playback.MiniPlayer
import com.zdownloader.app.playback.PlaybackMedia
import com.zdownloader.app.playback.DownloadedMediaLibrary
import com.zdownloader.app.playback.LocalMediaScreen
import com.zdownloader.app.playback.PlaybackRequestError
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import java.util.Locale
import java.text.DateFormat
import androidx.compose.ui.unit.LayoutDirection

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable fun ZRoot(model: ZViewModel,navigationController:NavHostController=rememberMediaFlowController(),updater:com.zdownloader.app.updates.AppUpdater?=null,
    clipboard:ClipboardPromptUi?=null,notificationPermissionRevision:Int=0,startupReady:Boolean=true,onOnboardingComplete:()->Unit={}) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val ui by model.ui.collectAsStateWithLifecycle()
    val base = LocalContext.current
    val currentConfiguration=LocalConfiguration.current
    val config = remember(settings.language,currentConfiguration) { Configuration(currentConfiguration).apply { setLocale(Locale.forLanguageTag(settings.language)); setLayoutDirection(Locale.forLanguageTag(settings.language)) } }
    val localized = remember(config) {
        val configured = base.createConfigurationContext(config)
        object : android.content.ContextWrapper(base) {
            override fun getResources(): android.content.res.Resources = configured.resources
            override fun getAssets(): android.content.res.AssetManager = configured.assets
        }
    }
    val dark = settings.theme == "dark" || settings.theme == "system" && isSystemInDarkTheme()
    val colors = if(dark) Spectrum.Dark else Spectrum.Light
    val expanded=calculateWindowSizeClass(base as android.app.Activity).widthSizeClass!=WindowWidthSizeClass.Compact
    DisposableEffect(base,dark) {
        val activity=base as com.zdownloader.app.MainActivity
        fun applyBars() { activity.applySpectrumBars(dark) }
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) activity.window.decorView.post {applyBars()} }
        activity.lifecycle.addObserver(observer)
        activity.window.decorView.post {applyBars()}
        onDispose {activity.lifecycle.removeObserver(observer)}
    }
    val navigationVisibility=remember {PrimaryNavigationVisibility()}
    val rootDayClock = rememberMediaDayClock()
    CompositionLocalProvider(LocalContext provides localized,LocalSpectrumContext provides localized,LocalConfiguration provides config,
        LocalPrimaryNavigationVisibility provides navigationVisibility,LocalMediaDayClock provides rootDayClock,LocalLayoutDirection provides if(settings.language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
        FlowTheme(colors) {
            val nav = navigationController
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            val primary=entry?.destination.primaryDestination()
            var presentedAnalysisId by rememberSaveable {mutableStateOf<String?>(null)}
            var extractionSource by rememberSaveable {mutableStateOf<String?>(null)}
            val extractAudio:(String?)->Unit={uri->extractionSource=uri;nav.openSecondary(SecondaryDestination.EXTRACT_AUDIO)}
            val playback=remember(base) {(base.applicationContext as com.zdownloader.app.ZApplication).playback}
            PlaybackLifecycle(playback)
            val playMedia:(DownloadEntity,List<DownloadEntity>)->Unit={entity,rows->
                val media=rows.mapNotNull {PlaybackMedia.from(it)}.ifEmpty {listOfNotNull(PlaybackMedia.from(entity))}
                playback.play(media,media.indexOfFirst {it.uri==entity.uri}.coerceAtLeast(0))
            }
            val snack = remember { SnackbarHostState() }
            var reportOpen by rememberSaveable {mutableStateOf(false)}
            var showFacebookLogin by rememberSaveable {mutableStateOf(false)}
            var reportUrl by rememberSaveable {mutableStateOf("")}
            var reportError by rememberSaveable {mutableStateOf("")}
            val queuedText = stringResource(R.string.download_added)
            val viewDownloadsText = stringResource(R.string.view_downloads)
            LaunchedEffect(ui.analysisId,ui.media?.url,ui.analyzing,primary) {
                if(ui.media!=null&&!ui.analyzing&&primary==PrimaryDestination.HOME&&presentedAnalysisId!=ui.analysisId) {
                    presentedAnalysisId=ui.analysisId;nav.openSecondary(SecondaryDestination.PREVIEW)
                }
            }
            // Pop consumed previews instead of saving/restoring a tab stack that
            // can restore the same preview after its media has been cleared.
            LaunchedEffect(route,ui.media==null,ui.analyzing) {
                if(nav.currentDestination?.route==SecondaryDestination.PREVIEW.route&&ui.media==null&&!ui.analyzing) {
                    if(!nav.popBackStack(PrimaryDestination.HOME.route,false)) {
                        nav.navigate(PrimaryDestination.HOME.route) {launchSingleTop=true}
                    }
                }
            }
            LaunchedEffect(model,queuedText,viewDownloadsText) {
                model.queuedEvents.collectLatest {
                    val action=snack.showSnackbar(queuedText,actionLabel=viewDownloadsText,withDismissAction=true,duration=SnackbarDuration.Short)
                    if(action==SnackbarResult.ActionPerformed) nav.selectPrimary(PrimaryDestination.DOWNLOADS)
                }
            }
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { runCatching { base.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); model.textSetting("destination",it.toString()) }.onFailure { model.reportError(ErrorCode.STORAGE_PERMISSION_ERROR) } }
            }
            var settingsPermissionRevision by remember {mutableIntStateOf(0)}
            var pendingNotificationSetting by remember {mutableStateOf<String?>(null)}
            val notificationPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
                settingsPermissionRevision++
                val setting=pendingNotificationSetting
                pendingNotificationSetting=null
                if(granted&&setting!=null) model.notificationSetting(setting,true)
            }
            val requestNotificationPermission:(String?)->Unit={setting->
                if(NotificationPermissionPolicy.requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                    if(androidx.core.content.ContextCompat.checkSelfPermission(base,Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        settingsPermissionRevision++
                        if(setting!=null) model.notificationSetting(setting,true)
                    } else {
                        pendingNotificationSetting=setting
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else if(setting!=null) model.notificationSetting(setting,true)
            }
            val currentNotificationPermissionRevision=notificationPermissionRevision+settingsPermissionRevision
            val navigate:(PrimaryDestination)->Unit=remember(nav) {{nav.selectPrimary(it)}}
            val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
            val bottomBarHeight=remember {mutableIntStateOf(0)}
            // A destination owns its entire scene. The controller publishes its
            // target before NavHost finishes drawing the outgoing destination;
            // changing an outer Scaffold here would detach its chrome early.
            val chrome=remember(expanded,keyboardVisible,navigationVisibility,playback,snack,navigate,bottomBarHeight) {
                NavigationChrome(expanded,keyboardVisible,navigationVisibility,playback,snack,navigate,bottomBarHeight)
            }
            val scene:@Composable (androidx.navigation.NavDestination,@Composable ()->Unit)->Unit={destination,content->NavigationScene(destination,chrome,content)}
            val motionEnabled=LocalFlowMotionEnabled.current
            val direction=if(LocalLayoutDirection.current==LayoutDirection.Rtl) -1 else 1
            val navEnter=if(motionEnabled) fadeIn(tween(FlowMotion.Scene, easing = FastOutSlowInEasing))+slideInHorizontally(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) {it/14*direction} else EnterTransition.None
            val navExit=if(motionEnabled) fadeOut(tween(FlowMotion.Exit, easing = FastOutSlowInEasing)) else ExitTransition.None
            val navPopEnter=if(motionEnabled) fadeIn(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) else EnterTransition.None
            val navPopExit=if(motionEnabled) fadeOut(tween(FlowMotion.Exit, easing = FastOutSlowInEasing))+slideOutHorizontally(tween(FlowMotion.Exit, easing = FastOutSlowInEasing)) {it/14*direction} else ExitTransition.None
            val shouldShowWelcome = remember(base) { SettingsRepository.shouldShowWelcome(base) }
            val rootStartDestination = if(shouldShowWelcome) MediaFlowGraphs.WELCOME else MediaFlowGraphs.PRIMARY
            NavHost(navController=nav,startDestination=rootStartDestination,modifier=Modifier.fillMaxSize(),
                        enterTransition={if(primaryTabTransition(initialState.destination,targetState.destination)) EnterTransition.None else navEnter},
                        exitTransition={if(primaryTabTransition(initialState.destination,targetState.destination)) ExitTransition.None else navExit},
                        popEnterTransition={if(primaryTabTransition(initialState.destination,targetState.destination)) EnterTransition.None else navPopEnter},
                        popExitTransition={if(primaryTabTransition(initialState.destination,targetState.destination)) ExitTransition.None else navPopExit}) {
                      composable(MediaFlowGraphs.WELCOME) {
                        WelcomeScreen(
                            versionName = BuildConfig.VERSION_NAME,
                            onComplete = {
                                model.completeOnboarding()
                                onOnboardingComplete()
                                nav.navigate(MediaFlowGraphs.PRIMARY) {
                                    popUpTo(MediaFlowGraphs.WELCOME) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                      }
                      navigation(startDestination=PrimaryDestination.HOME.route,route=MediaFlowGraphs.PRIMARY) {
                        composable(PrimaryDestination.HOME.route) {entry->scene(entry.destination) { val recent by model.recent.collectAsStateWithLifecycle();Home(ui,recent,model,{navigate(PrimaryDestination.DOWNLOADS)},{nav.openSecondary(SecondaryDestination.SETTINGS)},{nav.openSecondary(SecondaryDestination.PREVIEW)},{nav.openSecondary(SecondaryDestination.STATUS)},browser={nav.openSecondary(SecondaryDestination.BROWSER)},playMedia={playMedia(it,listOf(it))},extractAudio={extractAudio(null)}) } }
                        composable(PrimaryDestination.DOWNLOADS.route) {entry->scene(entry.destination) { val downloads by model.downloads.collectAsStateWithLifecycle(); val onPlaySingle=remember(playMedia) {{entity:DownloadEntity->playMedia(entity,listOf(entity))}}; Downloads(downloads,model,onPlaySingle) } }
                        composable(PrimaryDestination.LIBRARY.route) {entry->scene(entry.destination) { val library by model.library.collectAsStateWithLifecycle(); val onExtract=remember(extractAudio) {{uri:String->extractAudio(uri)}}; DownloadedMediaLibrary(playback,library,{nav.openSecondary(SecondaryDestination.LOCAL_MEDIA)},onExtract) } }
                      }
                       navigation(startDestination=SecondaryDestination.SETTINGS.route,route=MediaFlowGraphs.SECONDARY) {
                        composable(SecondaryDestination.LOCAL_MEDIA.route) {entry->scene(entry.destination) {LocalMediaScreen(playback,{nav.closeSecondary()},extractAudio={extractAudio(it)})} }
                       composable(SecondaryDestination.PREVIEW.route) {entry->scene(entry.destination) { ui.media?.let { media -> Preview(media,settings,model,{ nav.closeSecondary() },{ folderPicker.launch(null) }, { selection,title -> model.enqueue(media,selection,title) }) } } }
                           composable(SecondaryDestination.SETTINGS.route) {entry->scene(entry.destination) { ConsumerSettings(settings,model,{ folderPicker.launch(null) },requestNotificationPermission,{nav.closeSecondary()},currentNotificationPermissionRevision,statusSaver={nav.openSecondary(SecondaryDestination.STATUS)},storageManagement={nav.openSecondary(SecondaryDestination.STORAGE)},updater=updater) } }
                        composable(SecondaryDestination.STORAGE.route) {entry->scene(entry.destination) {StorageManagementScreen({nav.closeSecondary()},{navigate(PrimaryDestination.LIBRARY)},{navigate(PrimaryDestination.DOWNLOADS)})} }
                        composable(SecondaryDestination.STATUS.route) {entry->scene(entry.destination) {
                            val status=remember(base) {androidx.lifecycle.ViewModelProvider(base as androidx.activity.ComponentActivity)[com.zdownloader.app.status.StatusSaverViewModel::class.java]}
                            com.zdownloader.app.status.StatusSaverScreen(status) {nav.closeSecondary()}
                        } }
                        composable(SecondaryDestination.EXTRACT_AUDIO.route) {entry->scene(entry.destination) {
                            val extraction=remember(base) {androidx.lifecycle.ViewModelProvider(base as androidx.activity.ComponentActivity)[com.zdownloader.app.extraction.AudioExtractionViewModel::class.java]}
                            com.zdownloader.app.extraction.AudioExtractionScreen(extraction,{nav.closeSecondary()},{nav.selectPrimary(PrimaryDestination.LIBRARY)},extractionSource,{extractionSource=null})
                        } }
                        composable(SecondaryDestination.BROWSER.route) {entry->scene(entry.destination) {
                            BrowserScreen(
                                onClose = { nav.closeSecondary() },
                                onDownload = { url ->
                                    model.input(url)
                                    model.analyze()
                                    nav.closeSecondary()
                                }
                            )
                        } }
                      }
                    }
            if(reportOpen) ProblemReportSheet(reportOpen,reportUrl,reportError) {reportOpen=false}
            val isFacebookAuth = ui.error == ErrorCode.AUTHENTICATION_REQUIRED &&
                (com.zdownloader.app.engine.FacebookScraper.isFacebookUrl(ui.text) ||
                 runCatching { com.zdownloader.app.domain.UrlPolicy.inputLinks(ui.text).any { com.zdownloader.app.engine.FacebookScraper.isFacebookUrl(it) } }.getOrDefault(false))
            if (isFacebookAuth) {
                AlertDialog(
                    onDismissRequest = model::clearError,
                    title = { Text(stringResource(R.string.facebook_auth_dialog_title)) },
                    text = { Text(stringResource(R.string.facebook_auth_dialog_message)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                model.clearError()
                                showFacebookLogin = true
                            },
                            modifier = Modifier.testTag("facebook_login_dialog_confirm")
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.facebook_auth_dialog_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = model::clearError) {
                            Text(stringResource(R.string.facebook_auth_dialog_dismiss))
                        }
                    }
                )
            } else if(ui.error != null) {
                AlertDialog(onDismissRequest=model::clearError,title={ Text(stringResource(R.string.problem)) },text={ Text(stringResource(errorResource(ui.error!!))) },confirmButton={ TextButton(onClick=model::clearError) { Text(stringResource(R.string.ok)) } },dismissButton={TextButton({reportUrl=ui.media?.url ?: ui.text;reportError=ui.error?.name.orEmpty();reportOpen=true;model.clearError()},Modifier.testTag("analysis_report_problem")) {Text(stringResource(R.string.report_action))}})
            }
            if (showFacebookLogin) {
                BrowserAuthDialog(
                    onDismiss = { showFacebookLogin = false },
                    onAuthSuccess = {
                        showFacebookLogin = false
                        model.analyze()
                    }
                )
            }
            PlaybackRequestError(playback)
            ui.duplicate?.let {DuplicateDialog(it,model::confirmDuplicate,model::cancelDuplicate,open={match->match.uri?.let {uri->playback.playUri(uri,open=true)}})}
            val playlistState by model.playlists.state.collectAsStateWithLifecycle()
            ClipboardDownloadPrompt(clipboard?.takeIf {ui.error==null&&ui.duplicate==null&&!playlistState.visible&&!ui.analyzing&&!ui.submitting},
                bottomClearance=if(primary!=null&&!keyboardVisible&&!navigationVisibility.suppressed) with(LocalDensity.current) {
                    (bottomBarHeight.intValue-WindowInsets.navigationBars.getBottom(this)).coerceAtLeast(0).toDp()
                } else 0.dp)
            if(playlistState.visible) PlaylistPreview(model.playlists,
                single={model.playlists.close();model.analyze(forceSingle=true)},close=model.playlists::close,
                queued={model.playlists.close();nav.selectPrimary(PrimaryDestination.DOWNLOADS)},selectQuality=model::analyzePlaylistItem)
             if(updater!=null) UpdateOffer(updater,settings.language,
                  startupReady&&primary!=null&&!keyboardVisible&&!ui.analyzing&&!ui.submitting&&ui.error==null&&ui.duplicate==null&&!playlistState.visible&&clipboard==null&&!reportOpen)
        }
    }
}

private data class NavigationChrome(val expanded:Boolean,val keyboardVisible:Boolean,val visibility:PrimaryNavigationVisibility,
    val playback:com.zdownloader.app.playback.PlaybackConnection,val snackbar:SnackbarHostState,val navigate:(PrimaryDestination)->Unit,
    val bottomBarHeight:MutableIntState)

@Composable private fun NavigationScene(destination:androidx.navigation.NavDestination,chrome:NavigationChrome,content:@Composable ()->Unit) {
    val selected=destination.primaryDestination()
    val showNavigation=destination.isPrimaryDestination()&&!chrome.visibility.suppressed
    val readingDirection=LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(Modifier.fillMaxSize().testTag("navigation_scene_${destination.route}").background(mediaFlowBackground()).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            if(chrome.expanded&&showNavigation) PrimaryNavigationRail(selected,chrome.navigate)
            CompositionLocalProvider(LocalLayoutDirection provides readingDirection) {
                Scaffold(Modifier.weight(1f),containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onBackground,
                    snackbarHost={if(showNavigation) SnackbarHost(chrome.snackbar)},
                    bottomBar={if(showNavigation&&!chrome.keyboardVisible) PrimarySceneBottomBar(selected,chrome) else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))}
                ) {padding->NavigationSceneContent(destination,padding,content)}
            }
        }
    }
}

@Composable private fun NavigationSceneContent(destination:androidx.navigation.NavDestination,padding:PaddingValues,content:@Composable ()->Unit) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),contentAlignment=Alignment.TopCenter) {
        Box(
            Modifier.widthIn(max=if(destination.route==SecondaryDestination.SETTINGS.route) 760.dp else 1120.dp)
                .fillMaxSize()
        ) {
            content()
        }
    }
}

@Composable private fun PrimarySceneBottomBar(selected:PrimaryDestination?,chrome:NavigationChrome) {
    Column(Modifier.onSizeChanged {chrome.bottomBarHeight.intValue=it.height}) {
        MiniPlayer(chrome.playback)
        if(!chrome.expanded) PremiumBottomNavigation(selected,chrome.navigate)
        else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable private fun PrimaryNavigationRail(primary:PrimaryDestination?,navigate:(PrimaryDestination)->Unit) {
    val colors=MaterialTheme.colorScheme
    val fontScale=LocalConfiguration.current.fontScale
    NavigationRail(Modifier.width(if(fontScale>1.2f) 120.dp else 88.dp).windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),containerColor=colors.surface,
        header={Image(painterResource(R.drawable.ic_brand_mark),stringResource(R.string.brand_symbol),Modifier.padding(vertical=16.dp).size(width=40.dp,height=30.dp))}) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PrimaryDestination.entries.forEach {destination->
                val selected=primary==destination
                NavigationRailItem(selected,onClick={navigate(destination)},modifier=Modifier.testTag("nav_${destination.route}"),
                    icon={Icon(when(destination) {PrimaryDestination.HOME->FlowIcons.Home;PrimaryDestination.DOWNLOADS->FlowIcons.Downloads;PrimaryDestination.LIBRARY->FlowIcons.Library},null,Modifier.size(22.dp))},
                    label={Text(stringResource(destination.label),style=MaterialTheme.typography.labelMedium,fontWeight=if(selected) FontWeight.SemiBold else FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)},alwaysShowLabel=true,
                    colors=NavigationRailItemDefaults.colors(indicatorColor=Color.Transparent,selectedIconColor=colors.primary,selectedTextColor=colors.primary,unselectedIconColor=colors.onSurfaceVariant,unselectedTextColor=colors.onSurfaceVariant))
            }
        }
    }
}

@Composable private fun ZCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content: @Composable ColumnScope.() -> Unit) {
    if(onClick!=null) {
        Card(onClick=onClick,modifier=modifier.fillMaxWidth().animateContentSize(tween(FlowMotion.Reveal)),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f)),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content) }
    } else {
        Card(modifier.fillMaxWidth().animateContentSize(tween(FlowMotion.Reveal)),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f)),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content) }
    }
}
@Composable private fun Title(text: String) { Text(text,style=MaterialTheme.typography.titleLarge) }
@Composable private fun Subtitle(text: String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium) }
@Composable private fun Empty(text: String, hint:String=stringResource(R.string.paste_hint)) {
    FlowEmptyState(text,hint,FlowIcons.Downloads)
}

@Composable private fun Home(ui: UiState, downloads: List<DownloadEntity>, model: ZViewModel, viewAll: () -> Unit, settings: () -> Unit, results:()->Unit,statusSaver:()->Unit,browser:()->Unit,playMedia:((DownloadEntity)->Unit)?=null,extractAudio:()->Unit) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val wide = screenWidthDp >= 760.dp
    val gutter = if(screenWidthDp < 360.dp) 16.dp else if(wide) 32.dp else 24.dp
    val linkFocus=remember {FocusRequester()}
    val homeScroll=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val requestLink:()->Unit={scope.launch {homeScroll.scrollToItem(0);withFrameNanos {};linkFocus.requestFocus()};Unit}
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.widthIn(max=1040.dp).fillMaxSize().align(Alignment.TopCenter)) {
      // The identity is a fixed app header, never part of the scrolling feed.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().padding(horizontal=gutter,vertical=10.dp).heightIn(min=54.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.mediaflow_mark),stringResource(R.string.brand_symbol),Modifier.size(42.dp).testTag("home_brand_logo"))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                MediaFlowWordmark(Modifier.testTag("brand_header"))
                Text(stringResource(R.string.studio_brand_detail),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowIconButton(FlowIcons.Settings,stringResource(R.string.settings),settings,Modifier.testTag("open_settings"))
        }
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("home_list"),state=homeScroll,contentPadding=PaddingValues(start=gutter,end=gutter,top=16.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item(key="home_composer",contentType="composer") {
            val composer:@Composable ()->Unit={FlowPanel(Modifier.testTag("home_capture_panel")) {
                FlowEyebrow(stringResource(R.string.design_capture_eyebrow),FlowIcons.Paste)
                Text(stringResource(R.string.design_home_headline),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.testTag("home_headline").semantics {heading()})
                Text(stringResource(R.string.design_home_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                LinkCapture(ui,model,linkFocus)
            }}
            if(wide) Row(horizontalArrangement=Arrangement.spacedBy(32.dp)) {Column(Modifier.weight(1.2f)) {composer()};Column(Modifier.weight(1f)) {HomeFeatures(ui,model,statusSaver,browser,requestLink,extractAudio)}}
            else composer()
        }
        if(ui.batch.isNotEmpty()) item(key="home_batch",contentType="batch") { Batch(ui,model) }
        if(ui.batchErrors>0) item(key="home_batch_errors",contentType="error") { Text(spectrumPluralStringResource(R.plurals.batch_failed,ui.batchErrors,ui.batchErrors),color=MaterialTheme.colorScheme.error) }
        if(ui.media!=null&&!ui.analyzing) item(key="home_reopen_qualities",contentType="media_card") {
            Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(ui.media.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    TextButton(onClick=results,modifier=Modifier.testTag("reopen_qualities")) {Text(stringResource(R.string.choose_preferred_quality))}
                }
            }
        }
        if(!wide) item(key="home_features",contentType="features") { HomeFeatures(ui,model,statusSaver,browser,requestLink,extractAudio) }
        if(downloads.isNotEmpty()) item(key="home_recent",contentType="recent") {RecentDownloads(downloads,viewAll,playMedia)}
      }
    } }
}

@Composable private fun HomeFeatures(ui:UiState,model:ZViewModel,statusSaver:()->Unit,browser:()->Unit,requestLink:()->Unit,extractAudio:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.studio_tools),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val tools=listOf(Triple(R.string.studio_browser,FlowIcons.Browser,"home_browser"),Triple(R.string.studio_playlists,FlowIcons.Playlist,"home_playlist"),Triple(R.string.studio_status,FlowIcons.Status,"home_status_saver"))
        val clicks:List<()->Unit> = listOf(browser,{if(ui.text.isNotBlank()&&!ui.analyzing) model.playlist() else requestLink()},statusSaver)
        if(LocalDensity.current.fontScale>1.3f) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            tools.forEachIndexed {i,(label,icon,tag)->Surface(onClick=clicks[i],color=Color.Transparent,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().testTag(tag)) {
                Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {FlowIconTile(icon);Text(stringResource(label),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Icon(FlowIcons.Next,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            }}
        } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            tools.forEachIndexed {i,(label,icon,tag)->Surface(onClick=clicks[i],color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(22.dp),
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f)),modifier=Modifier.weight(1f).testTag(tag)) {
                Column(Modifier.padding(horizontal=4.dp,vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    FlowIconTile(icon,listOf(MaterialTheme.colorScheme.secondary,MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.tertiary)[i],size=46.dp,iconSize=23.dp)
                    Text(stringResource(label),style=MaterialTheme.typography.labelMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }}
        }
        Surface(onClick=extractAudio,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.tertiaryContainer.copy(alpha=.38f),modifier=Modifier.fillMaxWidth().testTag("home_extract_audio")) {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FlowIconTile(FlowIcons.Audio,MaterialTheme.colorScheme.tertiary)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.extract_title),style=MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.extract_description),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(FlowIcons.Next,null,Modifier.size(18.dp))
            }
        }
    }
}

@Composable private fun LinkCapture(ui:UiState,model:ZViewModel,linkFocus:FocusRequester) {
    val context=LocalContext.current
    val keyboard=LocalSoftwareKeyboardController.current
    val interaction=remember {MutableInteractionSource()}
    val focused by interaction.collectIsFocusedAsState()
    val fieldFill by animateColorAsState(if(focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,tween(FlowMotion.Fast),label="urlFocusFill")
    val valid=remember(ui.text) { ui.text.isNotBlank()&&runCatching { UrlPolicy.inputLinks(ui.text).isNotEmpty() }.getOrDefault(false) }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val label=stringResource(R.string.media_link)
            val arabic=LocalConfiguration.current.locales[0].language=="ar"
            OutlinedTextField(ui.text,model::input,Modifier.fillMaxWidth().heightIn(min=60.dp).focusRequester(linkFocus).testTag("url_input").semantics {contentDescription=label},placeholder={ Text(stringResource(R.string.studio_link_placeholder),Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium,textAlign=if(arabic) androidx.compose.ui.text.style.TextAlign.Right else androidx.compose.ui.text.style.TextAlign.Left) },shape=RoundedCornerShape(16.dp),maxLines=3,isError=ui.text.isNotBlank()&&!valid,
                textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr),
                interactionSource=interaction,colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=fieldFill,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceContainerLow,unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant),
                trailingIcon={if(ui.text.isNotEmpty()) FlowIconButton(FlowIcons.Close,stringResource(R.string.clear),{model.input("")}) else FlowIconButton(FlowIcons.Paste,stringResource(R.string.paste),{val clipboard=context.getSystemService(android.content.ClipboardManager::class.java);model.input(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())},accent=focused)},
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Uri,imeAction=androidx.compose.ui.text.input.ImeAction.Go,autoCorrectEnabled=false),
                keyboardActions=androidx.compose.foundation.text.KeyboardActions(onGo={if(valid&&!ui.analyzing) {keyboard?.hide();model.analyze()}}))
        }
        if(ui.text.isNotBlank()&&!valid) Text(stringResource(R.string.link_validation),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        PrimaryAction(onClick={keyboard?.hide();model.analyze()},enabled=valid&&!ui.analyzing,modifier=Modifier.fillMaxWidth().testTag("analyze_link").semantics {liveRegion=LiveRegionMode.Polite}) {
            if(ui.analyzing) FlowLoadingIndicator(compact=true) else Icon(FlowIcons.Search,null,Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp));Text(stringResource(if(ui.analyzing) R.string.analyzing else R.string.studio_analyze),modifier=Modifier.weight(1f,fill=false),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
        androidx.compose.animation.AnimatedVisibility(ui.analyzing,enter=fadeIn(tween(140)),exit=fadeOut(tween(100))) { Column(Modifier.testTag("analysis_state"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=model::cancelAnalysis) {Text(stringResource(R.string.cancel_analysis))}
            ui.analysisPreview?.let { partial->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {MediaArtwork(partial.thumbnail,partial.title,Modifier.size(64.dp));Column(Modifier.weight(1f)) { Text(partial.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Subtitle(listOfNotNull(partial.source,partial.duration?.let(::duration)).joinToString(" · ")) }} }
        } }
        if(valid&&!ui.analyzing) TextButton(onClick=model::playlist) {Text(stringResource(R.string.analyze_playlist),style=MaterialTheme.typography.labelMedium)}
    }
}
@Composable private fun RecentDownloads(downloads:List<DownloadEntity>,viewAll:()->Unit,playMedia:((DownloadEntity)->Unit)?=null) {
    val clock=rememberMediaDayClock()
    val sections=remember(downloads,clock.zone.id) {mediaDaySections(downloads.take(3),{it.createdAt},clock.zone)}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { Text(stringResource(R.string.recent),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));TextButton(onClick=viewAll) {Text(stringResource(R.string.view_all))} }
        if(downloads.isEmpty()) Empty(stringResource(R.string.no_downloads),stringResource(R.string.library_hint))
        sections.forEach {section->
        MediaDayHeader(section.dayStart,clock)
        section.entries.forEach { entity->Surface(onClick={if(entity.status=="COMPLETED"&&playMedia!=null) playMedia(entity) else viewAll()},shape=RoundedCornerShape(14.dp),color=Color.Transparent) {
            Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                MediaArtwork(entity.thumbnail,entity.title,Modifier.size(48.dp),entity.uri)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(entity.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                    val selection=remember(entity.selection) {runCatching {Selection.parse(entity.selection)}.getOrNull()}
                    Text(listOfNotNull(selection?.takeUnless {it.audioOnly}?.primary?.let {qualityTitle(it)},selection?.output?.uppercase(),statusLabel(entity.status)).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(entity.status.startsWith("DOWNLOADING")) RecentDownloadTime(entity)
                }
                FlowStatusIcon(entity.status)
            }
        } }
        }
    }
}

@Composable private fun RecentDownloadTime(entity:DownloadEntity) {
    val progress=remember(entity.status,entity.bytes,entity.total,entity.speed,entity.sizeKind) {
        DownloadsPresentation.computeProgress(entity)
    }
    val remaining=progress.remainingSeconds?.let {stringResource(R.string.remaining,duration(it.toDouble()))}
        ?: stringResource(R.string.download_time_calculating)
    Text(remaining,Modifier.testTag("recent_download_time_${entity.id}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
}

@Composable private fun Batch(ui: UiState, model: ZViewModel) {
    var selected by remember(ui.batch) { mutableStateOf(ui.batch.toSet()) }
    var audio by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(720) }
    ZCard {
        Text(spectrumPluralStringResource(R.plurals.batch_title,ui.batch.size,ui.batch.size),fontWeight=FontWeight.Bold)
        Row { TextButton(onClick={selected=ui.batch.toSet()}) { Text(stringResource(R.string.select_all)) }; TextButton(onClick={selected=emptySet()}) { Text(stringResource(R.string.deselect_all)) } }
        ui.batch.take(200).forEach { url -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(url in selected,{checked -> selected=if(checked) selected+url else selected-url }); Text(url,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f)); FlowIconButton(FlowIcons.Close,stringResource(R.string.remove),{model.removeBatch(url)}) } }
        Row(verticalAlignment=Alignment.CenterVertically) { Text(stringResource(R.string.audio_only),modifier=Modifier.weight(1f)); Switch(audio,{audio=it}) }
        if(!audio) { Subtitle(stringResource(R.string.batch_quality)); FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(480,720,1080).forEach { n -> FilterChip(selected=height==n,onClick={height=n},label={Text("≤ ${n}p")}) } } }
        Button(onClick={model.batchDownload(selected.toList(),audio,height)},enabled=selected.isNotEmpty()&&!ui.analyzing,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.enqueue_selected)) }
    }
}

@Composable internal fun Preview(media: Media, settings: Settings, model: ZViewModel, back: () -> Unit, folder: () -> Unit, enqueue: (Selection,String) -> Unit) {
    if(media.videoOptions().isEmpty()&&media.originalImage()!=null) {
        OriginalImagePreview(media,settings,model,ImagePreviewActions(back,folder,enqueue))
        return
    }
    val ui by model.ui.collectAsStateWithLifecycle()
    var audio by rememberSaveable(media.url) { mutableStateOf(settings.downloadType=="audio" || media.videoOptions().isEmpty()) }
    var chosenId by rememberSaveable(media.url) { mutableStateOf(media.recommendedVideo(settings.preferredHeight)?.id) }
    val chosen=media.formats.firstOrNull { it.id==chosenId }
    var audioOutput by rememberSaveable(media.url) { mutableStateOf("original") }
    var videoOutput by rememberSaveable(media.url) { mutableStateOf("") }
    var audioSourceId by rememberSaveable(media.url) { mutableStateOf(runCatching { Selection.audio(media,"original").primary.id }.getOrNull()) }
    var bitrate by rememberSaveable(media.url) { mutableIntStateOf(192) }
    var title by rememberSaveable(media.url) { mutableStateOf(media.title) }
    var formatsOpen by rememberSaveable(media.url) { mutableStateOf(false) }
    var schedule by remember {mutableStateOf(false)}
    var advanced by rememberSaveable(media.url) {mutableStateOf(false)}
    var videoOnly by rememberSaveable(media.url) {mutableStateOf(false)}
    var subtitle by rememberSaveable(media.url) {mutableStateOf<String?>(null)}
    var metadata by rememberSaveable(media.url) {mutableStateOf(true)}
    var creatorName by rememberSaveable(media.url) {mutableStateOf(false)}
    val selection = remember(media,chosen,audio,audioOutput,videoOutput,bitrate,audioSourceId,videoOnly,subtitle,metadata) { runCatching {
        val base=if(audio) Selection.audio(media,audioOutput,media.formats.firstOrNull { it.id==audioSourceId },bitrate) else chosen?.let {Selection.video(media,it,videoOutput.takeIf {it.isNotBlank()})} ?: return@runCatching null
        require(audio||subtitle==null||base.output in setOf("mp4","mkv"))
        base.copy(videoOnly=!audio&&videoOnly,secondary=if(!audio&&videoOnly) null else base.secondary,subtitle=if(audio) null else media.subtitles.firstOrNull {it.language==subtitle},embedMetadata=metadata,creator=if(metadata) base.creator else null)
    }.getOrNull() }
    val outputTitle=if(creatorName&&!media.uploader.isNullOrBlank()) "$title - ${media.uploader}" else title
    if(formatsOpen) FormatPicker(media,audio,chosenId,audioOutput,videoOutput,bitrate,audioSourceId,{audio=it},{chosenId=it},{audioOutput=it},{videoOutput=it},{bitrate=it},{audioSourceId=it}) { formatsOpen=false }
    if(schedule) ScheduleDialog({schedule=false}) {plan->schedule=false;selection?.let {model.enqueue(media,it,outputTitle,plan=plan)}}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) { FlowIconButton(FlowIcons.Back,stringResource(R.string.back),back); Text(stringResource(R.string.studio_download_options),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f)) }
        LazyColumn(Modifier.weight(1f).testTag("preview_list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        if(media.thumbnail!=null) item { Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { MediaArtwork(media.thumbnail,media.title,Modifier.widthIn(max=640.dp).fillMaxWidth().aspectRatio(16f/9f)) } }
        item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(media.title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=TextOverflow.Ellipsis); Subtitle(listOfNotNull(media.uploader,media.source,media.duration?.let(::duration)).joinToString(" · ")); MediaTypeControl(audio,media.videoOptions().isNotEmpty(),media.audioOptions().isNotEmpty()) { audio=it } } }
        item { ZCard {
            Text(stringResource(if(audio) R.string.audio_format else R.string.available_quality),fontWeight=FontWeight.Bold)
            if(!audio) Subtitle(chosen?.let {qualityTitle(it)} ?: stringResource(R.string.share_unknown_quality))
            if(!audio) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { val groups=QuickShareCatalog.compactGroups(media.qualityGroups(),chosen?.id); groups.forEach { g ->
                val active=g.candidates.firstOrNull {it.id==chosen?.id}
                val format=active ?: g.recommended
                val size=runCatching {Selection.video(media,format,videoOutput.takeIf {active!=null&&it.isNotBlank()}).outputSize}.getOrNull()
                FilterChip(selected=active!=null,onClick={if(active==null) chosenId=g.recommended.id},label={Text(qualityTitle(format)+" — "+sizeLabel(size))}) } }
            if(!audio) chosen?.let {f->encodedDimensionsText(f)?.let {Subtitle(it)}}
            selection?.let { Subtitle("${it.output.uppercase()} · ${sizeLabel(it.outputSize)}") }
            if(selection?.transcodeVideo==true) Text(stringResource(R.string.conversion_notice),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick={formatsOpen=true},modifier=Modifier.fillMaxWidth().testTag("choose_format")) { Icon(FlowIcons.Tune,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(stringResource(if(audio) R.string.choose_format else R.string.share_more_section_formats)) }
        } }
        item { ZCard { OutlinedTextField(title,{title=it},Modifier.fillMaxWidth(),label={Text(stringResource(R.string.filename))},shape=RoundedCornerShape(14.dp)); Subtitle(settings.destination?.let { stringResource(R.string.selected_folder,scopedFolderLabel(it)) } ?: stringResource(R.string.default_folder)); TextButton(onClick=folder) { Text(stringResource(R.string.choose_folder)) }; if(Build.VERSION.SDK_INT<29&&settings.destination==null) Text(stringResource(R.string.legacy_folder),color=MaterialTheme.colorScheme.error) } }
        item {OutlinedButton({schedule=true},enabled=selection!=null&&!ui.submitting&&(Build.VERSION.SDK_INT>=29||settings.destination!=null),modifier=Modifier.fillMaxWidth().testTag("schedule_download")) {Icon(androidx.compose.material.icons.Icons.Outlined.Schedule,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.schedule_download))}}
        item {TextButton({advanced=!advanced},Modifier.testTag("advanced_download_options")) {Text(stringResource(R.string.advanced_download_options))}}
        if(advanced) item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!audio) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(videoOnly,{videoOnly=it},modifier=Modifier.testTag("advanced_video_only"));Text(stringResource(R.string.advanced_video_only))}
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(metadata,{metadata=it});Text(stringResource(R.string.advanced_metadata))}
            if(!media.uploader.isNullOrBlank()) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(creatorName,{creatorName=it});Text(stringResource(R.string.advanced_creator_filename))}
            if(!audio&&media.subtitles.isNotEmpty()) {Text(stringResource(R.string.advanced_subtitle_hint),style=MaterialTheme.typography.bodySmall);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(subtitle==null,{subtitle=null},label={Text(stringResource(R.string.advanced_no_subtitles))})
                media.subtitles.forEach {track->FilterChip(subtitle==track.language,{subtitle=track.language},label={Text(java.util.Locale.forLanguageTag(track.language).getDisplayLanguage(LocalConfiguration.current.locales[0]).ifBlank {track.language})})}
            }}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ThumbnailExport(media.thumbnail,title) {model.reportError(ErrorCode.FILE_WRITE_FAILED)}
                MediaPackageExport(media,selection,title) {model.reportError(ErrorCode.FILE_WRITE_FAILED)}
            }
        }}
        }
        Surface(color=MaterialTheme.colorScheme.background,shadowElevation=0.dp) {
            PrimaryAction(
                onClick={selection?.let { enqueue(it,outputTitle) }},
                enabled=selection!=null&&title.isNotBlank()&&!ui.submitting&&(Build.VERSION.SDK_INT>=29||settings.destination!=null),
                modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp).testTag("start_download")
            ) {
                if(ui.submitting) {
                    FlowLoadingIndicator(compact=true)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.download_adding))
                } else {
                    Icon(FlowIcons.Downloads,null,Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${stringResource(R.string.download)} • ${sizeLabel(selection?.outputSize)}")
                }
            }
        }
    }
}

@Composable private fun Downloads(downloads: List<DownloadEntity>, model: ZViewModel, play: (DownloadEntity)->Unit) {
    val batches by model.batchSummaries.collectAsStateWithLifecycle()
    var batchFilter by rememberSaveable {mutableStateOf<String?>(null)}
    var filter by rememberSaveable { mutableStateOf("all") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val downloadIds = remember(downloads) { downloads.filter { it.batchId == null }.map { it.id }.toSet() }
    LaunchedEffect(downloadIds) { selected = selected.intersect(downloadIds) }
    val onCardChecked = remember { { id: String, checked: Boolean -> selected = if (checked) selected + id else selected - id } }
    if(batchFilter!=null) {
        val currentBatch = batches.firstOrNull { it.batch.id == batchFilter }
        PlaylistDetailScreen(
            batchId = batchFilter!!,
            summary = currentBatch,
            model = model,
            play = play,
            onBack = { batchFilter = null }
        )
        return
    }
    val activeFilter = remember(filter) { DownloadFilter.fromKey(filter) }
    val visibleBatches = remember(batches, activeFilter, searchQuery) {
        DownloadsPresentation.filterBatches(batches, activeFilter, searchQuery)
    }
    val visible = remember(downloads, activeFilter, searchQuery) {
        DownloadsPresentation.filterDownloads(downloads, activeFilter, searchQuery)
    }
    val motionEnabled = LocalFlowMotionEnabled.current
    val itemFadeIn = if (motionEnabled) tween<Float>(FlowMotion.Fast) else null
    val itemPlacement = if (motionEnabled) tween<IntOffset>(FlowMotion.Fast) else null
    val itemFadeOut = if (motionEnabled) tween<Float>(FlowMotion.Fast) else null
    val clock=rememberMediaDayClock()
    val daySections=remember(visible,clock.zone.id) {mediaDaySections(visible,{it.createdAt},clock.zone)}
    val canContinue = remember(downloads, batches) { DownloadsPresentation.canContinueQueue(downloads, batches) }
    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),modifier=Modifier.fillMaxSize().testTag("downloads_grid"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FlowIconTile(FlowIcons.Downloads, size = 44.dp)
                    Text(
                        text = stringResource(R.string.downloads),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DownloadsStatusMenu(
                    currentFilter = filter,
                    onFilterSelected = { filter = it },
                    downloads = downloads,
                    batches = batches
                )
            }
        }
        item(span={GridItemSpan(maxLineSpan)}) {
            Text(stringResource(R.string.design_downloads_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(span={GridItemSpan(maxLineSpan)}) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("downloads_search"),
                placeholder = { Text(stringResource(R.string.downloads_search_placeholder)) },
                singleLine = true,
                leadingIcon = { Icon(FlowIcons.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        FlowIconButton(FlowIcons.Close, stringResource(R.string.downloads_clear_search), { searchQuery = "" }, Modifier.testTag("downloads_clear_search"))
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
        if(visibleBatches.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(stringResource(R.string.pl_title),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=4.dp)) }
        gridItems(visibleBatches,key={"batch:${it.batch.id}"},span={GridItemSpan(maxLineSpan)}) {batch->
            Box(Modifier.animateItem(fadeInSpec=itemFadeIn,placementSpec=itemPlacement,fadeOutSpec=itemFadeOut)) {
                Column {
                    PlaylistBatchCard(batch,{model.pauseBatch(batch.batch.id)},{model.resumeBatch(batch.batch.id)},{model.cancelBatch(batch.batch.id)},{model.resumeBatch(batch.batch.id,true)},{batchFilter=batch.batch.id},expanded=false,remove={model.removeCancelledBatch(batch.batch.id)})
                    if(batch.status=="SCHEDULED") {ScheduleSummary(batch.scheduledAt,batch.scheduleNetwork,batch.requiresCharging);FlowRow {TextButton({model.startScheduled(batch.batch.id,true)}) {Text(stringResource(R.string.schedule_start_now))};TextButton({model.cancelBatch(batch.batch.id)}) {Text(stringResource(R.string.schedule_cancel))};TextButton({model.retrySchedule(batch.batch.id,true)}) {Text(stringResource(R.string.retry))}}}
                }
            }
        }
        if(visible.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text(stringResource(R.string.mf_individual_downloads),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=4.dp)) }
        if(selected.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { ZCard { Text(spectrumPluralStringResource(R.plurals.selected_count,selected.size,selected.size)); Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton(onClick={selected.forEach(model::pause)}) { Text(stringResource(R.string.pause)) }; TextButton(onClick={selected.forEach(model::resume)}) { Text(stringResource(R.string.resume)) }; TextButton(onClick={selected.forEach(model::cancel);selected=emptySet()}) { Text(stringResource(R.string.cancel)) }; TextButton(onClick={selected.forEach(model::remove);selected=emptySet()}) { Text(stringResource(R.string.remove_history)) };TextButton({selected=emptySet()},Modifier.testTag("downloads_clear_selection")) {Text(stringResource(R.string.downloads_clear_selection))} } } }
        if(visible.isEmpty()&&visibleBatches.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
            if(searchQuery.isNotBlank()) FlowEmptyState(stringResource(R.string.downloads_no_search_results),stringResource(R.string.search_hint),FlowIcons.Search)
            else if(filter=="all") Empty(stringResource(R.string.no_downloads),"")
            else FlowEmptyState(stringResource(R.string.downloads_filter_empty),stringResource(R.string.downloads_filter_empty_hint),FlowIcons.Downloads)
        }
        daySections.forEach {section->
            item(key="download-day:${section.dayStart}",span={GridItemSpan(maxLineSpan)}) {MediaDayHeader(section.dayStart,clock)}
            gridItems(section.entries,key={it.id}) { entity ->
                Box(Modifier.animateItem(fadeInSpec=itemFadeIn,placementSpec=itemPlacement,fadeOutSpec=itemFadeOut)) {DownloadCard(entity,model,play,entity.id in selected) { checked->onCardChecked(entity.id, checked) }}
            }
        }
        if(canContinue) item(span={GridItemSpan(maxLineSpan)}) {TextButton(onClick=model::continueQueue,modifier=Modifier.testTag("continue_pending_downloads")) {Text(stringResource(R.string.continue_queue))}}
    }
}
@Composable private fun PlaylistItemDetail(entity:DownloadEntity,model:ZViewModel,play:(DownloadEntity)->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val isCompleted=entity.status=="COMPLETED"
    Surface(onClick={if(isCompleted) play(entity) else Unit},enabled=isCompleted,modifier=modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("playlist_child_${entity.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                MediaArtwork(entity.thumbnail,entity.title,Modifier.size(48.dp),entity.uri)
                Column(Modifier.weight(1f)) {
                    Text("${entity.playlistPosition}. ${entity.title}",style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    val selection=remember(entity.selection) {runCatching {Selection.parse(entity.selection)}.getOrNull()}
                    selection?.let {Text(listOfNotNull(if(it.audioOnly) null else qualityTitle(it.primary),it.output.uppercase()).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
            }
            Text(if(entity.selection.isBlank()&&entity.status=="DOWNLOADING") stringResource(R.string.pl_analyzing_item) else downloadStateLabel(entity),style=MaterialTheme.typography.bodySmall)
            Text(bytesLabel(entity.bytes),style=MaterialTheme.typography.bodySmall)
            if(entity.total!=null&&entity.total>0&&entity.status.startsWith("DOWNLOADING")) MediaFlowProgress((entity.bytes.toFloat()/entity.total).coerceIn(0f,.99f))
            entity.error?.let {Text(stringResource(errorResource(ErrorCode.parse(it))),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            FlowRow {
                if(entity.status=="COMPLETED") {
                    TextButton({play(entity)}) {Text(stringResource(R.string.play))}
                    TextButton({openFile(context,entity,false)}) {Text(stringResource(R.string.open))}
                }
                else if(entity.status!="CANCELLED") {
                    if(entity.status in setOf("PAUSED","INTERRUPTED","FAILED")) TextButton({model.resume(entity.id)}) {Text(stringResource(if(entity.status=="FAILED") R.string.retry else R.string.resume))}
                    else TextButton({model.pause(entity.id)}) {Text(stringResource(R.string.pause))}
                    TextButton({model.cancel(entity.id)}) {Text(stringResource(R.string.cancel))}
                }
                 if(entity.canRetryInDefaultDestination())
                    TextButton({model.retryInDefaultDestination(entity.id)},Modifier.testTag("retry_default_destination_${entity.id}")) {
                        Text(stringResource(R.string.retry_default_destination))
                    }
            }
        }
    }
}
private fun DownloadEntity.canRetryInDefaultDestination() =
    Build.VERSION.SDK_INT>=29&&status=="FAILED"&&!destination.isNullOrBlank()&&pendingUri==null&&
        (error==ErrorCode.STORAGE_PERMISSION_ERROR.name||error==ErrorCode.STORAGE_DESTINATION_UNAVAILABLE.name)

@Composable internal fun DownloadCard(entity: DownloadEntity,model: ZViewModel,play: (DownloadEntity)->Unit,checked: Boolean,select:(Boolean)->Unit) {
    var reportOpen by rememberSaveable(entity.id) {mutableStateOf(false)}
    var details by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val selection=remember(entity.selection) { runCatching { Selection.parse(entity.selection) }.getOrNull() }
    val isCompleted=entity.status=="COMPLETED"
    val progressInfo = remember(entity.status, entity.bytes, entity.total, entity.speed, entity.sizeKind) {
        DownloadsPresentation.computeProgress(entity)
    }

    ZCard(modifier=Modifier.testTag("download_card_${entity.id}"),onClick=if(isCompleted) {{ play(entity) }} else null) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).background(when(entity.status) {"COMPLETED"->flowSuccessColor();"FAILED"->MaterialTheme.colorScheme.error;else->MaterialTheme.colorScheme.primary},RoundedCornerShape(50)))
            Text(entity.source.ifBlank {"MediaFlow"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(checked) Checkbox(true,select) else Surface(onClick={if(isCompleted) play(entity) else details=true},shape=RoundedCornerShape(12.dp),color=Color.Transparent) {MediaArtwork(entity.thumbnail,entity.title,Modifier.size(52.dp),entity.uri,selection?.audioOnly==true)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(entity.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                selection?.let {
                    Text(listOfNotNull(if(it.audioOnly) stringResource(R.string.audio) else qualityTitle(it.primary),it.output.uppercase()).joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(entity.status in listOf("DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","QUEUED","WAITING_FOR_NETWORK","RETRYING","RESUMING","WAITING_FOR_PROCESSOR","PROCESSING","MERGING","CONVERTING","VALIDATING","SAVING")) {
                FlowIconButton(FlowIcons.Pause,stringResource(R.string.pause),{model.pause(entity.id)})
            } else if(entity.status in listOf("PAUSED","INTERRUPTED","FAILED")) {
                FlowIconButton(if(entity.status=="FAILED") FlowIcons.Retry else FlowIcons.Play,stringResource(if(entity.status=="FAILED") R.string.retry else R.string.resume),{model.resume(entity.id)},accent=true)
            }
            Box {
                FlowIconButton(FlowIcons.More,stringResource(R.string.details),{menu=true})
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text(stringResource(R.string.details))},onClick={menu=false;details=true})
                    DropdownMenuItem(text={Text(stringResource(R.string.select_download))},onClick={menu=false;select(!checked)})
                    if(entity.status in listOf("QUEUED","RETRYING","RESUMING","WAITING_FOR_NETWORK")) DropdownMenuItem(text={Text(stringResource(R.string.pl_prioritize))},onClick={menu=false;model.prioritize(entity.id)})
                    if(isCompleted) {
                        DropdownMenuItem(text={Text(stringResource(R.string.open))},onClick={menu=false;openFile(context,entity,false)})
                        DropdownMenuItem(text={Text(stringResource(R.string.share))},onClick={menu=false;openFile(context,entity,true)})
                    }
                    if(entity.status !in listOf("COMPLETED","CANCELLED")) DropdownMenuItem(text={Text(stringResource(R.string.cancel))},onClick={menu=false;model.cancel(entity.id)})
                    if(entity.status in listOf("COMPLETED","CANCELLED","FAILED","PAUSED","INTERRUPTED")) DropdownMenuItem(text={Text(stringResource(R.string.remove_history))},onClick={menu=false;model.remove(entity.id)})
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            val tint=when(entity.status) { "COMPLETED"->if(MaterialTheme.colorScheme.background==Spectrum.Dark.background) Spectrum.Success else Color(0xFF126B4E);"FAILED"->MaterialTheme.colorScheme.error;else->MaterialTheme.colorScheme.primary }
            FlowStatusIcon(entity.status)
            val label=if(entity.selection.isBlank()&&entity.status=="DOWNLOADING") stringResource(R.string.pl_analyzing_item) else downloadStateLabel(entity)
            Crossfade(label,animationSpec=tween(if(LocalFlowMotionEnabled.current) FlowMotion.Fast else 0),label="downloadStateLabel") {
                Text(it,color=tint,style=MaterialTheme.typography.labelMedium,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            }
        }
        if(entity.status.startsWith("DOWNLOADING")) {
            val measurable=entity.total?.takeIf {it>0&&it>=entity.bytes&&entity.sizeKind!="UNKNOWN"}
            val isEstimated=entity.sizeKind!="EXACT"
            if(progressInfo.progress != null && measurable != null && progressInfo.percent != null) {
                Text(stringResource(if(isEstimated) R.string.estimated_percent else R.string.transfer_percent, progressInfo.percent),style=MaterialTheme.typography.labelMedium)
                MediaFlowProgress(progressInfo.progress)
            } else {
                FlowLoadingIndicator()
            }
            val amount=measurable?.let {"${bytesLabel(entity.bytes)} / ${if(isEstimated) stringResource(R.string.estimated_size,bytesLabel(it)) else bytesLabel(it)}"}
                ?: stringResource(R.string.pl_transferred,bytesLabel(entity.bytes))
            Text(amount,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("download_bytes_${entity.id}"))
            if(progressInfo.validSpeed != null) {
                val speedText = stringResource(R.string.speed_value, bytesLabel(progressInfo.validSpeed))
                val etaText = progressInfo.remainingSeconds?.let { " · " + stringResource(R.string.remaining, duration(it.toDouble())) } ?: ""
                Subtitle(speedText + etaText)
            }
        } else if(entity.status in listOf("WAITING_FOR_PROCESSOR","PROCESSING","MERGING","CONVERTING","VALIDATING","SAVING")) {
            AudioProcessingIndicator(entity.id,entity.status)
        }
        if(isCompleted) {
            val sizeLabel = entity.outputSize?.let { bytesLabel(it) }
            val locale = LocalConfiguration.current.locales[0]
            val completedSubtitle = remember(sizeLabel, entity.finishedAt, locale) {
                listOfNotNull(sizeLabel, entity.finishedAt?.let { DateFormat.getDateInstance(DateFormat.DEFAULT,locale).format(java.util.Date(it)) }).joinToString(" · ")
            }
            Subtitle(completedSubtitle)
        }
        if(entity.scheduledAt>0&&entity.status !in setOf("COMPLETED","CANCELLED")) {
            ScheduleSummary(entity.scheduledAt,entity.scheduleNetwork,entity.requiresCharging)
            FlowRow {
                if(entity.status in setOf("SCHEDULED","WAITING_FOR_NETWORK","QUEUED","RESUMING","RETRYING","PAUSED","INTERRUPTED")) TextButton({model.startScheduled(entity.id)}) {Text(stringResource(R.string.schedule_start_now))}
                if(entity.status=="SCHEDULED") TextButton({model.cancel(entity.id)}) {Text(stringResource(R.string.schedule_cancel))}
                if(entity.status=="SCHEDULED"&&entity.error!=null) TextButton({model.retrySchedule(entity.id)}) {Text(stringResource(R.string.retry))}
            }
        }
        entity.error?.takeUnless {entity.status=="COMPLETED"}?.let { Surface(color=MaterialTheme.colorScheme.error.copy(alpha=.07f),shape=RoundedCornerShape(12.dp)) {Text(stringResource(errorResource(ErrorCode.parse(it))),modifier=Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=if(entity.status=="FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)} }
        if(entity.status=="FAILED" && selection?.audioOnly==false && selection.output=="mp4" && entity.error in listOf("ENCODER_UNAVAILABLE","CONVERSION_FAILED")) TextButton(onClick={model.useMkv(entity.id)}) {Text(stringResource(R.string.save_mkv))}
         if(entity.canRetryInDefaultDestination())
            TextButton({model.retryInDefaultDestination(entity.id)},Modifier.testTag("retry_default_destination_${entity.id}")) {
                Text(stringResource(R.string.retry_default_destination))
            }
        if(entity.status=="FAILED") TextButton({reportOpen=true},Modifier.testTag("download_report_${entity.id}")) {Text(stringResource(R.string.report_action))}
    }
    ProblemReportSheet(reportOpen,entity.url,entity.error.orEmpty()) {reportOpen=false}
    if(details) {SecondaryNavigationOverlay();AlertDialog(onDismissRequest={details=false},title={Text(stringResource(R.string.details))},text={Text("${entity.title}\n${entity.filename}\n${entity.source}\n${selection?.primary?.let(::encodedDimensionsText).orEmpty()}\n${entity.error?.takeUnless {entity.status=="COMPLETED"}?.let {stringResource(errorResource(ErrorCode.parse(it)))} ?: ""}\n${bytesLabel(entity.outputSize ?: entity.bytes)}",modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={details=false}) { Text(stringResource(R.string.ok)) }})}
}

@Composable private fun Library(downloads: List<DownloadEntity>,model: ZViewModel,play:(DownloadEntity,List<DownloadEntity>)->Unit) {
    var audio by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    val playback=remember(context) {(context.applicationContext as com.zdownloader.app.ZApplication).playback}
    var delete by remember { mutableStateOf<DownloadEntity?>(null) }
    var rename by remember { mutableStateOf<DownloadEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("newest") }
    val locale=LocalConfiguration.current.locales[0]
    val visible=remember(downloads,audio,search,sort,locale) {
        val filtered=downloads.filter { row -> runCatching { Selection.parse(row.selection).audioOnly==audio }.getOrDefault(false) && (row.filename.contains(search,true)||row.title.contains(search,true)) }
        when(sort) { "name"->{val collator=java.text.Collator.getInstance(locale);filtered.sortedWith { a,b -> collator.compare(a.filename,b.filename) }};"size"->filtered.sortedByDescending { it.outputSize ?: 0 };else->filtered.sortedByDescending { it.finishedAt ?: it.createdAt } }
    }
    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {MediaTypeControl(audio,true,true) {audio=it}}
        item(span={GridItemSpan(maxLineSpan)}) { OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("library_search"),label={Text(stringResource(R.string.search_library))},singleLine=true,leadingIcon={Icon(FlowIcons.Search,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)},trailingIcon={if(search.isNotEmpty()) FlowIconButton(FlowIcons.Close,stringResource(R.string.clear),{search=""})},shape=RoundedCornerShape(18.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surfaceContainerHigh,unfocusedBorderColor=MaterialTheme.colorScheme.outline)) }
        item(span={GridItemSpan(maxLineSpan)}) { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("newest" to R.string.sort_newest,"name" to R.string.sort_name,"size" to R.string.sort_size).forEach { (key,label) -> FilterChip(sort==key,{sort=key},label={Text(stringResource(label))},modifier=Modifier.heightIn(min=48.dp).testTag("sort_$key")) } } }
        if(visible.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Empty(stringResource(if(search.isBlank()) R.string.library_empty else R.string.no_search_results),stringResource(if(search.isBlank()) R.string.library_hint else R.string.search_hint)) }
        gridItems(visible,key={it.id}) { entity ->
            var menu by remember { mutableStateOf(false) }
            Surface(onClick={play(entity,visible)},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.testTag("library_item_${entity.id}")) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(onClick={play(entity,visible)},shape=RoundedCornerShape(16.dp)) {MediaArtwork(entity.thumbnail,entity.title,Modifier.size(56.dp),entity.uri,audio)}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(androidx.core.text.BidiFormatter.getInstance().unicodeWrap(entity.filename),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val selection=remember(entity.selection) {runCatching {Selection.parse(entity.selection)}.getOrNull()}
                        Text(listOfNotNull(selection?.primary?.takeIf {!audio}?.let {qualityTitle(it)},selection?.output?.uppercase(),entity.outputSize?.let {bytesLabel(it)}).joinToString(" · "),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        entity.duration?.let {Text(duration(it),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    Box {
                        FlowIconButton(FlowIcons.More,stringResource(R.string.details),{menu=true})
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text(stringResource(R.string.play))},onClick={menu=false;play(entity,visible)},leadingIcon={Icon(FlowIcons.Play,null,Modifier.size(22.dp))})
                            DropdownMenuItem(text={Text(stringResource(R.string.player_play_next))},onClick={menu=false;PlaybackMedia.from(entity)?.let {playback.enqueue(it,true)}},leadingIcon={Icon(FlowIcons.Playlist,null,Modifier.size(22.dp))})
                            DropdownMenuItem(text={Text(stringResource(R.string.player_add_queue))},onClick={menu=false;PlaybackMedia.from(entity)?.let {playback.enqueue(it)}},leadingIcon={Icon(FlowIcons.Playlist,null,Modifier.size(22.dp))})
                            DropdownMenuItem(text={Text(stringResource(R.string.open))},onClick={menu=false;openFile(context,entity,false)},leadingIcon={Icon(FlowIcons.Open,null,Modifier.size(22.dp))})
                            DropdownMenuItem(text={Text(stringResource(R.string.share))},onClick={menu=false;openFile(context,entity,true)},leadingIcon={Icon(FlowIcons.Share,null,Modifier.size(22.dp))})
                            DropdownMenuItem(text={Text(stringResource(R.string.rename))},onClick={menu=false;rename=entity;name=entity.filename},leadingIcon={Icon(FlowIcons.Edit,null,Modifier.size(22.dp))})
                            DropdownMenuItem(text={Text(stringResource(R.string.delete))},onClick={menu=false;delete=entity},leadingIcon={Icon(FlowIcons.Delete,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.error)})
                        }
                    }
                }
            }
        }
    }
    delete?.let { item -> AlertDialog(onDismissRequest={delete=null},title={Text(stringResource(R.string.delete))},text={Text(stringResource(R.string.delete_confirm))},confirmButton={TextButton(onClick={model.deleteFile(item);delete=null}) { Text(stringResource(R.string.delete)) }},dismissButton={TextButton(onClick={delete=null}) { Text(stringResource(R.string.cancel)) }}) }
    rename?.let { item -> RenameMediaDialog(name,{name=it},{rename=null}) {model.rename(item,name);rename=null} }
}
@Composable internal fun RenameMediaDialog(name:String,change:(String)->Unit,dismiss:()->Unit,save:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.rename))},
        text={OutlinedTextField(name,change,label={Text(stringResource(R.string.filename))},singleLine=true,
            isError=name.isBlank(),supportingText={if(name.isBlank()) Text(stringResource(R.string.filename_required))},modifier=Modifier.testTag("rename_filename"))},
        confirmButton={TextButton(save,enabled=name.isNotBlank(),modifier=Modifier.testTag("rename_save")) {Text(stringResource(R.string.save))}},
        dismissButton={TextButton(dismiss) {Text(stringResource(R.string.cancel))}})
}
internal fun openFile(context: android.content.Context,entity: DownloadEntity,share:Boolean) {
    val uri=Uri.parse(entity.uri ?: return)
    val audio=runCatching { Selection.parse(entity.selection).audioOnly }.getOrDefault(false)
    runCatching { val intent=if(share) Intent(Intent.ACTION_SEND).setType(if(audio) "audio/*" else "video/*").putExtra(Intent.EXTRA_STREAM,uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri,if(audio) "audio/*" else "video/*"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.clipData=ClipData.newRawUri(entity.filename,uri);context.startActivity(Intent.createChooser(intent,context.getString(if(share) R.string.share else R.string.open))) }.onFailure { android.widget.Toast.makeText(context,R.string.file_missing,android.widget.Toast.LENGTH_LONG).show() }
}

@Composable private fun downloadStateLabel(entity:DownloadEntity):String =
    if(entity.scheduledAt>0&&entity.status in setOf("WAITING_FOR_NETWORK","INTERRUPTED")) stringResource(R.string.schedule_waiting_continue) else statusLabel(entity.status)

@Composable fun statusLabel(value:String):String = stringResource(when(value) { "SCHEDULED"->R.string.schedule_state;"WAITING_FOR_PROCESSOR"->R.string.waiting_processing;"DOWNLOADING_VIDEO"->R.string.downloading_video;"DOWNLOADING_AUDIO"->R.string.downloading_audio;"DOWNLOADING"->R.string.downloading;"PROCESSING"->R.string.processing;"MERGING"->R.string.merging;"CONVERTING"->R.string.converting;"VALIDATING"->R.string.validating;"RETRYING"->R.string.retrying;"SAVING"->R.string.saving;"RESUMING"->R.string.resuming;"COMPLETED"->R.string.completed;"FAILED"->R.string.failed;"CANCELLED"->R.string.cancelled;"PAUSED"->R.string.paused;"PAUSING"->R.string.pausing;"INTERRUPTED"->R.string.interrupted;"WAITING_FOR_NETWORK"->R.string.waiting_network;else->R.string.queued })
fun size(bytes:Long):String = when { bytes<1024->"$bytes B";bytes<1024*1024->"%.1f KB".format(bytes/1024.0);bytes<1024L*1024*1024->"%.1f MB".format(bytes/(1024.0*1024));else->"%.2f GB".format(bytes/(1024.0*1024*1024)) }
fun duration(seconds:Double):String { val n=seconds.toLong(); return if(n>=3600) String.format(Locale.ROOT,"%d:%02d:%02d",n/3600,n/60%60,n%60) else String.format(Locale.ROOT,"%d:%02d",n/60,n%60) }
@Composable internal fun bytesLabel(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(LocalSpectrumContext.current,bytes.coerceAtLeast(0))
@Composable internal fun MediaArtwork(url:String?,title:String,modifier:Modifier,localUri:String?=null,audio:Boolean=false) {
    var remoteFailed by remember(url) { mutableStateOf(url==null) }
    var localFailed by remember(localUri) {mutableStateOf(false)}
    var frameAttempt by remember(localUri) {mutableIntStateOf(0)}
    val context=LocalContext.current
    val embedded by produceState<ByteArray?>(null,localUri,audio,remoteFailed) {
        if(audio && remoteFailed && localUri!=null) value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {val retriever=android.media.MediaMetadataRetriever();try {retriever.setDataSource(context,Uri.parse(localUri));retriever.embeddedPicture?.takeIf {it.size<=1024*1024}} finally {retriever.release()}}.getOrNull()
        }
    }
    val source:Any?=if(!remoteFailed) url else if(audio) embedded else localUri?.takeUnless {localFailed}
    val motion=LocalFlowMotionEnabled.current
    val request=remember(source,context,frameAttempt,motion) {coil.request.ImageRequest.Builder(context).data(source).videoFrameMillis(listOf(0L,250L,1000L)[frameAttempt]).allowHardware(false).crossfade(if(motion) FlowMotion.Fast else 0).build()}
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),contentAlignment=Alignment.Center) {
        Icon(if(audio) FlowIcons.Audio else FlowIcons.Video,null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary.copy(alpha=.8f))
        if(source!=null) AsyncImage(request,title,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop,
            onError={if(source==url) remoteFailed=true else localFailed=true},
            onSuccess={state->if(source==localUri && !audio) {
                val bitmap=(state.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                val black=bitmap!=null && (1..3).all {x->(1..3).all {y->val color=bitmap.getPixel(bitmap.width*x/4,bitmap.height*y/4);android.graphics.Color.red(color)<12 && android.graphics.Color.green(color)<12 && android.graphics.Color.blue(color)<12}}
                if(black) {if(frameAttempt<2) frameAttempt++ else localFailed=true}
            }})
    }
}
