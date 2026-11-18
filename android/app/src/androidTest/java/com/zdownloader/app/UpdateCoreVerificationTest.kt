package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.updates.InstalledUpdateIdentity
import com.zdownloader.app.updates.UpdateApkVerifier
import com.zdownloader.app.updates.UpdateAsset
import com.zdownloader.app.updates.UpdateError
import com.zdownloader.app.updates.UpdateException
import com.zdownloader.app.updates.UpdateRelease
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Uses the real installed, signed full APK as a fixture. Does not install, stage, or uninstall anything. */
class UpdateCoreVerificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualSignedApkRequiresChecksumExactPackageVersionAndCurrentSignerSet() {
        val file = File(context.applicationInfo.sourceDir)
        val current = UpdateApkVerifier.installedIdentity(context)
        val asset = fixtureAsset(file)
        val release = fixtureRelease(current, asset)
        val older = current.copy(versionCode = current.versionCode - 1)
        val verifier = UpdateApkVerifier(context, { older })
        verifier.verify(file, release, asset)
        rejects(UpdateError.CHECKSUM) { verifier.verify(file, release, asset.copy(sha256 = "00".repeat(32))) }
        rejects(UpdateError.SIZE) { verifier.verify(file, release, asset.copy(bytes = asset.bytes + 1)) }
        rejects(UpdateError.PACKAGE) { verifier.verify(file, release.copy(appId = "other.package"), asset) }
        rejects(UpdateError.VERSION) { verifier.verify(file, release.copy(versionCode = release.versionCode + 1), asset) }
        rejects(UpdateError.VERSION) { UpdateApkVerifier(context, { current }).verify(file, release, asset) }
        rejects(UpdateError.SIGNATURE) {
            UpdateApkVerifier(context, { older.copy(signerSha256 = setOf("00".repeat(32))) }).verify(file, release, asset)
        }
        rejects(UpdateError.INCOMPATIBLE_SDK) { verifier.verify(file, release.copy(minSdk = release.minSdk + 1), asset) }
        rejects(UpdateError.INCOMPATIBLE_ABI) {
            UpdateApkVerifier(context, { older }, abis = listOf("nonexistent-abi")).verify(file, release, asset)
        }
    }

    private fun fixtureAsset(file: File): UpdateAsset {
        val abis = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
        val abi = if (abis.containsAll(setOf("arm64-v8a", "x86_64"))) "universal" else abis.single()
        return UpdateAsset(abi, "https://github.com/owner/repo/releases/download/qa-fixture/app.apk", UpdateApkVerifier.sha256(file), file.length())
    }

    private fun fixtureRelease(identity: InstalledUpdateIdentity, asset: UpdateAsset) = UpdateRelease(
        identity.packageName, "qa", identity.versionCode, "fixture", context.applicationInfo.minSdkVersion,
        null, mapOf("ar" to "اختبار", "en" to "Fixture"), listOf(asset), null)

    private fun rejects(error: UpdateError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: UpdateException) { assertEquals(error, e.code) }
    }
}
