package com.zdownloader.app

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID

/** Test-APK provider only; cannot address a user-selected or production tree. */
internal class DisposableDocuments:AutoCloseable {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    val resolver=context.contentResolver
    private val authority=instrument.context.packageName+".documents"
    private val root=DocumentsContract.buildTreeDocumentUri(authority,"root")
    private val owned=mutableListOf<Uri>()
    val home:Uri
    init {
        check(context.packageName=="com.zdownloader.app.candidate0965")
        val grant=ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
            "am start -W -n ${instrument.context.packageName}/${TestDocumentsGrantActivity::class.java.name}"))
            .bufferedReader().use {it.readText()}
        check(!grant.contains("Error")) {grant}
        home=create(DocumentsContract.buildDocumentUriUsingTree(root,"root"),"mf-remediation-${UUID.randomUUID()}",directory=true)
    }
    fun create(parent:Uri,name:String,directory:Boolean=false):Uri=DocumentsContract.createDocument(resolver,parent,
        if(directory) DocumentsContract.Document.MIME_TYPE_DIR else "audio/mp4",name)!!.also {owned+=it}
    fun tree(document:Uri):Uri {
        val id=DocumentsContract.getDocumentId(document)
        resolver.call(root,"grantTree",id,null)
        return DocumentsContract.buildTreeDocumentUri(authority,id)
    }
    fun remember(uri:Uri) {owned+=uri}
    fun mode(stable:Boolean=false,refuse:Boolean=false,failMetadata:Boolean=false) {
        resolver.call(root,"mutationMode",null,Bundle().apply {putBoolean("stable",stable);putBoolean("refuse",refuse);putBoolean("failMetadata",failMetadata)})
    }
    override fun close() {
        mode()
        owned.asReversed().distinct().forEach {runCatching {DocumentsContract.deleteDocument(resolver,it)}}
    }
}
