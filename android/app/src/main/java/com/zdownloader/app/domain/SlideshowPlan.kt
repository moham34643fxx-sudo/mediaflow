package com.zdownloader.app.domain

import org.json.JSONObject

/** Strict, bounded generated-video transport; never a remotely supplied FFmpeg command. */
data class SlideshowPlan(val urls:List<String>,val width:Int,val height:Int,val duration:Double,val postId:String) {
    companion object {
        const val PROTOCOL="mediaflow_slideshow"
        fun parse(payload:String):SlideshowPlan {
            val j=JSONObject(payload).getJSONObject("mediaflow_slideshow")
            require(j.getInt("version")==1&&j.getString("timing")=="equal_generated")
            val w=j.getInt("width");val h=j.getInt("height");val d=j.getDouble("duration")
            require(w in 2..1280&&h in 2..1280&&w%2==0&&h%2==0&&d.isFinite()&&d>0&&d<=600)
            val id=j.getString("post_id");require(id.matches(Regex("[0-9]{1,32}")))
            val a=j.getJSONArray("images");require(a.length() in 1..35)
            val urls=(0 until a.length()).map {i->
                val image=a.getJSONObject(i)
                require(image.getInt("width") in 1..16384&&image.getInt("height") in 1..16384)
                UrlPolicy.normalize(image.getString("url"))
            }
            return SlideshowPlan(urls,w,h,d,id)
        }
    }
}
