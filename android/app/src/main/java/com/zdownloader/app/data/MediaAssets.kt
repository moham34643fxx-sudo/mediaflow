package com.zdownloader.app.data

import android.content.Context
import android.net.Uri
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.awaitResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class ThumbnailData(val bytes:ByteArray,val extension:String) {
    val mime get()=if(extension=="jpg") "image/jpeg" else "image/$extension"
    companion object {
        const val MAX_BYTES=2*1024*1024
        fun parse(data:ByteArray):ThumbnailData {
            require(data.size<=MAX_BYTES)
            val extension=when {
                data.size>=3&&data[0]==0xff.toByte()&&data[1]==0xd8.toByte()&&data[2]==0xff.toByte()->"jpg"
                data.size>=8&&data.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->"png"
                data.size>=12&&String(data,0,4,Charsets.US_ASCII)=="RIFF"&&String(data,8,4,Charsets.US_ASCII)=="WEBP"->"webp"
                else->throw java.io.IOException("Unsupported thumbnail type")
            }
            return ThumbnailData(data,extension)
        }
    }
}

/** Explicit small original-image export. Production uses the shared secure client. */
@Singleton class MediaAssets @Inject constructor(@ApplicationContext private val context:Context) {
    internal suspend fun thumbnail(url:String,client:OkHttpClient=Transfer.networkClient):ThumbnailData=withContext(Dispatchers.IO) {
        val response=awaitResponse(client.newCall(Request.Builder().url(UrlPolicy.normalize(url)).build()))
        response.use {check(it.isSuccessful);val body=checkNotNull(it.body);require(body.contentLength()<=ThumbnailData.MAX_BYTES)
            body.byteStream().use {source->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {ensureActive();val n=source.read(buffer);if(n<0) break;require(out.size()+n<=ThumbnailData.MAX_BYTES);out.write(buffer,0,n)}
                ThumbnailData.parse(out.toByteArray())
            }
        }
    }
    internal suspend fun saveThumbnail(uri:Uri,data:ThumbnailData)=withContext(Dispatchers.IO) {
        require(uri.scheme=="content");ThumbnailData.parse(data.bytes)
        try {
            context.contentResolver.openOutputStream(uri,"w")?.use {it.write(data.bytes);it.flush()} ?: throw java.io.IOException()
            context.contentResolver.openInputStream(uri)?.use {stream->
                val actual=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {ensureActive();val n=stream.read(buffer);if(n<0) break;require(actual.size()+n<=ThumbnailData.MAX_BYTES);actual.write(buffer,0,n)}
                check(data.bytes.contentEquals(actual.toByteArray()))
            } ?: throw java.io.IOException()
        } catch(e:Exception) {withContext(NonCancellable) {runCatching {android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)}};throw e}
    }
}
