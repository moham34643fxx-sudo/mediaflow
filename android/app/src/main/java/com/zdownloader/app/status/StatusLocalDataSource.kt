package com.zdownloader.app.status

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Player sees an opaque media-item URI. Provider exception messages/causes can
 * contain private filenames, so they never reach Media3's production logger. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class StatusLocalDataSource(context:Context,private val actual:Uri):DataSource {
    private val delegate=ContentDataSource(context)
    private var opaque:Uri?=null
    override fun addTransferListener(transferListener:TransferListener) {delegate.addTransferListener(transferListener)}
    override fun open(dataSpec:DataSpec):Long {
        opaque=dataSpec.uri
        return try {delegate.open(dataSpec.buildUpon().setUri(actual).build())} catch(_:Exception) {throw IOException("Local status media unavailable")}
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int=try {delegate.read(buffer,offset,length)} catch(_:Exception) {throw IOException("Local status media unavailable")}
    override fun getUri():Uri?=opaque
    override fun close() {try {delegate.close()} catch(_:Exception) {throw IOException("Local status media unavailable")}}
}
