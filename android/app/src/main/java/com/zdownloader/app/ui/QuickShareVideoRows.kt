package com.zdownloader.app.ui

import com.zdownloader.app.domain.*

/** One source identity across compact/expanded layouts; the action still differs. */
internal data class QuickShareVideoRow(
    val format:Format,val choice:QuickShareChoice,val selected:Boolean,
    val expanded:Boolean,val resolution:Int?=null,val recommended:Boolean=false
) {
    val key:String get()="video-source:${format.id}"
    // Compact rows are verified-pixel caps, never compression-label preferences.
    val preferredHeight:Int? get()=if(expanded) null else resolution?.takeIf {it in DownloadRequest.heights}
    val tag:String get()=if(expanded) "share_video_variant_${format.id}" else "share_video_${resolution ?: "source"}"
}

/** One visible row per real quality tier; variants are children of that tier. */
internal data class QuickShareVideoTierRow(
    val group:QualityGroup,val choice:QuickShareChoice,val selected:Boolean,val expanded:Boolean
) {
    val key:String get()=QuickShareVideoRows.tierKey(group.resolution)
    val tag:String get()=QuickShareVideoRows.tierTag(group.resolution)
    val toggleTag:String get()=QuickShareVideoRows.toggleTag(group.resolution)
    val format:Format get()=group.candidates.firstOrNull {it.id==choice.formatId} ?: group.recommended
    val canExpand:Boolean get()=group.candidates.size>1
}

internal object QuickShareVideoRows {
    fun compact(groups:List<QualityGroup>,effectiveChoice:QuickShareChoice?):List<QuickShareVideoRow> = groups.map {group->
        val active=group.candidates.firstOrNull {it.id==effectiveChoice?.takeUnless {it.audio}?.formatId}
        val format=active ?: group.recommended
        QuickShareVideoRow(format,if(active!=null) requireNotNull(effectiveChoice) else QuickShareChoice(false,format.id),
            active!=null,false,group.resolution,format.id==group.recommended.id)
    }

    fun expanded(groups:List<QualityGroup>,effectiveChoice:QuickShareChoice?,expandedKeys:Set<String>):List<QuickShareVideoTierRow> {
        val selectedId=effectiveChoice?.takeUnless {it.audio}?.formatId
        return groups.sortedWith(compareBy<QualityGroup> {it.resolution ?: Int.MAX_VALUE}.thenBy {it.recommended.ext!="mp4"})
            .map {group->
                val selected=group.candidates.any {it.id==selectedId}
                val choice=if(selected) requireNotNull(effectiveChoice) else QuickShareChoice(false,group.recommended.id)
                QuickShareVideoTierRow(group,choice,selected,tierKey(group.resolution) in expandedKeys)
            }
    }

    fun variants(group:QualityGroup,effectiveChoice:QuickShareChoice?):List<QuickShareVideoRow> {
        val selectedId=effectiveChoice?.takeUnless {it.audio}?.formatId
        return group.candidates.map {format->
            val selected=selectedId==format.id
            QuickShareVideoRow(format,if(selected) requireNotNull(effectiveChoice) else QuickShareChoice(false,format.id),
                selected,true,group.resolution,format.id==group.recommended.id)
        }
    }

    fun tierKey(resolution:Int?)="video-quality:${resolution ?: "unknown"}"
    fun tierTag(resolution:Int?)="share_video_quality_${resolution ?: "unknown"}"
    fun toggleTag(resolution:Int?)="share_video_quality_toggle_${resolution ?: "unknown"}"
}

internal data class QuickShareVisibleSections(val video:Boolean,val audio:Boolean,val modeControl:Boolean)

/** Expanded Quick Share is a video OR audio-only picker, never a combined source list. */
internal object QuickShareFormatSections {
    fun resolve(expanded:Boolean,section:String,hasVideo:Boolean,hasAudio:Boolean,selectedAudio:Boolean):QuickShareVisibleSections {
        if(!expanded) return QuickShareVisibleSections(video=true,audio=true,modeControl=false)
        val audioMode=section=="audio" || (section=="all"&&selectedAudio) || (!hasVideo&&hasAudio)
        return QuickShareVisibleSections(video=!audioMode,audio=audioMode,modeControl=hasVideo&&hasAudio)
    }
}
