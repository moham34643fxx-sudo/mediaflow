package com.zdownloader.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.updates.*
import com.zdownloader.app.ui.spectrumStringResource as stringResource
import java.util.Locale
import kotlinx.coroutines.delay

/** The updater owns its work; closing this screen does not touch media transfers or playback. */
@Composable internal fun UpdateContent(updater:AppUpdater,language:String,modifier:Modifier=Modifier) {
    val state by updater.state.collectAsStateWithLifecycle()
    val activity=LocalContext.current.updateActivity()
    UpdateSettingsContent(state,language,modifier,onCheck={updater.check(true)},onDownload={updater.download(true)},
        onPause=updater::pause,onInstall={activity?.let {updater.install(it)}},canInstall=activity!=null,
        onRetry={if(state.asset!=null) updater.download(true) else updater.check(true)})
}

/** A scrollable overview and a separate bottom action area, even on large-text screens. */
@Composable internal fun UpdateSettingsContent(state:UpdateState,language:String,modifier:Modifier=Modifier,
    onCheck:()->Unit,onDownload:()->Unit,onPause:()->Unit,onInstall:()->Unit,canInstall:Boolean=true,onRetry:()->Unit) {
    val busy=state.phase in setOf(UpdatePhase.CHECKING,UpdatePhase.VERIFYING,UpdatePhase.INSTALLING)
    val release=state.release?.takeIf {state.asset!=null}
    Column(modifier.fillMaxSize().testTag("update_content")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                FlowEyebrow(stringResource(R.string.design_update_eyebrow),FlowIcons.Updates)
                Text(stringResource(R.string.design_update_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowPanel(Modifier.testTag("update_overview")) {
                Column(Modifier.fillMaxWidth().flowRibbon(MaterialTheme.colorScheme.primary),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    UpdateEmblem(state.phase)
                    Text(stringResource(updateStatusResource(state.phase)),Modifier.testTag("update_status").semantics {heading()},style=MaterialTheme.typography.headlineSmall)
                    if(release!=null) {
                        ReleaseVersion(release.versionName,state.total)
                        if(state.phase==UpdatePhase.AVAILABLE) Text(stringResource(R.string.update_offer_description),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(busy) FlowLoadingIndicator(Modifier.fillMaxWidth())
                    if(state.phase in setOf(UpdatePhase.DOWNLOADING,UpdatePhase.PAUSED)) {
                        MediaFlowProgress(state.progress,Modifier.testTag("update_progress"))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.update_transfer_bytes,updateSize(state.downloaded),updateSize(state.total)),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(state.total>0) Text("${(state.progress*100).toInt()}%",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        }
                        Text(stringResource(R.string.design_update_transfer_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            UpdateInstalledCard(state.checkedAt)
            state.error?.let {error->
                Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(FlowIcons.Error,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(updateErrorResource(error)),Modifier.weight(1f).testTag("update_error"),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if(release!=null) FlowPanel {ReleaseHighlights(release,language)}
            Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(FlowIcons.Security,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.design_update_checks),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top=12.dp,bottom=20.dp).testTag("update_actions"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(Modifier.padding(bottom=8.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))
            when(state.phase) {
                UpdatePhase.AVAILABLE,UpdatePhase.PAUSED -> PrimaryAction(onDownload,Modifier.fillMaxWidth().testTag("update_download")) {
                    Icon(FlowIcons.Downloads,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
                    Text(stringResource(if(state.phase==UpdatePhase.PAUSED) R.string.update_resume else R.string.update_now))
                }
                UpdatePhase.DOWNLOADING -> OutlinedButton(onPause,Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("update_pause"),shape=RoundedCornerShape(18.dp)) {
                    Icon(FlowIcons.Pause,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text(stringResource(R.string.update_pause))
                }
                UpdatePhase.READY,UpdatePhase.WAITING_PERMISSION -> PrimaryAction(onInstall,Modifier.fillMaxWidth().testTag("update_install"),enabled=canInstall) {
                    Icon(FlowIcons.Updates,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text(stringResource(R.string.update_install))
                }
                UpdatePhase.ERROR -> PrimaryAction(onRetry,Modifier.fillMaxWidth().testTag("update_retry")) {Text(stringResource(R.string.update_retry))}
                else -> Unit
            }
            if(state.phase in setOf(UpdatePhase.IDLE,UpdatePhase.CHECKING,UpdatePhase.CURRENT,UpdatePhase.NO_RELEASE,UpdatePhase.INSTALLED)) {
                PrimaryAction(onCheck,Modifier.fillMaxWidth().testTag("update_check"),enabled=!busy) {
                    if(busy) FlowLoadingIndicator(compact=true) else Icon(FlowIcons.Retry,null,Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp));Text(stringResource(if(busy) R.string.update_checking else R.string.update_check))
                }
            } else if(state.phase==UpdatePhase.AVAILABLE) {
                TextButton(onCheck,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("update_check")) {Text(stringResource(R.string.update_check))}
            }
        }
    }
}

@Composable internal fun UpdateOffer(updater:AppUpdater,language:String,canPresent:Boolean) {
    val state by updater.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val activity=LocalContext.current.updateActivity()
    UpdatePopupHost(state,language,canPresent&&lifecycle.isAtLeast(Lifecycle.State.RESUMED),
        UpdateDialogActions(dismiss={updater.dismissPrompt()},download={updater.download(true)},pause=updater::pause,
            install=activity?.let {{updater.install(it);Unit}},
            retry={if(state.asset!=null) updater.download(true) else updater.check(true)}))
}

internal data class UpdateDialogActions(
    val dismiss:()->Unit,val download:()->Unit,val pause:()->Unit,val install:(()->Unit)?,val retry:()->Unit
)

/** The complete update journey stays above the current destination, never in Settings. */
@Composable internal fun UpdatePopupHost(state:UpdateState,language:String,canPresent:Boolean,actions:UpdateDialogActions) {
    var engaged by rememberSaveable(state.release?.versionCode) {mutableStateOf(false)}
    var dismissed by rememberSaveable(state.release?.versionCode) {mutableStateOf(false)}
    val resumable=state.asset!=null&&state.phase in setOf(UpdatePhase.PAUSED,UpdatePhase.READY,UpdatePhase.WAITING_PERMISSION)
    val eligible=!dismissed&&(state.automaticPrompt&&state.phase==UpdatePhase.AVAILABLE||resumable)
    var settled by remember {mutableStateOf(false)}
    LaunchedEffect(canPresent,eligible,state.release?.versionCode) {
        settled=false
        if(canPresent&&eligible) {delay(400);settled=true}
    }
    LaunchedEffect(state.phase) {
        if(state.phase in setOf(UpdatePhase.CURRENT,UpdatePhase.INSTALLED,UpdatePhase.NO_RELEASE,UpdatePhase.IDLE)) engaged=false
    }
    if(canPresent&&(engaged||eligible&&settled)&&state.release!=null) {
        UpdateDialog(state,language,actions.copy(dismiss={
            engaged=false;dismissed=true
            // A running transfer owns the updater mutex. Closing its UI must not queue a command behind it.
            if(state.automaticPrompt) actions.dismiss()
        },download={engaged=true;actions.download()},retry={engaged=true;actions.retry()}))
    }
}

/** Release copy comes exclusively from the published manifest; no invented feature list. */
@Composable internal fun UpdateReleaseDialog(release:UpdateRelease,language:String,bytes:Long,onLater:()->Unit,onUpdate:()->Unit) {
    UpdateDialog(UpdateState(phase=UpdatePhase.AVAILABLE,release=release,total=bytes),language,
        UpdateDialogActions(onLater,onUpdate,{},null,onUpdate))
}

@Composable internal fun UpdateDialog(state:UpdateState,language:String,actions:UpdateDialogActions) {
    val release=state.release ?: return
    Dialog(onDismissRequest=actions.dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(24.dp).widthIn(max=460.dp).fillMaxWidth()
            .heightIn(max=(LocalConfiguration.current.screenHeightDp.dp-48.dp).coerceAtLeast(200.dp)).testTag("update_offer"),
             shape=RoundedCornerShape(32.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
             border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.19f)),tonalElevation=0.dp) {
            Column(Modifier.flowEntrance().padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    MediaFlowWordmark(Modifier.weight(1f))
                    FlowIconButton(FlowIcons.Close,stringResource(R.string.close),actions.dismiss,Modifier.testTag("update_offer_close"))
                }
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.fillMaxWidth().flowRibbon(MaterialTheme.colorScheme.primary),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        UpdateEmblem(state.phase)
                        Text(stringResource(updateStatusResource(state.phase)),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.testTag("update_offer_status").semantics {heading()})
                        if(state.phase==UpdatePhase.AVAILABLE) Text(stringResource(R.string.update_offer_description),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        ReleaseVersion(release.versionName,state.total)
                        if(state.phase in setOf(UpdatePhase.DOWNLOADING,UpdatePhase.PAUSED)) {
                            MediaFlowProgress(state.progress,Modifier.testTag("update_offer_progress"))
                            Text(stringResource(R.string.update_transfer_bytes,updateSize(state.downloaded),updateSize(state.total)),
                                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(state.phase in setOf(UpdatePhase.CHECKING,UpdatePhase.VERIFYING,UpdatePhase.INSTALLING)) FlowLoadingIndicator(Modifier.fillMaxWidth())
                        state.error?.let {Text(stringResource(updateErrorResource(it)),Modifier.testTag("update_offer_error"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    ReleaseHighlights(release,language)
                }
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    when(state.phase) {
                        UpdatePhase.AVAILABLE,UpdatePhase.PAUSED -> PrimaryAction(actions.download,Modifier.fillMaxWidth().testTag("update_offer_now")) {
                            Icon(FlowIcons.Downloads,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
                            Text(stringResource(if(state.phase==UpdatePhase.PAUSED) R.string.update_resume else R.string.update_now))
                        }
                        UpdatePhase.DOWNLOADING -> OutlinedButton(actions.pause,Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("update_offer_pause"),shape=RoundedCornerShape(18.dp)) {
                            Text(stringResource(R.string.update_pause))
                        }
                        UpdatePhase.READY,UpdatePhase.WAITING_PERMISSION -> PrimaryAction({actions.install?.invoke()},Modifier.fillMaxWidth().testTag("update_offer_install"),enabled=actions.install!=null) {
                            Text(stringResource(R.string.update_install))
                        }
                        UpdatePhase.ERROR -> PrimaryAction(actions.retry,Modifier.fillMaxWidth().testTag("update_offer_retry")) {Text(stringResource(R.string.update_retry))}
                        else -> Unit
                    }
                    TextButton(actions.dismiss,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("update_offer_later")) {
                        Text(stringResource(if(state.phase==UpdatePhase.AVAILABLE) R.string.update_later else R.string.close))
                    }
                }
            }
        }
    }
}

@Composable private fun UpdateEmblem(phase:UpdatePhase) {
    val tint=if(phase==UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val icon=when(phase) {
        UpdatePhase.CURRENT,UpdatePhase.INSTALLED,UpdatePhase.READY->FlowIcons.Success
        UpdatePhase.ERROR->FlowIcons.Error
        else->FlowIcons.Updates
    }
    Box(Modifier.size(80.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha=.16f),tint.copy(alpha=.025f))))
        drawCircle(tint.copy(alpha=.18f),radius=size.minDimension*.47f,style=Stroke(1.dp.toPx()))
        drawCircle(tint.copy(alpha=.09f),radius=size.minDimension*.36f,style=Stroke(1.dp.toPx()))
        drawCircle(tint,radius=3.dp.toPx(),center=Offset(size.width*.84f,size.height*.19f))
    },contentAlignment=Alignment.Center) {Icon(icon,null,Modifier.size(32.dp),tint=tint)}
}

@Composable private fun UpdateInstalledCard(checkedAt:Long) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            FlowIconTile(FlowIcons.Security,size=42.dp,iconSize=20.dp)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.design_update_current_label),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(BuildConfig.VERSION_NAME,style=MaterialTheme.typography.titleSmall.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Ltr),modifier=Modifier.testTag("update_installed_version"))
                if(checkedAt>0) {
                    val context=LocalSpectrumContext.current
                    val formatted=remember(checkedAt,context) {android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(checkedAt))+" · "+android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(checkedAt))}
                    Text(stringResource(R.string.design_update_last_checked,formatted),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun ReleaseVersion(version:String,bytes:Long) {
    Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f)) {
        Text(listOfNotNull(stringResource(R.string.update_version_badge,version),bytes.takeIf {it>0}?.let(::updateSize)).joinToString("  ·  "),
             Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun ReleaseHighlights(release:UpdateRelease,language:String) {
    val notes=remember(release.notes,language) {
        (release.notes[language]?.takeIf {it.isNotBlank()} ?: release.notes["en"]?.takeIf {it.isNotBlank()} ?: release.notes.values.firstOrNull {it.isNotBlank()}).orEmpty()
            .lineSequence().map {it.trim().replace(Regex("^(?:[-*•]\\s+|\\d+[.)]\\s+|#{1,6}\\s+)"),"").replace("**","")}.filter {it.isNotBlank()}.toList()
    }
    if(notes.isNotEmpty()) Column(Modifier.testTag("update_notes"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.update_release_notes),style=MaterialTheme.typography.titleSmall,modifier=Modifier.semantics {heading()})
        notes.forEach {note->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top=8.dp).size(5.dp).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(3.dp)))
            Text(note,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
    }
}

private fun Context.updateActivity():Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> if(baseContext!==this) baseContext.updateActivity() else null
    else -> null
}
private fun updateSize(bytes:Long):String=String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0)

internal fun updateStatusResource(phase:UpdatePhase):Int=when(phase) {
    UpdatePhase.IDLE -> R.string.update_idle
    UpdatePhase.CHECKING -> R.string.update_checking
    UpdatePhase.AVAILABLE -> R.string.update_available_title
    UpdatePhase.DOWNLOADING -> R.string.update_downloading
    UpdatePhase.PAUSED -> R.string.update_paused
    UpdatePhase.VERIFYING -> R.string.update_verifying
    UpdatePhase.READY -> R.string.update_ready
    UpdatePhase.WAITING_PERMISSION -> R.string.update_permission
    UpdatePhase.INSTALLING -> R.string.update_installing
    UpdatePhase.CURRENT -> R.string.update_current
    UpdatePhase.NO_RELEASE -> R.string.update_no_release
    UpdatePhase.INSTALLED -> R.string.update_installed
    UpdatePhase.ERROR -> R.string.update_failed
}
internal fun updateErrorResource(error:UpdateError):Int=when(error) {
    UpdateError.NETWORK,UpdateError.HTTP -> R.string.update_error_network
    UpdateError.RATE_LIMITED -> R.string.update_error_rate_limit
    UpdateError.METADATA,UpdateError.UNTRUSTED_URL -> R.string.update_error_metadata
    UpdateError.INCOMPATIBLE_ABI,UpdateError.INCOMPATIBLE_SDK,UpdateError.INSTALL_INCOMPATIBLE -> R.string.update_error_compatibility
    UpdateError.STORAGE -> R.string.update_error_storage
    UpdateError.RANGE,UpdateError.SIZE -> R.string.update_error_incomplete
    UpdateError.CHECKSUM,UpdateError.INVALID_APK -> R.string.update_error_integrity
    UpdateError.PACKAGE,UpdateError.VERSION -> R.string.update_error_identity
    UpdateError.SIGNATURE -> R.string.update_error_signature
    UpdateError.INSTALL_CONFLICT -> R.string.update_error_conflict
    UpdateError.INSTALL_BLOCKED -> R.string.update_error_blocked
    UpdateError.INSTALL_INVALID -> R.string.update_error_install_invalid
    UpdateError.INSTALL_PERMISSION -> R.string.update_permission
    UpdateError.INSTALL_CANCELLED -> R.string.update_error_cancelled
    UpdateError.INSTALL_FAILED,UpdateError.SESSION_LOST -> R.string.update_error_install
    UpdateError.INTERNAL -> R.string.update_failed
}
