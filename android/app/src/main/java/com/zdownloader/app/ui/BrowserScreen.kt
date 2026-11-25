package com.zdownloader.app.ui

import android.annotation.SuppressLint
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zdownloader.app.domain.BrowserMediaLinks
import com.zdownloader.app.R
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.adblock.BrowserAdblock
import com.zdownloader.app.adblock.BrowserAdblockDiagnostics
import com.zdownloader.app.adblock.BrowserCosmeticBridge
import com.zdownloader.app.adblock.BrowserRequestFilter
import com.zdownloader.app.adblock.BrowserServiceWorkerInterceptor
import com.zdownloader.app.adblock.BrowserYoutubePlayerScriptlet
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private data class QuickBookmark(val title:String,val url:String) {
    val host=java.net.URI(url).host.removePrefix("www.").removePrefix("m.")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserScreen(
    initialUrl: String = "https://www.google.com",
    onClose: () -> Unit,
    onDownload: (String) -> Unit = {},
    authMode: Boolean = false,
    authTitle: String? = null,
    onAuthSuccess: (() -> Unit)? = null
) {
    val appContext = LocalContext.current.applicationContext
    val adblockState by BrowserAdblock.state.collectAsState()
    val loadPlayerScript = !authMode && BrowserAdblockDiagnostics.isEnabledForQa()
    val playerScript by produceState<String?>(null, appContext, loadPlayerScript) {
        if (loadPlayerScript) value = withContext(Dispatchers.IO) {
            BrowserYoutubePlayerScriptlet.load(appContext)
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    val pageUrlForFiltering = remember(initialUrl) { AtomicReference(initialUrl) }
    var urlInput by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var detectedUrl by remember { mutableStateOf<String?>(null) }
    var browserError by remember {mutableStateOf<Int?>(null)}
    var browserGeneration by remember {mutableIntStateOf(0)}
    var showAdblockSettings by remember {mutableStateOf(false)}
    var blockedRequestCount by remember {mutableLongStateOf(0L)}
    var blockedPopupCount by remember {mutableLongStateOf(0L)}
    val retryState=remember(initialUrl) {BrowserRetryState(initialUrl)}
    val focusManager = LocalFocusManager.current
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,webView) {
        val current=webView
        val observer=LifecycleEventObserver {_,event->when(event) {
            Lifecycle.Event.ON_RESUME->if(webView===current) current?.onResume()
            Lifecycle.Event.ON_PAUSE->{
                if(webView===current) current?.onPause()
                runCatching { CookieManager.getInstance().flush() }
            }
            else->Unit
        }}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    LaunchedEffect(authMode) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            val trustedAuthPage=!authMode||FacebookAuthNavigationPolicy.isFacebookAuthOrigin(currentUrl)
            if (cm != null && trustedAuthPage && com.zdownloader.app.engine.FacebookSessionManager.hasAuthCookies(cm)) {
                if (com.zdownloader.app.engine.FacebookSessionManager.isSessionExpired() ||
                    !com.zdownloader.app.engine.FacebookSessionManager.hasActiveSession()) {
                    cm.flush()
                    com.zdownloader.app.engine.FacebookSessionManager.markSessionValid()
                }
                if (authMode) {
                    cm.flush()
                    com.zdownloader.app.engine.FacebookSessionManager.markSessionValid()
                    onAuthSuccess?.invoke()
                    break
                }
            }
        }
    }

    val bookmarks = remember {
        listOf(
            QuickBookmark("Instagram", "https://www.instagram.com"),
            QuickBookmark("YouTube", "https://m.youtube.com"),
            QuickBookmark("Facebook", "https://m.facebook.com"),
            QuickBookmark("TikTok", "https://www.tiktok.com"),
            QuickBookmark("X / Twitter", "https://x.com"),
            QuickBookmark("Pinterest", "https://www.pinterest.com")
        )
    }

    fun isMediaUrl(url: String?): Boolean {
        return BrowserMediaLinks.matches(url)
    }

    fun navigateTo(input: String) {
        val target = input.trim()
        if (target.isBlank()) return
        val url = if (target.startsWith("http://") || target.startsWith("https://")) {
            target
        } else if (target.contains(".") && !target.contains(" ")) {
            "https://$target"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
        }
        urlInput = url
        retryState.requested(url,"GET")
        browserError=null
        webView?.loadUrl(url)
        focusManager.clearFocus()
    }

    fun retryPage() {
        val target=retryState.target
        retryState.retry()
        browserError=null;detectedUrl=null;progress=0f
        if(webView==null) browserGeneration++ else webView?.loadUrl(target)
    }

    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onClose()
        }
    }

    val pulseScale by animateFloatAsState(if(detectedUrl!=null) 1f else .96f,tween(FlowMotion.Reveal),label="mediaActionReveal")
    val currentHost=remember(currentUrl) {
        try {java.net.URI(currentUrl).host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.")}
        catch(_:java.net.URISyntaxException) {null}
    }
    val authOrigin=remember(currentUrl) {FacebookAuthNavigationPolicy.inspect(currentUrl)}
    val authOriginScroll=rememberScrollState()
    val adblockHost=remember(currentUrl) {
        try {java.net.URI(currentUrl).host?.trimEnd('.')?.lowercase(java.util.Locale.ROOT)}
        catch(_:java.net.URISyntaxException) {null}
    }
    LaunchedEffect(appContext,authMode) {
        if(!authMode) BrowserAdblock.initialize(appContext)
    }
    LaunchedEffect(showAdblockSettings) {
        while (showAdblockSettings) {
            blockedRequestCount=BrowserAdblock.blockedRequestCount()
            blockedPopupCount=BrowserAdblock.blockedPopupCount()
            kotlinx.coroutines.delay(750)
        }
    }
    LaunchedEffect(adblockState.enabled,adblockState.excludedHosts,adblockState.engineReady,adblockHost) {
        webView?.evaluateJavascript(BrowserCosmeticBridge.REAPPLY_SCRIPT,null)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("browser_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding()
            ) {
                if (authMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FlowIconButton(FlowIcons.Back, stringResource(R.string.back), {
                            if (webView?.canGoBack() == true) webView?.goBack() else onClose()
                        }, modifier = Modifier.testTag("browser_auth_back"))

                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if(authOrigin.kind==FacebookAuthOriginKind.FACEBOOK_HTTPS) {
                                Icon(Icons.Outlined.Lock,contentDescription=null,tint=MaterialTheme.colorScheme.primary,
                                    modifier=Modifier.size(18.dp))
                            } else {
                                Text("!",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold,
                                    modifier=Modifier.testTag("browser_auth_origin_warning"))
                            }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = authTitle ?: stringResource(R.string.facebook_auth_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val originText=when(authOrigin.kind) {
                                    FacebookAuthOriginKind.FACEBOOK_HTTPS -> stringResource(R.string.facebook_auth_origin_facebook,authOrigin.host.orEmpty())
                                    FacebookAuthOriginKind.EXTERNAL_HTTPS -> stringResource(R.string.facebook_auth_origin_external,authOrigin.host.orEmpty())
                                    FacebookAuthOriginKind.BLOCKED_HTTP -> stringResource(R.string.facebook_auth_origin_insecure,authOrigin.host.orEmpty())
                                    FacebookAuthOriginKind.BLOCKED_UNVERIFIED -> authOrigin.host?.let {
                                        stringResource(R.string.facebook_auth_origin_unverified,it)
                                    } ?: stringResource(R.string.facebook_auth_origin_unknown)
                                }
                                Text(originText,modifier=Modifier.fillMaxWidth().horizontalScroll(authOriginScroll)
                                    .testTag("browser_auth_origin"),style=MaterialTheme.typography.labelSmall,
                                    maxLines=1,softWrap=false,overflow=TextOverflow.Clip,
                                    color=if(authOrigin.kind==FacebookAuthOriginKind.FACEBOOK_HTTPS)
                                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }

                        FlowIconButton(FlowIcons.Retry, stringResource(R.string.retry),
                            {if(browserError!=null||webView==null) retryPage() else webView?.reload()},
                            modifier = Modifier.testTag("browser_auth_reload"))
                        FlowIconButton(FlowIcons.Close, stringResource(R.string.close), onClose, Modifier.testTag("browser_auth_close"))
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FlowIconButton(FlowIcons.Back,stringResource(R.string.back),{
                            if (webView?.canGoBack() == true) webView?.goBack() else onClose()
                        })

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    stringResource(R.string.search_or_enter_url),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) }),
                            trailingIcon = {
                                if (urlInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { urlInput = "" },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(FlowIcons.Close, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )

                        FlowIconButton(FlowIcons.Retry,stringResource(R.string.retry),
                            {if(browserError!=null||webView==null) retryPage() else webView?.reload()})
                        if (!authMode) {
                            FlowIconButton(
                                FlowIcons.Security,
                                stringResource(R.string.adblock_accessibility),
                                {showAdblockSettings=true},
                                Modifier.testTag("browser_adblock_settings")
                            )
                        }
                        FlowIconButton(FlowIcons.Close,stringResource(R.string.close),onClose,Modifier.testTag("browser_close"))
                    }
                }

                if (progress > 0f && progress < 1f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }

                if (!authMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.quick_platforms) + ":",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        bookmarks.forEach { bookmark ->
                            FilterChip(
                                selected = currentHost==bookmark.host,
                                onClick = { navigateTo(bookmark.url) },
                                label = { Text(bookmark.title, style = MaterialTheme.typography.labelMedium) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    labelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = null
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !authMode && detectedUrl != null,
                enter = fadeIn(tween(200)) + scaleIn(tween(200)),
                exit = fadeOut(tween(150)) + scaleOut(tween(150))
            ) {
                ExtendedFloatingActionButton(
                    onClick = { detectedUrl?.let(onDownload) },
                    modifier = Modifier
                        .scale(pulseScale)
                        .shadow(8.dp, RoundedCornerShape(28.dp))
                        .testTag("floating_download_button"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(FlowIcons.Downloads, contentDescription = null) },
                    text = {
                        Text(
                            stringResource(R.string.download_detected_video),
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!loadPlayerScript || playerScript != null) key(browserGeneration) {AndroidView(
                factory = { context ->
                    val host=BrowserWebViewHost(context)
                    try {
                     WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                      WebView(context).apply {
                          host.attach(this)
                          webView=this
                          BrowserServiceWorkerInterceptor.registerWebView(
                              this,
                              retryState.target,
                              authMode
                          )
                           if(!authMode) BrowserAdblock.initialize(context)
                          val documentStartCosmetics=if(authMode) false else BrowserCosmeticBridge.install(this,playerScript)
                         // BrowserWebViewHost owns the child's FrameLayout.LayoutParams.
                        // Replacing them with generic ViewGroup params crashes measurement.
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                              cacheMode = WebSettings.LOAD_DEFAULT
                              setSupportMultipleWindows(!authMode)
                              javaScriptCanOpenWindowsAutomatically = false
                            // Keep the installed provider's actual capabilities/version.
                             // A fixed Chrome UA causes sites to choose incompatible paths.
                         }
                         configureBrowserWebViewSecurity(this,authMode)
                         CookieManager.getInstance().setAcceptCookie(true)
                         webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if(webView===view) progress = newProgress / 100f
                            }
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if(webView===view) pageTitle = title.orEmpty()
                            }
                            override fun onCreateWindow(view:WebView?,isDialog:Boolean,isUserGesture:Boolean,resultMsg:android.os.Message?):Boolean {
                                if(!authMode) BrowserAdblock.recordBlockedPopup()
                                return false
                            }
                        }
                          webViewClient=browserNavigationClient(navigation={view,url->if(webView===view) {
                              BrowserServiceWorkerInterceptor.updatePage(view,url)
                               if(!authMode) BrowserCosmeticBridge.updatePageUrl(view,url)
                              currentUrl=url;pageUrlForFiltering.set(url);urlInput=url
                             val cm = runCatching { CookieManager.getInstance() }.getOrNull()
                             val trustedAuthPage=!authMode||FacebookAuthNavigationPolicy.isFacebookAuthOrigin(url)
                             if (cm != null && trustedAuthPage && com.zdownloader.app.engine.FacebookSessionManager.hasAuthCookies(cm)) {
                                 cm.flush()
                                 com.zdownloader.app.engine.FacebookSessionManager.markSessionValid()
                                if (authMode) {
                                    onAuthSuccess?.invoke()
                                }
                            }
                            if (!authMode) {
                                detectedUrl=if(isMediaUrl(url)) url else null
                            }
                        }},pageError={view,url,method->if(webView===view) {retryState.failed(url,method);browserError=R.string.browser_page_failed;progress=0f}},rendererGone={view->
                            if(webView===view) {retryState.rendererGone();webView=null;detectedUrl=null;progress=0f;browserError=R.string.browser_renderer_failed}
                            host.release(rendererGone=true)
                          },requested=retryState::requested,pageStarted={view,url->
                              if(webView===view) {
                                  BrowserServiceWorkerInterceptor.updatePage(view,url)
                                  if(authMode) {
                                     currentUrl=url
                                     urlInput=url
                                     if(!FacebookAuthNavigationPolicy.allowsAuthNavigation(url)) view.stopLoading()
                                  } else {
                                       BrowserCosmeticBridge.beginPage(view,url)
                                      pageUrlForFiltering.set(url)
                                      BrowserAdblock.pageStarted(url)
                                     if(!documentStartCosmetics)
                                         view.evaluateJavascript(BrowserCosmeticBridge.fallbackScript(),null)
                                 }
                             }
                          },intercept={request->
                              if(authMode) null else BrowserRequestFilter.intercept(request,pageUrlForFiltering.get())
                          },blockMainFrameRedirect={request->
                              !authMode&&BrowserRequestFilter.shouldBlockMainFrameRedirect(request,pageUrlForFiltering.get())
                          },mainFrameNavigationAllowed={url->
                             !authMode||FacebookAuthNavigationPolicy.allowsAuthNavigation(url)
                         },blockedMainFrameNavigation={view,url->
                             if(authMode&&webView===view) {
                                 currentUrl=url
                                 urlInput=url
                                 detectedUrl=null
                             }
                         })
                        loadUrl(retryState.target)
                    }
                    } catch(_:RuntimeException) {host.release();webView=null;browserError=R.string.browser_unavailable}
                      catch(_:LinkageError) {host.release();webView=null;browserError=R.string.browser_unavailable}
                    host
                },
                onRelease = {released->
                    if(webView===released.browser) webView=null
                    released.release()
                },
                modifier = Modifier.fillMaxSize()
            )}
            browserError?.let {message->
                Surface(Modifier.fillMaxSize().testTag("browser_error"),color=MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(stringResource(message),style=MaterialTheme.typography.bodyLarge)
                        if(retryState.formNotResent) Text(stringResource(R.string.browser_form_not_resent))
                        Button(onClick=::retryPage,modifier=Modifier.testTag("browser_retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
    if(showAdblockSettings&&!authMode) {
        val host=adblockHost
        val blocked=blockedRequestCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val popups=blockedPopupCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        AlertDialog(
            onDismissRequest={showAdblockSettings=false},
            title={Text(stringResource(R.string.adblock_dialog_title))},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.adblock_global_toggle),Modifier.weight(1f))
                        Switch(
                            checked=adblockState.enabled,
                            onCheckedChange={BrowserAdblock.setEnabled(appContext,it)},
                            modifier=Modifier.testTag("browser_adblock_global_switch")
                        )
                    }
                    if(host!=null) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.adblock_site_exception,host),
                                Modifier.weight(1f),
                                style=MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked=host in adblockState.excludedHosts,
                                onCheckedChange={BrowserAdblock.setHostExcluded(appContext,host,it)},
                                modifier=Modifier.testTag("browser_adblock_site_switch")
                            )
                        }
                    }
                    Text(
                        pluralStringResource(R.plurals.adblock_blocked_resources,blocked,blocked),
                        modifier=Modifier.testTag("browser_adblock_blocked_resources"),
                        style=MaterialTheme.typography.bodySmall
                    )
                    Text(
                        pluralStringResource(R.plurals.adblock_blocked_popups,popups,popups),
                        modifier=Modifier.testTag("browser_adblock_blocked_popups"),
                        style=MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if(adblockState.engineReady) stringResource(
                            R.string.adblock_filter_counts,
                            adblockState.networkRules,
                            adblockState.cosmeticRules
                        ) else if(adblockState.loading) stringResource(R.string.adblock_loading)
                        else if(adblockState.nativeAvailable) stringResource(R.string.adblock_unavailable)
                        else stringResource(R.string.adblock_native_unavailable),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.adblock_update_policy),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.adblock_webview_limits),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if(loadPlayerScript) Text(
                        stringResource(R.string.adblock_player_scriptlet_reload),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton={
                TextButton(
                    onClick={showAdblockSettings=false},
                    modifier=Modifier.testTag("browser_adblock_close")
                ) {
                    Text(stringResource(R.string.close))
                }
            },
            modifier=Modifier.testTag("browser_adblock_dialog")
        )
    }
}

@Composable
internal fun BrowserAuthDialog(
    initialUrl: String = "https://m.facebook.com/login/",
    authTitle: String? = null,
    onDismiss: () -> Unit,
    onAuthSuccess: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BrowserScreen(
            initialUrl = initialUrl,
            onClose = onDismiss,
            authMode = true,
            authTitle = authTitle,
            onAuthSuccess = onAuthSuccess
        )
    }
}
