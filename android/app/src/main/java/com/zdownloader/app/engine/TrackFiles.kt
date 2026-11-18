package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import java.io.File

/** Invalidate only one proven-bad application-owned track, including its resume cache. */
internal object TrackFiles {
    fun invalidate(track:File,directory:File) {
        val root=directory.canonicalFile
        require(track.canonicalFile.parentFile==root) {"Track must be inside the owned transfer directory"}
        directory.listFiles().orEmpty().filter {it.name==track.name||it.name.startsWith(track.name+".")}.forEach {file->
            require(file.canonicalFile.parentFile==root&&!file.isDirectory) {"Unexpected track-cache ownership"}
            if(file.exists()&&!file.delete()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
        }
    }
}
