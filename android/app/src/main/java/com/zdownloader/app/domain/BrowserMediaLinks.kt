package com.zdownloader.app.domain

import java.net.URI
import java.util.Locale

/** Browser affordance only; extraction still applies the complete network URL policy. */
object BrowserMediaLinks {
    fun matches(value:String?):Boolean=runCatching {
        if(value.isNullOrBlank()||value.length>UrlPolicy.MAX_URL_LENGTH) return false
        val uri=URI(value)
        if(uri.scheme?.lowercase(Locale.ROOT) !in setOf("http","https")) return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        val path=uri.path.orEmpty().lowercase(Locale.ROOT)
        fun domain(name:String)=host==name||host.endsWith(".$name")
        if(path.substringAfterLast('.',"") in setOf("mp4","m4a","mp3","webm","m3u8","mpd","mov","m4v","aac","ogg","opus","flac","wav")) return true
        when {
            domain("instagram.com")->listOf("/reel/","/reels/","/p/","/share/").any(path::startsWith)
            domain("youtu.be")->path.trim('/').isNotEmpty()
            domain("youtube.com")->path.startsWith("/shorts/")||path.startsWith("/live/")||
                path in setOf("/watch","/playlist")&&uri.rawQuery.orEmpty().split('&').any {it.substringBefore('=') in setOf("v","list")}
            domain("fb.watch")->path.trim('/').isNotEmpty()
            domain("facebook.com")->path.startsWith("/reel/")||path.startsWith("/share/")||path.startsWith("/watch")||path.contains("/videos/")
            domain("tiktok.com")->path.contains("/video/")||path.contains("/photo/")||path.startsWith("/t/")||host in setOf("vm.tiktok.com","vt.tiktok.com")&&path.trim('/').isNotEmpty()
            domain("twitter.com")||domain("x.com")->path.contains("/status/")
            domain("pinterest.com")->path.startsWith("/pin/")
            domain("pin.it")->path.trim('/').isNotEmpty()
            domain("reddit.com")->path.startsWith("/r/")&&path.contains("/comments/")
            domain("vimeo.com")->path.trim('/').matches(Regex("(?:video/)?[0-9]+"))
            domain("dailymotion.com")->path.startsWith("/video/")
            domain("dai.ly")->path.trim('/').isNotEmpty()
            domain("snapchat.com")->path.startsWith("/spotlight/")
            else->false
        }
    }.getOrDefault(false)
}
