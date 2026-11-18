package com.zdownloader.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ReleaseQualityIdentityTest {
    @Test fun installedReleaseIdentityAndQaSigningIntentAreExplicit() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=app.packageManager.getPackageInfo(app.packageName,flags)
        val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo!!.apkContentsSigners else info.signatures!!
        val digest=MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()).joinToString("") {"%02x".format(it)}
        val debugCertificate="bc43d1d3a62cc52a692ba2bfbf11823219aaf2cef5c0a68e4499b39aa41d79bc"
        assertEquals("com.zdownloader.app",app.packageName)
        assertEquals(BuildConfig.VERSION_NAME,info.versionName)
        assertEquals(0,app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        if(digest==debugCertificate) assertTrue("A debug-signed Release must carry explicit QA signing intent",BuildConfig.QA_SIGNED_RELEASE)
        if(BuildConfig.QA_SIGNED_RELEASE) assertEquals(debugCertificate,digest)
        val directory=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(directory,"release-identity.json").writeText(JSONObject().put("package",app.packageName).put("version_name",info.versionName)
            .put("version_code",if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()).put("debuggable",false)
            .put("qa_signed_release",BuildConfig.QA_SIGNED_RELEASE).put("certificate_sha256",digest).toString(2))
    }
}
