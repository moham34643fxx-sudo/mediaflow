package com.zdownloader.app.domain

/** Reuse verified preview data for video only. Audio retains its existing submission path. */
internal object VideoPreferenceSelection {
    fun request(height:Int)=DownloadRequest(height=height,exact=false)
    fun ready(media:Media?,height:Int?):Selection? {
        if(media==null||height==null||height !in DownloadRequest.heights) return null
        val catalogSelection = QuickShareCatalog.videoEntries(media).firstOrNull {
            QuickShareCatalog.numericVideoQuality(it.format) == height
        }?.selection?.takeIf { !it.imageOnly && !it.audioOnly && it.primary.video }
        if (catalogSelection != null) return catalogSelection
        return runCatching {request(height).resolve(media)}.getOrNull()?.takeIf {!it.imageOnly&&!it.audioOnly&&it.primary.video}
    }
}
