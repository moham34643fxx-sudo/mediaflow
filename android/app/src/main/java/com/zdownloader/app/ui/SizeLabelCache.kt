package com.zdownloader.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.zdownloader.app.domain.MediaSize
import java.util.concurrent.ConcurrentHashMap

/** Per-sheet, per-locale immutable labels; no global Context or unbounded source cache. */
internal class SizeLabelCache(private val format:(MediaSize?)->String,private val capacity:Int=256) {
    private data class Key(val bytes:Long?,val estimated:Boolean)
    private val labels=ConcurrentHashMap<Key,String>()
    val entryCount:Int get()=labels.size
    fun label(value:MediaSize?):String {
        val key=Key(value?.bytes,value?.estimated ?: false)
        labels[key]?.let {return it}
        val text=format(value)
        // Formatting happens outside a lock: background preparation cannot block
        // a Main-thread cache hit behind ICU/NumberFormat initialization.
        if(labels.size>=capacity) return text
        return labels.putIfAbsent(key,text) ?: text
    }
}

internal val LocalSizeLabelCache=staticCompositionLocalOf<SizeLabelCache?> {null}
