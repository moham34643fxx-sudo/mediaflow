package com.zdownloader.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.background.errorResource
import com.zdownloader.app.domain.*
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import kotlinx.coroutines.launch
import java.util.Locale
import com.zdownloader.app.ui.spectrumStringResource as stringResource
import com.zdownloader.app.ui.spectrumPluralStringResource as pluralStringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QuickShareSheet(model:QuickShareViewModel,close:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val preferences by model.settings.collectAsStateWithLifecycle()
    val base=LocalContext.current
    val view=LocalView.current
    val configuration=LocalConfiguration.current
    val localized=remember(preferences.language,configuration) {
        Configuration(configuration).apply {setLocale(Locale.forLanguageTag(preferences.language));setLayoutDirection(Locale.forLanguageTag(preferences.language))}
    }
    val context=remember(base,localized) {
        val configured=base.createConfigurationContext(localized)
        object:android.content.ContextWrapper(base) {
            override fun getResources()=configured.resources
            override fun getAssets()=configured.assets
        }
    }
    val sizeLabels=remember(context,state.sourceUrl) {SizeLabelCache({formatSizeLabel(context,it)})}
    val presentation=rememberQuickSharePresentation(state.media,sizeLabels)
    val media=presentation?.media
    var schedule by remember {mutableStateOf(false)}
    var reportOpen by rememberSaveable {mutableStateOf(false)}
    var showFacebookLogin by rememberSaveable {mutableStateOf(false)}
    val isFacebookAuth = state.error == ErrorCode.AUTHENTICATION_REQUIRED &&
        (com.zdownloader.app.engine.FacebookScraper.isFacebookUrl(state.sourceUrl.orEmpty()) ||
         runCatching { com.zdownloader.app.domain.UrlPolicy.inputLinks(state.sourceUrl.orEmpty()).any { com.zdownloader.app.engine.FacebookScraper.isFacebookUrl(it) } }.getOrDefault(false))
    var showFacebookAuthDialog by rememberSaveable(state.error) { mutableStateOf(isFacebookAuth) }
    val isImageMedia = media?.let { it.videoOptions().isEmpty() && it.imageOptions().isNotEmpty() } == true
    val isLikelyImage = remember(state.sourceUrl) { QuickShareFormats.isLikelyImageUrl(state.sourceUrl) }
    val allVideoSources=presentation?.videoSources.orEmpty()
    val selectableIds=remember(allVideoSources) {allVideoSources.mapTo(hashSetOf()) {it.id}}
    val videoGroups=remember(media,selectableIds) {
        media?.qualityGroups()?.mapNotNull {group->
            val candidates=group.candidates.filter {it.id in selectableIds}
            candidates.takeIf {it.isNotEmpty()}?.let {QualityGroup(group.resolution,it)}
        }.orEmpty()
    }
    var expandedVideoTierKeys by rememberSaveable(state.sourceUrl) {mutableStateOf(emptyList<String>())}
    var expandedAudioLanguageKeys by rememberSaveable(state.sourceUrl) {mutableStateOf(emptyList<String>())}
    val toggleVideoTier:(String)->Unit={key->
        expandedVideoTierKeys=if(key in expandedVideoTierKeys) expandedVideoTierKeys-key else expandedVideoTierKeys+key
    }
    val pendingRows=FormatLoadingPresentation.pendingRows(state)
    val preferenceSelection=remember(media,state.qualityPreference,state.audioOutput) {runCatching {
        val source=media ?: return@runCatching null
        val height=state.qualityPreference ?: return@runCatching null
        if(height>=0) VideoPreferenceSelection.ready(source,height)
        else DownloadRequest(audioOnly=true,output=state.audioOutput).resolve(source)
    }.getOrNull()}
    val effectiveChoice=state.choice ?: if(isImageMedia&&media!=null) QuickShareFormats.defaultChoice(media)
        else preferenceSelection?.let {QuickShareChoice(it.audioOnly,it.primary.id,if(it.audioOnly) state.audioOutput else it.output)}
    val firstFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val selectionFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val firstSelectableFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val completeCatalogFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    LaunchedEffect(model) {
        model.toastEvents.collect { resId ->
            android.widget.Toast.makeText(context, context.getString(resId), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(state.queuedId) {
        if(state.queuedId!=null) {
            close()
        }
    }
    val selectedVideoFormat=remember(media,effectiveChoice) {
        media?.formats?.firstOrNull {it.id==effectiveChoice?.takeUnless {c->c.audio}?.formatId}
    }
    val preferenceSizes=remember(presentation,state.choice,state.selection) {
        presentation?.preferenceSizes.orEmpty().toMutableMap().apply {
            state.selection?.takeUnless {it.audioOnly}?.let {selected->
                state.qualityPreference?.takeIf {it in QuickShareCatalog.compactPreferences}?.let {prefHeight->
                    (selected.outputSize ?: selected.transferSize)?.let {put(prefHeight,it)}
                }
                (QuickShareCatalog.numericVideoQuality(selected.primary) ?: qualityLabel(selected.primary))?.takeIf {it in QuickShareCatalog.compactPreferences}?.let {height->
                    val size=selected.outputSize ?: selected.transferSize
                    if(size==null) remove(height) else put(height,size)
                }
            }
        }
    }
    val videoTierRows=remember(videoGroups,effectiveChoice,expandedVideoTierKeys) {
        QuickShareVideoRows.expanded(videoGroups,effectiveChoice,expandedVideoTierKeys.toSet())
    }
    val arrivals=remember(state.sourceUrl,state.expanded,state.formatSection) {
        FormatArrivalTracker(videoTierRows.map {it.key}+allVideoSources.map {it.id})
    }
    val selectedAudioId=effectiveChoice?.takeIf {it.audio}?.formatId
    val audioSource=remember(media,selectedAudioId) {
        media?.let {m->selectedAudioId?.let {id->m.formats.firstOrNull {it.id==id&&it.audio}}
            ?: runCatching {Selection.audio(m,"original").primary}.getOrNull()}
    }
    val audioChoices=remember(media,audioSource,state.mp3Available) {
        if(media!=null&&audioSource!=null) QuickShareFormats.audioChoices(media,audioSource,state.mp3Available) else emptyList()
    }
    val audioSourceOptions=remember(media) {
        media?.audioOptions()?.distinctBy {it.id}?.sortedWith(
            compareByDescending<Format> {it.languagePreference ?: 0.0}
                .thenByDescending {it.audioBitrate ?: if(it.streamOnly) it.bitrate ?: 0.0 else 0.0}
                .thenBy {it.id}
        ).orEmpty()
    }
    val audioSourceGroups=remember(audioSourceOptions,audioSource?.id,expandedAudioLanguageKeys) {
        QuickShareAudioRows.grouped(audioSourceOptions,audioSource?.id,expandedAudioLanguageKeys.toSet())
    }
    val audioRows=if(state.expanded) audioChoices else listOfNotNull(audioChoices.firstOrNull {it.output=="mp3"} ?: audioChoices.firstOrNull())
    val hasSelectableVideo=remember(presentation) {presentation?.videoSources?.isNotEmpty()==true}
    val hasSelectableAudio=remember(media,audioRows) {media!=null&&audioRows.any {it.resolve(media)!=null}}
    val visibleSections=remember(state.expanded,state.formatSection,hasSelectableVideo,hasSelectableAudio,effectiveChoice,state.qualityPreference) {
        QuickShareFormatSections.resolve(state.expanded,state.formatSection,hasSelectableVideo,hasSelectableAudio,
            effectiveChoice?.audio==true||state.qualityPreference==-1)
    }
    val showAudio=visibleSections.audio
    val showVideo=visibleSections.video
    val hasSelectableImage=media?.imageOptions()?.isNotEmpty()==true
    val scope=rememberCoroutineScope()
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={it!=SheetValue.Hidden||!state.saving})
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->
        uri?.let {
            runCatching {base.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                .onSuccess {model.setDestination(it.toString())}
                .onFailure {model.storageError()}
        }
    }
    val enqueue={model.enqueue()}
    BackHandler {
        if(state.queuedId!=null||state.persistedId!=null) close()
        else if(reportOpen) reportOpen=false
        else if(!state.saving) {if(state.expanded) model.collapse() else close()}
    }
    val dark=preferences.theme=="dark"||(preferences.theme=="system"&&androidx.compose.foundation.isSystemInDarkTheme())
    val colors=if(dark) Spectrum.Dark else Spectrum.Light
    CompositionLocalProvider(LocalContext provides context,LocalSpectrumContext provides context,LocalConfiguration provides localized,
        LocalSizeLabelCache provides sizeLabels,
        LocalLayoutDirection provides if(preferences.language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
         FlowTheme(colors) {
            val formatPulse=rememberFormatPulse(state.expanded&&(pendingRows>0||state.pendingSizeIds.isNotEmpty()||state.pendingDimensionIds.isNotEmpty()))
            val mp3NeedsEncoder=((effectiveChoice?.audio==true&&effectiveChoice.output=="mp3")||(state.qualityPreference==-1&&state.audioOutput=="mp3"))&&!state.mp3Available
            val hasSelectionOrPref=state.qualityPreference!=null||effectiveChoice!=null||state.selection!=null
            val storageGuard=Build.VERSION.SDK_INT<29&&preferences.destination==null
            val isBusy=state.saving||state.queuedId!=null||state.persistedId!=null
            val scheduleDisabledReason:String?=when {
                isBusy->null
                media==null&&!hasSelectionOrPref->stringResource(R.string.schedule_select_quality_hint)
                mp3NeedsEncoder->stringResource(R.string.schedule_encoder_required)
                state.preferenceUnavailable->stringResource(R.string.share_preference_unavailable,state.qualityPreference ?: 0)
                storageGuard->stringResource(R.string.legacy_folder)
                else->null
            }
            val readyToSchedule=!isBusy&&!state.saving&&state.error==null&&
                (media!=null||hasSelectionOrPref)&&scheduleDisabledReason==null
             ProblemReportSheet(reportOpen,state.sourceUrl.orEmpty(),state.error?.name.orEmpty()) {reportOpen=false}
             if(showFacebookAuthDialog && isFacebookAuth) {
                 AlertDialog(
                     onDismissRequest = { showFacebookAuthDialog = false },
                     title = { Text(stringResource(R.string.facebook_auth_dialog_title)) },
                     text = { Text(stringResource(R.string.facebook_auth_dialog_message)) },
                     confirmButton = {
                         Button(
                             onClick = {
                                 showFacebookAuthDialog = false
                                 showFacebookLogin = true
                             },
                             modifier = Modifier.testTag("facebook_auth_dialog_confirm")
                         ) {
                             Text(stringResource(R.string.facebook_auth_dialog_confirm))
                         }
                     },
                     dismissButton = {
                         TextButton(
                             onClick = { showFacebookAuthDialog = false },
                             modifier = Modifier.testTag("facebook_auth_dialog_dismiss")
                         ) {
                             Text(stringResource(R.string.facebook_auth_dialog_dismiss))
                         }
                     }
                 )
             }
             if(showFacebookLogin) {
                 BrowserAuthDialog(
                     onDismiss={showFacebookLogin=false},
                     onAuthSuccess={
                         showFacebookLogin=false
                         model.retry()
                     }
                 )
             }
             if(schedule) ScheduleDialog({schedule=false}) {plan->schedule=false;model.schedule(plan)}
            state.duplicate?.let {DuplicateDialog(it,model::confirmDuplicate,model::cancelDuplicate,open={match->
                match.uri?.let {uri->
                    val app=context.applicationContext as com.zdownloader.app.ZApplication
                    app.playback.playUri(uri,open=true)
                    close()
                }
            })}
            val playlist by model.playlists.state.collectAsStateWithLifecycle()
            if(playlist.visible) PlaylistPreview(model.playlists,single=model::videoOnly,close=close,queued=close)
             else if(!reportOpen)
             BoxWithConstraints(Modifier.fillMaxSize()) {
                 val limit=maxHeight*.85f
                ModalBottomSheet(onDismissRequest={
                    if(state.queuedId!=null||state.persistedId!=null) {
                        close()
                    } else if(!state.saving) {
                        if(state.expanded) {model.collapse();scope.launch {sheet.show()}}
                        else close()
                    }
                },sheetState=sheet,sheetMaxWidth=480.dp,shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp),
                    containerColor=colors.surfaceContainerLow,contentColor=colors.onSurface,scrimColor=Color.Black.copy(alpha=.64f),
                    dragHandle={Box(Modifier.padding(top=12.dp,bottom=10.dp).size(48.dp,4.dp).background(colors.outline.copy(alpha=.4f),RoundedCornerShape(50)))},
                    contentWindowInsets={WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal)}) {
                    val contentHeight=(limit-32.dp).coerceAtLeast(160.dp)
                      Column(Modifier.fillMaxWidth().then(if(state.expanded) Modifier.height(contentHeight) else Modifier.wrapContentHeight()).drawWithContent {
                         drawContent()
                         if(firstFrameReported.compareAndSet(false,true)) {
                             com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"sheet_drawn")
                             if(state.sourceUrl!=null) com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"preferences_interactive")
                             view.post {model.windowDrawn(state.traceId)}
                         }
                          if(effectiveChoice!=null&&selectionFrameReported.compareAndSet(false,true))
                              com.zdownloader.app.engine.AnalysisTrace.mark(state.traceId,"selectable_drawn")
                          if(state.expanded&&(hasSelectableVideo||hasSelectableAudio||hasSelectableImage)&&
                              firstSelectableFrameReported.compareAndSet(false,true))
                              com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"first_verified_selectable_drawn")
                          if(state.expanded&&state.catalogReady&&presentation!=null&&media!=null&&
                              presentation.media.id==media.id&&completeCatalogFrameReported.compareAndSet(false,true))
                              com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"complete_catalog_drawn")
                     }.testTag(if(state.expanded) "share_expanded" else "share_compact")) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(state.expanded) FlowIconButton(FlowIcons.Back,stringResource(R.string.back),model::collapse,Modifier.testTag("share_back"),enabled=!state.saving)
                            Text(stringResource(if(state.expanded) R.string.quick_more_formats else R.string.download),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f).padding(horizontal=8.dp))
                            FlowIconButton(FlowIcons.Close,stringResource(R.string.close),close,Modifier.testTag("share_close"),enabled=!state.saving)
                        }
                         val listModifier=if(state.expanded) Modifier.weight(1f,fill=false).fillMaxWidth() else Modifier.fillMaxWidth().wrapContentHeight()
                         LazyColumn(listModifier.testTag("share_formats"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=0.dp,bottom=14.dp),
                            verticalArrangement=Arrangement.spacedBy(0.dp)) {
                                item(key="media-title") {
                                  // Reserve the header before metadata arrives so
                                  // discovery cannot push the preference rows down.
                                  Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                     media?.thumbnail?.let {thumbnail->coil.compose.AsyncImage(
                                         coil.request.ImageRequest.Builder(base).data(thumbnail).size(160,90).build(),null,
                                         Modifier.size(64.dp,40.dp))}
                                      Text(state.media?.title.orEmpty(),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis,
                                         modifier=Modifier.weight(1f),color=colors.onSurfaceVariant)
                                 }
                                }
                                if(state.expanded&&visibleSections.modeControl) item(key="format-mode") {
                                    MediaTypeControl(
                                        audio=!showVideo,
                                        videoEnabled=hasSelectableVideo,
                                        audioEnabled=hasSelectableAudio,
                                        audioTag="share_audio_mode",
                                        videoTag="share_video_mode",
                                        onAudio={if(it) model.expandAudio() else model.expandVideo()}
                                    )
                                }
                                if (isFacebookAuth) {
                                    item(key = "facebook-auth-banner") {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                            ),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(20.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Lock,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(36.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.facebook_login_required),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    modifier = Modifier.testTag("facebook_login_required_message")
                                                )
                                            }
                                        }
                                    }
                                } else if(state.sourceUrl!=null&&!state.expanded&&media?.imageOptions().isNullOrEmpty()) {
                                    item(key="initial-preferences") {
                                        if(isLikelyImage && media == null) {
                                            ShareInitialPhotoPreference(
                                                enabled = !state.saving && state.persistedId == null && state.queuedId == null,
                                                onClick = { model.prefer(0) }
                                            )
                                        } else {
                                            ShareInitialPreferences(
                                                selectedPreference=state.qualityPreference?.takeIf {it>=0}
                                                    ?: selectedVideoFormat?.let(::qualityLabel),
                                                audioSelected=(state.qualityPreference==-1&&state.audioOutput=="mp3")||
                                                    (effectiveChoice?.audio==true&&effectiveChoice.output=="mp3"),
                                                enabled=!state.saving&&state.persistedId==null&&state.queuedId==null,
                                                onPreference=model::prefer,
                                                onAudio={model.audioOutput("mp3");model.prefer(-1)},
                                                onMore=model::expand,
                                                videoVisible=media==null||media.videoOptions().isNotEmpty(),
                                                audioVisible=media==null||media.audioOptions().isNotEmpty(),
                                                 sizes=preferenceSizes,qualities=presentation?.preferenceQualities.orEmpty(),audioSize=if(state.choice?.audio==true&&state.choice?.output=="mp3")
                                                    state.selection?.outputSize else presentation?.mp3Size
                                            )
                                        }
                                    }
                                    if(!isLikelyImage&&state.qualityPreference?.let {it>0}==true&&preferenceSelection!=null) item(key="resolved-preference") {
                                        Text(stringResource(R.string.share_cap_resolved,state.qualityPreference ?: 0,qualityTitle(preferenceSelection.primary)),
                                            Modifier.testTag("share_cap_resolved"),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                    }
                                    else if(state.choice!=null&&selectedVideoFormat!=null) item(key="selected-source") {
                                        Text(stringResource(R.string.share_selected_video,qualityTitle(selectedVideoFormat)),
                                            Modifier.testTag("share_selected_video"),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                    }
                                }
                                if(state.sourceUrl!=null&&media==null&&state.expanded&&state.discoveringFormats&&state.error==null) {
                                    item(key="initial-classification") {ShareInitialFormats()}
                                   items(pendingRows,key={"pending-format:$it"},contentType={"format-pending"}) {index->
                                       ShareFormatSkeleton(formatPulse,"share_format_pending_$index")
                                   }
                                  if(Build.VERSION.SDK_INT<29&&preferences.destination==null) item {
                                      Text(stringResource(R.string.legacy_folder),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                      TextButton(onClick={folder.launch(null)},enabled=!state.saving) {Text(stringResource(R.string.choose_folder))}
                                  }
                              }
                             if(state.preferenceUnavailable) item {Text(stringResource(R.string.share_preference_unavailable,state.qualityPreference ?: 0),Modifier.testTag("share_preference_unavailable"),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
                             media?.let {media ->
                                 if(media.imageOptions().isNotEmpty()) {
                                     item(key="photo-heading") {
                                         ShareSection(stringResource(R.string.original_photo))
                                         Text(stringResource(if(media.audioOptions().isEmpty()) R.string.photo_audio_unavailable else R.string.photo_audio_source_hint),
                                             Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                     }
                                     val images=if(state.expanded) media.imageOptions().sortedByDescending {(it.width ?: 0).toLong()*(it.height ?: 0)} else listOfNotNull(media.originalImage())
                                     items(images,key={"photo:${it.id}"}) {image->
                                         val choice=QuickShareChoice(false,image.id)
                                         ShareFormatRow(effectiveChoice?.formatId==image.id,enabled=!state.saving,
                                             title="${image.width}×${image.height} · ${image.ext.uppercase()}",size=sizeLabel(image.size(null)),
                                             audio=false,photo=true,tag="share_photo_${image.id}",subtitle=stringResource(R.string.photo_save_original),click={model.choose(choice)})
                                     }
                                     if(!state.expanded && media.imageOptions().size > 1) item(key="photo-more") {
                                         ShareMoreFormats("share_more", enabled=!state.saving, click=model::expand)
                                     }
                                 }
                                  if(state.expanded&&showVideo&&videoTierRows.isNotEmpty()) {
                                        item(key="video-heading") {
                                          ShareSection(stringResource(R.string.share_video_formats))
                                      }
                                    items(videoTierRows,key={it.key},contentType={"video-quality-tier"}) {row->
                                        val selection=remember(presentation,row.choice) {presentation.selection(row.choice)}
                                        if(selection!=null) {
                                            val fields=presentation.variantFields[row.format.id].orEmpty()
                                            val detail=if(row.canExpand) pluralStringResource(
                                                R.plurals.share_quality_source_count,row.group.candidates.size,row.group.candidates.size
                                            ) else videoVariantDescription(row.format,selection,fields)
                                            val subtitle=if(!row.selected) listOf(detail,stringResource(R.string.share_recommended)).filter {it.isNotBlank()}.joinToString(" · ") else detail
                                            FormatRowArrival(row.key,arrivals,animate=true) {
                                             Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                                 Box(Modifier.weight(1f)) {
                                                     ShareFormatRow(row.selected,!state.saving,qualityTitle(row.format),
                                                         sizeLabel(selection.transferSize),false,row.tag,subtitle=subtitle,
                                                         sizePending=FormatLoadingPresentation.sizePending(state,selection,true),
                                                         pulse=formatPulse,qualityTier=true,click={model.choose(row.choice)})
                                                 }
                                                 if(row.canExpand) IconButton(
                                                     onClick={toggleVideoTier(row.key)},enabled=!state.saving,
                                                     modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag(row.toggleTag)
                                                 ) {
                                                     Icon(if(row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                         contentDescription=stringResource(if(row.expanded) R.string.share_collapse_video_variants else R.string.share_expand_video_variants),
                                                         modifier=Modifier.size(20.dp))
                                                 }
                                                }
                                            }
                                            val motion=LocalFlowMotionEnabled.current
                                            AnimatedVisibility(
                                                visible=row.expanded&&row.canExpand,
                                                enter=if(motion) expandVertically(animationSpec=tween(FlowMotion.Reveal))+fadeIn(animationSpec=tween(FlowMotion.Fast)) else EnterTransition.None,
                                                exit=if(motion) shrinkVertically(animationSpec=tween(FlowMotion.Fast))+fadeOut(animationSpec=tween(FlowMotion.Fast)) else ExitTransition.None
                                            ) {
                                                 Column(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                                     val variants=remember(row.group,effectiveChoice) {
                                                         QuickShareVideoRows.variants(row.group,effectiveChoice)
                                                     }
                                                     val resolvedVariants=variants.mapNotNull {variant->
                                                         presentation.selection(variant.choice)?.let {variant to it}
                                                     }
                                                     val chips=resolvedVariants.map {(variant,variantSelection)->
                                                         VideoVariantChipSpec(variant.format,variant.selected,variant.recommended,
                                                             listOf(videoVariantDescription(variant.format,variantSelection,
                                                                 presentation.variantFields[variant.format.id].orEmpty()),
                                                                 sizeLabel(variantSelection.transferSize))
                                                                 .filter {it.isNotBlank()}.joinToString(" · "),
                                                             variant.tag)
                                                     }
                                                     VideoVariantChips(chips,enabled=!state.saving,
                                                         recommendedLabel=stringResource(R.string.share_recommended),
                                                         modifier=Modifier.fillMaxWidth(),arrivals=arrivals) {format->
                                                         resolvedVariants.firstOrNull {it.first.format.id==format.id}
                                                             ?.first?.let {model.choose(it.choice)}
                                                     }
                                                     resolvedVariants.firstOrNull {it.first.selected}?.let {(variant,variantSelection)->
                                                         FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),
                                                             modifier=Modifier.fillMaxWidth().padding(start=28.dp,bottom=8.dp)) {
                                                             remember(media,variant.format) {media.videoOutputs(variant.format)}.forEach {output->
                                                                 val outputChoice=QuickShareChoice(false,variant.format.id,output)
                                                                 FilterChip(
                                                                     selected=variantSelection.output==output,
                                                                     onClick={model.choose(outputChoice)},enabled=!state.saving,
                                                                     label={Text(output.uppercase()+" — "+sizeLabel(outputChoice.resolve(media)?.outputSize))}
                                                                 )
                                                             }
                                                         }
                                                         if(variantSelection.transcodeVideo) Text(stringResource(R.string.conversion_notice),
                                                             Modifier.padding(start=28.dp,bottom=8.dp),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                                     }
                                                 }
                                            }
                                        }
                                   }
                                      items(pendingRows,key={"pending-format:$it"},contentType={"format-pending"}) {index->
                                          ShareFormatSkeleton(formatPulse,"share_format_pending_$index")
                                      }
                                }
                                   if(showAudio&&(state.expanded||media.imageOptions().isNotEmpty())&&audioSource!=null&&audioRows.isNotEmpty()) {
                                    item(key="audio-heading") {ShareSection(stringResource(R.string.share_audio_formats))}
                                    items(audioRows,key={"audio:${it.output}"}) {choice ->
                                        val selection=remember(media,choice) {choice.resolve(media)}
                                        if(selection!=null) {
                                              val description=when {
                                                  choice.output=="mp3"&&!selection.convertAudio->stringResource(R.string.share_mp3_native_description)
                                                  choice.output=="mp3"->stringResource(R.string.share_mp3_convert_description)
                                                  !selection.convertAudio->stringResource(R.string.share_source_audio_description)
                                                  choice.output=="flac"->stringResource(R.string.share_flac_description)
                                                  else->stringResource(R.string.share_audio_convert_to,choice.output.uppercase(Locale.ROOT))
                                             }
                                             ShareFormatRow(effectiveChoice==choice,enabled=!state.saving,
                                                 title=if(choice.output=="original") "${selection.output.uppercase()} · ${stringResource(R.string.share_audio_original)}" else selection.output.uppercase(),
                                                 size=shareSizeLabel(selection),audio=true,tag="share_audio_${choice.output}",
                                                 subtitle=description,
                                                 sizePending=state.expanded&&FormatLoadingPresentation.sizePending(state,selection,false),
                                                 pulse=if(state.expanded) formatPulse else null,click={model.choose(choice)})
                                        }
                                    }
                                    if(state.expanded&&audioSourceOptions.size>1) {
                                        item(key="audio-source-options") {
                                            Text(stringResource(R.string.quick_audio_source),style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                                        }
                                        items(audioSourceGroups,key={it.key},contentType={"audio-source-group"}) {group->
                                            val sourceOutput=effectiveChoice?.takeIf {it.audio}?.output ?: "original"
                                            val groupChoice=QuickShareChoice(true,group.format.id,sourceOutput)
                                            val groupSelection=remember(media,groupChoice) {groupChoice.resolve(media)}
                                            val summary=if(group.canExpand) pluralStringResource(R.plurals.share_quality_source_count,
                                                group.candidates.size,group.candidates.size)
                                            else listOfNotNull(group.format.audioChannels?.let {"${it}ch"},
                                                group.format.sampleRate?.let {"${it}Hz"}).joinToString(" · ")
                                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                                Box(Modifier.weight(1f)) {
                                                    ShareFormatRow(group.selected,!state.saving,
                                                        audioSourceLabel(group.format,preferences.language),
                                                        groupSelection?.let {shareSizeLabel(it)}.orEmpty(),true,
                                                        if(group.canExpand) group.tag else "share_audio_source_${group.format.id}",
                                                        subtitle=summary,click={model.choose(groupChoice)})
                                                }
                                                if(group.canExpand) IconButton(
                                                    onClick={expandedAudioLanguageKeys=if(group.key in expandedAudioLanguageKeys)
                                                        expandedAudioLanguageKeys-group.key else expandedAudioLanguageKeys+group.key},
                                                    enabled=!state.saving,modifier=Modifier.testTag(group.toggleTag)
                                                ) {
                                                    Icon(if(group.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                        contentDescription=stringResource(if(group.expanded) R.string.share_collapse_video_variants else R.string.share_expand_video_variants))
                                                }
                                            }
                                            AnimatedVisibility(
                                                visible=group.expanded&&group.canExpand,
                                                enter=if(LocalFlowMotionEnabled.current) expandVertically(animationSpec=tween(FlowMotion.Reveal))+fadeIn(animationSpec=tween(FlowMotion.Fast)) else EnterTransition.None,
                                                exit=if(LocalFlowMotionEnabled.current) shrinkVertically(animationSpec=tween(FlowMotion.Fast))+fadeOut(animationSpec=tween(FlowMotion.Fast)) else ExitTransition.None
                                            ) {
                                                Column(Modifier.fillMaxWidth().padding(start=24.dp)) {
                                                    QuickShareAudioRows.variants(group,audioSource?.id).forEach {variant->
                                                        val format=variant.format
                                                        val choice=QuickShareChoice(true,format.id,sourceOutput)
                                                        val selection=remember(media,choice) {choice.resolve(media)}
                                                        ShareFormatRow(variant.selected,!state.saving,
                                                            audioSourceLabel(format,preferences.language),selection?.let {shareSizeLabel(it)}.orEmpty(),true,
                                                            "share_audio_source_${format.id}",
                                                            subtitle=listOfNotNull(format.audioChannels?.let {"${it}ch"},format.sampleRate?.let {"${it}Hz"},format.id).joinToString(" · "),
                                                            click={model.choose(choice)})
                                                    }
                                                }
                                            }
                                        }
                                    }
                                     
                                }
                                  if(state.expanded&&!showVideo&&state.discoveringFormats&&state.error==null) items(pendingRows,key={"pending-format:$it"},contentType={"format-pending"}) {index->
                                      ShareFormatSkeleton(formatPulse,"share_format_pending_$index")
                                  }
                                 if(state.choice!=null&&state.selection==null) item {Text(stringResource(R.string.error_format),color=colors.error,style=MaterialTheme.typography.bodySmall)}
                                 if(Build.VERSION.SDK_INT<29&&preferences.destination==null&&state.error!=ErrorCode.STORAGE_PERMISSION_ERROR) item {
                                     Text(stringResource(R.string.legacy_folder),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                     TextButton(onClick={folder.launch(null)},enabled=!state.saving) {Text(stringResource(R.string.choose_folder))}
                                 }
                            }
                            if(state.expanded) item(key="schedule") {
                                Row(
                                    modifier=Modifier.fillMaxWidth().padding(top=2.dp,bottom=2.dp),
                                    horizontalArrangement=if(LocalLayoutDirection.current==LayoutDirection.Rtl) Arrangement.Start else Arrangement.End,
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    TextButton(onClick={
                                        if(effectiveChoice==null&&state.qualityPreference==null) {
                                            media?.let {QuickShareFormats.defaultChoice(it)}?.let(model::choose)
                                        }
                                        schedule=true
                                    },enabled=readyToSchedule,contentPadding=PaddingValues(horizontal=6.dp,vertical=2.dp),modifier=Modifier.testTag("share_schedule")) {
                                        Icon(FlowIcons.Waiting,null,Modifier.size(16.dp),tint=if(readyToSchedule) MaterialTheme.colorScheme.primary else colors.onSurfaceVariant.copy(alpha=0.38f))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.schedule_download),style=MaterialTheme.typography.labelMedium,color=if(readyToSchedule) MaterialTheme.colorScheme.primary else colors.onSurfaceVariant.copy(alpha=0.38f))
                                    }
                                    if(!readyToSchedule) {
                                        scheduleDisabledReason?.let {reason->Text(reason,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,modifier=Modifier.testTag("share_schedule_disabled_reason"))}
                                    }
                                }
                            }
                            state.error?.let {error -> item {
                                val isFbAuth = isFacebookAuth
                                if (isFbAuth) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        TextButton(onClick=model::retry,enabled=!state.saving,modifier=Modifier.testTag("share_retry")) {Text(stringResource(R.string.retry))}
                                    }
                                } else {
                                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(if(error==ErrorCode.INVALID_URL) R.string.quick_invalid_share else errorResource(error)),color=colors.error,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("share_error"))
                                        TextButton({reportOpen=true},Modifier.testTag("share_report_problem")) {Text(stringResource(R.string.report_action))}
                                        if(error==ErrorCode.STORAGE_PERMISSION_ERROR) {
                                            TextButton(onClick={folder.launch(null)},enabled=!state.saving) {Text(stringResource(R.string.choose_folder))}
                                        } else if(error!=ErrorCode.INVALID_URL) TextButton(onClick=model::retry,enabled=!state.saving,modifier=Modifier.testTag("share_retry")) {Text(stringResource(R.string.retry))}
                                    }
                                }
                            } }
                        }
                        if (isFacebookAuth) {
                            PrimaryAction(
                                onClick = { showFacebookLogin = true },
                                enabled = true,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("facebook_login_button")
                            ) {
                                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.facebook_login_action), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        } else {
                            val canDownload=if(state.choice!=null||state.qualityPreference!=null) {
                                state.canSubmit
                            } else {
                                media!=null&&!state.analyzing&&state.defaultChoice!=null&&!state.saving&&state.error==null&&state.persistedId==null&&state.queuedId==null
                            }
                            PrimaryAction(onClick={
                                enqueue()
                            },enabled=canDownload,
                                shape=RoundedCornerShape(50),
                               modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).testTag("share_download")) {
                               if(state.saving) {FlowLoadingIndicator(compact=true);Spacer(Modifier.width(10.dp));Text(stringResource(R.string.quick_adding))}
                               else {
                                   val buttonIcon=if(isImageMedia||(media==null&&isLikelyImage)) FlowIcons.Photo else FlowIcons.Downloads
                                   val buttonText=if(isImageMedia||(media==null&&isLikelyImage)) stringResource(R.string.photo_save_original) else stringResource(R.string.download)
                                   Icon(buttonIcon,null,Modifier.size(20.dp))
                                   Spacer(Modifier.width(10.dp))
                                   Text(buttonText)
                               }
                           }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun videoVariantDescription(format:Format,selection:Selection,fields:Set<QuickShareVariantDetails.Field>):String {
    return (videoEncodingDetails(format,fields)+videoAudioDetails(selection,fields))
        .filter {it.isNotBlank()}.distinct().joinToString(" · ") {"\u2068$it\u2069"}
}

