package com.zdownloader.app.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** External opens use the same local-only player. No network or arbitrary private-file bridge. */
internal object ExternalAudioOpen {
    val mimeTypes=setOf("audio/mpeg","audio/mp3","audio/x-mpeg","audio/mp4","audio/x-m4a","audio/aac","audio/aacp",
        "audio/flac","audio/x-flac","audio/wav","audio/x-wav","audio/vnd.wave","audio/ogg","application/ogg","audio/opus","audio/vorbis","audio/x-matroska")
    fun acceptsMime(value:String?)=value?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) in mimeTypes
    fun acceptsIntent(context:Context,intent:Intent):Boolean {
        if(intent.action!=Intent.ACTION_VIEW) return false
        return acceptsMime(ExternalMediaOpen.mimeType(context,intent))
    }
    fun contentUri(context:Context,intent:Intent):Uri=ExternalMediaOpen.contentUri(context,intent,audioOnly=true)
}

internal object ExternalMediaOpen {
    private val videoTypes=setOf("video/mp4","video/webm","video/x-matroska","video/3gpp")
    fun videoMime(value:String?)=value?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) in videoTypes
    fun acceptsMime(value:String?)=ExternalAudioOpen.acceptsMime(value)||videoMime(value)
    fun mimeType(context:Context,intent:Intent):String? {
        val supplied=intent.type?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if(acceptsMime(supplied)) return supplied
        return runCatching {intent.data?.let(context.contentResolver::getType)}
            .getOrNull()?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) ?: supplied
    }
    fun contentUri(context:Context,intent:Intent,audioOnly:Boolean=false):Uri {
        require(intent.action==Intent.ACTION_VIEW)
        val uri=requireNotNull(intent.data)
        val mime=mimeType(context,intent)
        require(if(audioOnly) ExternalAudioOpen.acceptsMime(mime) else acceptsMime(mime))
        if(uri.scheme=="content") {
            require(LocalPlaybackPolicy.accepts(uri.toString()))
            return uri
        }
        require(uri.scheme=="file"&&uri.authority.isNullOrBlank()&&uri.fragment==null)
        val file=File(requireNotNull(uri.path)).canonicalFile
        val external=Environment.getExternalStorageDirectory().canonicalFile
        require(file.path.startsWith(external.path+File.separator)&&file.isFile&&file.canRead())
        // Legacy file:// requests can only bridge to an already indexed, readable item.
        // Never expose the app's own private files through a FileProvider or broaden permissions.
        val collection=if(videoMime(mime)) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.Audio.Media.DATA}=?",arrayOf(file.path),null)?.use {
            if(it.moveToFirst()) return android.content.ContentUris.withAppendedId(collection,it.getLong(0))
        }
        throw java.io.FileNotFoundException()
    }
}
