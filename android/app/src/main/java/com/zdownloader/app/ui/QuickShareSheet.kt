package com.zdownloader.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
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

// The sheet keeps its native handle and scrim. A list drag/fling at an edge
// must not hand its remaining vertical velocity to the sheet's dismissal drag.
private object QuickShareListScroll : NestedScrollConnection {
    override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource)=Offset(0f,available.y)
    override suspend fun onPostFling(consumed:Velocity,available:Velocity)=Velocity(0f,available.y)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QuickShareSheet(model:QuickShareViewModel,close:()->Unit) {
    // Lazy items must use the same state snapshot as the section that owns them.
    val state=model.state.collectAsStateWithLifecycle().value
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
    val presentation=key(state.sourceUrl) {rememberQuickSharePresentation(state.media,sizeLabels)}
    val media=presentation?.media
    val (overviewListState,videoListState,audioListState)=key(state.sourceUrl) {
        Triple(rememberLazyListState(),rememberLazyListState(),rememberLazyListState())
    }
    var schedule by remember(state.sourceUrl) {mutableStateOf(false)}
    var reportOpen by rememberSaveable(state.sourceUrl) {mutableStateOf(false)}
    var showFacebookLogin by rememberSaveable(state.sourceUrl) {mutableStateOf(false)}
    val isFacebookAuth = state.error == ErrorCode.AUTHENTICATION_REQUIRED &&
        (com.zdownloader.app.engine.FacebookScraper.isFacebookUrl(state.sourceUrl.orEmpty()) ||
         runCatching { com.zdownloader.app.domain.UrlPolicy.inputLinks(state.sourceUrl.orEmpty()).any { com.zdownloader.app.engine.FacebookScraper.isFacebookUrl(it) } }.getOrDefault(false))
    var showFacebookAuthDialog by rememberSaveable(state.error) { mutableStateOf(isFacebookAuth) }
    val isImageMedia = media?.let { it.videoOptions().isEmpty() && it.imageOptions().isNotEmpty() } == true
    val isLikelyImage = remember(state.sourceUrl) { QuickShareFormats.isLikelyImageUrl(state.sourceUrl) }
    val showPreferenceOptions=if(media==null) !isLikelyImage else !isImageMedia
    val compactOverview=!state.expanded&&showPreferenceOptions&&!isFacebookAuth
    val videoGroups=presentation?.groups.orEmpty()
    var openVideoGroups by rememberSaveable(state.sourceUrl) {mutableStateOf(emptyList<String>())}
    var collapsedAudioLanguageKeys by rememberSaveable(state.sourceUrl) {mutableStateOf(emptyList<String>())}
    var preferenceOptions by rememberSaveable(state.sourceUrl) {mutableStateOf(false)}
    val pendingRows=FormatLoadingPresentation.pendingRows(state,presented=presentation!=null)
    val effectiveChoice=state.choice
    val overviewChoice=effectiveChoice ?: if(compactOverview&&state.canSubmitDefault) state.defaultChoice else null
    val overviewVideoGroups=remember(videoGroups,overviewChoice) {compactTierGroups(videoGroups,overviewChoice)}
    val firstFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val selectionFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val firstSelectableFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val completeCatalogFrameReported=remember(state.traceId) {java.util.concurrent.atomic.AtomicBoolean(false)}
    val reportSelectableFrame:()->Unit=remember(state.traceId) {
        {
            if(firstSelectableFrameReported.compareAndSet(false,true))
                com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"first_verified_selectable_drawn")
        }
    }
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
    val arrivals=remember(state.sourceUrl) {
        FormatArrivalTracker(videoGroups.map {it.key}+presentation?.videoSources.orEmpty().map {it.id})
    }
    val selectedAudioId=overviewChoice?.takeIf {it.audio}?.formatId
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
    val audioSourceGroups=remember(audioSourceOptions,audioSource?.id,collapsedAudioLanguageKeys) {
        val groups=QuickShareAudioRows.grouped(audioSourceOptions,audioSource?.id,emptySet())
        groups.map {it.copy(expanded=it.key !in collapsedAudioLanguageKeys)}
    }
    val selectedAudioChoice=overviewChoice?.takeIf {it.audio&&it.formatId==audioSource?.id}
    val audioRows=remember(audioChoices,state.expanded,selectedAudioChoice) {
        audioChoices.filter {state.expanded||it.output=="mp3"||it.output=="original"||it.output==selectedAudioChoice?.output}
            .map {choice->selectedAudioChoice?.takeIf {it.output==choice.output} ?: choice}
    }
    val hasSelectableVideo=remember(presentation) {presentation?.videoSources?.isNotEmpty()==true}
    val hasSelectableAudio=audioChoices.isNotEmpty()
    val visibleAudioRows=if(compactOverview&&hasSelectableVideo) {
        audioRows.filter {it.output=="mp3"||it==overviewChoice}
    } else audioRows
    val visibleSections=remember(state.formatSection,hasSelectableVideo,hasSelectableAudio,effectiveChoice,state.qualityPreference,state.defaultDownloadType) {
        val preferAudio=effectiveChoice?.audio ?: state.qualityPreference?.let {it==-1}
            ?: (state.defaultDownloadType=="audio")
        QuickShareFormatSections.resolve(state.formatSection,hasSelectableVideo,hasSelectableAudio,preferAudio)
    }
    val showAudio=visibleSections.audio
    val showVideo=visibleSections.video
    val images=remember(media) {
        media?.imageOptions()?.distinctBy {it.id}?.sortedWith(
            compareByDescending<Format> {(it.width ?: 0).toLong()*(it.height ?: 0)}.thenBy {it.id}
        ).orEmpty()
    }
    val scope=rememberCoroutineScope()
    // Material keys the sheet state on this callback; catalog updates must not recreate its drag anchors.
    val confirmSheetChange=remember(model) {
        {target:SheetValue->target!=SheetValue.Hidden||!model.state.value.saving}
    }
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange=confirmSheetChange)
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->
        uri?.let {
            runCatching {base.contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                .onSuccess {model.setDestination(it.toString())}
                .onFailure {model.storageError()}
        }
    }
    val choose:(QuickShareChoice)->Unit={choice->
        if(model.state.value.sourceUrl==state.sourceUrl) model.choose(choice)
    }
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
             val formatPulse=rememberFormatPulse(pendingRows>0||state.pendingSizeIds.isNotEmpty())
             val mp3NeedsEncoder=!state.mp3Available&&(state.selection?.let {it.audioOnly&&it.output=="mp3"&&it.convertAudio}==true||
                 state.qualityPreference==-1&&state.audioOutput=="mp3")
             val canDownload=state.canSubmit||(presentation!=null&&state.canSubmitDefault)
             val editingEnabled=!state.saving&&state.persistedId==null&&state.queuedId==null
             val storageGuard=Build.VERSION.SDK_INT<29&&preferences.destination==null
             val isBusy=state.saving||state.queuedId!=null||state.persistedId!=null
            val scheduleDisabledReason:String?=when {
                isBusy->null
                 mp3NeedsEncoder->stringResource(R.string.schedule_encoder_required)
                  state.preferenceUnavailable->state.qualityPreference?.let {stringResource(R.string.share_preference_unavailable,it)}
                      ?: stringResource(R.string.error_format)
                 storageGuard->stringResource(R.string.legacy_folder)
                 !canDownload->stringResource(R.string.schedule_select_quality_hint)
                else->null
            }
             val readyToSchedule=canDownload&&scheduleDisabledReason==null
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
              if(schedule) ScheduleDialog({schedule=false}) {plan->
                  schedule=false
                  if(model.state.value.sourceUrl==state.sourceUrl) model.schedule(plan)
              }
            state.duplicate?.let {DuplicateDialog(it,model::confirmDuplicate,model::cancelDuplicate,open={match->
                match.uri?.let {uri->
                    val app=context.applicationContext as com.zdownloader.app.ZApplication
                    app.playback.playUri(uri,open=true)
                    close()
                }
            })}
            val playlist by model.playlists.state.collectAsStateWithLifecycle()
            if(playlist.visible) PlaylistPreview(model.playlists,single=model::videoOnly,close=close,queued=close,selectQuality=model::selectPlaylistItem)
              else if(!reportOpen)
              BoxWithConstraints(Modifier.fillMaxSize()) {
                      val sheetHeightRatio=if(maxHeight<400.dp) .94f else if(compactOverview) .80f else .85f
                     val contentHeight=(maxHeight*sheetHeightRatio-28.dp).coerceAtLeast(0.dp)
                ModalBottomSheet(onDismissRequest={
                    if(state.queuedId!=null||state.persistedId!=null) {
                        close()
                    } else if(!state.saving) {
                        if(state.expanded) {model.collapse();scope.launch {sheet.show()}}
                        else close()
                    }
                  },sheetState=sheet,sheetMaxWidth=480.dp,
                     shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp),
                    containerColor=colors.surfaceContainerLow,contentColor=colors.onSurface,scrimColor=Color.Black.copy(alpha=.64f),
                     dragHandle={Box(Modifier.testTag("share_drag_handle").padding(top=12.dp,bottom=10.dp)
                         .size(48.dp,4.dp).background(colors.outline.copy(alpha=.4f),RoundedCornerShape(50)))},
                    contentWindowInsets={WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal)}) {
                        Column(Modifier.fillMaxWidth().heightIn(max=contentHeight).drawWithContent {
                         drawContent()
                         if(firstFrameReported.compareAndSet(false,true)) {
                             com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"sheet_drawn")
                             if(state.sourceUrl!=null) com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"preferences_interactive")
                             view.post {model.windowDrawn(state.traceId)}
                         }
                          if(effectiveChoice!=null&&selectionFrameReported.compareAndSet(false,true))
                              com.zdownloader.app.engine.AnalysisTrace.mark(state.traceId,"selectable_drawn")
                           if(state.catalogReady&&presentation!=null&&presentation.media==state.media&&
                               completeCatalogFrameReported.compareAndSet(false,true))
                              com.zdownloader.app.engine.AnalysisTrace.checkpoint(state.traceId,"complete_catalog_drawn")
                     }.testTag(if(state.expanded) "share_expanded" else "share_compact")) {
                         Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).heightIn(min=48.dp).testTag("share_header"),
                             verticalAlignment=Alignment.CenterVertically) {
                            if(state.expanded) FlowIconButton(FlowIcons.Back,stringResource(R.string.back),model::collapse,Modifier.testTag("share_back"),enabled=!state.saving)
                             val thumbnail=media?.thumbnail?.takeUnless {compactOverview}
                             if(thumbnail!=null) {
                                 val thumbnailRequest=remember(base,thumbnail) {coil.request.ImageRequest.Builder(base).data(thumbnail).size(160,90).build()}
                                 coil.compose.AsyncImage(thumbnailRequest,null,Modifier.size(44.dp,32.dp))
                             }
                              val title=if(compactOverview) stringResource(R.string.download)
                                  else media?.title?.takeIf {it.isNotBlank()} ?: stringResource(R.string.quick_share_title)
                              Text(title,
                                  style=if(compactOverview) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                   modifier=Modifier.weight(1f).padding(horizontal=8.dp).semantics {heading()},
                                 maxLines=1,overflow=TextOverflow.Ellipsis)
                            FlowIconButton(FlowIcons.Close,stringResource(R.string.close),close,Modifier.testTag("share_close"),enabled=!state.saving)
                         }
                          if(state.expanded&&visibleSections.modeControl) {
                             Box(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=6.dp)
                                 .selectableGroup().testTag("share_media_mode")) {
                                 MediaTypeControl(
                                     audio=showAudio,
                                     videoEnabled=editingEnabled&&hasSelectableVideo,
                                     audioEnabled=editingEnabled&&hasSelectableAudio,
                                     audioTag="share_audio_mode",
                                     videoTag="share_video_mode",
                                     onAudio={if(it) model.showAudio() else model.showVideo()}
                                 )
                             }
                         }
                          // The overview and each details tab have distinct item keys and scroll positions.
                           key(compactOverview,!compactOverview&&showAudio) {
                               LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth()
                                   .nestedScroll(QuickShareListScroll).testTag("share_formats"),
                               state=when {
                                   compactOverview->overviewListState
                                   showAudio->audioListState
                                   else->videoListState
                               },
                               contentPadding=PaddingValues(start=20.dp,end=20.dp,top=0.dp,bottom=8.dp),
                               verticalArrangement=Arrangement.spacedBy(0.dp)) {
                                  if(compactOverview&&media!=null) item(key="overview-preview") {
                                      QuickShareOverviewPreview(media,if(hasSelectableVideo) FlowIcons.Video else FlowIcons.Audio)
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
                                 }
                                val formatActions:LazyListScope.()->Unit={
                                  if(state.sourceUrl!=null&&!isFacebookAuth&&(state.error==null||media!=null)) item(key="more-formats") {
                                       val showMore=!isImageMedia||images.size>1||audioSourceOptions.size>1
                                       val checking=FormatLoadingPresentation.morePending(state,showVideo||compactOverview&&hasSelectableVideo)
                                        val togglePreferences={preferenceOptions=!preferenceOptions}
                                        if(compactOverview) {
                                            HorizontalDivider(color=colors.outlineVariant.copy(alpha=.5f))
                                            if(showMore) QuickShareOverviewMore(editingEnabled,checking,model::expand)
                                            QuickShareActionRow(QuickShareAction("share_preferences",R.string.share_download_preferences,
                                                FlowIcons.Tune,if(preferenceOptions) ActionEnd.Collapse else ActionEnd.Expand),
                                                editingEnabled,togglePreferences)
                                        } else {
                                            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                                if(showMore) ShareMoreFormats("share_more",enabled=editingEnabled,
                                                    click={if(state.expanded) model.collapse() else model.expand()},expanded=state.expanded,
                                                    checking=checking)
                                                if(showPreferenceOptions) TextButton(onClick=togglePreferences,enabled=editingEnabled,
                                                    modifier=Modifier.testTag("share_preferences").semantics {this.selected=preferenceOptions}) {
                                                    Text(stringResource(R.string.share_download_preferences))
                                                }
                                              }
                                        }
                                 }
                                 if(state.sourceUrl!=null&&state.error==null&&showPreferenceOptions&&preferenceOptions) {
                                     item(key="initial-preferences") {
                                         ShareInitialPreferences(
                                             selectedPreference=state.qualityPreference?.takeIf {it>=0},
                                             audioSelected=state.qualityPreference==-1&&state.audioOutput=="mp3",
                                             enabled=editingEnabled,
                                             onPreference=model::prefer,
                                             onAudio={model.audioOutput("mp3");model.prefer(-1)},
                                             videoVisible=media==null||hasSelectableVideo,
                                             audioVisible=media==null||hasSelectableAudio,
                                             sizes=presentation?.preferenceSizes.orEmpty(),qualities=presentation?.preferenceQualities.orEmpty(),
                                             audioSize=presentation?.mp3Size
                                         )
                                     }
                                 }
                                 val resolvedCap=state.qualityPreference?.takeIf {it>0}
                                 val resolvedHeight=resolvedCap?.let {presentation?.preferenceQualities?.get(it)}
                                  if(resolvedCap!=null&&resolvedHeight!=null&&!state.analyzing) item(key="resolved-preference") {
                                      Text(stringResource(R.string.share_cap_resolved,resolvedCap,"${resolvedHeight}p"),
                                          Modifier.testTag("share_cap_resolved"),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                  }
                                }
                                if(!compactOverview) formatActions()
                                 if(pendingRows>0) {
                                     if(isLikelyImage&&state.media==null) item(key="initial-photo") {
                                         ShareInitialPhotoPreference(editingEnabled) {model.prefer(0)}
                                     }
                                     item(key="initial-classification") {ShareInitialFormats()}
                                     item(key="pending-format",contentType="format-pending") {
                                         ShareFormatSkeleton(formatPulse,"share_format_pending_0")
                                     }
                                 }
                               val unavailablePreference=state.qualityPreference?.takeIf {state.preferenceUnavailable}
                               if(unavailablePreference!=null) item(key="unavailable-preference") {
                                   Text(stringResource(R.string.share_preference_unavailable,unavailablePreference),
                                       Modifier.testTag("share_preference_unavailable"),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                               }
                              presentation?.let {catalog ->
                                  val media=catalog.media
                                  if(images.isNotEmpty()) {
                                     item(key="photo-heading") {
                                         ShareSection(stringResource(R.string.original_photo))
                                          Text(stringResource(if(!hasSelectableAudio) R.string.photo_audio_unavailable else R.string.photo_audio_source_hint),
                                             Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                     }
                                       val visibleImages=if(state.expanded) images
                                           else listOf(images.firstOrNull {it.id==effectiveChoice?.formatId} ?: images.first())
                                       items(visibleImages,key={"photo:${it.id}"}) {image->
                                           val choice=QuickShareChoice(false,image.id)
                                           ShareFormatRow(effectiveChoice?.formatId==image.id,enabled=editingEnabled,
                                               title="${image.width}×${image.height} · ${image.ext.uppercase()}",
                                               size=image.size(null)?.let {sizeLabel(it)}.orEmpty(),
                                               tag="share_photo_${image.id}",subtitle=stringResource(R.string.photo_save_original),
                                                onDrawn=reportSelectableFrame,click={choose(choice)})
                                      }
                                  }
                                     if((compactOverview||showAudio)&&audioSource!=null&&visibleAudioRows.isNotEmpty()) {
                                      if(!compactOverview&&!visibleSections.modeControl) item(key="audio-heading") {ShareSection(stringResource(R.string.share_audio_formats))}
                                     items(visibleAudioRows,key={"audio:${it.output}"}) {choice ->
                                         val selection=remember(media,choice) {choice.resolve(media)}
                                         if(selection!=null) {
                                               val original=choice.output=="original"
                                               val outputLabel=selection.output.uppercase(Locale.ROOT)
                                                val description=when {
                                                    original->outputLabel
                                                    compactOverview&&choice.output=="mp3"->stringResource(R.string.share_preference_mp3_description)
                                                    choice.output=="mp3"&&!selection.convertAudio->stringResource(R.string.share_mp3_native_description)
                                                  choice.output=="mp3"->stringResource(R.string.share_mp3_convert_description)
                                                  !selection.convertAudio->stringResource(R.string.share_source_audio_description)
                                                  choice.output=="flac"->stringResource(R.string.share_flac_description)
                                                  else->stringResource(R.string.share_audio_convert_to,choice.output.uppercase(Locale.ROOT))
                                             }
                                                 ShareFormatRow(overviewChoice==choice,enabled=editingEnabled,
                                                   title=if(original) stringResource(R.string.share_audio_original) else outputLabel,
                                                  size=shareSizeLabel(selection),tag="share_audio_${choice.output}",
                                                    subtitle=description,
                                                   overviewIcon=FlowIcons.Audio.takeIf {compactOverview},
                                                  sizePending=FormatLoadingPresentation.sizePending(state,selection,false),
                                                   pulse=formatPulse,onDrawn=reportSelectableFrame,click={choose(choice)})
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
                                             if(groupSelection!=null) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                                Box(Modifier.weight(1f)) {
                                                     ShareFormatRow(group.selected,editingEnabled,
                                                         audioSourceLabel(group.format,preferences.language),
                                                          shareSizeLabel(groupSelection),
                                                          if(group.canExpand) group.tag else "share_audio_source_${group.format.id}",
                                                          click={choose(groupChoice)})
                                                }
                                                if(group.canExpand) IconButton(
                                                     onClick={collapsedAudioLanguageKeys=if(group.key in collapsedAudioLanguageKeys)
                                                         collapsedAudioLanguageKeys-group.key else collapsedAudioLanguageKeys+group.key},
                                                     enabled=editingEnabled,modifier=Modifier.testTag(group.toggleTag)
                                                ) {
                                                    Icon(if(group.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                        contentDescription=stringResource(if(group.expanded) R.string.share_collapse_video_variants else R.string.share_expand_video_variants))
                                                }
                                            }
                                             if(group.expanded&&group.canExpand) {
                                                Column(Modifier.fillMaxWidth().padding(start=24.dp)) {
                                                     QuickShareAudioRows.variants(group,audioSource.id).filter {it.format.id!=group.format.id}.forEach {variant->
                                                        val format=variant.format
                                                        val choice=QuickShareChoice(true,format.id,sourceOutput)
                                                        val selection=remember(media,choice) {choice.resolve(media)}
                                                         if(selection!=null) ShareFormatRow(variant.selected,editingEnabled,
                                                              audioSourceLabel(format,preferences.language),shareSizeLabel(selection),
                                                              "share_audio_source_${format.id}",
                                                              subtitle=listOfNotNull(format.audioChannels?.let {"${it}ch"},format.sampleRate?.let {"${it}Hz"}).joinToString(" · "),
                                                             click={choose(choice)})
                                                    }
                                                }
                                            }
                                        }
                                    }

                                  }
                                   if((compactOverview||showVideo)&&videoGroups.isNotEmpty()) {
                                       if(!compactOverview&&!visibleSections.modeControl) item(key="video-heading") {
                                           ShareSection(stringResource(R.string.share_video_formats))
                                       }
                                        val listedGroups=if(compactOverview) overviewVideoGroups else videoGroups
                                        items(listedGroups,key={
                                            if(compactOverview) "overview-video:${it.dimensions.edge}:${it.recommended.generatedSlideshow}" else it.key
                                        },contentType={"video-quality-tier"}) {group->
                                           QuickShareVideoTier(group,catalog,state,group.key in openVideoGroups,editingEnabled,formatPulse,arrivals,
                                               reportSelectableFrame,choose) {
                                               openVideoGroups=if(group.key in openVideoGroups)
                                                   openVideoGroups-group.key else openVideoGroups+group.key
                                           }
                                       }
                                   }
                                   val hasMoreSources=images.size>1||if(showVideo)
                                        videoGroups.any {it.candidates.size>1}||overviewVideoGroups.size<videoGroups.size
                                        else audioSourceOptions.size>1
                                  if(state.expanded) {
                                      if(state.discoveryError!=null||showVideo&&state.failedDimensionIds.isNotEmpty()) item(key="more-failure") {
                                          Text(stringResource(R.string.share_sources_partial),modifier=Modifier.testTag("share_more_failure"),
                                              style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                          if(state.failedDimensionIds.isNotEmpty()) TextButton(model::retrySources,
                                              enabled=editingEnabled&&!state.discoveringFormats,modifier=Modifier.testTag("share_retry_sources")) {
                                              Text(stringResource(R.string.retry))
                                          }
                                      } else if(state.catalogReady&&!FormatLoadingPresentation.morePending(state,showVideo)&&!hasMoreSources) {
                                          item(key="no-more-sources") {
                                              Text(stringResource(R.string.share_no_more_sources),Modifier.testTag("share_no_more_sources"),
                                                  style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                          }
                                      }
                                  }
                                   if(state.choice!=null&&state.selection==null&&!state.analyzing) item(key="unavailable-choice") {
                                       Text(stringResource(R.string.error_format),color=colors.error,style=MaterialTheme.typography.bodySmall)
                                   }
                               }
                               if(compactOverview) formatActions()
                             if(storageGuard&&state.sourceUrl!=null&&state.error!=ErrorCode.STORAGE_PERMISSION_ERROR) item(key="legacy-folder") {
                                      Text(stringResource(R.string.legacy_folder),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                                      TextButton(onClick={folder.launch(null)},enabled=editingEnabled) {Text(stringResource(R.string.choose_folder))}
                             }
                             if(state.sourceUrl!=null&&!isFacebookAuth) item(key="schedule") {
                                 QuickShareActionRow(QuickShareAction("share_schedule",R.string.schedule_download,FlowIcons.Waiting,
                                     supportingText=scheduleDisabledReason),readyToSchedule) {
                                     if(state.canSubmitDefault) state.defaultChoice?.let(choose)
                                     schedule=true
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
                                         if(error in setOf(ErrorCode.STORAGE_PERMISSION_ERROR,ErrorCode.STORAGE_DESTINATION_UNAVAILABLE)) {
                                            TextButton(onClick={folder.launch(null)},enabled=!state.saving) {Text(stringResource(R.string.choose_folder))}
                                        } else if(error!=ErrorCode.INVALID_URL) TextButton(onClick=model::retry,enabled=!state.saving,modifier=Modifier.testTag("share_retry")) {Text(stringResource(R.string.retry))}
                                    }
                                }
                            } }
                            }
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
                              if(compactOverview) QuickShareOverviewSelection(state,presentation,overviewChoice)
                              PrimaryAction(onClick={
                                 if(model.state.value.sourceUrl==state.sourceUrl) model.enqueue()
                             },enabled=canDownload,
                                shape=RoundedCornerShape(50),
                                modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp).testTag("share_download")) {
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

@Composable private fun QuickShareVideoTier(
    group:QuickShareQualityGroup,catalog:QuickSharePresentation,state:QuickShareState,open:Boolean,enabled:Boolean,
    pulse:State<Float>,arrivals:FormatArrivalTracker,onDrawn:()->Unit,choose:(QuickShareChoice)->Unit,toggle:()->Unit
) {
    val current=state.choice ?: if(!state.expanded&&state.canSubmitDefault) state.defaultChoice else null
    val selected=group.selectedChoice(current)
    val choice=selected ?: QuickShareChoice(false,group.recommended.id)
    val selection=remember(catalog,choice) {catalog.selection(choice)} ?: return
    val title=shareVideoQualityTitle(selection.primary,group.dimensions)
    val subtitle=if(state.expanded) verifiedTierDimensions(group.dimensions)
        else overviewQualityDescription(selection.primary,group.dimensions)
    val canOpen=state.expanded&&(group.candidates.size>1||selected!=null)
    FormatRowArrival(group.key,arrivals,animate=true) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ShareFormatRow(selected!=null,enabled,title,selection.transferSize?.let {sizeLabel(it)}.orEmpty(),group.tag,
                     subtitle=subtitle,
                    overviewIcon=FlowIcons.Video.takeUnless {state.expanded},
                    sizePending=FormatLoadingPresentation.sizePending(state,selection,true),
                    pulse=pulse,onDrawn=onDrawn,click={choose(choice)})
            }
            if(canOpen) IconButton(toggle,enabled=enabled,modifier=Modifier.testTag(group.toggleTag)) {
                Icon(if(open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription=stringResource(if(open) R.string.share_collapse_video_variants else R.string.share_expand_video_variants))
            }
        }
    }
    if(!canOpen||!open) return
    QuickShareVideoOptions(group,catalog,selected,enabled,choose)
}

/** The overview has one entry per quality name; details keep each encoded size and source. */
private fun compactTierGroups(groups:List<QuickShareQualityGroup>,choice:QuickShareChoice?):List<QuickShareQualityGroup> =
    groups.groupBy {it.dimensions.edge to it.recommended.generatedSlideshow}.values.map {sameTier->
        sameTier.firstOrNull {it.selectedChoice(choice)!=null} ?: sameTier.first()
    }

@Composable private fun shareVideoQualityTitle(format:Format,dimensions:QuickShareDimensions):String=when {
    format.generatedSlideshow->stringResource(R.string.generated_slideshow)
    else->dimensions.edge?.let {"\u2066${it}p\u2069"} ?: stringResource(R.string.share_unknown_quality)
}

@Composable private fun overviewQualityDescription(format:Format,dimensions:QuickShareDimensions):String {
    val edge=dimensions.edge
    return stringResource(when {
        format.generatedSlideshow||edge==null->R.string.video
        edge<360->R.string.share_overview_basic_quality
        edge==360->R.string.share_preference_360_description
        edge<720->R.string.share_preference_480_description
        edge==720->R.string.share_preference_720_description
        else->R.string.share_overview_high_quality
    })
}

private fun verifiedTierDimensions(dimensions:QuickShareDimensions):String =
    dimensions.width?.let {"\u2066$it×${dimensions.height}\u2069"}.orEmpty()

@Composable private fun QuickShareOverviewPreview(media:Media,icon:ImageVector) {
    val colors=MaterialTheme.colorScheme
    val title=media.title.ifBlank {media.source}
    Column(Modifier.fillMaxWidth().testTag("share_overview_preview")) {
        if(title.isNotBlank()||media.thumbnail!=null) Surface(
            shape=RoundedCornerShape(14.dp),color=colors.surfaceContainer
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                QuickSharePreviewArtwork(media.thumbnail,icon)
                Column(Modifier.weight(1f)) {
                    Text(title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(media.source.isNotBlank()&&!media.source.equals(title,ignoreCase=true)) {
                        Text(media.source,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text(stringResource(R.string.share_overview_formats_heading),
            Modifier.padding(start=8.dp,top=8.dp,bottom=2.dp).semantics {heading()},
            style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)
    }
}

@Composable private fun QuickSharePreviewArtwork(thumbnail:String?,icon:ImageVector) {
    Box(Modifier.size(42.dp)) {
        FlowIconTile(icon,size=42.dp,iconSize=20.dp)
        if(thumbnail!=null) {
            val context=LocalContext.current
            val request=remember(context,thumbnail) {coil.request.ImageRequest.Builder(context).data(thumbnail).size(128).build()}
            coil.compose.AsyncImage(request,null,Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)),
                contentScale=ContentScale.Crop)
        }
    }
}

@Composable private fun QuickShareOverviewSelection(
    state:QuickShareState,catalog:QuickSharePresentation?,choice:QuickShareChoice?
) {
    val selection=remember(catalog,choice) {choice?.let {catalog?.selection(it)}}
    val label=if(selection!=null) {
        val format=when {
            !selection.audioOnly->shareVideoQualityTitle(selection.primary,QuickShareDimensions.from(selection.primary))
            choice?.output=="original"->stringResource(R.string.share_audio_original)
            else->selection.output.uppercase(Locale.ROOT)
        }
        stringResource(R.string.share_overview_selected_format,format)
    } else state.qualityPreference?.let {height->
        val preference=when(height) {
            -1->if(state.audioOutput=="mp3") "MP3" else stringResource(R.string.share_audio_original)
            0->stringResource(R.string.share_best_available)
            else->stringResource(R.string.share_prefer_up_to,height)
        }
        stringResource(R.string.share_requested_preference,preference)
    }
    if(label!=null) Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
        val colors=MaterialTheme.colorScheme
        HorizontalDivider(color=colors.outlineVariant.copy(alpha=.5f))
        Text(label,Modifier.fillMaxWidth().padding(top=6.dp).testTag("share_overview_selected"),
            color=colors.onSurfaceVariant,style=MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun QuickShareVideoOptions(
    group:QuickShareQualityGroup,catalog:QuickSharePresentation,selected:QuickShareChoice?,enabled:Boolean,
    choose:(QuickShareChoice)->Unit
) {
    if(group.candidates.size>1) {
        Column(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp,bottom=4.dp)) {
            group.candidates.forEachIndexed {index,source->key(source.id) {
                val option=selected?.takeIf {it.formatId==source.id} ?: QuickShareChoice(false,source.id)
                val alternate=remember(catalog,option) {catalog.selection(option)}
                if(alternate!=null) {
                    ShareFormatRow(selected?.formatId==source.id,enabled,
                        stringResource(R.string.share_source_variant,index+1),
                        alternate.transferSize?.let {sizeLabel(it)}.orEmpty(),"share_video_variant_${source.id}",
                        subtitle=videoVariantDescription(source,alternate,catalog.variantFields[source.id].orEmpty()),
                        click={choose(option)})
                }
            }}
        }
    }
    val selection=selected?.let {catalog.selection(it)} ?: return
    QuickShareVideoOutputs(catalog,selection,enabled,choose)
}

@Composable private fun QuickShareVideoOutputs(
    catalog:QuickSharePresentation,selection:Selection,enabled:Boolean,choose:(QuickShareChoice)->Unit
) {
    val format=selection.primary
    val outputs=remember(catalog,format) {
        // A visible cached choice can expire before computing its optional output chips.
        val available=try {catalog.media.videoOutputs(format)} catch(_:MediaException) {emptyList()}
        available.mapNotNull {output->
            val choice=QuickShareChoice(false,format.id,output)
            catalog.selection(choice)?.let {choice to it}
        }
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=4.dp)) {
        outputs.forEach { (choice,output) ->
            FilterChip(selected=selection.output==output.output,onClick={choose(choice)},enabled=enabled,
                modifier=Modifier.testTag("share_video_output_${output.output}"),
                label={Text(listOf(output.output.uppercase(Locale.ROOT),shareSizeLabel(output))
                    .filter {it.isNotBlank()}.joinToString(" — "))})
        }
    }
    if(selection.transcodeVideo) Text(stringResource(R.string.conversion_notice),
        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun videoVariantDescription(format:Format,selection:Selection,fields:Set<QuickShareVariantDetails.Field>):String {
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
    return listOfNotNull(
        QuickShareCatalog.sourceQualityLabel(format)?.let {stringResource(R.string.share_source_tier,it)},
        codecLabel(format.videoCodec).takeIf {it.isNotEmpty()},
        fpsLabel(format.fps)?.takeIf {QuickShareVariantDetails.Field.FPS in fields||(format.fps ?: 0.0)>30}
            ?.let {stringResource(R.string.share_frame_rate,it)},
        rate?.takeIf {QuickShareVariantDetails.Field.BITRATE in fields}?.let {
            stringResource(R.string.share_video_rate,java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString())},
        format.dynamicRange?.takeIf {isHdr(format)},
        format.ext.uppercase(Locale.ROOT)
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
            tag="share_preference_photo",
            subtitle=stringResource(R.string.photo_save_original),
            click=onClick
        )
    }
}

/** Pre-discovery controls are explicitly preferences, not claims about source formats. */
@Composable internal fun ShareInitialPreferences(
    selectedPreference:Int?,audioSelected:Boolean,enabled:Boolean,
    onPreference:(Int)->Unit,onAudio:()->Unit,onMore:(()->Unit)?=null,
    videoVisible:Boolean=true,audioVisible:Boolean=true,
    sizes:Map<Int,MediaSize> = emptyMap(),audioSize:MediaSize?=null,qualities:Map<Int,Int> = emptyMap(),
    resolvedHeights:Map<Int,Int> = emptyMap()
) {
    val activeQualities = if (resolvedHeights.isNotEmpty()) resolvedHeights else qualities
    Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=0.dp)) {
        Text(stringResource(R.string.share_compact_preference_hint),Modifier.fillMaxWidth().padding(bottom=8.dp),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(audioVisible) ShareFormatRow(audioSelected,enabled,
            stringResource(R.string.share_preference_mp3_title),audioSize?.let {sizeLabel(it)}.orEmpty(),"share_preference_mp3",
            subtitle=stringResource(R.string.share_preference_mp3_description),overviewIcon=FlowIcons.Audio,click=onAudio)
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
                    "share_preference_$height",subtitle=subtitle,overviewIcon=FlowIcons.Video,click={onPreference(height)})
            }
        }
        if(onMore!=null) QuickShareOverviewMore(enabled,false,onMore)
    }
}

@Composable private fun ShareMoreFormats(tag:String,enabled:Boolean,click:()->Unit,expanded:Boolean=false,
    checking:Boolean=false,modifier:Modifier=Modifier) {
    TextButton(onClick=click,enabled=enabled,modifier=modifier.heightIn(min=48.dp).testTag(tag)) {
        Text(stringResource(if(expanded) R.string.share_fewer_sources else R.string.share_more_sources))
        if(checking) {
            Spacer(Modifier.width(8.dp))
            QuickShareCheckingSources()
        }
    }
}

@Composable private fun ShareSection(title:String) {
    Row(Modifier.fillMaxWidth().padding(top=22.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.8f))
    }
}