@Composable internal fun audioSourceLabel(format:Format,language:String):String {
    val displayLocale=Locale.forLanguageTag(language)
    val languageName=format.language?.takeIf {it.isNotBlank()}
        ?.let {Locale.forLanguageTag(it).getDisplayName(displayLocale).ifBlank {it}}
        ?: stringResource(R.string.share_audio_language_unspecified)
    val codec=codecLabel(format.audioCodec).ifBlank {format.audioCodec ?: format.ext.uppercase(Locale.ROOT)}
    val rate=(format.audioBitrate ?: if(format.streamOnly) format.bitrate else null)
        ?.takeIf {it.isFinite()&&it>0}
        ?.let {stringResource(R.string.share_video_rate,java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString())}
    return listOfNotNull(languageName,codec,rate,format.ext.uppercase(Locale.ROOT)).distinct().joinToString(" · ")
}

@Composable private fun videoEncodingDetails(format:Format,fields:Set<QuickShareVariantDetails.Field>):List<String> {
    val rate=(format.videoBitrate ?: format.bitrate)?.takeIf {it.isFinite()&&it>0}
    val dimensions=if(format.width!=null&&format.height!=null) "${format.width}×${format.height}"
        else if(QuickShareVariantDetails.Field.DIMENSIONS in fields) stringResource(R.string.share_actual_resolution_missing) else null
    return listOfNotNull(
        dimensions,
        codecLabel(format.videoCodec).takeIf {QuickShareVariantDetails.Field.CODEC in fields&&it.isNotEmpty()},
        fpsLabel(format.fps)?.takeIf {QuickShareVariantDetails.Field.FPS in fields||(format.fps ?: 0.0)>30}
            ?.let {stringResource(R.string.share_frame_rate,it)},
        rate?.takeIf {QuickShareVariantDetails.Field.BITRATE in fields}?.let {
            stringResource(R.string.share_video_rate,java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString())},
        format.dynamicRange?.takeIf {isHdr(format)},
        format.ext.uppercase(Locale.ROOT).takeIf {QuickShareVariantDetails.Field.CONTAINER in fields},
        format.id.takeIf {QuickShareVariantDetails.Field.SOURCE_ID in fields}?.let {stringResource(R.string.share_format_identity,it)}
    )
}

