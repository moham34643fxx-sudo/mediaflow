package com.zdownloader.app.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class InstalledUpdateIdentity(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

/** Installed identity is injectable for an A->B fixture test without replacing the test host. */
class UpdateApkVerifier(
    private val context: Context,
    private val installed: () -> InstalledUpdateIdentity = { installedIdentity(context) },
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList()
) {
    fun verify(file: File, release: UpdateRelease, asset: UpdateAsset) {
        if (!file.isFile || file.length() != asset.bytes) throw UpdateException(UpdateError.SIZE)
        if (!sha256(file).equals(asset.sha256, true)) throw UpdateException(UpdateError.CHECKSUM)
        val current = installed()
        if (release.appId != current.packageName) throw UpdateException(UpdateError.PACKAGE)
        if (release.versionCode <= current.versionCode) throw UpdateException(UpdateError.VERSION)
        if (release.minSdk > sdk) throw UpdateException(UpdateError.INCOMPATIBLE_SDK)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: throw UpdateException(UpdateError.INVALID_APK)
        if (archive.packageName != release.appId) throw UpdateException(UpdateError.PACKAGE)
        if (version(archive) != release.versionCode || version(archive) <= current.versionCode)
            throw UpdateException(UpdateError.VERSION)
        val info = archive.applicationInfo ?: throw UpdateException(UpdateError.INVALID_APK)
        if (info.minSdkVersion != release.minSdk || info.minSdkVersion > sdk) throw UpdateException(UpdateError.INCOMPATIBLE_SDK)
        if (!archive.splitNames.isNullOrEmpty() || !info.splitSourceDirs.isNullOrEmpty()) throw UpdateException(UpdateError.INVALID_APK)
        val signers = signerSet(archive)
        val history=if(Build.VERSION.SDK_INT>=28&&archive.signingInfo?.hasMultipleSigners()!=true)
            archive.signingInfo?.signingCertificateHistory?.map {digest(it.toByteArray())}?.toSet().orEmpty() else emptySet()
        if (!SigningUpdatePolicy.compatible(current.signerSha256,signers,history,sdk,
                com.zdownloader.app.BuildConfig.PRODUCTION_SIGNER_SHA256,com.zdownloader.app.BuildConfig.LEGACY_SIGNER_SHA256))
            throw UpdateException(UpdateError.SIGNATURE)
        try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("classes.dex") == null || zip.getEntry("resources.arsc") == null)
                    throw UpdateException(UpdateError.INVALID_APK)
                val manifest = zip.getEntry("AndroidManifest.xml") ?: throw UpdateException(UpdateError.INVALID_APK)
                if (manifest.size !in 1..(4L * 1024 * 1024)) throw UpdateException(UpdateError.INVALID_APK)
                val manifestBytes = zip.getInputStream(manifest).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 4 * 1024 * 1024) throw UpdateException(UpdateError.INVALID_APK)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (requiresSplits(manifestBytes)) throw UpdateException(UpdateError.INVALID_APK)
                val nativeAbis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .map { it.split('/').getOrNull(1).orEmpty() }.toSet()
                if (nativeAbis.none { it in abis } || asset.abi != "universal" && asset.abi !in abis ||
                    asset.abi != "universal" && nativeAbis != setOf(asset.abi) ||
                    asset.abi == "universal" && !nativeAbis.containsAll(setOf("arm64-v8a", "x86_64")))
                    throw UpdateException(UpdateError.INCOMPATIBLE_ABI)
            }
        } catch (e: UpdateException) { throw e }
        catch (_: Exception) { throw UpdateException(UpdateError.INVALID_APK) }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun installedIdentity(context: Context): InstalledUpdateIdentity {
            val info = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
            return InstalledUpdateIdentity(info.packageName, version(info), signerSet(info))
        }

        @Suppress("DEPRECATION")
        private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        @Suppress("DEPRECATION")
        private fun signerSet(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { digest(it.toByteArray()) }?.toSet().orEmpty()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Read binary Android XML start tags and typed attributes, rather than trusting ZIP filenames.
         * A full-install base may still declare required feature splits; reject both forms. */
        internal fun requiresSplits(bytes: ByteArray): Boolean {
            val data = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            fun u16(at: Int) = data.getShort(at).toInt() and 0xffff
            fun i32(at: Int) = data.getInt(at)
            if (bytes.size < 8 || u16(0) != 3 || i32(4) != bytes.size) throw UpdateException(UpdateError.INVALID_APK)
            var strings = emptyList<String>()
            var cursor = u16(2)
            fun string(index: Int): String = strings.getOrNull(index) ?: throw UpdateException(UpdateError.INVALID_APK)
            while (cursor + 8 <= bytes.size) {
                val type = u16(cursor)
                val header = u16(cursor + 2)
                val size = i32(cursor + 4)
                if (size < header || header < 8 || size > bytes.size - cursor) throw UpdateException(UpdateError.INVALID_APK)
                if (type == 1) {
                    if (header < 28) throw UpdateException(UpdateError.INVALID_APK)
                    val count = i32(cursor + 8)
                    val utf8 = i32(cursor + 16) and 0x100 != 0
                    val start = cursor + i32(cursor + 20)
                    if (count !in 0..100000 || header.toLong() + count * 4L > size) throw UpdateException(UpdateError.INVALID_APK)
                    strings = (0 until count).map { index ->
                        var at = start + i32(cursor + header + index * 4)
                        fun length8(): Int { val first = bytes[at++].toInt() and 255; return if (first and 128 == 0) first else ((first and 127) shl 8) or (bytes[at++].toInt() and 255) }
                        if (utf8) {
                            length8(); val length = length8()
                            if (at < start || at.toLong() + length >= cursor.toLong() + size) throw UpdateException(UpdateError.INVALID_APK)
                            String(bytes, at, length, Charsets.UTF_8)
                        } else {
                            val first = u16(at); at += 2
                            val length = if (first and 0x8000 == 0) first else { val low = u16(at); at += 2; ((first and 0x7fff) shl 16) or low }
                            if (at < start || length < 0 || at.toLong() + length * 2L >= cursor.toLong() + size) throw UpdateException(UpdateError.INVALID_APK)
                            String(bytes, at, length * 2, Charsets.UTF_16LE)
                        }
                    }
                } else if (type == 0x102) {
                    if (header < 16 || size < 36) throw UpdateException(UpdateError.INVALID_APK)
                    val tag = string(i32(cursor + 20))
                    if (tag == "uses-split") return true
                    val attributeStart = u16(cursor + 24)
                    val attributeSize = u16(cursor + 26)
                    val count = u16(cursor + 28)
                    if (attributeSize < 20 || 16L + attributeStart + attributeSize.toLong() * count > size) throw UpdateException(UpdateError.INVALID_APK)
                    for (i in 0 until count) {
                        val at = cursor + 16 + attributeStart + i * attributeSize
                        val name = string(i32(at + 4))
                        val rawValue = i32(at + 8)
                        val valueType = bytes[at + 15].toInt() and 255
                        val text = if (rawValue != -1) string(rawValue) else if (valueType == 3) string(i32(at + 16)) else ""
                        if (tag == "manifest" && name in setOf("split", "configForSplit") && text.isNotEmpty()) return true
                        if (name == "isSplitRequired" && i32(at + 16) != 0) return true
                        if (name == "requiredSplitTypes" && text.isNotEmpty()) return true
                    }
                }
                cursor += size
            }
            if (cursor != bytes.size || strings.isEmpty()) throw UpdateException(UpdateError.INVALID_APK)
            return false
        }
    }
}
