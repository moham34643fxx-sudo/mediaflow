package com.zdownloader.app.domain

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Source identity and representation identity are intentionally separate. */
object MediaIdentity {
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    fun url(value:String):String {
        val u=URI(value.trim().substringBefore('#'))
        require(u.scheme.equals("https",true)&&u.host!=null&&u.userInfo==null)
        val host=u.host.lowercase(Locale.ROOT)
        var path=u.rawPath.orEmpty().ifBlank {"/"}
        if(host in setOf("instagram.com","www.instagram.com")) {
            path=path.replace("/share/reel/","/reel/")
                .replace("/share/p/","/p/")
                .replace("/share/r/","/reel/")
                .replace("/reels/","/reel/")
        }
        val page=when(host) {
            "youtube.com","www.youtube.com","m.youtube.com"->path in setOf("/watch","/playlist")||Regex("^/(shorts|embed)/[a-zA-Z0-9_-]+/?$").matches(path)
            "youtu.be"->Regex("^/[a-zA-Z0-9_-]+/?$").matches(path)
            "vimeo.com","www.vimeo.com"->Regex("^/[0-9]+/?$").matches(path)
            "instagram.com","www.instagram.com"->Regex("^/(reel|p|tv)/[a-zA-Z0-9_-]+/?$").matches(path)
            "facebook.com","www.facebook.com","m.facebook.com","web.facebook.com","fb.watch"->true
            "tiktok.com","www.tiktok.com","vm.tiktok.com","vt.tiktok.com"->true
            "twitter.com","www.twitter.com","x.com","www.x.com"->true
            "reddit.com","www.reddit.com","v.redd.it","redd.it"->true
            else->false
        }
        val signed=u.rawQuery.orEmpty().split('&').any {part->part.substringBefore('=').lowercase(Locale.ROOT).let {it in setOf("sig","lsig","signature","token","expire","expires","policy","key","key-pair-id","hmac","auth")||it.startsWith("x-amz-")||it.startsWith("x-goog-")}}
        val trackingParams=setOf("utm_source","utm_medium","utm_campaign","utm_term","utm_content","fbclid","gclid","si","feature","igsh","ig_mid","mibextid","ref","s","t","ref_src","_r","_t","is_from_webapp","sender_device")
        val query=u.rawQuery?.split('&')?.filterNot {page&&!signed&&it.substringBefore('=').lowercase(Locale.ROOT).let {key->key.startsWith("utm_")||key in trackingParams}}?.joinToString("&")?.takeIf {it.isNotEmpty()}
        return "https://"+host+(if(u.port !in setOf(-1,443)) ":${u.port}" else "")+path+(query?.let {"?$it"} ?: "")
    }
    fun urlKey(value:String)=digest(runCatching {url(value)}.getOrDefault(value))
    fun sourceKey(media:Media):String {
        val provider=media.source.lowercase(Locale.ROOT)
        val stable=media.id.isNotBlank()&&media.id!="null"&&provider !in setOf("generic","media","direct")&&!provider.contains('.')
        return if(stable) "v2:"+digest("extractor:$provider:${media.id}") else "url:"+urlKey(media.url)
    }
    fun playlistKey(entry:PlaylistEntry):String=if(!entry.mediaId.isNullOrBlank()&&!entry.extractor.isNullOrBlank())
        sourceKey(Media(entry.url.orEmpty(),entry.mediaId,"",entry.extractor,null,null,null,emptyList())) else "url:"+urlKey(entry.url.orEmpty())
}
