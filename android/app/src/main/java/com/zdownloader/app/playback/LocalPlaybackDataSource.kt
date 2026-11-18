package com.zdownloader.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*
import java.io.IOException

/** Media, manifests and subtitles can only read granted content; no HTTP/file fallback. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class LocalPlaybackDataSource(context:Context):DataSource {
    private val delegate=ContentDataSource(context.applicationContext)
    override fun addTransferListener(transferListener:TransferListener)=delegate.addTransferListener(transferListener)
    override fun open(dataSpec:DataSpec):Long {
        if(!LocalPlaybackPolicy.accepts(dataSpec.uri.toString())) throw IOException("Only accessible local media is allowed")
        return try {delegate.open(dataSpec)} catch(_:SecurityException) {throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION)}
        catch(e:ContentDataSource.ContentDataSourceException) {throw DataSourceException(e.reason)}
        catch(_:Exception) {throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)}
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int)=try {delegate.read(buffer,offset,length)} catch(_:Exception) {throw IOException("Local media read failed")}
    override fun getUri():Uri?=delegate.uri
    override fun close() {runCatching {delegate.close()}}
}