@Composable private fun videoAudioDetails(selection:Selection,fields:Set<QuickShareVariantDetails.Field>):List<String> {
    val audio=selection.secondary ?: selection.primary
    return listOfNotNull(
        if(!audio.audio) stringResource(R.string.without_audio) else if(QuickShareVariantDetails.Field.AUDIO in fields)
            codecLabel(audio.audioCodec).takeIf {it.isNotEmpty()}?.let {stringResource(R.string.share_audio_codec_value,it)} else null,
        if(selection.secondary!=null&&QuickShareVariantDetails.Field.AUDIO in fields) stringResource(R.string.requires_merge) else null,
        audio.language?.takeIf {QuickShareVariantDetails.Field.LANGUAGE in fields},
        audio.audioChannels?.takeIf {QuickShareVariantDetails.Field.CHANNELS in fields}?.let {stringResource(R.string.share_audio_channels,it)},
        audio.sampleRate?.takeIf {QuickShareVariantDetails.Field.SAMPLE_RATE in fields}?.let {"$it Hz"}
    )
}

/** An unclassified link is not evidence of any downloadable representation. */
@Composable internal fun ShareInitialFormats() {
    Text(stringResource(R.string.analyzing),Modifier.fillMaxWidth().padding(vertical=16.dp).testTag("share_classifying_media"),
        style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Pre-discovery controls when an image URL is recognized. Shows photo preference on frame 1. */
@Composable internal fun ShareInitialPhotoPreference(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=0.dp)) {
        Text(stringResource(R.string.photo_original_hint),Modifier.fillMaxWidth().padding(bottom=8.dp),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ShareFormatRow(
            selected=true,
            enabled=enabled,
            title=stringResource(R.string.original_photo),
            size="",
            audio=false,
            photo=true,
            tag="share_preference_photo",
            subtitle=stringResource(R.string.photo_save_original),
            reserveSize=false,
            click=onClick
        )
    }
}

/** Pre-discovery controls are explicitly preferences, not claims about source formats. */
@Composable internal fun ShareInitialPreferences(
    selectedPreference:Int?,audioSelected:Boolean,enabled:Boolean,
    onPreference:(Int)->Unit,onAudio:()->Unit,onMore:()->Unit,
    videoVisible:Boolean=true,audioVisible:Boolean=true,
    sizes:Map<Int,MediaSize> = emptyMap(),audioSize:MediaSize?=null,qualities:Map<Int,Int> = emptyMap(),
    resolvedHeights:Map<Int,Int> = emptyMap()
) {
    val activeQualities = if (resolvedHeights.isNotEmpty()) resolvedHeights else qualities
    Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=0.dp)) {
        Text(stringResource(R.string.share_compact_preference_hint),Modifier.fillMaxWidth().padding(bottom=8.dp),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(audioVisible) ShareFormatRow(audioSelected,enabled,
            stringResource(R.string.share_preference_mp3_title),audioSize?.let {sizeLabel(it)}.orEmpty(),true,"share_preference_mp3",
            subtitle=stringResource(R.string.share_preference_mp3_description),reserveSize=true,click=onAudio)
        if(videoVisible) {
            QuickShareCatalog.compactPreferences.forEach {height->
                val title=when(height) {
                    360->R.string.share_preference_360_title
                    480->R.string.share_preference_480_title
                    else->R.string.share_preference_720_title
                }
                val description=when(height) {
                    360->R.string.share_preference_360_description
                    480->R.string.share_preference_480_description
                    else->R.string.share_preference_720_description
                }
                val size=sizes[height]?.let {value->sizeLabel(value)}.orEmpty()
                // A cap may resolve to a lower genuine tier; say so instead of implying the cap itself.
                val resolved=activeQualities[height]
                val subtitle=if(resolved!=null&&resolved!=height) stringResource(R.string.share_preset_resolves,"${resolved}p")
                    else stringResource(description)
                ShareFormatRow(selectedPreference==height,enabled,stringResource(title),size,
                    false,"share_preference_$height",subtitle=subtitle,reserveSize=true,click={onPreference(height)})
            }
        }
        ShareMoreFormats("share_more",enabled,onMore)
    }
}

