package com.zdownloader.app.domain

import org.json.JSONObject
import java.net.URI

data class PlaylistSizeHint(val bytes:Long,val estimated:Boolean,val video:Boolean,val audio:Boolean,val quality:Int?)
data class PlaylistEntry(val key:String,val url:String?,val position:Int,val title:String?,val thumbnail:String?=null,val duration:Double?=null,val reason:String?=null,val collection:Boolean=false,val sizeHint:PlaylistSizeHint?=null,val mediaId:String?=null,val extractor:String?=null) {
    val selectable get()=url!=null&&reason==null&&!collection
}
data class PlaylistPage(val url:String,val title:String?,val creator:String?,val thumbnail:String?,val total:Int?,val items:List<PlaylistEntry>,val nextOffset:Int,val hasMore:Boolean) {
    companion object {
        fun parse(j:JSONObject,url:String):PlaylistPage {
            if(j.has("error")) throw MediaException(ErrorCode.parse(j.optString("error")))
            val a=j.getJSONArray("items")
            require(a.length()<=100)
            return PlaylistPage(url,j.text("title"),j.text("creator"),j.text("thumbnail"),j.number("total")?.toInt(),
                (0 until a.length()).map {i-> val e=a.getJSONObject(i)
                    val hint=e.optJSONObject("size_hint")?.let {h->h.number("bytes")?.takeIf {it<Long.MAX_VALUE.toDouble()}?.let {bytes->PlaylistSizeHint(bytes.toLong(),h.optBoolean("estimated"),h.optBoolean("video"),h.optBoolean("audio"),h.number("quality")?.toInt())}}
                    PlaylistEntry(e.getString("key"),e.text("url")?.let {runCatching {UrlPolicy.normalize(it)}.getOrNull()},e.getInt("position"),e.text("title"),e.text("thumbnail"),e.number("duration"),e.text("reason"),e.optBoolean("collection"),hint,e.text("id"),e.text("extractor"))
                },j.getInt("next_offset"),j.optBoolean("has_more"))
        }
    }
}
enum class QualityFallback { EXACT, AT_MOST, BEST }
/** Snapshots of retained representation bytes, never cumulative attempt counters. */
data class PlaylistProgress(val bytes:Long,val expected:Long?,val estimated:Boolean,val selected:Int,val completed:Int,val failed:Int,val cancelled:Int,val networkDone:Int) {
    val finished get()=selected>0&&completed+failed+cancelled==selected
    val successful get()=selected>0&&completed==selected
    val fraction:Float? get()=expected?.takeIf {it>0&&it>=bytes}?.let {(bytes.toDouble()/it).coerceIn(0.0,1.0).toFloat()}
    val finalizing get()=!finished&&selected>0&&networkDone==selected
    // 100% is reserved for published success. Bytes can finish earlier.
    val percent:Int? get()=if(successful) 100 else fraction?.takeIf {it<1f}?.let {(it*100).toInt()}
    val indeterminate get()=!finished&&(fraction==null||finalizing||fraction==1f)
}
data class PlaylistPolicy(val audio:Boolean=false,val output:String="original",val height:Int=720,val fallback:QualityFallback=QualityFallback.AT_MOST,val bitrate:Int=192) {
    init { require(height in DownloadRequest.heights);require(output in listOf("original","mp3"));require(bitrate in listOf(128,192,256,320)) }
    fun json()=JSONObject().put("audio",audio).put("output",output).put("height",height).put("fallback",fallback.name).put("bitrate",bitrate).toString()
    fun expected(entry:PlaylistEntry):MediaSize? {
        val hint=entry.sizeHint ?: return null
        if(hint.bytes<=0) return null
        if(audio&&!hint.audio||!audio&&!hint.video) return null
        if(!audio&&fallback!=QualityFallback.BEST&&(hint.quality==null||if(fallback==QualityFallback.EXACT) hint.quality!=height else hint.quality>height)) return null
        return MediaSize(hint.bytes,hint.estimated)
    }
    fun expected(entries:List<PlaylistEntry>):MediaSize? {
        if(entries.isEmpty()) return null
        val sizes=entries.map {expected(it) ?: return null}
        val sum=sizes.sumOf {it.bytes.toDouble()}
        return sum.takeIf {it.isFinite()&&it<Long.MAX_VALUE.toDouble()}?.let {MediaSize(it.toLong(),sizes.any {s->s.estimated})}
    }
    fun resolve(media:Media):Selection {
        return QualityResolver.resolve(media,if(fallback==QualityFallback.BEST) 0 else height,audio,
            if(audio) output else "auto",bitrate,exact=fallback==QualityFallback.EXACT,allowFallback=false)
    }
    companion object {fun parse(value:String):PlaylistPolicy {val j=JSONObject(value);return PlaylistPolicy(j.optBoolean("audio"),j.optString("output","original"),j.optInt("height",720),QualityFallback.valueOf(j.optString("fallback","AT_MOST")),j.optInt("bitrate",192))}}
}
object PlaylistUrls {
    fun ambiguous(url:String):Boolean {
        val u=runCatching {URI(url)}.getOrNull() ?: return false
        val query=u.rawQuery.orEmpty().split('&').map {it.substringBefore('=')}.toSet()
        return "list" in query && ("v" in query||u.host?.endsWith("youtu.be")==true||u.path.startsWith("/shorts/"))
    }
    fun candidate(url:String):Boolean {
        val u=runCatching {URI(url)}.getOrNull() ?: return false
        return u.rawQuery.orEmpty().split('&').any {it.substringBefore('=')=="list"}||Regex("/(playlist|sets|albums?|collections?|showcase)(/|$)",RegexOption.IGNORE_CASE).containsMatchIn(u.path)
    }
}

/** Stable-window adaptive admission; CPU processing remains separately serialized. */
class AdaptiveConcurrency {
    private var limit=2
    private var lastChange=0L
    private var lastSample=0L
    private var penaltyUntil=0L
    private var previousThroughput=0L
    @Synchronized fun failure(code:ErrorCode,now:Long) {if(code in setOf(ErrorCode.RATE_LIMITED,ErrorCode.HTTP_FORBIDDEN,ErrorCode.NETWORK_TIMEOUT,ErrorCode.SERVER_UNAVAILABLE)) {penaltyUntil=now+120_000;limit=1;lastChange=now}}
    @Synchronized fun choose(now:Long,wifi:Boolean,constrained:Boolean,processing:Int,throughput:Long):Int {
        val ceiling=if(constrained||now<penaltyUntil) 1 else if(wifi) 3 else 2
        if(limit>ceiling) {limit=ceiling;lastChange=now}
        if(now-lastChange>=30_000&&now-lastSample>=30_000) {
            lastSample=now
            val stalled=previousThroughput>0&&throughput>0&&throughput<previousThroughput/2
            val target=when {stalled->(limit-1).coerceAtLeast(1);processing>=2->minOf(limit,2);throughput>=256*1024->(limit+1).coerceAtMost(ceiling);else->limit}
            if(target!=limit) {limit=target;lastChange=now}
            previousThroughput=throughput
        }
        return limit.coerceIn(1,ceiling)
    }
}
