package com.zdownloader.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Bounded sampled cover cache, shared by retriever and Media3 metadata. No media-file writes. */
internal object EmbeddedArtwork {
    fun safeUri(context:Context,value:String?):String? = value?.takeIf {runCatching {
        val uri=Uri.parse(it)
        when(uri.scheme) {
            "content" -> LocalPlaybackPolicy.accepts(it)
            "file" -> File(requireNotNull(uri.path)).canonicalPath.startsWith(File(context.cacheDir,"playback-art").canonicalPath+File.separator)
            else -> false
        }
    }.getOrDefault(false)}
    const val MAX_ENCODED_BYTES=4*1024*1024
    private fun boundedBitmap(bytes:ByteArray):Bitmap? {
        if(bytes.isEmpty()||bytes.size>MAX_ENCODED_BYTES) return null
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>32768||bounds.outHeight>32768) return null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>640) sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
    }
    /** Normalize a private temporary download cover without evicting player artwork. */
    fun normalizeTemporaryCover(bytes:ByteArray,target:File):Boolean=runCatching {
        val bitmap=boundedBitmap(bytes) ?: return false
        try {target.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}} finally {bitmap.recycle()}
    }.getOrDefault(false)
    @Synchronized fun cache(context:Context,bytes:ByteArray?):String? = runCatching {
        if(bytes==null||bytes.isEmpty()||bytes.size>MAX_ENCODED_BYTES) return null
        val directory=File(context.cacheDir,"playback-art").apply {mkdirs()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        val target=File(directory,"$hash.jpg")
        if(!target.isFile) {
            val bitmap=boundedBitmap(bytes) ?: return null
            val temporary=File.createTempFile("cover-",".pending",directory)
            try {
                temporary.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))}
                check(temporary.renameTo(target))
            } finally {bitmap.recycle();temporary.delete()}
        }
        target.setLastModified(System.currentTimeMillis())
        directory.listFiles()?.filter {it.extension=="jpg"}?.sortedByDescending {it.lastModified()}?.drop(32)?.forEach {it.delete()}
        Uri.fromFile(target).toString()
    }.getOrNull()
}