@Composable private fun shareSizeLabel(selection:Selection):String {
    return selection.outputSize?.let {sizeLabel(it)}.orEmpty()
}

@Composable private fun QuickShareOverviewMore(enabled:Boolean,checking:Boolean,open:()->Unit) {
    QuickShareActionRow(QuickShareAction("share_more",R.string.share_overview_more,FlowIcons.Library,
        if(checking) ActionEnd.Checking else ActionEnd.Navigate,
        stringResource(R.string.share_more_description)),enabled,open)
}

private enum class ActionEnd { Navigate, Expand, Collapse, Checking }
private data class QuickShareAction(
    val tag:String,val label:Int,val icon:ImageVector,val end:ActionEnd=ActionEnd.Navigate,val supportingText:String?=null
)

@Composable private fun QuickShareActionRow(action:QuickShareAction,enabled:Boolean,onClick:()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(10.dp),
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(action.tag).semantics {
            if(action.end==ActionEnd.Expand||action.end==ActionEnd.Collapse) selected=action.end==ActionEnd.Collapse
        },contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) {
        Icon(action.icon,null,Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(action.label),style=MaterialTheme.typography.labelLarge)
            action.supportingText?.let {description->
                val descriptionModifier=if(enabled) Modifier else Modifier.testTag("${action.tag}_disabled_reason")
                Text(description,descriptionModifier,style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(action.end==ActionEnd.Checking) QuickShareCheckingSources()
        else Icon(when(action.end) {
            ActionEnd.Expand->Icons.Outlined.ExpandMore
            ActionEnd.Collapse->Icons.Outlined.ExpandLess
            else->FlowIcons.Next
        },null,Modifier.size(20.dp))
    }
}

@Composable private fun QuickShareCheckingSources() {
    val label=stringResource(R.string.share_checking_sources)
    CircularProgressIndicator(Modifier.size(16.dp).testTag("share_more_checking")
        .semantics {contentDescription=label},strokeWidth=2.dp)
}

@Composable private fun ShareFormatRow(
    selected:Boolean,enabled:Boolean,title:String,size:String,tag:String,subtitle:String="",
    sizePending:Boolean=false,pulse:State<Float>?=null,onDrawn:(()->Unit)?=null,
    overviewIcon:ImageVector?=null,click:()->Unit
) {
    val colors=MaterialTheme.colorScheme
    val config=LocalConfiguration.current
    val stackSize=config.fontScale>=1.35f||config.screenWidthDp<340
    val metrics:@Composable ()->Unit={
        if(sizePending&&pulse!=null) ShareFormatSize(size,true,pulse,tag)
        else if(size.isNotBlank()) Text(size,Modifier.testTag("${tag}_size"),
            style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=if(overviewIcon==null) 48.dp else 56.dp).testTag(tag)
            .then(if(onDrawn!=null&&enabled) Modifier.drawWithContent {
                drawContent()
                onDrawn()
            } else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(if(selected) colors.primaryContainer.copy(alpha=.42f) else Color.Transparent)
             .selectable(selected,interactionSource=remember {MutableInteractionSource()},
                 indication=LocalIndication.current.takeIf {overviewIcon!=null},
                enabled=enabled,role=Role.RadioButton,onClick=click)
            .padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            if(overviewIcon==null) FlowSelectionIndicator(selected,enabled)
            else Icon(overviewIcon,null,Modifier.size(22.dp),tint=if(enabled) colors.onSurface else colors.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(title,Modifier.testTag("${tag}_title"),style=MaterialTheme.typography.titleSmall
                    .copy(textDirection=androidx.compose.ui.text.style.TextDirection.Content),
                    color=if(selected) colors.onPrimaryContainer else colors.onSurface)
                if(subtitle.isNotBlank()) Text(subtitle,Modifier.testTag("${tag}_description"),
                    style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                if(stackSize) metrics()
            }
            if(!stackSize&&(size.isNotBlank()||sizePending)) metrics()
            if(overviewIcon!=null) FlowSelectionIndicator(selected,enabled)
        }
        if(overviewIcon==null) HorizontalDivider(color=colors.outlineVariant.copy(alpha=.36f))
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
    return encodedDimensionsText(format) ?: "${edge}p"
}

/** Isolate encoded width×height so an RTL paragraph cannot visually swap them. */
internal fun encodedDimensionsText(format:Format):String?=format.takeIf {it.dimensionsVerified}?.width?.let {w->format.height?.let {h->"\u2066$w×$h\u2069"}}
