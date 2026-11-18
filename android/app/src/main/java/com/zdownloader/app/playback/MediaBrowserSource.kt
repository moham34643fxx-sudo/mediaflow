package com.zdownloader.app.playback

import com.zdownloader.app.data.DownloadEntity

/** An empty download library is still a download library, never a device-query fallback. */
internal sealed interface MediaBrowserSource {
    data object Device : MediaBrowserSource
    data class Downloads(val entries: List<DownloadEntity>) : MediaBrowserSource
}
