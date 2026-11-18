package com.zdownloader.app.status

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.zdownloader.app.R
import java.io.FileNotFoundException

/** Read-only URIs for already-readable media; grants no storage access itself. */
class StatusFileProvider:FileProvider(R.xml.status_source_paths) {
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only status source")
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor? {
        if(mode!="r") throw FileNotFoundException("Read-only status source")
        return super.openFile(uri,mode)
    }
}
