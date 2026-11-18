package com.zdownloader.app

import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Read-only control for the explicitly protected original; never copies media. */
class ProtectedOriginalReadbackTest {
    @Test fun protectedOriginalIsByteIdenticalAndNoOwnedPendingPublicationRemains() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
        app.contentResolver.openInputStream(Uri.parse("content://media/external_primary/file/97218"))!!.use {source->
            val buffer=ByteArray(64*1024)
            while(true) {val count=source.read(buffer);if(count<0) break;bytes+=count;digest.update(buffer,0,count)}
        }
        val sha=digest.digest().joinToString("") {"%02x".format(it)}
        assertEquals(327950051L,bytes)
        assertEquals("f8efa4744c70482b7f2f550ab353f70ba83105c2fd7651098b55a419017dd7cd",sha)
        @Suppress("DEPRECATION")
        val collection=MediaStore.setIncludePending(MediaStore.Files.getContentUri("external"))
        val pending=app.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(app.packageName,".%.pending%"),null)!!.use {it.count}
        assertEquals("An app-owned pending publication was left behind",0,pending)
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "protected-original-readback"
        require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        val home=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {check(isDirectory||mkdirs())}
        File(home,"protected-original.json").writeText(JSONObject().put("passed",true).put("bytes",bytes).put("sha256",sha)
            .put("owned_pending",pending).put("host_media_copy",false).toString(2))
    }
}
