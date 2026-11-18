package com.zdownloader.app

/** Public Java-visible callback used by the embedded Python download hook. */
interface ProgressProxy {
    fun progress(downloaded: String, total: String)
    fun confidence(kind:String) {}
    /** Called after a progress hook confirms bytes beyond the retained resume prefix. */
    fun firstNewByte() {}
}
