package com.zdownloader.app.ui

import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.QuickShareChoice

/** A display dimension, not an extractor tier or a download preference. */
@ConsistentCopyVisibility
internal data class QuickShareDimensions private constructor(val width:Int?,val height:Int?) {
    val edge:Int? get()=height?.let {h->width?.let {minOf(it,h)} ?: h}
    val pixels:Long get()=(width?.toLong() ?: 0L)*(height ?: 0)
    val key:String get()=if(width!=null) "${width}x$height" else height?.let {"height-$it"} ?: "unknown"

    companion object {
        fun from(format:Format):QuickShareDimensions {
            val height=format.height?.takeIf {format.dimensionsVerified&&it in 1..32768}
            val width=format.width?.takeIf {height!=null&&it in 1..32768}
            return QuickShareDimensions(width,height)
        }
    }
}

internal data class QuickShareQualityGroup(
    val dimensions:QuickShareDimensions,val candidates:List<Format>
) {
    val key:String get()="share-quality:${dimensions.key}"
    val tag:String get()="share_video_quality_${dimensions.key}"
    val toggleTag:String get()="share_video_quality_toggle_${dimensions.key}"
    val recommended:Format get()=candidates.first()

    fun selectedChoice(choice:QuickShareChoice?):QuickShareChoice? =
        choice?.takeUnless {it.audio}?.takeIf {selected->candidates.any {it.id==selected.formatId}}
}

/** Candidates arrive in engine recommendation order; this only groups and orders their display. */
internal fun quickShareQualityGroups(sources:List<Format>):List<QuickShareQualityGroup> {
    val grouped=sources.distinctBy {it.id}.groupBy(QuickShareDimensions::from)
    return grouped.entries.sortedWith(compareBy<Map.Entry<QuickShareDimensions,List<Format>>> {
        it.key.edge ?: Int.MAX_VALUE
    }.thenBy {it.key.pixels}.thenBy {it.key.width}.thenBy {it.key.height}).map {(dimensions,candidates)->
        QuickShareQualityGroup(dimensions,candidates)
    }
}
