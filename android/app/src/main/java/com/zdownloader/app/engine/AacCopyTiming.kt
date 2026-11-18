package com.zdownloader.app.engine

import org.json.JSONObject

internal class AacCopyTiming private constructor(private val endTicks:Long,private val timeBase:String) {
    // The demuxer can expand a short terminal AAC sample to a whole codec frame.
    // Preserve all timestamps/payloads; only constrain that final packet to its declared end.
    val bitstreamFilter="setts=pts=PTS:dts=DTS:duration='if(eq(NEXT_PTS,NOPTS),min(DURATION,$endTicks-PTS),DURATION)'"
    val movieTimescale:String get()=timeBase.substringAfter('/')
    fun json():JSONObject=JSONObject().put("endTicks",endTicks).put("timeBase",timeBase)

    fun matches(stream:JSONObject):Boolean = stream.optString("codec_name")=="aac"&&
        stream.optString("sample_rate")==timeBase.substringAfter('/')&&
        stream.optString("time_base")==timeBase&&stream.optString("start_pts")=="0"&&
        stream.optString("duration_ts").toLongOrNull()==endTicks

    companion object {
        fun fromContract(contract:JSONObject):AacCopyTiming? {
            val timeBase=contract.optString("timeBase")
            val rate=timeBase.removePrefix("1/").toIntOrNull()?.takeIf {it>0} ?: return null
            if(timeBase!="1/$rate") return null
            val end=contract.optString("endTicks").toLongOrNull()?.takeIf {it in 1..9007199254740991L} ?: return null
            return AacCopyTiming(end,timeBase)
        }
        fun fromStream(stream:JSONObject):AacCopyTiming? {
            if(stream.optString("codec_name")!="aac"||stream.optString("start_pts")!="0") return null
            val rate=stream.optString("sample_rate").toIntOrNull()?.takeIf {it>0} ?: return null
            val timeBase=stream.optString("time_base").takeIf {it=="1/$rate"} ?: return null
            // FFmpeg expressions use doubles; larger integer endpoints are not exact.
            val end=stream.optString("duration_ts").toLongOrNull()?.takeIf {it in 1..9007199254740991L} ?: return null
            return AacCopyTiming(end,timeBase)
        }
    }
}
