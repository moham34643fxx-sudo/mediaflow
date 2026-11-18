package com.zdownloader.app.domain

import java.security.MessageDigest
import java.util.Locale

/** Stable extractor identity across share aliases; generic URLs keep their full path/query. */
object RequestFingerprint {
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    private fun selectionKey(selection:Selection,legacy:Boolean=false):String {
        val bitrate=if(legacy||selection.audioOnly&&selection.convertAudio&&selection.output!="flac") selection.bitrate else 0
        val base=listOf(selection.primary.id,selection.secondary?.id,selection.audioOnly,selection.output,bitrate,selection.convertAudio).joinToString("|")
        if(legacy) return base // Historical keys predate the optional advanced fields.
        return if(!selection.videoOnly&&selection.subtitle==null&&selection.embedMetadata&&selection.creator==null) base else base+"|advanced:"+listOf(selection.videoOnly,selection.subtitle?.language,selection.embedMetadata,selection.creator).joinToString("|")
    }
    fun legacy(url:String,selection:Selection)=digest("$url|${selectionKey(selection,legacy=true)}")
    fun create(media:Media,selection:Selection):String {
        val source=media.source.lowercase(Locale.ROOT)
        val known=media.id.isNotBlank()&&media.id!="null"&&source !in setOf("generic","media","direct")&&!source.contains('.')
        val identity=if(known) "extractor:$source:${media.id}" else "url:${media.url}"
        return "v2:${digest(identity)}:${digest(selectionKey(selection))}"
    }
    fun alternative(current:String,url:String,selection:Selection):String {
        return if(current.matches(Regex("v2:[0-9a-f]{64}:[0-9a-f]{64}"))) current.substringBeforeLast(':')+":"+digest(selectionKey(selection)) else legacy(url,selection)
    }
}
