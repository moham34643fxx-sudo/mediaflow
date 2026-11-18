package com.zdownloader.app.domain

import org.json.JSONObject

/** Missing tracks/items cannot be silently counted as zero. */
fun sumMediaSizes(sizes:List<MediaSize?>):MediaSize? {
    if(sizes.isEmpty()) return null
    var bytes=0L;var estimated=false
    for(size in sizes) {
        if(size==null||size.bytes<0||size.bytes>Long.MAX_VALUE-bytes) return null
        bytes+=size.bytes;estimated=estimated||size.estimated
    }
    return MediaSize(bytes,estimated)
}

data class SizeCoverage(val known:MediaSize?,val measured:Int,val count:Int) {
    val total get()=known?.takeIf {measured==count&&count>0}
    companion object {
        fun of(sizes:List<MediaSize?>)=SizeCoverage(sumMediaSizes(sizes.filterNotNull()),sizes.count {it!=null},sizes.size)
    }
}

data class SizeForecast(val output:MediaSize?,val transfer:MediaSize?,val quality:Int?,val error:ErrorCode?=null) {
    fun json():JSONObject=JSONObject().put("output",output?.bytes).put("outputEstimated",output?.estimated)
        .put("transfer",transfer?.bytes).put("transferEstimated",transfer?.estimated).put("quality",quality).put("error",error?.name)
    companion object {
        fun from(media:Media,policy:PlaylistPolicy):SizeForecast=try {
            val selection=policy.resolve(media)
            SizeForecast(selection.outputSize,selection.transferSize,qualityLabel(selection.primary))
        } catch(e:MediaException) {SizeForecast(null,null,null,e.code)}
        fun parse(j:JSONObject)=SizeForecast(
            if(j.isNull("output")) null else MediaSize(j.getLong("output"),j.optBoolean("outputEstimated",true)),
            if(j.isNull("transfer")) null else MediaSize(j.getLong("transfer"),j.optBoolean("transferEstimated",true)),
            j.number("quality")?.toInt(),j.text("error")?.let(ErrorCode::parse))
    }
}

val PlaylistPolicy.sizeKey:String get()=if(audio) "audio:$output:${if(output=="original") 0 else bitrate}"
    else "video:${if(fallback==QualityFallback.BEST) 0 else height}:$fallback"

/** Output prediction from an actual playlist duration, never a resolution-to-size table. */
fun PlaylistPolicy.durationOutput(duration:Double?):MediaSize? =
    if(audio&&output=="mp3") estimateBytes(duration,bitrate.toDouble())?.let {MediaSize(it,true)} else null

/** All supported playlist policies, so changing quality need not re-extract every entry. */
fun playlistSizeCatalog(media:Media):Map<String,SizeForecast> {
    val policies=listOf(PlaylistPolicy(fallback=QualityFallback.BEST),PlaylistPolicy(audio=true))+
        listOf(360,480,720,1080).flatMap {height->listOf(QualityFallback.EXACT,QualityFallback.AT_MOST).map {PlaylistPolicy(height=height,fallback=it)}}+
        listOf(128,192,256,320).map {PlaylistPolicy(audio=true,output="mp3",bitrate=it)}
    return policies.associate {it.sizeKey to SizeForecast.from(media,it)}
}
