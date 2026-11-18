package com.zdownloader.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import com.zdownloader.app.ui.spectrumStringResource as stringResource

@Composable internal fun ConsumerSettings(settings:Settings,model:ZViewModel,folder:()->Unit,permission:(String?)->Unit,back:()->Unit={},permissionRevision:Int=0,statusSaver:()->Unit={},storageManagement:()->Unit={},updater:com.zdownloader.app.updates.AppUpdater?=null,openUpdatesRequested:Boolean=false,onUpdatesOpened:()->Unit={}) {
    var page by rememberSaveable {mutableStateOf<String?>(null)}
    var helpReport by rememberSaveable {mutableStateOf(false)}
    if(helpReport) ProblemReportSheet(helpReport,close={helpReport=false})
    LaunchedEffect(openUpdatesRequested) {if(openUpdatesRequested) {page="updates";onUpdatesOpened()}}
    var licenseParent by rememberSaveable {mutableStateOf("about")}
    var privacyParent by rememberSaveable {mutableStateOf<String?>("about")}
    var choice by rememberSaveable {mutableStateOf<String?>(null)}
    val settingsScroll=rememberLazyListState()
    val context=LocalContext.current
    val notificationAccess by rememberNotificationAccess(permissionRevision)
    val closePage:()->Unit={when(page) {null->back();"about","updates","trash","help"->page=null;"privacy"->page=privacyParent;"licenses"->page=licenseParent;else->page="about"}}
    BackHandler(page!=null) {closePage()}
    val motion=LocalFlowMotionEnabled.current
    val direction=if(LocalLayoutDirection.current==LayoutDirection.Rtl) -1 else 1
    AnimatedContent(page,modifier=Modifier.fillMaxSize(),transitionSpec={
        val transform = if(motion) {
            val travel=if(targetState==null) -direction else direction
            (fadeIn(tween(FlowMotion.Scene, easing = FastOutSlowInEasing))+slideInHorizontally(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) {it/12*travel}) togetherWith
                (fadeOut(tween(FlowMotion.Exit, easing = FastOutSlowInEasing))+slideOutHorizontally(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) {-it/18*travel})
        } else EnterTransition.None togetherWith ExitTransition.None
        transform.using(SizeTransform(clip = false))
    },label="settingsSection",contentKey={it}) {shownPage->
    if(shownPage!=null) {
        if(shownPage=="help") HelpAndReport(closePage,{helpReport=true})
        else if(shownPage=="trash") DownloadTrashScreen(model,closePage)
        else if(shownPage=="updates"&&updater!=null) Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal=20.dp)) {SettingsHeader(stringResource(R.string.update_title),closePage,"updates_back")}
            UpdateContent(updater,settings.language,Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp))
        } else InformationPage(shownPage,closePage) {
            if(it=="licenses") licenseParent=page ?: "about"
            if(it=="privacy") privacyParent=page
            page=it
        }
    } else Column(Modifier.fillMaxSize()) {
      Box(Modifier.padding(horizontal=20.dp)) {SettingsHeader(stringResource(R.string.settings),back,"settings_back")}
      LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("settings_list"),state=settingsScroll,contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp)) {
        item(key="identity",contentType="identity") {MediaFlowIdentity(Modifier.padding(bottom=12.dp).testTag("settings_identity"))}
        item(key="hint",contentType="hint") {Text(stringResource(R.string.design_settings_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="sec_general",contentType="section") {SectionTitle(R.string.general)}
        item(key="language",contentType="choice") {SettingChoice(Icons.Outlined.Language,R.string.language,if(settings.language=="ar") "العربية" else "English","language_settings") {choice="language"}}
        item(key="theme",contentType="choice") {SettingChoice(Icons.Outlined.Palette,R.string.theme,stringResource(when(settings.theme) {"light"->R.string.light;"system"->R.string.follow_system;else->R.string.dark}),"theme_settings") {choice="theme"}}
        item(key="sec_downloads",contentType="section") {SectionTitle(R.string.downloads)}
        item(key="quality",contentType="choice") {SettingChoice(Icons.Outlined.HighQuality,R.string.default_video_quality,if(settings.preferredHeight==0) stringResource(R.string.best_available) else "${settings.preferredHeight}p","default_quality") {choice="quality"}}
        item(key="quality_hint",contentType="hint") {Text(stringResource(R.string.quality_preference_hint),Modifier.padding(start=36.dp,bottom=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="type",contentType="choice") {SettingChoice(FlowIcons.Downloads,R.string.default_download_type,stringResource(when(settings.downloadType) {"video"->R.string.video;"audio"->R.string.audio;else->R.string.ask_every_time}),"default_type") {choice="type"}}
        item(key="save_location",contentType="choice") {SettingChoice(FlowIcons.Folder,R.string.save_location,settings.destination?.let {scopedFolderLabel(it)} ?: stringResource(R.string.default_folder),"save_location") {folder()}}
        if(settings.destination!=null&&Build.VERSION.SDK_INT>=29) item(key="default_folder_reset",contentType="button") {TextButton(onClick={model.textSetting("destination","")},modifier=Modifier.padding(start=24.dp)) {Text(stringResource(R.string.default_folder))}}
        item(key="concurrency",contentType="choice") {SettingChoice(Icons.Outlined.Queue,R.string.simultaneous_downloads,if(settings.concurrency==0) stringResource(R.string.pl_automatic) else "${settings.concurrency}","concurrent_downloads") {choice="concurrency"}}
        item(key="speed_policy",contentType="hint") {Text(stringResource(R.string.transfer_speed_policy),Modifier.padding(start=36.dp,bottom=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="retries",contentType="choice") {SettingChoice(FlowIcons.Retry,R.string.advanced_retries,"${settings.retries}","retry_policy") {choice="retries"}}
        item(key="wifi_only",contentType="switch") {SettingSwitch(Icons.Outlined.Wifi,R.string.wifi_only,null,settings.wifiOnly,{model.booleanSetting("wifi",it)},"wifi_only")}
        item(key="storage_management",contentType="choice") {SettingChoice(Icons.Outlined.Storage,R.string.storage_management,stringResource(R.string.storage_manage_hint),"open_storage_management",storageManagement)}
        item(key="trash",contentType="choice") {SettingChoice(FlowIcons.Delete,R.string.trash_title,stringResource(R.string.trash_hint),"open_download_trash") {page="trash"}}
        item(key="status_saver",contentType="choice") {SettingChoice(FlowIcons.Status,R.string.status_saver_entry,stringResource(R.string.status_saver_description),"open_status_saver",statusSaver)}
        item(key="sec_notifications",contentType="section") {SectionTitle(R.string.notifications)}
        item(key="notify_progress_perm",contentType="hint") {Text(stringResource(when(notificationAccess?.progressEnabled) {true->R.string.notification_progress_enabled;false->R.string.notification_progress_blocked;null->R.string.mf_loading}),Modifier.padding(vertical=8.dp).testTag("notification_progress_permission"),style=MaterialTheme.typography.bodySmall,color=if(notificationAccess?.progressEnabled==false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
        item(key="notify_completion",contentType="switch") {SettingSwitch(Icons.Outlined.NotificationsActive,R.string.notification_completed_title,R.string.notification_completed_description,
            settings.notifications&&settings.completionNotifications&&notificationAccess?.completionEnabled==true,
            {if(it&&notificationAccess?.runtimePermissionGranted!=true) permission("completion_notifications") else model.notificationSetting("completion_notifications",it)},
            "notify_completion",enabled=notificationAccess?.completionEnabled==true)}
        item(key="notify_failure",contentType="switch") {SettingSwitch(FlowIcons.Error,R.string.notification_failed_title,R.string.notification_failed_description,
            settings.notifications&&settings.failureNotifications&&notificationAccess?.failureEnabled==true,
            {if(it&&notificationAccess?.runtimePermissionGranted!=true) permission("failure_notifications") else model.notificationSetting("failure_notifications",it)},
            "notify_failure",enabled=notificationAccess?.failureEnabled==true)}
        if(notificationAccess?.runtimePermissionGranted==false) {
            item(key="notify_perm_denied",contentType="hint") {Text(stringResource(R.string.notification_permission_denied),Modifier.padding(top=6.dp).testTag("notification_permission_denied"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            item(key="notify_perm_request",contentType="button") {TextButton(onClick={permission(null)},modifier=Modifier.testTag("request_notification_permission")) {Text(stringResource(R.string.notification_permission_request))}}
        } else if(notificationAccess?.systemEnabled==false) {
            item(key="notify_sys_off",contentType="hint") {Text(stringResource(R.string.notification_system_off),Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        } else if(notificationAccess?.let {!it.progressEnabled||!it.completionEnabled||!it.failureEnabled}==true) {
            item(key="notify_chan_off",contentType="hint") {Text(stringResource(R.string.notification_channel_off),Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        item(key="android_notify_settings",contentType="button") {TextButton(onClick={
            val intent=if(Build.VERSION.SDK_INT>=26) android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName)
            else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}"))
            runCatching {context.startActivity(intent)}.onFailure {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}")))
            }
        },modifier=Modifier.testTag("android_notification_settings")) {Text(stringResource(R.string.notification_system_settings))}}
        item(key="notify_ongoing_hint",contentType="hint") {Text(stringResource(R.string.notification_ongoing_hint),Modifier.padding(bottom=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item(key="sec_information",contentType="section") {SectionTitle(R.string.information)}
        if(com.zdownloader.app.BuildConfig.INTERNAL_DIAGNOSTICS) {
            item(key="diagnostics",contentType="diagnostics") {DiagnosticsControls()}
        }
        item(key="help_report",contentType="choice") {SettingChoice(Icons.Outlined.HelpOutline,R.string.report_help_title,"","open_help_report") {page="help"}}
        item(key="privacy_controls",contentType="choice") {SettingChoice(Icons.Outlined.PrivacyTip,R.string.privacy_information,"","open_privacy_controls") {privacyParent=null;page="privacy"}}
        if(updater!=null) item(key="updates",contentType="choice") {SettingChoice(Icons.Outlined.SystemUpdate,R.string.update_title,stringResource(R.string.update_settings_hint),"open_updates") {page="updates";updater.dismissPrompt()}}
        item(key="about_zd",contentType="choice") {SettingChoice(Icons.Outlined.Info,R.string.about_zd,stringResource(R.string.version_display,BuildConfig.VERSION_NAME),"about_zd") {page="about"}}
        item(key="copyright",contentType="hint") {Text(stringResource(R.string.about_copyright),Modifier.fillMaxWidth().padding(top=24.dp,bottom=20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)}
      }
    }
    }
    choice?.let {kind ->
        val options=when(kind) {
            "language"->listOf("ar" to "العربية","en" to "English")
            "theme"->listOf("system" to stringResource(R.string.follow_system),"dark" to stringResource(R.string.dark),"light" to stringResource(R.string.light))
            "quality"->listOf(0,1080,720,480,360).map {"$it" to if(it==0) stringResource(R.string.best_available) else "${it}p"}
             "type"->listOf("ask" to stringResource(R.string.ask_every_time),"video" to stringResource(R.string.video),"audio" to stringResource(R.string.audio))
             "retries"->(0..5).map {"$it" to "$it"}
            else->(1..5).map {"$it" to "$it"}+listOf("0" to stringResource(R.string.pl_automatic))
        }
        val selected=when(kind) {"language"->settings.language;"theme"->settings.theme;"quality"->settings.preferredHeight.toString();"type"->settings.downloadType;"retries"->settings.retries.toString();else->settings.concurrency.toString()}
        val title=when(kind) {"language"->R.string.language;"theme"->R.string.theme;"quality"->R.string.default_video_quality;"type"->R.string.default_download_type;"retries"->R.string.advanced_retries;else->R.string.simultaneous_downloads}
        AlertDialog(onDismissRequest={choice=null},title={Text(stringResource(title))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach {(key,label)->
                Row(Modifier.fillMaxWidth().heightIn(min=52.dp).selectable(selected==key,role=Role.RadioButton,onClick={
                    when(kind) {"language","theme"->model.textSetting(kind,key);"quality"->model.integerSetting("preferred_height",key.toInt());"type"->model.textSetting("download_type",key);"retries"->model.integerSetting("retries",key.toInt());else->model.integerSetting("concurrency",key.toInt())}
                    choice=null
                }).testTag("${kind}_$key"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected==key,null);Text(label,style=MaterialTheme.typography.bodyLarge)
                }
            }
        }},confirmButton={TextButton(onClick={choice=null}) {Text(stringResource(R.string.close))}})
    }

}

private data class NotificationAccess(
    val systemEnabled:Boolean,
    val runtimePermissionGranted:Boolean,
    val progressEnabled:Boolean,
    val completionEnabled:Boolean,
    val failureEnabled:Boolean
)

/** Binder queries must not block the first Settings composition or its transition. */
@Composable private fun rememberNotificationAccess(permissionRevision:Int):State<NotificationAccess?> {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    return produceState<NotificationAccess?>(null,context,lifecycle,permissionRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value=withContext(Dispatchers.IO) {
                val runtimePermissionGranted=!NotificationPermissionPolicy.requiresRuntimePermission(Build.VERSION.SDK_INT)||
                    androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
                val enabled=runtimePermissionGranted&&androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                val manager=if(Build.VERSION.SDK_INT>=26) context.getSystemService(android.app.NotificationManager::class.java) else null
                fun channelEnabled(channelId:String)=manager==null||manager.getNotificationChannel(channelId)?.importance!=android.app.NotificationManager.IMPORTANCE_NONE
                NotificationAccess(
                    systemEnabled=enabled,
                    runtimePermissionGranted=runtimePermissionGranted,
                    progressEnabled=NotificationPermissionPolicy.notificationsAvailable(Build.VERSION.SDK_INT,runtimePermissionGranted,enabled,channelEnabled(com.zdownloader.app.background.DownloadNotifications.CHANNEL)),
                    completionEnabled=NotificationPermissionPolicy.notificationsAvailable(Build.VERSION.SDK_INT,runtimePermissionGranted,enabled,channelEnabled(com.zdownloader.app.background.DownloadNotifications.COMPLETED_CHANNEL)),
                    failureEnabled=NotificationPermissionPolicy.notificationsAvailable(Build.VERSION.SDK_INT,runtimePermissionGranted,enabled,channelEnabled(com.zdownloader.app.background.DownloadNotifications.FAILED_CHANNEL))
                )
            }
            awaitCancellation()
        }
    }
}

@Composable private fun InformationPage(page:String,back:()->Unit,navigate:(String)->Unit) {
    if(page=="about") {AboutScreen(back) {navigate("privacy")};return}
    val context=LocalContext.current
    var notices by remember {mutableStateOf("")}
    LaunchedEffect(page) {if(page=="licenses") notices=withContext(Dispatchers.IO) {
        (listOf("THIRD_PARTY_NOTICES.md","LAME-COPYING.txt","ibmplexsansarabic-OFL.txt","inter-OFL.txt")+
            context.assets.list("legal/dependencies").orEmpty().map {"dependencies/$it"}).joinToString("\n\n") {name->context.assets.open("legal/$name").bufferedReader().use {it.readText()}}
    }}
    val blocks=remember(notices) {notices.lines().chunked(12).map {it.joinToString("\n")}}
    val title=when(page) {"privacy"->R.string.privacy_information;"legal"->R.string.legal_information;"licenses"->R.string.open_source_licenses;else->R.string.about_zd}
    Column(Modifier.fillMaxSize()) {
      Box(Modifier.padding(horizontal=20.dp)) {SettingsHeader(stringResource(title),back,"information_back")}
      key(page) {LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("information_$page"),contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when(page) {
            "privacy"->{
                item {Text(stringResource(R.string.about_privacy_body),
                      Modifier.testTag("privacy_practices"),style=MaterialTheme.typography.bodyLarge)}
                item {Text(stringResource(R.string.about_x_metadata_privacy),Modifier.testTag("x_metadata_privacy"),style=MaterialTheme.typography.bodyLarge)}
                item {Text(stringResource(R.string.player_privacy_body),Modifier.testTag("playback_privacy_practices"),style=MaterialTheme.typography.bodyLarge)}
                item {HorizontalDivider(Modifier.padding(vertical=12.dp))}
                item {Text(stringResource(R.string.about_third_party_title),style=MaterialTheme.typography.titleMedium)}
                item {Text(stringResource(R.string.about_third_party_scope),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                item {OutlinedButton(onClick={navigate("licenses")},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("open_source_licenses")) {
                    Text(stringResource(R.string.about_third_party_notices))
                }}
            }
            "legal"->{item {Text(stringResource(R.string.legal_summary),style=MaterialTheme.typography.bodyLarge)};item {SettingChoice(Icons.Outlined.Description,R.string.open_source_licenses,"","open_source_licenses") {navigate("licenses")}}}
             "licenses"->{if(notices.isEmpty()) item {FlowLoadingIndicator()};items(blocks) {Text(it,style=MaterialTheme.typography.bodySmall)}}
        }
      }}
    }
}

@Composable private fun SettingsHeader(title:String,back:()->Unit,tag:String) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FlowIconButton(FlowIcons.Back,stringResource(R.string.back),back,Modifier.testTag(tag))
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun SectionTitle(label:Int) {Row(Modifier.padding(top=22.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
    Text(stringResource(label),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
}}
@Composable private fun SettingChoice(icon:ImageVector,label:Int,value:String,tag:String,click:()->Unit) {
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().padding(bottom=6.dp).testTag(tag),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(stringResource(label),style=MaterialTheme.typography.bodyLarge)
            if(value.isNotBlank()) Text(value,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(FlowIcons.Next,null,tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(20.dp))
    }
    }
}
@Composable private fun SettingSwitch(icon:ImageVector,label:Int,description:Int?,checked:Boolean,change:(Boolean)->Unit,tag:String,enabled:Boolean=true) {
    Surface(modifier=Modifier.fillMaxWidth().padding(bottom=6.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange=change).padding(horizontal=14.dp,vertical=12.dp).testTag(tag),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label),style=MaterialTheme.typography.bodyLarge)
            description?.let {Text(stringResource(it),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
         Switch(checked,null,enabled=enabled)
    }
    }
}
@Composable private fun SettingsIcon(icon:ImageVector) {
    FlowIconTile(icon)
}
internal fun folderLabel(value:String):String=runCatching {android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(value)).substringAfterLast(':').substringAfterLast('/').ifBlank {"…"}}.getOrDefault("…")

/** A provider ID is not a display name. Resolve only the granted document, off the UI thread. */
@Composable internal fun scopedFolderLabel(uri:String):String {
    val context=LocalContext.current
    val fallback=folderLabel(uri)
    val label by produceState(fallback,uri,context) {
        value=withContext(Dispatchers.IO) {
            try {
                val parsed=android.net.Uri.parse(uri)
                val document=if(android.provider.DocumentsContract.isDocumentUri(context,parsed)) androidx.documentfile.provider.DocumentFile.fromSingleUri(context,parsed)
                    else androidx.documentfile.provider.DocumentFile.fromTreeUri(context,parsed)
                document?.name?.filterNot {it.isISOControl()}?.take(256)?.takeIf {it.isNotBlank()} ?: fallback
            } catch(error:kotlinx.coroutines.CancellationException) {throw error} catch(_:Exception) {fallback}
        }
    }
    return label
}
