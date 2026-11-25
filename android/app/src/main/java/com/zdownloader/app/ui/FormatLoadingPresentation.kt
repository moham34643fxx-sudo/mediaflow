package com.zdownloader.app.ui

import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.QuickShareCatalog
import com.zdownloader.app.domain.Selection

/** Presentation state only. Never infer a pending resolution, size, or format ID. */
internal object FormatLoadingPresentation {
    // Re-sort as real quality/size metadata arrives. Lazy keys preserve row/selection
    // identity; an append-only cache would violate numeric ordering for late formats.
    fun sources(state:QuickShareState):List<Format> = QuickShareCatalog.allVideoSources(state.media)

    fun pendingRows(state:QuickShareState,presented:Boolean=state.media!=null):Int =
        if(!presented&&state.sourceUrl!=null&&state.error==null&&(state.discoveringFormats||state.media!=null)) 1 else 0

    fun morePending(state:QuickShareState,showVideo:Boolean):Boolean = state.expanded&&showVideo&&state.pendingDimensionIds.isNotEmpty()

    fun sizePending(state:QuickShareState,selection:Selection,sourceSize:Boolean):Boolean {
        val displayed=if(sourceSize) selection.transferSize else selection.outputSize
        return displayed==null&&(selection.primary.id in state.pendingSizeIds||selection.secondary?.id in state.pendingSizeIds)
    }
}
