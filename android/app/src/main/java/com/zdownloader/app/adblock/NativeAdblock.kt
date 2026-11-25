package com.zdownloader.app.adblock

internal object NativeAdblock {
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("mediaflow_adblock")
            loaded = true
        }
    }

    external fun nativeCreateFromLists(easylist: ByteArray, easyprivacy: ByteArray): Long
    external fun nativeCreateFromCompiled(compiled: ByteArray, statsJson: String): Long
    external fun nativeSerialize(handle: Long): ByteArray?
    external fun nativeStats(handle: Long): String?
    external fun nativeShouldBlock(
        handle: Long,
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): Boolean
    external fun nativeShouldBlockDetailed(
        handle: Long,
        url: String,
        sourceUrl: String,
        requestType: String,
        method: String
    ): String?
    external fun nativeCosmeticResources(handle: Long, url: String): String?
    external fun nativeHiddenSelectors(
        handle: Long,
        url: String,
        classesJson: String,
        idsJson: String,
        exceptionsJson: String
    ): String?
    external fun nativeDestroy(handle: Long)
}
