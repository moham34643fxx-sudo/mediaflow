package com.zdownloader.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.zdownloader.app.ui.FlowFilterChip as FilterChip
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zdownloader.app.ui.spectrumStringResource as stringResource
import com.zdownloader.app.ui.spectrumPluralStringResource as pluralStringResource
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.domain.*

@Composable internal fun sizeLabel(value: MediaSize?): String {
    val context=LocalSpectrumContext.current
    val cache=LocalSizeLabelCache.current
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales.toLanguageTags()
    return remember(context,locale,cache,value) {cache?.label(value) ?: formatSizeLabel(context,value)}
}
internal fun formatSizeLabel(context:android.content.Context,value:MediaSize?):String {
    val text=value?.let { android.text.format.Formatter.formatShortFileSize(context,it.bytes) } ?: return context.getString(R.string.size_unknown)
    return "\u2066${if(value.estimated) "≈ " else ""}$text\u2069"
}
internal fun codecLabel(codec:String?):String = when {
    codec==null || codec=="none" || codec=="unknown"->""
    codec.startsWith("avc") || codec.startsWith("h264")->"H.264"
    codec.startsWith("hev") || codec.startsWith("hvc")->"H.265"
    codec.startsWith("vp09") || codec.startsWith("vp9")->"VP9"
    codec.startsWith("av01")->"AV1"
    codec.startsWith("mp4a")->"AAC"
    else->codec.substringBefore('.').uppercase()
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MediaTypeControl(audio:Boolean,videoEnabled:Boolean,audioEnabled:Boolean,audioTag:String="",videoTag:String="",onAudio:(Boolean)->Unit) {
    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLowest,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))) {
      Row(Modifier.fillMaxWidth().padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        listOf(false,true).forEach { value->
            val selected=audio==value
            val fill=animateColorAsState(if(selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,tween(FlowMotion.Reveal),label="media_mode").value
            Surface(onClick={onAudio(value)},enabled=if(value) audioEnabled else videoEnabled,
                modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics {this.selected=selected}.testTag(
                    if(value) audioTag.ifBlank {"media_type_audio"} else videoTag.ifBlank {"media_type_video"}),
                shape=RoundedCornerShape(14.dp),color=fill,
                contentColor=if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterHorizontally)) {
                    Icon(if(value) FlowIcons.Audio else FlowIcons.Video,null,Modifier.size(20.dp))
                    Text(stringResource(if(value) R.string.audio else R.string.video),style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f,false))
                }
            }
        }
      }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FormatPicker(media:Media,audio:Boolean,primaryId:String?,output:String,videoOutput:String,bitrate:Int,audioSourceId:String?,onAudio:(Boolean)->Unit,onPrimary:(String)->Unit,onOutput:(String)->Unit,onVideoOutput:(String)->Unit,onBitrate:(Int)->Unit,onAudioSource:(String)->Unit,close:()->Unit) {
    val selected=media.formats.firstOrNull { it.id==primaryId }
    val videoGroups=remember(media) {media.qualityGroups()}
    var expandedVideoTierKeys by rememberSaveable(media.url) {mutableStateOf(emptyList<String>())}
    var expandedAudioLanguageKeys by rememberSaveable(media.url) {mutableStateOf(emptyList<String>())}
    val selectedVideoChoice=primaryId?.let {QuickShareChoice(false,it,videoOutput)}
    val videoTierRows=remember(videoGroups,primaryId,videoOutput,expandedVideoTierKeys) {
        QuickShareVideoRows.expanded(videoGroups,selectedVideoChoice,expandedVideoTierKeys.toSet())
    }
    val audioGroups=remember(media,audioSourceId,expandedAudioLanguageKeys) {
        QuickShareAudioRows.grouped(media.audioOptions(),audioSourceId,expandedAudioLanguageKeys.toSet())
    }
    val motion=LocalFlowMotionEnabled.current
    ModalBottomSheet(onDismissRequest=close,shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp),containerColor=MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle={FlowSheetHandle()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).imePadding()) {
            Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.choose_format),style=MaterialTheme.typography.headlineSmall)
                MediaTypeControl(audio,media.videoOptions().isNotEmpty(),media.audioOptions().isNotEmpty(),
                    audioTag="format_audio",videoTag="format_video",onAudio=onAudio)
            }
        LazyColumn(Modifier.weight(1f,false).fillMaxWidth().testTag("format_sheet"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(audio) {
                item { Text(stringResource(R.string.audio_format),style=MaterialTheme.typography.titleMedium);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("original","mp3","m4a","aac","flac","opus").forEach { value ->
                        val size=runCatching {Selection.audio(media,value,media.formats.firstOrNull {it.id==audioSourceId},bitrate).outputSize}.getOrNull()
                        val title=if(value=="original") stringResource(R.string.share_source_copy,Selection.audio(media,"original",media.formats.firstOrNull {it.id==audioSourceId}).output.uppercase()) else value.uppercase()
                        FilterChip(output==value,{onOutput(value)},modifier=Modifier.testTag("audio_output_$value"),label={Text(title+" — "+sizeLabel(size))}) }
                } }
                if(output in listOf("mp3","m4a","aac","opus")) item {
                    Text(stringResource(R.string.audio_quality),style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (if(output=="mp3") listOf(0,128,192,256,320) else listOf(128,192,256)).forEach { value ->
                        val size=runCatching {Selection.audio(media,output,media.formats.firstOrNull {it.id==audioSourceId},value).outputSize}.getOrNull()
                        FilterChip(bitrate==value,{onBitrate(value)},modifier=Modifier.testTag("audio_bitrate_$value"),label={Text((if(value==0) stringResource(R.string.best_source_vbr) else stringResource(R.string.bitrate_value,value))+" — "+sizeLabel(size))}) } }
                }
                item { Text(stringResource(R.string.bitrate_hint),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
                items(audioGroups,key={it.key},contentType={"format-audio-source-group"}) {group->
                    val format=group.format
                    val selection=remember(media,output,bitrate,format.id) {runCatching {Selection.audio(media,output,format,bitrate)}.getOrNull()}
                    val subtitle=if(group.canExpand) pluralStringResource(R.plurals.share_quality_source_count,
                        group.candidates.size,group.candidates.size)
                    else listOfNotNull(format.audioChannels?.let {"${it}ch"},format.sampleRate?.let {"${it}Hz"},format.id).joinToString(" · ")
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        OptionCard(group.selected,{onAudioSource(format.id)},audioSourceLabel(format,LocalSpectrumContext.current.resources.configuration.locales[0].toLanguageTag()),
                            sizeLabel(selection?.outputSize),subtitle,modifier=Modifier.weight(1f),tag=group.formatTag)
                        if(group.canExpand) IconButton(onClick={
                            expandedAudioLanguageKeys=if(group.key in expandedAudioLanguageKeys) expandedAudioLanguageKeys-group.key else expandedAudioLanguageKeys+group.key
                        },modifier=Modifier.testTag(group.formatToggleTag)) {
                            Icon(if(group.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription=stringResource(if(group.expanded) R.string.share_collapse_video_variants else R.string.share_expand_video_variants))
                        }
                    }
                    AnimatedVisibility(
                        visible=group.expanded&&group.canExpand,
                        enter=if(motion) expandVertically(animationSpec=tween(FlowMotion.Reveal))+fadeIn(animationSpec=tween(FlowMotion.Fast)) else EnterTransition.None,
                        exit=if(motion) shrinkVertically(animationSpec=tween(FlowMotion.Fast))+fadeOut(animationSpec=tween(FlowMotion.Fast)) else ExitTransition.None
                    ) {
                        Column(Modifier.fillMaxWidth().padding(start=16.dp)) {
                            QuickShareAudioRows.variants(group,audioSourceId).forEach {variant->
                                val source=variant.format
                                val sourceSelection=remember(media,output,bitrate,source.id) {runCatching {Selection.audio(media,output,source,bitrate)}.getOrNull()}
                                val details=listOfNotNull(source.audioChannels?.let {"${it}ch"},source.sampleRate?.let {"${it}Hz"},source.id).joinToString(" · ")
                                OptionCard(variant.selected,{onAudioSource(source.id)},audioSourceLabel(source,LocalSpectrumContext.current.resources.configuration.locales[0].toLanguageTag()),
                                    sizeLabel(sourceSelection?.outputSize),details,modifier=Modifier.fillMaxWidth().padding(start=8.dp),tag="format_audio_source_${source.id}")
                            }
                        }
                    }
                }
            } else {
                items(videoTierRows,key={it.key},contentType={"format-quality-tier"}) {tier->
                    val tierSelection=remember(media,tier.choice) {runCatching {tier.choice.resolve(media)}.getOrNull()}
                    val tierTag="format_video_quality_${tier.group.resolution ?: "unknown"}"
                    val toggleTag="format_video_quality_toggle_${tier.group.resolution ?: "unknown"}"
                    val subtitle=if(tier.canExpand) pluralStringResource(R.plurals.share_quality_source_count,
                        tier.group.candidates.size,tier.group.candidates.size)
                    else listOfNotNull(codecLabel(tier.format.videoCodec).takeIf {it.isNotBlank()},tier.format.ext.uppercase()).joinToString(" · ")
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OptionCard(tier.selected,{onPrimary(tier.choice.formatId)},qualityTitle(tier.format),
                            sizeLabel(tierSelection?.outputSize),subtitle,modifier=Modifier.weight(1f),tag=tierTag,qualityTier=true)
                        if(tier.canExpand) IconButton(onClick={
                            expandedVideoTierKeys=if(tier.key in expandedVideoTierKeys) expandedVideoTierKeys-tier.key else expandedVideoTierKeys+tier.key
                        },modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag(toggleTag)) {
                            Icon(if(tier.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription=stringResource(if(tier.expanded) R.string.share_collapse_video_variants else R.string.share_expand_video_variants))
                        }
                    }
                    AnimatedVisibility(
                        visible=tier.expanded&&tier.canExpand,
                        enter=if(motion) expandVertically(animationSpec=tween(FlowMotion.Reveal))+fadeIn(animationSpec=tween(FlowMotion.Fast)) else EnterTransition.None,
                        exit=if(motion) shrinkVertically(animationSpec=tween(FlowMotion.Fast))+fadeOut(animationSpec=tween(FlowMotion.Fast)) else ExitTransition.None
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            val variants=QuickShareVideoRows.variants(tier.group,selectedVideoChoice)
                            val chips=variants.mapNotNull {variant->
                                val variantSelection=remember(media,variant.choice) {runCatching {variant.choice.resolve(media)}.getOrNull()}
                                    ?: return@mapNotNull null
                                val format=variant.format
                                val rate=(format.videoBitrate ?: format.bitrate)?.takeIf {it.isFinite()&&it>0}
                                    ?.let {stringResource(R.string.share_video_rate,java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString())}
                                val details=listOfNotNull(encodedDimensionsText(format),fpsLabel(format.fps)?.let {"${it}fps"},
                                    rate,format.dynamicRange?.takeIf {isHdr(format)},format.id,
                                    sizeLabel(variantSelection.outputSize)).joinToString(" · ")
                                VideoVariantChipSpec(format,variant.selected,variant.recommended,details,
                                    "format_video_variant_${format.id}")
                            }
                            VideoVariantChips(chips,enabled=true,recommendedLabel=stringResource(R.string.share_recommended)) {format->
                                onPrimary(format.id)
                            }
                        }
                    }
                }
                selected?.let { f -> item {
                    Text(stringResource(R.string.output_format),style=MaterialTheme.typography.titleMedium)
                    val selection=Selection.video(media,f)
                    val outputs=remember(media,f) {media.videoOutputs(f)}
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { outputs.forEach { container -> FilterChip((videoOutput.ifBlank { selection.output })==container,{onVideoOutput(container)},modifier=Modifier.testTag("format_output_$container"),label={Text(container.uppercase()+" — "+sizeLabel(Selection.video(media,f,container).outputSize))}) } }
                    Text(stringResource(R.string.processing_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
            PrimaryAction(onClick=close,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).testTag("format_confirm")) { Text(stringResource(R.string.use_selection)) }
        }
    }
}
@Composable private fun OptionCard(selected:Boolean,click:()->Unit,title:String,size:String,extra:String="",modifier:Modifier=Modifier,tag:String="",qualityTier:Boolean=false) {
    val fill by animateColorAsState(if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,tween(FlowMotion.Fast),label="formatFill")
    val border by animateColorAsState(if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.6f) else MaterialTheme.colorScheme.outlineVariant,tween(FlowMotion.Fast),label="formatBorder")
    Surface(modifier=modifier,shape=RoundedCornerShape(if(qualityTier) 16.dp else 20.dp),border=BorderStroke(1.dp,border),color=fill) {
        Row(Modifier.fillMaxWidth().heightIn(min=if(qualityTier) 68.dp else 56.dp)
            .selectable(selected,role=Role.RadioButton,onClick=click)
            .padding(if(qualityTier) 14.dp else 12.dp)
            .then(if(tag.isBlank()) Modifier else Modifier.testTag(tag)),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FlowSelectionIndicator(selected)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,
                    fontWeight=if(qualityTier) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
                if(extra.isNotBlank()) Text(extra,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(size,style=MaterialTheme.typography.labelMedium,color=if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
