package com.zdownloader.app.domain

import org.json.JSONObject

/** User intent, not a representation. Safe to persist before any network/engine work. */
data class DownloadRequest(
    val height:Int=0,val audioOnly:Boolean=false,val output:String=if(audioOnly) "original" else "auto",
    val bitrate:Int=192,val template:String="{title}",val retries:Int=2,val wifiOnly:Boolean=false,
    val traceId:String="",val confirmedAt:Long=System.currentTimeMillis(),
    val exact:Boolean=false
) {
    init {
        require(height in heights)
        require(if(audioOnly) AudioOutputFormats.supports(output) else output in listOf("auto","mp4","webm","mkv"))
        require(bitrate in listOf(128,192,256,320)&&retries in 0..5&&template.length<=4096)
    }
    fun json():String=JSONObject().put("version",1).put("height",height).put("audio",audioOnly)
        .put("output",output).put("bitrate",bitrate).put("template",template).put("retries",retries)
        .put("wifiOnly",wifiOnly).put("traceId",traceId).put("confirmedAt",confirmedAt).put("exact",exact).toString()
    fun resolve(media:Media,capabilities:MediaIntelligence.CodecCapabilities=MediaIntelligence.platformCapabilities):Selection =
        QualityResolver.resolve(media,height,audioOnly,output,bitrate,exact=exact,allowFallback=!exact,capabilities=capabilities)
    companion object {
        val heights=listOf(0,144,240,360,480,720,1080,1440,2160)
        fun parse(value:String):DownloadRequest {
            val j=JSONObject(value)
            require(j.getInt("version")==1)
            return DownloadRequest(j.getInt("height"),j.getBoolean("audio"),j.getString("output"),j.getInt("bitrate"),
                j.getString("template"),j.getInt("retries"),j.getBoolean("wifiOnly"),j.optString("traceId"),j.getLong("confirmedAt"),
                j.optBoolean("exact",false))
        }
    }
}

/** Shared across extractors and persisted single/batch intents. A cap never permits a higher or unknown resolution. */
object QualityResolver {
    fun resolve(media:Media,height:Int,audio:Boolean=false,output:String=if(audio) "original" else "auto",bitrate:Int=192,
                exact:Boolean=false,allowFallback:Boolean=true,capabilities:MediaIntelligence.CodecCapabilities=MediaIntelligence.platformCapabilities):Selection {
        val usable=QuickShareFormats.usable(media)
        if(audio) return Selection.audio(usable,output,bitrate=bitrate)
        val allGroups=usable.qualityGroups(capabilities)
        val candidates=allGroups.filter {group->
            if(height==0) true else group.resolution?.let {if(exact) it==height else it<=height}==true
        }
        for(group in candidates) for(format in group.candidates) {
            val selected=runCatching {Selection.video(usable,format,output.takeUnless {it=="auto"})}.getOrNull() ?: continue
            return selected
        }
        throw MediaException(if(!exact&&height>0&&candidates.isEmpty()) ErrorCode.QUALITY_UNAVAILABLE else ErrorCode.FORMAT_UNAVAILABLE)
    }
}
