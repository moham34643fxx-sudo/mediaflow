package com.zdownloader.app.domain

import org.json.JSONObject

data class SubtitleTrack(val language:String,val extension:String,val payload:String) {
    init {require(language.matches(Regex("[a-zA-Z0-9_-]{1,40}")));require(extension in setOf("srt","vtt"))}
    fun json()=JSONObject().put("language",language).put("extension",extension).put("payload",JSONObject(payload))
    companion object {fun parse(j:JSONObject):SubtitleTrack {
        val payload=j.getJSONObject("payload");UrlPolicy.normalize(payload.getString("url"))
        require(payload.toString().toByteArray().size<=16384)
        return SubtitleTrack(j.getString("language"),j.getString("extension"),payload.toString())
    }}
}
