package com.zdownloader.app.ui

import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.QuickShareCatalog
import com.zdownloader.app.domain.Selection

/** Presentation state only. Never infer a pending resolution, size, or format ID. */
internal object FormatLoadingPresentation {
    // Re-sort as real quality/size metadata arrives. Lazy keys preserve row/selection
    // identity; an append-only cache would violate numeric ordering for late formats.
    fun sources(state:QuickShareState):List<Format> = QuickShareCatalog.allVideoSources(state.media)

    fun pendingRows(state:QuickShareState):Int = when {
        !state.expanded||state.sourceUrl==null||!state.discoveringFormats||state.error!=null -> 0
        state.media==null -> 3
        else -> 1 // More work is pending; its format count/qualities are not known yet.
    }

    fun sizePending(state:QuickShareState,selection:Selection,sourceSize:Boolean):Boolean {
        val displayed=if(sourceSize) selection.transferSize else selection.outputSize
        return displayed==null&&(selection.primary.id in state.pendingSizeIds||selection.secondary?.id in state.pendingSizeIds)
    }
}
