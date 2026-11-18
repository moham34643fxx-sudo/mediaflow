package com.zdownloader.app.data

import java.io.File

/** Application operation IDs are opaque tokens, never filesystem paths. */
internal object OwnedStoragePaths {
    private val operationId=Regex("[A-Za-z0-9_-]{1,128}")
    fun transfer(files:File,id:String):File=child(files,"transfers",id,"")
    fun publication(files:File,id:String):File=child(files,"publications",id,".json")
    private fun child(files:File,bucket:String,id:String,suffix:String):File {
        require(operationId.matches(id)) {"Invalid storage operation id"}
        val owner=files.canonicalFile
        val directory=File(owner,bucket)
        require(directory.canonicalFile==directory) {"Storage directory is an alias"}
        val child=File(directory,id+suffix)
        require(child.canonicalFile==child) {"Storage entry is an alias"}
        return child
    }
}