@Composable private fun ShareMoreFormats(tag:String,enabled:Boolean,click:()->Unit) {
    TextButton(onClick=click,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.share_more_section_formats),style=MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.share_more_description),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(FlowIcons.Next,null,Modifier.size(20.dp))
    }
}

@Composable private fun ShareSection(title:String) {
    Row(Modifier.fillMaxWidth().padding(top=22.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.8f))
    }
}

@Composable private fun shareSizeLabel(selection:Selection):String {
    return sizeLabel(selection.outputSize)
}

@Composable internal fun ShareFormatRow(selected:Boolean,enabled:Boolean,title:String,size:String,audio:Boolean,tag:String,subtitle:String="",compact:Boolean=false,
    sizePending:Boolean=false,metadataPending:Boolean=false,pulse:State<Float>?=null,reserveSize:Boolean=false,photo:Boolean=false,
    qualityTier:Boolean=false,click:()->Unit) {
    val interaction=remember {MutableInteractionSource()}
    val tierFill=if(selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.38f)
        else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha=.55f)
    val tierBorder=if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.58f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.72f)
    Surface(
        modifier=if(qualityTier) Modifier.fillMaxWidth().padding(vertical=3.dp) else Modifier,
        shape=RoundedCornerShape(if(qualityTier) 16.dp else 12.dp),
        color=if(qualityTier) tierFill else Color.Transparent,
        contentColor=MaterialTheme.colorScheme.onSurface,
        border=if(qualityTier) BorderStroke(1.dp,tierBorder) else null
    ) {
        val textLayout=LocalLayoutDirection.current
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().heightIn(min=if(qualityTier) 68.dp else 64.dp).testTag(tag)
                .selectable(selected,interactionSource=interaction,indication=null,enabled=enabled,role=Role.RadioButton,onClick=click)
                .padding(horizontal=if(qualityTier) 8.dp else 4.dp,vertical=if(qualityTier) 10.dp else 12.dp),
                verticalAlignment=Alignment.CenterVertically) {
                // Physical left: [Selection Circle] [File Size]
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FlowSelectionIndicator(selected,enabled)
                    if(sizePending&&pulse!=null) {
                        ShareFormatSize(size,sizePending,pulse,tag)
                    } else if(size.isNotEmpty()) {
                        Text(size,modifier=Modifier.testTag("${tag}_size"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    } else if(reserveSize) {
                        Spacer(Modifier.width(48.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                // Physical right: [Title + Subtitle] [Icon] (RTL start has Icon on far right, Column to its left)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(audio) Icon(FlowIcons.Audio,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurface)
                        else if(photo) Icon(FlowIcons.Photo,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurface)
                        else Icon(painterResource(R.drawable.ic_share_video),null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurface)
                        CompositionLocalProvider(LocalLayoutDirection provides textLayout) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp),horizontalAlignment=Alignment.Start) {
                                Text(title,modifier=Modifier.testTag("${tag}_title"),
                                    fontWeight=if(qualityTier) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    textAlign=androidx.compose.ui.text.style.TextAlign.Start,
                                    style=(if(qualityTier) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge).copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content),
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                                if(metadataPending&&pulse!=null) FormatPlaceholderBar(pulse,Modifier.width(120.dp).height(10.dp).testTag("${tag}_metadata_pending"))
                                else if(subtitle.isNotEmpty()) Text(subtitle,Modifier.testTag("${tag}_description"),style=MaterialTheme.typography.bodySmall.copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content),color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun qualityDescription(resolution:Int?):String=when(resolution) {
    1080->stringResource(R.string.quality_full_hd)
    720->stringResource(R.string.quality_hd)
    480->stringResource(R.string.quality_standard)
    360->stringResource(R.string.quality_data_saver)
    else->""
}

@Composable internal fun qualityTitle(format:Format):String {
    if(format.generatedSlideshow) return stringResource(R.string.generated_slideshow)
    val edge=QuickShareCatalog.numericVideoQuality(format) ?: return stringResource(R.string.share_unknown_quality)
    return "${edge}p"
}

/** Isolate encoded width×height so an RTL paragraph cannot visually swap them. */
internal fun encodedDimensionsText(format:Format):String?=format.takeIf {it.dimensionsVerified}?.width?.let {w->format.height?.let {h->"\u2066$w×$h\u2069"}}
