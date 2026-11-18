package com.zdownloader.app.domain

import java.net.URI
import org.json.JSONObject

/** Metadata eligibility is not a promise that a remote server will still accept a later GET. */
object FormatAvailability {
    fun expiresAt(payload:String):Long? = runCatching {
        val query=URI(JSONObject(payload).getString("url")).rawQuery.orEmpty()
        query.split('&').mapNotNull { entry ->
            val parts=entry.split('=',limit=2)
            if(parts.size!=2||parts[0].lowercase() !in listOf("expire","expires")) null
            else parts[1].toLongOrNull()?.takeIf {it in 1_000_000_000L..9_999_999_999L}?.times(1000)
        }.minOrNull()
    }.getOrNull()
    fun eligible(format:Format,now:Long=System.currentTimeMillis()):Boolean = format.transportValid &&
        (format.expiresAt?.let {it>now+5_000} ?: true)
    fun unavailableReason(format:Format,now:Long=System.currentTimeMillis()):String? =
        transportRejectionReason(format) ?: if(format.expiresAt?.let {it<=now+5_000}==true) "expired_url" else null
    fun transportValid(format:Format):Boolean = transportRejectionReason(format)==null
    private fun transportRejectionReason(format:Format):String? = runCatching {
        if(format.id.isBlank()||format.id=="null") return@runCatching "missing_id"
        if(format.protocol==SlideshowPlan.PROTOCOL) {
            val plan=SlideshowPlan.parse(format.payload)
            return@runCatching if(format.id=="mediaflow-slideshow"&&format.ext=="mp4"&&format.videoCodec=="h264"&&format.audioCodec=="none"&&format.width==plan.width&&format.height==plan.height) null else "invalid_slideshow"
        }
        if(format.protocol !in supportedMediaProtocols) return@runCatching "unsupported_protocol"
        if(!format.video&&!format.audio&&!format.image) return@runCatching "not_media"
        if(format.image&&(format.protocol!="https"||format.width==null||format.height==null)) return@runCatching "invalid_image_source"
        val data=JSONObject(format.payload)
        if(runCatching {UrlPolicy.normalize(data.getString("url"))}.isFailure) return@runCatching "unsafe_or_invalid_url"
        when {
            data.optBoolean("has_drm")->"drm"
            data.optBoolean("is_live")->"live"
            !data.isNull("request_data")->"request_body_not_supported"
            format.protocol in listOf("http_dash_segments","dash") && data.optJSONArray("fragments")?.length()?.let {it>0}!=true ->"missing_dash_fragments"
            else->null
        }
    }.getOrDefault("invalid_payload")
    fun reusable(media:Media,age:Long,now:Long=System.currentTimeMillis()):Boolean = age in 0..<45_000 &&
        media.formats.all {eligible(it,now+10_000)}
}
